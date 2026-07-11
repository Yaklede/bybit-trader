#!/usr/bin/env node

import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { DatabaseSync } from "node:sqlite";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { createInterface } from "node:readline";
import { Readable } from "node:stream";

const DEFAULT_START = "2023-01-19";
const DEFAULT_HISTORY_API_BASE_URL = "https://api2.bybit.com";
const DEFAULT_MAX_DAYS_PER_CATALOG_REQUEST = 6;
const DEFAULT_ORDER_BOOK_DEPTH = 50;
const IMPORTER_VERSION = "bybit-orderbook-archive-v1";
const SYMBOL_PATTERN = /^[A-Z0-9]{2,30}$/;
const ONE_MINUTE_MILLIS = 60_000;
const MINUTES_PER_DAY = 24 * 60;

export function parseArgs(argv) {
  const values = new Map();
  for (const argument of argv) {
    if (!argument.startsWith("--") || !argument.includes("=")) {
      throw new Error(`Invalid argument: ${argument}. Use --name=value.`);
    }
    const [name, ...rest] = argument.slice(2).split("=");
    values.set(name, rest.join("="));
  }

  const today = new Date();
  today.setUTCDate(today.getUTCDate() - 1);
  const options = {
    db: resolve(values.get("db") ?? "build/runtime-test/bybit-trader-full-history.sqlite"),
    symbol: (values.get("symbol") ?? "BTCUSDT").toUpperCase(),
    start: values.get("start") ?? DEFAULT_START,
    end: values.get("end") ?? today.toISOString().slice(0, 10),
    force: values.get("force") === "true",
    historyApiBaseUrl: values.get("history-api-base-url") ?? DEFAULT_HISTORY_API_BASE_URL,
    orderBookDepth: Number(values.get("orderbook-depth") ?? DEFAULT_ORDER_BOOK_DEPTH),
    catalogDaysPerRequest: Number(values.get("catalog-days-per-request") ?? DEFAULT_MAX_DAYS_PER_CATALOG_REQUEST),
    funzipCommand: values.get("funzip-command") ?? "funzip",
  };

  if (!SYMBOL_PATTERN.test(options.symbol)) throw new Error("Symbol must contain only uppercase letters and numbers.");
  if (!isDate(options.start) || !isDate(options.end) || options.start > options.end) {
    throw new Error("Start/end must be valid YYYY-MM-DD values with start <= end.");
  }
  if (!Number.isInteger(options.orderBookDepth) || options.orderBookDepth < 1 || options.orderBookDepth > 500) {
    throw new Error("orderbook-depth must be an integer between 1 and 500.");
  }
  if (!Number.isInteger(options.catalogDaysPerRequest) || options.catalogDaysPerRequest < 1 || options.catalogDaysPerRequest > 6) {
    throw new Error("catalog-days-per-request must be an integer between 1 and 6.");
  }
  if (!options.historyApiBaseUrl.startsWith("https://")) {
    throw new Error("history-api-base-url must use HTTPS.");
  }
  if (!options.funzipCommand.trim()) throw new Error("funzip-command must not be blank.");
  return options;
}

export function ensureSchema(db) {
  db.exec(`
    PRAGMA journal_mode=WAL;
    PRAGMA synchronous=NORMAL;
    PRAGMA busy_timeout=30000;
    CREATE TABLE IF NOT EXISTS orderBookImbalanceBars (
      id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
      symbol TEXT NOT NULL,
      opened_at TEXT NOT NULL,
      sample_count INTEGER NOT NULL,
      mean_bid_notional TEXT NOT NULL,
      mean_ask_notional TEXT NOT NULL,
      mean_imbalance TEXT NOT NULL,
      mean_spread_bps TEXT NOT NULL,
      max_spread_bps TEXT NOT NULL
    );
    CREATE UNIQUE INDEX IF NOT EXISTS orderBookImbalanceBars_symbol_openedAt_idx
      ON orderBookImbalanceBars(symbol, opened_at);
    CREATE TABLE IF NOT EXISTS historicalOrderBookImports (
      id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
      provider TEXT NOT NULL,
      dataset TEXT NOT NULL,
      symbol TEXT NOT NULL,
      source_date TEXT NOT NULL,
      source_url TEXT NOT NULL,
      archive_filename TEXT NOT NULL,
      archive_size_bytes INTEGER NOT NULL,
      archive_sha256 TEXT NOT NULL,
      event_count INTEGER NOT NULL,
      first_event_at TEXT NOT NULL,
      last_event_at TEXT NOT NULL,
      minute_bar_count INTEGER NOT NULL,
      imported_at TEXT NOT NULL,
      importer_version TEXT NOT NULL
    );
    CREATE UNIQUE INDEX IF NOT EXISTS historicalOrderBookImports_source_idx
      ON historicalOrderBookImports(provider, dataset, symbol, source_date);
  `);
}

export async function backfill(options, dependencies = {}) {
  const fetchImpl = dependencies.fetchImpl ?? fetch;
  const log = dependencies.log ?? console.log;
  const db = dependencies.db ?? new DatabaseSync(options.db);
  const ownsDatabase = dependencies.db == null;
  ensureSchema(db);
  try {
    const catalog = await listArchiveFiles(options, fetchImpl);
    const requestedDates = datesBetween(options.start, options.end);
    const byDate = new Map(catalog.map((file) => [file.date, file]));
    const unavailableDates = requestedDates.filter((date) => !byDate.has(date));
    if (unavailableDates.length > 0) {
      throw new Error(`Official order-book archive is unavailable for ${unavailableDates.length} requested day(s): ${unavailableDates.slice(0, 10).join(", ")}${unavailableDates.length > 10 ? ", ..." : ""}`);
    }

    let importedDays = 0;
    let skippedDays = 0;
    for (const date of requestedDates) {
      const file = byDate.get(date);
      if (!options.force && importExists(db, options.symbol, date)) {
        skippedDays += 1;
        continue;
      }
      const result = await importArchiveFile(file, options, fetchImpl);
      persistImportedDay(db, result);
      importedDays += 1;
      log(`order-book archive imported date=${date} events=${result.eventCount} minuteBars=${result.bars.length} sha256=${result.archiveSha256}`);
    }
    return { importedDays, skippedDays, requestedDays: requestedDates.length };
  } finally {
    if (ownsDatabase) db.close();
  }
}

export async function listArchiveFiles(options, fetchImpl = fetch) {
  const files = [];
  for (const { start, end } of dateRanges(options.start, options.end, options.catalogDaysPerRequest)) {
    const url = new URL("/quote/public/support/download/list-files", options.historyApiBaseUrl);
    url.searchParams.set("bizType", "contract");
    url.searchParams.set("productId", "orderbook");
    url.searchParams.set("symbols", options.symbol);
    url.searchParams.set("interval", "daily");
    url.searchParams.set("startDay", start);
    url.searchParams.set("endDay", end);
    const payload = await fetchJson(url, fetchImpl, "order-book archive catalog");
    if (payload.ret_code !== 0 || !Array.isArray(payload.result?.list)) {
      throw new Error(`Order-book archive catalog returned ret_code=${payload.ret_code ?? "unknown"}: ${payload.ret_msg ?? "missing result"}`);
    }
    for (const file of payload.result.list) {
      validateCatalogFile(file, options.symbol);
      files.push(file);
    }
  }
  return files;
}

export async function importArchiveFile(file, options, fetchImpl = fetch) {
  const response = await fetchImpl(file.url);
  if (!response.ok || !response.body) {
    throw new Error(`Order-book archive download failed date=${file.date} HTTP ${response.status}.`);
  }

  const archive = Readable.fromWeb(response.body);
  const archiveHash = createHash("sha256");
  let archiveSizeBytes = 0;
  archive.on("data", (chunk) => {
    archiveHash.update(chunk);
    archiveSizeBytes += chunk.length;
  });

  const funzip = spawn(options.funzipCommand, [], { stdio: ["pipe", "pipe", "pipe"] });
  const stderr = [];
  funzip.stderr.on("data", (chunk) => stderr.push(chunk));
  funzip.stdin.on("error", () => {});
  const processCompletion = waitForProcess(funzip, stderr);
  archive.pipe(funzip.stdin);

  try {
    const aggregate = await aggregateArchiveLines(funzip.stdout, {
      sourceDate: file.date,
      symbol: options.symbol,
      depth: options.orderBookDepth,
    });
    await processCompletion;
    if (archiveSizeBytes !== Number(file.size)) {
      throw new Error(`Order-book archive size mismatch date=${file.date}: expected=${file.size} actual=${archiveSizeBytes}.`);
    }
    assertCompleteDay(aggregate.bars, file.date);
    return {
      file,
      archiveSizeBytes,
      archiveSha256: archiveHash.digest("hex"),
      ...aggregate,
    };
  } catch (error) {
    archive.destroy();
    funzip.kill();
    throw error;
  }
}

export async function aggregateArchiveLines(stream, { sourceDate, symbol, depth }) {
  const reader = createInterface({ input: stream, crlfDelay: Infinity });
  const book = new ArchiveOrderBookAggregator({ sourceDate, symbol, depth });
  for await (const line of reader) {
    if (line) book.record(line);
  }
  return book.finish();
}

export function assertCompleteDay(bars, date) {
  const dayStart = Date.parse(`${date}T00:00:00Z`);
  if (bars.length !== MINUTES_PER_DAY) {
    throw new Error(`Order-book archive day ${date} is incomplete: expected ${MINUTES_PER_DAY} minute bars, received ${bars.length}.`);
  }
  for (let offset = 0; offset < MINUTES_PER_DAY; offset += 1) {
    const expected = dayStart + offset * ONE_MINUTE_MILLIS;
    if (bars[offset].openedAt !== expected) {
      throw new Error(`Order-book archive day ${date} is not continuous at minute offset=${offset}.`);
    }
  }
}

class ArchiveOrderBookAggregator {
  constructor({ sourceDate, symbol, depth }) {
    this.sourceDate = sourceDate;
    this.symbol = symbol;
    this.depth = depth;
    this.bids = new Map();
    this.asks = new Map();
    this.initialized = false;
    this.currentMinute = null;
    this.lastTimestamp = null;
    this.firstTimestamp = null;
    this.eventCount = 0;
    this.bars = [];
  }

  record(line) {
    const message = JSON.parse(line);
    const topic = message.topic;
    const topicMatch = new RegExp(`^orderbook\\.(\\d+)\\.${this.symbol}$`).exec(topic ?? "");
    if (!topicMatch || Number(topicMatch[1]) < this.depth) {
      throw new Error(`Unexpected archive topic: ${topic}`);
    }
    const timestamp = parseEpochMillis(message.ts, "ts");
    if (this.lastTimestamp != null && timestamp < this.lastTimestamp) {
      throw new Error(`Order-book archive timestamps must be non-decreasing: ${timestamp} < ${this.lastTimestamp}.`);
    }
    const minute = Math.floor(timestamp / ONE_MINUTE_MILLIS) * ONE_MINUTE_MILLIS;
    if (this.currentMinute != null && minute > this.currentMinute) {
      this.finalizeMinute(this.currentMinute);
    }
    this.apply(message);
    this.currentMinute = minute;
    this.lastTimestamp = timestamp;
    this.firstTimestamp ??= timestamp;
    this.eventCount += 1;
  }

  finish() {
    if (this.currentMinute != null) this.finalizeMinute(this.currentMinute);
    if (this.firstTimestamp == null || this.lastTimestamp == null) {
      throw new Error(`Order-book archive ${this.sourceDate} contains no events.`);
    }
    return {
      bars: this.bars,
      eventCount: this.eventCount,
      firstEventAt: this.firstTimestamp,
      lastEventAt: this.lastTimestamp,
    };
  }

  apply(message) {
    const type = message.type;
    const data = message.data;
    if (!data || data.s !== this.symbol) throw new Error(`Unexpected archive symbol: ${data?.s ?? "missing"}.`);
    if (type === "snapshot") {
      this.bids.clear();
      this.asks.clear();
      this.initialized = true;
    } else if (type !== "delta") {
      throw new Error(`Unsupported order-book archive message type: ${type}.`);
    } else if (!this.initialized) {
      throw new Error("Order-book archive delta arrived before its initial snapshot.");
    }
    applyLevels(data.b, this.bids);
    applyLevels(data.a, this.asks);
    if (this.bids.size === 0 || this.asks.size === 0) {
      throw new Error("Order-book archive produced an empty bid or ask side.");
    }
  }

  finalizeMinute(openedAt) {
    if (toDate(openedAt) !== this.sourceDate) return;
    const bids = topLevels(this.bids, this.depth, true);
    const asks = topLevels(this.asks, this.depth, false);
    if (bids.length !== this.depth || asks.length !== this.depth) {
      throw new Error(`Order-book archive ${this.sourceDate} has fewer than ${this.depth} levels at ${instantString(openedAt)}.`);
    }
    const bidNotional = sumNotional(bids);
    const askNotional = sumNotional(asks);
    const midpoint = (bids[0][0] + asks[0][0]) / 2;
    if (!Number.isFinite(midpoint) || midpoint <= 0) throw new Error("Order-book archive midpoint must be positive.");
    const totalNotional = bidNotional + askNotional;
    this.bars.push({
      symbol: this.symbol,
      openedAt,
      sampleCount: 1,
      meanBidNotional: bidNotional,
      meanAskNotional: askNotional,
      meanImbalance: totalNotional === 0 ? 0 : (bidNotional - askNotional) / totalNotional,
      meanSpreadBps: ((asks[0][0] - bids[0][0]) / midpoint) * 10_000,
      maxSpreadBps: ((asks[0][0] - bids[0][0]) / midpoint) * 10_000,
    });
  }
}

function applyLevels(rows, levels) {
  if (!Array.isArray(rows)) return;
  for (const row of rows) {
    if (!Array.isArray(row) || row.length < 2) throw new Error("Order-book archive level must contain price and size.");
    const price = Number(row[0]);
    const quantity = Number(row[1]);
    if (!Number.isFinite(price) || price <= 0 || !Number.isFinite(quantity) || quantity < 0) {
      throw new Error(`Invalid order-book archive level price=${row[0]} quantity=${row[1]}.`);
    }
    if (quantity === 0) levels.delete(price);
    else levels.set(price, quantity);
  }
}

function topLevels(levels, depth, descending) {
  const selected = [];
  for (const entry of levels) {
    if (selected.length === depth && (descending ? entry[0] <= selected.at(-1)[0] : entry[0] >= selected.at(-1)[0])) {
      continue;
    }
    let low = 0;
    let high = selected.length;
    while (low < high) {
      const middle = Math.floor((low + high) / 2);
      const before = descending ? entry[0] > selected[middle][0] : entry[0] < selected[middle][0];
      if (before) high = middle;
      else low = middle + 1;
    }
    selected.splice(low, 0, entry);
    if (selected.length > depth) selected.pop();
  }
  return selected;
}

function sumNotional(levels) {
  return levels.reduce((sum, [price, quantity]) => sum + price * quantity, 0);
}

function persistImportedDay(db, result) {
  const existingImport = db.prepare(`
    SELECT archive_sha256 FROM historicalOrderBookImports
    WHERE provider='bybit' AND dataset='orderbook' AND symbol=? AND source_date=?
    LIMIT 1
  `).get(result.file.symbol, result.file.date);
  verifyExistingArchiveHash(existingImport?.archive_sha256, result.archiveSha256, result.file.date);
  const insertBar = db.prepare(`
    INSERT INTO orderBookImbalanceBars(
      symbol, opened_at, sample_count, mean_bid_notional, mean_ask_notional,
      mean_imbalance, mean_spread_bps, max_spread_bps
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(symbol, opened_at) DO UPDATE SET
      sample_count=excluded.sample_count,
      mean_bid_notional=excluded.mean_bid_notional,
      mean_ask_notional=excluded.mean_ask_notional,
      mean_imbalance=excluded.mean_imbalance,
      mean_spread_bps=excluded.mean_spread_bps,
      max_spread_bps=excluded.max_spread_bps
  `);
  const insertManifest = db.prepare(`
    INSERT INTO historicalOrderBookImports(
      provider, dataset, symbol, source_date, source_url, archive_filename, archive_size_bytes,
      archive_sha256, event_count, first_event_at, last_event_at, minute_bar_count, imported_at, importer_version
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(provider, dataset, symbol, source_date) DO NOTHING
  `);
  inTransaction(db, () => {
    for (const bar of result.bars) {
      insertBar.run(
        bar.symbol,
        instantString(bar.openedAt),
        bar.sampleCount,
        decimalString(bar.meanBidNotional),
        decimalString(bar.meanAskNotional),
        decimalString(bar.meanImbalance),
        decimalString(bar.meanSpreadBps),
        decimalString(bar.maxSpreadBps),
      );
    }
    insertManifest.run(
      "bybit",
      "orderbook",
      result.file.symbol,
      result.file.date,
      result.file.url,
      result.file.filename,
      result.archiveSizeBytes,
      result.archiveSha256,
      result.eventCount,
      instantString(result.firstEventAt),
      instantString(result.lastEventAt),
      result.bars.length,
      new Date().toISOString(),
      IMPORTER_VERSION,
    );
  });
}

export function verifyExistingArchiveHash(existingHash, archiveHash, sourceDate) {
  if (existingHash != null && existingHash !== archiveHash) {
    throw new Error(`Official order-book archive hash changed for ${sourceDate}; refusing to replace the recorded provenance.`);
  }
}

function importExists(db, symbol, date) {
  return db.prepare(`
    SELECT 1 FROM historicalOrderBookImports
    WHERE provider='bybit' AND dataset='orderbook' AND symbol=? AND source_date=?
    LIMIT 1
  `).get(symbol, date) != null;
}

function validateCatalogFile(file, expectedSymbol) {
  if (file.bizType !== "contract" || file.productId !== "orderbook" || file.interval !== "daily") {
    throw new Error("Unexpected order-book archive catalog metadata.");
  }
  if (file.symbol !== expectedSymbol || !isDate(file.date) || !file.filename || !file.url?.startsWith("https://") || !Number.isSafeInteger(Number(file.size)) || Number(file.size) <= 0) {
    throw new Error("Invalid order-book archive catalog file metadata.");
  }
}

async function fetchJson(url, fetchImpl, label) {
  const response = await fetchImpl(url);
  if (!response.ok) throw new Error(`${label} failed with HTTP ${response.status}.`);
  return response.json();
}

function waitForProcess(process, stderr) {
  return new Promise((resolve, reject) => {
    process.once("error", reject);
    process.once("close", (code) => {
      if (code === 0) resolve();
      else reject(new Error(`funzip exited with code=${code}: ${Buffer.concat(stderr).toString().trim()}`));
    });
  });
}

function inTransaction(db, action) {
  db.exec("BEGIN IMMEDIATE");
  try {
    action();
    db.exec("COMMIT");
  } catch (error) {
    db.exec("ROLLBACK");
    throw error;
  }
}

function* dateRanges(start, end, maxDays) {
  let current = start;
  while (current <= end) {
    const rangeEnd = addUtcDays(current, maxDays - 1);
    yield { start: current, end: rangeEnd > end ? end : rangeEnd };
    current = addUtcDays(rangeEnd, 1);
  }
}

function datesBetween(start, end) {
  const result = [];
  let current = start;
  while (current <= end) {
    result.push(current);
    current = addUtcDays(current, 1);
  }
  return result;
}

function addUtcDays(date, days) {
  const value = new Date(`${date}T00:00:00Z`);
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
}

function parseEpochMillis(value, fieldName) {
  const timestamp = Number(value);
  if (!Number.isSafeInteger(timestamp) || timestamp <= 0) throw new Error(`Order-book archive ${fieldName} must be a positive epoch millisecond.`);
  return timestamp;
}

function decimalString(value) {
  if (!Number.isFinite(value)) throw new Error("Cannot persist a non-finite decimal.");
  return Number(value.toPrecision(15)).toString();
}

function instantString(milliseconds) {
  return new Date(milliseconds).toISOString().replace(".000Z", "Z");
}

function toDate(milliseconds) {
  return new Date(milliseconds).toISOString().slice(0, 10);
}

function isDate(value) {
  return /^\d{4}-\d{2}-\d{2}$/.test(value) && new Date(`${value}T00:00:00Z`).toISOString().slice(0, 10) === value;
}

const invokedPath = process.argv[1] ? pathToFileURL(resolve(process.argv[1])).href : null;
if (invokedPath === import.meta.url) {
  const options = parseArgs(process.argv.slice(2));
  console.log(`Bybit order-book backfill db=${options.db} symbol=${options.symbol} range=${options.start}..${options.end} depth=${options.orderBookDepth}`);
  const result = await backfill(options);
  console.log(JSON.stringify(result));
}
