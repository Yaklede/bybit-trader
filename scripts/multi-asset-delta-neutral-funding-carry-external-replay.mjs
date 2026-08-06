#!/usr/bin/env node

import { createHash } from "node:crypto";
import { createReadStream, rmSync } from "node:fs";
import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { DatabaseSync } from "node:sqlite";
import { dirname, resolve } from "node:path";
import { pathToFileURL } from "node:url";

import {
  normalizedMultiAssetEvidenceFingerprint,
} from "./multi-asset-delta-neutral-funding-carry-acquire.mjs";
import {
  loadFundingRatesBySymbol,
  loadPortfolioFrames,
} from "./multi-asset-delta-neutral-funding-carry-development-replay.mjs";
import {
  loadMultiAssetDeltaNeutralFundingCarryExternalProtocol,
} from "./multi-asset-delta-neutral-funding-carry-external-protocol.mjs";
import {
  evaluateMultiAssetDevelopmentGate,
  simulateMultiAssetFundingCarryCandidate,
} from "./lib/multi-asset-delta-neutral-funding-carry-research.mjs";

const DEFAULT_FREEZE =
  "config/bybit-multi-asset-delta-neutral-funding-carry-external-replay-freeze-v2.json";

export function parseArgs(argv) {
  const values = new Map();
  for (const argument of argv) {
    if (!argument.startsWith("--") || !argument.includes("=")) {
      throw new Error(`Invalid argument: ${argument}. Use --name=value.`);
    }
    const [name, ...rest] = argument.slice(2).split("=");
    if (!["freeze", "report", "trades"].includes(name)) {
      throw new Error(`Unsupported argument: --${name}.`);
    }
    if (values.has(name)) throw new Error(`Duplicate argument: --${name}.`);
    values.set(name, rest.join("="));
  }
  return {
    freeze: resolve(values.get("freeze") ?? DEFAULT_FREEZE),
    report: values.has("report") ? resolve(values.get("report")) : null,
    trades: values.has("trades") ? resolve(values.get("trades")) : null,
  };
}

export async function replayMultiAssetDeltaNeutralFundingCarryExternal(options) {
  const freeze = await loadExternalReplayFreeze(options.freeze);
  const repositoryRoot = resolve(dirname(options.freeze), "..");
  const loaded = await loadMultiAssetDeltaNeutralFundingCarryExternalProtocol(
    resolve(repositoryRoot, freeze.manifest.protocol.path),
  );
  if (loaded.sha256 !== freeze.manifest.protocol.sha256) {
    throw new Error("External replay protocol differs from the frozen manifest.");
  }
  const developmentProtocolBytes = await readFile(resolve(
    repositoryRoot,
    loaded.protocol.developmentProtocol.path,
  ));
  const developmentProtocol = JSON.parse(developmentProtocolBytes);
  if (sha256(developmentProtocolBytes) !== loaded.developmentProtocolSha256) {
    throw new Error("Development protocol changed after external declaration.");
  }
  const evaluationProtocol = buildExternalEvaluationProtocol(loaded.protocol, developmentProtocol);

  const receiptPath = resolve(repositoryRoot, freeze.manifest.acquisitionReceipt.path);
  const receiptBytes = await readFile(receiptPath);
  const receipt = JSON.parse(receiptBytes);
  validateExternalAcquisitionReceipt(receipt, loaded);
  if (sha256(receiptBytes) !== freeze.manifest.acquisitionReceipt.sha256) {
    throw new Error("External replay receipt differs from the frozen manifest.");
  }
  const snapshotPath = resolve(repositoryRoot, receipt.stageSnapshot);
  const actualSnapshotSha256 = await sha256File(snapshotPath);
  if (actualSnapshotSha256 !== receipt.stageSnapshotSha256) {
    throw new Error(`External snapshot hash mismatch: ${actualSnapshotSha256}.`);
  }

  const db = new DatabaseSync(snapshotPath, { readOnly: true });
  let framesBySymbol;
  let fundingRatesBySymbol;
  try {
    db.exec("PRAGMA query_only=ON");
    const metadata = db.prepare("SELECT * FROM multiAssetMetadata WHERE singleton=1").get();
    if (metadata?.protocol_sha256 !== loaded.sha256 ||
        metadata.parent_result_sha256 !== loaded.parentResultSha256 ||
        metadata.normalized_evidence_sha256 !== receipt.normalizedEvidenceSha256) {
      throw new Error("External snapshot metadata differs from the frozen receipt.");
    }
    const actualEvidenceSha256 = normalizedMultiAssetEvidenceFingerprint(db, evaluationProtocol);
    if (actualEvidenceSha256 !== receipt.normalizedEvidenceSha256) {
      throw new Error("External normalized evidence fingerprint changed.");
    }
    framesBySymbol = loadPortfolioFrames(db, evaluationProtocol);
    fundingRatesBySymbol = loadFundingRatesBySymbol(db, evaluationProtocol);
  } finally {
    db.close();
    rmSync(`${snapshotPath}-shm`, { force: true });
    rmSync(`${snapshotPath}-wal`, { force: true });
  }

  const metrics = simulateMultiAssetFundingCarryCandidate({
    candidate: loaded.protocol.selectedCandidate,
    framesBySymbol,
    fundingRatesBySymbol,
    protocol: evaluationProtocol,
  });
  const gate = evaluateMultiAssetDevelopmentGate(
    metrics,
    loaded.protocol.externalValidationGate,
  );
  const reportPath = options.report ?? resolve(
    repositoryRoot,
    `build/research/${loaded.protocol.protocolId}-result.json`,
  );
  const tradesPath = options.trades ?? resolve(
    repositoryRoot,
    `build/research/${loaded.protocol.protocolId}-trades.json`,
  );
  const tradesArtifact = {
    schemaVersion: 1,
    protocolId: loaded.protocol.protocolId,
    candidateId: loaded.protocol.selectedCandidate.id,
    candidateSha256: loaded.protocol.selectedCandidateSha256,
    trades: metrics.trades,
  };
  await writeJsonAtomic(tradesPath, tradesArtifact);
  const tradesSha256 = await sha256File(tradesPath);
  const report = {
    schemaVersion: 1,
    protocolId: loaded.protocol.protocolId,
    protocolSha256: loaded.sha256,
    replayFreezeSha256: freeze.sha256,
    parentInternalResultSha256: loaded.parentResultSha256,
    acquisitionReceiptSha256: sha256(receiptBytes),
    externalSnapshotSha256: actualSnapshotSha256,
    normalizedEvidenceSha256: receipt.normalizedEvidenceSha256,
    implementation: {
      replaySha256: freeze.manifest.implementation.replaySha256,
      simulatorSha256: freeze.manifest.implementation.simulatorSha256,
      compositeSha256: freeze.compositeImplementationSha256,
    },
    status: gate.passed
      ? "MULTI_ASSET_EXTERNAL_VALIDATION_PASSED_FOR_SEALED_ACQUISITION"
      : "REJECTED_MULTI_ASSET_EXTERNAL_VALIDATION",
    trialAccounting: {
      priorObservedCandidatesAndProtocols:
        loaded.protocol.trialAccounting.priorObservedCandidatesAndProtocols,
      evaluatedCandidates: 1,
      cumulativeObservedCandidatesAndProtocols:
        loaded.protocol.trialAccounting.cumulativeObservedCandidatesAndProtocolsAfterExternal,
      retunedCandidates: 0,
    },
    evidence: {
      symbols: evaluationProtocol.sourceData.symbols,
      frameCountBySymbol: Object.fromEntries(
        evaluationProtocol.sourceData.symbols.map((symbol) => [symbol, framesBySymbol[symbol].length]),
      ),
      fundingSettlementCountBySymbol: Object.fromEntries(
        evaluationProtocol.sourceData.symbols.map(
          (symbol) => [symbol, fundingRatesBySymbol[symbol].length],
        ),
      ),
      stageStart: evaluationProtocol.sourceData.developmentStart,
      stageEndExclusive: evaluationProtocol.sourceData.developmentEndExclusive,
    },
    candidate: loaded.protocol.selectedCandidate,
    candidateSha256: loaded.protocol.selectedCandidateSha256,
    metrics: withoutTrades(metrics),
    gate,
    tradesSha256,
    evidenceBoundary: {
      development2023Read: true,
      development2024Read: true,
      external2025Read: true,
      sealed2026Read: false,
      freshForwardSealRead: false,
    },
    sealed2026AcquisitionAllowed: gate.passed,
    automaticExecutionAllowed: false,
    liveExecutionAllowed: false,
  };
  await writeJsonAtomic(reportPath, report);
  return { report, reportPath, tradesPath };
}

export function buildExternalEvaluationProtocol(externalProtocol, developmentProtocol) {
  return {
    ...developmentProtocol,
    protocolId: externalProtocol.protocolId,
    sourceData: {
      ...developmentProtocol.sourceData,
      ...externalProtocol.sourceData,
      developmentStart: externalProtocol.sourceData.stageStart,
      developmentEndExclusive: externalProtocol.sourceData.stageEndExclusive,
    },
    evidenceSchedule: {
      ...developmentProtocol.evidenceSchedule,
      developmentBlocks: externalProtocol.externalValidationBlocks,
    },
    developmentGate: externalProtocol.externalValidationGate,
    internalValidationGate: externalProtocol.externalValidationGate,
  };
}

export async function loadExternalReplayFreeze(path) {
  const bytes = await readFile(path);
  const manifest = JSON.parse(bytes);
  const repositoryRoot = resolve(dirname(path), "..");
  const actual = {
    protocolSha256: await sha256File(resolve(repositoryRoot, manifest.protocol.path)),
    acquisitionReceiptSha256: await sha256File(
      resolve(repositoryRoot, manifest.acquisitionReceipt.path),
    ),
    simulatorSha256: await sha256File(resolve(repositoryRoot, manifest.implementation.simulatorPath)),
    replaySha256: await sha256File(resolve(repositoryRoot, manifest.implementation.replayPath)),
  };
  validateExternalReplayFreeze(manifest, actual);
  const compositeImplementationSha256 = sha256(
    `${actual.simulatorSha256}\0${actual.replaySha256}`,
  );
  if (manifest.implementation.compositeSha256 !== compositeImplementationSha256) {
    throw new Error("External replay composite implementation hash changed.");
  }
  return { manifest, sha256: sha256(bytes), compositeImplementationSha256 };
}

export function validateExternalReplayFreeze(manifest, actual) {
  if (manifest?.freezeId !==
      "bybit-multi-asset-delta-neutral-funding-carry-external-replay-freeze-v2" ||
      manifest.status !== "FROZEN_BEFORE_MULTI_ASSET_EXTERNAL_OUTCOME_REPLAY" ||
      manifest.outcomeBoundary?.externalPortfolioMetricsReadBeforeFreeze !== false ||
      manifest.outcomeBoundary.sealed2026Read !== false ||
      manifest.outcomeBoundary.freshForwardSealRead !== false ||
      manifest.candidatePolicy?.evaluatedCandidateCount !== 1 ||
      manifest.candidatePolicy.candidateMayBeRetunedAfterOutcome !== false ||
      manifest.gatePolicy?.gateMayBeChangedAfterOutcome !== false ||
      manifest.automaticExecutionAllowed !== false || manifest.liveExecutionAllowed !== false) {
    throw new Error("External replay freeze boundary changed.");
  }
  if (manifest.protocol?.sha256 !== actual.protocolSha256 ||
      manifest.acquisitionReceipt?.sha256 !== actual.acquisitionReceiptSha256 ||
      manifest.implementation?.simulatorSha256 !== actual.simulatorSha256 ||
      manifest.implementation?.replaySha256 !== actual.replaySha256) {
    throw new Error("External replay freeze hashes do not match the implementation or evidence.");
  }
  return manifest;
}

export function validateExternalAcquisitionReceipt(receipt, loadedProtocol) {
  if (receipt?.status !== "COMPLETE_MULTI_ASSET_EXTERNAL_EVIDENCE_SEALED" ||
      receipt.stage !== "external" || receipt.protocolSha256 !== loadedProtocol.sha256 ||
      receipt.parentInternalResultSha256 !== loadedProtocol.parentResultSha256 ||
      receipt.selectedCandidateSha256 !== loadedProtocol.protocol.selectedCandidateSha256 ||
      receipt.simulatorSha256 !== loadedProtocol.simulatorSha256 ||
      !isSha256(receipt.stageSnapshotSha256) || !isSha256(receipt.normalizedEvidenceSha256) ||
      receipt.coverage?.symbolCount !== 3 || receipt.coverage.seriesPerSymbol !== 4 ||
      receipt.coverage.expectedM5RowsPerSeries !== 105120 ||
      receipt.coverage.matchingM5RowsPerSymbol !== 105120 ||
      receipt.coverage.totalMatchingM5Rows !== 315360 ||
      receipt.coverage.fundingRowsPerSymbol !== 1095 || receipt.coverage.totalFundingRows !== 3285 ||
      receipt.coverage.missingDecisionInputCount !== 0 ||
      Object.values(receipt.integrity ?? {}).some((value) => value !== true) ||
      receipt.outcomeBoundary?.external2025PortfolioMetricsReadBeforeReceipt !== false ||
      receipt.outcomeBoundary.sealed2026Read !== false ||
      receipt.outcomeBoundary.freshForwardSealRead !== false ||
      receipt.externalEvaluationAllowed !== true || receipt.automaticExecutionAllowed !== false ||
      receipt.liveExecutionAllowed !== false) {
    throw new Error("Multi-asset external receipt is not eligible for replay.");
  }
  return receipt;
}

function withoutTrades(metrics) {
  const { trades, ...summary } = metrics;
  return summary;
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
  replayMultiAssetDeltaNeutralFundingCarryExternal(parseArgs(process.argv.slice(2)))
    .then(({ report }) => console.log(JSON.stringify(report, null, 2)))
    .catch((error) => {
      console.error(error.stack ?? error.message);
      process.exitCode = 1;
    });
}
