import assert from "node:assert/strict";
import test from "node:test";

import {
  buildInternalEvaluationProtocol,
  parseArgs,
  validateInternalAcquisitionReceipt,
  validateInternalReplayFreeze,
} from "./multi-asset-delta-neutral-funding-carry-internal-replay.mjs";

test("internal replay arguments cannot change candidate, gate, or evidence", () => {
  const parsed = parseArgs(["--freeze=config/freeze.json", "--report=build/report.json"]);
  assert.match(parsed.freeze, /config\/freeze\.json$/);
  assert.throws(() => parseArgs(["--candidate=other"]), /Unsupported argument/);
  assert.throws(() => parseArgs(["--gate=relaxed"]), /Unsupported argument/);
  assert.throws(() => parseArgs(["--start=2025-01-01"]), /Unsupported argument/);
});

test("evaluation protocol changes only the predeclared stage and blocks", () => {
  const development = {
    protocolId: "development",
    sourceData: {
      symbols: ["BTCUSDT", "ETHUSDT", "SOLUSDT"],
      developmentStart: "2023-01-01T00:00:00Z",
      developmentEndExclusive: "2024-01-01T00:00:00Z",
    },
    evidenceSchedule: { developmentBlocks: [{ id: "D01" }], other: true },
    executionContract: { startingEquityUsdt: 660 },
    statistics: { bootstrapSamples: 100 },
  };
  const internal = {
    protocolId: "internal",
    sourceData: {
      symbols: ["BTCUSDT", "ETHUSDT", "SOLUSDT"],
      stageStart: "2024-01-01T00:00:00Z",
      stageEndExclusive: "2025-01-01T00:00:00Z",
    },
    internalValidationBlocks: [{ id: "I01" }],
    internalValidationGate: { minimumClosedPositions: 20 },
  };
  const result = buildInternalEvaluationProtocol(internal, development);
  assert.equal(result.protocolId, "internal");
  assert.equal(result.sourceData.developmentStart, internal.sourceData.stageStart);
  assert.equal(result.sourceData.developmentEndExclusive, internal.sourceData.stageEndExclusive);
  assert.deepEqual(result.evidenceSchedule.developmentBlocks, internal.internalValidationBlocks);
  assert.deepEqual(result.executionContract, development.executionContract);
  assert.deepEqual(result.statistics, development.statistics);
});

test("freeze rejects read outcomes, retuning, and changed hashes", () => {
  const hash = "a".repeat(64);
  const manifest = {
    freezeId: "bybit-multi-asset-delta-neutral-funding-carry-internal-replay-freeze-v1",
    status: "FROZEN_BEFORE_MULTI_ASSET_INTERNAL_OUTCOME_REPLAY",
    protocol: { sha256: hash },
    acquisitionReceipt: { sha256: hash },
    implementation: { simulatorSha256: hash, replaySha256: hash },
    outcomeBoundary: {
      internalPortfolioMetricsReadBeforeFreeze: false,
      external2025Read: false,
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
  assert.doesNotThrow(() => validateInternalReplayFreeze(manifest, actual));
  assert.throws(() => validateInternalReplayFreeze({
    ...manifest,
    outcomeBoundary: { ...manifest.outcomeBoundary, internalPortfolioMetricsReadBeforeFreeze: true },
  }, actual), /boundary changed/);
});

test("receipt validation requires exact 2024 evidence and fixed candidate", () => {
  const hash = "a".repeat(64);
  const loaded = {
    sha256: hash,
    parentResultSha256: "b".repeat(64),
    simulatorSha256: "c".repeat(64),
    protocol: { selectedCandidateSha256: "d".repeat(64) },
  };
  const receipt = {
    status: "COMPLETE_MULTI_ASSET_INTERNAL_EVIDENCE_SEALED",
    stage: "internal",
    protocolSha256: loaded.sha256,
    parentDevelopmentResultSha256: loaded.parentResultSha256,
    selectedCandidateSha256: loaded.protocol.selectedCandidateSha256,
    simulatorSha256: loaded.simulatorSha256,
    stageSnapshotSha256: "e".repeat(64),
    normalizedEvidenceSha256: "f".repeat(64),
    coverage: {
      symbolCount: 3,
      seriesPerSymbol: 4,
      expectedM5RowsPerSeries: 105408,
      matchingM5RowsPerSymbol: 105408,
      totalMatchingM5Rows: 316224,
      fundingRowsPerSymbol: 1098,
      totalFundingRows: 3294,
      missingDecisionInputCount: 0,
    },
    integrity: { complete: true },
    priorObservationDisclosure: { multiAssetPortfolio2024MetricsReadBeforeFreeze: false },
    lockedEvidence: { external: false, sealed: false, forward: false },
    internalEvaluationAllowed: true,
    automaticExecutionAllowed: false,
    liveExecutionAllowed: false,
  };
  assert.doesNotThrow(() => validateInternalAcquisitionReceipt(receipt, loaded));
  assert.throws(() => validateInternalAcquisitionReceipt({
    ...receipt,
    selectedCandidateSha256: "0".repeat(64),
  }, loaded), /not eligible/);
});
