#!/usr/bin/env node

import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { DatabaseSync } from "node:sqlite";
import { dirname, resolve } from "node:path";
import { pathToFileURL } from "node:url";

import {
  backfill as backfillOrderBook,
  parseArgs as parseOrderBookArgs,
} from "./bybit-orderbook-backfill.mjs";
import {
  backfill as backfillTrades,
  parseArgs as parseTradeArgs,
} from "./bybit-flow-backfill.mjs";
import { auditEventFlowCoverage } from "./bybit-event-flow-coverage-audit.mjs";
import {
  acquisitionBlocks,
  validateEventFlowProtocol,
} from "./event-flow-research-protocol.mjs";

const DEFAULT_PROTOCOL = "config/bybit-event-flow-development-v1.json";
const ONE_MINUTE_MILLIS = 60_000;
const TIMEFRAME_INTERVALS = new Map([
  ["M1", ONE_MINUTE_MILLIS],
  ["M5", 5 * ONE_MINUTE_MILLIS],
  ["M15", 15 * ONE_MINUTE_MILLIS],
]);
const SCOPED_TIME_TABLES = [
  ["marketCandles", "opened_at"],
  ["orderBookImbalanceBars", "opened_at"],
  ["orderBookEventFlowBars", "opened_at"],
  ["takerFlowBars", "opened_at"],
  ["takerEventFlowBars", "opened_at"],
];
const SCOPED_IMPORT_TABLES = [
  ["historicalOrderBookImports", "source_date"],
  ["historicalTradeImports", "source_date"],
];

export function parseArgs(argv) {
  const values = new Map();
  for (const argument of argv) {
    if (!argument.startsWith("--") || !argument.includes("=")) {
      throw new Error(`Invalid argument: ${argument}. Use --name=value.`);
    }
    const [name, ...rest] = argument.slice(2).split("=");
    if (!["protocol", "report"].includes(name)) throw new Error(`Unsupported argument: --${name}.`);
    values.set(name, rest.join("="));
  }
  return {
    protocol: resolve(values.get("protocol") ?? DEFAULT_PROTOCOL),
    report: values.has("report") ? resolve(values.get("report")) : null,
  };
}

export async function acquireDevelopment(options, dependencies = {}) {
  const protocolBytes = await readFile(options.protocol);
  const protocol = validateEventFlowProtocol(JSON.parse(protocolBytes));
  const blocks = acquisitionBlocks(protocol, "development");
  return acquireEventFlowBlocks({
    options,
    protocolBytes,
    protocol,
    blocks,
    stage: "development",
    sourceFingerprintField: "developmentSourceFingerprintSha256",
    logLabel: "event-flow development",
  }, dependencies);
}

export async function acquireEventFlowBlocks(context, dependencies = {}) {
  const {
    options,
    protocolBytes,
    protocol,
    blocks,
    stage,
    sourceFingerprintField,
    logLabel,
  } = context;
  const protocolSha256 = sha256(protocolBytes);
  const repositoryRoot = resolve(dirname(options.protocol), "..");
  const sourceDatabase = resolve(repositoryRoot, protocol.sourceData.canonicalCandleDatabase);
  const targetDatabase = resolve(repositoryRoot, protocol.sourceData.researchDatabase);
  const reportPath = options.report ?? resolve(
    repositoryRoot,
    `build/research/${protocol.protocolId}-acquisition.json`,
  );
  if (sourceDatabase === targetDatabase) throw new Error("Research database must not be the canonical candle database.");

  const hashFile = dependencies.hashFile ?? sha256File;
  const actualSourceSha256 = await hashFile(sourceDatabase);
  if (actualSourceSha256 !== protocol.sourceData.canonicalCandleDatabaseSha256) {
    throw new Error(
      `Canonical candle database hash mismatch: expected ${protocol.sourceData.canonicalCandleDatabaseSha256}, got ${actualSourceSha256}.`,
    );
  }

  await mkdir(dirname(targetDatabase), { recursive: true });
  await mkdir(dirname(reportPath), { recursive: true });
  const sourceDb = dependencies.sourceDb ?? new DatabaseSync(sourceDatabase, { readOnly: true });
  const targetDb = dependencies.targetDb ?? new DatabaseSync(targetDatabase);
  const ownsSourceDb = dependencies.sourceDb == null;
  const ownsTargetDb = dependencies.targetDb == null;
  const now = dependencies.now ?? (() => new Date().toISOString());
  const log = dependencies.log ?? console.log;
  const orderBookBackfill = dependencies.orderBookBackfill ?? backfillOrderBook;
  const tradeBackfill = dependencies.tradeBackfill ?? backfillTrades;
  const coverageAudit = dependencies.coverageAudit ?? auditEventFlowCoverage;
  const startedAt = now();
  const report = {
    schemaVersion: 1,
    protocolId: protocol.protocolId,
    protocolSha256,
    stage,
    status: "IN_PROGRESS",
    sourceDatabase: protocol.sourceData.canonicalCandleDatabase,
    sourceDatabaseSha256: actualSourceSha256,
    targetDatabase: protocol.sourceData.researchDatabase,
    startedAt,
    updatedAt: startedAt,
    completedBlocks: [],
    failure: null,
    targetDatabaseSha256: null,
    [sourceFingerprintField]: null,
  };

  let failure = null;
  try {
    ensureResearchSchema(targetDb);
    bindResearchDatabase(targetDb, {
      protocolId: protocol.protocolId,
      protocolSha256,
      sourceDatabaseSha256: actualSourceSha256,
      stage,
    });
    assertDevelopmentOnly(targetDb, blocks);
    await writeReport(reportPath, report);

    for (const block of blocks) {
      log(`${logLabel} acquisition started block=${block.id} range=${block.sourceStartDate}..${block.sourceEndDate}`);
      copyCandleBlock(sourceDb, targetDb, protocol.sourceData.symbol, protocol.sourceData.timeframes, block);
      assertDevelopmentOnly(targetDb, blocks);

      await orderBookBackfill(
        parseOrderBookArgs([
          `--db=${targetDatabase}`,
          `--symbol=${protocol.sourceData.symbol}`,
          `--start=${block.sourceStartDate}`,
          `--end=${block.sourceEndDate}`,
        ]),
        { db: targetDb, log },
      );
      await tradeBackfill(
        parseTradeArgs([
          `--db=${targetDatabase}`,
          `--symbol=${protocol.sourceData.symbol}`,
          `--start=${block.sourceStartDate}`,
          `--end=${block.sourceEndDate}`,
          "--datasets=trades",
        ]),
        { db: targetDb, log },
      );

      const audit = coverageAudit(
        {
          db: targetDatabase,
          symbol: protocol.sourceData.symbol,
          start: block.sourceStartDate,
          end: block.sourceEndDate,
        },
        { db: targetDb },
      );
      if (audit.status !== "COMPLETE") {
        throw new Error(`Event-flow evidence audit rejected ${block.id}: ${audit.status}.`);
      }
      report.completedBlocks.push({
        id: block.id,
        era: block.era,
        sourceStartDate: block.sourceStartDate,
        sourceEndDate: block.sourceEndDate,
        replayStartAt: block.replayStartAt,
        replayEndAt: block.replayEndAt,
        sourceFingerprintSha256: audit.sourceFingerprintSha256,
        orderBookArchiveSha256: audit.days.map((day) => day.orderBookArchiveSha256),
        tradeArchiveSha256: audit.days.map((day) => day.tradeArchiveSha256),
      });
      report.updatedAt = now();
      await writeReport(reportPath, report);
      log(`${logLabel} acquisition completed block=${block.id} fingerprint=${audit.sourceFingerprintSha256}`);
    }
    assertDevelopmentOnly(targetDb, blocks);
    report.status = "COMPLETE";
    report.updatedAt = now();
    report[sourceFingerprintField] = sha256(
      report.completedBlocks.map((block) => `${block.id}|${block.sourceFingerprintSha256}`).join("\n"),
    );
  } catch (error) {
    failure = error;
    report.status = "FAILED_SOURCE_ACQUISITION";
    report.updatedAt = now();
    report.failure = {
      name: error instanceof Error ? error.name : "Error",
      message: error instanceof Error ? error.message : String(error),
    };
  } finally {
    if (ownsSourceDb) sourceDb.close();
    if (ownsTargetDb) targetDb.close();
  }

  if (failure == null) report.targetDatabaseSha256 = await hashFile(targetDatabase);
  await writeReport(reportPath, report);
  if (failure != null) throw failure;
  return report;
}

export function ensureResearchSchema(db) {
  db.exec(`
    PRAGMA journal_mode=WAL;
    PRAGMA synchronous=NORMAL;
    PRAGMA busy_timeout=30000;
    CREATE TABLE IF NOT EXISTS marketCandles (
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
    CREATE UNIQUE INDEX IF NOT EXISTS marketCandles_symbol_timeframe_openedAt_idx
      ON marketCandles(symbol, timeframe, opened_at);
    CREATE TABLE IF NOT EXISTS researchAcquisitionMetadata (
      key TEXT NOT NULL PRIMARY KEY,
      value TEXT NOT NULL
    );
  `);
}

export function bindResearchDatabase(db, expected) {
  const entries = new Map([
    ["protocolId", expected.protocolId],
    ["protocolSha256", expected.protocolSha256],
    ["stage", expected.stage ?? "development"],
    ["sourceDatabaseSha256", expected.sourceDatabaseSha256],
  ]);
  const select = db.prepare("SELECT value FROM researchAcquisitionMetadata WHERE key=?");
  const insert = db.prepare("INSERT INTO researchAcquisitionMetadata(key, value) VALUES (?, ?)");
  db.exec("BEGIN IMMEDIATE");
  try {
    for (const [key, value] of entries) {
      const existing = select.get(key);
      if (existing != null && existing.value !== value) {
        throw new Error(`Research database metadata mismatch for ${key}.`);
      }
      if (existing == null) insert.run(key, value);
    }
    db.exec("COMMIT");
  } catch (error) {
    db.exec("ROLLBACK");
    throw error;
  }
}

export function copyCandleBlock(sourceDb, targetDb, symbol, timeframes, block) {
  const sourceStartAt = `${block.sourceStartDate}T00:00:00Z`;
  const sourceEndAt = `${nextDate(block.sourceEndDate)}T00:00:00Z`;
  const select = sourceDb.prepare(`
    SELECT symbol, timeframe, opened_at, open, high, low, close, volume, source_timestamp
    FROM marketCandles
    WHERE symbol=? AND timeframe=? AND opened_at>=? AND opened_at<?
    ORDER BY opened_at
  `);
  const upsert = targetDb.prepare(`
    INSERT INTO marketCandles(symbol, timeframe, opened_at, open, high, low, close, volume, source_timestamp)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(symbol, timeframe, opened_at) DO UPDATE SET
      open=excluded.open,
      high=excluded.high,
      low=excluded.low,
      close=excluded.close,
      volume=excluded.volume,
      source_timestamp=excluded.source_timestamp
  `);
  const rowsByTimeframe = new Map();
  for (const timeframe of timeframes) {
    const intervalMillis = TIMEFRAME_INTERVALS.get(timeframe);
    if (intervalMillis == null) throw new Error(`Unsupported frozen timeframe: ${timeframe}.`);
    const rows = select.all(symbol, timeframe, sourceStartAt, sourceEndAt);
    const expectedCount = (Date.parse(sourceEndAt) - Date.parse(sourceStartAt)) / intervalMillis;
    if (!hasContinuousCandles(rows, sourceStartAt, expectedCount, intervalMillis)) {
      throw new Error(`Canonical candle coverage is incomplete for ${block.id} ${timeframe}.`);
    }
    rowsByTimeframe.set(timeframe, rows);
  }

  targetDb.exec("BEGIN IMMEDIATE");
  try {
    for (const rows of rowsByTimeframe.values()) {
      for (const row of rows) {
        upsert.run(
          row.symbol,
          row.timeframe,
          row.opened_at,
          row.open,
          row.high,
          row.low,
          row.close,
          row.volume,
          row.source_timestamp,
        );
      }
    }
    targetDb.exec("COMMIT");
  } catch (error) {
    targetDb.exec("ROLLBACK");
    throw error;
  }
}

export function assertDevelopmentOnly(db, blocks) {
  const allowedDates = new Set(blocks.flatMap((block) => datesBetween(block.sourceStartDate, block.sourceEndDate)));
  for (const [table, column] of [...SCOPED_TIME_TABLES, ...SCOPED_IMPORT_TABLES]) {
    if (!tableExists(db, table)) continue;
    const expression = column === "opened_at" ? `substr(${column}, 1, 10)` : column;
    const rows = db.prepare(`SELECT DISTINCT ${expression} AS source_date FROM ${table}`).all();
    const unexpected = rows.map((row) => row.source_date).filter((date) => !allowedDates.has(date));
    if (unexpected.length > 0) {
      throw new Error(`Research database contains locked or undeclared ${table} date(s): ${unexpected.slice(0, 5).join(", ")}.`);
    }
  }
}

export function hasContinuousCandles(rows, startAt, expectedCount, intervalMillis) {
  if (rows.length !== expectedCount) return false;
  const start = Date.parse(startAt);
  return rows.every((row, index) => row.opened_at === instantString(start + index * intervalMillis));
}

export async function sha256File(path) {
  const hash = createHash("sha256");
  for await (const chunk of createReadStream(path)) hash.update(chunk);
  return hash.digest("hex");
}

async function writeReport(path, report) {
  const temporaryPath = `${path}.tmp`;
  await writeFile(temporaryPath, `${JSON.stringify(report, null, 2)}\n`);
  await rename(temporaryPath, path);
}

function tableExists(db, table) {
  return db.prepare("SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1").get(table) != null;
}

function datesBetween(start, end) {
  const dates = [];
  for (let date = start; date <= end; date = nextDate(date)) dates.push(date);
  return dates;
}

function nextDate(date) {
  const value = new Date(`${date}T00:00:00Z`);
  value.setUTCDate(value.getUTCDate() + 1);
  return value.toISOString().slice(0, 10);
}

function instantString(milliseconds) {
  return new Date(milliseconds).toISOString().replace(".000Z", "Z");
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

const invokedPath = process.argv[1] ? pathToFileURL(resolve(process.argv[1])).href : null;
if (invokedPath === import.meta.url) {
  const options = parseArgs(process.argv.slice(2));
  const report = await acquireDevelopment(options);
  console.log(JSON.stringify({
    status: report.status,
    completedBlocks: report.completedBlocks.length,
    sourceFingerprintSha256: report.developmentSourceFingerprintSha256,
    targetDatabaseSha256: report.targetDatabaseSha256,
  }));
}
