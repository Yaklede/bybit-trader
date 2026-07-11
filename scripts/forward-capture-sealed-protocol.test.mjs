import assert from "node:assert/strict";
import test from "node:test";
import { DatabaseSync } from "node:sqlite";
import { verifyProtocol } from "./volume-flow-sealed-evaluate.mjs";
import {
  buildProtocol,
  expectedCoverage,
  generateWindows,
  parseArgs,
  verifyArchiveProvenance,
  verifyCoverageStats,
  verifyTardisMachineProvenance,
} from "./forward-capture-sealed-protocol.mjs";

const options = {
  db: "/tmp/forward.sqlite",
  symbol: "BTCUSDT",
  start: "2020-01-01T00:00:00.000Z",
  end: "2025-01-01T00:00:00.000Z",
  seed: 20260711,
  windowCount: 40,
  minMonths: 1,
  maxMonths: 60,
  source: "forward-order-book-and-taker-capture",
  out: null,
};

test("forward protocol requires an explicit sixty-month month-aligned observation window", () => {
  assert.throws(() => parseArgs(["--db=/tmp/forward.sqlite", "--seed=1"]), /month boundaries/);
  assert.throws(
    () =>
      parseArgs([
        "--db=/tmp/forward.sqlite",
        "--seed=1",
        "--start=2020-01-01T00:00:00.000Z",
        "--end=2024-12-01T00:00:00.000Z",
      ]),
    /at least max-months/,
  );
  assert.deepEqual(
    parseArgs([
      "--db=/tmp/forward.sqlite",
      "--seed=20260711",
      "--start=2020-01-01T00:00:00.000Z",
      "--end=2025-01-01T00:00:00.000Z",
    ]),
    options,
  );
});

test("forward protocol records archive provenance only when explicitly declared", () => {
  const archive = parseArgs([
    "--db=/tmp/forward.sqlite",
    "--seed=20260711",
    "--start=2023-02-01T00:00:00.000Z",
    "--end=2026-07-01T00:00:00.000Z",
    "--max-months=41",
    "--source=bybit-official-orderbook-archive-and-taker-history",
  ]);
  assert.equal(archive.source, "bybit-official-orderbook-archive-and-taker-history");
  assert.equal(buildProtocol(archive, completeCoverage(archive)).sourceData.source, archive.source);
  assert.throws(
    () =>
      parseArgs([
        "--db=/tmp/forward.sqlite",
        "--seed=20260711",
        "--start=2020-01-01T00:00:00.000Z",
        "--end=2025-01-01T00:00:00.000Z",
        "--source=unverified-history",
      ]),
    /source must be one of/,
  );
});

test("forward protocol windows are deterministic, unique, and compatible with sealed evaluation", () => {
  const first = generateWindows(options);
  const second = generateWindows(options);
  assert.deepEqual(first, second);
  assert.equal(first.length, 40);
  assert.equal(new Set(first.map((window) => `${window.replayStartAt}:${window.replayEndAt}`)).size, 40);
  assert.equal(first.every((window) => window.durationMonths >= 1 && window.durationMonths <= 60), true);

  const protocol = buildProtocol(options, completeCoverage(options));
  assert.equal(verifyProtocol(protocol).actualHash, protocol.windowsSha256);
  assert.equal(protocol.generation.tuningAllowed, false);
});

test("forward protocol rejects missing capture or candle coverage", () => {
  const valid = completeCoverage(options);
  assert.doesNotThrow(() => verifyCoverageStats(options, valid));

  const missingOrderBook = structuredClone(valid);
  missingOrderBook.orderBook.gapCount = 1;
  assert.throws(() => verifyCoverageStats(options, missingOrderBook), /orderBookImbalanceBars must be continuous/);

  const missingM15 = structuredClone(valid);
  missingM15.candles.M15.count -= 1;
  assert.throws(() => verifyCoverageStats(options, missingM15), /marketCandles.M15 must be continuous/);

  const missingLiquidationTable = structuredClone(valid);
  missingLiquidationTable.liquidationTablePresent = false;
  assert.throws(() => verifyCoverageStats(options, missingLiquidationTable), /liquidationFlowBars table is required/);
});

test("archive source requires a complete manifest with a source hash for every day", () => {
  const db = new DatabaseSync(":memory:");
  db.exec(`
    CREATE TABLE historicalOrderBookImports (
      provider TEXT NOT NULL, dataset TEXT NOT NULL, symbol TEXT NOT NULL,
      source_date TEXT NOT NULL, minute_bar_count INTEGER NOT NULL, archive_sha256 TEXT NOT NULL
    );
  `);
  const archiveOptions = {
    symbol: "BTCUSDT",
    start: "2025-01-01T00:00:00.000Z",
    end: "2025-01-03T00:00:00.000Z",
  };
  insertArchiveManifest(db, "2025-01-01", "a".repeat(64));
  assert.throws(() => verifyArchiveProvenance(db, archiveOptions), /missing for 2025-01-02/);

  insertArchiveManifest(db, "2025-01-02", "b".repeat(64));
  assert.deepEqual(verifyArchiveProvenance(db, archiveOptions), {
    provider: "bybit",
    dataset: "orderbook",
    verifiedDays: 2,
    firstSourceDate: "2025-01-01",
    lastSourceDate: "2025-01-02",
  });
  db.close();
});

test("Tardis Machine source requires its own immutable daily replay manifests", () => {
  const db = new DatabaseSync(":memory:");
  db.exec(`
    CREATE TABLE historicalOrderBookImports (
      provider TEXT NOT NULL, dataset TEXT NOT NULL, symbol TEXT NOT NULL,
      source_date TEXT NOT NULL, minute_bar_count INTEGER NOT NULL, archive_sha256 TEXT NOT NULL
    );
    CREATE TABLE tardisMachineOrderBookImportDiagnostics (
      symbol TEXT NOT NULL, source_date TEXT NOT NULL,
      disconnect_count INTEGER NOT NULL, carried_forward_minute_bars INTEGER NOT NULL
    );
  `);
  const sourceOptions = {
    symbol: "BTCUSDT",
    start: "2025-01-01T00:00:00.000Z",
    end: "2025-01-03T00:00:00.000Z",
  };
  insertTardisMachineManifest(db, "2025-01-01", "a".repeat(64));
  assert.throws(() => verifyTardisMachineProvenance(db, sourceOptions), /missing for 2025-01-02/);
  insertTardisMachineManifest(db, "2025-01-02", "b".repeat(64));
  assert.deepEqual(verifyTardisMachineProvenance(db, sourceOptions), {
    provider: "tardis",
    dataset: "machine-normalized-book-snapshot-25-1m-v1",
    verifiedDays: 2,
    firstSourceDate: "2025-01-01",
    lastSourceDate: "2025-01-02",
    replayDiagnostics: {
      totalDisconnects: 3,
      totalCarriedForwardMinuteBars: 1,
      maxCarriedForwardMinuteBars: 1,
    },
  });
  db.close();
});

function completeCoverage(protocolOptions) {
  const expected = expectedCoverage(protocolOptions);
  return {
    orderBook: dataset(expected.orderBook, protocolOptions.start, protocolOptions.end, 1),
    takerFlow: dataset(expected.takerFlow, protocolOptions.start, protocolOptions.end, 1),
    candles: {
      M1: dataset(expected.candles.M1, protocolOptions.start, protocolOptions.end, 1),
      M5: dataset(expected.candles.M5, protocolOptions.start, protocolOptions.end, 5),
      M15: dataset(expected.candles.M15, protocolOptions.start, protocolOptions.end, 15),
    },
    liquidationTablePresent: true,
  };
}

function dataset(count, start, end, intervalMinutes) {
  return {
    count,
    firstOpenedAt: start,
    lastOpenedAt: new Date(Date.parse(end) - intervalMinutes * 60_000).toISOString(),
    gapCount: 0,
  };
}

function insertArchiveManifest(db, date, hash) {
  db.prepare(`
    INSERT INTO historicalOrderBookImports(provider, dataset, symbol, source_date, minute_bar_count, archive_sha256)
    VALUES ('bybit', 'orderbook', 'BTCUSDT', ?, 1440, ?)
  `).run(date, hash);
}

function insertTardisMachineManifest(db, date, hash) {
  db.prepare(`
    INSERT INTO historicalOrderBookImports(provider, dataset, symbol, source_date, minute_bar_count, archive_sha256)
    VALUES ('tardis', 'machine-normalized-book-snapshot-25-1m-v1', 'BTCUSDT', ?, 1440, ?)
  `).run(date, hash);
  db.prepare(`
    INSERT INTO tardisMachineOrderBookImportDiagnostics(symbol, source_date, disconnect_count, carried_forward_minute_bars)
    VALUES ('BTCUSDT', ?, ?, ?)
  `).run(date, date === "2025-01-01" ? 1 : 2, date === "2025-01-01" ? 0 : 1);
}
