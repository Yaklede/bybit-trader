import assert from "node:assert/strict";
import test from "node:test";
import {
  buildLegacyFeedUrl,
  fetchLegacyFeedSample,
  parseArgs,
  validateLegacyFeedSample,
} from "./tardis-bybit-legacy-feed-preflight.mjs";

const options = {
  symbol: "BTCUSDT",
  date: "2020-06-01",
  apiUrl: "https://api.tardis.dev/v1/data-feeds/bybit",
  apiKeyEnv: "TARDIS_API_KEY",
  out: null,
};

test("legacy feed preflight validates its source request", () => {
  assert.throws(() => parseArgs([]), /date must be a valid/);
  assert.throws(() => parseArgs(["--date=2020-06-01", "--api-url=http://example.test"]), /HTTPS/);
  assert.deepEqual(parseArgs(["--date=2020-06-01"]), options);

  const url = buildLegacyFeedUrl(options);
  assert.equal(url.searchParams.get("from"), "2020-06-01");
  assert.equal(url.searchParams.get("offset"), "0");
  assert.deepEqual(JSON.parse(url.searchParams.get("filters")), [
    { channel: "orderBook_200", symbols: ["BTCUSDT"] },
    { channel: "trade", symbols: ["BTCUSDT"] },
  ]);
});

test("legacy feed preflight enforces capture order and snapshot-delta reconstruction", () => {
  const report = validateLegacyFeedSample(validSample(), { symbol: "BTCUSDT", minimumDepth: 2 });

  assert.deepEqual(report, {
    capturedRecordCount: 4,
    snapshotCount: 1,
    deltaCount: 1,
    tradeCount: 1,
    firstSnapshotAt: "2020-06-01T00:00:00.2000000Z",
    lastCapturedAt: "2020-06-01T00:00:00.4000000Z",
    retainedBidLevels: 2,
    retainedAskLevels: 2,
  });
  assert.throws(
    () => validateLegacyFeedSample(deltaBeforeSnapshot(), { symbol: "BTCUSDT", minimumDepth: 1 }),
    /before an initial snapshot/,
  );
});

test("legacy feed preflight sends a bearer token without exposing it in the result", async () => {
  let receivedHeaders = null;
  const payload = await fetchLegacyFeedSample(
    options,
    async (_url, request) => {
      receivedHeaders = request.headers;
      return { ok: true, text: async () => validSample() };
    },
    { TARDIS_API_KEY: "secret-value" },
  );

  assert.equal(payload, validSample());
  assert.equal(receivedHeaders.Authorization, "Bearer secret-value");
  assert.equal(receivedHeaders["Accept-Encoding"], "gzip");
});

function validSample() {
  return [
    '2020-06-01T00:00:00.1000000Z {"topic":"trade.BTCUSDT","data":[{"symbol":"BTCUSDT","side":"Buy","price":"9500","size":1}]}',
    '2020-06-01T00:00:00.2000000Z {"topic":"orderBook_200.100ms.BTCUSDT","type":"snapshot","data":{"order_book":[{"symbol":"BTCUSDT","side":"Buy","price":"9499","size":2},{"symbol":"BTCUSDT","side":"Buy","price":"9498","size":1},{"symbol":"BTCUSDT","side":"Sell","price":"9501","size":2},{"symbol":"BTCUSDT","side":"Sell","price":"9502","size":1}]}}',
    '2020-06-01T00:00:00.3000000Z {"topic":"orderBook_200.100ms.BTCUSDT","type":"delta","data":{"delete":[{"symbol":"BTCUSDT","side":"Buy","price":"9498"}],"update":[{"symbol":"BTCUSDT","side":"Buy","price":"9499","size":3}],"insert":[{"symbol":"BTCUSDT","side":"Buy","price":"9497","size":1}]}}',
    '2020-06-01T00:00:00.4000000Z {"topic":"ignored","data":[]}',
  ].join("\n");
}

function deltaBeforeSnapshot() {
  return '2020-06-01T00:00:00.1000000Z {"topic":"orderBook_200.100ms.BTCUSDT","type":"delta","data":{"delete":[],"update":[],"insert":[]}}';
}
