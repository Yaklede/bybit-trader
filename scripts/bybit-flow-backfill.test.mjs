import test from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import {
  applyTrade,
  coverageReport,
  ensureSchema,
  minuteEpochMillis,
  parseArgs,
} from "./bybit-flow-backfill.mjs";

test("parseArgs validates date ranges and dataset names", () => {
  const options = parseArgs(["--db=/tmp/flow.sqlite", "--start=2024-01-01", "--end=2024-02-01", "--datasets=oi,coverage"]);
  assert.equal(options.start, "2024-01-01");
  assert.deepEqual([...options.datasets], ["oi", "coverage"]);
  assert.throws(() => parseArgs(["--start=2024-02-01", "--end=2024-01-01"]));
  assert.throws(() => parseArgs(["--datasets=unknown"]));
});

test("minuteEpochMillis supports official decimal-second and epoch-millisecond timestamps", () => {
  assert.equal(minuteEpochMillis("1704067200.2353"), 1_704_067_200_000);
  assert.equal(minuteEpochMillis("1704067259999"), 1_704_067_200_000);
});

test("applyTrade aggregates taker sides without mixing counts", () => {
  const columns = {timestamp: 0, side: 1, size: 2, price: 3};
  const bars = new Map();
  applyTrade(["1704067200.1", "Buy", "0.1", "40000"], columns, bars);
  applyTrade(["1704067201.1", "Sell", "0.2", "40010"], columns, bars);
  const bar = [...bars.values()][0];
  assert.equal(bar.buyBase, 0.1);
  assert.equal(bar.sellBase, 0.2);
  assert.equal(bar.buyCount, 1);
  assert.equal(bar.sellCount, 1);
});

test("ensureSchema is idempotent and coverage reports missing positive-volume minutes", () => {
  const db = new DatabaseSync(":memory:");
  ensureSchema(db);
  ensureSchema(db);
  db.exec(`
    CREATE TABLE marketCandles(symbol TEXT, timeframe TEXT, opened_at TEXT, volume TEXT);
    INSERT INTO marketCandles VALUES ('BTCUSDT','M1','2024-01-01T00:00:00Z','1');
  `);
  const report = coverageReport(db, {
    symbol: "BTCUSDT",
    start: "2024-01-01",
    end: "2024-01-01",
  });
  assert.equal(Number(report.takerFlow.count), 0);
  assert.equal(report.missingPositiveVolumeTradeMinutes, 1);
  db.close();
});
