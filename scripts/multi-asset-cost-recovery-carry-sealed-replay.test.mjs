import assert from "node:assert/strict";
import test from "node:test";

import {
  buildSealedEvaluationProtocol,
  parseArgs,
  validateSealedAcquisitionReceipt,
  validateSealedReplayFreeze,
} from "./multi-asset-cost-recovery-carry-sealed-replay.mjs";

test("sealed replay arguments cannot change candidate, gate, or period", () => {
  const parsed = parseArgs(["--freeze=config/freeze.json", "--report=build/report.json"]);
  assert.match(parsed.freeze, /config\/freeze\.json$/);
  assert.throws(() => parseArgs(["--candidate=other"]), /Unsupported argument/);
  assert.throws(() => parseArgs(["--minimum-trades=1"]), /Unsupported argument/);
  assert.throws(() => parseArgs(["--start=2026-02-01"]), /Unsupported argument/);
});

test("sealed evaluation preserves execution contract and swaps only stage metadata", () => {
  const base = {
    protocolId: "development",
    sourceData: { symbols: ["BTCUSDT", "ETHUSDT", "SOLUSDT"] },
    evidenceSchedule: { developmentBlocks: [{ id: "D01" }], other: true },
    executionContract: { startingEquityUsdt: 660 },
    statistics: { bootstrapSamples: 10000 },
  };
  const sealed = {
    protocolId: "sealed",
    sourceData: {
      symbols: base.sourceData.symbols,
      stageStart: "2026-01-01T00:00:00Z",
      stageEndExclusive: "2026-07-01T00:00:00Z",
    },
    sealedValidationBlocks: [{ id: "S01" }, { id: "S02" }],
    sealedValidationGate: { minimumClosedPositions: 3 },
  };
  const result = buildSealedEvaluationProtocol(sealed, base);
  assert.equal(result.sourceData.developmentStart, sealed.sourceData.stageStart);
  assert.equal(result.sourceData.developmentEndExclusive, sealed.sourceData.stageEndExclusive);
  assert.deepEqual(result.evidenceSchedule.developmentBlocks, sealed.sealedValidationBlocks);
  assert.deepEqual(result.executionContract, base.executionContract);
  assert.equal(result.statistics.bootstrapSamples, 10000);
  assert.equal(result.statistics.randomSeed, "sealed|sealed");
});

test("freeze rejects read outcomes, retuning, and changed hashes", () => {
  const hash = "a".repeat(64);
  const manifest = {
    freezeId: "bybit-multi-asset-cost-recovery-carry-sealed-replay-freeze-v3",
    status: "FROZEN_BEFORE_SEALED_2026_H1_OUTCOME_REPLAY",
    protocol: { sha256: hash },
    acquisitionReceipt: { sha256: hash },
    implementation: { simulatorSha256: hash, replaySha256: hash },
    outcomeBoundary: {
      sealedPortfolioMetricsReadBeforeFreeze: false,
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
  assert.doesNotThrow(() => validateSealedReplayFreeze(manifest, actual));
  assert.throws(() => validateSealedReplayFreeze({
    ...manifest,
    outcomeBoundary: { ...manifest.outcomeBoundary, sealedPortfolioMetricsReadBeforeFreeze: true },
  }, actual), /boundary changed/);
});

test("receipt validation requires exact 2026 H1 evidence and fixed candidate", () => {
  const loaded = {
    sha256: "a".repeat(64),
    developmentResultSha256: "b".repeat(64),
    simulatorSha256: "c".repeat(64),
    protocol: { selectedCandidateSha256: "d".repeat(64) },
  };
  const receipt = {
    status: "COMPLETE_MULTI_ASSET_SEALED_2026_H1_EVIDENCE",
    stage: "sealed-2026-h1",
    protocolSha256: loaded.sha256,
    developmentResultSha256: loaded.developmentResultSha256,
    selectedCandidateSha256: loaded.protocol.selectedCandidateSha256,
    simulatorSha256: loaded.simulatorSha256,
    stageSnapshotSha256: "e".repeat(64),
    normalizedEvidenceSha256: "f".repeat(64),
    coverage: {
      symbolCount: 3,
      seriesPerSymbol: 4,
      expectedM5RowsPerSeries: 52128,
      matchingM5RowsPerSymbol: 52128,
      totalMatchingM5Rows: 156384,
      fundingRowsPerSymbol: 543,
      totalFundingRows: 1629,
      missingDecisionInputCount: 0,
    },
    integrity: { complete: true },
    outcomeBoundary: {
      sealed2026H1PortfolioMetricsReadBeforeReceipt: false,
      freshForwardSealRead: false,
    },
    sealedEvaluationAllowed: true,
    automaticExecutionAllowed: false,
    liveExecutionAllowed: false,
  };
  assert.doesNotThrow(() => validateSealedAcquisitionReceipt(receipt, loaded));
  assert.throws(() => validateSealedAcquisitionReceipt({
    ...receipt,
    coverage: { ...receipt.coverage, totalFundingRows: 1 },
  }, loaded), /not eligible/);
});
