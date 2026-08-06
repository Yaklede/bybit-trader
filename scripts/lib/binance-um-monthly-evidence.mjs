import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import fs from "node:fs/promises";
import path from "node:path";
import { DatabaseSync } from "node:sqlite";

const ARCHIVE_ROOT = "https://data.binance.vision/data/futures/um/monthly";
const H4_MILLIS = 4 * 60 * 60 * 1_000;
const FUNDING_BOUNDARY_TOLERANCE_MILLIS = 60_000;

export function enumerateUtcMonths(startInclusive, endExclusive) {
  const start = parseInstant(startInclusive);
  const end = parseInstant(endExclusive);
  if (start >= end) throw new Error(`Invalid month range: ${startInclusive}..${endExclusive}`);
  const startDate = new Date(start);
  if (startDate.getUTCDate() !== 1 || startDate.getUTCHours() !== 0 || startDate.getUTCMinutes() !== 0 ||
      startDate.getUTCSeconds() !== 0 || startDate.getUTCMilliseconds() !== 0) {
    throw new Error("Binance monthly evidence must start at a UTC month boundary.");
  }
  const endDate = new Date(end);
  if (endDate.getUTCDate() !== 1 || endDate.getUTCHours() !== 0 || endDate.getUTCMinutes() !== 0 ||
      endDate.getUTCSeconds() !== 0 || endDate.getUTCMilliseconds() !== 0) {
    throw new Error("Binance monthly evidence must end at a UTC month boundary.");
  }
  const months = [];
  const cursor = new Date(start);
  while (cursor.getTime() < end) {
    months.push(cursor.toISOString().slice(0, 7));
    cursor.setUTCMonth(cursor.getUTCMonth() + 1);
  }
  return months;
}

export function monthlyArchiveDescriptor(dataset, symbol, month) {
  if (!/^\d{4}-\d{2}$/.test(month)) throw new Error(`Invalid Binance archive month: ${month}`);
  if (dataset === "klines") {
    const name = `${symbol}-4h-${month}.zip`;
    return {
      dataset,
      month,
      name,
      url: `${ARCHIVE_ROOT}/klines/${symbol}/4h/${name}`,
    };
  }
  if (dataset === "fundingRate") {
    const name = `${symbol}-fundingRate-${month}.zip`;
    return {
      dataset,
      month,
      name,
      url: `${ARCHIVE_ROOT}/fundingRate/${symbol}/${name}`,
    };
  }
  throw new Error(`Unsupported Binance monthly dataset: ${dataset}`);
}

export function parsePublishedChecksum(payload, expectedName) {
  const [digest, name] = payload.trim().split(/\s+/);
  if (!/^[a-f\d]{64}$/i.test(digest) || name?.replace(/^\*/, "") !== expectedName) {
    throw new Error(`Invalid Binance checksum payload for ${expectedName}.`);
  }
  return digest.toLowerCase();
}

export function parseBinanceH4Klines(csv, month = null) {
  const rows = [];
  for (const line of csv.trim().split(/\r?\n/)) {
    if (!line.trim()) continue;
    const columns = line.split(",");
    if (!/^\d+$/.test(columns[0]?.trim() ?? "")) continue;
    if (columns.length < 6) throw new Error("Binance H4 kline row is incomplete.");
    const openedAt = normalizeEpochMillis(columns[0]);
    if (openedAt % H4_MILLIS !== 0) throw new Error(`Binance H4 kline is off boundary: ${columns[0]}`);
    const row = {
      openedAt,
      open: decimalString(columns[1]),
      high: decimalString(columns[2]),
      low: decimalString(columns[3]),
      close: decimalString(columns[4]),
      volume: decimalString(columns[5]),
    };
    assertOhlcv(row);
    if (month != null) assertTimestampInMonth(openedAt, month, "H4 kline");
    rows.push(row);
  }
  assertStrictlyIncreasing(rows, (row) => row.openedAt, "Binance H4 kline");
  return rows;
}

export function parseBinanceFundingRates(csv, month = null) {
  const rows = [];
  for (const line of csv.trim().split(/\r?\n/)) {
    if (!line.trim()) continue;
    const columns = line.split(",");
    if (!/^\d+$/.test(columns[0]?.trim() ?? "")) continue;
    if (columns.length < 3) throw new Error("Binance funding row is incomplete.");
    const sourceTimestamp = normalizeEpochMillis(columns[0]);
    const timestamp = nearestH4Boundary(sourceTimestamp);
    if (Math.abs(timestamp - sourceTimestamp) > FUNDING_BOUNDARY_TOLERANCE_MILLIS) {
      throw new Error(`Binance funding timestamp is off H4 boundary: ${columns[0]}`);
    }
    if (month != null) assertTimestampInMonth(timestamp, month, "funding");
    rows.push({
      timestamp,
      sourceTimestamp,
      intervalHours: positiveInteger(columns[1], "funding interval"),
      rate: decimalString(columns[2]),
    });
  }
  assertStrictlyIncreasing(rows, (row) => row.timestamp, "Binance funding");
  return rows;
}

export async function downloadVerifiedArchives({
  descriptors,
  directory,
  fetchImpl = globalThis.fetch,
  concurrency = 6,
  onProgress = () => {},
}) {
  if (!Array.isArray(descriptors) || descriptors.length === 0) throw new Error("Binance archive list is empty.");
  await fs.mkdir(directory, { recursive: true });
  return mapConcurrent(descriptors, concurrency, async (descriptor) => {
    const target = path.join(directory, descriptor.name);
    const checksumResponse = await fetchWithRetry(`${descriptor.url}.CHECKSUM`, fetchImpl);
    const expectedSha256 = parsePublishedChecksum(await checksumResponse.text(), descriptor.name);
    const existingSha256 = await sha256FileOrNull(target);
    if (existingSha256 === expectedSha256) {
      const result = { ...descriptor, path: target, sha256: expectedSha256, reused: true };
      onProgress(result);
      return result;
    }
    const archiveResponse = await fetchWithRetry(descriptor.url, fetchImpl);
    const bytes = Buffer.from(await archiveResponse.arrayBuffer());
    const actualSha256 = sha256(bytes);
    if (actualSha256 !== expectedSha256) {
      throw new Error(`Binance archive checksum mismatch: ${descriptor.name}`);
    }
    const temporary = `${target}.part-${process.pid}`;
    await fs.writeFile(temporary, bytes);
    await fs.rename(temporary, target);
    const result = { ...descriptor, path: target, sha256: actualSha256, reused: false };
    onProgress(result);
    return result;
  });
}

export async function acquireBinanceTrendEvidence({
  protocol,
  protocolSha256,
  databasePath,
  archiveDirectory,
  fetchImpl = globalThis.fetch,
  unzipBinary = "unzip",
  now = () => new Date().toISOString(),
  onProgress = () => {},
}) {
  const source = protocol.externalEvidence;
  if (source?.venue !== "BINANCE_USDM" || source.mustVerifyPublishedChecksums !== true ||
      source.parametersMayChangeAfterRead !== false) {
    throw new Error("External evidence contract is not frozen for Binance USD-M.");
  }
  const months = enumerateUtcMonths(source.startInclusive, source.endExclusive);
  const descriptors = months.flatMap((month) => [
    monthlyArchiveDescriptor("klines", protocol.market.symbol, month),
    monthlyArchiveDescriptor("fundingRate", protocol.market.symbol, month),
  ]);
  const archives = await downloadVerifiedArchives({
    descriptors,
    directory: archiveDirectory,
    fetchImpl,
    onProgress,
  });
  const temporaryDatabasePath = `${databasePath}.part-${process.pid}`;
  await fs.mkdir(path.dirname(databasePath), { recursive: true });
  await fs.rm(temporaryDatabasePath, { force: true });
  const db = new DatabaseSync(temporaryDatabasePath);
  try {
    ensureSchema(db);
    const insertedAt = now();
    db.exec("BEGIN IMMEDIATE");
    try {
      db.prepare(`
        INSERT INTO binanceEvidenceMetadata(
          singleton,protocol_id,protocol_sha256,venue,symbol,range_start,range_end_exclusive,imported_at
        ) VALUES (1,?,?,?,?,?,?,?)
      `).run(
        protocol.protocolId,
        protocolSha256,
        source.venue,
        protocol.market.symbol,
        source.startInclusive,
        source.endExclusive,
        insertedAt,
      );
      for (const archive of archives) {
        const csv = execFileSync(unzipBinary, ["-p", archive.path], {
          encoding: "utf8",
          maxBuffer: 64 * 1024 * 1024,
        });
        const rows = archive.dataset === "klines"
          ? insertH4Rows(db, protocol.market.symbol, parseBinanceH4Klines(csv, archive.month))
          : insertFundingRows(db, protocol.market.symbol, parseBinanceFundingRates(csv, archive.month));
        if (rows.length === 0) throw new Error(`Binance archive contains no rows: ${archive.name}`);
        db.prepare(`
          INSERT INTO binanceMonthlyImports(
            dataset,month,archive_name,source_url,published_sha256,archive_sha256,
            row_count,first_timestamp,last_timestamp,imported_at
          ) VALUES (?,?,?,?,?,?,?,?,?,?)
        `).run(
          archive.dataset,
          archive.month,
          archive.name,
          archive.url,
          archive.sha256,
          archive.sha256,
          rows.length,
          instantString(archive.dataset === "klines" ? rows[0].openedAt : rows[0].timestamp),
          instantString(archive.dataset === "klines" ? rows.at(-1).openedAt : rows.at(-1).timestamp),
          insertedAt,
        );
      }
      db.exec("COMMIT");
    } catch (error) {
      db.exec("ROLLBACK");
      throw error;
    }
    const audit = auditBinanceTrendEvidence(db, protocol, archives.length, protocolSha256);
    db.close();
    await fs.rename(temporaryDatabasePath, databasePath);
    return {
      databasePath,
      archiveDirectory,
      protocolId: protocol.protocolId,
      protocolSha256,
      importedAt: insertedAt,
      archiveCount: archives.length,
      reusedArchiveCount: archives.filter((archive) => archive.reused).length,
      archiveManifestSha256: archiveManifestHash(archives),
      archives: archives.map(({ dataset, month, name, url, sha256, reused }) => ({
        dataset,
        month,
        name,
        url,
        sha256,
        reused,
      })),
      ...audit,
    };
  } catch (error) {
    try {
      db.close();
    } catch {
      // The database may already be closed after a successful audit.
    }
    await fs.rm(temporaryDatabasePath, { force: true });
    throw error;
  }
}

export function auditBinanceTrendEvidence(db, protocol, expectedArchiveCount, expectedProtocolSha256 = null) {
  const metadata = db.prepare("SELECT * FROM binanceEvidenceMetadata WHERE singleton=1").get();
  if (metadata == null || metadata.protocol_id !== protocol.protocolId ||
      (expectedProtocolSha256 != null && metadata.protocol_sha256 !== expectedProtocolSha256)) {
    throw new Error("Binance metadata mismatch.");
  }
  const start = parseInstant(protocol.externalEvidence.startInclusive);
  const end = parseInstant(protocol.externalEvidence.endExclusive);
  const candles = db.prepare(`
    SELECT opened_at FROM marketCandles WHERE symbol=? AND timeframe='H4' ORDER BY opened_at
  `).all(protocol.market.symbol).map((row) => parseInstant(row.opened_at));
  const expectedCandleCount = (end - start) / H4_MILLIS;
  if (!Number.isInteger(expectedCandleCount) || candles.length !== expectedCandleCount ||
      candles[0] !== start || candles.at(-1) !== end - H4_MILLIS) {
    throw new Error(`Binance H4 coverage mismatch: expected=${expectedCandleCount} actual=${candles.length}.`);
  }
  candles.forEach((timestamp, index) => {
    if (timestamp !== start + index * H4_MILLIS) {
      throw new Error(`Binance H4 gap at ${instantString(start + index * H4_MILLIS)}.`);
    }
  });
  const funding = db.prepare("SELECT timestamp FROM fundingRates WHERE symbol=? ORDER BY timestamp")
    .all(protocol.market.symbol).map((row) => parseInstant(row.timestamp));
  assertStrictlyIncreasing(funding, (timestamp) => timestamp, "Persisted Binance funding");
  if (funding.length === 0 || funding[0] < start || funding.at(-1) >= end) {
    throw new Error("Binance funding coverage is outside the frozen range.");
  }
  const imports = db.prepare(`
    SELECT dataset,month,archive_name,source_url,published_sha256,archive_sha256,
           row_count,first_timestamp,last_timestamp
    FROM binanceMonthlyImports ORDER BY month,dataset
  `).all();
  if (imports.length !== expectedArchiveCount || imports.some((row) => row.published_sha256 !== row.archive_sha256)) {
    throw new Error("Binance archive manifest is incomplete.");
  }
  return {
    h4BarCount: candles.length,
    firstH4OpenedAt: instantString(candles[0]),
    lastH4OpenedAt: instantString(candles.at(-1)),
    fundingRateCount: funding.length,
    firstFundingAt: instantString(funding[0]),
    lastFundingAt: instantString(funding.at(-1)),
    importManifestSha256: hashRows(imports),
  };
}

function ensureSchema(db) {
  db.exec(`
    PRAGMA journal_mode=DELETE;
    PRAGMA synchronous=FULL;
    CREATE TABLE binanceEvidenceMetadata (
      singleton INTEGER NOT NULL PRIMARY KEY CHECK(singleton=1),
      protocol_id TEXT NOT NULL,
      protocol_sha256 TEXT NOT NULL,
      venue TEXT NOT NULL,
      symbol TEXT NOT NULL,
      range_start TEXT NOT NULL,
      range_end_exclusive TEXT NOT NULL,
      imported_at TEXT NOT NULL
    );
    CREATE TABLE marketCandles (
      symbol TEXT NOT NULL,
      timeframe TEXT NOT NULL,
      opened_at TEXT NOT NULL,
      open TEXT NOT NULL,
      high TEXT NOT NULL,
      low TEXT NOT NULL,
      close TEXT NOT NULL,
      volume TEXT NOT NULL,
      source_timestamp TEXT NOT NULL,
      PRIMARY KEY(symbol,timeframe,opened_at)
    );
    CREATE TABLE fundingRates (
      symbol TEXT NOT NULL,
      timestamp TEXT NOT NULL,
      source_timestamp TEXT NOT NULL,
      interval_hours INTEGER NOT NULL,
      funding_rate TEXT NOT NULL,
      PRIMARY KEY(symbol,timestamp)
    );
    CREATE TABLE binanceMonthlyImports (
      dataset TEXT NOT NULL,
      month TEXT NOT NULL,
      archive_name TEXT NOT NULL,
      source_url TEXT NOT NULL,
      published_sha256 TEXT NOT NULL,
      archive_sha256 TEXT NOT NULL,
      row_count INTEGER NOT NULL,
      first_timestamp TEXT NOT NULL,
      last_timestamp TEXT NOT NULL,
      imported_at TEXT NOT NULL,
      PRIMARY KEY(dataset,month)
    );
  `);
}

function insertH4Rows(db, symbol, rows) {
  const insert = db.prepare(`
    INSERT INTO marketCandles(symbol,timeframe,opened_at,open,high,low,close,volume,source_timestamp)
    VALUES (?,'H4',?,?,?,?,?,?,?)
  `);
  for (const row of rows) {
    const openedAt = instantString(row.openedAt);
    insert.run(symbol, openedAt, row.open, row.high, row.low, row.close, row.volume, openedAt);
  }
  return rows;
}

function insertFundingRows(db, symbol, rows) {
  const insert = db.prepare(`
    INSERT INTO fundingRates(symbol,timestamp,source_timestamp,interval_hours,funding_rate)
    VALUES (?,?,?,?,?)
  `);
  for (const row of rows) {
    insert.run(symbol, instantString(row.timestamp), instantString(row.sourceTimestamp), row.intervalHours, row.rate);
  }
  return rows;
}

async function fetchWithRetry(url, fetchImpl) {
  let lastError = null;
  for (let attempt = 1; attempt <= 4; attempt += 1) {
    try {
      const response = await fetchImpl(url);
      if (response.ok) return response;
      if (response.status < 500 && response.status !== 429) {
        throw new Error(`Binance archive request failed: ${response.status} ${url}`);
      }
      lastError = new Error(`Binance archive request failed: ${response.status} ${url}`);
    } catch (error) {
      lastError = error;
    }
    if (attempt < 4) await delay(250 * (2 ** (attempt - 1)));
  }
  throw lastError;
}

async function mapConcurrent(items, concurrency, operation) {
  if (!Number.isInteger(concurrency) || concurrency < 1) throw new Error("Archive concurrency must be positive.");
  const results = Array(items.length);
  let cursor = 0;
  const workers = Array.from({ length: Math.min(concurrency, items.length) }, async () => {
    while (cursor < items.length) {
      const index = cursor;
      cursor += 1;
      results[index] = await operation(items[index], index);
    }
  });
  await Promise.all(workers);
  return results;
}

function assertTimestampInMonth(timestamp, month, label) {
  const actual = new Date(timestamp).toISOString().slice(0, 7);
  if (actual !== month) throw new Error(`${label} timestamp ${timestamp} is outside ${month}.`);
}

function assertStrictlyIncreasing(rows, timestamp, label) {
  for (let index = 1; index < rows.length; index += 1) {
    if (timestamp(rows[index]) <= timestamp(rows[index - 1])) {
      throw new Error(`${label} timestamps are not strictly increasing.`);
    }
  }
}

function assertOhlcv(row) {
  const open = Number(row.open);
  const high = Number(row.high);
  const low = Number(row.low);
  const close = Number(row.close);
  const volume = Number(row.volume);
  if (low > Math.min(open, close) || high < Math.max(open, close) || low > high || volume < 0) {
    throw new Error(`Invalid Binance H4 OHLCV at ${row.openedAt}.`);
  }
}

function normalizeEpochMillis(value) {
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed <= 0) throw new Error(`Invalid Binance timestamp: ${value}`);
  return parsed > 100_000_000_000_000 ? Math.trunc(parsed / 1_000) : parsed;
}

function nearestH4Boundary(timestamp) {
  return Math.round(timestamp / H4_MILLIS) * H4_MILLIS;
}

function decimalString(value) {
  const normalized = String(value).trim();
  if (!normalized || !Number.isFinite(Number(normalized))) throw new Error(`Invalid Binance decimal: ${value}`);
  return normalized;
}

function positiveInteger(value, label) {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed <= 0) throw new Error(`Invalid ${label}: ${value}`);
  return parsed;
}

function parseInstant(value) {
  const parsed = typeof value === "number" ? value : Date.parse(value);
  if (!Number.isFinite(parsed)) throw new Error(`Invalid instant: ${value}`);
  return parsed;
}

function instantString(timestamp) {
  return new Date(timestamp).toISOString();
}

function archiveManifestHash(archives) {
  return hashRows(archives.map((archive) => ({
    dataset: archive.dataset,
    month: archive.month,
    name: archive.name,
    url: archive.url,
    sha256: archive.sha256,
  })));
}

function hashRows(rows) {
  const digest = createHash("sha256");
  rows.forEach((row) => digest.update(`${JSON.stringify(row)}\n`));
  return digest.digest("hex");
}

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

async function sha256FileOrNull(file) {
  try {
    return sha256(await fs.readFile(file));
  } catch (error) {
    if (error?.code === "ENOENT") return null;
    throw error;
  }
}

function delay(milliseconds) {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}
