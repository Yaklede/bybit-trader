#!/usr/bin/env node

import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import { DatabaseSync } from "node:sqlite";
import { createReadStream } from "node:fs";
import { access } from "node:fs/promises";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { createInterface } from "node:readline";
import { Readable } from "node:stream";

const DEFAULT_START = "2023-01-19";
const DEFAULT_HISTORY_API_BASE_URL = "https://api2.bybit.com";
const DEFAULT_MAX_DAYS_PER_CATALOG_REQUEST = 6;
const DEFAULT_ORDER_BOOK_DEPTH = 50;
const DEFAULT_ARCHIVE_ATTEMPTS = 3;
const DEFAULT_ARCHIVE_RETRY_DELAY_MILLIS = 1_000;
const IMPORTER_VERSION = "bybit-orderbook-archive-v2-event-flow";
const SYMBOL_PATTERN = /^[A-Z0-9]{2,30}$/;
const ONE_MINUTE_MILLIS = 60_000;
const MINUTES_PER_DAY = 24 * 60;
const MICROSTRUCTURE_DEPTH = 5;

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
    archiveAttempts: Number(values.get("archive-attempts") ?? DEFAULT_ARCHIVE_ATTEMPTS),
    archiveRetryDelayMillis: Number(values.get("archive-retry-delay-millis") ?? DEFAULT_ARCHIVE_RETRY_DELAY_MILLIS),
    archiveDirectory: values.get("archive-dir") == null ? null : resolve(values.get("archive-dir")),
    funzipCommand: values.get("funzip-command") ?? "funzip",
  };

  if (!SYMBOL_PATTERN.test(options.symbol)) throw new Error("Symbol must contain only uppercase letters and numbers.");
  if (!isDate(options.start) || !isDate(options.end) || options.start > options.end) {
    throw new Error("Start/end must be valid YYYY-MM-DD values with start <= end.");
  }
  if (options.orderBookDepth !== DEFAULT_ORDER_BOOK_DEPTH) {
    throw new Error(`orderbook-depth must be ${DEFAULT_ORDER_BOOK_DEPTH} for archive/live feature parity.`);
  }
  if (!Number.isInteger(options.catalogDaysPerRequest) || options.catalogDaysPerRequest < 1 || options.catalogDaysPerRequest > 6) {
    throw new Error("catalog-days-per-request must be an integer between 1 and 6.");
  }
  if (!Number.isInteger(options.archiveAttempts) || options.archiveAttempts < 1 || options.archiveAttempts > 5) {
    throw new Error("archive-attempts must be an integer between 1 and 5.");
  }
  if (!Number.isInteger(options.archiveRetryDelayMillis) || options.archiveRetryDelayMillis < 0 || options.archiveRetryDelayMillis > 60_000) {
    throw new Error("archive-retry-delay-millis must be an integer between 0 and 60000.");
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
    CREATE TABLE IF NOT EXISTS orderBookEventFlowBars (
      id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
      symbol TEXT NOT NULL,
      opened_at TEXT NOT NULL,
      message_count INTEGER NOT NULL,
      snapshot_count INTEGER NOT NULL,
      mean_top5_imbalance TEXT NOT NULL,
      mean_top50_imbalance TEXT NOT NULL,
      start_top5_imbalance TEXT NOT NULL,
      end_top5_imbalance TEXT NOT NULL,
      min_top5_imbalance TEXT NOT NULL,
      max_top5_imbalance TEXT NOT NULL,
      mean_spread_bps TEXT NOT NULL,
      max_spread_bps TEXT NOT NULL,
      mean_microprice_edge_bps TEXT NOT NULL,
      bid_added_top5_notional TEXT NOT NULL,
      bid_removed_top5_notional TEXT NOT NULL,
      ask_added_top5_notional TEXT NOT NULL,
      ask_removed_top5_notional TEXT NOT NULL,
      bid_update_count INTEGER NOT NULL,
      ask_update_count INTEGER NOT NULL,
      open_mid_price TEXT NOT NULL,
      high_mid_price TEXT NOT NULL,
      low_mid_price TEXT NOT NULL,
      close_mid_price TEXT NOT NULL
    );
    CREATE UNIQUE INDEX IF NOT EXISTS orderBookEventFlowBars_symbol_openedAt_idx
      ON orderBookEventFlowBars(symbol, opened_at);
    CREATE TABLE IF NOT EXISTS liquidationFlowBars (
      id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
      symbol TEXT NOT NULL,
      opened_at TEXT NOT NULL,
      long_liquidation_notional TEXT NOT NULL,
      short_liquidation_notional TEXT NOT NULL,
      long_liquidation_count INTEGER NOT NULL,
      short_liquidation_count INTEGER NOT NULL
    );
    CREATE UNIQUE INDEX IF NOT EXISTS liquidationFlowBars_symbol_openedAt_idx
      ON liquidationFlowBars(symbol, opened_at);
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
  return retryArchiveOperation(
    () => importArchiveFileOnce(file, options, fetchImpl),
    options.archiveAttempts ?? DEFAULT_ARCHIVE_ATTEMPTS,
    options.archiveRetryDelayMillis ?? DEFAULT_ARCHIVE_RETRY_DELAY_MILLIS,
  );
}

async function importArchiveFileOnce(file, options, fetchImpl) {
  const { stream: archive, localArchive } = await openArchiveStream(file, options, fetchImpl);
  const archiveHash = createHash("sha256");
  let archiveSizeBytes = 0;
  const archiveCompletion = waitForReadableEnd(archive);
  archive.on("data", (chunk) => {
    archiveHash.update(chunk);
    archiveSizeBytes += chunk.length;
  });

  const funzip = localArchive == null
    ? spawn(options.funzipCommand, [], { stdio: ["pipe", "pipe", "pipe"] })
    : spawn("unzip", ["-p", localArchive], { stdio: ["ignore", "pipe", "pipe"] });
  const extractorName = localArchive == null ? options.funzipCommand : "unzip";
  const stderr = [];
  funzip.stderr.on("data", (chunk) => stderr.push(chunk));
  if (funzip.stdin != null) funzip.stdin.on("error", () => {});
  const processCompletion = waitForProcess(funzip, stderr, extractorName);
  if (localArchive == null) archive.pipe(funzip.stdin);

  try {
    const aggregate = await aggregateArchiveLines(funzip.stdout, {
      sourceDate: file.date,
      symbol: options.symbol,
      depth: options.orderBookDepth,
    });
    await processCompletion;
    await archiveCompletion;
    if (archiveSizeBytes !== Number(file.size)) {
      throw new Error(`Order-book archive size mismatch date=${file.date}: expected=${file.size} actual=${archiveSizeBytes}.`);
    }
    assertCompleteDay(aggregate.bars, file.date);
    assertCompleteDay(aggregate.eventFlowBars, file.date);
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

export async function openArchiveStream(file, options, fetchImpl = fetch) {
  if (options.archiveDirectory != null) {
    const localArchive = resolve(options.archiveDirectory, file.filename);
    try {
      await access(localArchive);
      return { stream: createReadStream(localArchive), localArchive };
    } catch {
      // A cache miss falls through to the official source URL.
    }
  }
  const response = await fetchImpl(file.url);
  if (!response.ok || !response.body) {
    throw new Error(`Order-book archive download failed date=${file.date} HTTP ${response.status}.`);
  }
  return { stream: Readable.fromWeb(response.body), localArchive: null };
}

export async function retryArchiveOperation(operation, attempts, retryDelayMillis, sleep = defaultSleep) {
  let lastError = null;
  for (let attempt = 1; attempt <= attempts; attempt += 1) {
    try {
      return await operation(attempt);
    } catch (error) {
      lastError = error;
      if (attempt < attempts) await sleep(retryDelayMillis * 2 ** (attempt - 1));
    }
  }
  throw lastError;
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
    this.bids = new SortedBookSide(true);
    this.asks = new SortedBookSide(false);
    this.initialized = false;
    this.currentMinute = null;
    this.lastTimestamp = null;
    this.lastSourceTimestamp = null;
    this.firstTimestamp = null;
    this.eventCount = 0;
    this.bars = [];
    this.eventFlowBars = [];
    this.minuteAccumulator = null;
  }

  record(line) {
    const message = JSON.parse(line);
    const topic = message.topic;
    const topicMatch = new RegExp(`^orderbook\\.(\\d+)\\.${this.symbol}$`).exec(topic ?? "");
    if (!topicMatch || Number(topicMatch[1]) < this.depth) {
      throw new Error(`Unexpected archive topic: ${topic}`);
    }
    const sourceTimestamp = parseEpochMillis(message.ts, "ts");
    const timestamp = parseEpochMillis(message.cts ?? message.data?.cts ?? message.ts, "cts/ts");
    if (this.lastSourceTimestamp != null && sourceTimestamp < this.lastSourceTimestamp) {
      throw new Error(`Order-book archive source timestamps must be non-decreasing: ${sourceTimestamp} < ${this.lastSourceTimestamp}.`);
    }
    if (this.lastTimestamp != null && timestamp < this.lastTimestamp) {
      throw new Error(`Order-book archive matching-engine timestamps must be non-decreasing: ${timestamp} < ${this.lastTimestamp}.`);
    }
    const minute = Math.floor(timestamp / ONE_MINUTE_MILLIS) * ONE_MINUTE_MILLIS;
    if (this.currentMinute != null && minute > this.currentMinute) {
      this.finalizeMinute(this.currentMinute);
    }
    const mutations = this.apply(message);
    this.accumulateMinute(minute, message.type, mutations, this.snapshotMetrics());
    this.currentMinute = minute;
    this.lastTimestamp = timestamp;
    this.lastSourceTimestamp = sourceTimestamp;
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
      eventFlowBars: this.eventFlowBars,
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
    const bidMutations = this.bids.applyLevels(data.b, type === "delta" ? MICROSTRUCTURE_DEPTH : 0);
    const askMutations = this.asks.applyLevels(data.a, type === "delta" ? MICROSTRUCTURE_DEPTH : 0);
    if (this.bids.size === 0 || this.asks.size === 0) {
      throw new Error("Order-book archive produced an empty bid or ask side.");
    }
    return { bid: bidMutations, ask: askMutations };
  }

  finalizeMinute(openedAt) {
    if (toDate(openedAt) !== this.sourceDate) return;
    const accumulator = this.minuteAccumulator;
    if (accumulator == null || accumulator.openedAt !== openedAt || accumulator.messageCount === 0) {
      throw new Error(`Order-book archive ${this.sourceDate} has no event samples at ${instantString(openedAt)}.`);
    }
    this.bars.push({
      symbol: this.symbol,
      openedAt,
      sampleCount: accumulator.messageCount,
      meanBidNotional: accumulator.bidNotionalTotal / accumulator.messageCount,
      meanAskNotional: accumulator.askNotionalTotal / accumulator.messageCount,
      meanImbalance: accumulator.topDepthImbalanceTotal / accumulator.messageCount,
      meanSpreadBps: accumulator.spreadTotal / accumulator.messageCount,
      maxSpreadBps: accumulator.maxSpreadBps,
    });
    this.eventFlowBars.push(accumulator.toEventFlowBar(this.symbol));
    this.minuteAccumulator = null;
  }

  snapshotMetrics() {
    const bids = this.bids.entries(this.depth);
    const asks = this.asks.entries(this.depth);
    if (bids.length !== this.depth || asks.length !== this.depth) {
      throw new Error(`Order-book archive ${this.sourceDate} has fewer than ${this.depth} retained levels.`);
    }
    const top5Bids = bids.slice(0, MICROSTRUCTURE_DEPTH);
    const top5Asks = asks.slice(0, MICROSTRUCTURE_DEPTH);
    if (top5Bids.length !== MICROSTRUCTURE_DEPTH || top5Asks.length !== MICROSTRUCTURE_DEPTH) {
      throw new Error(`Order-book archive ${this.sourceDate} has fewer than ${MICROSTRUCTURE_DEPTH} microstructure levels.`);
    }
    const bidNotional = sumNotional(bids);
    const askNotional = sumNotional(asks);
    const top5BidNotional = sumNotional(top5Bids);
    const top5AskNotional = sumNotional(top5Asks);
    const bestBid = bids[0];
    const bestAsk = asks[0];
    const midpoint = (bestBid[0] + bestAsk[0]) / 2;
    if (!Number.isFinite(midpoint) || midpoint <= 0 || bestAsk[0] < bestBid[0]) {
      throw new Error("Order-book archive midpoint must be positive and uncrossed.");
    }
    const bestQuantityTotal = bestBid[1] + bestAsk[1];
    const microprice = bestQuantityTotal === 0
      ? midpoint
      : ((bestAsk[0] * bestBid[1]) + (bestBid[0] * bestAsk[1])) / bestQuantityTotal;
    return {
      bidNotional,
      askNotional,
      topDepthImbalance: imbalance(bidNotional, askNotional),
      top5Imbalance: imbalance(top5BidNotional, top5AskNotional),
      spreadBps: ((bestAsk[0] - bestBid[0]) / midpoint) * 10_000,
      micropriceEdgeBps: ((microprice - midpoint) / midpoint) * 10_000,
      midpoint,
    };
  }

  accumulateMinute(openedAt, messageType, mutations, sample) {
    if (this.minuteAccumulator == null) this.minuteAccumulator = new MinuteEventFlowAccumulator(openedAt);
    if (this.minuteAccumulator.openedAt !== openedAt) {
      throw new Error("Order-book minute accumulator advanced without finalizing its prior minute.");
    }
    this.minuteAccumulator.add(messageType, mutations, sample);
  }
}

class SortedBookSide {
  constructor(descending) {
    this.descending = descending;
    this.prices = [];
    this.quantities = new Map();
  }

  get size() {
    return this.prices.length;
  }

  clear() {
    this.prices.length = 0;
    this.quantities.clear();
  }

  entries(depth) {
    return this.prices.slice(0, depth).map((price) => [price, this.quantities.get(price)]);
  }

  applyLevels(rows, trackedDepth) {
    if (!Array.isArray(rows) || rows.length === 0) return emptyMutations();
    const parsed = rows.map((row) => parseLevel(row));
    if (new Set(parsed.map((level) => level.price)).size !== parsed.length) {
      throw new Error("Order-book archive message contains a duplicate price level.");
    }
    const before = new Set(this.prices.slice(0, trackedDepth));
    const changes = parsed.map(({ price, quantity }) => ({
      price,
      oldQuantity: this.quantities.get(price) ?? 0,
      newQuantity: quantity,
    }));
    for (const change of changes) this.set(change.price, change.newQuantity);
    const after = new Set(this.prices.slice(0, trackedDepth));
    let addedNotional = 0;
    let removedNotional = 0;
    let updateCount = 0;
    for (const change of changes) {
      const difference = change.newQuantity - change.oldQuantity;
      if (difference === 0) continue;
      if (!before.has(change.price) && !after.has(change.price)) continue;
      updateCount += 1;
      if (difference > 0) addedNotional += change.price * difference;
      else removedNotional += change.price * -difference;
    }
    return { addedNotional, removedNotional, updateCount };
  }

  set(price, quantity) {
    const existing = this.quantities.has(price);
    if (quantity === 0) {
      if (!existing) return;
      const index = this.findIndex(price);
      this.prices.splice(index, 1);
      this.quantities.delete(price);
      return;
    }
    this.quantities.set(price, quantity);
    if (existing) return;
    this.prices.splice(this.insertionIndex(price), 0, price);
  }

  findIndex(price) {
    const index = this.insertionIndex(price);
    if (this.prices[index] !== price) throw new Error(`Order-book price ${price} is missing from its sorted index.`);
    return index;
  }

  insertionIndex(price) {
    let low = 0;
    let high = this.prices.length;
    while (low < high) {
      const middle = Math.floor((low + high) / 2);
      const before = this.descending ? price > this.prices[middle] : price < this.prices[middle];
      if (before) high = middle;
      else low = middle + 1;
    }
    if (low > 0 && this.prices[low - 1] === price) return low - 1;
    return low;
  }
}

class MinuteEventFlowAccumulator {
  constructor(openedAt) {
    this.openedAt = openedAt;
    this.messageCount = 0;
    this.snapshotCount = 0;
    this.bidNotionalTotal = 0;
    this.askNotionalTotal = 0;
    this.topDepthImbalanceTotal = 0;
    this.top5ImbalanceTotal = 0;
    this.startTop5Imbalance = null;
    this.endTop5Imbalance = null;
    this.minTop5Imbalance = Infinity;
    this.maxTop5Imbalance = -Infinity;
    this.spreadTotal = 0;
    this.maxSpreadBps = 0;
    this.micropriceEdgeTotal = 0;
    this.bidAddedTop5Notional = 0;
    this.bidRemovedTop5Notional = 0;
    this.askAddedTop5Notional = 0;
    this.askRemovedTop5Notional = 0;
    this.bidUpdateCount = 0;
    this.askUpdateCount = 0;
    this.openMidPrice = null;
    this.highMidPrice = -Infinity;
    this.lowMidPrice = Infinity;
    this.closeMidPrice = null;
  }

  add(messageType, mutations, sample) {
    this.messageCount += 1;
    if (messageType === "snapshot") this.snapshotCount += 1;
    this.bidNotionalTotal += sample.bidNotional;
    this.askNotionalTotal += sample.askNotional;
    this.topDepthImbalanceTotal += sample.topDepthImbalance;
    this.top5ImbalanceTotal += sample.top5Imbalance;
    this.startTop5Imbalance ??= sample.top5Imbalance;
    this.endTop5Imbalance = sample.top5Imbalance;
    this.minTop5Imbalance = Math.min(this.minTop5Imbalance, sample.top5Imbalance);
    this.maxTop5Imbalance = Math.max(this.maxTop5Imbalance, sample.top5Imbalance);
    this.spreadTotal += sample.spreadBps;
    this.maxSpreadBps = Math.max(this.maxSpreadBps, sample.spreadBps);
    this.micropriceEdgeTotal += sample.micropriceEdgeBps;
    this.bidAddedTop5Notional += mutations.bid.addedNotional;
    this.bidRemovedTop5Notional += mutations.bid.removedNotional;
    this.askAddedTop5Notional += mutations.ask.addedNotional;
    this.askRemovedTop5Notional += mutations.ask.removedNotional;
    this.bidUpdateCount += mutations.bid.updateCount;
    this.askUpdateCount += mutations.ask.updateCount;
    this.openMidPrice ??= sample.midpoint;
    this.highMidPrice = Math.max(this.highMidPrice, sample.midpoint);
    this.lowMidPrice = Math.min(this.lowMidPrice, sample.midpoint);
    this.closeMidPrice = sample.midpoint;
  }

  toEventFlowBar(symbol) {
    return {
      symbol,
      openedAt: this.openedAt,
      messageCount: this.messageCount,
      snapshotCount: this.snapshotCount,
      meanTop5Imbalance: this.top5ImbalanceTotal / this.messageCount,
      meanTop50Imbalance: this.topDepthImbalanceTotal / this.messageCount,
      startTop5Imbalance: this.startTop5Imbalance,
      endTop5Imbalance: this.endTop5Imbalance,
      minTop5Imbalance: this.minTop5Imbalance,
      maxTop5Imbalance: this.maxTop5Imbalance,
      meanSpreadBps: this.spreadTotal / this.messageCount,
      maxSpreadBps: this.maxSpreadBps,
      meanMicropriceEdgeBps: this.micropriceEdgeTotal / this.messageCount,
      bidAddedTop5Notional: this.bidAddedTop5Notional,
      bidRemovedTop5Notional: this.bidRemovedTop5Notional,
      askAddedTop5Notional: this.askAddedTop5Notional,
      askRemovedTop5Notional: this.askRemovedTop5Notional,
      bidUpdateCount: this.bidUpdateCount,
      askUpdateCount: this.askUpdateCount,
      openMidPrice: this.openMidPrice,
      highMidPrice: this.highMidPrice,
      lowMidPrice: this.lowMidPrice,
      closeMidPrice: this.closeMidPrice,
    };
  }
}

function parseLevel(row) {
  if (!Array.isArray(row) || row.length < 2) throw new Error("Order-book archive level must contain price and size.");
  const price = Number(row[0]);
  const quantity = Number(row[1]);
  if (!Number.isFinite(price) || price <= 0 || !Number.isFinite(quantity) || quantity < 0) {
    throw new Error(`Invalid order-book archive level price=${row[0]} quantity=${row[1]}.`);
  }
  return { price, quantity };
}

function emptyMutations() {
  return { addedNotional: 0, removedNotional: 0, updateCount: 0 };
}

function imbalance(bidNotional, askNotional) {
  const total = bidNotional + askNotional;
  return total === 0 ? 0 : (bidNotional - askNotional) / total;
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
    ON CONFLICT(provider, dataset, symbol, source_date) DO UPDATE SET
      source_url=excluded.source_url,
      archive_filename=excluded.archive_filename,
      archive_size_bytes=excluded.archive_size_bytes,
      event_count=excluded.event_count,
      first_event_at=excluded.first_event_at,
      last_event_at=excluded.last_event_at,
      minute_bar_count=excluded.minute_bar_count,
      imported_at=excluded.imported_at,
      importer_version=excluded.importer_version
  `);
  const insertEventFlowBar = db.prepare(`
    INSERT INTO orderBookEventFlowBars(
      symbol, opened_at, message_count, snapshot_count,
      mean_top5_imbalance, mean_top50_imbalance, start_top5_imbalance, end_top5_imbalance,
      min_top5_imbalance, max_top5_imbalance, mean_spread_bps, max_spread_bps,
      mean_microprice_edge_bps, bid_added_top5_notional, bid_removed_top5_notional,
      ask_added_top5_notional, ask_removed_top5_notional, bid_update_count, ask_update_count,
      open_mid_price, high_mid_price, low_mid_price, close_mid_price
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(symbol, opened_at) DO UPDATE SET
      message_count=excluded.message_count,
      snapshot_count=excluded.snapshot_count,
      mean_top5_imbalance=excluded.mean_top5_imbalance,
      mean_top50_imbalance=excluded.mean_top50_imbalance,
      start_top5_imbalance=excluded.start_top5_imbalance,
      end_top5_imbalance=excluded.end_top5_imbalance,
      min_top5_imbalance=excluded.min_top5_imbalance,
      max_top5_imbalance=excluded.max_top5_imbalance,
      mean_spread_bps=excluded.mean_spread_bps,
      max_spread_bps=excluded.max_spread_bps,
      mean_microprice_edge_bps=excluded.mean_microprice_edge_bps,
      bid_added_top5_notional=excluded.bid_added_top5_notional,
      bid_removed_top5_notional=excluded.bid_removed_top5_notional,
      ask_added_top5_notional=excluded.ask_added_top5_notional,
      ask_removed_top5_notional=excluded.ask_removed_top5_notional,
      bid_update_count=excluded.bid_update_count,
      ask_update_count=excluded.ask_update_count,
      open_mid_price=excluded.open_mid_price,
      high_mid_price=excluded.high_mid_price,
      low_mid_price=excluded.low_mid_price,
      close_mid_price=excluded.close_mid_price
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
    for (const bar of result.eventFlowBars) {
      insertEventFlowBar.run(
        bar.symbol,
        instantString(bar.openedAt),
        bar.messageCount,
        bar.snapshotCount,
        decimalString(bar.meanTop5Imbalance),
        decimalString(bar.meanTop50Imbalance),
        decimalString(bar.startTop5Imbalance),
        decimalString(bar.endTop5Imbalance),
        decimalString(bar.minTop5Imbalance),
        decimalString(bar.maxTop5Imbalance),
        decimalString(bar.meanSpreadBps),
        decimalString(bar.maxSpreadBps),
        decimalString(bar.meanMicropriceEdgeBps),
        decimalString(bar.bidAddedTop5Notional),
        decimalString(bar.bidRemovedTop5Notional),
        decimalString(bar.askAddedTop5Notional),
        decimalString(bar.askRemovedTop5Notional),
        bar.bidUpdateCount,
        bar.askUpdateCount,
        decimalString(bar.openMidPrice),
        decimalString(bar.highMidPrice),
        decimalString(bar.lowMidPrice),
        decimalString(bar.closeMidPrice),
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
  const next = addUtcDays(date, 1);
  const row = db.prepare(`
    SELECT
      i.importer_version,
      i.minute_bar_count,
      (SELECT count(*) FROM orderBookEventFlowBars e
       WHERE e.symbol=i.symbol AND e.opened_at>=? AND e.opened_at<?) AS event_flow_count
    FROM historicalOrderBookImports i
    WHERE i.provider='bybit' AND i.dataset='orderbook' AND i.symbol=? AND i.source_date=?
    LIMIT 1
  `).get(`${date}T00:00:00Z`, `${next}T00:00:00Z`, symbol, date);
  return row?.importer_version === IMPORTER_VERSION &&
    Number(row.minute_bar_count) === MINUTES_PER_DAY &&
    Number(row.event_flow_count) === MINUTES_PER_DAY;
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

function waitForProcess(process, stderr, name) {
  return new Promise((resolve, reject) => {
    process.once("error", reject);
    process.once("close", (code) => {
      if (code === 0) resolve();
      else reject(new Error(`${name} exited with code=${code}: ${Buffer.concat(stderr).toString().trim()}`));
    });
  });
}

function waitForReadableEnd(stream) {
  return new Promise((resolve, reject) => {
    stream.once("error", reject);
    stream.once("end", resolve);
    stream.once("close", resolve);
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

function defaultSleep(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

const invokedPath = process.argv[1] ? pathToFileURL(resolve(process.argv[1])).href : null;
if (invokedPath === import.meta.url) {
  const options = parseArgs(process.argv.slice(2));
  console.log(`Bybit order-book backfill db=${options.db} symbol=${options.symbol} range=${options.start}..${options.end} depth=${options.orderBookDepth}`);
  const result = await backfill(options);
  console.log(JSON.stringify(result));
}
