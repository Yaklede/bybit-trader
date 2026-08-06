import assert from "node:assert/strict";
import fs from "node:fs/promises";
import path from "node:path";
import test from "node:test";

import {
  MINUTE_MS,
  TIMEFRAME_MS,
  attachClosedM15Regimes,
  detectM5Setup,
  expandCandidates,
  metricsForTrades,
  prepareHigherTimeframeCandles,
  resolveExitOnCandle,
  runCandidateBatch,
} from "./lib/volume-impact-state-research.mjs";

const repoRoot = path.resolve(new URL("..", import.meta.url).pathname);
const protocol = JSON.parse(
  await fs.readFile(path.join(repoRoot, "config/volume-impact-state-development-v1.json"), "utf8"),
);
const v2Protocol = JSON.parse(
  await fs.readFile(path.join(repoRoot, "config/volume-structure-development-v2.json"), "utf8"),
);
const v3Protocol = JSON.parse(
  await fs.readFile(path.join(repoRoot, "config/asymmetric-cluster-absorption-development-v3.json"), "utf8"),
);

test("candidate expansion preserves the predeclared two-family 24-trial boundary", () => {
  const candidates = expandCandidates(protocol);
  assert.equal(candidates.length, 24);
  assert.equal(candidates.filter((candidate) => candidate.family === "VOLUME_IMPACT_CONTINUATION").length, 12);
  assert.equal(candidates.filter((candidate) => candidate.family === "VOLUME_EXHAUSTION_REVERSAL").length, 12);
  assert.equal(new Set(candidates.map((candidate) => candidate.id)).size, 24);
});

test("M15 regime attachment never uses a bar closing after the M5 decision", () => {
  const fixture = continuationFixture();
  const preparedM5 = prepareHigherTimeframeCandles(fixture.m5, TIMEFRAME_MS.M5);
  const preparedM15 = prepareHigherTimeframeCandles(fixture.m15, TIMEFRAME_MS.M15);
  attachClosedM15Regimes(preparedM5, preparedM15, 4);
  const before = preparedM5.at(-1).m15Regime;
  assert.ok(before.sourceCloseTimeMs <= preparedM5.at(-1).closeTimeMs);
  assert.equal(before.direction, "BUY");

  const future = candle(preparedM15.at(-1).closeTimeMs, 50, 51, 1, 2, 100, TIMEFRAME_MS.M15);
  const withFuture = prepareHigherTimeframeCandles([...fixture.m15, future], TIMEFRAME_MS.M15);
  const replayedM5 = prepareHigherTimeframeCandles(fixture.m5, TIMEFRAME_MS.M5);
  attachClosedM15Regimes(replayedM5, withFuture, 4);
  assert.deepEqual(replayedM5.at(-1).m15Regime, before);
});

test("higher-timeframe preparation rejects a missing bar", () => {
  const first = candle(Date.parse("2020-01-01T00:00:00Z"), 100, 101, 99, 100.5, 1, TIMEFRAME_MS.M5);
  const afterGap = candle(Date.parse("2020-01-01T00:10:00Z"), 100.5, 101, 100, 100.8, 1, TIMEFRAME_MS.M5);
  assert.throws(
    () => prepareHigherTimeframeCandles([first, afterGap], TIMEFRAME_MS.M5),
    /must be contiguous/,
  );
});

test("M5 setup requires a later M1 confirmation and fills only at the next contiguous M1 open", async () => {
  const fixture = continuationFixture();
  const candidate = expandCandidates(protocol).find((item) => item.family === "VOLUME_IMPACT_CONTINUATION");
  const batch = await runCandidateBatch({
    m1Candles: fixture.m1,
    m5Candles: fixture.m5,
    m15Candles: fixture.m15,
    candidates: [candidate],
    protocol,
  });
  const result = batch.candidates[0];
  assert.equal(result.dataGapCount, 0);
  assert.equal(result.trades.length, 1, JSON.stringify(result, null, 2));
  const trade = result.trades[0];
  assert.equal(trade.setupAt, "2020-06-01T00:15:00.000Z");
  assert.equal(trade.confirmationAt, "2020-06-01T00:16:00.000Z");
  assert.equal(trade.openedAt, "2020-06-01T00:16:00.000Z");
  assert.ok(Date.parse(trade.confirmationAt) > Date.parse(trade.setupAt));
});

test("a missing next M1 candle rejects the pending entry instead of filling across the gap", async () => {
  const fixture = continuationFixture();
  const candidate = expandCandidates(protocol).find((item) => item.family === "VOLUME_IMPACT_CONTINUATION");
  const missingEntry = fixture.m1.filter((item) => item.openedAt !== "2020-06-01T00:16:00.000Z");
  const batch = await runCandidateBatch({
    m1Candles: missingEntry,
    m5Candles: fixture.m5,
    m15Candles: fixture.m15,
    candidates: [candidate],
    protocol,
  });
  assert.equal(batch.candidates[0].trades.length, 0);
  assert.ok(batch.candidates[0].dataGapCount >= 1);
  assert.ok(batch.candidates[0].rejectedDiscontinuousEntries >= 1);
});

test("same-bar stop and target conflict is stop-first, while a gap stop fills at the open", () => {
  const position = {
    side: "BUY",
    stopPrice: 95,
    targetPrice: 105,
    liquidationPrice: 90,
    maxCloseTimeMs: Date.parse("2020-01-02T00:00:00Z"),
    trailingMoved: false,
  };
  const conflict = normalizeFixtureCandle("2020-01-01T00:00:00Z", 100, 106, 94, 101);
  assert.deepEqual(resolveExitOnCandle(position, conflict), { price: 95, reason: "STOP" });
  const gap = normalizeFixtureCandle("2020-01-01T00:01:00Z", 94, 96, 93, 95);
  assert.deepEqual(resolveExitOnCandle(position, gap), { price: 94, reason: "STOP" });
});

test("v2 continuation waits for a post-setup retest and fills on the following M1", async () => {
  const fixture = continuationFixture();
  fixture.m1[915] = candle(
    Date.parse("2020-06-01T00:15:00Z"),
    101.0,
    101.3,
    100.75,
    101.2,
    100,
    MINUTE_MS,
  );
  fixture.m1[916] = candle(
    Date.parse("2020-06-01T00:16:00Z"),
    101.22,
    101.45,
    101.1,
    101.35,
    100,
    MINUTE_MS,
  );
  const candidate = expandCandidates(v2Protocol).find((item) => item.family === "VOLUME_BREAKOUT_RETEST_CONTINUATION");
  const batch = await runCandidateBatch({
    m1Candles: fixture.m1,
    m5Candles: fixture.m5,
    m15Candles: fixture.m15,
    candidates: [candidate],
    protocol: v2Protocol,
  });
  const trade = batch.candidates[0].trades[0];
  assert.ok(trade, JSON.stringify(batch.candidates[0], null, 2));
  assert.equal(trade.setupAt, "2020-06-01T00:15:00.000Z");
  assert.equal(trade.confirmationAt, "2020-06-01T00:16:00.000Z");
  assert.equal(trade.openedAt, "2020-06-01T00:16:00.000Z");
});

test("v2 clustered reversal uses two closed M5 bars and the closed M15 direction", () => {
  const fixture = clusteredReversalFixture();
  const m5 = prepareHigherTimeframeCandles(fixture.m5, TIMEFRAME_MS.M5);
  const m15 = prepareHigherTimeframeCandles(fixture.m15, TIMEFRAME_MS.M15);
  attachClosedM15Regimes(m5, m15, 4);
  const candidate = expandCandidates(v2Protocol).find((item) => item.family === "CLUSTERED_VOLUME_EXHAUSTION_REVERSAL");
  const setup = detectM5Setup(candidate, m5, m5.length - 1);
  assert.ok(setup);
  assert.equal(setup.side, "SELL");
  assert.equal(setup.m15Regime.direction, "SELL");
  assert.ok(setup.relativeVolume >= candidate.minimumClusterRelativeVolume);
  assert.ok(setup.displacementAtr <= candidate.maximumClusterDisplacementAtr);
});

test("v3 asymmetric cluster applies a finite volume band to short reversals", () => {
  const fixture = clusteredReversalFixture();
  const m5 = prepareHigherTimeframeCandles(fixture.m5, TIMEFRAME_MS.M5);
  const m15 = prepareHigherTimeframeCandles(fixture.m15, TIMEFRAME_MS.M15);
  attachClosedM15Regimes(m5, m15, 4);
  const v3 = JSON.parse(JSON.stringify(v3Protocol));
  const candidate = expandCandidates(v3).find((item) => item.m1ConfirmationWindowBars === 4);
  const setup = detectM5Setup(candidate, m5, m5.length - 1);
  assert.equal(setup?.side, "SELL");
  assert.equal(
    detectM5Setup({ ...candidate, maximumShortClusterRelativeVolumeExclusive: 1.9 }, m5, m5.length - 1),
    null,
  );
});

test("event metrics separate active-month quality from no-trade coverage", () => {
  const trade = {
    openedAt: "2020-01-10T00:00:00.000Z",
    closedAt: "2020-01-10T01:00:00.000Z",
    openedAtMs: Date.parse("2020-01-10T00:00:00Z"),
    closedAtMs: Date.parse("2020-01-10T01:00:00Z"),
    netR: 1,
    maeR: -0.25,
    exitReason: "TARGET",
  };
  const metrics = metricsForTrades(
    [trade],
    "2020-01-01T00:00:00Z",
    "2020-04-01T00:00:00Z",
    v3Protocol,
  );
  assert.ok(Math.abs(metrics.positiveMonthRatio - (1 / 3)) < 1e-7);
  assert.equal(metrics.activeMonthPositiveRatio, 1);
  assert.ok(Math.abs(metrics.activeMonthCoverage - (1 / 3)) < 1e-7);
});

function continuationFixture() {
  const replayStart = Date.parse(protocol.sourceData.developmentReplayStartsAt);
  const base = replayStart - 15 * 60 * MINUTE_MS;
  const m15 = Array.from({ length: 61 }, (_, index) => {
    const open = 100 + index * 0.08;
    return candle(base + index * TIMEFRAME_MS.M15, open, open + 0.12, open - 0.04, open + 0.08, 300, TIMEFRAME_MS.M15);
  });
  const m5 = Array.from({ length: 183 }, (_, index) => {
    const open = 100 + index * 0.005;
    return candle(base + index * TIMEFRAME_MS.M5, open, open + 0.08, open - 0.03, open + 0.04, 100, TIMEFRAME_MS.M5);
  });
  m5[m5.length - 1] = candle(
    Date.parse("2020-06-01T00:10:00Z"),
    100.9,
    102.2,
    100.85,
    102.1,
    250,
    TIMEFRAME_MS.M5,
  );
  const m1 = Array.from({ length: 920 }, (_, index) =>
    candle(base + index * MINUTE_MS, 101, 101.06, 100.96, 101.02, 100, MINUTE_MS),
  );
  m1[914] = candle(Date.parse("2020-06-01T00:14:00Z"), 102, 102.4, 101.95, 102.3, 100, MINUTE_MS);
  m1[915] = candle(Date.parse("2020-06-01T00:15:00Z"), 102.15, 102.5, 102.1, 102.4, 100, MINUTE_MS);
  m1[916] = candle(Date.parse("2020-06-01T00:16:00Z"), 102.35, 102.55, 102.25, 102.45, 100, MINUTE_MS);
  m1[917] = candle(Date.parse("2020-06-01T00:17:00Z"), 102.45, 102.65, 102.35, 102.55, 100, MINUTE_MS);
  return { m1, m5, m15 };
}

function clusteredReversalFixture() {
  const replayStart = Date.parse(v2Protocol.sourceData.developmentReplayStartsAt);
  const base = replayStart - 15 * 60 * MINUTE_MS;
  const m15 = Array.from({ length: 61 }, (_, index) => {
    const open = 110 - index * 0.08;
    return candle(base + index * TIMEFRAME_MS.M15, open, open + 0.04, open - 0.12, open - 0.08, 300, TIMEFRAME_MS.M15);
  });
  const m5 = Array.from({ length: 183 }, (_, index) => {
    const open = 110 - index * 0.005;
    return candle(base + index * TIMEFRAME_MS.M5, open, open + 0.04, open - 0.08, open - 0.04, 100, TIMEFRAME_MS.M5);
  });
  m5[181] = candle(Date.parse("2020-06-01T00:05:00Z"), 109.0, 110.0, 108.9, 109.6, 200, TIMEFRAME_MS.M5);
  m5[182] = candle(Date.parse("2020-06-01T00:10:00Z"), 109.6, 110.2, 109.0, 109.05, 200, TIMEFRAME_MS.M5);
  return { m5, m15 };
}

function candle(openedAtMs, open, high, low, close, volume, durationMs) {
  return {
    openedAt: new Date(openedAtMs).toISOString(),
    openedAtMs,
    closeTimeMs: openedAtMs + durationMs,
    open,
    high,
    low,
    close,
    volume,
  };
}

function normalizeFixtureCandle(openedAt, open, high, low, close) {
  const openedAtMs = Date.parse(openedAt);
  return { openedAt, openedAtMs, closeTimeMs: openedAtMs + MINUTE_MS, open, high, low, close, volume: 1 };
}
