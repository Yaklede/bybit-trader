import assert from "node:assert/strict";
import test from "node:test";
import {
  aggregateM15ToH4,
  buildTrendCommands,
  calculateTrendQuantity,
  simulateTrendRun,
  validateTrendProtocol,
} from "./lib/volume-confirmed-trend-research.mjs";

test("M15 evidence aggregates into one strict H4 bar", () => {
  const start = Date.UTC(2026, 0, 1);
  const rows = Array.from({ length: 16 }, (_, index) => ({
    openedAt: start + index * 15 * 60 * 1_000,
    open: 100 + index,
    high: 102 + index,
    low: 99 + index,
    close: 101 + index,
    volume: 2,
  }));
  const bars = aggregateM15ToH4(rows);
  assert.equal(bars.length, 1);
  assert.deepEqual(bars[0], {
    openedAt: start,
    open: 100,
    high: 117,
    low: 99,
    close: 116,
    volume: 32,
    sourceBarCount: 16,
  });
});

test("internal incomplete H4 evidence fails closed", () => {
  const start = Date.UTC(2026, 0, 1);
  const rows = Array.from({ length: 48 }, (_, index) => ({
    openedAt: start + index * 15 * 60 * 1_000,
    open: 100,
    high: 101,
    low: 99,
    close: 100,
    volume: 1,
  })).filter((_, index) => index !== 20);
  assert.throws(() => aggregateM15ToH4(rows), /Incomplete internal H4 bucket/);
});

test("trend direction change executes only on the next bar after volume confirmation", () => {
  const bars = Array.from({ length: 8 }, (_, index) => ({
    openedAt: Date.UTC(2026, 0, 1) + index * 4 * 60 * 60 * 1_000,
    open: 100 + index,
    high: 101 + index,
    low: 99 + index,
    close: 100 + index,
    volume: index === 5 ? 20 : 10,
    sourceBarCount: 16,
  }));
  const commands = buildTrendCommands(bars, {
    emaVotePairs: [{ fast: 1, slow: 2 }],
    minimumMajorityVotes: 1,
    volumeMedianLookbackBars: 2,
    executionDelayBars: 1,
  }, 3);
  const commandIndex = commands.findIndex(Boolean);
  assert.equal(commandIndex > 0, true);
  assert.equal(commands[commandIndex].executionIndex, commands[commandIndex].decisionIndex + 1);
  assert.equal(commands[commandIndex].decisionAt, bars[commands[commandIndex].decisionIndex].openedAt + 4 * 60 * 60 * 1_000);
});

test("quantity uses a minimum step only under the rounded exposure ceiling", () => {
  assert.equal(calculateTrendQuantity({
    equity: 100,
    price: 64_000,
    targetExposureFraction: 0.65,
    maximumRoundedExposureFraction: 0.85,
    quantityStep: 0.001,
    minimumQuantity: 0.001,
    maximumNotional: null,
  }), 0.001);
  assert.equal(calculateTrendQuantity({
    equity: 70,
    price: 64_000,
    targetExposureFraction: 0.65,
    maximumRoundedExposureFraction: 0.85,
    quantityStep: 0.001,
    minimumQuantity: 0.001,
    maximumNotional: null,
  }), 0);
});

test("funding is charged to the position held before a same-timestamp reversal", () => {
  const protocol = fixtureProtocol();
  const start = Date.UTC(2026, 0, 1);
  const bars = [
    fixtureBar(start, 100, 110),
    fixtureBar(start + 4 * 60 * 60 * 1_000, 110, 100),
    fixtureBar(start + 8 * 60 * 60 * 1_000, 100, 100),
  ];
  const commands = [
    { side: 1 },
    { side: -1 },
    null,
  ];
  const run = simulateTrendRun({
    bars,
    fundingRates: [{ timestamp: bars[1].openedAt, rate: 0.01 }],
    commands,
    protocol,
    startingEquity: 100,
    costMultiplier: 1,
  });
  assert.equal(run.totalFundingPnlUsdt < 0, true);
  assert.equal(run.closedTradeCount, 2);
  assert.equal(run.liquidationCount, 0);
  assert.equal(run.maximumEntryExposureFraction <= 0.8, true);
  assert.equal(run.maximumAdverseExposureFraction >= run.maximumEntryExposureFraction, true);
});

test("protocol validation rejects any live permission", () => {
  const protocol = fixtureProtocol();
  assert.equal(validateTrendProtocol(protocol), protocol);
  assert.throws(() => validateTrendProtocol({ ...protocol, liveExecutionAllowed: true }), /cannot enable/);
});

function fixtureBar(openedAt, open, close) {
  return {
    openedAt,
    open,
    high: Math.max(open, close) + 1,
    low: Math.min(open, close) - 1,
    close,
    volume: 10,
    sourceBarCount: 16,
  };
}

function fixtureProtocol() {
  return {
    schemaVersion: 1,
    protocolId: "fixture",
    candidateId: "fixture",
    automaticExecutionAllowed: false,
    liveExecutionAllowed: false,
    market: {
      symbol: "BTCUSDT",
      decisionTimeframe: "H4",
      sourceTimeframe: "M15",
      requiredSourceBarsPerDecisionBar: 16,
      warmupDecisionBars: 2,
    },
    strategy: {
      emaVotePairs: [{ fast: 1, slow: 2 }],
      minimumMajorityVotes: 1,
      volumeMedianLookbackBars: 1,
      volumeMedianExcludesDecisionBar: true,
      changeSideOnlyWhenVolumeAtOrAboveMedian: true,
      executionDelayBars: 1,
      holdUntilOppositeConfirmed: true,
    },
    capital: {
      startingEquitiesUsdt: ["100"],
      targetExposureFraction: "0.5",
      maximumRoundedExposureFraction: "0.8",
      quantityStepBtc: "0.001",
      minimumQuantityBtc: "0.001",
      absoluteMaximumNotionalUsdt: null,
    },
    costs: {
      oneWayFeeRate: "0",
      oneWaySlippageRate: "0",
      stressMultipliers: ["1"],
      applyActualFunding: true,
    },
  };
}
