import test from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { Readable } from "node:stream";
import {
  aggregateArchiveLines,
  assertCompleteDay,
  ensureSchema,
  listArchiveFiles,
  parseArgs,
  retryArchiveOperation,
  openArchiveStream,
  verifyExistingArchiveHash,
} from "./bybit-orderbook-backfill.mjs";

test("parseArgs fixes archive depth to the live top-50 feature contract", () => {
  const options = parseArgs(["--db=/tmp/orderbook.sqlite", "--start=2024-01-01", "--end=2024-01-02", "--orderbook-depth=50"]);
  assert.equal(options.symbol, "BTCUSDT");
  assert.equal(options.orderBookDepth, 50);
  assert.equal(options.catalogDaysPerRequest, 6);
  assert.throws(() => parseArgs(["--orderbook-depth=25"]), /archive\/live feature parity/);
  assert.throws(() => parseArgs(["--orderbook-depth=501"]));
  assert.throws(() => parseArgs(["--catalog-days-per-request=7"]));
  assert.throws(() => parseArgs(["--archive-attempts=6"]));
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

test("catalog lookup retries a transient network termination", async () => {
  const options = parseArgs([
    "--start=2024-01-01",
    "--end=2024-01-01",
    "--archive-attempts=3",
    "--archive-retry-delay-millis=0",
  ]);
  let attempts = 0;
  const files = await listArchiveFiles(options, async () => {
    attempts += 1;
    if (attempts < 3) throw new TypeError("terminated");
    return new Response(JSON.stringify({
      ret_code: 0,
      result: {
        list: [{
          bizType: "contract",
          productId: "orderbook",
          interval: "daily",
          symbol: "BTCUSDT",
          date: "2024-01-01",
          filename: "2024-01-01_BTCUSDT_ob500.data.zip",
          size: "1",
          url: "https://quote-saver.bycsi.com/orderbook/linear/BTCUSDT/2024-01-01.zip",
        }],
      },
    }));
  });
  assert.equal(attempts, 3);
  assert.equal(files.length, 1);
});

test("archive aggregation preserves event-weighted depth and top-five mutations", async () => {
  const lines = [
    message("2024-01-01T00:00:05.000Z", "snapshot", [["100", "2"], ["99", "1"], ["98", "3"], ["97", "1"], ["96", "1"], ["95", "1"]], [["101", "2"], ["102", "1"], ["103", "3"], ["104", "1"], ["105", "1"], ["106", "1"]]),
    message("2024-01-01T00:00:30.000Z", "delta", [["100", "0"], ["99", "2"]], [["101", "1"]]),
    message("2024-01-01T00:01:05.000Z", "snapshot", [["100", "1"], ["99", "1"], ["98", "1"], ["97", "1"], ["96", "1"]], [["101", "1"], ["102", "1"], ["103", "1"], ["104", "1"], ["105", "1"]]),
  ].map(JSON.stringify);

  const result = await aggregateArchiveLines(Readable.from(lines.map((line) => `${line}\n`)), {
    sourceDate: "2024-01-01",
    symbol: "BTCUSDT",
    depth: 5,
  });

  assert.equal(result.eventCount, 3);
  assert.equal(result.bars.length, 2);
  assert.deepEqual(result.bars.map((bar) => bar.openedAt), [Date.parse("2024-01-01T00:00:00Z"), Date.parse("2024-01-01T00:01:00Z")]);
  assert.equal(result.bars[0].sampleCount, 2);
  assert.equal(result.bars[0].meanBidNotional, 783);
  assert.equal(result.bars[0].meanAskNotional, 771.5);
  assert.ok(Math.abs(result.bars[0].meanSpreadBps - 149.75124378109453) < 1e-9);
  assert.equal(result.eventFlowBars.length, 2);
  assert.equal(result.eventFlowBars[0].messageCount, 2);
  assert.equal(result.eventFlowBars[0].snapshotCount, 1);
  assert.equal(result.eventFlowBars[0].bidAddedTop5Notional, 99);
  assert.equal(result.eventFlowBars[0].bidRemovedTop5Notional, 200);
  assert.equal(result.eventFlowBars[0].askRemovedTop5Notional, 101);
  assert.equal(result.eventFlowBars[0].bidUpdateCount, 2);
  assert.equal(result.eventFlowBars[0].askUpdateCount, 1);
});

test("archive aggregation rejects delta messages before an initial snapshot", async () => {
  const line = JSON.stringify(message("2024-01-01T00:00:00.000Z", "delta", [["100", "2"]], [["101", "2"]]));
  await assert.rejects(
    () => aggregateArchiveLines(Readable.from([`${line}\n`]), { sourceDate: "2024-01-01", symbol: "BTCUSDT", depth: 5 }),
    /delta arrived before its initial snapshot/,
  );
});

test("archive aggregation uses matching-engine cts for the same minute boundary as live capture", async () => {
  const snapshot = message(
    "2024-01-01T00:01:00.010Z",
    "snapshot",
    [["100", "1"], ["99", "1"], ["98", "1"], ["97", "1"], ["96", "1"]],
    [["101", "1"], ["102", "1"], ["103", "1"], ["104", "1"], ["105", "1"]],
  );
  snapshot.cts = Date.parse("2024-01-01T00:00:59.990Z");
  const result = await aggregateArchiveLines(Readable.from([`${JSON.stringify(snapshot)}\n`]), {
    sourceDate: "2024-01-01",
    symbol: "BTCUSDT",
    depth: 5,
  });
  assert.equal(result.bars[0].openedAt, Date.parse("2024-01-01T00:00:00Z"));
  assert.equal(result.firstEventAt, snapshot.cts);
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
  assert.deepEqual(names, ["historicalOrderBookImports", "liquidationFlowBars", "orderBookEventFlowBars", "orderBookImbalanceBars", "sqlite_sequence"]);
  db.close();
});

test("an existing source day rejects a changed archive hash", () => {
  assert.doesNotThrow(() => verifyExistingArchiveHash("same", "same", "2024-01-01"));
  assert.throws(
    () => verifyExistingArchiveHash("recorded", "changed", "2024-01-01"),
    /refusing to replace the recorded provenance/,
  );
});

test("archive operation retries a transient decompression failure without changing its inputs", async () => {
  const attempts = [];
  const result = await retryArchiveOperation(
    async (attempt) => {
      attempts.push(attempt);
      if (attempt < 3) throw new Error("invalid compressed data");
      return "complete";
    },
    3,
    1,
    async () => {},
  );
  assert.equal(result, "complete");
  assert.deepEqual(attempts, [1, 2, 3]);
});

test("archive directory takes precedence over a network request for a verified filename", async () => {
  const directory = await mkdtemp(join(tmpdir(), "bybit-orderbook-cache-"));
  try {
    await writeFile(join(directory, "sample.zip"), "cached archive");
    const { stream, localArchive } = await openArchiveStream(
      { filename: "sample.zip", url: "https://example.test/sample.zip", date: "2024-01-01" },
      { archiveDirectory: directory },
      async () => {
        throw new Error("network must not be called");
      },
    );
    const chunks = [];
    for await (const chunk of stream) chunks.push(chunk);
    assert.equal(Buffer.concat(chunks).toString(), "cached archive");
    assert.equal(localArchive, join(directory, "sample.zip"));
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
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
