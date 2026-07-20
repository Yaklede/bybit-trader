import assert from "node:assert/strict";
import test from "node:test";

import {
  attachBinanceMetrics,
  enumerateUtcDays,
  metricsArchiveUrl,
  parseBinanceUmMetricsCsv,
} from "./lib/binance-um-metrics-archive.mjs";

test("enumerates end-exclusive metrics days and builds the official URL", () => {
  assert.deepEqual(enumerateUtcDays("2023-01-01T00:00:00Z", "2023-01-03T00:00:00Z"), [
    "2023-01-01",
    "2023-01-02",
  ]);
  assert.equal(
    metricsArchiveUrl("BTCUSDT", "2023-01-01"),
    "https://data.binance.vision/data/futures/um/daily/metrics/BTCUSDT/BTCUSDT-metrics-2023-01-01.zip",
  );
});

test("parses metrics rows and attaches causal open-interest changes", () => {
  const header = "create_time,symbol,sum_open_interest,sum_open_interest_value,count_toptrader_long_short_ratio,sum_toptrader_long_short_ratio,count_long_short_ratio,sum_taker_long_short_vol_ratio";
  const lines = [header];
  const candles = [];
  for (let index = 0; index < 37; index += 1) {
    const openedAtMs = Date.parse("2023-01-01T00:00:00Z") + index * 300_000;
    const timestamp = new Date(openedAtMs).toISOString().replace("T", " ").slice(0, 19);
    lines.push(`${timestamp},BTCUSDT,${100 + index},${200 + index},2,1.5,1.2,3`);
    candles.push({ openedAtMs });
  }

  const rows = parseBinanceUmMetricsCsv(lines.join("\n"));
  const coverage = attachBinanceMetrics(candles, rows);

  assert.equal(rows.length, 37);
  assert.equal(coverage.matched, 37);
  assert.ok(Math.abs(candles[3].binanceOpenInterestChange3Pct - 3) < 1e-9);
  assert.ok(Math.abs(candles[12].binanceOpenInterestChange12Pct - 12) < 1e-9);
  assert.ok(Math.abs(candles[36].binanceOpenInterestChange36Pct - 36) < 1e-9);
  assert.equal(candles[36].binanceMetricsTakerImbalance, 0.5);
});

test("keeps open-interest rows when optional ratios are unavailable", () => {
  const csv = [
    "create_time,symbol,sum_open_interest,sum_open_interest_value,count_toptrader_long_short_ratio,sum_toptrader_long_short_ratio,count_long_short_ratio,sum_taker_long_short_vol_ratio",
    "2022-01-01 00:00:00,BTCUSDT,74803.41,3456683790.32,\"\",\"\",\"\",\"\"",
  ].join("\n");

  const rows = parseBinanceUmMetricsCsv(csv);

  assert.equal(rows.length, 1);
  assert.equal(rows[0].openInterest, 74803.41);
  assert.equal(rows[0].topTraderAccountRatio, null);
  assert.equal(rows[0].takerLongShortRatio, null);
});
