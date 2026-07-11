#!/usr/bin/env node

import { createHash } from "node:crypto";
import { DatabaseSync } from "node:sqlite";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { createInterface } from "node:readline";
import { Readable } from "node:stream";
import { ensureSchema } from "./bybit-orderbook-backfill.mjs";

const DEFAULT_MACHINE_URL = "http://127.0.0.1:8000";
const DEFAULT_ORDER_BOOK_DEPTH = 50;
const DATASET = "machine-normalized-book-snapshot-1m-v1";
const IMPORTER_VERSION = "tardis-machine-orderbook-backfill-v1";
const MINUTES_PER_DAY = 24 * 60;
const ONE_MINUTE_MILLIS = 60_000;
const SYMBOL_PATTERN = /^[A-Z0-9]{2,30}$/;

export function parseArgs(argv) {
  const values = new Map();
  for (const argument of argv) {
    if (!argument.startsWith("--") || !argument.includes("=")) {
      throw new Error(`Invalid argument: ${argument}. Use --name=value.`);
    }
    const [name, ...rest] = argument.slice(2).split("=");
    if (!["db", "symbol", "start", "end", "machine-url", "orderbook-depth", "force"].includes(name)) {
      throw new Error(`Unsupported argument: --${name}.`);
    }
    values.set(name, rest.join("="));
  }
  const options = {
    db: resolve(values.get("db") ?? "build/runtime-test/tardis-machine-orderbook.sqlite"),
    symbol: (values.get("symbol") ?? "BTCUSDT").trim().toUpperCase(),
    start: values.get("start") ?? null,
    end: values.get("end") ?? null,
    machineUrl: normalizeMachineUrl(values.get("machine-url") ?? DEFAULT_MACHINE_URL),
    orderBookDepth: Number(values.get("orderbook-depth") ?? DEFAULT_ORDER_BOOK_DEPTH),
    force: values.get("force") === "true",
  };
  if (!SYMBOL_PATTERN.test(options.symbol)) throw new Error("Symbol must contain only uppercase letters and numbers.");
  if (!isDate(options.start) || !isDate(options.end) || options.start > options.end) {
    throw new Error("Start/end must be valid YYYY-MM-DD values with start <= end.");
  }
  if (!Number.isInteger(options.orderBookDepth) || options.orderBookDepth < 1 || options.orderBookDepth > 50) {
    throw new Error("orderbook-depth must be an integer between 1 and 50.");
  }
  return options;
}

export function normalizeMachineUrl(value) {
  let url;
  try {
    url = new URL(value);
  } catch {
    throw new Error("machine-url must be a valid local HTTP(S) URL.");
  }
  if (!new Set(["http:", "https:"]).has(url.protocol) || !new Set(["127.0.0.1", "localhost", "[::1]", "::1"]).has(url.hostname)) {
    throw new Error("machine-url must target localhost so the Tardis credential cannot leave the research runner.");
  }
  if (url.username || url.password || (url.pathname !== "/" && url.pathname !== "")) {
    throw new Error("machine-url must not include credentials or a path.");
  }
  url.pathname = "";
  url.search = "";
  url.hash = "";
  return url.toString().replace(/\/$/, "");
}

export function buildReplayUrl(options, date) {
  if (!isDate(date)) throw new Error("date must be a valid YYYY-MM-DD value.");
  const url = new URL("/replay-normalized", `${options.machineUrl}/`);
  url.searchParams.set(
    "options",
    JSON.stringify({
      exchange: "bybit",
      from: date,
      to: addUtcDays(date, 1),
      symbols: [options.symbol],
      withDisconnectMessages: true,
      dataTypes: [`book_snapshot_${options.orderBookDepth}_1m`],
    }),
  );
  return url;
}

export async function backfill(options, dependencies = {}) {
  const fetchImpl = dependencies.fetchImpl ?? fetch;
  const log = dependencies.log ?? console.log;
  const db = dependencies.db ?? new DatabaseSync(options.db);
  const ownsDatabase = dependencies.db == null;
  ensureSchema(db);
  try {
    let importedDays = 0;
    let skippedDays = 0;
    const requestedDates = [...datesBetween(options.start, options.end)];
    for (const date of requestedDates) {
      assertNoForeignSourceDay(db, options, date);
      if (!options.force && hasCompleteImport(db, options, date)) {
        skippedDays += 1;
        continue;
      }
      const result = await importMachineDay(options, date, fetchImpl);
      persistImportedDay(db, options, result);
      importedDays += 1;
      log(
        `tardis machine order-book imported date=${date} snapshots=${result.eventCount} ` +
          `carriedForwardMinutes=${result.carriedForwardMinuteBars} sha256=${result.archiveSha256}`,
      );
    }
    return { importedDays, skippedDays, requestedDays: requestedDates.length };
  } finally {
    if (ownsDatabase) db.close();
  }
}

export async function importMachineDay(options, date, fetchImpl = fetch) {
  const sourceUrl = buildReplayUrl(options, date);
  const response = await fetchImpl(sourceUrl);
  if (!response.ok || !response.body) {
    throw new Error(`Tardis Machine replay failed date=${date} HTTP ${response.status}.`);
  }
  const stream = Readable.fromWeb(response.body);
  const archiveHash = createHash("sha256");
  let archiveSizeBytes = 0;
  stream.on("data", (chunk) => {
    archiveHash.update(chunk);
    archiveSizeBytes += chunk.length;
  });
  const aggregate = await aggregateNormalizedSnapshots(stream, {
    date,
    symbol: options.symbol,
    depth: options.orderBookDepth,
  });
  return {
    ...aggregate,
    sourceUrl: sourceUrl.toString(),
    archiveSizeBytes,
    archiveSha256: archiveHash.digest("hex"),
  };
}

export async function aggregateNormalizedSnapshots(stream, { date, symbol, depth, expectedMinutes = MINUTES_PER_DAY }) {
  if (!isDate(date)) throw new Error("date must be a valid YYYY-MM-DD value.");
  if (!SYMBOL_PATTERN.test(symbol) || !Number.isInteger(depth) || depth < 1 || depth > 50) {
    throw new Error("Normalized snapshot aggregation requires a valid symbol and depth between 1 and 50.");
  }
  if (!Number.isInteger(expectedMinutes) || expectedMinutes < 1 || expectedMinutes > MINUTES_PER_DAY) {
    throw new Error(`expectedMinutes must be an integer between 1 and ${MINUTES_PER_DAY}.`);
  }

  const dayStart = Date.parse(`${date}T00:00:00Z`);
  const dayEnd = dayStart + expectedMinutes * ONE_MINUTE_MILLIS;
  const snapshotsByMinute = new Map();
  const reader = createInterface({ input: stream, crlfDelay: Infinity });
  let previousLocalTimestamp = null;
  let firstEventAt = null;
  let lastEventAt = null;
  let eventCount = 0;

  for await (const line of reader) {
    if (!line) continue;
    const message = JSON.parse(line);
    if (message?.type === "disconnect") {
      throw new Error(`Tardis Machine reported a source disconnect for ${date}; the day is rejected.`);
    }
    if (message?.type === "error") {
      throw new Error(`Tardis Machine reported an upstream error for ${date}: ${String(message.details ?? "unknown error")}.`);
    }
    if (message?.type !== "book_snapshot") {
      throw new Error(`Unexpected Tardis Machine message type for ${date}: ${String(message?.type)}.`);
    }
    const snapshot = validateSnapshot(message, { symbol, depth });
    const localTimestamp = parseTimestamp(snapshot.localTimestamp, "localTimestamp");
    if (previousLocalTimestamp != null && localTimestamp < previousLocalTimestamp) {
      throw new Error(`Tardis Machine local timestamps must be nondecreasing for ${date}.`);
    }
    if (localTimestamp < dayStart || localTimestamp >= dayEnd) {
      throw new Error(`Tardis Machine snapshot is outside the requested UTC day ${date}.`);
    }
    previousLocalTimestamp = localTimestamp;
    const minute = Math.floor(localTimestamp / ONE_MINUTE_MILLIS) * ONE_MINUTE_MILLIS;
    snapshotsByMinute.set(minute, snapshot);
    firstEventAt ??= localTimestamp;
    lastEventAt = localTimestamp;
    eventCount += 1;
  }

  if (eventCount === 0 || firstEventAt == null || lastEventAt == null) {
    throw new Error(`Tardis Machine returned no order-book snapshots for ${date}.`);
  }
  if (!snapshotsByMinute.has(dayStart)) {
    throw new Error(`Tardis Machine day ${date} has no initial-minute order-book snapshot.`);
  }
  if (!snapshotsByMinute.has(dayEnd - ONE_MINUTE_MILLIS)) {
    throw new Error(`Tardis Machine day ${date} has no final-minute order-book snapshot.`);
  }

  const bars = [];
  let currentSnapshot = null;
  let carriedForwardMinuteBars = 0;
  for (let offset = 0; offset < expectedMinutes; offset += 1) {
    const openedAt = dayStart + offset * ONE_MINUTE_MILLIS;
    const snapshot = snapshotsByMinute.get(openedAt);
    if (snapshot != null) {
      currentSnapshot = snapshot;
    } else {
      carriedForwardMinuteBars += 1;
    }
    if (currentSnapshot == null) throw new Error(`Tardis Machine day ${date} has no causal order-book state at minute ${offset}.`);
    bars.push(snapshotToBar(currentSnapshot, symbol, depth, openedAt));
  }
  assertCompleteDay(bars, date, expectedMinutes);
  return { bars, eventCount, firstEventAt, lastEventAt, carriedForwardMinuteBars };
}

function validateSnapshot(snapshot, { symbol, depth }) {
  if (
    snapshot.exchange !== "bybit" ||
    snapshot.symbol !== symbol ||
    snapshot.name !== `book_snapshot_${depth}_1m` ||
    Number(snapshot.depth) !== depth ||
    Number(snapshot.interval) !== ONE_MINUTE_MILLIS ||
    !Array.isArray(snapshot.bids) ||
    !Array.isArray(snapshot.asks)
  ) {
    throw new Error("Tardis Machine order-book snapshot has an unexpected contract.");
  }
  parseTimestamp(snapshot.timestamp, "timestamp");
  if (typeof snapshot.localTimestamp !== "string") throw new Error("Tardis Machine snapshot localTimestamp is missing.");
  return snapshot;
}

function snapshotToBar(snapshot, symbol, depth, openedAt) {
  const bids = topLevels(snapshot.bids, depth, true);
  const asks = topLevels(snapshot.asks, depth, false);
  if (bids.length !== depth || asks.length !== depth) {
    throw new Error(`Tardis Machine snapshot has fewer than ${depth} bid or ask levels.`);
  }
  const bidNotional = sumNotional(bids);
  const askNotional = sumNotional(asks);
  const midpoint = (bids[0].price + asks[0].price) / 2;
  if (!Number.isFinite(midpoint) || midpoint <= 0) throw new Error("Tardis Machine snapshot midpoint must be positive.");
  if (asks[0].price <= bids[0].price) throw new Error("Tardis Machine snapshot has a crossed or locked order book.");
  const spreadBps = ((asks[0].price - bids[0].price) / midpoint) * 10_000;
  const totalNotional = bidNotional + askNotional;
  return {
    symbol,
    openedAt,
    sampleCount: 1,
    meanBidNotional: bidNotional,
    meanAskNotional: askNotional,
    meanImbalance: totalNotional === 0 ? 0 : (bidNotional - askNotional) / totalNotional,
    meanSpreadBps: spreadBps,
    maxSpreadBps: spreadBps,
  };
}

function topLevels(levels, depth, descending) {
  const prices = new Set();
  const normalized = levels.map((level) => {
    const price = Number(level?.price);
    const amount = Number(level?.amount);
    if (!Number.isFinite(price) || price <= 0 || !Number.isFinite(amount) || amount <= 0) {
      throw new Error("Tardis Machine snapshot level must have positive price and amount.");
    }
    if (prices.has(price)) throw new Error("Tardis Machine snapshot side contains duplicate price levels.");
    prices.add(price);
    return { price, amount };
  });
  normalized.sort((left, right) => (descending ? right.price - left.price : left.price - right.price));
  return normalized.slice(0, depth);
}

function sumNotional(levels) {
  return levels.reduce((sum, level) => sum + level.price * level.amount, 0);
}

export function assertCompleteDay(bars, date, expectedMinutes = MINUTES_PER_DAY) {
  const dayStart = Date.parse(`${date}T00:00:00Z`);
  if (bars.length !== expectedMinutes) {
    throw new Error(`Tardis Machine day ${date} is incomplete: expected ${expectedMinutes} minute bars, received ${bars.length}.`);
  }
  for (let offset = 0; offset < expectedMinutes; offset += 1) {
    if (bars[offset].openedAt !== dayStart + offset * ONE_MINUTE_MILLIS) {
      throw new Error(`Tardis Machine day ${date} is not continuous at minute offset=${offset}.`);
    }
  }
}

function persistImportedDay(db, options, result) {
  const date = new Date(result.bars[0].openedAt).toISOString().slice(0, 10);
  const existing = db.prepare(`
    SELECT archive_sha256 FROM historicalOrderBookImports
    WHERE provider='tardis' AND dataset=? AND symbol=? AND source_date=?
    LIMIT 1
  `).get(DATASET, options.symbol, date);
  if (existing?.archive_sha256 != null && existing.archive_sha256 !== result.archiveSha256) {
    throw new Error(`Tardis Machine replay hash changed for ${date}; refusing to replace recorded provenance.`);
  }
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
      "tardis",
      DATASET,
      options.symbol,
      date,
      result.sourceUrl,
      `tardis-machine-bybit-${options.symbol}-${date}.ndjson`,
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

function assertNoForeignSourceDay(db, options, date) {
  const foreignManifest = db.prepare(`
    SELECT provider, dataset FROM historicalOrderBookImports
    WHERE symbol=? AND source_date=? AND (provider != 'tardis' OR dataset != ?)
    LIMIT 1
  `).get(options.symbol, date, DATASET);
  if (foreignManifest != null) {
    throw new Error(`Refusing to overwrite ${foreignManifest.provider}/${foreignManifest.dataset} order-book data for ${date}; use a separate research database.`);
  }
  const currentManifest = db.prepare(`
    SELECT 1 FROM historicalOrderBookImports
    WHERE provider='tardis' AND dataset=? AND symbol=? AND source_date=?
    LIMIT 1
  `).get(DATASET, options.symbol, date);
  const existingBars = Number(
    db.prepare(`
      SELECT count(*) AS count FROM orderBookImbalanceBars
      WHERE symbol=? AND opened_at>=? AND opened_at<?
    `).get(options.symbol, `${date}T00:00:00Z`, `${addUtcDays(date, 1)}T00:00:00Z`).count,
  );
  if (currentManifest == null && existingBars > 0) {
    throw new Error(`Refusing to overwrite unprovenanced order-book data for ${date}; use a separate research database.`);
  }
}

function hasCompleteImport(db, options, date) {
  const manifest = db.prepare(`
    SELECT minute_bar_count, archive_sha256 FROM historicalOrderBookImports
    WHERE provider='tardis' AND dataset=? AND symbol=? AND source_date=?
    LIMIT 1
  `).get(DATASET, options.symbol, date);
  if (manifest == null || Number(manifest.minute_bar_count) !== MINUTES_PER_DAY || !isSha256(manifest.archive_sha256)) return false;
  const row =
    db.prepare(`
      WITH ordered AS (
        SELECT opened_at, strftime('%s', opened_at) AS opened_epoch,
          LAG(strftime('%s', opened_at)) OVER (ORDER BY opened_at) AS previous_epoch
        FROM orderBookImbalanceBars
        WHERE symbol=? AND opened_at>=? AND opened_at<?
      )
      SELECT
        count(*) AS count,
        min(opened_at) AS first_opened_at,
        max(opened_at) AS last_opened_at,
        coalesce(sum(CASE WHEN previous_epoch IS NOT NULL AND opened_epoch - previous_epoch != 60 THEN 1 ELSE 0 END), 0) AS gap_count
      FROM ordered
    `).get(options.symbol, `${date}T00:00:00Z`, `${addUtcDays(date, 1)}T00:00:00Z`);
  return (
    Number(row.count) === MINUTES_PER_DAY &&
    row.first_opened_at === `${date}T00:00:00Z` &&
    row.last_opened_at === `${date}T23:59:00Z` &&
    Number(row.gap_count) === 0
  );
}

function parseTimestamp(value, field) {
  const timestamp = Date.parse(value);
  if (!Number.isFinite(timestamp)) throw new Error(`Tardis Machine snapshot ${field} must be an ISO-8601 timestamp.`);
  return timestamp;
}

function decimalString(value) {
  if (!Number.isFinite(value)) throw new Error("Cannot persist a non-finite order-book value.");
  return Number(value.toPrecision(15)).toString();
}

function instantString(milliseconds) {
  return new Date(milliseconds).toISOString().replace(".000Z", "Z");
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

function* datesBetween(start, end) {
  let date = start;
  while (date <= end) {
    yield date;
    date = addUtcDays(date, 1);
  }
}

function addUtcDays(date, days) {
  const value = new Date(`${date}T00:00:00Z`);
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
}

function isDate(value) {
  return typeof value === "string" && /^\d{4}-\d{2}-\d{2}$/.test(value) &&
    new Date(`${value}T00:00:00Z`).toISOString().slice(0, 10) === value;
}

function isSha256(value) {
  return typeof value === "string" && /^[a-f0-9]{64}$/.test(value);
}

const invokedPath = process.argv[1] ? pathToFileURL(resolve(process.argv[1])).href : null;
if (invokedPath === import.meta.url) {
  const options = parseArgs(process.argv.slice(2));
  console.log(`Tardis Machine order-book backfill db=${options.db} symbol=${options.symbol} range=${options.start}..${options.end}`);
  console.log(JSON.stringify(await backfill(options)));
}
