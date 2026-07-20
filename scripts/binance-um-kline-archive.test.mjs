import assert from "node:assert/strict";
import test from "node:test";

import {
  archiveName,
  archiveUrl,
  attachBinanceFlow,
  enumerateUtcMonths,
  parseBinanceUmKlineCsv,
} from "./lib/binance-um-kline-archive.mjs";

test("enumerates end-exclusive UTC months and builds official archive paths", () => {
  assert.deepEqual(
    enumerateUtcMonths("2023-11-15T00:00:00Z", "2024-02-01T00:00:00Z"),
    ["2023-11", "2023-12", "2024-01"],
  );
  assert.equal(archiveName("BTCUSDT", "5m", "2023-11"), "BTCUSDT-5m-2023-11.zip");
  assert.equal(
    archiveUrl("BTCUSDT", "5m", "2023-11"),
    "https://data.binance.vision/data/futures/um/monthly/klines/BTCUSDT/5m/BTCUSDT-5m-2023-11.zip",
  );
});

test("parses millisecond and microsecond futures kline timestamps", () => {
  const rows = parseBinanceUmKlineCsv([
    "open_time,open,high,low,close,volume,close_time,quote_volume,count,taker_buy_volume,taker_buy_quote_volume,ignore",
    "1704067200000,42000,42100,41900,42050,10,1704067499999,420500,100,7,294350,0",
    "1735689600000000,93000,93100,92900,93050,20,1735689899999999,1861000,200,5,465250,0",
  ].join("\n"));

  assert.equal(rows.length, 2);
  assert.equal(rows[0].openedAtMs, 1_704_067_200_000);
  assert.equal(rows[0].takerImbalance, 0.4);
  assert.equal(rows[1].openedAtMs, 1_735_689_600_000);
  assert.equal(rows[1].takerImbalance, -0.5);
});

test("attaches causal Binance flow features to matching Bybit candles", () => {
  const base = Date.parse("2023-01-01T00:00:00Z");
  const candles = Array.from({ length: 50 }, (_, index) => ({
    index,
    openedAtMs: base + index * 300_000,
  }));
  const rows = candles.map((candle, index) => ({
    openedAtMs: candle.openedAtMs,
    open: 100,
    close: 100 + index / 100,
    quoteVolume: 1_000 + index,
    takerImbalance: index % 2 === 0 ? 0.2 : -0.1,
  }));

  const coverage = attachBinanceFlow(candles, rows);

  assert.equal(coverage.matched, 50);
  assert.equal(coverage.coveragePct, 100);
  assert.equal(candles[19].binanceFlowZ, null);
  assert.equal(typeof candles[20].binanceFlowZ, "number");
  assert.equal(typeof candles[49].binanceRelativeQuoteVolume, "number");
  assert.equal(typeof candles[49].binanceFlow12, "number");
  assert.equal(typeof candles[49].binanceFlow36, "number");
});
