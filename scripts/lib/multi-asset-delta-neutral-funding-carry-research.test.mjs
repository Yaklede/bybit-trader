import assert from "node:assert/strict";
import test from "node:test";

import {
  calculateAssetPairSizing,
  evaluateMultiAssetDevelopmentGate,
  evaluateMultiAssetFundingCarrySignal,
  rankEligibleSignals,
  simulateMultiAssetFundingCarryCandidate,
} from "./multi-asset-delta-neutral-funding-carry-research.mjs";

const symbols = ["BTCUSDT", "ETHUSDT", "SOLUSDT"];
const execution = {
  spotTakerFeeRate: 0.001,
  perpetualTakerFeeRate: 0.00055,
  spotSlippageRatePerLeg: 0.0003,
  perpetualSlippageRatePerLeg: 0.0002,
  baseRoundTripCostRateOnMatchedNotional: 0.0041,
  costStressMultiplier: 1.5,
  secondLegDelayStressBars: 1,
  startingEquityUsdt: 660,
  maximumTotalMatchedNotionalFractionOfEquity: 0.4,
  perpetualLeverage: 1,
  minimumUncommittedEquityFraction: 0.2,
  conservativeLiquidationPriceMultiple: 1.98,
};

const candidate = {
  id: "candidate",
  symbols,
  minimumPositiveFundingStreak: 3,
  minimumTrailingMedianFundingRate: 0.0001,
  maximumConcurrentPairs: 2,
  exitConsecutiveNonPositiveFundingCount: 1,
  entryDelayMinutes: 5,
  maximumHoldingDays: 30,
  minimumEntryBasisPct: 0,
  maximumEntryBasisPct: 0.03,
  maximumAbsoluteMarkIndexPremiumPct: 0.01,
  basisDivergenceStopPctFromEntry: 0.03,
  reentryCooldownHours: 8,
  projectedCarryHorizonSettlements: 90,
  minimumProjectedNetCarryScore: 0,
};

test("asset sizing preserves pair cap, global reserve, and symbol precision", () => {
  const btc = calculateAssetPairSizing({
    equityUsdt: 660,
    existingCommittedCapitalUsdt: 0,
    spotReferencePrice: 20_000,
    perpetualReferencePrice: 20_010,
    execution,
    instrument: instruments().BTCUSDT,
    maximumConcurrentPairs: 2,
  });
  assert.equal(btc.targetNetQuantityBase, 0.006);
  assert.equal(btc.matchedNotionalUsdt <= 660 * 0.2 + 1, true);
  assert.equal(btc.netHedgeMismatchBase <= 0.000001, true);
  assert.equal(calculateAssetPairSizing({
    equityUsdt: 100,
    existingCommittedCapitalUsdt: 0,
    spotReferencePrice: 100_000,
    perpetualReferencePrice: 100_010,
    execution,
    instrument: instruments().BTCUSDT,
    maximumConcurrentPairs: 1,
  }), null);
});

test("signals use settled history and rank by fixed net carry score", () => {
  const decisionFrame = frame(0, 100, 100.2, 100.1, 100);
  const fundingRates = [0.0001, 0.0002, 0.0003].map((rate, index) => ({ timestamp: index, rate }));
  const signal = evaluateMultiAssetFundingCarrySignal({
    candidate,
    fundingRates,
    fundingIndex: 2,
    decisionFrame,
    execution,
  });
  assert.equal(signal.trailingMedianFundingRate, 0.0002);
  const ranked = rankEligibleSignals([
    { symbol: "BTCUSDT", signal: { projectedNetCarryScore: 0.01 } },
    { symbol: "SOLUSDT", signal: { projectedNetCarryScore: 0.02 } },
    { symbol: "ETHUSDT", signal: { projectedNetCarryScore: 0.02 } },
  ]);
  assert.deepEqual(ranked.map((row) => row.symbol), ["ETHUSDT", "SOLUSDT", "BTCUSDT"]);
});

test("two concurrent pairs share one equity and remain inside the total exposure cap", () => {
  const start = Date.parse("2023-01-01T00:00:00Z");
  const count = 40 * 24 * 12;
  const framesBySymbol = {
    BTCUSDT: makeFrames(start, count, 20_000, 0.001, 0.2),
    ETHUSDT: makeFrames(start, count, 1_500, 0.001, 0.02),
    SOLUSDT: makeFrames(start, count, 20, 0.001, 0.0002),
  };
  const fundingRatesBySymbol = {
    BTCUSDT: funding(start, 40, 0.0002),
    ETHUSDT: funding(start, 40, 0.0003),
    SOLUSDT: funding(start, 40, 0.0004),
  };
  const metrics = simulateMultiAssetFundingCarryCandidate({
    candidate,
    framesBySymbol,
    fundingRatesBySymbol,
    protocol: protocol(),
  });
  assert.equal(metrics.maximumConcurrentPositionCount, 2);
  assert.equal(metrics.maximumTotalMatchedNotionalFraction <= 0.405, true);
  assert.equal(metrics.tradedAssetCount >= 2, true);
  assert.equal(metrics.fundingPnlUsdt > 0, true);
  assert.equal(metrics.liquidationCount, 0);
  assert.equal(Math.abs(
    metrics.startingEquityUsdt + metrics.pricePnlUsdt + metrics.fundingPnlUsdt -
      metrics.feeCostUsdt - metrics.slippageCostUsdt - metrics.endingEquityUsdt,
  ) < 0.000001, true);
});

test("a basis break exits only the affected symbol on the next M5 open", () => {
  const start = Date.parse("2023-01-01T00:00:00Z");
  const count = 10 * 24 * 12;
  const framesBySymbol = {
    BTCUSDT: makeFrames(start, count, 20_000, 0.001, 0),
    ETHUSDT: makeFrames(start, count, 1_500, 0.001, 0),
    SOLUSDT: makeFrames(start, count, 20, 0.001, 0),
  };
  for (let index = 300; index < count; index += 1) {
    framesBySymbol.ETHUSDT[index].perpetual = candle(1_500 * 1.04);
    framesBySymbol.ETHUSDT[index].mark = candle(1_500 * 1.04);
  }
  const fundingRatesBySymbol = Object.fromEntries(symbols.map((symbol) => [symbol, funding(start, 10, 0.0004)]));
  const metrics = simulateMultiAssetFundingCarryCandidate({
    candidate,
    framesBySymbol,
    fundingRatesBySymbol,
    protocol: protocol(),
  });
  assert.equal((metrics.exitReasons.BASIS_DIVERGENCE_STOP ?? 0) >= 1, true);
  assert.equal(metrics.trades.some((trade) => trade.symbol === "ETHUSDT" &&
    trade.exitReason === "BASIS_DIVERGENCE_STOP"), true);
  assert.equal(metrics.liquidationCount, 0);
});

test("non-synchronous intrabar extremes cannot fabricate a portfolio basis stop", () => {
  const start = Date.parse("2023-01-01T00:00:00Z");
  const count = 10 * 24 * 12;
  const framesBySymbol = {
    BTCUSDT: makeFrames(start, count, 20_000, 0.001, 0),
    ETHUSDT: makeFrames(start, count, 1_500, 0.001, 0),
    SOLUSDT: makeFrames(start, count, 20, 0.001, 0),
  };
  framesBySymbol.ETHUSDT[300].spot.low = 1_000;
  framesBySymbol.ETHUSDT[300].perpetual.high = 2_000;
  const fundingRatesBySymbol = Object.fromEntries(
    symbols.map((symbol) => [symbol, funding(start, 10, 0.0004)]),
  );
  const metrics = simulateMultiAssetFundingCarryCandidate({
    candidate,
    framesBySymbol,
    fundingRatesBySymbol,
    protocol: protocol(),
  });
  assert.equal(metrics.exitReasons.BASIS_DIVERGENCE_STOP ?? 0, 0);
  assert.equal(metrics.liquidationCount, 0);
});

test("profit concentration and asset breadth are hard approval gates", () => {
  const gate = {
    minimumClosedPositions: 1,
    minimumActiveCalendarDays: 1,
    minimumCapturedFundingSettlements: 1,
    minimumTradedAssetCount: 3,
    minimumPositiveAssetCount: 2,
    minimumPositiveBlockCount: 1,
    totalBlockCount: 1,
    minimumNetReturnPct: 0,
    minimumProfitFactor: 1.1,
    minimumMeanDailyReturnPct: 0,
    minimumBootstrapLowerMeanDailyReturnPct: 0,
    maximumDrawdownPct: 15,
    maximumLiquidationCount: 0,
    maximumPositivePositionProfitConcentration: 0.25,
    maximumPositiveAssetProfitConcentration: 0.6,
    maximumNetHedgeMismatchBySymbol: { BTCUSDT: 0.000001, ETHUSDT: 0.00001, SOLUSDT: 0.0001 },
    costStressMinimumNetReturnPct: 0,
    secondLegDelayStressMinimumNetReturnPct: 0,
  };
  const metrics = {
    tradeCount: 20,
    activeCalendarDays: 200,
    capturedFundingSettlements: 100,
    tradedAssetCount: 2,
    positiveAssetCount: 1,
    positiveBlockCount: 1,
    totalBlockCount: 1,
    netReturnPct: 1,
    profitFactor: 2,
    meanDailyReturnPct: 0.01,
    bootstrapLowerMeanDailyReturnPct: 0.001,
    maximumDrawdownPct: 1,
    liquidationCount: 0,
    positivePositionProfitConcentration: 0.4,
    positiveAssetProfitConcentration: 1,
    maximumNetHedgeMismatchBySymbol: { BTCUSDT: 0, ETHUSDT: 0, SOLUSDT: 0 },
    costStressNetReturnPct: 0.5,
    secondLegDelayStressNetReturnPct: 0.5,
  };
  const result = evaluateMultiAssetDevelopmentGate(metrics, gate);
  assert.equal(result.passed, false);
  assert.equal(result.failedChecks.includes("minimumTradedAssetCount"), true);
  assert.equal(result.failedChecks.includes("maximumPositivePositionProfitConcentration"), true);
  assert.equal(result.failedChecks.includes("maximumPositiveAssetProfitConcentration"), true);
});

function protocol() {
  return {
    sourceData: { symbols },
    executionContract: execution,
    observedInstrumentRules: instruments(),
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

function instruments() {
  return {
    BTCUSDT: instrument(0.000001, 0.001),
    ETHUSDT: instrument(0.00001, 0.01),
    SOLUSDT: instrument(0.0001, 0.1),
  };
}

function instrument(precision, step) {
  return {
    spot: { minimumOrderAmountUsdt: 5, basePrecision: precision },
    perpetual: { minimumOrderQuantityBase: step, quantityStepBase: step, minimumNotionalUsdt: 5 },
    maximumNetHedgeMismatchBase: precision,
  };
}

function makeFrames(start, count, initialPrice, basis, risePerBar) {
  return Array.from({ length: count }, (_, index) => {
    const spot = initialPrice + index * risePerBar;
    return frame(start + index * 5 * 60 * 1_000, spot, spot * (1 + basis), spot * (1 + basis), spot);
  });
}

function funding(start, days, rate) {
  const rows = [];
  for (let timestamp = start; timestamp < start + days * 24 * 60 * 60 * 1_000;
    timestamp += 8 * 60 * 60 * 1_000) {
    rows.push({ timestamp, rate });
  }
  return rows;
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
