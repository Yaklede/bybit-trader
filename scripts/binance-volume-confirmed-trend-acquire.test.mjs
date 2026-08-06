import assert from "node:assert/strict";
import test from "node:test";

import {
  enumerateUtcMonths,
  monthlyArchiveDescriptor,
  parseBinanceFundingRates,
  parseBinanceH4Klines,
  parsePublishedChecksum,
} from "./lib/binance-um-monthly-evidence.mjs";

test("enumerates an end-exclusive UTC month range", () => {
  assert.deepEqual(
    enumerateUtcMonths("2020-01-01T00:00:00Z", "2020-04-01T00:00:00Z"),
    ["2020-01", "2020-02", "2020-03"],
  );
  assert.throws(
    () => enumerateUtcMonths("2020-01-02T00:00:00Z", "2020-04-01T00:00:00Z"),
    /month boundary/,
  );
});

test("builds official Binance USD-M monthly archive URLs", () => {
  assert.deepEqual(monthlyArchiveDescriptor("klines", "BTCUSDT", "2020-01"), {
    dataset: "klines",
    month: "2020-01",
    name: "BTCUSDT-4h-2020-01.zip",
    url: "https://data.binance.vision/data/futures/um/monthly/klines/BTCUSDT/4h/BTCUSDT-4h-2020-01.zip",
  });
  assert.equal(
    monthlyArchiveDescriptor("fundingRate", "BTCUSDT", "2020-01").url,
    "https://data.binance.vision/data/futures/um/monthly/fundingRate/BTCUSDT/BTCUSDT-fundingRate-2020-01.zip",
  );
});

test("accepts only a checksum bound to the expected archive name", () => {
  const digest = "a".repeat(64);
  assert.equal(parsePublishedChecksum(`${digest}  BTCUSDT-4h-2020-01.zip`, "BTCUSDT-4h-2020-01.zip"), digest);
  assert.throws(() => parsePublishedChecksum(`${digest}  another.zip`, "expected.zip"), /Invalid Binance checksum/);
});

test("parses headerless H4 klines and microsecond timestamps", () => {
  const milliseconds = Date.UTC(2026, 0, 1);
  const csv = [
    `${milliseconds},100,110,90,105,12,0,0,0,0,0,0`,
    `${(milliseconds + 4 * 60 * 60 * 1_000) * 1_000},105,115,100,112,13,0,0,0,0,0,0`,
  ].join("\n");
  const rows = parseBinanceH4Klines(csv, "2026-01");
  assert.equal(rows.length, 2);
  assert.equal(rows[1].openedAt, milliseconds + 4 * 60 * 60 * 1_000);
  assert.equal(rows[1].close, "112");
});

test("normalizes millisecond funding jitter to its H4 settlement boundary", () => {
  const boundary = Date.UTC(2026, 0, 1, 8);
  const rows = parseBinanceFundingRates([
    "calc_time,funding_interval_hours,last_funding_rate",
    `${boundary + 2},8,0.0001`,
  ].join("\n"), "2026-01");
  assert.deepEqual(rows, [{
    timestamp: boundary,
    sourceTimestamp: boundary + 2,
    intervalHours: 8,
    rate: "0.0001",
  }]);
});

test("rejects funding that cannot be assigned to a causal H4 boundary", () => {
  const boundary = Date.UTC(2026, 0, 1, 8);
  assert.throws(
    () => parseBinanceFundingRates(`${boundary + 90_000},8,0.0001`, "2026-01"),
    /off H4 boundary/,
  );
});
