import assert from "node:assert/strict";
import test from "node:test";

import {
  buildYearEvaluationProtocol,
  evaluateCostRecoveryDevelopmentGate,
  parseArgs,
  rankCostRecoveryEvaluations,
  summarizeAnnualMetrics,
  validateCostRecoveryReplayFreeze,
} from "./multi-asset-cost-recovery-carry-replay.mjs";

test("v3 replay arguments cannot expand the grid or read 2026", () => {
  const parsed = parseArgs(["--freeze=config/freeze.json", "--report=build/report.json"]);
  assert.match(parsed.freeze, /config\/freeze\.json$/);
  assert.throws(() => parseArgs(["--candidate=other"]), /Unsupported argument/);
  assert.throws(() => parseArgs(["--year=2026"]), /Unsupported argument/);
  assert.throws(() => parseArgs(["--minimum-return=-1"]), /Unsupported argument/);
});

test("year protocol preserves execution while changing range, quarters, and bootstrap seed", () => {
  const base = {
    sourceData: { symbols: ["BTCUSDT", "ETHUSDT", "SOLUSDT"] },
    evidenceSchedule: { developmentBlocks: [{ id: "old" }] },
    statistics: { randomSeed: "old", bootstrapSamples: 10000 },
    executionContract: { startingEquityUsdt: 660 },
  };
  const v3 = { protocolId: "v3" };
  const evidence = {
    year: 2025,
    startAt: "2025-01-01T00:00:00Z",
    endExclusive: "2026-01-01T00:00:00Z",
    blocks: [{ id: "D25Q1" }],
  };
  const result = buildYearEvaluationProtocol(v3, base, evidence);
  assert.equal(result.sourceData.developmentStart, evidence.startAt);
  assert.deepEqual(result.evidenceSchedule.developmentBlocks, evidence.blocks);
  assert.equal(result.statistics.randomSeed, "v3|2025");
  assert.deepEqual(result.executionContract, base.executionContract);
});

test("development gate requires every year to remain net and stress positive", () => {
  const annual = [2023, 2024, 2025].map((year) => ({ year, metrics: metrics() }));
  const summary = { ...summarizeAnnualMetrics(annual), annual };
  const gate = gateConfig();
  assert.equal(evaluateCostRecoveryDevelopmentGate(summary, gate).passed, true);
  const failedAnnual = annual.map((row) => row.year === 2025
    ? { ...row, metrics: { ...row.metrics, costStressNetReturnPct: -0.01 } }
    : row);
  const failedSummary = { ...summarizeAnnualMetrics(failedAnnual), annual: failedAnnual };
  assert.deepEqual(
    evaluateCostRecoveryDevelopmentGate(failedSummary, gate).failedChecks,
    ["minimumCostStressNetReturnPctPerYear"],
  );
});

test("ranking prioritizes pass then worst-year stress robustness", () => {
  const rows = [
    evaluation("failed", false, 2, 9),
    evaluation("weak", true, 0.1, 2),
    evaluation("strong", true, 0.2, 1),
  ];
  assert.deepEqual(
    rankCostRecoveryEvaluations(rows).map((row) => row.candidate.id),
    ["strong", "weak", "failed"],
  );
});

test("freeze rejects grid outcomes read before implementation lock", () => {
  const hash = "a".repeat(64);
  const manifest = {
    freezeId: "bybit-multi-asset-cost-recovery-carry-replay-freeze-v3",
    status: "FROZEN_BEFORE_COST_RECOVERY_DEVELOPMENT_REPLAY",
    protocol: { sha256: hash },
    implementation: { simulatorSha256: hash, replaySha256: hash },
    outcomeBoundary: {
      developmentGridMetricsReadBeforeFreeze: false,
      sealed2026Read: false,
      freshForwardSealRead: false,
    },
    trialAccounting: { frozenCandidatesToEvaluate: 54 },
    selectionPolicy: { maximumSelectedCandidateCount: 1, candidateMayBeRetunedAfterOutcome: false },
    automaticExecutionAllowed: false,
    liveExecutionAllowed: false,
  };
  const actual = { protocolSha256: hash, simulatorSha256: hash, replaySha256: hash };
  assert.doesNotThrow(() => validateCostRecoveryReplayFreeze(manifest, actual));
  assert.throws(() => validateCostRecoveryReplayFreeze({
    ...manifest,
    outcomeBoundary: { ...manifest.outcomeBoundary, developmentGridMetricsReadBeforeFreeze: true },
  }, actual), /boundary changed/);
});

function metrics() {
  return {
    tradeCount: 6,
    tradedAssetCount: 3,
    positiveAssetCount: 2,
    positiveBlockCount: 3,
    netReturnPct: 1,
    profitFactor: 2,
    meanDailyReturnPct: 0.001,
    bootstrapLowerMeanDailyReturnPct: 0.0001,
    maximumDrawdownPct: 1,
    liquidationCount: 0,
    positivePositionProfitConcentration: 0.4,
    positiveAssetProfitConcentration: 0.6,
    maximumNetHedgeMismatchBySymbol: { BTCUSDT: 0, ETHUSDT: 0, SOLUSDT: 0 },
    costStressNetReturnPct: 0.5,
    secondLegDelayStressNetReturnPct: 0.4,
  };
}

function gateConfig() {
  return {
    minimumTotalClosedPositions: 15,
    minimumClosedPositionsPerYear: 3,
    minimumTradedAssetCountPerYear: 3,
    minimumPositiveAssetCountPerYear: 2,
    minimumPositiveQuarterCountPerYear: 2,
    minimumTotalPositiveQuarterCount: 8,
    minimumNetReturnPctPerYear: 0,
    minimumProfitFactorPerYear: 1.1,
    minimumMeanDailyReturnPctPerYear: 0,
    minimumBootstrapLowerMeanDailyReturnPctPerYear: 0,
    maximumDrawdownPctPerYear: 5,
    maximumLiquidationCount: 0,
    maximumPositivePositionProfitConcentrationPerYear: 0.6,
    maximumPositiveAssetProfitConcentrationPerYear: 0.75,
    maximumNetHedgeMismatchBySymbol: { BTCUSDT: 0.000001, ETHUSDT: 0.00001, SOLUSDT: 0.0001 },
    minimumCostStressNetReturnPctPerYear: 0,
    minimumSecondLegDelayStressNetReturnPctPerYear: 0,
  };
}

function evaluation(id, passed, stress, returnPct) {
  return {
    candidate: { id },
    gate: { passed, failedChecks: passed ? [] : ["x"] },
    summary: {
      worstYearCostStressNetReturnPct: stress,
      worstYearSecondLegDelayStressNetReturnPct: stress,
      worstYearNetReturnPct: returnPct,
      threeYearCompoundedReturnPct: returnPct,
    },
  };
}
