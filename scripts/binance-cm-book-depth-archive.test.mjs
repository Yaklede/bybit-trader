import assert from "node:assert/strict";
import test from "node:test";

import {
  attachBinanceDepth,
  bookDepthArchiveUrl,
  downloadBinanceCmBookDepthArchives,
  enumerateUtcDays,
  parseBinanceCmBookDepth5m,
} from "./lib/binance-cm-book-depth-archive.mjs";

test("enumerates end-exclusive depth days and builds the official archive URL", () => {
  assert.deepEqual(enumerateUtcDays("2024-01-01T12:00:00Z", "2024-01-03T00:00:00Z"), ["2024-01-01", "2024-01-02"]);
  assert.equal(
    bookDepthArchiveUrl("BTCUSD_PERP", "2024-01-01"),
    "https://data.binance.vision/data/futures/cm/daily/bookDepth/BTCUSD_PERP/BTCUSD_PERP-bookDepth-2024-01-01.zip",
  );
});

test("aggregates complete 30-second depth snapshots into a causal five-minute row", () => {
  const lines = ["timestamp,percentage,depth,notional"];
  for (let snapshot = 0; snapshot < 10; snapshot += 1) {
    const timestamp = `2024-01-01 00:0${Math.floor(snapshot / 2)}:${snapshot % 2 === 0 ? "00" : "30"}`;
    for (const percentage of [-5, -4, -3, -2, -1, 1, 2, 3, 4, 5]) {
      const notional = percentage < 0 ? 150 : 50;
      lines.push(`${timestamp},${percentage},1000,${notional}`);
    }
  }

  const rows = parseBinanceCmBookDepth5m(lines.join("\n"));

  assert.equal(rows.length, 1);
  assert.equal(rows[0].snapshotCount, 10);
  assert.equal(rows[0].complete, true);
  assert.equal(rows[0].imbalance1, 0.5);
  assert.equal(rows[0].imbalanceChange1, 0);
  assert.equal(rows[0].totalNotional1, 200);
});

test("attaches only complete depth buckets to matching candles", () => {
  const openedAtMs = Date.parse("2024-01-01T00:00:00Z");
  const candles = [{ openedAtMs }, { openedAtMs: openedAtMs + 300_000 }];
  const coverage = attachBinanceDepth(candles, [
    { openedAtMs, complete: true, imbalance1: 0.2, imbalance2: 0.1, imbalance5: 0.05, totalNotional1: 100 },
    { openedAtMs: openedAtMs + 300_000, complete: false, imbalance1: -0.2 },
  ]);

  assert.equal(coverage.matched, 1);
  assert.equal(candles[0].binanceDepthImbalance1, 0.2);
  assert.equal("binanceDepthImbalance1" in candles[1], false);
});

test("records an explicitly allowed missing archive day", async () => {
  const progress = [];
  const results = await downloadBinanceCmBookDepthArchives({
    startAt: "2023-09-25T00:00:00Z",
    endAt: "2023-09-26T00:00:00Z",
    outDir: "build/test-binance-depth-missing",
    allowMissing: true,
    fetchImpl: async () => ({ ok: false, status: 404 }),
    onProgress: (event) => progress.push(event),
  });

  assert.equal(results[0].missing, true);
  assert.deepEqual(progress, [{ day: "2023-09-25", reused: false, missing: true }]);
});
