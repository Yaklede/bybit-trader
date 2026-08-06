import assert from "node:assert/strict";
import { DatabaseSync } from "node:sqlite";
import test from "node:test";

import { auditEventFlowCoverage, hasContinuousRows, parseArgs } from "./bybit-event-flow-coverage-audit.mjs";

test("combined event-flow audit requires an explicit valid range", () => {
  assert.throws(() => parseArgs(["--start=2026-01-01", "--end=2026-01-01"]), /db is required/);
  assert.throws(() => parseArgs(["--db=/tmp/research.sqlite", "--start=2026-01-02", "--end=2026-01-01"]), /start\/end/);
});

test("continuous-row validator rejects a duplicate or missing minute", () => {
  const startAt = "2026-01-01T00:00:00Z";
  const rows = Array.from({ length: 3 }, (_, index) => ({ opened_at: instant(Date.parse(startAt) + index * 60_000) }));
  assert.equal(hasContinuousRows(rows, startAt, 3, 60_000), true);
  assert.equal(hasContinuousRows([rows[0], rows[0], rows[2]], startAt, 3, 60_000), false);
});

test("combined event-flow audit fails closed when a required table is absent", () => {
  const db = new DatabaseSync(":memory:");
  db.exec("CREATE TABLE marketCandles(symbol TEXT, timeframe TEXT, opened_at TEXT)");
  const report = auditEventFlowCoverage(
    { symbol: "BTCUSDT", start: "2026-01-01", end: "2026-01-01" },
    { db },
  );
  assert.equal(report.status, "REJECTED_MISSING_TABLES");
  assert.ok(report.missingTables.includes("orderBookEventFlowBars"));
  assert.equal(report.sourceFingerprintSha256, null);
  db.close();
});

test("combined event-flow audit fingerprints only complete causal source days", () => {
  const db = new DatabaseSync(":memory:");
  createSchema(db);
  insertCompleteDay(db, "2026-01-01");
  const report = auditEventFlowCoverage(
    { symbol: "BTCUSDT", start: "2026-01-01", end: "2026-01-01" },
    { db },
  );
  assert.equal(report.status, "COMPLETE");
  assert.equal(report.completeDays, 1);
  assert.match(report.sourceFingerprintSha256, /^[a-f0-9]{64}$/);

  db.prepare("DELETE FROM takerEventFlowBars WHERE opened_at='2026-01-01T12:00:00Z'").run();
  const invalid = auditEventFlowCoverage(
    { symbol: "BTCUSDT", start: "2026-01-01", end: "2026-01-01" },
    { db },
  );
  assert.equal(invalid.status, "REJECTED_INCOMPLETE_COVERAGE");
  assert.ok(invalid.days[0].reasons.includes("takerEventFlowBars_INCOMPLETE"));
  assert.equal(invalid.sourceFingerprintSha256, null);
  db.close();
});

function createSchema(db) {
  db.exec(`
    CREATE TABLE marketCandles(symbol TEXT, timeframe TEXT, opened_at TEXT);
    CREATE TABLE orderBookImbalanceBars(symbol TEXT, opened_at TEXT);
    CREATE TABLE orderBookEventFlowBars(symbol TEXT, opened_at TEXT);
    CREATE TABLE takerFlowBars(symbol TEXT, opened_at TEXT);
    CREATE TABLE takerEventFlowBars(symbol TEXT, opened_at TEXT);
    CREATE TABLE historicalOrderBookImports(
      provider TEXT, dataset TEXT, symbol TEXT, source_date TEXT,
      archive_sha256 TEXT, minute_bar_count INTEGER, importer_version TEXT
    );
    CREATE TABLE historicalTradeImports(
      provider TEXT, dataset TEXT, symbol TEXT, source_date TEXT,
      archive_sha256 TEXT, minute_bar_count INTEGER, importer_version TEXT
    );
  `);
}

function insertCompleteDay(db, date) {
  db.prepare(`
    INSERT INTO historicalOrderBookImports VALUES (
      'bybit','orderbook','BTCUSDT',?,'${"a".repeat(64)}',1440,'bybit-orderbook-archive-v2-event-flow'
    )
  `).run(date);
  db.prepare(`
    INSERT INTO historicalTradeImports VALUES (
      'bybit','public-trades','BTCUSDT',?,'${"b".repeat(64)}',1440,'bybit-public-trades-v2-event-flow'
    )
  `).run(date);
  const minuteTables = ["orderBookImbalanceBars", "orderBookEventFlowBars", "takerFlowBars", "takerEventFlowBars"];
  const minuteInserts = minuteTables.map((table) => db.prepare(`INSERT INTO ${table} VALUES ('BTCUSDT', ?)`));
  const candleInsert = db.prepare("INSERT INTO marketCandles VALUES ('BTCUSDT', ?, ?)");
  const start = Date.parse(`${date}T00:00:00Z`);
  for (let minute = 0; minute < 1_440; minute += 1) {
    const openedAt = instant(start + minute * 60_000);
    minuteInserts.forEach((insert) => insert.run(openedAt));
    candleInsert.run("M1", openedAt);
    if (minute % 5 === 0) candleInsert.run("M5", openedAt);
    if (minute % 15 === 0) candleInsert.run("M15", openedAt);
  }
}

function instant(milliseconds) {
  return new Date(milliseconds).toISOString().replace(".000Z", "Z");
}
