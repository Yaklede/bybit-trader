#!/usr/bin/env node

import { createHash } from "node:crypto";
import { DatabaseSync } from "node:sqlite";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

const SYMBOL_PATTERN = /^[A-Z0-9]{2,30}$/;
const ORDER_BOOK_IMPORTER_VERSION = "bybit-orderbook-archive-v2-event-flow";
const TRADE_IMPORTER_VERSION = "bybit-public-trades-v2-event-flow";
const ONE_MINUTE_MILLIS = 60_000;
const TABLES = [
  "marketCandles",
  "orderBookImbalanceBars",
  "orderBookEventFlowBars",
  "takerFlowBars",
  "takerEventFlowBars",
  "historicalOrderBookImports",
  "historicalTradeImports",
];

export function parseArgs(argv) {
  const values = new Map();
  for (const argument of argv) {
    if (!argument.startsWith("--") || !argument.includes("=")) {
      throw new Error(`Invalid argument: ${argument}. Use --name=value.`);
    }
    const [name, ...rest] = argument.slice(2).split("=");
    if (!["db", "symbol", "start", "end"].includes(name)) throw new Error(`Unsupported argument: --${name}.`);
    values.set(name, rest.join("="));
  }
  const options = {
    db: values.get("db") == null ? null : resolve(values.get("db")),
    symbol: (values.get("symbol") ?? "BTCUSDT").toUpperCase(),
    start: values.get("start") ?? null,
    end: values.get("end") ?? null,
  };
  if (options.db == null) throw new Error("db is required.");
  if (!SYMBOL_PATTERN.test(options.symbol)) throw new Error("Symbol must contain only uppercase letters and numbers.");
  if (!isDate(options.start) || !isDate(options.end) || options.start > options.end) {
    throw new Error("start/end must be valid YYYY-MM-DD values with start <= end.");
  }
  return options;
}

export function auditEventFlowCoverage(options, dependencies = {}) {
  const db = dependencies.db ?? new DatabaseSync(options.db, { readOnly: true });
  const ownsDatabase = dependencies.db == null;
  try {
    const missingTables = TABLES.filter((table) => !tableExists(db, table));
    if (missingTables.length > 0) {
      return {
        schemaVersion: 1,
        status: "REJECTED_MISSING_TABLES",
        symbol: options.symbol,
        requestedRange: requestedRange(options.start, options.end),
        missingTables,
        completeDays: 0,
        invalidDays: requestedRange(options.start, options.end).days,
        days: [],
        sourceFingerprintSha256: null,
      };
    }

    const days = datesBetween(options.start, options.end).map((date) => auditDay(db, options.symbol, date));
    const complete = days.filter((day) => day.status === "COMPLETE");
    const sourceFingerprintSha256 = complete.length === days.length
      ? createHash("sha256")
        .update(complete.map((day) => `${day.date}|${day.orderBookArchiveSha256}|${day.tradeArchiveSha256}`).join("\n"))
        .digest("hex")
      : null;
    return {
      schemaVersion: 1,
      status: complete.length === days.length ? "COMPLETE" : "REJECTED_INCOMPLETE_COVERAGE",
      symbol: options.symbol,
      requestedRange: requestedRange(options.start, options.end),
      missingTables: [],
      completeDays: complete.length,
      invalidDays: days.length - complete.length,
      days,
      sourceFingerprintSha256,
    };
  } finally {
    if (ownsDatabase) db.close();
  }
}

function auditDay(db, symbol, date) {
  const startAt = `${date}T00:00:00Z`;
  const endAt = `${nextDate(date)}T00:00:00Z`;
  const reasons = [];
  const orderBookManifest = db.prepare(`
    SELECT archive_sha256, minute_bar_count, importer_version
    FROM historicalOrderBookImports
    WHERE provider='bybit' AND dataset='orderbook' AND symbol=? AND source_date=?
    LIMIT 1
  `).get(symbol, date);
  const tradeManifest = db.prepare(`
    SELECT archive_sha256, minute_bar_count, importer_version
    FROM historicalTradeImports
    WHERE provider='bybit' AND dataset='public-trades' AND symbol=? AND source_date=?
    LIMIT 1
  `).get(symbol, date);
  validateManifest(orderBookManifest, ORDER_BOOK_IMPORTER_VERSION, "ORDER_BOOK", reasons);
  validateManifest(tradeManifest, TRADE_IMPORTER_VERSION, "TRADE", reasons);

  validateMinuteTable(db, "orderBookImbalanceBars", "opened_at", symbol, startAt, endAt, 1_440, reasons);
  validateMinuteTable(db, "orderBookEventFlowBars", "opened_at", symbol, startAt, endAt, 1_440, reasons);
  validateMinuteTable(db, "takerFlowBars", "opened_at", symbol, startAt, endAt, 1_440, reasons);
  validateMinuteTable(db, "takerEventFlowBars", "opened_at", symbol, startAt, endAt, 1_440, reasons);
  validateCandleTable(db, symbol, "M1", startAt, endAt, 1_440, reasons);
  validateCandleTable(db, symbol, "M5", startAt, endAt, 288, reasons);
  validateCandleTable(db, symbol, "M15", startAt, endAt, 96, reasons);

  return {
    date,
    status: reasons.length === 0 ? "COMPLETE" : "INVALID",
    reasons,
    orderBookArchiveSha256: orderBookManifest?.archive_sha256 ?? null,
    tradeArchiveSha256: tradeManifest?.archive_sha256 ?? null,
  };
}

function validateManifest(manifest, importerVersion, label, reasons) {
  if (manifest == null) {
    reasons.push(`${label}_MANIFEST_MISSING`);
    return;
  }
  if (!isSha256(manifest.archive_sha256)) reasons.push(`${label}_ARCHIVE_HASH_INVALID`);
  if (Number(manifest.minute_bar_count) !== 1_440) reasons.push(`${label}_MANIFEST_MINUTE_COUNT_INVALID`);
  if (manifest.importer_version !== importerVersion) reasons.push(`${label}_IMPORTER_VERSION_INVALID`);
}

function validateMinuteTable(db, table, timeColumn, symbol, startAt, endAt, expectedCount, reasons) {
  const rows = db.prepare(`
    SELECT ${timeColumn} AS opened_at FROM ${table}
    WHERE symbol=? AND ${timeColumn}>=? AND ${timeColumn}<?
    ORDER BY ${timeColumn}
  `).all(symbol, startAt, endAt);
  if (!hasContinuousRows(rows, startAt, expectedCount, ONE_MINUTE_MILLIS)) reasons.push(`${table}_INCOMPLETE`);
}

function validateCandleTable(db, symbol, timeframe, startAt, endAt, expectedCount, reasons) {
  const rows = db.prepare(`
    SELECT opened_at FROM marketCandles
    WHERE symbol=? AND timeframe=? AND opened_at>=? AND opened_at<?
    ORDER BY opened_at
  `).all(symbol, timeframe, startAt, endAt);
  const intervalMillis = timeframe === "M1" ? ONE_MINUTE_MILLIS : timeframe === "M5" ? 5 * ONE_MINUTE_MILLIS : 15 * ONE_MINUTE_MILLIS;
  if (!hasContinuousRows(rows, startAt, expectedCount, intervalMillis)) reasons.push(`marketCandles_${timeframe}_INCOMPLETE`);
}

export function hasContinuousRows(rows, startAt, expectedCount, intervalMillis) {
  if (rows.length !== expectedCount) return false;
  const start = Date.parse(startAt);
  return rows.every((row, index) => row.opened_at === instantString(start + index * intervalMillis));
}

function tableExists(db, table) {
  return db.prepare("SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1").get(table) != null;
}

function requestedRange(start, end) {
  return { start, end, days: datesBetween(start, end).length };
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

function isDate(value) {
  return typeof value === "string" && /^\d{4}-\d{2}-\d{2}$/.test(value)
    && new Date(`${value}T00:00:00Z`).toISOString().slice(0, 10) === value;
}

function isSha256(value) {
  return typeof value === "string" && /^[a-f0-9]{64}$/.test(value);
}

const invokedPath = process.argv[1] ? pathToFileURL(resolve(process.argv[1])).href : null;
if (invokedPath === import.meta.url) {
  const report = auditEventFlowCoverage(parseArgs(process.argv.slice(2)));
  console.log(JSON.stringify(report, null, 2));
  if (report.status !== "COMPLETE") process.exitCode = 1;
}
