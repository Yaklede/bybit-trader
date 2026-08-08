import assert from "node:assert/strict";
import test from "node:test";
import { auditFrozenTrendRiskPolicy } from "./lib/volume-confirmed-trend-risk-parity.mjs";
import { parseArgs } from "./volume-confirmed-trend-live-risk-parity-audit.mjs";

test("risk parity audit identifies the first daily and permanent loss-streak divergence", () => {
  const run = fixtureRun([-10, -5, -5, 20]);

  const audit = auditFrozenTrendRiskPolicy({
    run,
    maximumDailyLossFraction: 0.03,
    maximumConsecutiveLosses: 3,
  });

  assert.equal(audit.livePathSimulation, false);
  assert.equal(audit.dailyLossMeasurementKind, "CLOSED_EQUITY_VS_H4_UTC_DAY_START_PROXY");
  assert.equal(audit.frozenPathReproducible, false);
  assert.equal(audit.maximumObservedConsecutiveLosses, 3);
  assert.deepEqual(audit.firstDailyLossBreach, {
    tradeIndex: 1,
    exitAt: "2026-01-01T04:00:00.000Z",
    utcDayStartedAt: "2026-01-01T00:00:00.000Z",
    dayStartEquityUsdt: 100,
    closedEquityUsdt: 90,
    dailyLossFraction: 0.1,
    dailyLossPct: 10,
  });
  assert.deepEqual(audit.firstConsecutiveLossBreach, {
    tradeIndex: 3,
    exitAt: "2026-01-01T12:00:00.000Z",
    lossStreak: 3,
    prefixEndingEquityUsdt: 80,
    prefixNetReturnPct: -20,
    remainingFrozenTrades: 1,
  });
  assert.deepEqual(audit.reasonCodes, [
    "FROZEN_PATH_BREACHES_RUNTIME_DAILY_LOSS_LIMIT",
    "FROZEN_PATH_BREACHES_RUNTIME_CONSECUTIVE_LOSS_LIMIT",
  ]);
});

test("profitable closure resets the consecutive loss counter", () => {
  const audit = auditFrozenTrendRiskPolicy({
    run: fixtureRun([-1, -1, 3, -1, -1]),
    maximumDailyLossFraction: 1,
    maximumConsecutiveLosses: 3,
  });

  assert.equal(audit.maximumObservedConsecutiveLosses, 2);
  assert.equal(audit.firstConsecutiveLossBreach, null);
  assert.equal(audit.frozenPathReproducible, true);
});

test("risk parity audit fails closed on missing UTC day-start equity", () => {
  const run = fixtureRun([-1]);
  run.equityCurve = [];

  assert.throws(
    () => auditFrozenTrendRiskPolicy({
      run,
      maximumDailyLossFraction: 0.03,
      maximumConsecutiveLosses: 3,
    }),
    /no positive UTC day-start equity/,
  );
});

test("risk parity CLI requires explicit runtime loss limits", () => {
  assert.throws(() => parseArgs([]), /maximum-daily-loss-fraction/);
  const options = parseArgs([
    "--maximum-daily-loss-fraction=0.03",
    "--maximum-account-drawdown-fraction=0.35",
    "--maximum-consecutive-losses=3",
    "--risk-state-maximum-age-seconds=600",
    "--wallet-reconciliation-maximum-age-seconds=600",
    "--wallet-reconciliation-confirmed-mismatch-count=2",
  ]);
  assert.equal(options.maximumDailyLossFraction, 0.03);
  assert.equal(options.maximumAccountDrawdownFraction, 0.35);
  assert.equal(options.maximumConsecutiveLosses, 3);
  assert.equal(options.riskStateMaximumAgeSeconds, 600);
  assert.equal(options.walletReconciliationMaximumAgeSeconds, 600);
  assert.equal(options.walletReconciliationConfirmedMismatchCount, 2);
});

function fixtureRun(netPnls) {
  const dayStartedAt = Date.UTC(2026, 0, 1);
  return {
    startingEquityUsdt: 100,
    trades: netPnls.map((netPnl, index) => ({
      netPnl,
      exitAt: new Date(dayStartedAt + (index + 1) * 4 * 60 * 60 * 1_000).toISOString(),
    })),
    equityCurve: [{ at: dayStartedAt, equity: 100 }],
  };
}
