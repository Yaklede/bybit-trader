import test from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { Readable } from "node:stream";
import {
  aggregateArchiveLines,
  assertCompleteDay,
  ensureSchema,
  listArchiveFiles,
  parseArgs,
  verifyExistingArchiveHash,
} from "./bybit-orderbook-backfill.mjs";

test("parseArgs defaults to the first complete official archive day and validates archive controls", () => {
  const options = parseArgs(["--db=/tmp/orderbook.sqlite", "--start=2024-01-01", "--end=2024-01-02", "--orderbook-depth=25"]);
  assert.equal(options.symbol, "BTCUSDT");
  assert.equal(options.orderBookDepth, 25);
  assert.equal(options.catalogDaysPerRequest, 6);
  assert.throws(() => parseArgs(["--orderbook-depth=501"]));
  assert.throws(() => parseArgs(["--catalog-days-per-request=7"]));
});

test("catalog requests are bounded to six days and validate official order-book files", async () => {
  const requests = [];
  const options = parseArgs(["--start=2024-01-01", "--end=2024-01-07"]);
  const files = await listArchiveFiles(options, async (url) => {
    requests.push(url);
    const start = url.searchParams.get("startDay");
    const end = url.searchParams.get("endDay");
    const list = [];
    for (let day = start; day <= end; day = addUtcDays(day, 1)) {
      list.push({
        bizType: "contract",
        productId: "orderbook",
        interval: "daily",
        symbol: "BTCUSDT",
        date: day,
        filename: `${day}_BTCUSDT_ob500.data.zip`,
        size: "1",
        url: `https://quote-saver.bycsi.com/orderbook/linear/BTCUSDT/${day}.zip`,
      });
    }
    return new Response(JSON.stringify({ ret_code: 0, result: { list } }));
  });

  assert.equal(requests.length, 2);
  assert.equal(requests[0].searchParams.get("startDay"), "2024-01-01");
  assert.equal(requests[0].searchParams.get("endDay"), "2024-01-06");
  assert.equal(requests[1].searchParams.get("startDay"), "2024-01-07");
  assert.equal(files.length, 7);
});

test("archive aggregation applies snapshot and delta state before closing each minute", async () => {
  const lines = [
    message("2024-01-01T00:00:05.000Z", "snapshot", [["100", "2"], ["99", "1"], ["98", "3"]], [["101", "2"], ["102", "1"], ["103", "3"]]),
    message("2024-01-01T00:00:30.000Z", "delta", [["100", "0"], ["99", "2"]], [["101", "1"]]),
    message("2024-01-01T00:01:05.000Z", "snapshot", [["100", "1"], ["99", "1"]], [["101", "1"], ["102", "1"]]),
  ].map(JSON.stringify);

  const result = await aggregateArchiveLines(Readable.from(lines.map((line) => `${line}\n`)), {
    sourceDate: "2024-01-01",
    symbol: "BTCUSDT",
    depth: 2,
  });

  assert.equal(result.eventCount, 3);
  assert.equal(result.bars.length, 2);
  assert.deepEqual(result.bars.map((bar) => bar.openedAt), [Date.parse("2024-01-01T00:00:00Z"), Date.parse("2024-01-01T00:01:00Z")]);
  assert.equal(result.bars[0].sampleCount, 1);
  assert.equal(result.bars[0].meanBidNotional, 492);
  assert.equal(result.bars[0].meanAskNotional, 203);
  assert.equal(result.bars[0].meanSpreadBps, 200);
});

test("archive aggregation rejects delta messages before an initial snapshot", async () => {
  const line = JSON.stringify(message("2024-01-01T00:00:00.000Z", "delta", [["100", "2"]], [["101", "2"]]));
  await assert.rejects(
    () => aggregateArchiveLines(Readable.from([`${line}\n`]), { sourceDate: "2024-01-01", symbol: "BTCUSDT", depth: 1 }),
    /delta arrived before its initial snapshot/,
  );
});

test("complete-day validation rejects gaps and accepts exactly contiguous minute bars", () => {
  const date = "2024-01-01";
  const dayStart = Date.parse(`${date}T00:00:00Z`);
  const complete = Array.from({ length: 1_440 }, (_, offset) => ({ openedAt: dayStart + offset * 60_000 }));
  assert.doesNotThrow(() => assertCompleteDay(complete, date));
  assert.throws(() => assertCompleteDay(complete.slice(1), date), /incomplete/);
});

test("research schema creates the order-book bars and immutable import manifest", () => {
  const db = new DatabaseSync(":memory:");
  ensureSchema(db);
  ensureSchema(db);
  const names = db.prepare("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name").all().map((row) => row.name);
  assert.deepEqual(names, ["historicalOrderBookImports", "liquidationFlowBars", "orderBookImbalanceBars", "sqlite_sequence"]);
  db.close();
});

test("an existing source day rejects a changed archive hash", () => {
  assert.doesNotThrow(() => verifyExistingArchiveHash("same", "same", "2024-01-01"));
  assert.throws(
    () => verifyExistingArchiveHash("recorded", "changed", "2024-01-01"),
    /refusing to replace the recorded provenance/,
  );
});

function message(timestamp, type, bids, asks) {
  return {
    topic: "orderbook.500.BTCUSDT",
    type,
    ts: Date.parse(timestamp),
    data: { s: "BTCUSDT", b: bids, a: asks },
  };
}

function addUtcDays(date, days) {
  const value = new Date(`${date}T00:00:00Z`);
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
}
