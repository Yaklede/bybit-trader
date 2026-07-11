import assert from "node:assert/strict";
import test from "node:test";
import { DatabaseSync } from "node:sqlite";
import { Readable } from "node:stream";
import {
  aggregateNormalizedSnapshots,
  backfill,
  buildReplayUrl,
  normalizeMachineUrl,
  parseArgs,
} from "./tardis-machine-orderbook-backfill.mjs";

test("Tardis Machine importer accepts only a local research endpoint", () => {
  const options = parseArgs([
    "--db=/tmp/tardis.sqlite",
    "--start=2020-06-01",
    "--end=2020-06-01",
    "--machine-url=http://localhost:18000",
  ]);
  assert.equal(options.machineUrl, "http://localhost:18000");
  assert.equal(options.orderBookDepth, 50);
  assert.throws(() => normalizeMachineUrl("https://machine.example.com"), /localhost/);
  assert.throws(() => parseArgs(["--start=2020-06-01", "--end=2020-06-01", "--orderbook-depth=51"]), /between 1 and 50/);
});

test("Tardis Machine replay request pins source, range, depth, and disconnect reporting", () => {
  const options = parseArgs([
    "--db=/tmp/tardis.sqlite",
    "--start=2020-06-01",
    "--end=2020-06-01",
    "--machine-url=http://127.0.0.1:18000",
    "--orderbook-depth=25",
  ]);
  const request = buildReplayUrl(options, "2020-06-01");
  const replay = JSON.parse(request.searchParams.get("options"));
  assert.equal(request.origin, "http://127.0.0.1:18000");
  assert.equal(request.pathname, "/replay-normalized");
  assert.deepEqual(replay, {
    exchange: "bybit",
    from: "2020-06-01",
    to: "2020-06-02",
    symbols: ["BTCUSDT"],
    withDisconnectMessages: true,
    dataTypes: ["book_snapshot_25_1m"],
  });
});

test("normalized snapshots create causal minute bars and make carry-forward explicit", async () => {
  const result = await aggregateNormalizedSnapshots(
    Readable.from([
      `${JSON.stringify(snapshot("2020-06-01T00:00:05.000Z", 100, 101))}\n`,
      `${JSON.stringify(snapshot("2020-06-01T00:02:05.000Z", 102, 103))}\n`,
    ]),
    { date: "2020-06-01", symbol: "BTCUSDT", depth: 2, expectedMinutes: 3 },
  );
  assert.equal(result.eventCount, 2);
  assert.equal(result.carriedForwardMinuteBars, 1);
  assert.deepEqual(result.bars.map((bar) => bar.openedAt), [
    Date.parse("2020-06-01T00:00:00Z"),
    Date.parse("2020-06-01T00:01:00Z"),
    Date.parse("2020-06-01T00:02:00Z"),
  ]);
  assert.equal(result.bars[0].meanBidNotional, result.bars[1].meanBidNotional);
  assert.notEqual(result.bars[1].meanBidNotional, result.bars[2].meanBidNotional);
});

test("normalized day rejects source disconnects and incomplete causal boundaries", async () => {
  await assert.rejects(
    () => aggregateNormalizedSnapshots(Readable.from([`${JSON.stringify({ type: "disconnect" })}\n`]), {
      date: "2020-06-01",
      symbol: "BTCUSDT",
      depth: 2,
      expectedMinutes: 3,
    }),
    /source disconnect/,
  );
  await assert.rejects(
    () => aggregateNormalizedSnapshots(Readable.from([`${JSON.stringify(snapshot("2020-06-01T00:01:05.000Z", 100, 101))}\n`]), {
      date: "2020-06-01",
      symbol: "BTCUSDT",
      depth: 2,
      expectedMinutes: 3,
    }),
    /initial-minute/,
  );
});

test("backfill persists a complete immutable daily provenance record", async () => {
  const db = new DatabaseSync(":memory:");
  const options = parseArgs([
    "--db=/tmp/tardis.sqlite",
    "--start=2020-06-01",
    "--end=2020-06-01",
    "--orderbook-depth=2",
  ]);
  const payload = [
    JSON.stringify(snapshot("2020-06-01T00:00:05.000Z", 100, 101)),
    JSON.stringify(snapshot("2020-06-01T23:59:05.000Z", 101, 102)),
  ].join("\n");
  const result = await backfill(options, {
    db,
    log: () => {},
    fetchImpl: async () => new Response(payload),
  });
  assert.deepEqual(result, { importedDays: 1, skippedDays: 0, requestedDays: 1 });
  assert.equal(db.prepare("SELECT count(*) AS count FROM orderBookImbalanceBars").get().count, 1_440);
  const manifest = db.prepare("SELECT provider, dataset, minute_bar_count, archive_sha256 FROM historicalOrderBookImports").get();
  assert.equal(manifest.provider, "tardis");
  assert.equal(manifest.dataset, "machine-normalized-book-snapshot-1m-v1");
  assert.equal(manifest.minute_bar_count, 1_440);
  assert.match(manifest.archive_sha256, /^[a-f0-9]{64}$/);
  db.close();
});

test("backfill repairs a corrupted persisted day instead of silently skipping it", async () => {
  const db = new DatabaseSync(":memory:");
  const options = parseArgs([
    "--db=/tmp/tardis.sqlite",
    "--start=2020-06-01",
    "--end=2020-06-01",
    "--orderbook-depth=2",
  ]);
  const payload = [
    JSON.stringify(snapshot("2020-06-01T00:00:05.000Z", 100, 101)),
    JSON.stringify(snapshot("2020-06-01T23:59:05.000Z", 101, 102)),
  ].join("\n");
  const dependencies = { db, log: () => {}, fetchImpl: async () => new Response(payload) };
  await backfill(options, dependencies);
  db.prepare("DELETE FROM orderBookImbalanceBars WHERE opened_at='2020-06-01T12:00:00Z'").run();
  const repaired = await backfill(options, dependencies);
  assert.deepEqual(repaired, { importedDays: 1, skippedDays: 0, requestedDays: 1 });
  assert.equal(db.prepare("SELECT count(*) AS count FROM orderBookImbalanceBars").get().count, 1_440);
  db.close();
});

test("backfill refuses to overwrite an existing source day from a different provider", async () => {
  const db = new DatabaseSync(":memory:");
  db.exec(`
    CREATE TABLE historicalOrderBookImports (
      provider TEXT, dataset TEXT, symbol TEXT, source_date TEXT
    );
    CREATE TABLE orderBookImbalanceBars (
      symbol TEXT, opened_at TEXT
    );
  `);
  db.prepare("INSERT INTO historicalOrderBookImports VALUES ('bybit', 'orderbook', 'BTCUSDT', '2020-06-01')").run();
  const options = parseArgs(["--db=/tmp/tardis.sqlite", "--start=2020-06-01", "--end=2020-06-01"]);
  await assert.rejects(() => backfill(options, { db, fetchImpl: async () => new Response("") }), /Refusing to overwrite bybit\/orderbook/);
  db.close();
});

function snapshot(localTimestamp, bestBid, bestAsk) {
  return {
    type: "book_snapshot",
    exchange: "bybit",
    symbol: "BTCUSDT",
    name: "book_snapshot_2_1m",
    depth: 2,
    interval: 60_000,
    timestamp: localTimestamp,
    localTimestamp,
    bids: [
      { price: bestBid, amount: 2 },
      { price: bestBid - 1, amount: 1 },
    ],
    asks: [
      { price: bestAsk, amount: 2 },
      { price: bestAsk + 1, amount: 1 },
    ],
  };
}
