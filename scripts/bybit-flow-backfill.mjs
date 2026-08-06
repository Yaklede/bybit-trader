#!/usr/bin/env node

import { createInterface } from "node:readline";
import { createHash } from "node:crypto";
import { Readable, Transform } from "node:stream";
import { createGunzip } from "node:zlib";
import { DatabaseSync } from "node:sqlite";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

const DEFAULT_START = "2020-03-25";
const DEFAULT_BASE_URL = "https://api.bybit.com";
const DEFAULT_ARCHIVE_URL = "https://public.bybit.com/trading";
const SYMBOL_PATTERN = /^[A-Z0-9]{2,30}$/;
const TRADE_IMPORTER_VERSION = "bybit-public-trades-v2-event-flow";
const MINUTES_PER_DAY = 1_440;

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
    datasets: new Set((values.get("datasets") ?? "trades,oi,account-ratio,premium,funding,coverage").split(",")),
    force: values.get("force") === "true",
    apiBaseUrl: values.get("api-base-url") ?? DEFAULT_BASE_URL,
    archiveBaseUrl: values.get("archive-base-url") ?? DEFAULT_ARCHIVE_URL,
    requestDelayMs: Number(values.get("request-delay-ms") ?? 125),
  };
  if (!SYMBOL_PATTERN.test(options.symbol)) throw new Error("Symbol must contain only uppercase letters and numbers.");
  if (!isDate(options.start) || !isDate(options.end) || options.start > options.end) {
    throw new Error("Start/end must be valid YYYY-MM-DD values with start <= end.");
  }
  const allowed = new Set(["trades", "oi", "account-ratio", "premium", "funding", "coverage"]);
  for (const dataset of options.datasets) {
    if (!allowed.has(dataset)) throw new Error(`Unsupported dataset: ${dataset}`);
  }
  if (!Number.isInteger(options.requestDelayMs) || options.requestDelayMs < 0) {
    throw new Error("request-delay-ms must be a non-negative integer.");
  }
  return options;
}

export function minuteEpochMillis(timestamp) {
  const numeric = Number(timestamp);
  if (!Number.isFinite(numeric) || numeric < 0) throw new Error(`Invalid trade timestamp: ${timestamp}`);
  const milliseconds = numeric >= 10_000_000_000 ? numeric : numeric * 1_000;
  return Math.floor(milliseconds / 60_000) * 60_000;
}

export function applyTrade(fields, columns, bars) {
  const minute = minuteEpochMillis(fields[columns.timestamp]);
  const side = fields[columns.side];
  const size = Number(fields[columns.size]);
  const price = Number(fields[columns.price]);
  if (!Number.isFinite(size) || size < 0 || !Number.isFinite(price) || price < 0) {
    throw new Error("Trade size and price must be non-negative numbers.");
  }
  const bar = bars.get(minute) ?? {
    buyBase: 0,
    buyNotional: 0,
    sellBase: 0,
    sellNotional: 0,
    buyCount: 0,
    sellCount: 0,
    largestBuyNotional: 0,
    largestSellNotional: 0,
    openPrice: price,
    highPrice: price,
    lowPrice: price,
    closePrice: price,
  };
  const notional = size * price;
  if (side === "Buy") {
    bar.buyBase += size;
    bar.buyNotional += notional;
    bar.buyCount += 1;
    bar.largestBuyNotional = Math.max(bar.largestBuyNotional, notional);
  } else if (side === "Sell") {
    bar.sellBase += size;
    bar.sellNotional += notional;
    bar.sellCount += 1;
    bar.largestSellNotional = Math.max(bar.largestSellNotional, notional);
  } else {
    throw new Error(`Unsupported taker side: ${side}`);
  }
  bar.highPrice = Math.max(bar.highPrice, price);
  bar.lowPrice = Math.min(bar.lowPrice, price);
  bar.closePrice = price;
  bars.set(minute, bar);
}

export function ensureSchema(db) {
  db.exec(`
    PRAGMA journal_mode=WAL;
    PRAGMA synchronous=NORMAL;
    PRAGMA busy_timeout=30000;
    CREATE TABLE IF NOT EXISTS takerFlowBars (
      id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
      symbol TEXT NOT NULL,
      opened_at TEXT NOT NULL,
      taker_buy_base TEXT NOT NULL,
      taker_buy_notional TEXT NOT NULL,
      taker_sell_base TEXT NOT NULL,
      taker_sell_notional TEXT NOT NULL,
      buy_trade_count INTEGER NOT NULL,
      sell_trade_count INTEGER NOT NULL
    );
    CREATE UNIQUE INDEX IF NOT EXISTS takerFlowBars_symbol_openedAt_idx
      ON takerFlowBars(symbol, opened_at);
    CREATE TABLE IF NOT EXISTS takerEventFlowBars (
      id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
      symbol TEXT NOT NULL,
      opened_at TEXT NOT NULL,
      trade_count INTEGER NOT NULL,
      buy_vwap TEXT NOT NULL,
      sell_vwap TEXT NOT NULL,
      largest_buy_notional TEXT NOT NULL,
      largest_sell_notional TEXT NOT NULL,
      open_trade_price TEXT NOT NULL,
      high_trade_price TEXT NOT NULL,
      low_trade_price TEXT NOT NULL,
      close_trade_price TEXT NOT NULL
    );
    CREATE UNIQUE INDEX IF NOT EXISTS takerEventFlowBars_symbol_openedAt_idx
      ON takerEventFlowBars(symbol, opened_at);
    CREATE TABLE IF NOT EXISTS historicalTradeImports (
      id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
      provider TEXT NOT NULL,
      dataset TEXT NOT NULL,
      symbol TEXT NOT NULL,
      source_date TEXT NOT NULL,
      source_url TEXT NOT NULL,
      archive_size_bytes INTEGER NOT NULL,
      archive_sha256 TEXT NOT NULL,
      event_count INTEGER NOT NULL,
      first_event_at TEXT NOT NULL,
      last_event_at TEXT NOT NULL,
      minute_bar_count INTEGER NOT NULL,
      imported_at TEXT NOT NULL,
      importer_version TEXT NOT NULL
    );
    CREATE UNIQUE INDEX IF NOT EXISTS historicalTradeImports_source_idx
      ON historicalTradeImports(provider, dataset, symbol, source_date);
    CREATE TABLE IF NOT EXISTS openInterestSnapshots (
      id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
      symbol TEXT NOT NULL,
      interval TEXT NOT NULL,
      timestamp TEXT NOT NULL,
      open_interest TEXT NOT NULL
    );
    CREATE UNIQUE INDEX IF NOT EXISTS openInterestSnapshots_symbol_interval_timestamp_idx
      ON openInterestSnapshots(symbol, interval, timestamp);
    CREATE TABLE IF NOT EXISTS accountRatioSnapshots (
      id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
      symbol TEXT NOT NULL,
      period TEXT NOT NULL,
      timestamp TEXT NOT NULL,
      buy_ratio TEXT NOT NULL,
      sell_ratio TEXT NOT NULL
    );
    CREATE UNIQUE INDEX IF NOT EXISTS accountRatioSnapshots_symbol_period_timestamp_idx
      ON accountRatioSnapshots(symbol, period, timestamp);
    CREATE TABLE IF NOT EXISTS premiumIndexBars (
      id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
      symbol TEXT NOT NULL,
      timeframe TEXT NOT NULL,
      opened_at TEXT NOT NULL,
      open TEXT NOT NULL,
      high TEXT NOT NULL,
      low TEXT NOT NULL,
      close TEXT NOT NULL
    );
    CREATE UNIQUE INDEX IF NOT EXISTS premiumIndexBars_symbol_timeframe_openedAt_idx
      ON premiumIndexBars(symbol, timeframe, opened_at);
    CREATE TABLE IF NOT EXISTS fundingRates (
      id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
      symbol TEXT NOT NULL,
      timestamp TEXT NOT NULL,
      funding_rate TEXT NOT NULL
    );
    CREATE UNIQUE INDEX IF NOT EXISTS fundingRates_symbol_timestamp_idx
      ON fundingRates(symbol, timestamp);
  `);
}

export async function backfill(options, dependencies = {}) {
  const fetchImpl = dependencies.fetchImpl ?? fetch;
  const log = dependencies.log ?? console.log;
  const db = dependencies.db ?? new DatabaseSync(options.db);
  const ownsDatabase = dependencies.db == null;
  ensureSchema(db);
  const request = createBybitRequester(fetchImpl, options.apiBaseUrl, options.requestDelayMs);
  try {
    if (options.datasets.has("trades")) await backfillTrades(db, options, fetchImpl, log);
    if (options.datasets.has("oi")) await backfillOpenInterest(db, options, request, log);
    if (options.datasets.has("account-ratio")) await backfillAccountRatios(db, options, request, log);
    if (options.datasets.has("premium")) await backfillPremium(db, options, request, log);
    if (options.datasets.has("funding")) await backfillFunding(db, options, request, log);
    if (options.datasets.has("coverage")) log(JSON.stringify(coverageReport(db, options), null, 2));
  } finally {
    if (ownsDatabase) db.close();
  }
}

async function backfillTrades(db, options, fetchImpl, log) {
  const insert = db.prepare(`
    INSERT INTO takerFlowBars(
      symbol, opened_at, taker_buy_base, taker_buy_notional,
      taker_sell_base, taker_sell_notional, buy_trade_count, sell_trade_count
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(symbol, opened_at) DO UPDATE SET
      taker_buy_base=excluded.taker_buy_base,
      taker_buy_notional=excluded.taker_buy_notional,
      taker_sell_base=excluded.taker_sell_base,
      taker_sell_notional=excluded.taker_sell_notional,
      buy_trade_count=excluded.buy_trade_count,
      sell_trade_count=excluded.sell_trade_count
  `);
  const insertEventFlow = db.prepare(`
    INSERT INTO takerEventFlowBars(
      symbol, opened_at, trade_count, buy_vwap, sell_vwap,
      largest_buy_notional, largest_sell_notional,
      open_trade_price, high_trade_price, low_trade_price, close_trade_price
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(symbol, opened_at) DO UPDATE SET
      trade_count=excluded.trade_count,
      buy_vwap=excluded.buy_vwap,
      sell_vwap=excluded.sell_vwap,
      largest_buy_notional=excluded.largest_buy_notional,
      largest_sell_notional=excluded.largest_sell_notional,
      open_trade_price=excluded.open_trade_price,
      high_trade_price=excluded.high_trade_price,
      low_trade_price=excluded.low_trade_price,
      close_trade_price=excluded.close_trade_price
  `);
  const insertManifest = db.prepare(`
    INSERT INTO historicalTradeImports(
      provider, dataset, symbol, source_date, source_url, archive_size_bytes, archive_sha256,
      event_count, first_event_at, last_event_at, minute_bar_count, imported_at, importer_version
    ) VALUES ('bybit', 'public-trades', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(provider, dataset, symbol, source_date) DO UPDATE SET
      source_url=excluded.source_url,
      archive_size_bytes=excluded.archive_size_bytes,
      event_count=excluded.event_count,
      first_event_at=excluded.first_event_at,
      last_event_at=excluded.last_event_at,
      minute_bar_count=excluded.minute_bar_count,
      imported_at=excluded.imported_at,
      importer_version=excluded.importer_version
  `);
  let completed = 0;
  for (const date of datesBetween(options.start, options.end)) {
    if (!options.force && hasCompleteTradeImportDay(db, options.symbol, date)) {
      completed += 1;
      continue;
    }
    const url = `${options.archiveBaseUrl.replace(/\/$/, "")}/${options.symbol}/${options.symbol}${date}.csv.gz`;
    const response = await fetchImpl(url);
    if (response.status === 404) {
      log(`trade archive unavailable date=${date}`);
      continue;
    }
    if (!response.ok || !response.body) throw new Error(`Trade archive ${date} failed with HTTP ${response.status}.`);
    const aggregate = await aggregateTradeArchive(response.body, options.symbol);
    completeTradeBarsForCandles(db, options.symbol, date, aggregate.bars);
    const existing = db.prepare(`
      SELECT archive_sha256 FROM historicalTradeImports
      WHERE provider='bybit' AND dataset='public-trades' AND symbol=? AND source_date=?
      LIMIT 1
    `).get(options.symbol, date);
    verifyExistingTradeArchiveHash(existing?.archive_sha256, aggregate.archiveSha256, date);
    inTransaction(db, () => {
      for (const [minute, bar] of aggregate.bars) {
        insert.run(
          options.symbol,
          instantString(minute),
          decimalString(bar.buyBase),
          decimalString(bar.buyNotional),
          decimalString(bar.sellBase),
          decimalString(bar.sellNotional),
          bar.buyCount,
          bar.sellCount,
        );
        insertEventFlow.run(
          options.symbol,
          instantString(minute),
          bar.buyCount + bar.sellCount,
          decimalString(bar.buyBase === 0 ? 0 : bar.buyNotional / bar.buyBase),
          decimalString(bar.sellBase === 0 ? 0 : bar.sellNotional / bar.sellBase),
          decimalString(bar.largestBuyNotional),
          decimalString(bar.largestSellNotional),
          decimalString(bar.openPrice),
          decimalString(bar.highPrice),
          decimalString(bar.lowPrice),
          decimalString(bar.closePrice),
        );
      }
      insertManifest.run(
        options.symbol,
        date,
        url,
        aggregate.archiveSizeBytes,
        aggregate.archiveSha256,
        aggregate.eventCount,
        instantString(aggregate.firstEventAt),
        instantString(aggregate.lastEventAt),
        aggregate.bars.size,
        new Date().toISOString(),
        TRADE_IMPORTER_VERSION,
      );
    });
    completed += 1;
    if (completed % 30 === 0) log(`trade archives completed=${completed} latest=${date}`);
  }
}

export function hasCompleteTradeImportDay(db, symbol, date) {
  if (!hasCompleteTakerFlowDay(db, symbol, date)) return false;
  const nextDate = addUtcDays(date, 1);
  const manifest = db.prepare(`
    SELECT importer_version, minute_bar_count
    FROM historicalTradeImports
    WHERE provider='bybit' AND dataset='public-trades' AND symbol=? AND source_date=?
    LIMIT 1
  `).get(symbol, date);
  if (manifest?.importer_version !== TRADE_IMPORTER_VERSION || Number(manifest.minute_bar_count) !== MINUTES_PER_DAY) {
    return false;
  }
  const eventFlowCount = db.prepare(`
    SELECT count(*) AS count FROM takerEventFlowBars
    WHERE symbol=? AND opened_at>=? AND opened_at<?
  `).get(symbol, `${date}T00:00:00Z`, `${nextDate}T00:00:00Z`).count;
  return Number(eventFlowCount) === MINUTES_PER_DAY;
}

export function hasCompleteTakerFlowDay(db, symbol, date) {
  const nextDate = addUtcDays(date, 1);
  const expectedFirst = `${date}T00:00:00Z`;
  const expectedLast = `${nextDate}T00:00:00Z`;
  const row = db.prepare(`
    WITH ordered AS (
      SELECT
        opened_at,
        strftime('%s', opened_at) AS opened_epoch,
        LAG(strftime('%s', opened_at)) OVER (ORDER BY opened_at) AS previous_epoch
      FROM takerFlowBars
      WHERE symbol=? AND opened_at>=? AND opened_at<?
    )
    SELECT
      count(*) AS count,
      min(opened_at) AS first_opened_at,
      max(opened_at) AS last_opened_at,
      coalesce(sum(CASE WHEN previous_epoch IS NOT NULL AND opened_epoch - previous_epoch != 60 THEN 1 ELSE 0 END), 0) AS gap_count
    FROM ordered
  `).get(symbol, expectedFirst, expectedLast);
  return (
    Number(row.count) === 1_440 &&
    row.first_opened_at === expectedFirst &&
    row.last_opened_at === `${date}T23:59:00Z` &&
    Number(row.gap_count) === 0
  );
}

export function completeTradeBarsForCandles(db, symbol, date, bars) {
  const nextDate = addUtcDays(date, 1);
  const dayStart = `${date}T00:00:00Z`;
  const dayEnd = `${nextDate}T00:00:00Z`;
  if (!tableExists(db, "marketCandles")) {
    if (bars.size !== 1_440) {
      throw new Error(`Cannot verify missing trade minutes for ${date} without M1 candle coverage.`);
    }
    return bars;
  }
  const candles = db.prepare(`
    SELECT opened_at, volume
    FROM marketCandles
    WHERE symbol=? AND timeframe='M1' AND opened_at>=? AND opened_at<?
    ORDER BY opened_at ASC
  `).all(symbol, dayStart, dayEnd);
  if (candles.length !== 1_440) {
    throw new Error(`M1 candle coverage is incomplete for ${date}: expected 1440 rows, received ${candles.length}.`);
  }
  const dayStartMillis = Date.parse(dayStart);
  const dayEndMillis = Date.parse(dayEnd);
  for (let minute = 0; minute < candles.length; minute += 1) {
    const candle = candles[minute];
    const openedAt = Date.parse(candle.opened_at);
    if (candle.opened_at !== instantString(dayStartMillis + minute * 60_000)) {
      throw new Error(`M1 candle coverage is not continuous for ${date} at minute offset=${minute}.`);
    }
    if (!bars.has(openedAt)) {
      if (Number(candle.volume) !== 0) {
        throw new Error(`Trade archive misses positive-volume minute ${candle.opened_at}.`);
      }
      bars.set(openedAt, zeroTakerFlowBar());
    }
  }
  for (const openedAt of bars.keys()) {
    if (openedAt < dayStartMillis || openedAt >= dayEndMillis) {
      throw new Error(`Trade archive returned a minute outside ${date}: ${instantString(openedAt)}.`);
    }
  }
  return bars;
}

export async function aggregateTradeArchive(webBody, expectedSymbol) {
  const archiveHash = createHash("sha256");
  let archiveSizeBytes = 0;
  const hashingStream = new Transform({
    transform(chunk, _encoding, callback) {
      archiveHash.update(chunk);
      archiveSizeBytes += chunk.length;
      callback(null, chunk);
    },
  });
  const lines = createInterface({ input: Readable.fromWeb(webBody).pipe(hashingStream).pipe(createGunzip()), crlfDelay: Infinity });
  const bars = new Map();
  let columns;
  let eventCount = 0;
  let firstEventAt = null;
  let lastEventAt = null;
  for await (const line of lines) {
    if (!line) continue;
    const fields = line.split(",");
    if (!columns) {
      const header = new Map(fields.map((name, index) => [name.trim(), index]));
      columns = Object.fromEntries(["timestamp", "symbol", "side", "size", "price"].map((name) => {
        if (!header.has(name)) throw new Error(`Trade archive is missing ${name} column.`);
        return [name, header.get(name)];
      }));
      continue;
    }
    if (fields[columns.symbol] !== expectedSymbol) throw new Error(`Unexpected archive symbol ${fields[columns.symbol]}.`);
    const eventAt = Number(fields[columns.timestamp]);
    const eventAtMillis = eventAt >= 10_000_000_000 ? eventAt : eventAt * 1_000;
    if (!Number.isFinite(eventAtMillis)) throw new Error("Trade archive contains an invalid event timestamp.");
    if (lastEventAt != null && eventAtMillis < lastEventAt) throw new Error("Trade archive timestamps must be non-decreasing.");
    firstEventAt ??= eventAtMillis;
    lastEventAt = eventAtMillis;
    eventCount += 1;
    applyTrade(fields, columns, bars);
  }
  if (eventCount === 0 || firstEventAt == null || lastEventAt == null) {
    throw new Error("Trade archive contains no events.");
  }
  return {
    bars,
    eventCount,
    firstEventAt,
    lastEventAt,
    archiveSizeBytes,
    archiveSha256: archiveHash.digest("hex"),
  };
}

export function verifyExistingTradeArchiveHash(existingHash, archiveHash, sourceDate) {
  if (existingHash != null && existingHash !== archiveHash) {
    throw new Error(`Official trade archive hash changed for ${sourceDate}; refusing to replace the recorded provenance.`);
  }
}

async function backfillOpenInterest(db, options, request, log) {
  const insert = db.prepare(`
    INSERT INTO openInterestSnapshots(symbol, interval, timestamp, open_interest) VALUES (?, 'M5', ?, ?)
    ON CONFLICT(symbol, interval, timestamp) DO UPDATE SET open_interest=excluded.open_interest
  `);
  await backfillReversePages({
    options,
    limit: 200,
    requestPage: (end) => request("/v5/market/open-interest", {
      category: "linear", symbol: options.symbol, intervalTime: "5min",
      startTime: dayStartMillis(options.start), endTime: end, limit: 200,
    }),
    rows: (result) => result.list ?? [],
    timestamp: (row) => Number(row.timestamp),
    insertRows: (rows) => inTransaction(db, () => rows.forEach((row) => insert.run(options.symbol, instantString(Number(row.timestamp)), row.openInterest))),
    label: "open-interest",
    log,
  });
}

async function backfillAccountRatios(db, options, request, log) {
  const insert = db.prepare(`
    INSERT INTO accountRatioSnapshots(symbol, period, timestamp, buy_ratio, sell_ratio)
    VALUES (?, 'M5', ?, ?, ?)
    ON CONFLICT(symbol, period, timestamp) DO UPDATE SET
      buy_ratio=excluded.buy_ratio,
      sell_ratio=excluded.sell_ratio
  `);
  await backfillReversePages({
    options,
    limit: 500,
    requestPage: (end) => request("/v5/market/account-ratio", {
      category: "linear", symbol: options.symbol, period: "5min",
      startTime: dayStartMillis(options.start), endTime: end, limit: 500,
    }),
    rows: (result) => result.list ?? [],
    timestamp: (row) => Number(row.timestamp),
    insertRows: (rows) => inTransaction(db, () => rows.forEach((row) => {
      insert.run(options.symbol, instantString(Number(row.timestamp)), row.buyRatio, row.sellRatio);
    })),
    label: "account-ratio",
    log,
  });
}

async function backfillPremium(db, options, request, log) {
  const insert = db.prepare(`
    INSERT INTO premiumIndexBars(symbol, timeframe, opened_at, open, high, low, close)
    VALUES (?, 'M15', ?, ?, ?, ?, ?)
    ON CONFLICT(symbol, timeframe, opened_at) DO UPDATE SET
      open=excluded.open, high=excluded.high, low=excluded.low, close=excluded.close
  `);
  await backfillReversePages({
    options,
    limit: 1000,
    requestPage: (end) => request("/v5/market/premium-index-price-kline", {
      category: "linear", symbol: options.symbol, interval: 15,
      start: dayStartMillis(options.start), end, limit: 1000,
    }),
    rows: (result) => result.list ?? [],
    timestamp: (row) => Number(row[0]),
    insertRows: (rows) => inTransaction(db, () => rows.forEach((row) => insert.run(options.symbol, instantString(Number(row[0])), ...row.slice(1, 5)))),
    label: "premium",
    log,
  });
}

async function backfillFunding(db, options, request, log) {
  const insert = db.prepare(`
    INSERT INTO fundingRates(symbol, timestamp, funding_rate) VALUES (?, ?, ?)
    ON CONFLICT(symbol, timestamp) DO UPDATE SET funding_rate=excluded.funding_rate
  `);
  await backfillReversePages({
    options,
    limit: 200,
    requestPage: (end) => request("/v5/market/funding/history", {
      category: "linear", symbol: options.symbol,
      startTime: dayStartMillis(options.start), endTime: end, limit: 200,
    }),
    rows: (result) => result.list ?? [],
    timestamp: (row) => Number(row.fundingRateTimestamp),
    insertRows: (rows) => inTransaction(db, () => rows.forEach((row) => insert.run(options.symbol, instantString(Number(row.fundingRateTimestamp)), row.fundingRate))),
    label: "funding",
    log,
  });
}

async function backfillReversePages({options, limit, requestPage, rows, timestamp, insertRows, label, log}) {
  const start = dayStartMillis(options.start);
  let end = dayEndMillis(options.end);
  let pages = 0;
  while (end >= start) {
    const result = await requestPage(end);
    const page = rows(result).filter((row) => timestamp(row) >= start && timestamp(row) <= dayEndMillis(options.end));
    if (page.length === 0) break;
    insertRows(page);
    const oldest = Math.min(...page.map(timestamp));
    if (oldest >= end) throw new Error(`${label} pagination did not move backwards.`);
    end = oldest - 1;
    pages += 1;
    if (pages % 50 === 0) log(`${label} pages=${pages} oldest=${instantString(oldest)}`);
  }
  log(`${label} complete pages=${pages}`);
}

function createBybitRequester(fetchImpl, baseUrl, delayMs) {
  let lastRequestAt = 0;
  return async (path, params) => {
    for (let attempt = 0; attempt < 8; attempt += 1) {
      const wait = Math.max(0, lastRequestAt + delayMs - Date.now());
      if (wait > 0) await sleep(wait);
      const url = new URL(path, baseUrl);
      for (const [key, value] of Object.entries(params)) {
        url.searchParams.set(key, String(value));
      }
      lastRequestAt = Date.now();
      const response = await fetchImpl(url);
      if (!response.ok) {
        await sleep(Math.min(30_000, 500 * 2 ** attempt));
        continue;
      }
      const payload = await response.json();
      if (payload.retCode === 0) return payload.result ?? {};
      if (payload.retCode !== 10006) throw new Error(`${path} failed: ${payload.retCode} ${payload.retMsg}`);
      await sleep(Math.min(30_000, 1_000 * 2 ** attempt));
    }
    throw new Error(`${path} retry budget exhausted.`);
  };
}

export function coverageReport(db, options) {
  const bounds = [`${options.start}T00:00:00Z`, `${addUtcDays(options.end, 1)}T00:00:00Z`];
  const summary = (table, timeColumn, extra = "") => db.prepare(`
    SELECT count(*) count, min(${timeColumn}) earliest, max(${timeColumn}) latest
    FROM ${table} WHERE symbol=? AND ${timeColumn}>=? AND ${timeColumn}<? ${extra}
  `).get(options.symbol, ...bounds);
  const missingTradeMinutes = db.prepare(`
    SELECT count(*) count
    FROM marketCandles candle
    LEFT JOIN takerFlowBars flow ON flow.symbol=candle.symbol AND flow.opened_at=candle.opened_at
    WHERE candle.symbol=? AND candle.timeframe='M1' AND candle.opened_at>=? AND candle.opened_at<?
      AND CAST(candle.volume AS REAL)>0 AND flow.id IS NULL
  `).get(options.symbol, ...bounds).count;
  return {
    symbol: options.symbol,
    start: options.start,
    end: options.end,
    takerFlow: summary("takerFlowBars", "opened_at"),
    openInterest: summary("openInterestSnapshots", "timestamp", "AND interval='M5'"),
    accountRatio: summary("accountRatioSnapshots", "timestamp", "AND period='M5'"),
    premium: summary("premiumIndexBars", "opened_at", "AND timeframe='M15'"),
    funding: summary("fundingRates", "timestamp"),
    missingPositiveVolumeTradeMinutes: Number(missingTradeMinutes),
  };
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

function dayStartMillis(date) {
  return Date.parse(`${date}T00:00:00Z`);
}

function dayEndMillis(date) {
  return Date.parse(`${date}T23:59:59.999Z`);
}

function instantString(milliseconds) {
  return new Date(milliseconds).toISOString().replace(".000Z", "Z");
}

function decimalString(value) {
  if (!Number.isFinite(value)) throw new Error("Cannot persist a non-finite decimal.");
  return value.toFixed(12).replace(/\.?0+$/, "");
}

function zeroTakerFlowBar() {
  return {
    buyBase: 0,
    buyNotional: 0,
    sellBase: 0,
    sellNotional: 0,
    buyCount: 0,
    sellCount: 0,
    largestBuyNotional: 0,
    largestSellNotional: 0,
    openPrice: 0,
    highPrice: 0,
    lowPrice: 0,
    closePrice: 0,
  };
}

function tableExists(db, table) {
  return db.prepare("SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1").get(table) != null;
}

function isDate(value) {
  return /^\d{4}-\d{2}-\d{2}$/.test(value) && new Date(`${value}T00:00:00Z`).toISOString().slice(0, 10) === value;
}

function sleep(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

const invokedPath = process.argv[1] ? pathToFileURL(resolve(process.argv[1])).href : null;
if (invokedPath === import.meta.url) {
  const options = parseArgs(process.argv.slice(2));
  console.log(`Bybit flow backfill db=${options.db} symbol=${options.symbol} range=${options.start}..${options.end}`);
  await backfill(options);
}
