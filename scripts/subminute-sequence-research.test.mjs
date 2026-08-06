import assert from "node:assert/strict";
import test from "node:test";

import {
  buildAtrTimeline,
  buildM15RegimeTimeline,
  calculateQuantity,
  detectSubminuteSetup,
  isConfirmation,
  movingBlockBootstrapMean,
  prepareBlockFeatures,
  rankAndSelectCandidates,
  rollingMedianBefore,
  selectionGate,
  simulateSubminuteTrade,
} from "./lib/subminute-sequence-research.mjs";

const FIVE_SECONDS = 5_000;
const ONE_MINUTE = 60_000;

test("rolling median excludes the decision bucket and evicts the oldest value", () => {
  assert.deepEqual(
    rollingMedianBefore([10, 20, 30, 100], 3, 2),
    [null, null, 15, 20],
  );
});

test("M15 regime and M1 ATR become available only after their candle closes", () => {
  const openedAt = Date.parse("2023-01-01T00:00:00Z");
  const m15 = Array.from({ length: 61 }, (_, index) => ({
    openedAt: openedAt + index * 15 * ONE_MINUTE,
    close: 100 + index,
  }));
  const regimes = buildM15RegimeTimeline(m15);
  assert.equal(regimes[58].regime, 0);
  assert.equal(regimes[59].regime, 1);
  assert.equal(regimes[59].availableAt, m15[59].openedAt + 15 * ONE_MINUTE);

  const m1 = [
    candle(openedAt, 100, 102, 99, 101),
    candle(openedAt + ONE_MINUTE, 101, 104, 100, 103),
    candle(openedAt + 2 * ONE_MINUTE, 103, 105, 102, 104),
  ];
  const atr = buildAtrTimeline(m1, 2);
  assert.equal(atr[0].atr, null);
  assert.equal(atr[1].atr, 3.5);
  assert.equal(atr[1].availableAt, m1[1].openedAt + ONE_MINUTE);
});

test("continuation setup requires aligned impact, depletion, and expanding open interest", () => {
  const rows = [
    flowRow(0, { closeMidPrice: 100 }),
    flowRow(1, { closeMidPrice: 100.02, buyNotional: 400, askAddedTop5Notional: 20, askRemovedTop5Notional: 100 }),
    flowRow(2, { closeMidPrice: 100.05, buyNotional: 400, askAddedTop5Notional: 20, askRemovedTop5Notional: 100 }),
    flowRow(3, { closeMidPrice: 100.06, buyNotional: 70, sellNotional: 30, meanMicropriceEdgeBps: 0.1 }),
  ];
  const features = detectionFeatures(rows, { regime: 1, openInterestChange: 0.002 });
  const candidate = {
    family: "SUBMINUTE_DEPLETION_CONTINUATION",
    shockWindowSeconds: 10,
    minimumAbsoluteTakerImbalance: 0.5,
    minimumRelativeTakerNotional: 3,
    minimumDirectionalPriceImpactBps: 2,
    maximumConsumedSideReplenishmentRatio: 0.5,
    minimumOpenInterestChangePct: 0.001,
    minimumConfirmationAbsoluteTakerImbalance: 0.2,
    minimumAlignedMicropriceEdgeBps: 0.05,
  };
  const setup = detectSubminuteSetup(candidate, features, 2);
  assert.equal(setup.entryDirection, 1);
  assert.ok(setup.directionalImpactBps >= 2);
  assert.ok(setup.replenishmentRatio <= 0.5);
  assert.equal(isConfirmation(candidate, features, setup, 2, 3), true);

  features.openInterestChange[2] = -0.002;
  assert.equal(detectSubminuteSetup(candidate, features, 2), null);
});

test("absorption setup enters with the M15 regime only after opposite flow confirms", () => {
  const rows = [
    flowRow(0, { closeMidPrice: 100 }),
    flowRow(1, { closeMidPrice: 99.995, sellNotional: 400, bidAddedTop5Notional: 150, bidRemovedTop5Notional: 100 }),
    flowRow(2, { closeMidPrice: 99.99, sellNotional: 400, bidAddedTop5Notional: 150, bidRemovedTop5Notional: 100 }),
    flowRow(3, { closeMidPrice: 100.01, buyNotional: 70, sellNotional: 30, meanMicropriceEdgeBps: 0.1 }),
  ];
  const features = detectionFeatures(rows, { regime: 1, openInterestChange: -0.004 });
  const candidate = {
    family: "SUBMINUTE_ABSORPTION_REVERSAL",
    shockWindowSeconds: 10,
    minimumAbsoluteTakerImbalance: 0.5,
    minimumRelativeTakerNotional: 3,
    maximumDirectionalPriceImpactBps: 2,
    minimumConsumedSideReplenishmentRatio: 1.25,
    maximumOpenInterestChangePct: -0.003,
    minimumConfirmationAbsoluteTakerImbalance: 0.2,
    minimumOpposingMicropriceEdgeBps: 0.05,
  };
  const setup = detectSubminuteSetup(candidate, features, 2);
  assert.equal(setup.shockDirection, -1);
  assert.equal(setup.entryDirection, 1);
  assert.equal(isConfirmation(candidate, features, setup, 2, 3), true);

  rows[3].meanMicropriceEdgeBps = -0.1;
  assert.equal(isConfirmation(candidate, features, setup, 2, 3), false);
});

test("trade simulation enters on the next bucket and resolves a TP/SL collision as stop first", () => {
  const openedAt = Date.parse("2023-01-01T00:00:00Z");
  const rows = [
    marketRow(openedAt, 59_999, 60_000),
    marketRow(openedAt + FIVE_SECONDS, 60_009, 60_010),
    marketRow(openedAt + 2 * FIVE_SECONDS, 59_999, 60_000, {
      tradeCount: 2,
      openPrice: 60_000,
      highPrice: 61_000,
      lowPrice: 59_000,
      closePrice: 60_000,
    }),
  ];
  const execution = executionContract();
  const trade = simulateSubminuteTrade({
    features: { rows, atr: [100, 100, 100], funding: [] },
    setup: { entryDirection: 1, directionalImpactBps: 4, replenishmentRatio: 0.2, oiChange: 0.002 },
    confirmationIndex: 0,
    entryIndex: 1,
    execution,
    equity: 100,
    evaluationEndIndex: rows.length,
  });
  assert.equal(trade.entryAt, rows[1].openedAt + FIVE_SECONDS);
  assert.equal(trade.exitReason, "STOP");
  assert.equal(trade.exitIndex, 2);
  assert.equal(trade.quantity, 0.001);
  assert.ok(trade.netPnl < 0);
  assert.ok(trade.minimumOpenPnl < trade.netPnl);
});

test("quantity calculation floors to exchange step and rejects minimum-size risk overflow", () => {
  const execution = executionContract();
  assert.equal(calculateQuantity({
    equity: 100,
    entryPrice: 60_000,
    stopPrice: 59_760,
    execution,
  }), 0.001);
  assert.equal(calculateQuantity({
    equity: 100,
    entryPrice: 60_000,
    stopPrice: 59_760,
    execution: { ...execution, maximumNotionalUsdt: 50 },
  }), null);
});

test("future market mutations cannot change already available features", () => {
  const base = Date.parse("2023-01-02T00:00:00Z");
  const rows = Array.from({ length: 500 }, (_, index) => flowRow(index, {
    openedAt: base + index * FIVE_SECONDS,
    buyNotional: 10 + index % 5,
    sellNotional: 8 + index % 3,
    closeMidPrice: 100 + index * 0.001,
  }));
  const m1Start = base - 14 * ONE_MINUTE;
  const m1 = Array.from({ length: 60 }, (_, index) => candle(
    m1Start + index * ONE_MINUTE,
    100 + index,
    102 + index,
    99 + index,
    101 + index,
  ));
  const m15Start = base - 60 * 15 * ONE_MINUTE;
  const m15 = Array.from({ length: 64 }, (_, index) => ({
    openedAt: m15Start + index * 15 * ONE_MINUTE,
    close: 100 + index,
  }));
  const openInterest = Array.from({ length: 15 }, (_, index) => ({
    timestamp: base - 20 * ONE_MINUTE + index * 5 * ONE_MINUTE,
    value: 1_000 + index,
  }));
  const block = {
    id: "CAUSAL",
    era: "TEST",
    replayStartAt: new Date(base).toISOString(),
    replayEndAt: new Date(base + rows.length * FIVE_SECONDS).toISOString(),
  };
  const original = prepareBlockFeatures({ block, rows, m1Candles: m1, m15Candles: m15, openInterest, funding: [] });
  const mutatedRows = rows.map((row) => ({ ...row }));
  mutatedRows[450].buyNotional = 1_000_000;
  const mutatedM1 = m1.map((row) => ({ ...row }));
  mutatedM1.at(-1).high = 1_000_000;
  const mutatedM15 = m15.map((row) => ({ ...row }));
  mutatedM15.at(-1).close = 1_000_000;
  const mutatedOi = openInterest.map((row) => ({ ...row }));
  mutatedOi.at(-1).value = 1_000_000;
  const mutated = prepareBlockFeatures({
    block,
    rows: mutatedRows,
    m1Candles: mutatedM1,
    m15Candles: mutatedM15,
    openInterest: mutatedOi,
    funding: [],
  });
  const cutoff = 440;
  assert.deepEqual(mutated.baseline.slice(0, cutoff), original.baseline.slice(0, cutoff));
  assert.deepEqual(mutated.regime.slice(0, cutoff), original.regime.slice(0, cutoff));
  assert.deepEqual(mutated.atr.slice(0, cutoff), original.atr.slice(0, cutoff));
  assert.deepEqual(mutated.openInterestChange.slice(0, cutoff), original.openInterestChange.slice(0, cutoff));
  assert.deepEqual(mutated.prefix.buyNotional.slice(0, cutoff + 1), original.prefix.buyNotional.slice(0, cutoff + 1));
});

test("bootstrap is deterministic and the selection gate accepts explicit infinite profit factor", () => {
  const first = movingBlockBootstrapMean([1, -0.5, 0.25, 0.75], 2, 500, "fixed-seed");
  const second = movingBlockBootstrapMean([1, -0.5, 0.25, 0.75], 2, 500, "fixed-seed");
  assert.deepEqual(first, second);
  const gate = {
    minimumTrades: 2,
    minimumLongTrades: 1,
    minimumShortTrades: 1,
    minimumPositiveQuarterCount: 1,
    minimumProfitFactor: 1.15,
    minimumMeanNetR: 0,
    minimumBootstrapLowerMeanNetR: 0,
    maximumDrawdownPct: 15,
    maximumLiquidationCount: 0,
    maximumWinnerProfitConcentration: 0.7,
  };
  const result = selectionGate(gate, {
    tradeCount: 2,
    longTrades: 1,
    shortTrades: 1,
    positiveQuarterCount: 1,
    profitFactor: null,
    profitFactorInfinite: true,
    meanNetR: 1,
    bootstrapLowerMeanNetR: 0.1,
    maxDrawdownPct: 1,
    liquidationCount: 0,
    winnerProfitConcentration: 0.5,
  });
  assert.equal(result.passed, true);
});

test("ranking selects no more than one passing candidate per hypothesis family", () => {
  const result = rankAndSelectCandidates([
    rankedCandidate("a2", "SUBMINUTE_ABSORPTION_REVERSAL", true, 0.1),
    rankedCandidate("a1", "SUBMINUTE_ABSORPTION_REVERSAL", true, 0.2),
    rankedCandidate("c1", "SUBMINUTE_DEPLETION_CONTINUATION", false, 0.3),
    rankedCandidate("c2", "SUBMINUTE_DEPLETION_CONTINUATION", true, 0.1),
  ]);
  assert.deepEqual(result.selected.map((row) => row.candidateId), ["a1", "c2"]);
});

function candle(openedAt, open, high, low, close) {
  return { openedAt, open, high, low, close, volume: 1 };
}

function flowRow(index, overrides = {}) {
  const openedAt = overrides.openedAt ?? Date.parse("2023-01-01T00:00:00Z") + index * FIVE_SECONDS;
  return {
    openedAt,
    closeBestBid: 99.99,
    closeBestAsk: 100.01,
    openMidPrice: 100,
    highMidPrice: 100,
    lowMidPrice: 100,
    closeMidPrice: 100,
    meanTop5Imbalance: 0,
    meanMicropriceEdgeBps: 0,
    bidAddedTop5Notional: 0,
    bidRemovedTop5Notional: 0,
    askAddedTop5Notional: 0,
    askRemovedTop5Notional: 0,
    tradeCount: 1,
    buyNotional: 0,
    sellNotional: 0,
    buyCount: 0,
    sellCount: 0,
    openPrice: 100,
    highPrice: 100,
    lowPrice: 100,
    closePrice: 100,
    ...overrides,
  };
}

function detectionFeatures(rows, { regime, openInterestChange }) {
  const prefix = (getter) => rows.reduce((values, row) => [...values, values.at(-1) + getter(row)], [0]);
  return {
    rows,
    baseline: rows.map(() => 100),
    regime: rows.map(() => regime),
    openInterestChange: rows.map(() => openInterestChange),
    tradeImbalance: rows.map((row) => {
      const total = row.buyNotional + row.sellNotional;
      return total === 0 ? 0 : (row.buyNotional - row.sellNotional) / total;
    }),
    prefix: {
      buyNotional: prefix((row) => row.buyNotional),
      sellNotional: prefix((row) => row.sellNotional),
      bidAdded: prefix((row) => row.bidAddedTop5Notional),
      bidRemoved: prefix((row) => row.bidRemovedTop5Notional),
      askAdded: prefix((row) => row.askAddedTop5Notional),
      askRemoved: prefix((row) => row.askRemovedTop5Notional),
    },
  };
}

function marketRow(openedAt, bid, ask, overrides = {}) {
  return {
    openedAt,
    closeBestBid: bid,
    closeBestAsk: ask,
    tradeCount: 0,
    openPrice: null,
    highPrice: null,
    lowPrice: null,
    closePrice: null,
    ...overrides,
  };
}

function executionContract() {
  return {
    entryFeeRate: 0.0006,
    exitFeeRate: 0.0006,
    entrySlippageRate: 0.0002,
    exitSlippageRate: 0.0002,
    initialStopAtrMultiple: 1.25,
    minimumEffectiveStopPct: 0.004,
    targetR: 3,
    maximumHoldingMinutes: 60,
    startingEquityUsdt: 100,
    riskFractionPerTrade: 0.01,
    minimumQuantityBtc: 0.001,
    quantityStepBtc: 0.001,
    maximumNotionalUsdt: 100,
    maximumLeverage: 15,
    liquidationBufferPct: 0.6,
  };
}

function rankedCandidate(candidateId, family, passed, lower) {
  return {
    candidateId,
    family,
    gate: { passed },
    bootstrapLowerMeanNetR: lower,
    meanNetR: lower,
    profitFactor: 1.2,
    profitFactorInfinite: false,
    tradeCount: 10,
  };
}
