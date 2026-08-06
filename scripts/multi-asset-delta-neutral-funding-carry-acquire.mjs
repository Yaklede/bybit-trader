#!/usr/bin/env node

import { createHash } from "node:crypto";
import { createReadStream, rmSync } from "node:fs";
import { copyFile, mkdir, readFile, rename, stat, writeFile } from "node:fs/promises";
import { DatabaseSync } from "node:sqlite";
import { dirname, resolve } from "node:path";
import { pathToFileURL } from "node:url";

import {
  fetchReversePages,
  normalizeFundingRows,
  normalizeKlineRows,
  verifyExactIntervalCoverage,
} from "./delta-neutral-funding-carry-acquire.mjs";
import {
  loadMultiAssetDeltaNeutralFundingCarryProtocol,
} from "./multi-asset-delta-neutral-funding-carry-protocol.mjs";

const DEFAULT_PROTOCOL = "config/bybit-multi-asset-delta-neutral-funding-carry-development-v1.json";
const KLINE_INTERVAL_MILLIS = 5 * 60 * 1_000;
const FUNDING_INTERVAL_MILLIS = 8 * 60 * 60 * 1_000;
const IMPORTER_VERSION = "multi-asset-delta-neutral-funding-carry-development-v1";
const BAR_SERIES = ["SPOT_LAST", "PERPETUAL_LAST", "PERPETUAL_MARK", "PERPETUAL_INDEX"];

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
  const requestDelayMs = Number(values.get("request-delay-ms") ?? 175);
  if (!Number.isInteger(requestDelayMs) || requestDelayMs < 0) {
    throw new Error("request-delay-ms must be a non-negative integer.");
  }
  return {
    protocol: resolve(values.get("protocol") ?? DEFAULT_PROTOCOL),
    report: values.has("report") ? resolve(values.get("report")) : null,
    requestDelayMs,
  };
}

export async function acquireMultiAssetDeltaNeutralFundingCarryDevelopment(options, dependencies = {}) {
  const loaded = await loadMultiAssetDeltaNeutralFundingCarryProtocol(options.protocol);
  const protocol = loaded.protocol;
  const repositoryRoot = resolve(dirname(options.protocol), "..");
  const targetDatabasePath = resolve(repositoryRoot, protocol.sourceData.researchDatabase);
  const snapshotPath = resolve(repositoryRoot, `build/research/${protocol.protocolId}-snapshot.sqlite`);
  const reportPath = options.report ?? resolve(
    repositoryRoot,
    `build/research/${protocol.protocolId}-acquisition.json`,
  );
  await mkdir(dirname(targetDatabasePath), { recursive: true });
  await mkdir(dirname(reportPath), { recursive: true });
  const db = dependencies.database ?? new DatabaseSync(targetDatabasePath);
  const ownsDatabase = dependencies.database == null;
  const now = dependencies.now ?? (() => new Date().toISOString());
  const log = dependencies.log ?? console.log;
  const request = createPublicRequester(
    dependencies.fetchImpl ?? fetch,
    protocol.sourceData.baseUrl,
    options.requestDelayMs,
    dependencies.wait,
  );
  let result;
  try {
    ensureMultiAssetDeltaNeutralFundingCarrySchema(db);
    bindMultiAssetDeltaNeutralFundingCarryDatabase(db, {
      protocolId: protocol.protocolId,
      protocolSha256: loaded.sha256,
      parentResultSha256: loaded.parentResultSha256,
      boundAt: now(),
    });
    const imports = [];
    for (const definition of multiAssetDatasetDefinitions(protocol)) {
      imports.push(await importDatasetIfMissing(db, protocol, definition, request, now, log));
    }
    const coverage = auditMultiAssetDeltaNeutralFundingCarryCoverage(db, protocol);
    if (!coverage.complete) {
      throw new Error(`Multi-asset carry coverage failed: ${coverage.failures.join("; ")}.`);
    }
    const normalizedEvidenceSha256 = normalizedMultiAssetEvidenceFingerprint(db, protocol);
    db.prepare(`
      UPDATE multiAssetMetadata SET normalized_evidence_sha256=? WHERE singleton=1
    `).run(normalizedEvidenceSha256);
    const boundAt = db.prepare("SELECT bound_at FROM multiAssetMetadata WHERE singleton=1").get().bound_at;
    db.exec("PRAGMA wal_checkpoint(TRUNCATE)");
    const snapshotSha256 = await sealSnapshot(
      targetDatabasePath,
      snapshotPath,
      normalizedEvidenceSha256,
      dependencies.hashFile ?? sha256File,
    );
    result = {
      schemaVersion: 1,
      protocolId: protocol.protocolId,
      protocolSha256: loaded.sha256,
      parentResultSha256: loaded.parentResultSha256,
      implementationSha256: await implementationFingerprint(repositoryRoot),
      status: "COMPLETE_MULTI_ASSET_DEVELOPMENT_EVIDENCE_SEALED",
      developmentRange: {
        startAt: protocol.sourceData.developmentStart,
        endExclusive: protocol.sourceData.developmentEndExclusive,
      },
      imports,
      coverage,
      normalizedEvidenceSha256,
      snapshot: `build/research/${protocol.protocolId}-snapshot.sqlite`,
      snapshotSha256,
      internalValidation2024Read: false,
      external2025Read: false,
      sealed2026Read: false,
      freshForwardSealRead: false,
      generatedAt: boundAt,
      automaticExecutionAllowed: false,
      liveExecutionAllowed: false,
    };
    await writeJsonAtomic(reportPath, result);
  } finally {
    if (ownsDatabase) db.close();
  }
  return result;
}

export function ensureMultiAssetDeltaNeutralFundingCarrySchema(db) {
  db.exec(`
    PRAGMA journal_mode=WAL;
    PRAGMA synchronous=NORMAL;
    PRAGMA busy_timeout=30000;
    CREATE TABLE IF NOT EXISTS multiAssetMetadata (
      singleton INTEGER NOT NULL PRIMARY KEY CHECK(singleton=1),
      protocol_id TEXT NOT NULL,
      protocol_sha256 TEXT NOT NULL,
      parent_result_sha256 TEXT NOT NULL,
      normalized_evidence_sha256 TEXT,
      bound_at TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS marketBars (
      symbol TEXT NOT NULL,
      series TEXT NOT NULL,
      opened_at TEXT NOT NULL,
      open TEXT NOT NULL,
      high TEXT NOT NULL,
      low TEXT NOT NULL,
      close TEXT NOT NULL,
      volume TEXT,
      turnover TEXT,
      PRIMARY KEY(symbol, series, opened_at)
    );
    CREATE TABLE IF NOT EXISTS fundingRates (
      symbol TEXT NOT NULL,
      timestamp TEXT NOT NULL,
      funding_rate TEXT NOT NULL,
      PRIMARY KEY(symbol, timestamp)
    );
    CREATE TABLE IF NOT EXISTS multiAssetImports (
      dataset TEXT NOT NULL PRIMARY KEY,
      symbol TEXT NOT NULL,
      series TEXT,
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
    CREATE TABLE IF NOT EXISTS multiAssetRawPages (
      dataset TEXT NOT NULL,
      page_index INTEGER NOT NULL,
      canonical_request TEXT NOT NULL,
      response_sha256 TEXT NOT NULL,
      raw_body TEXT NOT NULL,
      PRIMARY KEY(dataset, page_index)
    );
  `);
}

export function bindMultiAssetDeltaNeutralFundingCarryDatabase(db, binding) {
  const existing = db.prepare("SELECT * FROM multiAssetMetadata WHERE singleton=1").get();
  if (existing == null) {
    db.prepare(`
      INSERT INTO multiAssetMetadata(
        singleton,protocol_id,protocol_sha256,parent_result_sha256,bound_at
      ) VALUES (1,?,?,?,?)
    `).run(binding.protocolId, binding.protocolSha256, binding.parentResultSha256, binding.boundAt);
    return;
  }
  if (existing.protocol_id !== binding.protocolId || existing.protocol_sha256 !== binding.protocolSha256 ||
      existing.parent_result_sha256 !== binding.parentResultSha256) {
    throw new Error("Multi-asset database is bound to different evidence.");
  }
}

export function multiAssetDatasetDefinitions(protocol) {
  const definitions = [];
  for (const symbol of protocol.sourceData.symbols) {
    definitions.push(
      dataset(symbol, "spot_last", "BAR", "SPOT_LAST", "spot", protocol.sourceData.spotKlineEndpoint),
      dataset(symbol, "perpetual_last", "BAR", "PERPETUAL_LAST", "linear", protocol.sourceData.perpetualKlineEndpoint),
      dataset(symbol, "perpetual_mark", "BAR", "PERPETUAL_MARK", "linear", protocol.sourceData.markKlineEndpoint),
      dataset(symbol, "perpetual_index", "BAR", "PERPETUAL_INDEX", "linear", protocol.sourceData.indexKlineEndpoint),
      dataset(symbol, "funding", "FUNDING", null, "linear", protocol.sourceData.fundingEndpoint),
    );
  }
  return definitions;
}

export function auditMultiAssetDeltaNeutralFundingCarryCoverage(db, protocol) {
  const start = protocol.sourceData.developmentStart;
  const end = protocol.sourceData.developmentEndExclusive;
  const startMillis = Date.parse(start);
  const endMillis = Date.parse(end);
  const expectedM5Rows = Math.floor((endMillis - startMillis) / KLINE_INTERVAL_MILLIS);
  const failures = [];
  const symbols = {};
  for (const symbol of protocol.sourceData.symbols) {
    const series = {};
    for (const name of BAR_SERIES) {
      const summary = db.prepare(`
        SELECT count(*) row_count,min(opened_at) first_timestamp,max(opened_at) last_timestamp,
          sum(CASE WHEN (unixepoch(opened_at) * 1000) % ? = 0 THEN 0 ELSE 1 END) off_grid_count,
          sum(CASE WHEN CAST(open AS REAL)<=0 OR CAST(high AS REAL)<=0 OR CAST(low AS REAL)<=0 OR
            CAST(close AS REAL)<=0 OR CAST(high AS REAL)<CAST(low AS REAL) THEN 1 ELSE 0 END)
            invalid_price_count
        FROM marketBars WHERE symbol=? AND series=? AND opened_at>=? AND opened_at<?
      `).get(KLINE_INTERVAL_MILLIS, symbol, name, start, end);
      const rowCount = Number(summary.row_count);
      const offGridCount = Number(summary.off_grid_count);
      const invalidPriceCount = Number(summary.invalid_price_count);
      if (rowCount !== expectedM5Rows || summary.first_timestamp !== start ||
          summary.last_timestamp !== instantString(endMillis - KLINE_INTERVAL_MILLIS) ||
          offGridCount !== 0 || invalidPriceCount !== 0) {
        failures.push(`${symbol} ${name} is incomplete, off-grid, or contains invalid prices.`);
      }
      series[name] = {
        rowCount,
        expectedRows: expectedM5Rows,
        firstTimestamp: summary.first_timestamp,
        lastTimestamp: summary.last_timestamp,
        offGridCount,
        invalidPriceCount,
      };
    }
    const matchingM5Rows = Number(db.prepare(`
      SELECT count(*) count FROM marketBars spot
      JOIN marketBars perpetual ON perpetual.symbol=spot.symbol AND
        perpetual.series='PERPETUAL_LAST' AND perpetual.opened_at=spot.opened_at
      JOIN marketBars mark ON mark.symbol=spot.symbol AND
        mark.series='PERPETUAL_MARK' AND mark.opened_at=spot.opened_at
      JOIN marketBars idx ON idx.symbol=spot.symbol AND
        idx.series='PERPETUAL_INDEX' AND idx.opened_at=spot.opened_at
      WHERE spot.symbol=? AND spot.series='SPOT_LAST' AND spot.opened_at>=? AND spot.opened_at<?
    `).get(symbol, start, end).count);
    if (matchingM5Rows !== expectedM5Rows) {
      failures.push(`${symbol} four M5 series do not share one exact timeline.`);
    }
    const funding = db.prepare(`
      SELECT timestamp,funding_rate FROM fundingRates
      WHERE symbol=? AND timestamp>=? AND timestamp<? ORDER BY timestamp
    `).all(symbol, start, end).map((row) => ({
      timestamp: Date.parse(row.timestamp),
      fundingRate: Number(row.funding_rate),
    }));
    const fundingCoverage = verifyExactIntervalCoverage(
      funding,
      FUNDING_INTERVAL_MILLIS,
      startMillis,
      endMillis,
      `${symbol} FUNDING`,
    );
    failures.push(...fundingCoverage.failures);
    if (funding.some((row) => !Number.isFinite(row.fundingRate))) {
      failures.push(`${symbol} funding contains a non-numeric rate.`);
    }
    const exists = db.prepare(
      "SELECT 1 present FROM marketBars WHERE symbol=? AND series=? AND opened_at=?",
    );
    const missingDecisionInputs = [];
    for (const settlement of funding) {
      if (settlement.timestamp === startMillis) continue;
      const decisionBar = instantString(settlement.timestamp - KLINE_INTERVAL_MILLIS);
      const entryBar = instantString(settlement.timestamp + KLINE_INTERVAL_MILLIS);
      for (const name of BAR_SERIES) {
        if (exists.get(symbol, name, decisionBar) == null ||
            (settlement.timestamp + KLINE_INTERVAL_MILLIS < endMillis &&
              exists.get(symbol, name, entryBar) == null)) {
          missingDecisionInputs.push({
            fundingTimestamp: instantString(settlement.timestamp),
            series: name,
          });
        }
      }
    }
    if (missingDecisionInputs.length > 0) {
      failures.push(`${symbol} funding settlements lack closed or entry M5 bars.`);
    }
    symbols[symbol] = {
      series,
      matchingM5Rows,
      expectedM5Rows,
      funding: {
        rowCount: funding.length,
        expectedRows: fundingCoverage.expectedRows,
        firstTimestamp: funding[0] == null ? null : instantString(funding[0].timestamp),
        lastTimestamp: funding.at(-1) == null ? null : instantString(funding.at(-1).timestamp),
      },
      missingDecisionInputCount: missingDecisionInputs.length,
      missingDecisionInputExamples: missingDecisionInputs.slice(0, 20),
    };
  }
  return {
    complete: failures.length === 0,
    failures,
    expectedM5RowsPerSeries: expectedM5Rows,
    expectedFundingRowsPerSymbol: Math.floor((endMillis - startMillis) / FUNDING_INTERVAL_MILLIS),
    symbols,
    totalMatchingM5Rows: Object.values(symbols).reduce((sum, value) => sum + value.matchingM5Rows, 0),
    totalFundingRows: Object.values(symbols).reduce((sum, value) => sum + value.funding.rowCount, 0),
    missingDecisionInputCount: Object.values(symbols)
      .reduce((sum, value) => sum + value.missingDecisionInputCount, 0),
  };
}

export function normalizedMultiAssetEvidenceFingerprint(db, protocol) {
  const hash = createHash("sha256");
  for (const symbol of protocol.sourceData.symbols) {
    for (const series of BAR_SERIES) {
      forEachPagedRow(db, {
        sql: `SELECT symbol,series,opened_at,open,high,low,close,volume,turnover FROM marketBars
          WHERE symbol=? AND series=? AND opened_at>? ORDER BY opened_at LIMIT ?`,
        params: [symbol, series],
        cursorColumn: "opened_at",
        onRow: (row) => hash.update(`${JSON.stringify(row)}\n`),
      });
    }
    forEachPagedRow(db, {
      sql: `SELECT symbol,timestamp,funding_rate FROM fundingRates
        WHERE symbol=? AND timestamp>? ORDER BY timestamp LIMIT ?`,
      params: [symbol],
      cursorColumn: "timestamp",
      onRow: (row) => hash.update(`${JSON.stringify(row)}\n`),
    });
  }
  return hash.digest("hex");
}

export function validateExistingImport(summary, rows, pages, expected) {
  if (rows.length === 0 || summary.rowCount !== rows.length ||
      summary.firstTimestamp !== instantString(rows[0].timestamp) ||
      summary.lastTimestamp !== instantString(rows.at(-1).timestamp) ||
      summary.normalizedContentSha256 !== hashNormalizedRows(rows)) {
    throw new Error(`Persisted ${summary.dataset} rows differ from their immutable import receipt.`);
  }
  if (summary.symbol !== expected.symbol || summary.series !== expected.series ||
      summary.sourceEndpoint !== expected.endpoint || summary.rangeStart !== expected.rangeStart ||
      summary.rangeEndExclusive !== expected.rangeEndExclusive || summary.importerVersion !== IMPORTER_VERSION) {
    throw new Error(`Persisted ${summary.dataset} import receipt changed its source contract.`);
  }
  if (summary.pageCount !== pages.length || summary.responseChainSha256 !== responseChainFingerprint(pages)) {
    throw new Error(`Persisted ${summary.dataset} raw pages differ from their immutable import receipt.`);
  }
}

async function importDatasetIfMissing(db, protocol, definition, request, now, log) {
  const existing = importSummary(db, definition.dataset);
  if (existing != null) {
    const rows = definition.kind === "FUNDING"
      ? loadPersistedFundingRows(db, definition.symbol, protocol)
      : loadPersistedBarRows(db, definition.symbol, definition.series, protocol);
    const pages = loadPersistedRawPages(db, definition.dataset);
    validateExistingImport(existing, rows, pages, {
      symbol: definition.symbol,
      series: definition.series,
      endpoint: definition.sourceEndpoint,
      rangeStart: protocol.sourceData.developmentStart,
      rangeEndExclusive: protocol.sourceData.developmentEndExclusive,
    });
    return existing;
  }
  log(`multi-asset acquisition started dataset=${definition.dataset}`);
  const startMillis = Date.parse(protocol.sourceData.developmentStart);
  const endExclusiveMillis = Date.parse(protocol.sourceData.developmentEndExclusive);
  const params = definition.kind === "FUNDING"
    ? {
        category: "linear",
        symbol: definition.symbol,
        startTime: startMillis,
        limit: protocol.sourceData.requestLimit.funding,
      }
    : {
        category: definition.category,
        symbol: definition.symbol,
        interval: protocol.sourceData.klineInterval,
        start: startMillis,
        limit: protocol.sourceData.requestLimit.kline,
      };
  const acquired = await fetchReversePages({
    endpoint: definition.requestEndpoint,
    params,
    endExclusiveMillis,
    request,
    rows: (result) => result.list ?? [],
    timestamp: definition.kind === "FUNDING"
      ? (row) => Number(row.fundingRateTimestamp)
      : (row) => Number(row[0]),
  });
  const rows = definition.kind === "FUNDING"
    ? normalizeFundingRows(acquired.rows, startMillis, endExclusiveMillis)
    : normalizeKlineRows(acquired.rows, startMillis, endExclusiveMillis, definition.dataset);
  const coverage = verifyExactIntervalCoverage(
    rows,
    definition.kind === "FUNDING" ? FUNDING_INTERVAL_MILLIS : KLINE_INTERVAL_MILLIS,
    startMillis,
    endExclusiveMillis,
    definition.dataset,
  );
  if (!coverage.complete) throw new Error(coverage.failures.join("; "));
  inTransaction(db, () => {
    if (definition.kind === "FUNDING") replaceFundingRows(db, definition.symbol, protocol, rows);
    else replaceBarRows(db, definition.symbol, definition.series, protocol, rows);
    insertRawPages(db, definition.dataset, acquired.rawPages);
    insertImport(db, {
      dataset: definition.dataset,
      symbol: definition.symbol,
      series: definition.series,
      endpoint: definition.sourceEndpoint,
      startAt: protocol.sourceData.developmentStart,
      endExclusive: protocol.sourceData.developmentEndExclusive,
      rows,
      pages: acquired.rawPages,
      responseChainSha256: acquired.responseChainSha256,
      contentSha256: hashNormalizedRows(rows),
      importedAt: now(),
    });
  });
  log(`multi-asset acquisition completed dataset=${definition.dataset} rows=${rows.length}`);
  return importSummary(db, definition.dataset);
}

function dataset(symbol, name, kind, series, category, sourceEndpoint) {
  return {
    dataset: `${symbol.toLowerCase()}_${name}`,
    symbol,
    kind,
    series,
    category,
    sourceEndpoint,
    requestEndpoint: sourceEndpoint.split("?")[0],
  };
}

function replaceBarRows(db, symbol, series, protocol, rows) {
  db.prepare(`
    DELETE FROM marketBars WHERE symbol=? AND series=? AND opened_at>=? AND opened_at<?
  `).run(symbol, series, protocol.sourceData.developmentStart, protocol.sourceData.developmentEndExclusive);
  const insert = db.prepare(`
    INSERT INTO marketBars(symbol,series,opened_at,open,high,low,close,volume,turnover)
    VALUES (?,?,?,?,?,?,?,?,?)
  `);
  for (const row of rows) {
    insert.run(
      symbol,
      series,
      instantString(row.timestamp),
      row.open,
      row.high,
      row.low,
      row.close,
      row.volume,
      row.turnover,
    );
  }
}

function replaceFundingRows(db, symbol, protocol, rows) {
  db.prepare("DELETE FROM fundingRates WHERE symbol=? AND timestamp>=? AND timestamp<?").run(
    symbol,
    protocol.sourceData.developmentStart,
    protocol.sourceData.developmentEndExclusive,
  );
  const insert = db.prepare("INSERT INTO fundingRates(symbol,timestamp,funding_rate) VALUES (?,?,?)");
  for (const row of rows) insert.run(symbol, instantString(row.timestamp), row.fundingRate);
}

function insertImport(db, value) {
  db.prepare(`
    INSERT INTO multiAssetImports(
      dataset,symbol,series,source_endpoint,range_start,range_end_exclusive,page_count,row_count,
      first_timestamp,last_timestamp,response_chain_sha256,normalized_content_sha256,
      imported_at,importer_version
    ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
  `).run(
    value.dataset,
    value.symbol,
    value.series,
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

function insertRawPages(db, datasetName, pages) {
  db.prepare("DELETE FROM multiAssetRawPages WHERE dataset=?").run(datasetName);
  const insert = db.prepare(`
    INSERT INTO multiAssetRawPages(dataset,page_index,canonical_request,response_sha256,raw_body)
    VALUES (?,?,?,?,?)
  `);
  for (const page of pages) {
    insert.run(datasetName, page.pageIndex, page.canonicalRequest, page.responseSha256, page.rawBody);
  }
}

function importSummary(db, datasetName) {
  const row = db.prepare("SELECT * FROM multiAssetImports WHERE dataset=?").get(datasetName);
  if (row == null) return null;
  return {
    dataset: row.dataset,
    symbol: row.symbol,
    series: row.series,
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

function loadPersistedBarRows(db, symbol, series, protocol) {
  return db.prepare(`
    SELECT opened_at,open,high,low,close,volume,turnover FROM marketBars
    WHERE symbol=? AND series=? AND opened_at>=? AND opened_at<? ORDER BY opened_at
  `).all(
    symbol,
    series,
    protocol.sourceData.developmentStart,
    protocol.sourceData.developmentEndExclusive,
  ).map((row) => ({
    timestamp: Date.parse(row.opened_at),
    open: row.open,
    high: row.high,
    low: row.low,
    close: row.close,
    volume: row.volume,
    turnover: row.turnover,
  }));
}

function loadPersistedFundingRows(db, symbol, protocol) {
  return db.prepare(`
    SELECT timestamp,funding_rate FROM fundingRates
    WHERE symbol=? AND timestamp>=? AND timestamp<? ORDER BY timestamp
  `).all(
    symbol,
    protocol.sourceData.developmentStart,
    protocol.sourceData.developmentEndExclusive,
  ).map((row) => ({ timestamp: Date.parse(row.timestamp), fundingRate: row.funding_rate }));
}

function loadPersistedRawPages(db, datasetName) {
  return db.prepare(`
    SELECT page_index,canonical_request,response_sha256,raw_body
    FROM multiAssetRawPages WHERE dataset=? ORDER BY page_index
  `).all(datasetName).map((row) => {
    if (sha256Text(row.raw_body) !== row.response_sha256) {
      throw new Error(`Persisted ${datasetName} raw page ${row.page_index} content hash changed.`);
    }
    return {
      pageIndex: Number(row.page_index),
      canonicalRequest: row.canonical_request,
      responseSha256: row.response_sha256,
      rawBody: row.raw_body,
    };
  });
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
        throw new Error(
          `Bybit public request failed ${canonicalRequest}: HTTP ${response.status} code=${payload?.retCode}.`,
        );
      }
      await wait(Math.min(10_000, 500 * 2 ** attempt));
    }
    throw new Error(`Bybit public request exhausted retries: ${canonicalRequest}.`);
  };
}

function hashNormalizedRows(rows) {
  const hash = createHash("sha256");
  for (const row of rows) hash.update(`${JSON.stringify(row)}\n`);
  return hash.digest("hex");
}

function responseChainFingerprint(pages) {
  const hash = createHash("sha256");
  for (const page of pages) {
    hash.update(page.canonicalRequest);
    hash.update("\0");
    hash.update(page.rawBody);
    hash.update("\0");
  }
  return hash.digest("hex");
}

function forEachPagedRow(db, options) {
  let cursor = "";
  while (true) {
    const rows = db.prepare(options.sql).all(...options.params, cursor, 10_000);
    for (const row of rows) options.onRow(row);
    if (rows.length < 10_000) break;
    cursor = rows.at(-1)[options.cursorColumn];
  }
}

function inTransaction(db, operation) {
  db.exec("BEGIN IMMEDIATE");
  try {
    operation();
    db.exec("COMMIT");
  } catch (error) {
    db.exec("ROLLBACK");
    throw error;
  }
}

async function sealSnapshot(targetPath, snapshotPath, expectedFingerprint, hashFile) {
  try {
    await stat(snapshotPath);
    const targetSha256 = await hashFile(targetPath);
    const snapshotSha256 = await hashFile(snapshotPath);
    if (targetSha256 !== snapshotSha256) {
      throw new Error("Existing multi-asset snapshot differs from its source database.");
    }
    verifySnapshotFingerprint(snapshotPath, expectedFingerprint);
    return snapshotSha256;
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }
  const temporaryPath = `${snapshotPath}.tmp-${process.pid}`;
  await copyFile(targetPath, temporaryPath);
  verifySnapshotFingerprint(temporaryPath, expectedFingerprint);
  await rename(temporaryPath, snapshotPath);
  return hashFile(snapshotPath);
}

function verifySnapshotFingerprint(path, expectedFingerprint) {
  const snapshot = new DatabaseSync(path, { readOnly: true });
  try {
    const row = snapshot.prepare(`
      SELECT normalized_evidence_sha256 FROM multiAssetMetadata WHERE singleton=1
    `).get();
    if (row?.normalized_evidence_sha256 !== expectedFingerprint) {
      throw new Error("Multi-asset snapshot fingerprint does not match normalized evidence.");
    }
  } finally {
    snapshot.close();
    rmSync(`${path}-shm`, { force: true });
    rmSync(`${path}-wal`, { force: true });
  }
}

export async function implementationFingerprint(repositoryRoot) {
  const hash = createHash("sha256");
  for (const relativePath of [
    "scripts/multi-asset-delta-neutral-funding-carry-protocol.mjs",
    "scripts/multi-asset-delta-neutral-funding-carry-acquire.mjs",
    "scripts/delta-neutral-funding-carry-acquire.mjs",
  ]) {
    hash.update(relativePath);
    hash.update("\0");
    hash.update(await readFile(resolve(repositoryRoot, relativePath)));
    hash.update("\0");
  }
  return hash.digest("hex");
}

async function sha256File(path) {
  const hash = createHash("sha256");
  for await (const chunk of createReadStream(path)) hash.update(chunk);
  return hash.digest("hex");
}

async function writeJsonAtomic(path, value) {
  const temporaryPath = `${path}.tmp-${process.pid}`;
  await writeFile(temporaryPath, `${JSON.stringify(value, null, 2)}\n`);
  await rename(temporaryPath, path);
}

function sha256Text(value) {
  return createHash("sha256").update(value).digest("hex");
}

function instantString(timestamp) {
  return new Date(timestamp).toISOString().replace(".000Z", "Z");
}

function sleep(milliseconds) {
  return new Promise((resolvePromise) => setTimeout(resolvePromise, milliseconds));
}

const invokedPath = process.argv[1] == null ? null : pathToFileURL(resolve(process.argv[1])).href;
if (invokedPath === import.meta.url) {
  acquireMultiAssetDeltaNeutralFundingCarryDevelopment(parseArgs(process.argv.slice(2)))
    .then((result) => {
      console.log(JSON.stringify(result, null, 2));
    })
    .catch((error) => {
      console.error(error.stack ?? error.message);
      process.exitCode = 1;
    });
}
