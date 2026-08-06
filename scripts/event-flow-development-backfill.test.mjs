import assert from "node:assert/strict";
import fs from "node:fs/promises";
import { DatabaseSync } from "node:sqlite";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import {
  acquireEventFlowBlocks,
  assertDevelopmentOnly,
  bindResearchDatabase,
  copyCandleBlock,
  ensureResearchSchema,
  hasContinuousCandles,
  parseArgs,
} from "./event-flow-development-backfill.mjs";

const BLOCK = {
  id: "T01",
  sourceStartDate: "2024-01-01",
  sourceEndDate: "2024-01-03",
};

test("development backfill accepts only protocol and report paths", () => {
  const options = parseArgs(["--protocol=config/protocol.json", "--report=build/report.json"]);
  assert.match(options.protocol, /config\/protocol\.json$/);
  assert.match(options.report, /build\/report\.json$/);
  assert.throws(() => parseArgs(["--stage=validation"]), /Unsupported argument/);
  assert.throws(() => parseArgs(["config/protocol.json"]), /Use --name=value/);
});

test("candle copy requires and preserves every frozen causal candle", () => {
  const source = new DatabaseSync(":memory:");
  const target = new DatabaseSync(":memory:");
  createCandleSchema(source);
  ensureResearchSchema(target);
  seedTimeframe(source, "BTCUSDT", "M1", BLOCK.sourceStartDate, 3 * 1_440, 60_000);
  seedTimeframe(source, "BTCUSDT", "M5", BLOCK.sourceStartDate, 3 * 288, 300_000);
  seedTimeframe(source, "BTCUSDT", "M15", BLOCK.sourceStartDate, 3 * 96, 900_000);

  copyCandleBlock(source, target, "BTCUSDT", ["M1", "M5", "M15"], BLOCK);
  const counts = target
    .prepare("SELECT timeframe, COUNT(*) AS count FROM marketCandles GROUP BY timeframe ORDER BY timeframe")
    .all()
    .map((row) => ({ ...row }));
  assert.deepEqual(counts, [
    { timeframe: "M1", count: 4_320 },
    { timeframe: "M15", count: 288 },
    { timeframe: "M5", count: 864 },
  ]);
  assertDevelopmentOnly(target, [BLOCK]);

  source.prepare("DELETE FROM marketCandles WHERE timeframe='M1' AND opened_at='2024-01-02T00:00:00Z'").run();
  assert.throws(
    () => copyCandleBlock(source, target, "BTCUSDT", ["M1", "M5", "M15"], BLOCK),
    /coverage is incomplete/,
  );
  source.close();
  target.close();
});

test("development scope rejects any locked date already present in the research database", () => {
  const db = new DatabaseSync(":memory:");
  ensureResearchSchema(db);
  insertCandle(db, "BTCUSDT", "M1", "2025-01-01T00:00:00Z");
  assert.throws(() => assertDevelopmentOnly(db, [BLOCK]), /locked or undeclared/);
  db.close();
});

test("research database binding cannot be silently reused by another protocol", () => {
  const db = new DatabaseSync(":memory:");
  ensureResearchSchema(db);
  const expected = {
    protocolId: "protocol-v1",
    protocolSha256: "a".repeat(64),
    sourceDatabaseSha256: "b".repeat(64),
  };
  bindResearchDatabase(db, expected);
  bindResearchDatabase(db, expected);
  assert.throws(
    () => bindResearchDatabase(db, { ...expected, protocolSha256: "c".repeat(64) }),
    /metadata mismatch/,
  );
  assert.throws(
    () => bindResearchDatabase(db, { ...expected, stage: "fixed-candidate-extension" }),
    /metadata mismatch/,
  );
  db.close();
});

test("continuous candle validation rejects time gaps even when row count matches", () => {
  assert.equal(hasContinuousCandles([
    { opened_at: "2024-01-01T00:00:00Z" },
    { opened_at: "2024-01-01T00:01:00Z" },
  ], "2024-01-01T00:00:00Z", 2, 60_000), true);
  assert.equal(hasContinuousCandles([
    { opened_at: "2024-01-01T00:00:00Z" },
    { opened_at: "2024-01-01T00:02:00Z" },
  ], "2024-01-01T00:00:00Z", 2, 60_000), false);
});

test("generic event-flow acquisition seals an explicit extension stage and fingerprint", async () => {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), "event-flow-extension-"));
  const source = new DatabaseSync(":memory:");
  const target = new DatabaseSync(":memory:");
  createCandleSchema(source);
  seedTimeframe(source, "BTCUSDT", "M1", "2024-01-01", 1_440, 60_000);
  const block = {
    id: "X01",
    era: "TEST",
    sourceStartDate: "2024-01-01",
    sourceEndDate: "2024-01-01",
    replayStartAt: "2024-01-01T00:00:00Z",
    replayEndAt: "2024-01-02T00:00:00Z",
  };
  let hashCalls = 0;
  try {
    const report = await acquireEventFlowBlocks({
      options: {
        protocol: path.join(root, "config", "protocol.json"),
        report: path.join(root, "extension-report.json"),
      },
      protocolBytes: Buffer.from("frozen-extension"),
      protocol: {
        protocolId: "extension-test-v1",
        sourceData: {
          canonicalCandleDatabase: "source.sqlite",
          canonicalCandleDatabaseSha256: "a".repeat(64),
          researchDatabase: "target.sqlite",
          symbol: "BTCUSDT",
          timeframes: ["M1"],
        },
      },
      blocks: [block],
      stage: "fixed-candidate-extension",
      sourceFingerprintField: "extensionSourceFingerprintSha256",
      logLabel: "test extension",
    }, {
      sourceDb: source,
      targetDb: target,
      hashFile: async () => (++hashCalls === 1 ? "a".repeat(64) : "c".repeat(64)),
      orderBookBackfill: async () => {},
      tradeBackfill: async () => {},
      coverageAudit: () => ({
        status: "COMPLETE",
        sourceFingerprintSha256: "b".repeat(64),
        days: [{ orderBookArchiveSha256: "d".repeat(64), tradeArchiveSha256: "e".repeat(64) }],
      }),
      now: () => "2026-08-06T00:00:00.000Z",
      log: () => {},
    });
    assert.equal(report.status, "COMPLETE");
    assert.equal(report.stage, "fixed-candidate-extension");
    assert.match(report.extensionSourceFingerprintSha256, /^[a-f0-9]{64}$/);
    assert.equal(report.developmentSourceFingerprintSha256, undefined);
    assert.equal(target.prepare("SELECT value FROM researchAcquisitionMetadata WHERE key='stage'").get().value, "fixed-candidate-extension");
  } finally {
    source.close();
    target.close();
    await fs.rm(root, { recursive: true, force: true });
  }
});

function createCandleSchema(db) {
  db.exec(`
    CREATE TABLE marketCandles (
      id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
      symbol TEXT NOT NULL,
      timeframe TEXT NOT NULL,
      opened_at TEXT NOT NULL,
      open TEXT NOT NULL,
      high TEXT NOT NULL,
      low TEXT NOT NULL,
      close TEXT NOT NULL,
      volume TEXT NOT NULL,
      source_timestamp TEXT NOT NULL
    );
    CREATE UNIQUE INDEX marketCandles_symbol_timeframe_openedAt_idx
      ON marketCandles(symbol, timeframe, opened_at);
  `);
}

function seedTimeframe(db, symbol, timeframe, startDate, count, intervalMillis) {
  const start = Date.parse(`${startDate}T00:00:00Z`);
  db.exec("BEGIN");
  try {
    for (let index = 0; index < count; index += 1) {
      insertCandle(db, symbol, timeframe, instantString(start + index * intervalMillis));
    }
    db.exec("COMMIT");
  } catch (error) {
    db.exec("ROLLBACK");
    throw error;
  }
}

function insertCandle(db, symbol, timeframe, openedAt) {
  db.prepare(`
    INSERT INTO marketCandles(symbol, timeframe, opened_at, open, high, low, close, volume, source_timestamp)
    VALUES (?, ?, ?, '100', '101', '99', '100.5', '10', ?)
  `).run(symbol, timeframe, openedAt, openedAt);
}

function instantString(milliseconds) {
  return new Date(milliseconds).toISOString().replace(".000Z", "Z");
}
