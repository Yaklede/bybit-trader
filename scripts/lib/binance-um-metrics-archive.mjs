import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import fs from "node:fs/promises";
import path from "node:path";

const ARCHIVE_ROOT = "https://data.binance.vision/data/futures/um/daily/metrics";

export function enumerateUtcDays(startAt, endAt) {
  const start = new Date(startAt);
  const end = new Date(endAt);
  if (!Number.isFinite(start.getTime()) || !Number.isFinite(end.getTime()) || start >= end) {
    throw new Error(`Invalid day range: ${startAt}..${endAt}`);
  }
  const cursor = new Date(Date.UTC(start.getUTCFullYear(), start.getUTCMonth(), start.getUTCDate()));
  const days = [];
  while (cursor < end) {
    days.push(cursor.toISOString().slice(0, 10));
    cursor.setUTCDate(cursor.getUTCDate() + 1);
  }
  return days;
}

export function metricsArchiveName(symbol, day) {
  return `${symbol}-metrics-${day}.zip`;
}

export function metricsArchiveUrl(symbol, day) {
  return `${ARCHIVE_ROOT}/${symbol}/${metricsArchiveName(symbol, day)}`;
}

export async function downloadBinanceUmMetricsArchives({
  symbol = "BTCUSDT",
  startAt,
  endAt,
  outDir,
  fetchImpl = globalThis.fetch,
  onProgress = () => {},
  allowMissing = false,
}) {
  if (typeof fetchImpl !== "function") throw new Error("fetch implementation is required");
  await fs.mkdir(outDir, { recursive: true });
  const results = [];
  for (const day of enumerateUtcDays(startAt, endAt)) {
    const name = metricsArchiveName(symbol, day);
    const target = path.join(outDir, name);
    const url = metricsArchiveUrl(symbol, day);
    const checksumResponse = await fetchImpl(`${url}.CHECKSUM`);
    if (checksumResponse.status === 404 && allowMissing) {
      results.push({ day, path: null, sha256: null, reused: false, missing: true });
      onProgress({ day, reused: false, missing: true });
      continue;
    }
    if (!checksumResponse.ok) {
      throw new Error(`Binance metrics checksum download failed: ${checksumResponse.status} ${day}`);
    }
    const expectedSha256 = parseChecksum(await checksumResponse.text(), name);
    const existingSha256 = await sha256FileOrNull(target);
    if (existingSha256 === expectedSha256) {
      results.push({ day, path: target, sha256: expectedSha256, reused: true, missing: false });
      onProgress({ day, reused: true, missing: false });
      continue;
    }

    const response = await fetchImpl(url);
    if (!response.ok) throw new Error(`Binance metrics archive download failed: ${response.status} ${day}`);
    const bytes = Buffer.from(await response.arrayBuffer());
    const actualSha256 = sha256(bytes);
    if (actualSha256 !== expectedSha256) throw new Error(`Binance metrics checksum mismatch: ${day}`);
    const temporary = `${target}.part`;
    await fs.writeFile(temporary, bytes);
    await fs.rename(temporary, target);
    results.push({ day, path: target, sha256: actualSha256, reused: false, missing: false });
    onProgress({ day, reused: false, missing: false });
  }
  return results;
}

export async function loadBinanceUmMetrics({
  directory,
  symbol = "BTCUSDT",
  unzipBinary = "unzip",
}) {
  const prefix = `${symbol}-metrics-`;
  const names = (await fs.readdir(directory))
    .filter((name) => name.startsWith(prefix) && name.endsWith(".zip"))
    .sort();
  if (names.length === 0) throw new Error(`No Binance metrics archives found in ${directory}`);

  const byOpenedAt = new Map();
  for (const name of names) {
    const csv = execFileSync(unzipBinary, ["-p", path.join(directory, name)], {
      encoding: "utf8",
      maxBuffer: 4 * 1024 * 1024,
    });
    for (const row of parseBinanceUmMetricsCsv(csv)) byOpenedAt.set(row.openedAtMs, row);
  }
  return [...byOpenedAt.values()].sort((left, right) => left.openedAtMs - right.openedAtMs);
}

export function parseBinanceUmMetricsCsv(csv) {
  return csv
    .trim()
    .split(/\r?\n/)
    .filter(Boolean)
    .map((line) => parseMetricsLine(line))
    .filter((row) => row != null);
}

export function attachBinanceMetrics(candles, rows) {
  const enrichedRows = rows.map((row, index) => ({
    ...row,
    openInterestChange3Pct: percentageChange(rows, index, 3),
    openInterestChange12Pct: percentageChange(rows, index, 12),
    openInterestChange36Pct: percentageChange(rows, index, 36),
    openInterestChange288Pct: percentageChange(rows, index, 288),
  }));
  const byOpenedAt = new Map(enrichedRows.map((row) => [row.openedAtMs, row]));
  let matched = 0;
  for (const candle of candles) {
    const row = byOpenedAt.get(candle.openedAtMs);
    if (row == null) continue;
    matched += 1;
    candle.binanceOpenInterest = row.openInterest;
    candle.binanceOpenInterestValue = row.openInterestValue;
    candle.binanceOpenInterestChange3Pct = row.openInterestChange3Pct;
    candle.binanceOpenInterestChange12Pct = row.openInterestChange12Pct;
    candle.binanceOpenInterestChange36Pct = row.openInterestChange36Pct;
    candle.binanceOpenInterestChange288Pct = row.openInterestChange288Pct;
    candle.binanceTopTraderAccountRatio = row.topTraderAccountRatio;
    candle.binanceTopTraderPositionRatio = row.topTraderPositionRatio;
    candle.binanceGlobalAccountRatio = row.globalAccountRatio;
    candle.binanceMetricsTakerImbalance = ratioToImbalance(row.takerLongShortRatio);
  }
  return {
    matched,
    totalCandles: candles.length,
    coveragePct: candles.length > 0 ? (matched / candles.length) * 100 : 0,
  };
}

function parseMetricsLine(line) {
  const columns = line.split(",");
  if (columns.length < 8 || columns[0] === "create_time") return null;
  const openedAtMs = parseUtcTimestamp(columns[0]);
  const openInterest = requiredNumber(columns[2]);
  const openInterestValue = requiredNumber(columns[3]);
  if (!Number.isFinite(openedAtMs) || openInterest == null || openInterestValue == null) return null;
  return {
    openedAtMs,
    openInterest,
    openInterestValue,
    topTraderAccountRatio: optionalNumber(columns[4]),
    topTraderPositionRatio: optionalNumber(columns[5]),
    globalAccountRatio: optionalNumber(columns[6]),
    takerLongShortRatio: optionalNumber(columns[7]),
  };
}

function requiredNumber(value) {
  const parsed = Number(value);
  return Number.isFinite(parsed) && value.trim() !== "" ? parsed : null;
}

function optionalNumber(value) {
  const normalized = value.trim().replace(/^"|"$/g, "");
  return normalized === "" ? null : requiredNumber(normalized);
}

function percentageChange(rows, index, lookback) {
  if (index < lookback) return null;
  const prior = rows[index - lookback];
  const current = rows[index];
  if (current.openedAtMs - prior.openedAtMs !== lookback * 300_000 || prior.openInterest <= 0) return null;
  return ((current.openInterest / prior.openInterest) - 1) * 100;
}

function ratioToImbalance(ratio) {
  return ratio > 0 ? (ratio - 1) / (ratio + 1) : null;
}

function parseUtcTimestamp(value) {
  return Date.parse(`${value.replace(" ", "T")}Z`);
}

function parseChecksum(text, expectedName) {
  const [digest, name] = text.trim().split(/\s+/);
  if (!/^[a-f\d]{64}$/i.test(digest) || name?.replace(/^\*/, "") !== expectedName) {
    throw new Error(`Invalid Binance checksum payload for ${expectedName}`);
  }
  return digest.toLowerCase();
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
