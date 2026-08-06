import assert from "node:assert/strict";
import test from "node:test";

import {
  buildExternalEvaluationProtocol,
  parseArgs,
  validateExternalAcquisitionReceipt,
  validateExternalReplayFreeze,
} from "./multi-asset-delta-neutral-funding-carry-external-replay.mjs";

test("external replay arguments cannot change candidate, gate, or year", () => {
  const parsed = parseArgs(["--freeze=config/freeze.json", "--report=build/report.json"]);
  assert.match(parsed.freeze, /config\/freeze\.json$/);
  assert.throws(() => parseArgs(["--candidate=other"]), /Unsupported argument/);
  assert.throws(() => parseArgs(["--minimum-trades=1"]), /Unsupported argument/);
  assert.throws(() => parseArgs(["--start=2024-01-01"]), /Unsupported argument/);
});

test("external evaluation preserves execution contract and swaps only stage metadata", () => {
  const development = {
    protocolId: "development",
    sourceData: { symbols: ["BTCUSDT", "ETHUSDT", "SOLUSDT"] },
    evidenceSchedule: { developmentBlocks: [{ id: "D01" }], other: true },
    executionContract: { startingEquityUsdt: 660 },
    statistics: { bootstrapSamples: 10000 },
  };
  const external = {
    protocolId: "external",
    sourceData: {
      symbols: development.sourceData.symbols,
      stageStart: "2025-01-01T00:00:00Z",
      stageEndExclusive: "2026-01-01T00:00:00Z",
    },
    externalValidationBlocks: [{ id: "E01" }],
    externalValidationGate: { minimumClosedPositions: 12 },
  };
  const result = buildExternalEvaluationProtocol(external, development);
  assert.equal(result.sourceData.developmentStart, external.sourceData.stageStart);
  assert.equal(result.sourceData.developmentEndExclusive, external.sourceData.stageEndExclusive);
  assert.deepEqual(result.evidenceSchedule.developmentBlocks, external.externalValidationBlocks);
  assert.deepEqual(result.executionContract, development.executionContract);
  assert.deepEqual(result.statistics, development.statistics);
});

test("freeze rejects read outcomes, retuning, and changed hashes", () => {
  const hash = "a".repeat(64);
  const manifest = {
    freezeId: "bybit-multi-asset-delta-neutral-funding-carry-external-replay-freeze-v2",
    status: "FROZEN_BEFORE_MULTI_ASSET_EXTERNAL_OUTCOME_REPLAY",
    protocol: { sha256: hash },
    acquisitionReceipt: { sha256: hash },
    implementation: { simulatorSha256: hash, replaySha256: hash },
    outcomeBoundary: {
      externalPortfolioMetricsReadBeforeFreeze: false,
      sealed2026Read: false,
      freshForwardSealRead: false,
    },
    candidatePolicy: { evaluatedCandidateCount: 1, candidateMayBeRetunedAfterOutcome: false },
    gatePolicy: { gateMayBeChangedAfterOutcome: false },
    automaticExecutionAllowed: false,
    liveExecutionAllowed: false,
  };
  const actual = {
    protocolSha256: hash,
    acquisitionReceiptSha256: hash,
    simulatorSha256: hash,
    replaySha256: hash,
  };
  assert.doesNotThrow(() => validateExternalReplayFreeze(manifest, actual));
  assert.throws(() => validateExternalReplayFreeze({
    ...manifest,
    outcomeBoundary: { ...manifest.outcomeBoundary, externalPortfolioMetricsReadBeforeFreeze: true },
  }, actual), /boundary changed/);
});

test("receipt validation requires exact 2025 evidence and fixed candidate", () => {
  const loaded = {
    sha256: "a".repeat(64),
    parentResultSha256: "b".repeat(64),
    simulatorSha256: "c".repeat(64),
    protocol: { selectedCandidateSha256: "d".repeat(64) },
  };
  const receipt = {
    status: "COMPLETE_MULTI_ASSET_EXTERNAL_EVIDENCE_SEALED",
    stage: "external",
    protocolSha256: loaded.sha256,
    parentInternalResultSha256: loaded.parentResultSha256,
    selectedCandidateSha256: loaded.protocol.selectedCandidateSha256,
    simulatorSha256: loaded.simulatorSha256,
    stageSnapshotSha256: "e".repeat(64),
    normalizedEvidenceSha256: "f".repeat(64),
    coverage: {
      symbolCount: 3,
      seriesPerSymbol: 4,
      expectedM5RowsPerSeries: 105120,
      matchingM5RowsPerSymbol: 105120,
      totalMatchingM5Rows: 315360,
      fundingRowsPerSymbol: 1095,
      totalFundingRows: 3285,
      missingDecisionInputCount: 0,
    },
    integrity: { complete: true },
    outcomeBoundary: {
      external2025PortfolioMetricsReadBeforeReceipt: false,
      sealed2026Read: false,
      freshForwardSealRead: false,
    },
    externalEvaluationAllowed: true,
    automaticExecutionAllowed: false,
    liveExecutionAllowed: false,
  };
  assert.doesNotThrow(() => validateExternalAcquisitionReceipt(receipt, loaded));
  assert.throws(() => validateExternalAcquisitionReceipt({
    ...receipt,
    coverage: { ...receipt.coverage, totalFundingRows: 1 },
  }, loaded), /not eligible/);
});
