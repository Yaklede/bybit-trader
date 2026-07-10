import assert from "node:assert/strict";
import test from "node:test";
import { DatabaseSync } from "node:sqlite";
import {
  aggregateOrderBookBuckets,
  aggregateTakerFlowBuckets,
  buildDiagnosticReport,
  liquidationBand,
  loadRecords,
  orderBookDirection,
  parseArgs,
  takerFlowDirection,
} from "./bybit-forward-capture-diagnostics.mjs";

test("parseArgs requires an explicit immutable observation window", () => {
  const options = parseArgs([
    "--db=/tmp/forward.sqlite",
    "--start=2026-07-01T00:00:00Z",
    "--end=2026-07-02T00:00:00Z",
    "--horizons-m5=1,3,12",
  ]);

  assert.deepEqual(options.horizonsM5, [1, 3, 12]);
  assert.throws(() => parseArgs(["--start=2026-07-02T00:00:00Z", "--end=2026-07-01T00:00:00Z"]));
  assert.throws(() =>
    parseArgs([
      "--start=2026-07-01T00:00:00Z",
      "--end=2026-07-02T00:00:00Z",
      "--horizons-m5=1,1",
    ]),
  );
});

test("five-minute aggregation requires every captured minute", () => {
  const completeRows = [0, 1, 2, 3, 4].map((minute) => ({
    opened_at: `2026-07-01T00:0${minute}:00Z`,
    sample_count: 2,
    mean_imbalance: "0.2",
    mean_spread_bps: "1",
  }));
  const incompleteRows = completeRows.filter((row) => !row.opened_at.includes("00:03"));

  assert.equal(aggregateOrderBookBuckets(completeRows)[0].complete, true);
  assert.equal(aggregateOrderBookBuckets(incompleteRows)[0].complete, false);
});

test("taker-flow aggregation requires every captured minute and preserves direction", () => {
  const completeRows = [0, 1, 2, 3, 4].map((minute) => ({
    opened_at: `2026-07-01T00:0${minute}:00Z`,
    taker_buy_notional: "100",
    taker_sell_notional: "50",
    buy_trade_count: 2,
    sell_trade_count: 1,
  }));
  const incompleteRows = completeRows.filter((row) => !row.opened_at.includes("00:03"));

  const complete = aggregateTakerFlowBuckets(completeRows)[0];
  assert.equal(complete.complete, true);
  assert.equal(complete.buyNotional, 500);
  assert.equal(complete.sellNotional, 250);
  assert.equal(takerFlowDirection(complete.buyNotional, complete.sellNotional), "BUY");
  assert.equal(aggregateTakerFlowBuckets(incompleteRows)[0].complete, false);
});

test("diagnostic enters only after a complete capture bucket closes", () => {
  const db = new DatabaseSync(":memory:");
  try {
    db.exec(`
      CREATE TABLE orderBookImbalanceBars(opened_at TEXT, symbol TEXT, sample_count INTEGER, mean_imbalance TEXT, mean_spread_bps TEXT);
      CREATE TABLE liquidationFlowBars(opened_at TEXT, symbol TEXT, long_liquidation_notional TEXT, short_liquidation_notional TEXT);
      CREATE TABLE takerFlowBars(opened_at TEXT, symbol TEXT, taker_buy_notional TEXT, taker_sell_notional TEXT, buy_trade_count INTEGER, sell_trade_count INTEGER);
      CREATE TABLE marketCandles(opened_at TEXT, symbol TEXT, timeframe TEXT, open TEXT, close TEXT);
    `);
    const orderBook = db.prepare("INSERT INTO orderBookImbalanceBars VALUES (?, 'BTCUSDT', 2, '0.2', '1')");
    for (let minute = 0; minute < 5; minute += 1) orderBook.run(`2026-07-01T00:0${minute}:00Z`);
    const takerFlow = db.prepare("INSERT INTO takerFlowBars VALUES (?, 'BTCUSDT', '200', '100', 2, 1)");
    for (let minute = 0; minute < 5; minute += 1) takerFlow.run(`2026-07-01T00:0${minute}:00Z`);
    db.prepare("INSERT INTO liquidationFlowBars VALUES ('2026-07-01T00:01:00Z', 'BTCUSDT', '0', '250')").run();
    const candle = db.prepare("INSERT INTO marketCandles VALUES (?, 'BTCUSDT', 'M5', ?, ?)");
    candle.run("2026-07-01T00:05:00Z", "100", "103");
    candle.run("2026-07-01T00:10:00Z", "103", "104");

    const options = {
      symbol: "BTCUSDT",
      start: "2026-07-01T00:00:00Z",
      end: "2026-07-01T00:04:00Z",
      horizonsM5: [1],
      roundTripCostBps: 10,
      minSamples: 1,
    };
    const loaded = loadRecords(db, options);
    const report = buildDiagnosticReport(options, loaded);

    assert.equal(loaded.records[0].openedAt, "2026-07-01T00:00:00Z");
    assert.equal(loaded.records[0].returnsByHorizon[1], 3);
    assert.equal(loaded.records[0].takerDirection, "BUY");
    assert.equal(loaded.records[0].liquidationBand, "SHORT_FLUSH");
    assert.equal(loaded.coverage.completeCommonM5Buckets, 1);
    assert.equal(report.horizons[0].bookOnly[0].meanNetReturnPct, 2.9);
    assert.equal(report.horizons[0].bookAndTaker[0].group, "book=BUY|taker=BUY");
  } finally {
    db.close();
  }
});

test("fixed bands remain descriptive and do not derive a threshold from data", () => {
  assert.equal(orderBookDirection(0.1), "BUY");
  assert.equal(orderBookDirection(-0.1), "SELL");
  assert.equal(orderBookDirection(0.09), "NEUTRAL");
  assert.equal(liquidationBand(0, 0), "NONE");
  assert.equal(liquidationBand(100, 0), "LONG_FLUSH");
  assert.equal(liquidationBand(0, 100), "SHORT_FLUSH");
  assert.equal(takerFlowDirection(100, 0), "BUY");
  assert.equal(takerFlowDirection(0, 100), "SELL");
  assert.equal(takerFlowDirection(100, 100), "NEUTRAL");
});
