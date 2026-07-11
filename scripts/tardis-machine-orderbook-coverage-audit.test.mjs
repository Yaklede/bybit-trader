import assert from "node:assert/strict";
import test from "node:test";
import { DatabaseSync } from "node:sqlite";
import { resolve } from "node:path";
import { auditTardisMachineCoverage, parseArgs } from "./tardis-machine-orderbook-coverage-audit.mjs";

test("Tardis Machine coverage audit requires an explicit database and inclusive date range", () => {
  assert.throws(() => parseArgs(["--start=2025-01-01", "--end=2025-01-02"]), /db is required/);
  assert.throws(() => parseArgs(["--db=fixture.sqlite", "--start=2025-01-02"]), /start\/end/);
  assert.deepEqual(
    parseArgs(["--db=fixture.sqlite", "--symbol=btcusdt", "--start=2025-01-01", "--end=2025-01-02"]),
    { db: resolve("fixture.sqlite"), symbol: "BTCUSDT", start: "2025-01-01", end: "2025-01-02" },
  );
});

test("Tardis Machine coverage audit requires complete provenance, diagnostics, and minute bars", () => {
  const db = new DatabaseSync(":memory:");
  createSchema(db);
  insertCompleteDay(db, "2025-01-01", { disconnectCount: 2, carriedForwardMinuteBars: 0 });
  insertManifest(db, "2025-01-02");
  insertCompleteDay(db, "2025-01-03", { disconnectCount: 1, carriedForwardMinuteBars: 3 });
  db.prepare("DELETE FROM orderBookImbalanceBars WHERE opened_at='2025-01-03T12:00:00Z'").run();

  const report = auditTardisMachineCoverage({ symbol: "BTCUSDT", start: "2025-01-01", end: "2025-01-03" }, { db });

  assert.equal(report.manifestDays, 3);
  assert.equal(report.diagnosticsDays, 2);
  assert.equal(report.completeDays, 1);
  assert.equal(report.invalidDays, 2);
  assert.deepEqual(report.statusCounts, { complete: 1, "missing-diagnostics": 1, "stored-bars-invalid": 1 });
  assert.deepEqual(report.completeRanges, [{ start: "2025-01-01", end: "2025-01-01", days: 1 }]);
  assert.deepEqual(report.gapRanges, [
    { start: "2025-01-02", end: "2025-01-03", days: 2, reasons: ["missing-diagnostics", "stored-bars-invalid"] },
  ]);
  assert.deepEqual(report.replayDiagnostics, {
    totalDisconnects: 2,
    totalCarriedForwardMinuteBars: 0,
    maxCarriedForwardMinuteBars: 0,
  });
  db.close();
});

test("Tardis Machine coverage audit reports missing schema as a non-qualifying gap", () => {
  const db = new DatabaseSync(":memory:");
  const report = auditTardisMachineCoverage({ symbol: "BTCUSDT", start: "2025-01-01", end: "2025-01-01" }, { db });

  assert.deepEqual(report.tableState, { imports: false, orderBookBars: false, diagnostics: false });
  assert.equal(report.completeDays, 0);
  assert.deepEqual(report.gapRanges, [
    { start: "2025-01-01", end: "2025-01-01", days: 1, reasons: ["missing-import-table"] },
  ]);
  db.close();
});

function createSchema(db) {
  db.exec(`
    CREATE TABLE historicalOrderBookImports (
      provider TEXT NOT NULL, dataset TEXT NOT NULL, symbol TEXT NOT NULL,
      source_date TEXT NOT NULL, minute_bar_count INTEGER NOT NULL, archive_sha256 TEXT NOT NULL
    );
    CREATE TABLE tardisMachineOrderBookImportDiagnostics (
      symbol TEXT NOT NULL, source_date TEXT NOT NULL,
      disconnect_count INTEGER NOT NULL, carried_forward_minute_bars INTEGER NOT NULL
    );
    CREATE TABLE orderBookImbalanceBars (symbol TEXT NOT NULL, opened_at TEXT NOT NULL);
  `);
}

function insertCompleteDay(db, date, diagnostic) {
  insertManifest(db, date);
  db.prepare(`
    INSERT INTO tardisMachineOrderBookImportDiagnostics(symbol, source_date, disconnect_count, carried_forward_minute_bars)
    VALUES ('BTCUSDT', ?, ?, ?)
  `).run(date, diagnostic.disconnectCount, diagnostic.carriedForwardMinuteBars);
  const insert = db.prepare("INSERT INTO orderBookImbalanceBars(symbol, opened_at) VALUES (?, ?)");
  const start = Date.parse(`${date}T00:00:00Z`);
  for (let offset = 0; offset < 1_440; offset += 1) {
    insert.run("BTCUSDT", new Date(start + offset * 60_000).toISOString().replace(".000Z", "Z"));
  }
}

function insertManifest(db, date, archiveSha256 = "a".repeat(64)) {
  db.prepare(`
    INSERT INTO historicalOrderBookImports(provider, dataset, symbol, source_date, minute_bar_count, archive_sha256)
    VALUES ('tardis', 'machine-normalized-book-snapshot-25-1m-v1', 'BTCUSDT', ?, 1440, ?)
  `).run(date, archiveSha256);
}
