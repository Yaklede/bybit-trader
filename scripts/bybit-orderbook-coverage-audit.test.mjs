import test from "node:test";
import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import { resolve } from "node:path";
import { auditArchiveCoverage, parseArgs } from "./bybit-orderbook-coverage-audit.mjs";

test("coverage audit requires an explicit database and inclusive date range", () => {
  assert.throws(() => parseArgs(["--start=2025-01-01", "--end=2025-01-02"]), /db is required/);
  assert.throws(() => parseArgs(["--db=fixture.sqlite", "--start=2025-01-02"]), /start\/end/);
  assert.deepEqual(
    parseArgs(["--db=fixture.sqlite", "--symbol=btcusdt", "--start=2025-01-01", "--end=2025-01-02"]),
    { db: resolve("fixture.sqlite"), symbol: "BTCUSDT", start: "2025-01-01", end: "2025-01-02" },
  );
});

test("coverage audit reports only stored, continuous complete days as valid ranges", () => {
  const db = new DatabaseSync(":memory:");
  createSchema(db);
  insertCompleteDay(db, "2025-01-01");
  insertManifest(db, "2025-01-03", 1_440);
  insertIncompleteDay(db, "2025-01-03");

  const report = auditArchiveCoverage({ symbol: "BTCUSDT", start: "2025-01-01", end: "2025-01-03" }, { db });

  assert.equal(report.manifestDays, 2);
  assert.equal(report.completeDays, 1);
  assert.equal(report.invalidDays, 2);
  assert.deepEqual(report.completeRanges, [{ start: "2025-01-01", end: "2025-01-01", days: 1 }]);
  assert.deepEqual(report.gapRanges, [
    { start: "2025-01-02", end: "2025-01-03", days: 2, reasons: ["missing-manifest", "stored-bars-invalid"] },
  ]);
  db.close();
});

test("coverage audit rejects an invalid provenance manifest before reading bars", () => {
  const db = new DatabaseSync(":memory:");
  createSchema(db);
  insertManifest(db, "2025-01-01", 1_440, "not-a-sha256");

  const report = auditArchiveCoverage({ symbol: "BTCUSDT", start: "2025-01-01", end: "2025-01-01" }, { db });

  assert.equal(report.completeDays, 0);
  assert.deepEqual(report.gapRanges, [
    { start: "2025-01-01", end: "2025-01-01", days: 1, reasons: ["invalid-manifest"] },
  ]);
  db.close();
});

function createSchema(db) {
  db.exec(`
    CREATE TABLE historicalOrderBookImports (
      provider TEXT NOT NULL, dataset TEXT NOT NULL, symbol TEXT NOT NULL,
      source_date TEXT NOT NULL, minute_bar_count INTEGER NOT NULL, archive_sha256 TEXT NOT NULL
    );
    CREATE TABLE orderBookImbalanceBars (symbol TEXT NOT NULL, opened_at TEXT NOT NULL);
  `);
}

function insertCompleteDay(db, date) {
  insertManifest(db, date, 1_440);
  const insert = db.prepare("INSERT INTO orderBookImbalanceBars(symbol, opened_at) VALUES (?, ?)");
  const start = Date.parse(`${date}T00:00:00Z`);
  for (let offset = 0; offset < 1_440; offset += 1) {
    insert.run("BTCUSDT", new Date(start + offset * 60_000).toISOString().replace(".000Z", "Z"));
  }
}

function insertIncompleteDay(db, date) {
  db.prepare("INSERT INTO orderBookImbalanceBars(symbol, opened_at) VALUES (?, ?)")
    .run("BTCUSDT", `${date}T00:00:00Z`);
}

function insertManifest(db, date, minuteBarCount, archiveSha256 = "a".repeat(64)) {
  db.prepare(`
    INSERT INTO historicalOrderBookImports(provider, dataset, symbol, source_date, minute_bar_count, archive_sha256)
    VALUES ('bybit', 'orderbook', 'BTCUSDT', ?, ?, ?)
  `).run(date, minuteBarCount, archiveSha256);
}
