#!/usr/bin/env node

import { createHash } from "node:crypto";
import { createReadStream, rmSync } from "node:fs";
import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { DatabaseSync } from "node:sqlite";
import { dirname, resolve } from "node:path";
import { pathToFileURL } from "node:url";

import { normalizedDeltaNeutralEvidenceFingerprint } from "./delta-neutral-funding-carry-acquire.mjs";
import {
  expandDeltaNeutralFundingCarryCandidates,
  loadDeltaNeutralFundingCarryProtocol,
} from "./delta-neutral-funding-carry-protocol.mjs";
import {
  evaluateDevelopmentGate,
  simulateFundingCarryCandidate,
} from "./lib/delta-neutral-funding-carry-research.mjs";

const DEFAULT_PROTOCOL = "config/bybit-delta-neutral-funding-carry-development-v1.json";
const DEFAULT_RECEIPT = "config/bybit-delta-neutral-funding-carry-development-acquisition-receipt-v1.json";

export function parseArgs(argv) {
  const values = new Map();
  for (const argument of argv) {
    if (!argument.startsWith("--") || !argument.includes("=")) {
      throw new Error(`Invalid argument: ${argument}. Use --name=value.`);
    }
    const [name, ...rest] = argument.slice(2).split("=");
    if (!["protocol", "receipt", "report", "ranked", "trades"].includes(name)) {
      throw new Error(`Unsupported argument: --${name}.`);
    }
    if (values.has(name)) throw new Error(`Duplicate argument: --${name}.`);
    values.set(name, rest.join("="));
  }
  return {
    protocol: resolve(values.get("protocol") ?? DEFAULT_PROTOCOL),
    receipt: resolve(values.get("receipt") ?? DEFAULT_RECEIPT),
    report: values.has("report") ? resolve(values.get("report")) : null,
    ranked: values.has("ranked") ? resolve(values.get("ranked")) : null,
    trades: values.has("trades") ? resolve(values.get("trades")) : null,
  };
}

export async function replayDeltaNeutralFundingCarryDevelopment(options) {
  const loaded = await loadDeltaNeutralFundingCarryProtocol(options.protocol);
  const protocol = loaded.protocol;
  const repositoryRoot = resolve(dirname(options.protocol), "..");
  const receiptBytes = await readFile(options.receipt);
  const receipt = JSON.parse(receiptBytes);
  validateAcquisitionReceipt(receipt, loaded.sha256);
  const snapshotPath = resolve(repositoryRoot, receipt.stageSnapshot);
  const actualSnapshotSha256 = await sha256File(snapshotPath);
  if (actualSnapshotSha256 !== receipt.stageSnapshotSha256) {
    throw new Error(`Delta-neutral snapshot hash mismatch: ${actualSnapshotSha256}.`);
  }

  const db = new DatabaseSync(snapshotPath, { readOnly: true });
  let frames;
  let fundingRates;
  try {
    db.exec("PRAGMA query_only=ON");
    const metadata = db.prepare("SELECT * FROM deltaNeutralMetadata WHERE singleton=1").get();
    if (metadata?.protocol_sha256 !== loaded.sha256 ||
        metadata.parent_result_sha256 !== loaded.parentResultSha256 ||
        metadata.normalized_evidence_sha256 !== receipt.normalizedEvidenceSha256) {
      throw new Error("Delta-neutral snapshot metadata differs from the frozen receipt.");
    }
    const actualEvidenceSha256 = normalizedDeltaNeutralEvidenceFingerprint(db, protocol);
    if (actualEvidenceSha256 !== receipt.normalizedEvidenceSha256) {
      throw new Error("Delta-neutral normalized evidence fingerprint changed.");
    }
    frames = loadMarketFrames(db, protocol);
    fundingRates = loadFundingRates(db, protocol);
  } finally {
    db.close();
    rmSync(`${snapshotPath}-shm`, { force: true });
    rmSync(`${snapshotPath}-wal`, { force: true });
  }

  const candidates = expandDeltaNeutralFundingCarryCandidates(protocol);
  const evaluations = [];
  for (const candidate of candidates) {
    const metrics = simulateFundingCarryCandidate({ candidate, frames, fundingRates, protocol });
    const gate = evaluateDevelopmentGate(metrics, protocol.developmentGate);
    evaluations.push({ candidate, metrics, gate });
  }
  const ranked = rankCandidateEvaluations(evaluations);
  const passed = ranked.filter((evaluation) => evaluation.gate.passed);
  const selected = passed.slice(0, 1);
  const best = ranked[0];
  const reportPath = options.report ?? resolve(
    repositoryRoot,
    `build/research/${protocol.protocolId}-result.json`,
  );
  const rankedPath = options.ranked ?? resolve(
    repositoryRoot,
    `build/research/${protocol.protocolId}-ranked-candidates.json`,
  );
  const tradesPath = options.trades ?? resolve(
    repositoryRoot,
    `build/research/${protocol.protocolId}-best-trades.json`,
  );
  const rankedArtifact = ranked.map((evaluation, index) => ({
    rank: index + 1,
    candidate: evaluation.candidate,
    metrics: withoutTrades(evaluation.metrics),
    gate: evaluation.gate,
  }));
  const tradesArtifact = {
    schemaVersion: 1,
    protocolId: protocol.protocolId,
    candidateId: best?.candidate.id ?? null,
    trades: best?.metrics.trades ?? [],
  };
  await writeJsonAtomic(rankedPath, rankedArtifact);
  await writeJsonAtomic(tradesPath, tradesArtifact);
  const rankedCandidatesSha256 = await sha256File(rankedPath);
  const bestCandidateTradesSha256 = await sha256File(tradesPath);
  const implementationSha256 = await replayImplementationFingerprint(repositoryRoot);
  const report = {
    schemaVersion: 1,
    protocolId: protocol.protocolId,
    protocolSha256: loaded.sha256,
    parentResultSha256: loaded.parentResultSha256,
    acquisitionReceiptSha256: sha256(receiptBytes),
    developmentSnapshotSha256: actualSnapshotSha256,
    normalizedEvidenceSha256: receipt.normalizedEvidenceSha256,
    implementationSha256,
    status: selected.length === 1
      ? "DEVELOPMENT_CANDIDATE_FROZEN_FOR_INTERNAL_VALIDATION"
      : "REJECTED_NO_DELTA_NEUTRAL_DEVELOPMENT_CANDIDATE",
    trialAccounting: {
      priorObservedCandidates: protocol.trialAccounting.priorObservedCandidates,
      evaluatedCandidates: candidates.length,
      cumulativeObservedCandidates: protocol.trialAccounting.cumulativeCandidateCountAfterReplay,
      passedCandidates: passed.length,
    },
    evidence: {
      frameCount: frames.length,
      fundingSettlementCount: fundingRates.length,
      developmentStart: protocol.sourceData.developmentStart,
      developmentEndExclusive: protocol.sourceData.developmentEndExclusive,
    },
    bestCandidate: best == null ? null : {
      candidate: best.candidate,
      metrics: withoutTrades(best.metrics),
      gate: best.gate,
    },
    selectedCandidateIds: selected.map((evaluation) => evaluation.candidate.id),
    rankedCandidatesSha256,
    bestCandidateTradesSha256,
    evidenceBoundary: {
      development2023Read: true,
      internalValidation2024Read: false,
      external2025Read: false,
      sealed2026Read: false,
      freshForwardSealRead: false,
    },
    internalValidationAcquisitionAllowed: selected.length === 1,
    automaticExecutionAllowed: false,
    liveExecutionAllowed: false,
  };
  await writeJsonAtomic(reportPath, report);
  return { report, reportPath, rankedPath, tradesPath };
}

export function validateAcquisitionReceipt(receipt, protocolSha256) {
  if (receipt?.status !== "COMPLETE_DELTA_NEUTRAL_DEVELOPMENT_EVIDENCE_SEALED" ||
      receipt.stage !== "development" || receipt.protocolSha256 !== protocolSha256 ||
      !isSha256(receipt.stageSnapshotSha256) || !isSha256(receipt.normalizedEvidenceSha256) ||
      receipt.coverage?.matchingM5Rows !== 105120 || receipt.coverage.fundingRows !== 1095 ||
      receipt.coverage.missingDecisionInputCount !== 0 ||
      Object.values(receipt.integrity ?? {}).some((value) => value !== true) ||
      Object.values(receipt.lockedEvidence ?? {}).some((value) => value !== false) ||
      receipt.developmentEvaluationAllowed !== true || receipt.automaticExecutionAllowed !== false ||
      receipt.liveExecutionAllowed !== false) {
    throw new Error("Delta-neutral acquisition receipt is not eligible for development replay.");
  }
  return receipt;
}

export function rankCandidateEvaluations(evaluations) {
  return [...evaluations].sort((left, right) => {
    if (left.gate.passed !== right.gate.passed) return left.gate.passed ? -1 : 1;
    if (left.gate.failedChecks.length !== right.gate.failedChecks.length) {
      return left.gate.failedChecks.length - right.gate.failedChecks.length;
    }
    if (left.gate.minimumGateMargin !== right.gate.minimumGateMargin) {
      return right.gate.minimumGateMargin - left.gate.minimumGateMargin;
    }
    if (left.metrics.costStressNetReturnPct !== right.metrics.costStressNetReturnPct) {
      return right.metrics.costStressNetReturnPct - left.metrics.costStressNetReturnPct;
    }
    if (left.metrics.bootstrapLowerMeanDailyReturnPct !== right.metrics.bootstrapLowerMeanDailyReturnPct) {
      return right.metrics.bootstrapLowerMeanDailyReturnPct - left.metrics.bootstrapLowerMeanDailyReturnPct;
    }
    return left.candidate.id.localeCompare(right.candidate.id);
  });
}

export function loadMarketFrames(db, protocol) {
  const frames = [];
  let cursor = "";
  while (true) {
    const rows = db.prepare(`
      SELECT spot.opened_at,
        spot.open spot_open,spot.high spot_high,spot.low spot_low,spot.close spot_close,
        perpetual.open perpetual_open,perpetual.high perpetual_high,
        perpetual.low perpetual_low,perpetual.close perpetual_close,
        mark.open mark_open,mark.high mark_high,mark.low mark_low,mark.close mark_close,
        idx.open index_open,idx.high index_high,idx.low index_low,idx.close index_close
      FROM marketBars spot
      JOIN marketBars perpetual ON perpetual.series='PERPETUAL_LAST' AND perpetual.opened_at=spot.opened_at
      JOIN marketBars mark ON mark.series='PERPETUAL_MARK' AND mark.opened_at=spot.opened_at
      JOIN marketBars idx ON idx.series='PERPETUAL_INDEX' AND idx.opened_at=spot.opened_at
      WHERE spot.series='SPOT_LAST' AND spot.opened_at>? AND spot.opened_at>=? AND spot.opened_at<?
      ORDER BY spot.opened_at LIMIT ?
    `).all(cursor, protocol.sourceData.developmentStart, protocol.sourceData.developmentEndExclusive, 10_000);
    for (const row of rows) frames.push(marketFrame(row));
    if (rows.length < 10_000) break;
    cursor = rows.at(-1).opened_at;
  }
  return frames;
}

export function loadFundingRates(db, protocol) {
  return db.prepare(`
    SELECT timestamp,funding_rate FROM fundingRates
    WHERE symbol=? AND timestamp>=? AND timestamp<? ORDER BY timestamp
  `).all(
    protocol.sourceData.perpetualSymbol,
    protocol.sourceData.developmentStart,
    protocol.sourceData.developmentEndExclusive,
  ).map((row) => ({ timestamp: Date.parse(row.timestamp), rate: Number(row.funding_rate) }));
}

function marketFrame(row) {
  return {
    timestamp: Date.parse(row.opened_at),
    spot: candle(row, "spot"),
    perpetual: candle(row, "perpetual"),
    mark: candle(row, "mark"),
    index: candle(row, "index"),
  };
}

function candle(row, prefix) {
  const result = {};
  for (const name of ["open", "high", "low", "close"]) {
    const value = Number(row[`${prefix}_${name}`]);
    if (!Number.isFinite(value) || value <= 0) throw new Error(`Invalid ${prefix} ${name} at ${row.opened_at}.`);
    result[name] = value;
  }
  return result;
}

function withoutTrades(metrics) {
  const { trades, ...summary } = metrics;
  return summary;
}

async function replayImplementationFingerprint(repositoryRoot) {
  const hash = createHash("sha256");
  for (const relativePath of [
    "scripts/delta-neutral-funding-carry-development-replay.mjs",
    "scripts/lib/delta-neutral-funding-carry-research.mjs",
  ]) {
    hash.update(relativePath);
    hash.update("\0");
    hash.update(await readFile(resolve(repositoryRoot, relativePath)));
    hash.update("\0");
  }
  return hash.digest("hex");
}

async function writeJsonAtomic(path, value) {
  await mkdir(dirname(path), { recursive: true });
  const temporaryPath = `${path}.tmp-${process.pid}`;
  await writeFile(temporaryPath, `${JSON.stringify(value, null, 2)}\n`);
  await rename(temporaryPath, path);
}

async function sha256File(path) {
  const hash = createHash("sha256");
  for await (const chunk of createReadStream(path)) hash.update(chunk);
  return hash.digest("hex");
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

function isSha256(value) {
  return typeof value === "string" && /^[a-f0-9]{64}$/.test(value);
}

const invokedPath = process.argv[1] == null ? null : pathToFileURL(resolve(process.argv[1])).href;
if (invokedPath === import.meta.url) {
  replayDeltaNeutralFundingCarryDevelopment(parseArgs(process.argv.slice(2)))
    .then(({ report }) => {
      console.log(JSON.stringify(report, null, 2));
    })
    .catch((error) => {
      console.error(error.stack ?? error.message);
      process.exitCode = 1;
    });
}
