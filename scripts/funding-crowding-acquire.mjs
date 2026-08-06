#!/usr/bin/env node

import { createHash } from "node:crypto";
import { copyFile, mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { DatabaseSync } from "node:sqlite";
import { dirname, resolve } from "node:path";
import { pathToFileURL } from "node:url";

import { sha256File } from "./event-flow-development-backfill.mjs";
import { loadFundingCrowdingProtocol } from "./funding-crowding-protocol.mjs";

const DEFAULT_PROTOCOL = "config/bybit-funding-crowding-development-v1.json";
const FUNDING_INTERVAL_MILLIS = 8 * 60 * 60 * 1_000;
const PREMIUM_INTERVAL_MILLIS = 15 * 60 * 1_000;
const IMPORTER_VERSION = "funding-crowding-development-v1";

export function parseArgs(argv) {
  const values = new Map();
  for (const argument of argv) {
    if (!argument.startsWith("--") || !argument.includes("=")) {
      throw new Error(`Invalid argument: ${argument}. Use --name=value.`);
    }
    const [name, ...rest] = argument.slice(2).split("=");
    if (!["protocol", "report", "request-delay-ms"].includes(name)) {
      throw new Error(`Unsupported argument: --${name}.`);
    }
    if (values.has(name)) throw new Error(`Duplicate argument: --${name}.`);
    values.set(name, rest.join("="));
  }
  const requestDelayMs = Number(values.get("request-delay-ms") ?? 150);
  if (!Number.isInteger(requestDelayMs) || requestDelayMs < 0) {
    throw new Error("request-delay-ms must be a non-negative integer.");
  }
  return {
    protocol: resolve(values.get("protocol") ?? DEFAULT_PROTOCOL),
    report: values.has("report") ? resolve(values.get("report")) : null,
    requestDelayMs,
  };
}

export async function acquireFundingCrowdingDevelopment(options, dependencies = {}) {
  const loaded = await loadFundingCrowdingProtocol(options.protocol);
  const protocol = loaded.protocol;
  const repositoryRoot = resolve(dirname(options.protocol), "..");
  const sourceDatabasePath = resolve(repositoryRoot, protocol.sourceData.canonicalCandleDatabase);
  const targetDatabasePath = resolve(repositoryRoot, protocol.sourceData.researchDatabase);
  const snapshotPath = resolve(repositoryRoot, `build/research/${protocol.protocolId}-development.sqlite`);
  const reportPath = options.report ?? resolve(
    repositoryRoot,
    `build/research/${protocol.protocolId}-development-acquisition.json`,
  );
  const hashFile = dependencies.hashFile ?? sha256File;
  const sourceDatabaseSha256 = await hashFile(sourceDatabasePath);
  if (sourceDatabaseSha256 !== protocol.sourceData.canonicalCandleDatabaseSha256) {
    throw new Error(`Canonical candle database hash mismatch: ${sourceDatabaseSha256}.`);
  }
  await mkdir(dirname(targetDatabasePath), { recursive: true });
  await mkdir(dirname(reportPath), { recursive: true });
  const db = dependencies.database ?? new DatabaseSync(targetDatabasePath);
  const ownsDatabase = dependencies.database == null;
  const fetchImpl = dependencies.fetchImpl ?? fetch;
  const now = dependencies.now ?? (() => new Date().toISOString());
  const log = dependencies.log ?? console.log;
  let result;
  try {
    ensureFundingCrowdingSchema(db);
    bindFundingCrowdingDatabase(db, {
      protocolId: protocol.protocolId,
      protocolSha256: loaded.sha256,
      parentResultSha256: loaded.parentResultSha256,
      candleDatabaseSha256: sourceDatabaseSha256,
      boundAt: now(),
    });
    copyDevelopmentCandles(db, sourceDatabasePath, protocol);
    const request = createPublicRequester(
      fetchImpl,
      protocol.sourceData.baseUrl,
      options.requestDelayMs,
      dependencies.wait,
    );
    const fundingImport = await importFundingIfMissing(db, protocol, request, now, log);
    const premiumImport = await importPremiumIfMissing(db, protocol, request, now, log);
    const coverage = auditFundingCrowdingCoverage(db, protocol);
    if (!coverage.complete) {
      throw new Error(`Funding crowding coverage failed: ${coverage.failures.join("; ")}.`);
    }
    const normalizedFeatureSha256 = normalizedFundingFeatureFingerprint(db, protocol.sourceData.symbol);
    db.exec("PRAGMA wal_checkpoint(TRUNCATE)");
    const snapshotSha256 = await sealDevelopmentSnapshot(
      targetDatabasePath,
      snapshotPath,
      db,
      protocol,
      normalizedFeatureSha256,
      hashFile,
    );
    result = {
      schemaVersion: 1,
      protocolId: protocol.protocolId,
      protocolSha256: loaded.sha256,
      parentResultSha256: loaded.parentResultSha256,
      implementationSha256: await implementationFingerprint(repositoryRoot),
      status: "COMPLETE_DEVELOPMENT_EVIDENCE_SEALED",
      sourceDatabaseSha256,
      developmentRange: {
        startAt: protocol.sourceData.developmentStart,
        endExclusive: protocol.sourceData.developmentEndExclusive,
      },
      fundingImport,
      premiumImport,
      coverage,
      normalizedFeatureSha256,
      snapshot: `build/research/${protocol.protocolId}-development.sqlite`,
      snapshotSha256,
      internalValidation2023Through2024Read: false,
      external2025Read: false,
      external2026Read: false,
      freshForwardSealRead: false,
      generatedAt: now(),
      automaticExecutionAllowed: false,
      liveExecutionAllowed: false,
    };
    await writeJsonAtomic(reportPath, result);
  } finally {
    if (ownsDatabase) db.close();
  }
  return result;
}

export function ensureFundingCrowdingSchema(db) {
  db.exec(`
    PRAGMA journal_mode=WAL;
    PRAGMA synchronous=NORMAL;
    PRAGMA busy_timeout=30000;
    CREATE TABLE IF NOT EXISTS fundingCrowdingMetadata (
      singleton INTEGER NOT NULL PRIMARY KEY CHECK(singleton=1),
      protocol_id TEXT NOT NULL,
      protocol_sha256 TEXT NOT NULL,
      parent_result_sha256 TEXT NOT NULL,
      candle_database_sha256 TEXT NOT NULL,
      bound_at TEXT NOT NULL
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
    CREATE UNIQUE INDEX IF NOT EXISTS fundingMarketCandles_symbol_timeframe_openedAt_idx
      ON marketCandles(symbol, timeframe, opened_at);
    CREATE TABLE IF NOT EXISTS fundingRates (
      symbol TEXT NOT NULL,
      timestamp TEXT NOT NULL,
      funding_rate TEXT NOT NULL,
      PRIMARY KEY(symbol, timestamp)
    );
    CREATE TABLE IF NOT EXISTS premiumIndexBars (
      symbol TEXT NOT NULL,
      timeframe TEXT NOT NULL,
      opened_at TEXT NOT NULL,
      open TEXT NOT NULL,
      high TEXT NOT NULL,
      low TEXT NOT NULL,
      close TEXT NOT NULL,
      PRIMARY KEY(symbol, timeframe, opened_at)
    );
    CREATE TABLE IF NOT EXISTS fundingCrowdingImports (
      dataset TEXT NOT NULL PRIMARY KEY,
      source_endpoint TEXT NOT NULL,
      range_start TEXT NOT NULL,
      range_end_exclusive TEXT NOT NULL,
      page_count INTEGER NOT NULL,
      row_count INTEGER NOT NULL,
      first_timestamp TEXT NOT NULL,
      last_timestamp TEXT NOT NULL,
      response_chain_sha256 TEXT NOT NULL,
      normalized_content_sha256 TEXT NOT NULL,
      imported_at TEXT NOT NULL,
      importer_version TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS fundingCrowdingRawPages (
      dataset TEXT NOT NULL,
      page_index INTEGER NOT NULL,
      canonical_request TEXT NOT NULL,
      response_sha256 TEXT NOT NULL,
      raw_body TEXT NOT NULL,
      PRIMARY KEY(dataset, page_index)
    );
  `);
}

export function bindFundingCrowdingDatabase(db, binding) {
  const existing = db.prepare("SELECT * FROM fundingCrowdingMetadata WHERE singleton=1").get();
  if (existing == null) {
    db.prepare(`
      INSERT INTO fundingCrowdingMetadata(
        singleton, protocol_id, protocol_sha256, parent_result_sha256, candle_database_sha256, bound_at
      ) VALUES (1, ?, ?, ?, ?, ?)
    `).run(
      binding.protocolId,
      binding.protocolSha256,
      binding.parentResultSha256,
      binding.candleDatabaseSha256,
      binding.boundAt,
    );
    return;
  }
  if (existing.protocol_id !== binding.protocolId || existing.protocol_sha256 !== binding.protocolSha256 ||
      existing.parent_result_sha256 !== binding.parentResultSha256 ||
      existing.candle_database_sha256 !== binding.candleDatabaseSha256) {
    throw new Error("Funding crowding database is bound to different evidence.");
  }
}

export function normalizeFundingRows(rows, startMillis, endExclusiveMillis) {
  return normalizeTimestampRows(
    rows,
    (row) => Number(row.fundingRateTimestamp),
    (row, timestamp) => ({ timestamp, fundingRate: canonicalDecimal(row.fundingRate) }),
    startMillis,
    endExclusiveMillis,
    "funding",
  );
}

export function normalizePremiumRows(rows, startMillis, endExclusiveMillis) {
  return normalizeTimestampRows(
    rows,
    (row) => Number(row[0]),
    (row, timestamp) => ({
      timestamp,
      open: canonicalDecimal(row[1]),
      high: canonicalDecimal(row[2]),
      low: canonicalDecimal(row[3]),
      close: canonicalDecimal(row[4]),
    }),
    startMillis,
    endExclusiveMillis,
    "premium",
  );
}

export function verifyIntervalCoverage(rows, expectedIntervalMillis, label, options = {}) {
  const failures = [];
  if (rows.length === 0) return { complete: false, failures: [`${label} has no rows.`], maximumGapMillis: null };
  let maximumGapMillis = 0;
  for (let index = 1; index < rows.length; index += 1) {
    const gap = rows[index].timestamp - rows[index - 1].timestamp;
    maximumGapMillis = Math.max(maximumGapMillis, gap);
    if (gap !== expectedIntervalMillis) failures.push(`${label} gap at ${instantString(rows[index].timestamp)} is ${gap}ms.`);
  }
  if (options.requiredLastAtOrAfter != null && rows.at(-1).timestamp < options.requiredLastAtOrAfter) {
    failures.push(`${label} ends before required boundary.`);
  }
  if (options.minimumRows != null && rows.length < options.minimumRows) {
    failures.push(`${label} has ${rows.length} rows, expected at least ${options.minimumRows}.`);
  }
  return { complete: failures.length === 0, failures, maximumGapMillis };
}

export function normalizedFundingFeatureFingerprint(db, symbol) {
  const hash = createHash("sha256");
  const update = (query, parameters) => {
    const statement = db.prepare(query);
    for (const row of statement.iterate(...parameters)) {
      hash.update(JSON.stringify(row));
      hash.update("\n");
    }
  };
  update("SELECT symbol,timestamp,funding_rate FROM fundingRates WHERE symbol=? ORDER BY timestamp", [symbol]);
  update(`
    SELECT symbol,timeframe,opened_at,open,high,low,close
    FROM premiumIndexBars WHERE symbol=? AND timeframe='M15' ORDER BY opened_at
  `, [symbol]);
  return hash.digest("hex");
}

export function auditFundingCrowdingCoverage(db, protocol) {
  const symbol = protocol.sourceData.symbol;
  const startMillis = Date.parse(protocol.sourceData.developmentStart);
  const endExclusiveMillis = Date.parse(protocol.sourceData.developmentEndExclusive);
  const funding = db.prepare(`
    SELECT timestamp,funding_rate FROM fundingRates WHERE symbol=? AND timestamp>=? AND timestamp<? ORDER BY timestamp
  `).all(symbol, instantString(startMillis), instantString(endExclusiveMillis)).map((row) => ({
    timestamp: Date.parse(row.timestamp),
    fundingRate: row.funding_rate,
  }));
  const premium = db.prepare(`
    SELECT opened_at FROM premiumIndexBars
    WHERE symbol=? AND timeframe='M15' AND opened_at>=? AND opened_at<? ORDER BY opened_at
  `).all(symbol, instantString(startMillis), instantString(endExclusiveMillis)).map((row) => ({
    timestamp: Date.parse(row.opened_at),
  }));
  const firstPremiumCandle = db.prepare(`
    SELECT min(opened_at) timestamp FROM marketCandles
    WHERE symbol=? AND timeframe='M15' AND opened_at>=? AND opened_at<?
  `).get(symbol, instantString(startMillis), instantString(endExclusiveMillis)).timestamp;
  const fundingCoverage = verifyIntervalCoverage(funding, FUNDING_INTERVAL_MILLIS, "funding", {
    requiredLastAtOrAfter: endExclusiveMillis - FUNDING_INTERVAL_MILLIS,
    minimumRows: 90,
  });
  const premiumCoverage = verifyIntervalCoverage(premium, PREMIUM_INTERVAL_MILLIS, "premium", {
    requiredLastAtOrAfter: endExclusiveMillis - PREMIUM_INTERVAL_MILLIS,
    minimumRows: 90,
  });
  const failures = [...fundingCoverage.failures, ...premiumCoverage.failures];
  if (premium[0]?.timestamp !== Date.parse(firstPremiumCandle)) {
    failures.push("Premium history does not begin at the first canonical M15 candle.");
  }
  const warmupBeforeFirstBlock = funding.filter((row) =>
    row.timestamp < Date.parse(protocol.evidenceSchedule.developmentBlocks[0].startAt)).length;
  if (warmupBeforeFirstBlock < 90) failures.push("Funding history lacks 90 settled observations before F01.");
  const candleCounts = Object.fromEntries(db.prepare(`
    SELECT timeframe,count(*) count FROM marketCandles
    WHERE symbol=? AND opened_at>=? AND opened_at<? GROUP BY timeframe ORDER BY timeframe
  `).all(symbol, instantString(startMillis), instantString(endExclusiveMillis)).map((row) => [row.timeframe, Number(row.count)]));
  const candleCoverage = {};
  for (const [timeframe, intervalMillis] of [["M1", 60_000], ["M5", 300_000], ["M15", 900_000]]) {
    if (!Number.isInteger(candleCounts[timeframe]) || candleCounts[timeframe] <= 0) {
      failures.push(`Canonical ${timeframe} candles are missing.`);
      continue;
    }
    const summary = db.prepare(`
      SELECT count(*) row_count,min(opened_at) first_timestamp,max(opened_at) last_timestamp,
        sum(CASE WHEN (unixepoch(opened_at) * 1000) % ? = 0 THEN 0 ELSE 1 END) off_grid_count
      FROM marketCandles WHERE symbol=? AND timeframe=? AND opened_at>=? AND opened_at<?
    `).get(intervalMillis, symbol, timeframe, instantString(startMillis), instantString(endExclusiveMillis));
    const firstMillis = Date.parse(summary.first_timestamp);
    const lastMillis = Date.parse(summary.last_timestamp);
    const expectedRows = Number.isFinite(firstMillis) && Number.isFinite(lastMillis)
      ? Math.floor((lastMillis - firstMillis) / intervalMillis) + 1
      : 0;
    const rowCount = Number(summary.row_count);
    const offGridCount = Number(summary.off_grid_count);
    if (rowCount !== expectedRows || offGridCount !== 0 || lastMillis < endExclusiveMillis - intervalMillis) {
      failures.push(`Canonical ${timeframe} candle timeline is incomplete or off-grid.`);
    }
    candleCoverage[timeframe] = {
      rowCount,
      expectedRows,
      firstTimestamp: summary.first_timestamp,
      lastTimestamp: summary.last_timestamp,
      offGridCount,
    };
  }
  return {
    complete: failures.length === 0,
    failures,
    funding: {
      rowCount: funding.length,
      firstTimestamp: funding[0] == null ? null : instantString(funding[0].timestamp),
      lastTimestamp: funding.at(-1) == null ? null : instantString(funding.at(-1).timestamp),
      maximumGapMillis: fundingCoverage.maximumGapMillis,
      warmupBeforeFirstBlock,
    },
    premium: {
      rowCount: premium.length,
      firstTimestamp: premium[0] == null ? null : instantString(premium[0].timestamp),
      lastTimestamp: premium.at(-1) == null ? null : instantString(premium.at(-1).timestamp),
      maximumGapMillis: premiumCoverage.maximumGapMillis,
    },
    candleCounts,
    candleCoverage,
  };
}

async function importFundingIfMissing(db, protocol, request, now, log) {
  const existing = importSummary(db, "funding");
  if (existing != null) {
    const rows = loadPersistedFundingRows(db, protocol);
    validateExistingImport(existing, rows, loadPersistedRawPages(db, "funding"), {
      endpoint: protocol.sourceData.fundingEndpoint,
      rangeStart: protocol.sourceData.developmentStart,
      rangeEndExclusive: protocol.sourceData.developmentEndExclusive,
    });
    return existing;
  }
  log("funding crowding development funding acquisition started");
  const startMillis = Date.parse(protocol.sourceData.developmentStart);
  const endExclusiveMillis = Date.parse(protocol.sourceData.developmentEndExclusive);
  const acquired = await fetchReversePages({
    endpoint: protocol.sourceData.fundingEndpoint,
    params: {
      category: "linear",
      symbol: protocol.sourceData.symbol,
      startTime: startMillis,
      limit: protocol.sourceData.requestLimit.funding,
    },
    endExclusiveMillis,
    request,
    rows: (result) => result.list ?? [],
    timestamp: (row) => Number(row.fundingRateTimestamp),
  });
  const rows = normalizeFundingRows(acquired.rows, startMillis, endExclusiveMillis);
  const coverage = verifyIntervalCoverage(rows, FUNDING_INTERVAL_MILLIS, "funding", {
    requiredLastAtOrAfter: endExclusiveMillis - FUNDING_INTERVAL_MILLIS,
    minimumRows: 90,
  });
  if (!coverage.complete) throw new Error(coverage.failures.join("; "));
  const contentSha256 = hashNormalizedRows(rows);
  inTransaction(db, () => {
    db.prepare("DELETE FROM fundingRates WHERE symbol=? AND timestamp>=? AND timestamp<?").run(
      protocol.sourceData.symbol,
      instantString(startMillis),
      instantString(endExclusiveMillis),
    );
    const insert = db.prepare("INSERT INTO fundingRates(symbol,timestamp,funding_rate) VALUES (?,?,?)");
    for (const row of rows) insert.run(protocol.sourceData.symbol, instantString(row.timestamp), row.fundingRate);
    insertRawPages(db, "funding", acquired.rawPages);
    insertImport(db, {
      dataset: "funding",
      endpoint: protocol.sourceData.fundingEndpoint,
      startAt: protocol.sourceData.developmentStart,
      endExclusive: protocol.sourceData.developmentEndExclusive,
      rows,
      pages: acquired.rawPages,
      responseChainSha256: acquired.responseChainSha256,
      contentSha256,
      importedAt: now(),
    });
  });
  log(`funding crowding development funding acquisition completed rows=${rows.length}`);
  return importSummary(db, "funding");
}

async function importPremiumIfMissing(db, protocol, request, now, log) {
  const existing = importSummary(db, "premium");
  if (existing != null) {
    const rows = loadPersistedPremiumRows(db, protocol);
    validateExistingImport(existing, rows, loadPersistedRawPages(db, "premium"), {
      endpoint: protocol.sourceData.premiumEndpoint,
      rangeStart: existing.rangeStart,
      rangeEndExclusive: protocol.sourceData.developmentEndExclusive,
    });
    return existing;
  }
  log("funding crowding development premium acquisition started");
  const startMillis = Date.parse(protocol.sourceData.developmentStart);
  const endExclusiveMillis = Date.parse(protocol.sourceData.developmentEndExclusive);
  const acquired = await fetchReversePages({
    endpoint: protocol.sourceData.premiumEndpoint,
    params: {
      category: "linear",
      symbol: protocol.sourceData.symbol,
      interval: protocol.sourceData.premiumInterval,
      start: startMillis,
      limit: protocol.sourceData.requestLimit.premium,
    },
    endExclusiveMillis,
    request,
    rows: (result) => result.list ?? [],
    timestamp: (row) => Number(row[0]),
  });
  const rows = normalizePremiumRows(acquired.rows, startMillis, endExclusiveMillis);
  const firstM15 = db.prepare(`
    SELECT min(opened_at) timestamp FROM marketCandles
    WHERE symbol=? AND timeframe='M15' AND opened_at>=? AND opened_at<?
  `).get(protocol.sourceData.symbol, instantString(startMillis), instantString(endExclusiveMillis)).timestamp;
  const rowsFromLaunch = rows.filter((row) => row.timestamp >= Date.parse(firstM15));
  const coverage = verifyIntervalCoverage(rowsFromLaunch, PREMIUM_INTERVAL_MILLIS, "premium", {
    requiredLastAtOrAfter: endExclusiveMillis - PREMIUM_INTERVAL_MILLIS,
    minimumRows: 90,
  });
  if (!coverage.complete || rowsFromLaunch[0]?.timestamp !== Date.parse(firstM15)) {
    throw new Error([...coverage.failures, "Premium start does not match canonical M15 launch."].join("; "));
  }
  const contentSha256 = hashNormalizedRows(rowsFromLaunch);
  inTransaction(db, () => {
    db.prepare("DELETE FROM premiumIndexBars WHERE symbol=? AND timeframe='M15' AND opened_at>=? AND opened_at<?")
      .run(protocol.sourceData.symbol, instantString(startMillis), instantString(endExclusiveMillis));
    const insert = db.prepare(`
      INSERT INTO premiumIndexBars(symbol,timeframe,opened_at,open,high,low,close)
      VALUES (?,'M15',?,?,?,?,?)
    `);
    for (const row of rowsFromLaunch) {
      insert.run(protocol.sourceData.symbol, instantString(row.timestamp), row.open, row.high, row.low, row.close);
    }
    insertRawPages(db, "premium", acquired.rawPages);
    insertImport(db, {
      dataset: "premium",
      endpoint: protocol.sourceData.premiumEndpoint,
      startAt: instantString(rowsFromLaunch[0].timestamp),
      endExclusive: protocol.sourceData.developmentEndExclusive,
      rows: rowsFromLaunch,
      pages: acquired.rawPages,
      responseChainSha256: acquired.responseChainSha256,
      contentSha256,
      importedAt: now(),
    });
  });
  log(`funding crowding development premium acquisition completed rows=${rowsFromLaunch.length}`);
  return importSummary(db, "premium");
}

export function copyDevelopmentCandles(db, sourceDatabasePath, protocol) {
  const source = new DatabaseSync(sourceDatabasePath, { readOnly: true });
  try {
    const symbol = protocol.sourceData.symbol;
    const start = protocol.sourceData.developmentStart;
    const end = protocol.sourceData.developmentEndExclusive;
    const sourceCounts = Object.fromEntries(source.prepare(`
      SELECT timeframe,count(*) count FROM marketCandles
      WHERE symbol=? AND timeframe IN ('M1','M5','M15') AND opened_at>=? AND opened_at<? GROUP BY timeframe
    `).all(symbol, start, end).map((row) => [row.timeframe, Number(row.count)]));
    const targetCounts = Object.fromEntries(db.prepare(`
      SELECT timeframe,count(*) count FROM marketCandles
      WHERE symbol=? AND timeframe IN ('M1','M5','M15') AND opened_at>=? AND opened_at<? GROUP BY timeframe
    `).all(symbol, start, end).map((row) => [row.timeframe, Number(row.count)]));
    if (!["M1", "M5", "M15"].every((timeframe) => sourceCounts[timeframe] === targetCounts[timeframe])) {
      inTransaction(db, () => {
        db.prepare("DELETE FROM marketCandles WHERE symbol=? AND opened_at>=? AND opened_at<?").run(symbol, start, end);
        const insert = db.prepare(`
          INSERT INTO marketCandles(symbol,timeframe,opened_at,open,high,low,close,volume,source_timestamp)
          VALUES (?,?,?,?,?,?,?,?,?)
        `);
        const sourceCandles = source.prepare(`
          SELECT symbol,timeframe,opened_at,open,high,low,close,volume,source_timestamp
          FROM marketCandles WHERE symbol=? AND timeframe IN ('M1','M5','M15')
            AND opened_at>=? AND opened_at<? ORDER BY timeframe,opened_at
        `);
        for (const row of sourceCandles.iterate(symbol, start, end)) {
          insert.run(
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
      });
    }
    const sourceFingerprint = candleSubsetFingerprint(source, symbol, start, end);
    const targetFingerprint = candleSubsetFingerprint(db, symbol, start, end);
    if (sourceFingerprint !== targetFingerprint) {
      throw new Error("Copied funding crowding candles differ from the canonical source.");
    }
  } finally {
    source.close();
  }
}

export async function fetchReversePages({ endpoint, params, endExclusiveMillis, request, rows, timestamp }) {
  const startMillis = Number(params.startTime ?? params.start);
  let end = endExclusiveMillis - 1;
  const collected = [];
  const responseChain = createHash("sha256");
  const rawPages = [];
  while (end >= startMillis) {
    const query = { ...params };
    if ("startTime" in query) query.endTime = end;
    else query.end = end;
    const response = await request(endpoint, query);
    responseChain.update(response.canonicalRequest);
    responseChain.update("\0");
    responseChain.update(response.rawBody);
    responseChain.update("\0");
    rawPages.push({
      pageIndex: rawPages.length,
      canonicalRequest: response.canonicalRequest,
      responseSha256: sha256Text(response.rawBody),
      rawBody: response.rawBody,
    });
    const page = rows(response.payload.result).filter((row) => {
      const value = timestamp(row);
      return value >= startMillis && value < endExclusiveMillis;
    });
    if (page.length === 0) break;
    collected.push(...page);
    const oldest = Math.min(...page.map(timestamp));
    if (oldest > end) throw new Error(`${endpoint} pagination moved forward.`);
    end = oldest - 1;
    if (rawPages.length > 10_000) throw new Error(`${endpoint} pagination exceeded safety limit.`);
  }
  return {
    rows: collected,
    rawPages,
    pageCount: rawPages.length,
    responseChainSha256: responseChain.digest("hex"),
  };
}

function createPublicRequester(fetchImpl, baseUrl, delayMillis, wait = sleep) {
  let lastRequestAt = 0;
  return async (endpoint, params) => {
    const sorted = Object.entries(params).sort(([left], [right]) => left.localeCompare(right));
    const query = new URLSearchParams(sorted.map(([name, value]) => [name, String(value)]));
    const canonicalRequest = `${endpoint}?${query}`;
    for (let attempt = 0; attempt < 8; attempt += 1) {
      const remaining = delayMillis - (Date.now() - lastRequestAt);
      if (remaining > 0) await wait(remaining);
      lastRequestAt = Date.now();
      const response = await fetchImpl(`${baseUrl}${canonicalRequest}`);
      const rawBody = await response.text();
      let payload;
      try {
        payload = JSON.parse(rawBody);
      } catch {
        payload = null;
      }
      if (response.ok && payload?.retCode === 0) return { payload, rawBody, canonicalRequest };
      const retryable = response.status === 429 || response.status >= 500 || payload?.retCode === 10006;
      if (!retryable || attempt === 7) {
        throw new Error(`Bybit public request failed ${canonicalRequest}: HTTP ${response.status} code=${payload?.retCode}.`);
      }
      await wait(Math.min(10_000, 500 * 2 ** attempt));
    }
    throw new Error(`Bybit public request exhausted retries: ${canonicalRequest}.`);
  };
}

function normalizeTimestampRows(rows, timestampOf, normalize, startMillis, endExclusiveMillis, label) {
  const byTimestamp = new Map();
  for (const source of rows) {
    const timestamp = timestampOf(source);
    if (!Number.isInteger(timestamp) || timestamp < startMillis || timestamp >= endExclusiveMillis) continue;
    const row = normalize(source, timestamp);
    const encoded = JSON.stringify(row);
    const existing = byTimestamp.get(timestamp);
    if (existing != null && JSON.stringify(existing) !== encoded) {
      throw new Error(`${label} has conflicting rows at ${instantString(timestamp)}.`);
    }
    byTimestamp.set(timestamp, row);
  }
  return [...byTimestamp.values()].sort((left, right) => left.timestamp - right.timestamp);
}

function insertImport(db, value) {
  db.prepare(`
    INSERT INTO fundingCrowdingImports(
      dataset,source_endpoint,range_start,range_end_exclusive,page_count,row_count,
      first_timestamp,last_timestamp,response_chain_sha256,normalized_content_sha256,
      imported_at,importer_version
    ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
  `).run(
    value.dataset,
    value.endpoint,
    value.startAt,
    value.endExclusive,
    value.pages.length,
    value.rows.length,
    instantString(value.rows[0].timestamp),
    instantString(value.rows.at(-1).timestamp),
    value.responseChainSha256,
    value.contentSha256,
    value.importedAt,
    IMPORTER_VERSION,
  );
}

function insertRawPages(db, dataset, pages) {
  db.prepare("DELETE FROM fundingCrowdingRawPages WHERE dataset=?").run(dataset);
  const insert = db.prepare(`
    INSERT INTO fundingCrowdingRawPages(dataset,page_index,canonical_request,response_sha256,raw_body)
    VALUES (?,?,?,?,?)
  `);
  for (const page of pages) {
    insert.run(dataset, page.pageIndex, page.canonicalRequest, page.responseSha256, page.rawBody);
  }
}

function importSummary(db, dataset) {
  const row = db.prepare("SELECT * FROM fundingCrowdingImports WHERE dataset=?").get(dataset);
  if (row == null) return null;
  return {
    dataset: row.dataset,
    sourceEndpoint: row.source_endpoint,
    rangeStart: row.range_start,
    rangeEndExclusive: row.range_end_exclusive,
    pageCount: Number(row.page_count),
    rowCount: Number(row.row_count),
    firstTimestamp: row.first_timestamp,
    lastTimestamp: row.last_timestamp,
    responseChainSha256: row.response_chain_sha256,
    normalizedContentSha256: row.normalized_content_sha256,
    importerVersion: row.importer_version,
  };
}

export function validateExistingImport(summary, rows, pages = null, expected = {}) {
  if (rows.length === 0 || summary.rowCount !== rows.length ||
      summary.firstTimestamp !== instantString(rows[0].timestamp) ||
      summary.lastTimestamp !== instantString(rows.at(-1).timestamp) ||
      summary.normalizedContentSha256 !== hashNormalizedRows(rows)) {
    throw new Error(`Persisted ${summary.dataset} rows differ from their immutable import receipt.`);
  }
  if ((expected.endpoint != null && summary.sourceEndpoint !== expected.endpoint) ||
      (expected.rangeStart != null && summary.rangeStart !== expected.rangeStart) ||
      (expected.rangeEndExclusive != null && summary.rangeEndExclusive !== expected.rangeEndExclusive) ||
      (summary.importerVersion != null && summary.importerVersion !== IMPORTER_VERSION)) {
    throw new Error(`Persisted ${summary.dataset} import receipt changed its source contract.`);
  }
  if (pages != null && (summary.pageCount !== pages.length ||
      summary.responseChainSha256 !== responseChainFingerprint(pages))) {
    throw new Error(`Persisted ${summary.dataset} raw pages differ from their immutable import receipt.`);
  }
}

function loadPersistedRawPages(db, dataset) {
  return db.prepare(`
    SELECT page_index,canonical_request,response_sha256,raw_body
    FROM fundingCrowdingRawPages WHERE dataset=? ORDER BY page_index
  `).all(dataset).map((row) => ({
    pageIndex: Number(row.page_index),
    canonicalRequest: row.canonical_request,
    responseSha256: row.response_sha256,
    rawBody: row.raw_body,
  }));
}

function responseChainFingerprint(pages) {
  const hash = createHash("sha256");
  for (let index = 0; index < pages.length; index += 1) {
    const page = pages[index];
    if (page.pageIndex !== index || page.responseSha256 !== sha256Text(page.rawBody)) {
      throw new Error(`Raw response page ${index} failed its content hash.`);
    }
    hash.update(page.canonicalRequest);
    hash.update("\0");
    hash.update(page.rawBody);
    hash.update("\0");
  }
  return hash.digest("hex");
}

function loadPersistedFundingRows(db, protocol) {
  return db.prepare(`
    SELECT timestamp,funding_rate FROM fundingRates
    WHERE symbol=? AND timestamp>=? AND timestamp<? ORDER BY timestamp
  `).all(
    protocol.sourceData.symbol,
    protocol.sourceData.developmentStart,
    protocol.sourceData.developmentEndExclusive,
  ).map((row) => ({
    timestamp: Date.parse(row.timestamp),
    fundingRate: canonicalDecimal(row.funding_rate),
  }));
}

function loadPersistedPremiumRows(db, protocol) {
  return db.prepare(`
    SELECT opened_at,open,high,low,close FROM premiumIndexBars
    WHERE symbol=? AND timeframe='M15' AND opened_at>=? AND opened_at<? ORDER BY opened_at
  `).all(
    protocol.sourceData.symbol,
    protocol.sourceData.developmentStart,
    protocol.sourceData.developmentEndExclusive,
  ).map((row) => ({
    timestamp: Date.parse(row.opened_at),
    open: canonicalDecimal(row.open),
    high: canonicalDecimal(row.high),
    low: canonicalDecimal(row.low),
    close: canonicalDecimal(row.close),
  }));
}

function candleSubsetFingerprint(db, symbol, start, end) {
  const hash = createHash("sha256");
  const statement = db.prepare(`
    SELECT symbol,timeframe,opened_at,open,high,low,close,volume,source_timestamp
    FROM marketCandles WHERE symbol=? AND timeframe IN ('M1','M5','M15')
      AND opened_at>=? AND opened_at<? ORDER BY timeframe,opened_at
  `);
  for (const row of statement.iterate(symbol, start, end)) {
    hash.update(JSON.stringify(row));
    hash.update("\n");
  }
  return hash.digest("hex");
}

async function sealDevelopmentSnapshot(targetPath, snapshotPath, db, protocol, expectedFingerprint, hashFile) {
  const expectedCandleFingerprint = candleSubsetFingerprint(
    db,
    protocol.sourceData.symbol,
    protocol.sourceData.developmentStart,
    protocol.sourceData.developmentEndExclusive,
  );
  try {
    await readFile(snapshotPath);
  } catch (error) {
    if (error?.code !== "ENOENT") throw error;
    await copyFile(targetPath, snapshotPath);
  }
  const snapshot = new DatabaseSync(snapshotPath, { readOnly: true });
  try {
    const metadata = snapshot.prepare("SELECT * FROM fundingCrowdingMetadata WHERE singleton=1").get();
    if (metadata?.protocol_id !== protocol.protocolId ||
        metadata?.protocol_sha256 !== db.prepare(
          "SELECT protocol_sha256 FROM fundingCrowdingMetadata WHERE singleton=1",
        ).get()?.protocol_sha256 ||
        metadata?.parent_result_sha256 !== db.prepare(
          "SELECT parent_result_sha256 FROM fundingCrowdingMetadata WHERE singleton=1",
        ).get()?.parent_result_sha256 ||
        metadata?.candle_database_sha256 !== protocol.sourceData.canonicalCandleDatabaseSha256 ||
        normalizedFundingFeatureFingerprint(snapshot, protocol.sourceData.symbol) !== expectedFingerprint ||
        candleSubsetFingerprint(
          snapshot,
          protocol.sourceData.symbol,
          protocol.sourceData.developmentStart,
          protocol.sourceData.developmentEndExclusive,
        ) !== expectedCandleFingerprint) {
      throw new Error("Funding crowding development snapshot does not match sealed evidence.");
    }
    validateSnapshotImports(snapshot, protocol);
    const snapshotCoverage = auditFundingCrowdingCoverage(snapshot, protocol);
    if (!snapshotCoverage.complete) throw new Error("Funding crowding sealed snapshot coverage changed.");
  } finally {
    snapshot.close();
  }
  return hashFile(snapshotPath);
}

function validateSnapshotImports(snapshot, protocol) {
  const fundingSummary = importSummary(snapshot, "funding");
  const premiumSummary = importSummary(snapshot, "premium");
  if (fundingSummary == null || premiumSummary == null) {
    throw new Error("Funding crowding snapshot is missing import receipts.");
  }
  validateExistingImport(
    fundingSummary,
    loadPersistedFundingRows(snapshot, protocol),
    loadPersistedRawPages(snapshot, "funding"),
    {
      endpoint: protocol.sourceData.fundingEndpoint,
      rangeStart: protocol.sourceData.developmentStart,
      rangeEndExclusive: protocol.sourceData.developmentEndExclusive,
    },
  );
  const firstM15 = snapshot.prepare(`
    SELECT min(opened_at) timestamp FROM marketCandles
    WHERE symbol=? AND timeframe='M15' AND opened_at>=? AND opened_at<?
  `).get(
    protocol.sourceData.symbol,
    protocol.sourceData.developmentStart,
    protocol.sourceData.developmentEndExclusive,
  ).timestamp;
  validateExistingImport(
    premiumSummary,
    loadPersistedPremiumRows(snapshot, protocol),
    loadPersistedRawPages(snapshot, "premium"),
    {
      endpoint: protocol.sourceData.premiumEndpoint,
      rangeStart: firstM15,
      rangeEndExclusive: protocol.sourceData.developmentEndExclusive,
    },
  );
}

export async function implementationFingerprint(repositoryRoot) {
  const paths = [
    "scripts/funding-crowding-protocol.mjs",
    "scripts/funding-crowding-acquire.mjs",
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

function hashNormalizedRows(rows) {
  const hash = createHash("sha256");
  for (const row of rows) {
    hash.update(JSON.stringify(row));
    hash.update("\n");
  }
  return hash.digest("hex");
}

function sha256Text(value) {
  return createHash("sha256").update(value).digest("hex");
}

function canonicalDecimal(value) {
  const number = Number(value);
  if (!Number.isFinite(number)) throw new Error(`Invalid decimal: ${value}.`);
  return number.toFixed(12).replace(/\.?0+$/, "") || "0";
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

async function writeJsonAtomic(path, value) {
  const temporary = `${path}.tmp`;
  await writeFile(temporary, `${JSON.stringify(value, null, 2)}\n`);
  await rename(temporary, path);
}

function instantString(milliseconds) {
  return new Date(milliseconds).toISOString().replace(".000Z", "Z");
}

function sleep(milliseconds) {
  return new Promise((resolvePromise) => setTimeout(resolvePromise, milliseconds));
}

const invokedPath = process.argv[1] ? pathToFileURL(resolve(process.argv[1])).href : null;
if (invokedPath === import.meta.url) {
  const options = parseArgs(process.argv.slice(2));
  const result = await acquireFundingCrowdingDevelopment(options);
  console.log(JSON.stringify({
    status: result.status,
    fundingRows: result.coverage.funding.rowCount,
    premiumRows: result.coverage.premium.rowCount,
    candleCounts: result.coverage.candleCounts,
    normalizedFeatureSha256: result.normalizedFeatureSha256,
    snapshotSha256: result.snapshotSha256,
  }));
}
