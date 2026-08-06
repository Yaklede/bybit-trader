#!/usr/bin/env node

import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import { access, copyFile, mkdir, readFile, rename, rm, writeFile } from "node:fs/promises";
import { DatabaseSync } from "node:sqlite";
import { dirname, resolve } from "node:path";
import { pathToFileURL } from "node:url";

import {
  aggregateArchiveLines,
  readArchiveFile,
} from "./bybit-orderbook-backfill.mjs";
import {
  aggregateTradeArchiveBuckets,
  backfill as backfillFlow,
  parseArgs as parseFlowArgs,
  retryTradeArchiveOperation,
} from "./bybit-flow-backfill.mjs";
import { loadSubminuteSequenceProtocol } from "./subminute-sequence-protocol.mjs";

const DEFAULT_PROTOCOL = "config/bybit-subminute-sequence-development-v1.json";
const ONE_DAY_MILLIS = 86_400_000;
const IMPORTER_VERSION = "bybit-subminute-sequence-v1";
const STAGES = new Set(["selection", "internal-validation"]);

export function parseArgs(argv) {
  const values = new Map();
  for (const argument of argv) {
    if (!argument.startsWith("--") || !argument.includes("=")) {
      throw new Error(`Invalid argument: ${argument}. Use --name=value.`);
    }
    const [name, ...rest] = argument.slice(2).split("=");
    if (!["protocol", "stage", "report", "request-delay-ms"].includes(name)) {
      throw new Error(`Unsupported argument: --${name}.`);
    }
    values.set(name, rest.join("="));
  }
  const stage = values.get("stage") ?? "selection";
  const requestDelayMs = Number(values.get("request-delay-ms") ?? 150);
  if (!STAGES.has(stage)) throw new Error(`stage must be one of: ${[...STAGES].join(", ")}.`);
  if (!Number.isInteger(requestDelayMs) || requestDelayMs < 0) {
    throw new Error("request-delay-ms must be a non-negative integer.");
  }
  return {
    protocol: resolve(values.get("protocol") ?? DEFAULT_PROTOCOL),
    stage,
    report: values.has("report") ? resolve(values.get("report")) : null,
    requestDelayMs,
  };
}

export async function acquireSubminuteSequence(options, dependencies = {}) {
  const { protocol, sha256: protocolSha256 } = await loadSubminuteSequenceProtocol(options.protocol);
  const repositoryRoot = resolve(dirname(options.protocol), "..");
  const candleDatabasePath = resolve(repositoryRoot, protocol.sourceData.canonicalCandleDatabase);
  const aggregateDatabasePath = resolve(repositoryRoot, protocol.sourceData.aggregateEvidenceDatabase);
  const targetDatabasePath = resolve(repositoryRoot, protocol.sourceData.researchDatabase);
  const reportPath = options.report ?? resolve(
    repositoryRoot,
    `build/research/${protocol.protocolId}-${options.stage}-acquisition.json`,
  );
  const stageSnapshotPath = resolve(
    repositoryRoot,
    `build/research/${protocol.protocolId}-${options.stage}.sqlite`,
  );
  const implementationSha256 = await implementationFingerprint(repositoryRoot);
  const blocks = options.stage === "selection"
    ? protocol.acquisition.selectionBlocks
    : protocol.acquisition.internalValidationBlocks;
  const dates = uniqueBlockDates(blocks);
  const hashFile = dependencies.hashFile ?? sha256File;
  const [candleDatabaseSha256, aggregateDatabaseSha256] = await Promise.all([
    hashFile(candleDatabasePath),
    hashFile(aggregateDatabasePath),
  ]);
  if (candleDatabaseSha256 !== protocol.sourceData.canonicalCandleDatabaseSha256) {
    throw new Error(`Canonical candle database hash mismatch: ${candleDatabaseSha256}.`);
  }
  if (aggregateDatabaseSha256 !== protocol.sourceData.aggregateEvidenceDatabaseSha256) {
    throw new Error(`Aggregate evidence database hash mismatch: ${aggregateDatabaseSha256}.`);
  }

  await mkdir(dirname(targetDatabasePath), { recursive: true });
  await mkdir(dirname(reportPath), { recursive: true });
  const candleDb = dependencies.candleDb ?? new DatabaseSync(candleDatabasePath, { readOnly: true });
  const aggregateDb = dependencies.aggregateDb ?? new DatabaseSync(aggregateDatabasePath, { readOnly: true });
  const targetDb = dependencies.targetDb ?? new DatabaseSync(targetDatabasePath);
  const ownsCandleDb = dependencies.candleDb == null;
  const ownsAggregateDb = dependencies.aggregateDb == null;
  const ownsTargetDb = dependencies.targetDb == null;
  const fetchImpl = dependencies.fetchImpl ?? fetch;
  const log = dependencies.log ?? console.log;
  const now = dependencies.now ?? (() => new Date().toISOString());
  const report = {
    schemaVersion: 1,
    protocolId: protocol.protocolId,
    protocolSha256,
    implementationSha256,
    stage: options.stage,
    status: "IN_PROGRESS",
    sourceDateCount: dates.length,
    completedDates: [],
    failedAt: null,
    failure: null,
    targetDatabase: protocol.sourceData.researchDatabase,
    targetDatabaseSha256: null,
    stageSnapshot: `build/research/${protocol.protocolId}-${options.stage}.sqlite`,
    stageSnapshotSha256: null,
    normalizedFeatureSha256: null,
    startedAt: now(),
    updatedAt: now(),
  };

  let failure = null;
  try {
    ensureSubminuteSchema(targetDb);
    bindResearchDatabase(targetDb, {
      protocolId: protocol.protocolId,
      protocolSha256,
      candleDatabaseSha256,
      aggregateDatabaseSha256,
      bucketMillis: protocol.sourceData.bucketMillis,
    });
    copyCandleBlocks(candleDb, targetDb, protocol.sourceData.symbol, blocks);
    await acquireDerivativesFeatures(targetDb, protocol.sourceData.symbol, blocks, options.requestDelayMs, log, fetchImpl);

    for (const date of dates) {
      const expected = expectedArchiveDay(aggregateDb, protocol.sourceData.symbol, date);
      if (!hasCompleteOrderBookDay(targetDb, protocol.sourceData.symbol, date, expected.orderBook.archiveSha256)) {
        log(`subminute order-book acquisition started date=${date}`);
        const orderBook = await readArchiveFile(
          {
            date,
            symbol: protocol.sourceData.symbol,
            filename: expected.orderBook.archiveFilename,
            size: String(expected.orderBook.archiveSizeBytes),
            url: expected.orderBook.sourceUrl,
          },
          {
            symbol: protocol.sourceData.symbol,
            orderBookDepth: protocol.sourceData.retainedOrderBookDepth,
            archiveAttempts: 3,
            archiveRetryDelayMillis: 1_000,
            archiveDirectory: null,
            funzipCommand: "funzip",
          },
          (stream, context) => aggregateArchiveLines(stream, {
            ...context,
            bucketMillis: protocol.sourceData.bucketMillis,
            fillEmptyBuckets: true,
          }),
          fetchImpl,
        );
        assertOrderBookProvenance(orderBook, expected.orderBook);
        assertCompleteSliceDay(orderBook.eventFlowBars, date, protocol.sourceData.bucketMillis);
        verifyOrderBookMinuteParity(orderBook.eventFlowBars, aggregateDb, protocol.sourceData.symbol, date);
        persistOrderBookDay(targetDb, orderBook.eventFlowBars, expected.orderBook, protocol.sourceData.symbol, date);
        log(`subminute order-book acquisition completed date=${date} slices=${orderBook.eventFlowBars.length}`);
      }

      if (!hasCompleteTradeDay(targetDb, protocol.sourceData.symbol, date, expected.trade.archiveSha256)) {
        log(`subminute trade acquisition started date=${date}`);
        const trade = await retryTradeArchiveOperation(async () => {
          const response = await fetchImpl(expected.trade.sourceUrl);
          if (!response.ok || !response.body) {
            throw new Error(`Trade archive download failed date=${date} HTTP ${response.status}.`);
          }
          return aggregateTradeArchiveBuckets(
            response.body,
            protocol.sourceData.symbol,
            protocol.sourceData.bucketMillis,
          );
        }, 3, 1_000);
        assertTradeProvenance(trade, expected.trade);
        const slices = completeTradeSliceDay(trade.bars, date, protocol.sourceData.bucketMillis);
        verifyTradeMinuteParity(slices, aggregateDb, protocol.sourceData.symbol, date);
        persistTradeDay(targetDb, slices, expected.trade, protocol.sourceData.symbol, date);
        log(`subminute trade acquisition completed date=${date} slices=${slices.length}`);
      }

      report.completedDates = auditCompletedDates(targetDb, protocol.sourceData.symbol, dates);
      report.updatedAt = now();
      await writeJsonAtomic(reportPath, report);
    }

    const audit = auditSubminuteCoverage(
      targetDb,
      protocol.sourceData.symbol,
      dates,
      protocol.sourceData.bucketMillis,
    );
    if (!audit.complete) throw new Error(`Subminute coverage audit failed: ${audit.failures.join("; ")}.`);
    report.status = "COMPLETE";
    report.updatedAt = now();
    report.completedDates = audit.completedDates;
    report.normalizedFeatureSha256 = normalizedFeatureFingerprint(targetDb, protocol.sourceData.symbol, dates);
    targetDb.exec("PRAGMA wal_checkpoint(TRUNCATE)");
    report.stageSnapshotSha256 = await sealStageSnapshot(
      targetDatabasePath,
      stageSnapshotPath,
      protocol.sourceData.symbol,
      dates,
      report.normalizedFeatureSha256,
      hashFile,
    );
  } catch (error) {
    failure = error;
    report.status = "FAILED_SOURCE_ACQUISITION";
    report.failedAt = now();
    report.updatedAt = report.failedAt;
    report.failure = {
      name: error instanceof Error ? error.name : "Error",
      message: error instanceof Error ? error.message : String(error),
    };
  } finally {
    if (ownsCandleDb) candleDb.close();
    if (ownsAggregateDb) aggregateDb.close();
    if (ownsTargetDb) targetDb.close();
  }

  if (failure == null) report.targetDatabaseSha256 = await hashFile(targetDatabasePath);
  await writeJsonAtomic(reportPath, report);
  if (failure != null) throw failure;
  return report;
}

export function ensureSubminuteSchema(db) {
  db.exec(`
    PRAGMA journal_mode=WAL;
    PRAGMA synchronous=NORMAL;
    PRAGMA busy_timeout=30000;
    CREATE TABLE IF NOT EXISTS researchAcquisitionMetadata (
      key TEXT NOT NULL PRIMARY KEY,
      value TEXT NOT NULL
    );
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
    CREATE TABLE IF NOT EXISTS subminuteOrderBookSlices (
      id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
      symbol TEXT NOT NULL,
      opened_at TEXT NOT NULL,
      message_count INTEGER NOT NULL,
      snapshot_count INTEGER NOT NULL,
      carried_forward INTEGER NOT NULL,
      close_best_bid TEXT NOT NULL,
      close_best_ask TEXT NOT NULL,
      open_mid_price TEXT NOT NULL,
      high_mid_price TEXT NOT NULL,
      low_mid_price TEXT NOT NULL,
      close_mid_price TEXT NOT NULL,
      mean_top5_imbalance TEXT NOT NULL,
      start_top5_imbalance TEXT NOT NULL,
      end_top5_imbalance TEXT NOT NULL,
      min_top5_imbalance TEXT NOT NULL,
      max_top5_imbalance TEXT NOT NULL,
      mean_microprice_edge_bps TEXT NOT NULL,
      bid_added_top5_notional TEXT NOT NULL,
      bid_removed_top5_notional TEXT NOT NULL,
      ask_added_top5_notional TEXT NOT NULL,
      ask_removed_top5_notional TEXT NOT NULL
    );
    CREATE UNIQUE INDEX IF NOT EXISTS subminuteOrderBookSlices_symbol_openedAt_idx
      ON subminuteOrderBookSlices(symbol, opened_at);
    CREATE TABLE IF NOT EXISTS subminuteTradeSlices (
      id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
      symbol TEXT NOT NULL,
      opened_at TEXT NOT NULL,
      trade_count INTEGER NOT NULL,
      buy_notional TEXT NOT NULL,
      sell_notional TEXT NOT NULL,
      buy_count INTEGER NOT NULL,
      sell_count INTEGER NOT NULL,
      open_price TEXT,
      high_price TEXT,
      low_price TEXT,
      close_price TEXT,
      first_trade_at TEXT,
      last_trade_at TEXT
    );
    CREATE UNIQUE INDEX IF NOT EXISTS subminuteTradeSlices_symbol_openedAt_idx
      ON subminuteTradeSlices(symbol, opened_at);
    CREATE TABLE IF NOT EXISTS subminuteOrderBookImports (
      symbol TEXT NOT NULL,
      source_date TEXT NOT NULL,
      source_url TEXT NOT NULL,
      archive_filename TEXT NOT NULL,
      archive_size_bytes INTEGER NOT NULL,
      archive_sha256 TEXT NOT NULL,
      event_count INTEGER NOT NULL,
      first_event_at TEXT NOT NULL,
      last_event_at TEXT NOT NULL,
      slice_count INTEGER NOT NULL,
      imported_at TEXT NOT NULL,
      importer_version TEXT NOT NULL,
      PRIMARY KEY(symbol, source_date)
    );
    CREATE TABLE IF NOT EXISTS subminuteTradeImports (
      symbol TEXT NOT NULL,
      source_date TEXT NOT NULL,
      source_url TEXT NOT NULL,
      archive_size_bytes INTEGER NOT NULL,
      archive_sha256 TEXT NOT NULL,
      event_count INTEGER NOT NULL,
      first_event_at TEXT NOT NULL,
      last_event_at TEXT NOT NULL,
      slice_count INTEGER NOT NULL,
      imported_at TEXT NOT NULL,
      importer_version TEXT NOT NULL,
      PRIMARY KEY(symbol, source_date)
    );
  `);
}

export function bindResearchDatabase(db, values) {
  const select = db.prepare("SELECT value FROM researchAcquisitionMetadata WHERE key=?");
  const insert = db.prepare("INSERT INTO researchAcquisitionMetadata(key, value) VALUES (?, ?)");
  inTransaction(db, () => {
    for (const [key, rawValue] of Object.entries(values)) {
      const value = String(rawValue);
      const existing = select.get(key);
      if (existing != null && existing.value !== value) {
        throw new Error(`Subminute research database metadata mismatch for ${key}.`);
      }
      if (existing == null) insert.run(key, value);
    }
  });
}

export function expectedArchiveDay(db, symbol, date) {
  const orderBook = db.prepare(`
    SELECT source_url, archive_filename, archive_size_bytes, archive_sha256,
           event_count, first_event_at, last_event_at
    FROM historicalOrderBookImports
    WHERE provider='bybit' AND dataset='orderbook' AND symbol=? AND source_date=?
  `).get(symbol, date);
  const trade = db.prepare(`
    SELECT source_url, archive_size_bytes, archive_sha256,
           event_count, first_event_at, last_event_at
    FROM historicalTradeImports
    WHERE provider='bybit' AND dataset='public-trades' AND symbol=? AND source_date=?
  `).get(symbol, date);
  if (orderBook == null || trade == null) throw new Error(`Aggregate source manifest is missing date=${date}.`);
  return {
    orderBook: normalizeManifest(orderBook),
    trade: normalizeManifest(trade),
  };
}

export function assertCompleteSliceDay(slices, date, bucketMillis) {
  const dayStart = Date.parse(`${date}T00:00:00Z`);
  const expectedCount = ONE_DAY_MILLIS / bucketMillis;
  if (slices.length !== expectedCount) {
    throw new Error(`Subminute day ${date} expected ${expectedCount} slices, received ${slices.length}.`);
  }
  for (let index = 0; index < slices.length; index += 1) {
    if (slices[index].openedAt !== dayStart + index * bucketMillis) {
      throw new Error(`Subminute day ${date} is not contiguous at slice=${index}.`);
    }
  }
}

export function completeTradeSliceDay(bars, date, bucketMillis) {
  const dayStart = Date.parse(`${date}T00:00:00Z`);
  const dayEnd = dayStart + ONE_DAY_MILLIS;
  for (const openedAt of bars.keys()) {
    if (openedAt < dayStart || openedAt >= dayEnd || openedAt % bucketMillis !== 0) {
      throw new Error(`Trade slice ${openedAt} is outside or misaligned for ${date}.`);
    }
  }
  const slices = [];
  for (let openedAt = dayStart; openedAt < dayEnd; openedAt += bucketMillis) {
    const bar = bars.get(openedAt);
    slices.push(bar == null
      ? {
          openedAt,
          tradeCount: 0,
          buyNotional: 0,
          sellNotional: 0,
          buyCount: 0,
          sellCount: 0,
          openPrice: null,
          highPrice: null,
          lowPrice: null,
          closePrice: null,
          firstTradeAt: null,
          lastTradeAt: null,
        }
      : {
          openedAt,
          tradeCount: bar.buyCount + bar.sellCount,
          buyNotional: bar.buyNotional,
          sellNotional: bar.sellNotional,
          buyCount: bar.buyCount,
          sellCount: bar.sellCount,
          openPrice: bar.openPrice,
          highPrice: bar.highPrice,
          lowPrice: bar.lowPrice,
          closePrice: bar.closePrice,
          firstTradeAt: bar.firstTradeAt,
          lastTradeAt: bar.lastTradeAt,
        });
  }
  assertCompleteSliceDay(slices, date, bucketMillis);
  return slices;
}

export function verifyOrderBookMinuteParity(slices, aggregateDb, symbol, date) {
  const expectedRows = aggregateDb.prepare(`
    SELECT * FROM orderBookEventFlowBars
    WHERE symbol=? AND opened_at>=? AND opened_at<? ORDER BY opened_at
  `).all(symbol, `${date}T00:00:00Z`, `${addUtcDays(date, 1)}T00:00:00Z`);
  if (expectedRows.length !== 1_440) throw new Error(`Expected order-book minute parity source is incomplete for ${date}.`);
  for (let minute = 0; minute < 1_440; minute += 1) {
    const group = slices.slice(minute * 12, minute * 12 + 12);
    const expected = expectedRows[minute];
    const eventSlices = group.filter((slice) => slice.messageCount > 0);
    const messageCount = sum(group, "messageCount");
    assertEqualNumber(messageCount, expected.message_count, `order-book message count ${expected.opened_at}`);
    assertEqualNumber(sum(group, "snapshotCount"), expected.snapshot_count, `order-book snapshot count ${expected.opened_at}`);
    assertClose(weightedMean(eventSlices, "meanTop5Imbalance", "messageCount"), expected.mean_top5_imbalance, `order-book imbalance ${expected.opened_at}`);
    assertClose(weightedMean(eventSlices, "meanMicropriceEdgeBps", "messageCount"), expected.mean_microprice_edge_bps, `order-book microprice ${expected.opened_at}`);
    assertClose(sum(group, "bidAddedTop5Notional"), expected.bid_added_top5_notional, `order-book bid add ${expected.opened_at}`);
    assertClose(sum(group, "bidRemovedTop5Notional"), expected.bid_removed_top5_notional, `order-book bid remove ${expected.opened_at}`);
    assertClose(sum(group, "askAddedTop5Notional"), expected.ask_added_top5_notional, `order-book ask add ${expected.opened_at}`);
    assertClose(sum(group, "askRemovedTop5Notional"), expected.ask_removed_top5_notional, `order-book ask remove ${expected.opened_at}`);
    assertClose(eventSlices[0].openMidPrice, expected.open_mid_price, `order-book open mid ${expected.opened_at}`);
    assertClose(eventSlices.at(-1).closeMidPrice, expected.close_mid_price, `order-book close mid ${expected.opened_at}`);
    assertClose(Math.max(...eventSlices.map((slice) => slice.highMidPrice)), expected.high_mid_price, `order-book high mid ${expected.opened_at}`);
    assertClose(Math.min(...eventSlices.map((slice) => slice.lowMidPrice)), expected.low_mid_price, `order-book low mid ${expected.opened_at}`);
    assertEqualNumber(messageCount > 0, true, `order-book minute has events ${expected.opened_at}`);
  }
}

export function verifyTradeMinuteParity(slices, aggregateDb, symbol, date) {
  const expectedRows = aggregateDb.prepare(`
    SELECT f.*, e.open_trade_price, e.high_trade_price, e.low_trade_price, e.close_trade_price
    FROM takerFlowBars f
    JOIN takerEventFlowBars e ON e.symbol=f.symbol AND e.opened_at=f.opened_at
    WHERE f.symbol=? AND f.opened_at>=? AND f.opened_at<? ORDER BY f.opened_at
  `).all(symbol, `${date}T00:00:00Z`, `${addUtcDays(date, 1)}T00:00:00Z`);
  if (expectedRows.length !== 1_440) throw new Error(`Expected trade minute parity source is incomplete for ${date}.`);
  for (let minute = 0; minute < 1_440; minute += 1) {
    const group = slices.slice(minute * 12, minute * 12 + 12);
    const expected = expectedRows[minute];
    const traded = group.filter((slice) => slice.tradeCount > 0);
    assertClose(sum(group, "buyNotional"), expected.taker_buy_notional, `trade buy notional ${expected.opened_at}`);
    assertClose(sum(group, "sellNotional"), expected.taker_sell_notional, `trade sell notional ${expected.opened_at}`);
    assertEqualNumber(sum(group, "buyCount"), expected.buy_trade_count, `trade buy count ${expected.opened_at}`);
    assertEqualNumber(sum(group, "sellCount"), expected.sell_trade_count, `trade sell count ${expected.opened_at}`);
    if (traded.length === 0) {
      assertClose(0, expected.open_trade_price, `trade empty open ${expected.opened_at}`);
      continue;
    }
    assertClose(traded[0].openPrice, expected.open_trade_price, `trade open ${expected.opened_at}`);
    assertClose(traded.at(-1).closePrice, expected.close_trade_price, `trade close ${expected.opened_at}`);
    assertClose(Math.max(...traded.map((slice) => slice.highPrice)), expected.high_trade_price, `trade high ${expected.opened_at}`);
    assertClose(Math.min(...traded.map((slice) => slice.lowPrice)), expected.low_trade_price, `trade low ${expected.opened_at}`);
  }
}

function assertOrderBookProvenance(actual, expected) {
  assertManifestProvenance(actual, expected, "order-book");
}

function assertTradeProvenance(actual, expected) {
  assertManifestProvenance(actual, expected, "trade");
}

function assertManifestProvenance(actual, expected, label) {
  if (actual.archiveSha256 !== expected.archiveSha256 ||
      actual.archiveSizeBytes !== expected.archiveSizeBytes ||
      actual.eventCount !== expected.eventCount ||
      instantString(actual.firstEventAt) !== expected.firstEventAt ||
      instantString(actual.lastEventAt) !== expected.lastEventAt) {
    throw new Error(`${label} archive provenance changed from the bound minute evidence.`);
  }
}

function persistOrderBookDay(db, slices, expected, symbol, date) {
  const insert = db.prepare(`
    INSERT INTO subminuteOrderBookSlices(
      symbol, opened_at, message_count, snapshot_count, carried_forward,
      close_best_bid, close_best_ask, open_mid_price, high_mid_price, low_mid_price, close_mid_price,
      mean_top5_imbalance, start_top5_imbalance, end_top5_imbalance,
      min_top5_imbalance, max_top5_imbalance, mean_microprice_edge_bps,
      bid_added_top5_notional, bid_removed_top5_notional,
      ask_added_top5_notional, ask_removed_top5_notional
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `);
  inTransaction(db, () => {
    db.prepare("DELETE FROM subminuteOrderBookSlices WHERE symbol=? AND opened_at>=? AND opened_at<?")
      .run(symbol, `${date}T00:00:00Z`, `${addUtcDays(date, 1)}T00:00:00Z`);
    for (const slice of slices) {
      insert.run(
        symbol, instantString(slice.openedAt), slice.messageCount, slice.snapshotCount,
        slice.carriedForward ? 1 : 0, decimalString(slice.closeBestBid), decimalString(slice.closeBestAsk),
        decimalString(slice.openMidPrice), decimalString(slice.highMidPrice),
        decimalString(slice.lowMidPrice), decimalString(slice.closeMidPrice),
        decimalString(slice.meanTop5Imbalance), decimalString(slice.startTop5Imbalance),
        decimalString(slice.endTop5Imbalance), decimalString(slice.minTop5Imbalance),
        decimalString(slice.maxTop5Imbalance), decimalString(slice.meanMicropriceEdgeBps),
        decimalString(slice.bidAddedTop5Notional), decimalString(slice.bidRemovedTop5Notional),
        decimalString(slice.askAddedTop5Notional), decimalString(slice.askRemovedTop5Notional),
      );
    }
    db.prepare(`
      INSERT OR REPLACE INTO subminuteOrderBookImports VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `).run(
      symbol, date, expected.sourceUrl, expected.archiveFilename, expected.archiveSizeBytes,
      expected.archiveSha256, expected.eventCount, expected.firstEventAt, expected.lastEventAt,
      slices.length, new Date().toISOString(), IMPORTER_VERSION,
    );
  });
}

function persistTradeDay(db, slices, expected, symbol, date) {
  const insert = db.prepare(`
    INSERT INTO subminuteTradeSlices(
      symbol, opened_at, trade_count, buy_notional, sell_notional, buy_count, sell_count,
      open_price, high_price, low_price, close_price, first_trade_at, last_trade_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `);
  inTransaction(db, () => {
    db.prepare("DELETE FROM subminuteTradeSlices WHERE symbol=? AND opened_at>=? AND opened_at<?")
      .run(symbol, `${date}T00:00:00Z`, `${addUtcDays(date, 1)}T00:00:00Z`);
    for (const slice of slices) {
      insert.run(
        symbol, instantString(slice.openedAt), slice.tradeCount,
        decimalString(slice.buyNotional), decimalString(slice.sellNotional),
        slice.buyCount, slice.sellCount,
        nullableDecimalString(slice.openPrice), nullableDecimalString(slice.highPrice),
        nullableDecimalString(slice.lowPrice), nullableDecimalString(slice.closePrice),
        nullableInstantString(slice.firstTradeAt), nullableInstantString(slice.lastTradeAt),
      );
    }
    db.prepare(`
      INSERT OR REPLACE INTO subminuteTradeImports VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `).run(
      symbol, date, expected.sourceUrl, expected.archiveSizeBytes, expected.archiveSha256,
      expected.eventCount, expected.firstEventAt, expected.lastEventAt,
      slices.length, new Date().toISOString(), IMPORTER_VERSION,
    );
  });
}

function hasCompleteOrderBookDay(db, symbol, date, archiveSha256) {
  return hasCompleteDay(db, "subminuteOrderBookImports", "subminuteOrderBookSlices", symbol, date, archiveSha256);
}

function hasCompleteTradeDay(db, symbol, date, archiveSha256) {
  return hasCompleteDay(db, "subminuteTradeImports", "subminuteTradeSlices", symbol, date, archiveSha256);
}

function hasCompleteDay(db, importTable, sliceTable, symbol, date, archiveSha256) {
  const next = addUtcDays(date, 1);
  const row = db.prepare(`
    SELECT i.archive_sha256, i.slice_count,
      (SELECT count(*) FROM ${sliceTable} s WHERE s.symbol=i.symbol AND s.opened_at>=? AND s.opened_at<?) actual_count
    FROM ${importTable} i WHERE i.symbol=? AND i.source_date=?
  `).get(`${date}T00:00:00Z`, `${next}T00:00:00Z`, symbol, date);
  return row?.archive_sha256 === archiveSha256 && Number(row.slice_count) === 17_280 && Number(row.actual_count) === 17_280;
}

export function copyCandleBlocks(sourceDb, targetDb, symbol, blocks) {
  const select = sourceDb.prepare(`
    SELECT symbol, timeframe, opened_at, open, high, low, close, volume, source_timestamp
    FROM marketCandles
    WHERE symbol=? AND timeframe IN ('M1','M5','M15') AND opened_at>=? AND opened_at<?
    ORDER BY opened_at, timeframe
  `);
  const insert = targetDb.prepare(`
    INSERT OR IGNORE INTO marketCandles(
      symbol, timeframe, opened_at, open, high, low, close, volume, source_timestamp
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
  `);
  inTransaction(targetDb, () => {
    for (const block of blocks) {
      const rows = select.all(symbol, `${block.sourceStartDate}T00:00:00Z`, `${addUtcDays(block.sourceEndDate, 1)}T00:00:00Z`);
      if (rows.length === 0) throw new Error(`Canonical candles are missing block=${block.id}.`);
      for (const row of rows) {
        insert.run(
          row.symbol, row.timeframe, row.opened_at, row.open, row.high,
          row.low, row.close, row.volume, row.source_timestamp,
        );
      }
    }
  });
}

async function acquireDerivativesFeatures(db, symbol, blocks, requestDelayMs, log, fetchImpl) {
  const start = blocks.map((block) => block.sourceStartDate).sort()[0];
  const end = blocks.map((block) => block.sourceEndDate).sort().at(-1);
  if (hasCompleteDerivativesCoverage(db, symbol, start, end)) {
    log(`derivatives feature acquisition skipped range=${start}..${end} reason=complete`);
    return;
  }
  await backfillFlow(parseFlowArgs([
    `--db=/unused/subminute.sqlite`,
    `--symbol=${symbol}`,
    `--start=${start}`,
    `--end=${end}`,
    "--datasets=oi,funding",
    `--request-delay-ms=${requestDelayMs}`,
  ]), { db, log, fetchImpl });
}

function hasCompleteDerivativesCoverage(db, symbol, start, end) {
  if (!tableExists(db, "openInterestSnapshots") || !tableExists(db, "fundingRates")) return false;
  const startAt = `${start}T00:00:00Z`;
  const endExclusive = `${addUtcDays(end, 1)}T00:00:00Z`;
  const durationMillis = Date.parse(endExclusive) - Date.parse(startAt);
  const expectedOpenInterest = durationMillis / 300_000;
  const expectedFunding = durationMillis / 28_800_000;
  const openInterest = db.prepare(`
    SELECT count(*) count FROM openInterestSnapshots
    WHERE symbol=? AND interval='M5' AND timestamp>=? AND timestamp<?
  `).get(symbol, startAt, endExclusive);
  const funding = db.prepare(`
    SELECT count(*) count FROM fundingRates
    WHERE symbol=? AND timestamp>=? AND timestamp<?
  `).get(symbol, startAt, endExclusive);
  return Number(openInterest.count) === expectedOpenInterest && Number(funding.count) === expectedFunding;
}

function auditSubminuteCoverage(db, symbol, dates, bucketMillis) {
  const failures = [];
  const completedDates = [];
  const expected = ONE_DAY_MILLIS / bucketMillis;
  for (const date of dates) {
    const next = addUtcDays(date, 1);
    const book = Number(db.prepare(`SELECT count(*) count FROM subminuteOrderBookSlices WHERE symbol=? AND opened_at>=? AND opened_at<?`)
      .get(symbol, `${date}T00:00:00Z`, `${next}T00:00:00Z`).count);
    const trade = Number(db.prepare(`SELECT count(*) count FROM subminuteTradeSlices WHERE symbol=? AND opened_at>=? AND opened_at<?`)
      .get(symbol, `${date}T00:00:00Z`, `${next}T00:00:00Z`).count);
    if (book !== expected || trade !== expected) failures.push(`${date}:book=${book},trade=${trade}`);
    else completedDates.push(date);
  }
  return { complete: failures.length === 0, failures, completedDates };
}

function auditCompletedDates(db, symbol, dates) {
  return auditSubminuteCoverage(db, symbol, dates, 5_000).completedDates;
}

export function normalizedFeatureFingerprint(db, symbol, dates) {
  const hash = createHash("sha256");
  const updateRows = (query, parameters) => {
    const statement = db.prepare(query);
    for (const row of statement.all(...parameters)) {
      const { id: _ignoredId, ...stableRow } = row;
      hash.update(JSON.stringify(stableRow));
      hash.update("\n");
    }
  };
  for (const date of dates) {
    const bounds = [symbol, `${date}T00:00:00Z`, `${addUtcDays(date, 1)}T00:00:00Z`];
    updateRows("SELECT * FROM subminuteOrderBookSlices WHERE symbol=? AND opened_at>=? AND opened_at<? ORDER BY opened_at", bounds);
    updateRows("SELECT * FROM subminuteTradeSlices WHERE symbol=? AND opened_at>=? AND opened_at<? ORDER BY opened_at", bounds);
  }
  updateRows("SELECT symbol, interval, timestamp, open_interest FROM openInterestSnapshots WHERE symbol=? ORDER BY timestamp", [symbol]);
  updateRows("SELECT symbol, timestamp, funding_rate FROM fundingRates WHERE symbol=? ORDER BY timestamp", [symbol]);
  return hash.digest("hex");
}

export async function implementationFingerprint(repositoryRoot) {
  const paths = [
    "scripts/bybit-orderbook-backfill.mjs",
    "scripts/bybit-flow-backfill.mjs",
    "scripts/subminute-sequence-protocol.mjs",
    "scripts/subminute-sequence-acquire.mjs",
  ];
  const hash = createHash("sha256");
  for (const path of paths) {
    hash.update(path);
    hash.update("\0");
    hash.update(await readFile(resolve(repositoryRoot, path)));
    hash.update("\0");
  }
  return hash.digest("hex");
}

export async function sealStageSnapshot(
  sourcePath,
  snapshotPath,
  symbol,
  dates,
  expectedFeatureSha256,
  hashFile = sha256File,
) {
  try {
    await access(snapshotPath);
  } catch {
    await mkdir(dirname(snapshotPath), { recursive: true });
    const temporaryPath = `${snapshotPath}.tmp`;
    await rm(temporaryPath, { force: true });
    await copyFile(sourcePath, temporaryPath);
    await rename(temporaryPath, snapshotPath);
  }
  const snapshotDb = new DatabaseSync(snapshotPath, { readOnly: true });
  try {
    const actualFeatureSha256 = normalizedFeatureFingerprint(snapshotDb, symbol, dates);
    if (actualFeatureSha256 !== expectedFeatureSha256) {
      throw new Error(
        `Sealed stage snapshot feature hash mismatch: expected ${expectedFeatureSha256}, got ${actualFeatureSha256}.`,
      );
    }
  } finally {
    snapshotDb.close();
  }
  return hashFile(snapshotPath);
}

function uniqueBlockDates(blocks) {
  const dates = new Set();
  for (const block of blocks) {
    for (let date = block.sourceStartDate; date <= block.sourceEndDate; date = addUtcDays(date, 1)) dates.add(date);
  }
  return [...dates].sort();
}

function normalizeManifest(row) {
  return {
    sourceUrl: row.source_url,
    archiveFilename: row.archive_filename,
    archiveSizeBytes: Number(row.archive_size_bytes),
    archiveSha256: row.archive_sha256,
    eventCount: Number(row.event_count),
    firstEventAt: row.first_event_at,
    lastEventAt: row.last_event_at,
  };
}

function weightedMean(rows, valueField, weightField) {
  const weight = rows.reduce((total, row) => total + Number(row[weightField]), 0);
  if (weight === 0) throw new Error(`Cannot calculate ${valueField} without weighted observations.`);
  return rows.reduce((total, row) => total + Number(row[valueField]) * Number(row[weightField]), 0) / weight;
}

function sum(rows, field) {
  return rows.reduce((total, row) => total + Number(row[field]), 0);
}

function assertEqualNumber(actual, expected, label) {
  if (Number(actual) !== Number(expected)) throw new Error(`${label} mismatch: ${actual} != ${expected}.`);
}

function assertClose(actual, expected, label) {
  const left = Number(actual);
  const right = Number(expected);
  const tolerance = Math.max(1e-6, Math.abs(right) * 1e-8);
  if (!Number.isFinite(left) || !Number.isFinite(right) || Math.abs(left - right) > tolerance) {
    throw new Error(`${label} mismatch: ${left} != ${right}.`);
  }
}

function decimalString(value) {
  if (!Number.isFinite(Number(value))) throw new Error(`Cannot persist non-finite decimal: ${value}.`);
  return Number(value).toFixed(12).replace(/\.?0+$/, "");
}

function nullableDecimalString(value) {
  return value == null ? null : decimalString(value);
}

function instantString(milliseconds) {
  return new Date(Number(milliseconds)).toISOString().replace(".000Z", "Z");
}

function nullableInstantString(milliseconds) {
  return milliseconds == null ? null : instantString(milliseconds);
}

function addUtcDays(date, days) {
  const value = new Date(`${date}T00:00:00Z`);
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
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

function tableExists(db, table) {
  return db.prepare("SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1").get(table) != null;
}

async function sha256File(path) {
  const hash = createHash("sha256");
  for await (const chunk of createReadStream(path)) hash.update(chunk);
  return hash.digest("hex");
}

async function writeJsonAtomic(path, value) {
  const temporaryPath = `${path}.tmp`;
  await writeFile(temporaryPath, `${JSON.stringify(value, null, 2)}\n`);
  await rename(temporaryPath, path);
}

const invokedPath = process.argv[1] ? pathToFileURL(resolve(process.argv[1])).href : null;
if (invokedPath === import.meta.url) {
  const options = parseArgs(process.argv.slice(2));
  const report = await acquireSubminuteSequence(options);
  console.log(JSON.stringify(report, null, 2));
}
