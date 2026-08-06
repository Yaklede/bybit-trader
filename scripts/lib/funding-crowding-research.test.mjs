import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import test from "node:test";

import {
  buildFundingTradePlan,
  calculateFundingQuantity,
  fundingDevelopmentGate,
  movingBlockBootstrapDailyMean,
  qualifyFundingEvent,
  rankFundingCandidates,
  robustFundingScore,
  simulateFundingPath,
} from "./funding-crowding-research.mjs";

test("robust funding score uses only the supplied prior settlements", () => {
  const prior = [-3, -2, -1, 0, 1, 2, 3];
  const score = robustFundingScore(prior, 10);
  assert.equal(Number(score.toFixed(6)), Number((10 / (2 * 1.4826)).toFixed(6)));
  assert.equal(robustFundingScore([1, 1, 1], 2), null);
});

test("funding setup requires aligned fresh premium and frozen thresholds", () => {
  const candidate = {
    fundingLookbackSettlements: 30,
    minimumAbsoluteRobustZ: 2,
    minimumAbsoluteFundingRate: 0.0003,
    minimumAbsolutePremium: 0.0002,
  };
  const event = {
    robustScores: { 30: 3 },
    fundingRate: 0.0005,
    premiumClose: 0.0003,
    premiumStalenessMillis: 0,
    rawEntryPrice: 100,
    atr: 1,
  };
  assert.equal(qualifyFundingEvent(event, candidate), null);
  assert.equal(qualifyFundingEvent({ ...event, premiumClose: -0.0003 }, candidate), "PREMIUM_SIGN");
  assert.equal(qualifyFundingEvent({ ...event, premiumStalenessMillis: 31 * 60_000 }, candidate), "PREMIUM_UNAVAILABLE");
});

test("quantity floors to exchange steps and rejects unaffordable minimum BTC", () => {
  const execution = {
    riskFractionPerTrade: 0.01,
    maximumNotionalUsdt: 660,
    maximumLeverage: 3,
    quantityStepBtc: 0.001,
    minimumQuantityBtc: 0.001,
  };
  assert.equal(calculateFundingQuantity({ equity: 660, entryPrice: 60_000, riskPerBtc: 2_000, execution }), 0.003);
  assert.equal(calculateFundingQuantity({
    equity: 100,
    entryPrice: 110_000,
    riskPerBtc: 2_000,
    execution: { ...execution, maximumNotionalUsdt: 100 },
  }), null);
});

test("trade plan prices stop risk after adverse entry and exit costs", () => {
  const plan = buildFundingTradePlan(
    { fundingRate: -0.001, rawEntryPrice: 100, atr: 0.25, entryAt: 0 },
    { initialStopAtrMultiple: 4, minimumStopPct: 0.02, targetR: 2, maximumHoldingHours: 24 },
    {
      entrySlippageRate: 0,
      exitSlippageRate: 0,
      entryFeeRate: 0.001,
      exitFeeRate: 0.001,
      riskFractionPerTrade: 0.01,
      maximumNotionalUsdt: 1_000,
      maximumLeverage: 3,
      liquidationBufferPct: 0.6,
      quantityStepBtc: 0.001,
      minimumQuantityBtc: 0.001,
    },
    1_000,
  );
  assert.equal(plan.direction, 1);
  assert.equal(plan.stopPrice, 98);
  assert.equal(plan.targetPrice, 104);
  assert.equal(Number(plan.riskPerBtc.toFixed(3)), 2.198);
  assert.equal(buildFundingTradePlan(
    { fundingRate: -0.001, rawEntryPrice: 100, atr: 20, entryAt: 0 },
    { initialStopAtrMultiple: 4, minimumStopPct: 0.02, targetR: 2, maximumHoldingHours: 24 },
    {
      entrySlippageRate: 0,
      exitSlippageRate: 0,
      entryFeeRate: 0,
      exitFeeRate: 0,
      riskFractionPerTrade: 0.01,
      maximumNotionalUsdt: 1_000,
      maximumLeverage: 3,
      liquidationBufferPct: 0.6,
      quantityStepBtc: 0.001,
      minimumQuantityBtc: 0.001,
    },
    1_000,
  ), null);
});

test("same M1 stop and target conflict is resolved stop first", () => {
  const entryAt = Date.parse("2020-01-01T00:05:00Z");
  const db = m1Database([{ openedAt: entryAt, open: 100, high: 105, low: 97, close: 100 }]);
  try {
    const path = simulateFundingPath(db, "BTCUSDT", {
      direction: 1,
      entryAt,
      rawEntryPrice: 100,
      entryPrice: 100,
      stopPrice: 98,
      targetPrice: 104,
      liquidationPrice: 50,
      maximumExitAt: entryAt + 60 * 60_000,
    }, [], zeroCostExecution());
    assert.equal(path.exitReason, "STOP");
    assert.equal(path.exitPrice, 98);
    assert.throws(() => simulateFundingPath(db, "BTCUSDT", {
      direction: 1,
      entryAt,
      rawEntryPrice: 101,
      entryPrice: 101,
      stopPrice: 98,
      targetPrice: 104,
      liquidationPrice: 50,
      maximumExitAt: entryAt + 60 * 60_000,
    }, [], zeroCostExecution()), /M1 and M5 entry prices differ/);
  } finally {
    db.close();
  }
});

test("actual subsequent funding is booked before a causal five-minute funding exit", () => {
  const entryAt = Date.parse("2020-01-01T00:05:00Z");
  const rows = [];
  for (let timestamp = entryAt; timestamp <= entryAt + 16 * 60 * 60_000 + 5 * 60_000; timestamp += 60_000) {
    rows.push({ openedAt: timestamp, open: 100, high: 100, low: 100, close: 100 });
  }
  const db = m1Database(rows);
  const funding = [
    { timestamp: entryAt + 8 * 60 * 60_000, rate: 0.001 },
    { timestamp: entryAt + 16 * 60 * 60_000, rate: -0.002 },
  ];
  try {
    const path = simulateFundingPath(db, "BTCUSDT", {
      direction: -1,
      entryAt,
      rawEntryPrice: 100,
      entryPrice: 100,
      stopPrice: 200,
      targetPrice: 1,
      liquidationPrice: 300,
      maximumExitAt: entryAt + 24 * 60 * 60_000,
    }, funding, zeroCostExecution());
    assert.equal(path.exitReason, "FUNDING_CHANGE");
    assert.equal(path.exitAt, funding[1].timestamp + 5 * 60_000);
    assert.equal(path.fundingSettlementCount, 2);
    assert.equal(Number(path.fundingPnlPerBtc.toFixed(8)), -0.1);
  } finally {
    db.close();
  }
});

test("daily moving-block bootstrap is deterministic and ranking selects at most one pass", () => {
  const first = movingBlockBootstrapDailyMean([0, 1, -0.5, 0.25, 0, 0.5, -0.1], {
    blockDays: 3,
    samples: 500,
    confidence: 0.95,
    seed: "fixed",
  });
  const second = movingBlockBootstrapDailyMean([0, 1, -0.5, 0.25, 0, 0.5, -0.1], {
    blockDays: 3,
    samples: 500,
    confidence: 0.95,
    seed: "fixed",
  });
  assert.deepEqual(first, second);
  const gate = fundingDevelopmentGate({
    minimumTrades: 1,
    minimumLongTrades: 1,
    minimumShortTrades: 1,
    minimumPositiveBlockCount: 1,
    minimumProfitFactor: 1,
    minimumMeanNetR: 0,
    minimumBootstrapLowerMeanNetR: 0,
    maximumDrawdownPct: 20,
    maximumLiquidationCount: 0,
    maximumWinnerProfitConcentration: 1,
  }, passingSummary());
  assert.equal(gate.passed, true);
  const ranked = rankFundingCandidates([
    { ...passingSummary(), candidateId: "b", gate, bootstrapLowerMeanNetR: 0.1 },
    { ...passingSummary(), candidateId: "a", gate, bootstrapLowerMeanNetR: 0.2 },
  ]);
  assert.equal(ranked.selected.length, 1);
  assert.equal(ranked.selected[0].candidateId, "a");
});

function m1Database(rows) {
  const db = new DatabaseSync(":memory:");
  db.exec(`
    CREATE TABLE marketCandles (
      symbol TEXT NOT NULL,timeframe TEXT NOT NULL,opened_at TEXT NOT NULL,
      open TEXT NOT NULL,high TEXT NOT NULL,low TEXT NOT NULL,close TEXT NOT NULL
    );
  `);
  const insert = db.prepare("INSERT INTO marketCandles VALUES ('BTCUSDT','M1',?,?,?,?,?)");
  for (const row of rows) {
    insert.run(instant(row.openedAt), row.open, row.high, row.low, row.close);
  }
  return db;
}

function zeroCostExecution() {
  return { entryFeeRate: 0, exitFeeRate: 0, exitSlippageRate: 0, maximumLeverage: 3 };
}

function passingSummary() {
  return {
    tradeCount: 2,
    longTrades: 1,
    shortTrades: 1,
    positiveBlockCount: 1,
    profitFactor: 2,
    profitFactorInfinite: false,
    meanNetR: 0.2,
    bootstrapLowerMeanNetR: 0.1,
    maxDrawdownPct: 5,
    liquidationCount: 0,
    winnerProfitConcentration: 0.5,
  };
}

function instant(timestamp) {
  return new Date(timestamp).toISOString().replace(".000Z", "Z");
}
