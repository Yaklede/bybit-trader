import { createHash } from "node:crypto";
import { createReadStream, rmSync } from "node:fs";
import { copyFile, mkdir, readFile, rename, stat, writeFile } from "node:fs/promises";
import { DatabaseSync } from "node:sqlite";
import { dirname, resolve } from "node:path";

import {
  fetchReversePages,
  normalizeFundingRows,
  normalizeKlineRows,
  verifyExactIntervalCoverage,
} from "../delta-neutral-funding-carry-acquire.mjs";
import {
  auditMultiAssetDeltaNeutralFundingCarryCoverage,
  bindMultiAssetDeltaNeutralFundingCarryDatabase,
  ensureMultiAssetDeltaNeutralFundingCarrySchema,
  multiAssetDatasetDefinitions,
  normalizedMultiAssetEvidenceFingerprint,
} from "../multi-asset-delta-neutral-funding-carry-acquire.mjs";

const KLINE_INTERVAL_MILLIS = 5 * 60 * 1_000;
const FUNDING_INTERVAL_MILLIS = 8 * 60 * 60 * 1_000;

export async function acquireMultiAssetEvidenceStage(options, dependencies = {}) {
  const {
    protocol,
    protocolSha256,
    parentResultSha256,
    stageProtocol,
    stage,
    status,
    importerVersion,
    targetDatabasePath,
    snapshotPath,
    reportPath,
    repositoryRoot,
    implementationPaths,
    resultFields = {},
  } = options;
  await mkdir(dirname(targetDatabasePath), { recursive: true });
  await mkdir(dirname(snapshotPath), { recursive: true });
  await mkdir(dirname(reportPath), { recursive: true });
  const db = dependencies.database ?? new DatabaseSync(targetDatabasePath);
  const ownsDatabase = dependencies.database == null;
  const now = dependencies.now ?? (() => new Date().toISOString());
  const log = dependencies.log ?? console.log;
  const request = createPublicRequester(
    dependencies.fetchImpl ?? fetch,
    stageProtocol.sourceData.baseUrl,
    dependencies.requestDelayMs ?? 175,
    dependencies.wait,
  );
  let result;
  try {
    ensureMultiAssetDeltaNeutralFundingCarrySchema(db);
    bindMultiAssetDeltaNeutralFundingCarryDatabase(db, {
      protocolId: protocol.protocolId,
      protocolSha256,
      parentResultSha256,
      boundAt: now(),
    });
    const imports = [];
    for (const definition of multiAssetDatasetDefinitions(stageProtocol)) {
      imports.push(await importDatasetIfMissing({
        db,
        stageProtocol,
        definition,
        request,
        now,
        log,
        importerVersion,
        stage,
      }));
    }
    const coverage = auditMultiAssetDeltaNeutralFundingCarryCoverage(db, stageProtocol);
    if (!coverage.complete) {
      throw new Error(`${stage} multi-asset coverage failed: ${coverage.failures.join("; ")}.`);
    }
    const normalizedEvidenceSha256 = normalizedMultiAssetEvidenceFingerprint(db, stageProtocol);
    db.prepare("UPDATE multiAssetMetadata SET normalized_evidence_sha256=? WHERE singleton=1")
      .run(normalizedEvidenceSha256);
    const boundAt = db.prepare("SELECT bound_at FROM multiAssetMetadata WHERE singleton=1").get().bound_at;
    db.exec("PRAGMA wal_checkpoint(TRUNCATE)");
    const snapshotSha256 = await sealSnapshot(
      targetDatabasePath,
      snapshotPath,
      normalizedEvidenceSha256,
      dependencies.hashFile ?? sha256File,
      stage,
    );
    result = {
      schemaVersion: 1,
      protocolId: protocol.protocolId,
      protocolSha256,
      parentResultSha256,
      implementationSha256: await implementationFingerprint(repositoryRoot, implementationPaths),
      status,
      stage,
      stageRange: {
        startAt: stageProtocol.sourceData.developmentStart,
        endExclusive: stageProtocol.sourceData.developmentEndExclusive,
      },
      imports,
      coverage,
      normalizedEvidenceSha256,
      snapshot: relativeBuildPath(repositoryRoot, snapshotPath),
      snapshotSha256,
      generatedAt: boundAt,
      ...resultFields,
      automaticExecutionAllowed: false,
      liveExecutionAllowed: false,
    };
    await writeJsonAtomic(reportPath, result);
  } finally {
    if (ownsDatabase) db.close();
  }
  return result;
}

export function stageProtocolFromInternal(protocol) {
  return {
    sourceData: {
      ...protocol.sourceData,
      developmentStart: protocol.sourceData.stageStart,
      developmentEndExclusive: protocol.sourceData.stageEndExclusive,
    },
    evidenceSchedule: {
      developmentBlocks: protocol.internalValidationBlocks,
    },
  };
}

export function validateExistingStageImport(summary, rows, pages, expected, importerVersion) {
  if (rows.length === 0 || summary.rowCount !== rows.length ||
      summary.firstTimestamp !== instantString(rows[0].timestamp) ||
      summary.lastTimestamp !== instantString(rows.at(-1).timestamp) ||
      summary.normalizedContentSha256 !== hashNormalizedRows(rows)) {
    throw new Error(`Persisted ${summary.dataset} rows differ from their immutable stage receipt.`);
  }
  if (summary.symbol !== expected.symbol || summary.series !== expected.series ||
      summary.sourceEndpoint !== expected.endpoint || summary.rangeStart !== expected.rangeStart ||
      summary.rangeEndExclusive !== expected.rangeEndExclusive || summary.importerVersion !== importerVersion) {
    throw new Error(`Persisted ${summary.dataset} stage receipt changed its source contract.`);
  }
  if (summary.pageCount !== pages.length || summary.responseChainSha256 !== responseChainFingerprint(pages)) {
    throw new Error(`Persisted ${summary.dataset} raw pages differ from their immutable stage receipt.`);
  }
}

async function importDatasetIfMissing(context) {
  const { db, stageProtocol, definition, request, now, log, importerVersion, stage } = context;
  const existing = importSummary(db, definition.dataset);
  if (existing != null) {
    const rows = definition.kind === "FUNDING"
      ? loadPersistedFundingRows(db, definition.symbol, stageProtocol)
      : loadPersistedBarRows(db, definition.symbol, definition.series, stageProtocol);
    const pages = loadPersistedRawPages(db, definition.dataset);
    validateExistingStageImport(existing, rows, pages, {
      symbol: definition.symbol,
      series: definition.series,
      endpoint: definition.sourceEndpoint,
      rangeStart: stageProtocol.sourceData.developmentStart,
      rangeEndExclusive: stageProtocol.sourceData.developmentEndExclusive,
    }, importerVersion);
    return existing;
  }
  log(`multi-asset ${stage} acquisition started dataset=${definition.dataset}`);
  const startMillis = Date.parse(stageProtocol.sourceData.developmentStart);
  const endExclusiveMillis = Date.parse(stageProtocol.sourceData.developmentEndExclusive);
  const params = definition.kind === "FUNDING"
    ? {
        category: "linear",
        symbol: definition.symbol,
        startTime: startMillis,
        limit: stageProtocol.sourceData.requestLimit.funding,
      }
    : {
        category: definition.category,
        symbol: definition.symbol,
        interval: stageProtocol.sourceData.klineInterval,
        start: startMillis,
        limit: stageProtocol.sourceData.requestLimit.kline,
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
    if (definition.kind === "FUNDING") {
      replaceFundingRows(db, definition.symbol, stageProtocol, rows);
    } else {
      replaceBarRows(db, definition.symbol, definition.series, stageProtocol, rows);
    }
    insertRawPages(db, definition.dataset, acquired.rawPages);
    insertImport(db, {
      dataset: definition.dataset,
      symbol: definition.symbol,
      series: definition.series,
      endpoint: definition.sourceEndpoint,
      startAt: stageProtocol.sourceData.developmentStart,
      endExclusive: stageProtocol.sourceData.developmentEndExclusive,
      rows,
      pages: acquired.rawPages,
      responseChainSha256: acquired.responseChainSha256,
      contentSha256: hashNormalizedRows(rows),
      importedAt: now(),
      importerVersion,
    });
  });
  log(`multi-asset ${stage} acquisition completed dataset=${definition.dataset} rows=${rows.length}`);
  return importSummary(db, definition.dataset);
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
    value.importerVersion,
  );
}

function insertRawPages(db, dataset, pages) {
  db.prepare("DELETE FROM multiAssetRawPages WHERE dataset=?").run(dataset);
  const insert = db.prepare(`
    INSERT INTO multiAssetRawPages(dataset,page_index,canonical_request,response_sha256,raw_body)
    VALUES (?,?,?,?,?)
  `);
  for (const page of pages) {
    insert.run(dataset, page.pageIndex, page.canonicalRequest, page.responseSha256, page.rawBody);
  }
}

function importSummary(db, dataset) {
  const row = db.prepare("SELECT * FROM multiAssetImports WHERE dataset=?").get(dataset);
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

function loadPersistedRawPages(db, dataset) {
  return db.prepare(`
    SELECT page_index,canonical_request,response_sha256,raw_body
    FROM multiAssetRawPages WHERE dataset=? ORDER BY page_index
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

async function sealSnapshot(targetPath, snapshotPath, expectedFingerprint, hashFile, stage) {
  try {
    await stat(snapshotPath);
    const targetSha256 = await hashFile(targetPath);
    const snapshotSha256 = await hashFile(snapshotPath);
    if (targetSha256 !== snapshotSha256) {
      throw new Error(`Existing ${stage} snapshot differs from its source database.`);
    }
    verifySnapshotFingerprint(snapshotPath, expectedFingerprint, stage);
    return snapshotSha256;
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }
  const temporaryPath = `${snapshotPath}.tmp-${process.pid}`;
  await copyFile(targetPath, temporaryPath);
  verifySnapshotFingerprint(temporaryPath, expectedFingerprint, stage);
  await rename(temporaryPath, snapshotPath);
  return hashFile(snapshotPath);
}

function verifySnapshotFingerprint(path, expectedFingerprint, stage) {
  const snapshot = new DatabaseSync(path, { readOnly: true });
  try {
    const row = snapshot.prepare(`
      SELECT normalized_evidence_sha256 FROM multiAssetMetadata WHERE singleton=1
    `).get();
    if (row?.normalized_evidence_sha256 !== expectedFingerprint) {
      throw new Error(`${stage} snapshot fingerprint does not match normalized evidence.`);
    }
  } finally {
    snapshot.close();
    rmSync(`${path}-shm`, { force: true });
    rmSync(`${path}-wal`, { force: true });
  }
}

async function implementationFingerprint(repositoryRoot, relativePaths) {
  const hash = createHash("sha256");
  for (const relativePath of relativePaths) {
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

function relativeBuildPath(repositoryRoot, path) {
  const prefix = `${resolve(repositoryRoot)}/`;
  const absolute = resolve(path);
  if (!absolute.startsWith(prefix)) throw new Error("Snapshot path must stay inside the repository.");
  return absolute.slice(prefix.length);
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
