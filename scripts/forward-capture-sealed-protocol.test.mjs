import assert from "node:assert/strict";
import test from "node:test";
import { verifyProtocol } from "./volume-flow-sealed-evaluate.mjs";
import {
  buildProtocol,
  expectedCoverage,
  generateWindows,
  parseArgs,
  verifyCoverageStats,
} from "./forward-capture-sealed-protocol.mjs";

const options = {
  db: "/tmp/forward.sqlite",
  symbol: "BTCUSDT",
  start: "2020-01-01T00:00:00.000Z",
  end: "2025-01-01T00:00:00.000Z",
  seed: 20260711,
  windowCount: 40,
  minMonths: 1,
  maxMonths: 60,
  out: null,
};

test("forward protocol requires an explicit sixty-month month-aligned observation window", () => {
  assert.throws(() => parseArgs(["--db=/tmp/forward.sqlite", "--seed=1"]), /month boundaries/);
  assert.throws(
    () =>
      parseArgs([
        "--db=/tmp/forward.sqlite",
        "--seed=1",
        "--start=2020-01-01T00:00:00.000Z",
        "--end=2024-12-01T00:00:00.000Z",
      ]),
    /at least max-months/,
  );
  assert.deepEqual(
    parseArgs([
      "--db=/tmp/forward.sqlite",
      "--seed=20260711",
      "--start=2020-01-01T00:00:00.000Z",
      "--end=2025-01-01T00:00:00.000Z",
    ]),
    options,
  );
});

test("forward protocol windows are deterministic, unique, and compatible with sealed evaluation", () => {
  const first = generateWindows(options);
  const second = generateWindows(options);
  assert.deepEqual(first, second);
  assert.equal(first.length, 40);
  assert.equal(new Set(first.map((window) => `${window.replayStartAt}:${window.replayEndAt}`)).size, 40);
  assert.equal(first.every((window) => window.durationMonths >= 1 && window.durationMonths <= 60), true);

  const protocol = buildProtocol(options, completeCoverage(options));
  assert.equal(verifyProtocol(protocol).actualHash, protocol.windowsSha256);
  assert.equal(protocol.generation.tuningAllowed, false);
});

test("forward protocol rejects missing capture or candle coverage", () => {
  const valid = completeCoverage(options);
  assert.doesNotThrow(() => verifyCoverageStats(options, valid));

  const missingOrderBook = structuredClone(valid);
  missingOrderBook.orderBook.gapCount = 1;
  assert.throws(() => verifyCoverageStats(options, missingOrderBook), /orderBookImbalanceBars must be continuous/);

  const missingM15 = structuredClone(valid);
  missingM15.candles.M15.count -= 1;
  assert.throws(() => verifyCoverageStats(options, missingM15), /marketCandles.M15 must be continuous/);

  const missingLiquidationTable = structuredClone(valid);
  missingLiquidationTable.liquidationTablePresent = false;
  assert.throws(() => verifyCoverageStats(options, missingLiquidationTable), /liquidationFlowBars table is required/);
});

function completeCoverage(protocolOptions) {
  const expected = expectedCoverage(protocolOptions);
  return {
    orderBook: dataset(expected.orderBook, protocolOptions.start, protocolOptions.end, 1),
    takerFlow: dataset(expected.takerFlow, protocolOptions.start, protocolOptions.end, 1),
    candles: {
      M1: dataset(expected.candles.M1, protocolOptions.start, protocolOptions.end, 1),
      M5: dataset(expected.candles.M5, protocolOptions.start, protocolOptions.end, 5),
      M15: dataset(expected.candles.M15, protocolOptions.start, protocolOptions.end, 15),
    },
    liquidationTablePresent: true,
  };
}

function dataset(count, start, end, intervalMinutes) {
  return {
    count,
    firstOpenedAt: start,
    lastOpenedAt: new Date(Date.parse(end) - intervalMinutes * 60_000).toISOString(),
    gapCount: 0,
  };
}
