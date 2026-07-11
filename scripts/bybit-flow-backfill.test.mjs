import test from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import {
  applyTrade,
  completeTradeBarsForCandles,
  coverageReport,
  ensureSchema,
  hasCompleteTakerFlowDay,
  minuteEpochMillis,
  parseArgs,
} from "./bybit-flow-backfill.mjs";

test("parseArgs validates date ranges and dataset names", () => {
  const options = parseArgs(["--db=/tmp/flow.sqlite", "--start=2024-01-01", "--end=2024-02-01", "--datasets=oi,account-ratio,coverage"]);
  assert.equal(options.start, "2024-01-01");
  assert.deepEqual([...options.datasets], ["oi", "account-ratio", "coverage"]);
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

test("complete taker-flow day check rejects a partial historical repair", () => {
  const db = new DatabaseSync(":memory:");
  ensureSchema(db);
  const insert = db.prepare(`
    INSERT INTO takerFlowBars(
      symbol, opened_at, taker_buy_base, taker_buy_notional,
      taker_sell_base, taker_sell_notional, buy_trade_count, sell_trade_count
    ) VALUES ('BTCUSDT', ?, '1', '100', '0', '0', 1, 0)
  `);
  const start = Date.parse("2024-01-01T00:00:00Z");
  for (let minute = 0; minute < 1_440; minute += 1) {
    if (minute !== 240) insert.run(new Date(start + minute * 60_000).toISOString().replace(".000Z", "Z"));
  }
  assert.equal(hasCompleteTakerFlowDay(db, "BTCUSDT", "2024-01-01"), false);

  insert.run("2024-01-01T04:00:00Z");
  assert.equal(hasCompleteTakerFlowDay(db, "BTCUSDT", "2024-01-01"), true);
  db.close();
});

test("official zero-volume candles complete a trade archive without fabricating positive flow", () => {
  const db = new DatabaseSync(":memory:");
  ensureSchema(db);
  db.exec("CREATE TABLE marketCandles(symbol TEXT, timeframe TEXT, opened_at TEXT, volume TEXT)");
  const insertCandle = db.prepare("INSERT INTO marketCandles VALUES ('BTCUSDT', 'M1', ?, ?)");
  const start = Date.parse("2024-01-01T00:00:00Z");
  const bars = new Map();
  for (let minute = 0; minute < 1_440; minute += 1) {
    const openedAt = start + minute * 60_000;
    insertCandle.run(new Date(openedAt).toISOString().replace(".000Z", "Z"), minute === 240 ? "0" : "1");
    if (minute !== 240) {
      bars.set(openedAt, { buyBase: 1, buyNotional: 100, sellBase: 0, sellNotional: 0, buyCount: 1, sellCount: 0 });
    }
  }
  completeTradeBarsForCandles(db, "BTCUSDT", "2024-01-01", bars);
  assert.equal(bars.size, 1_440);
  assert.deepEqual(bars.get(start + 240 * 60_000), {
    buyBase: 0,
    buyNotional: 0,
    sellBase: 0,
    sellNotional: 0,
    buyCount: 0,
    sellCount: 0,
  });

  const positiveGap = new Map(bars);
  positiveGap.delete(start + 241 * 60_000);
  assert.throws(
    () => completeTradeBarsForCandles(db, "BTCUSDT", "2024-01-01", positiveGap),
    /misses positive-volume minute/,
  );
  db.close();
});
