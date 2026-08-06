import assert from "node:assert/strict";
import test from "node:test";

import {
  calculatePairQuantity,
  evaluateDevelopmentGate,
  evaluateFundingCarrySignal,
  movingBlockBootstrapMean,
  simulateFundingCarryCandidate,
} from "./delta-neutral-funding-carry-research.mjs";

const execution = {
  spotTakerFeeRate: 0.001,
  perpetualTakerFeeRate: 0.00055,
  spotSlippageRatePerLeg: 0.0003,
  perpetualSlippageRatePerLeg: 0.0002,
  baseRoundTripCostRateOnMatchedNotional: 0.0041,
  costStressMultiplier: 1.5,
  secondLegDelayStressBars: 1,
  startingEquityUsdt: 660,
  maximumMatchedNotionalFractionOfEquity: 0.4,
  perpetualLeverage: 1,
  minimumUncommittedEquityFraction: 0.2,
  conservativeLiquidationPriceMultiple: 1.98,
  perpetualMinimumQuantityBtc: 0.001,
  perpetualQuantityStepBtc: 0.001,
  spotBasePrecisionBtc: 0.000001,
  maximumNetHedgeMismatchBtc: 0.000001,
};

const candidate = {
  id: "candidate",
  minimumPositiveFundingStreak: 3,
  minimumTrailingMedianFundingRate: 0.0001,
  minimumEntryBasisPct: 0,
  exitConsecutiveNonPositiveFundingCount: 1,
  entryDelayMinutes: 5,
  maximumHoldingDays: 30,
  maximumEntryBasisPct: 0.03,
  maximumAbsoluteMarkIndexPremiumPct: 0.01,
  basisDivergenceStopPctFromEntry: 0.03,
  reentryCooldownHours: 8,
  projectedCarryHorizonDays: 30,
  minimumProjectedGrossCarryToBaseCostRatio: 1,
};

test("capital-aware sizing preserves reserve and exact hedge tolerance", () => {
  const sizing = calculatePairQuantity(660, 20_000, 20_010, execution);
  assert.equal(sizing.targetNetQuantityBtc, 0.013);
  assert.equal(sizing.committedCapitalUsdt <= 660 * 0.8, true);
  assert.equal(sizing.netHedgeMismatchBtc <= 0.000001, true);
  assert.equal(calculatePairQuantity(100, 100_000, 100_010, execution), null);
});

test("funding signal uses only the settled positive streak and closed basis", () => {
  const fundingRates = [0.0001, 0.0002, 0.0003].map((rate, index) => ({ timestamp: index, rate }));
  const decisionFrame = frame(0, 100, 100.2, 100.1, 100);
  const signal = evaluateFundingCarrySignal({
    candidate,
    fundingRates,
    fundingIndex: 2,
    decisionFrame,
    execution,
  });
  assert.equal(signal.trailingMedianFundingRate, 0.0002);
  assert.equal(signal.entryBasisPct > 0, true);
  assert.equal(evaluateFundingCarrySignal({
    candidate,
    fundingRates: [{ timestamp: 0, rate: 0.0001 }, { timestamp: 1, rate: -0.0001 }, { timestamp: 2, rate: 0.0003 }],
    fundingIndex: 2,
    decisionFrame,
    execution,
  }), null);
});

test("matched spot and perpetual legs cancel common directional movement before costs", () => {
  const start = Date.parse("2023-01-01T00:00:00Z");
  const frames = makeFrames(start, 40 * 24 * 12, (index) => {
    const price = 20_000 + index * 0.5;
    return { spot: price, perpetual: price * 1.001, mark: price * 1.001, index: price };
  });
  const fundingRates = [];
  for (let timestamp = start; timestamp < start + 40 * 24 * 60 * 60 * 1_000; timestamp += 8 * 60 * 60 * 1_000) {
    fundingRates.push({ timestamp, rate: 0.0003 });
  }
  const metrics = simulateFundingCarryCandidate({ candidate, frames, fundingRates, protocol: protocol() });
  assert.equal(metrics.tradeCount >= 1, true);
  assert.equal(metrics.fundingPnlUsdt > 0, true);
  assert.equal(metrics.liquidationCount, 0);
  assert.equal(metrics.maximumNetHedgeMismatchBtc <= 0.000001, true);
});

test("basis expansion is closed before the conservative liquidation boundary", () => {
  const start = Date.parse("2023-01-01T00:00:00Z");
  const frames = makeFrames(start, 10 * 24 * 12, (index) => {
    const spot = 20_000;
    const basis = index < 300 ? 0.001 : 0.04;
    return { spot, perpetual: spot * (1 + basis), mark: spot * (1 + basis), index: spot };
  });
  const fundingRates = [];
  for (let timestamp = start; timestamp < start + 10 * 24 * 60 * 60 * 1_000; timestamp += 8 * 60 * 60 * 1_000) {
    fundingRates.push({ timestamp, rate: 0.0003 });
  }
  const metrics = simulateFundingCarryCandidate({ candidate, frames, fundingRates, protocol: protocol() });
  assert.equal((metrics.exitReasons.BASIS_DIVERGENCE_STOP ?? 0) >= 1, true);
  assert.equal(metrics.liquidationCount, 0);
});

test("non-synchronous spot low and perpetual high cannot fabricate a basis stop", () => {
  const start = Date.parse("2023-01-01T00:00:00Z");
  const frames = makeFrames(start, 10 * 24 * 12, () => ({
    spot: 20_000,
    perpetual: 20_020,
    mark: 20_020,
    index: 20_000,
  }));
  frames[300].spot.low = 18_000;
  frames[300].perpetual.high = 22_000;
  const fundingRates = [];
  for (let timestamp = start; timestamp < start + 10 * 24 * 60 * 60 * 1_000; timestamp += 8 * 60 * 60 * 1_000) {
    fundingRates.push({ timestamp, rate: 0.0003 });
  }
  const metrics = simulateFundingCarryCandidate({ candidate, frames, fundingRates, protocol: protocol() });
  assert.equal(metrics.exitReasons.BASIS_DIVERGENCE_STOP ?? 0, 0);
  assert.equal(metrics.liquidationCount, 0);
});

test("daily moving-block bootstrap is deterministic and preserves expectancy sign", () => {
  const positive = movingBlockBootstrapMean([0.001, 0.002, 0.0015, 0.0005], {
    samples: 1000,
    confidence: 0.95,
    blockLength: 2,
    seed: "same",
  });
  const repeated = movingBlockBootstrapMean([0.001, 0.002, 0.0015, 0.0005], {
    samples: 1000,
    confidence: 0.95,
    blockLength: 2,
    seed: "same",
  });
  const negative = movingBlockBootstrapMean([-0.001, -0.002, -0.0015, -0.0005], {
    samples: 1000,
    confidence: 0.95,
    blockLength: 2,
    seed: "negative",
  });
  assert.deepEqual(positive, repeated);
  assert.equal(positive.lower > 0, true);
  assert.equal(negative.upper < 0, true);
});

test("a positive base return cannot bypass cost or delay stress gates", () => {
  const gate = {
    minimumClosedPositions: 1,
    minimumActiveCalendarDays: 1,
    minimumCapturedFundingSettlements: 1,
    minimumPositiveBlockCount: 1,
    totalBlockCount: 1,
    minimumNetReturnPct: 0,
    minimumProfitFactor: 1.1,
    minimumMeanDailyReturnPct: 0,
    minimumBootstrapLowerMeanDailyReturnPct: 0,
    maximumDrawdownPct: 15,
    maximumLiquidationCount: 0,
    maximumPositivePositionProfitConcentration: 1,
    maximumNetHedgeMismatchBtc: 0.000001,
    costStressMinimumNetReturnPct: 0,
    secondLegDelayStressMinimumNetReturnPct: 0,
  };
  const metrics = {
    tradeCount: 1,
    activeCalendarDays: 10,
    capturedFundingSettlements: 10,
    positiveBlockCount: 1,
    totalBlockCount: 1,
    netReturnPct: 1,
    profitFactor: 2,
    meanDailyReturnPct: 0.01,
    bootstrapLowerMeanDailyReturnPct: 0.001,
    maximumDrawdownPct: 1,
    liquidationCount: 0,
    positivePositionProfitConcentration: 1,
    maximumNetHedgeMismatchBtc: 0,
    costStressNetReturnPct: -0.1,
    secondLegDelayStressNetReturnPct: -0.2,
  };
  const result = evaluateDevelopmentGate(metrics, gate);
  assert.equal(result.passed, false);
  assert.equal(result.failedChecks.includes("costStressMinimumNetReturnPct"), true);
  assert.equal(result.failedChecks.includes("secondLegDelayStressMinimumNetReturnPct"), true);
});

function protocol() {
  return {
    executionContract: execution,
    statistics: {
      bootstrapSamples: 500,
      bootstrapConfidence: 0.95,
      bootstrapBlockDays: 7,
      randomSeed: "test",
    },
    evidenceSchedule: {
      developmentBlocks: [
        { id: "D01", era: "test", startAt: "2023-01-01T00:00:00Z", endAt: "2023-04-01T00:00:00Z" },
      ],
    },
  };
}

function makeFrames(start, count, values) {
  return Array.from({ length: count }, (_, index) => {
    const value = values(index);
    return frame(start + index * 5 * 60 * 1_000, value.spot, value.perpetual, value.mark, value.index);
  });
}

function frame(timestamp, spot, perpetual, mark, index) {
  return {
    timestamp,
    spot: candle(spot),
    perpetual: candle(perpetual),
    mark: candle(mark),
    index: candle(index),
  };
}

function candle(value) {
  return { open: value, high: value, low: value, close: value };
}
