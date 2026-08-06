#!/usr/bin/env node

import { createHash } from "node:crypto";
import { createReadStream, rmSync } from "node:fs";
import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { DatabaseSync } from "node:sqlite";
import { dirname, resolve } from "node:path";
import { pathToFileURL } from "node:url";

import { normalizedDeltaNeutralEvidenceFingerprint } from "./delta-neutral-funding-carry-acquire.mjs";
import { loadFundingRates, loadMarketFrames } from "./delta-neutral-funding-carry-development-replay.mjs";
import { internalStageProtocol } from "./delta-neutral-funding-carry-internal-acquire.mjs";
import { loadDeltaNeutralFundingCarryInternalProtocol } from "./delta-neutral-funding-carry-internal-protocol.mjs";
import {
  evaluateDevelopmentGate,
  simulateFundingCarryCandidate,
} from "./lib/delta-neutral-funding-carry-research.mjs";

const DEFAULT_PROTOCOL = "config/bybit-delta-neutral-funding-carry-internal-v1.json";
const DEFAULT_RECEIPT = "config/bybit-delta-neutral-funding-carry-internal-acquisition-receipt-v1.json";

export function parseArgs(argv) {
  const values = new Map();
  for (const argument of argv) {
    if (!argument.startsWith("--") || !argument.includes("=")) {
      throw new Error(`Invalid argument: ${argument}. Use --name=value.`);
    }
    const [name, ...rest] = argument.slice(2).split("=");
    if (!["protocol", "receipt", "report", "trades"].includes(name)) {
      throw new Error(`Unsupported argument: --${name}.`);
    }
    if (values.has(name)) throw new Error(`Duplicate argument: --${name}.`);
    values.set(name, rest.join("="));
  }
  return {
    protocol: resolve(values.get("protocol") ?? DEFAULT_PROTOCOL),
    receipt: resolve(values.get("receipt") ?? DEFAULT_RECEIPT),
    report: values.has("report") ? resolve(values.get("report")) : null,
    trades: values.has("trades") ? resolve(values.get("trades")) : null,
  };
}

export async function replayDeltaNeutralFundingCarryInternal(options) {
  const loaded = await loadDeltaNeutralFundingCarryInternalProtocol(options.protocol);
  const protocol = loaded.protocol;
  const repositoryRoot = resolve(dirname(options.protocol), "..");
  const receiptBytes = await readFile(options.receipt);
  const receipt = JSON.parse(receiptBytes);
  validateInternalAcquisitionReceipt(receipt, loaded);
  const developmentProtocolBytes = await readFile(resolve(repositoryRoot, protocol.developmentProtocol.path));
  if (sha256(developmentProtocolBytes) !== loaded.developmentProtocolSha256) {
    throw new Error("Original delta-neutral development protocol changed before internal replay.");
  }
  const developmentProtocol = JSON.parse(developmentProtocolBytes);
  const stageProtocol = internalStageProtocol(protocol);
  const evaluationProtocol = {
    executionContract: developmentProtocol.executionContract,
    statistics: developmentProtocol.statistics,
    evidenceSchedule: { developmentBlocks: protocol.internalValidationBlocks },
  };
  const snapshotPath = resolve(repositoryRoot, receipt.stageSnapshot);
  const actualSnapshotSha256 = await sha256File(snapshotPath);
  if (actualSnapshotSha256 !== receipt.stageSnapshotSha256) {
    throw new Error(`Delta-neutral internal snapshot hash mismatch: ${actualSnapshotSha256}.`);
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
      throw new Error("Delta-neutral internal snapshot metadata differs from the frozen receipt.");
    }
    if (normalizedDeltaNeutralEvidenceFingerprint(db, stageProtocol) !== receipt.normalizedEvidenceSha256) {
      throw new Error("Delta-neutral internal normalized evidence fingerprint changed.");
    }
    frames = loadMarketFrames(db, stageProtocol);
    fundingRates = loadFundingRates(db, stageProtocol);
  } finally {
    db.close();
    rmSync(`${snapshotPath}-shm`, { force: true });
    rmSync(`${snapshotPath}-wal`, { force: true });
  }

  const metrics = simulateFundingCarryCandidate({
    candidate: protocol.selectedCandidate,
    frames,
    fundingRates,
    protocol: evaluationProtocol,
  });
  const gate = evaluateDevelopmentGate(metrics, protocol.internalValidationGate);
  const reportPath = options.report ?? resolve(
    repositoryRoot,
    `build/research/${protocol.protocolId}-result.json`,
  );
  const tradesPath = options.trades ?? resolve(
    repositoryRoot,
    `build/research/${protocol.protocolId}-trades.json`,
  );
  await writeJsonAtomic(tradesPath, {
    schemaVersion: 1,
    protocolId: protocol.protocolId,
    candidateId: protocol.selectedCandidate.id,
    trades: metrics.trades,
  });
  const tradesSha256 = await sha256File(tradesPath);
  const { trades, ...metricsSummary } = metrics;
  const report = {
    schemaVersion: 1,
    protocolId: protocol.protocolId,
    protocolSha256: loaded.sha256,
    parentDevelopmentResultSha256: loaded.parentResultSha256,
    acquisitionReceiptSha256: sha256(receiptBytes),
    internalSnapshotSha256: actualSnapshotSha256,
    normalizedEvidenceSha256: receipt.normalizedEvidenceSha256,
    frozenCandidateSha256: protocol.selectedCandidateSha256,
    researchLibrarySha256: loaded.researchLibrarySha256,
    implementationSha256: await implementationFingerprint(repositoryRoot),
    status: gate.passed
      ? "INTERNAL_VALIDATION_PASSED_EXTERNAL_2025_ACQUISITION_ALLOWED"
      : "INTERNAL_VALIDATION_REJECTED_EXTERNAL_EVIDENCE_LOCKED",
    trialAccounting: {
      priorObservedCandidates: protocol.trialAccounting.priorObservedCandidates,
      evaluatedCandidates: 1,
      newCandidateCount: 0,
      cumulativeObservedCandidates: protocol.trialAccounting.cumulativeCandidateCountAfterInternalValidation,
    },
    evidence: {
      frameCount: frames.length,
      fundingSettlementCount: fundingRates.length,
      startAt: protocol.sourceData.stageStart,
      endExclusive: protocol.sourceData.stageEndExclusive,
    },
    candidate: protocol.selectedCandidate,
    metrics: metricsSummary,
    gate,
    tradesSha256,
    evidenceBoundary: {
      development2023Read: true,
      internalValidation2024Read: true,
      external2025Read: false,
      sealed2026Read: false,
      freshForwardSealRead: false,
    },
    external2025AcquisitionAllowed: gate.passed,
    automaticExecutionAllowed: false,
    liveExecutionAllowed: false,
  };
  await writeJsonAtomic(reportPath, report);
  return { report, reportPath, tradesPath };
}

export function validateInternalAcquisitionReceipt(receipt, loadedProtocol) {
  if (receipt?.status !== "COMPLETE_DELTA_NEUTRAL_INTERNAL_EVIDENCE_SEALED" ||
      receipt.stage !== "internal" || receipt.protocolSha256 !== loadedProtocol.sha256 ||
      receipt.parentDevelopmentResultSha256 !== loadedProtocol.parentResultSha256 ||
      receipt.frozenCandidateSha256 !== loadedProtocol.protocol.selectedCandidateSha256 ||
      receipt.researchLibrarySha256 !== loadedProtocol.researchLibrarySha256 ||
      !isSha256(receipt.stageSnapshotSha256) || !isSha256(receipt.normalizedEvidenceSha256) ||
      receipt.coverage?.matchingM5Rows !== 105408 || receipt.coverage.fundingRows !== 1098 ||
      receipt.coverage.missingDecisionInputCount !== 0 ||
      Object.values(receipt.integrity ?? {}).some((value) => value !== true) ||
      Object.values(receipt.lockedEvidence ?? {}).some((value) => value !== false) ||
      receipt.internalEvaluationAllowed !== true || receipt.automaticExecutionAllowed !== false ||
      receipt.liveExecutionAllowed !== false) {
    throw new Error("Delta-neutral internal receipt is not eligible for replay.");
  }
  return receipt;
}

async function implementationFingerprint(repositoryRoot) {
  const hash = createHash("sha256");
  for (const relativePath of [
    "scripts/delta-neutral-funding-carry-internal-replay.mjs",
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
  replayDeltaNeutralFundingCarryInternal(parseArgs(process.argv.slice(2)))
    .then(({ report }) => console.log(JSON.stringify(report, null, 2)))
    .catch((error) => {
      console.error(error.stack ?? error.message);
      process.exitCode = 1;
    });
}
