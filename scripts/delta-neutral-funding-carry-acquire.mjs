#!/usr/bin/env node

import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import { copyFile, mkdir, readFile, rename, stat, writeFile } from "node:fs/promises";
import { DatabaseSync } from "node:sqlite";
import { dirname, resolve } from "node:path";
import { pathToFileURL } from "node:url";

import { loadDeltaNeutralFundingCarryProtocol } from "./delta-neutral-funding-carry-protocol.mjs";

const DEFAULT_PROTOCOL = "config/bybit-delta-neutral-funding-carry-development-v1.json";
const KLINE_INTERVAL_MILLIS = 5 * 60 * 1_000;
const FUNDING_INTERVAL_MILLIS = 8 * 60 * 60 * 1_000;
const IMPORTER_VERSION = "delta-neutral-funding-carry-development-v1";
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

export async function acquireDeltaNeutralFundingCarryDevelopment(options, dependencies = {}) {
  const loaded = await loadDeltaNeutralFundingCarryProtocol(options.protocol);
  const protocol = loaded.protocol;
  const repositoryRoot = resolve(dirname(options.protocol), "..");
  const targetDatabasePath = resolve(repositoryRoot, protocol.sourceData.researchDatabase);
  const snapshotPath = resolve(repositoryRoot, `build/research/${protocol.protocolId}-development.sqlite`);
  const reportPath = options.report ?? resolve(
    repositoryRoot,
    `build/research/${protocol.protocolId}-development-acquisition.json`,
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
    ensureDeltaNeutralFundingCarrySchema(db);
    bindDeltaNeutralFundingCarryDatabase(db, {
      protocolId: protocol.protocolId,
      protocolSha256: loaded.sha256,
      parentResultSha256: loaded.parentResultSha256,
      boundAt: now(),
    });
    const imports = [];
    for (const definition of datasetDefinitions(protocol)) {
      imports.push(await importDatasetIfMissing(db, protocol, definition, request, now, log));
    }
    const coverage = auditDeltaNeutralFundingCarryCoverage(db, protocol);
    if (!coverage.complete) {
      throw new Error(`Delta-neutral funding carry coverage failed: ${coverage.failures.join("; ")}.`);
    }
    const normalizedEvidenceSha256 = normalizedDeltaNeutralEvidenceFingerprint(db, protocol);
    db.prepare(`
      UPDATE deltaNeutralMetadata SET normalized_evidence_sha256=? WHERE singleton=1
    `).run(normalizedEvidenceSha256);
    db.exec("PRAGMA wal_checkpoint(TRUNCATE)");
    const snapshotSha256 = await sealDevelopmentSnapshot(
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
      status: "COMPLETE_DELTA_NEUTRAL_DEVELOPMENT_EVIDENCE_SEALED",
      developmentRange: {
        startAt: protocol.sourceData.developmentStart,
        endExclusive: protocol.sourceData.developmentEndExclusive,
      },
      imports,
      coverage,
      normalizedEvidenceSha256,
      snapshot: `build/research/${protocol.protocolId}-development.sqlite`,
      snapshotSha256,
      internalValidation2024Read: false,
      external2025Read: false,
      sealed2026Read: false,
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

export function ensureDeltaNeutralFundingCarrySchema(db) {
  db.exec(`
    PRAGMA journal_mode=WAL;
    PRAGMA synchronous=NORMAL;
    PRAGMA busy_timeout=30000;
    CREATE TABLE IF NOT EXISTS deltaNeutralMetadata (
      singleton INTEGER NOT NULL PRIMARY KEY CHECK(singleton=1),
      protocol_id TEXT NOT NULL,
      protocol_sha256 TEXT NOT NULL,
      parent_result_sha256 TEXT NOT NULL,
      normalized_evidence_sha256 TEXT,
      bound_at TEXT NOT NULL
    );
    CREATE TABLE IF NOT EXISTS marketBars (
      series TEXT NOT NULL,
      opened_at TEXT NOT NULL,
      open TEXT NOT NULL,
      high TEXT NOT NULL,
      low TEXT NOT NULL,
      close TEXT NOT NULL,
      volume TEXT,
      turnover TEXT,
      PRIMARY KEY(series, opened_at)
    );
    CREATE TABLE IF NOT EXISTS fundingRates (
      symbol TEXT NOT NULL,
      timestamp TEXT NOT NULL,
      funding_rate TEXT NOT NULL,
      PRIMARY KEY(symbol, timestamp)
    );
    CREATE TABLE IF NOT EXISTS deltaNeutralImports (
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
    CREATE TABLE IF NOT EXISTS deltaNeutralRawPages (
      dataset TEXT NOT NULL,
      page_index INTEGER NOT NULL,
      canonical_request TEXT NOT NULL,
      response_sha256 TEXT NOT NULL,
      raw_body TEXT NOT NULL,
      PRIMARY KEY(dataset, page_index)
    );
  `);
}

export function bindDeltaNeutralFundingCarryDatabase(db, binding) {
  const existing = db.prepare("SELECT * FROM deltaNeutralMetadata WHERE singleton=1").get();
  if (existing == null) {
    db.prepare(`
      INSERT INTO deltaNeutralMetadata(
        singleton,protocol_id,protocol_sha256,parent_result_sha256,bound_at
      ) VALUES (1,?,?,?,?)
    `).run(binding.protocolId, binding.protocolSha256, binding.parentResultSha256, binding.boundAt);
    return;
  }
  if (existing.protocol_id !== binding.protocolId || existing.protocol_sha256 !== binding.protocolSha256 ||
      existing.parent_result_sha256 !== binding.parentResultSha256) {
    throw new Error("Delta-neutral database is bound to different evidence.");
  }
}

export function normalizeKlineRows(rows, startMillis, endExclusiveMillis, label) {
  return normalizeTimestampRows(
    rows,
    (row) => Number(row[0]),
    (row, timestamp) => ({
      timestamp,
      open: canonicalDecimal(row[1]),
      high: canonicalDecimal(row[2]),
      low: canonicalDecimal(row[3]),
      close: canonicalDecimal(row[4]),
      volume: row[5] == null ? null : canonicalDecimal(row[5]),
      turnover: row[6] == null ? null : canonicalDecimal(row[6]),
    }),
    startMillis,
    endExclusiveMillis,
    label,
  );
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

export function verifyExactIntervalCoverage(rows, expectedIntervalMillis, startMillis, endExclusiveMillis, label) {
  const failures = [];
  const expectedRows = Math.floor((endExclusiveMillis - startMillis) / expectedIntervalMillis);
  if (rows.length !== expectedRows) failures.push(`${label} has ${rows.length} rows, expected ${expectedRows}.`);
  if (rows[0]?.timestamp !== startMillis) failures.push(`${label} does not start at the declared boundary.`);
  if (rows.at(-1)?.timestamp !== endExclusiveMillis - expectedIntervalMillis) {
    failures.push(`${label} does not end at the declared boundary.`);
  }
  for (let index = 1; index < rows.length; index += 1) {
    if (rows[index].timestamp - rows[index - 1].timestamp !== expectedIntervalMillis) {
      failures.push(`${label} has a gap before ${instantString(rows[index].timestamp)}.`);
      if (failures.length >= 20) break;
    }
  }
  return { complete: failures.length === 0, failures, rowCount: rows.length, expectedRows };
}

export function auditDeltaNeutralFundingCarryCoverage(db, protocol) {
  const start = protocol.sourceData.developmentStart;
  const end = protocol.sourceData.developmentEndExclusive;
  const startMillis = Date.parse(start);
  const endMillis = Date.parse(end);
  const failures = [];
  const series = {};
  for (const name of BAR_SERIES) {
    const summary = db.prepare(`
      SELECT count(*) row_count,min(opened_at) first_timestamp,max(opened_at) last_timestamp,
        sum(CASE WHEN (unixepoch(opened_at) * 1000) % ? = 0 THEN 0 ELSE 1 END) off_grid_count,
        sum(CASE WHEN CAST(open AS REAL)<=0 OR CAST(high AS REAL)<=0 OR CAST(low AS REAL)<=0 OR
          CAST(close AS REAL)<=0 OR CAST(high AS REAL)<CAST(low AS REAL) THEN 1 ELSE 0 END) invalid_price_count
      FROM marketBars WHERE series=? AND opened_at>=? AND opened_at<?
    `).get(KLINE_INTERVAL_MILLIS, name, start, end);
    const expectedRows = Math.floor((endMillis - startMillis) / KLINE_INTERVAL_MILLIS);
    const rowCount = Number(summary.row_count);
    const offGridCount = Number(summary.off_grid_count);
    const invalidPriceCount = Number(summary.invalid_price_count);
    if (rowCount !== expectedRows || summary.first_timestamp !== start ||
        summary.last_timestamp !== instantString(endMillis - KLINE_INTERVAL_MILLIS) ||
        offGridCount !== 0 || invalidPriceCount !== 0) {
      failures.push(`${name} is incomplete, off-grid, or contains invalid prices.`);
    }
    series[name] = {
      rowCount,
      expectedRows,
      firstTimestamp: summary.first_timestamp,
      lastTimestamp: summary.last_timestamp,
      offGridCount,
      invalidPriceCount,
    };
  }
  const matchingBarCount = Number(db.prepare(`
    SELECT count(*) count FROM marketBars spot
    JOIN marketBars perpetual ON perpetual.series='PERPETUAL_LAST' AND perpetual.opened_at=spot.opened_at
    JOIN marketBars mark ON mark.series='PERPETUAL_MARK' AND mark.opened_at=spot.opened_at
    JOIN marketBars idx ON idx.series='PERPETUAL_INDEX' AND idx.opened_at=spot.opened_at
    WHERE spot.series='SPOT_LAST' AND spot.opened_at>=? AND spot.opened_at<?
  `).get(start, end).count);
  const expectedMatchingBarCount = Math.floor((endMillis - startMillis) / KLINE_INTERVAL_MILLIS);
  if (matchingBarCount !== expectedMatchingBarCount) failures.push("The four M5 series do not share one exact timeline.");

  const funding = db.prepare(`
    SELECT timestamp,funding_rate FROM fundingRates
    WHERE symbol=? AND timestamp>=? AND timestamp<? ORDER BY timestamp
  `).all(protocol.sourceData.perpetualSymbol, start, end).map((row) => ({
    timestamp: Date.parse(row.timestamp),
    fundingRate: Number(row.funding_rate),
  }));
  const fundingCoverage = verifyExactIntervalCoverage(
    funding,
    FUNDING_INTERVAL_MILLIS,
    startMillis,
    endMillis,
    "FUNDING",
  );
  failures.push(...fundingCoverage.failures);
  if (funding.some((row) => !Number.isFinite(row.fundingRate))) failures.push("Funding contains a non-numeric rate.");

  const exists = db.prepare("SELECT 1 present FROM marketBars WHERE series=? AND opened_at=?");
  const missingDecisionInputs = [];
  for (const settlement of funding) {
    const decisionBar = instantString(settlement.timestamp - KLINE_INTERVAL_MILLIS);
    const entryBar = instantString(settlement.timestamp + KLINE_INTERVAL_MILLIS);
    if (settlement.timestamp === startMillis) continue;
    for (const name of BAR_SERIES) {
      if (exists.get(name, decisionBar) == null ||
          (settlement.timestamp + KLINE_INTERVAL_MILLIS < endMillis && exists.get(name, entryBar) == null)) {
        missingDecisionInputs.push({ fundingTimestamp: instantString(settlement.timestamp), series: name });
      }
    }
  }
  if (missingDecisionInputs.length > 0) failures.push("Funding settlements lack matching closed or entry M5 bars.");
  return {
    complete: failures.length === 0,
    failures,
    series,
    matchingBarCount,
    expectedMatchingBarCount,
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

export function normalizedDeltaNeutralEvidenceFingerprint(db, protocol) {
  const hash = createHash("sha256");
  for (const series of BAR_SERIES) {
    forEachPagedRow(db, {
      sql: `SELECT series,opened_at,open,high,low,close,volume,turnover FROM marketBars
        WHERE series=? AND opened_at>? ORDER BY opened_at LIMIT ?`,
      params: [series],
      cursorColumn: "opened_at",
      onRow: (row) => hash.update(`${JSON.stringify(row)}\n`),
    });
  }
  forEachPagedRow(db, {
    sql: `SELECT symbol,timestamp,funding_rate FROM fundingRates
      WHERE symbol=? AND timestamp>? ORDER BY timestamp LIMIT ?`,
    params: [protocol.sourceData.perpetualSymbol],
    cursorColumn: "timestamp",
    onRow: (row) => hash.update(`${JSON.stringify(row)}\n`),
  });
  return hash.digest("hex");
}

export function validateExistingImport(summary, rows, pages, expected) {
  if (rows.length === 0 || summary.rowCount !== rows.length ||
      summary.firstTimestamp !== instantString(rows[0].timestamp) ||
      summary.lastTimestamp !== instantString(rows.at(-1).timestamp) ||
      summary.normalizedContentSha256 !== hashNormalizedRows(rows)) {
    throw new Error(`Persisted ${summary.dataset} rows differ from their immutable import receipt.`);
  }
  if (summary.sourceEndpoint !== expected.endpoint || summary.rangeStart !== expected.rangeStart ||
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
      ? loadPersistedFundingRows(db, protocol)
      : loadPersistedBarRows(db, definition.series, protocol);
    const pages = loadPersistedRawPages(db, definition.dataset);
    validateExistingImport(existing, rows, pages, {
      endpoint: definition.sourceEndpoint,
      rangeStart: protocol.sourceData.developmentStart,
      rangeEndExclusive: protocol.sourceData.developmentEndExclusive,
    });
    return existing;
  }
  log(`delta-neutral acquisition started dataset=${definition.dataset}`);
  const startMillis = Date.parse(protocol.sourceData.developmentStart);
  const endExclusiveMillis = Date.parse(protocol.sourceData.developmentEndExclusive);
  const params = definition.kind === "FUNDING"
    ? {
        category: "linear",
        symbol: protocol.sourceData.perpetualSymbol,
        startTime: startMillis,
        limit: protocol.sourceData.requestLimit.funding,
      }
    : {
        category: definition.category,
        symbol: definition.category === "spot"
          ? protocol.sourceData.spotSymbol
          : protocol.sourceData.perpetualSymbol,
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
    if (definition.kind === "FUNDING") replaceFundingRows(db, protocol, rows);
    else replaceBarRows(db, definition.series, protocol, rows);
    insertRawPages(db, definition.dataset, acquired.rawPages);
    insertImport(db, {
      dataset: definition.dataset,
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
  log(`delta-neutral acquisition completed dataset=${definition.dataset} rows=${rows.length}`);
  return importSummary(db, definition.dataset);
}

function datasetDefinitions(protocol) {
  return [
    dataset("spot_last", "BAR", "SPOT_LAST", "spot", protocol.sourceData.spotKlineEndpoint),
    dataset("perpetual_last", "BAR", "PERPETUAL_LAST", "linear", protocol.sourceData.perpetualKlineEndpoint),
    dataset("perpetual_mark", "BAR", "PERPETUAL_MARK", "linear", protocol.sourceData.markKlineEndpoint),
    dataset("perpetual_index", "BAR", "PERPETUAL_INDEX", "linear", protocol.sourceData.indexKlineEndpoint),
    dataset("funding", "FUNDING", null, "linear", protocol.sourceData.fundingEndpoint),
  ];
}

function dataset(name, kind, series, category, sourceEndpoint) {
  return {
    dataset: name,
    kind,
    series,
    category,
    sourceEndpoint,
    requestEndpoint: sourceEndpoint.split("?")[0],
  };
}

function replaceBarRows(db, series, protocol, rows) {
  db.prepare("DELETE FROM marketBars WHERE series=? AND opened_at>=? AND opened_at<?").run(
    series,
    protocol.sourceData.developmentStart,
    protocol.sourceData.developmentEndExclusive,
  );
  const insert = db.prepare(`
    INSERT INTO marketBars(series,opened_at,open,high,low,close,volume,turnover)
    VALUES (?,?,?,?,?,?,?,?)
  `);
  for (const row of rows) {
    insert.run(series, instantString(row.timestamp), row.open, row.high, row.low, row.close, row.volume, row.turnover);
  }
}

function replaceFundingRows(db, protocol, rows) {
  const symbol = protocol.sourceData.perpetualSymbol;
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
    INSERT INTO deltaNeutralImports(
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
  db.prepare("DELETE FROM deltaNeutralRawPages WHERE dataset=?").run(dataset);
  const insert = db.prepare(`
    INSERT INTO deltaNeutralRawPages(dataset,page_index,canonical_request,response_sha256,raw_body)
    VALUES (?,?,?,?,?)
  `);
  for (const page of pages) {
    insert.run(dataset, page.pageIndex, page.canonicalRequest, page.responseSha256, page.rawBody);
  }
}

function importSummary(db, dataset) {
  const row = db.prepare("SELECT * FROM deltaNeutralImports WHERE dataset=?").get(dataset);
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

function loadPersistedBarRows(db, series, protocol) {
  return db.prepare(`
    SELECT opened_at,open,high,low,close,volume,turnover FROM marketBars
    WHERE series=? AND opened_at>=? AND opened_at<? ORDER BY opened_at
  `).all(series, protocol.sourceData.developmentStart, protocol.sourceData.developmentEndExclusive).map((row) => ({
    timestamp: Date.parse(row.opened_at),
    open: row.open,
    high: row.high,
    low: row.low,
    close: row.close,
    volume: row.volume,
    turnover: row.turnover,
  }));
}

function loadPersistedFundingRows(db, protocol) {
  return db.prepare(`
    SELECT timestamp,funding_rate FROM fundingRates
    WHERE symbol=? AND timestamp>=? AND timestamp<? ORDER BY timestamp
  `).all(
    protocol.sourceData.perpetualSymbol,
    protocol.sourceData.developmentStart,
    protocol.sourceData.developmentEndExclusive,
  ).map((row) => ({ timestamp: Date.parse(row.timestamp), fundingRate: row.funding_rate }));
}

function loadPersistedRawPages(db, dataset) {
  return db.prepare(`
    SELECT page_index,canonical_request,response_sha256,raw_body
    FROM deltaNeutralRawPages WHERE dataset=? ORDER BY page_index
  `).all(dataset).map((row) => {
    if (sha256Text(row.raw_body) !== row.response_sha256) {
      throw new Error(`Persisted ${dataset} raw page ${row.page_index} content hash changed.`);
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

function canonicalDecimal(value) {
  if (typeof value !== "string" && typeof value !== "number") throw new Error("Decimal value is missing.");
  const number = Number(value);
  if (!Number.isFinite(number)) throw new Error(`Invalid decimal value: ${value}.`);
  if (number === 0) return "0";
  return String(number);
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

async function sealDevelopmentSnapshot(targetPath, snapshotPath, expectedFingerprint, hashFile) {
  try {
    await stat(snapshotPath);
    const targetSha256 = await hashFile(targetPath);
    const snapshotSha256 = await hashFile(snapshotPath);
    if (targetSha256 !== snapshotSha256) throw new Error("Existing delta-neutral snapshot differs from its source database.");
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
      SELECT normalized_evidence_sha256 FROM deltaNeutralMetadata WHERE singleton=1
    `).get();
    if (row?.normalized_evidence_sha256 !== expectedFingerprint) {
      throw new Error("Delta-neutral snapshot fingerprint does not match normalized evidence.");
    }
  } finally {
    snapshot.close();
  }
}

export async function implementationFingerprint(repositoryRoot) {
  const hash = createHash("sha256");
  for (const relativePath of [
    "scripts/delta-neutral-funding-carry-protocol.mjs",
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
  acquireDeltaNeutralFundingCarryDevelopment(parseArgs(process.argv.slice(2)))
    .then((result) => {
      console.log(JSON.stringify(result, null, 2));
    })
    .catch((error) => {
      console.error(error.stack ?? error.message);
      process.exitCode = 1;
    });
}
