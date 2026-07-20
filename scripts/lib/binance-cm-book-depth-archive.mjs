import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import fs from "node:fs/promises";
import path from "node:path";

const ARCHIVE_ROOT = "https://data.binance.vision/data/futures/cm/daily/bookDepth";

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

export function bookDepthArchiveName(symbol, day) {
  return `${symbol}-bookDepth-${day}.zip`;
}

export function bookDepthArchiveUrl(symbol, day) {
  return `${ARCHIVE_ROOT}/${symbol}/${bookDepthArchiveName(symbol, day)}`;
}

export async function downloadBinanceCmBookDepthArchives({
  symbol = "BTCUSD_PERP",
  startAt,
  endAt,
  outDir,
  fetchImpl = globalThis.fetch,
  onProgress = () => {},
  allowMissing = false,
}) {
  await fs.mkdir(outDir, { recursive: true });
  const results = [];
  for (const day of enumerateUtcDays(startAt, endAt)) {
    const name = bookDepthArchiveName(symbol, day);
    const target = path.join(outDir, name);
    const url = bookDepthArchiveUrl(symbol, day);
    const checksumResponse = await fetchImpl(`${url}.CHECKSUM`);
    if (checksumResponse.status === 404 && allowMissing) {
      results.push({ day, path: null, sha256: null, reused: false, missing: true });
      onProgress({ day, reused: false, missing: true });
      continue;
    }
    if (!checksumResponse.ok) throw new Error(`Binance depth checksum download failed: ${checksumResponse.status} ${day}`);
    const expectedSha256 = parseChecksum(await checksumResponse.text(), name);
    const existingSha256 = await sha256FileOrNull(target);
    if (existingSha256 === expectedSha256) {
      results.push({ day, path: target, sha256: expectedSha256, reused: true });
      onProgress({ day, reused: true, missing: false });
      continue;
    }

    const response = await fetchImpl(url);
    if (!response.ok) throw new Error(`Binance depth archive download failed: ${response.status} ${day}`);
    const bytes = Buffer.from(await response.arrayBuffer());
    const actualSha256 = sha256(bytes);
    if (actualSha256 !== expectedSha256) throw new Error(`Binance depth checksum mismatch: ${day}`);
    const temporary = `${target}.part`;
    await fs.writeFile(temporary, bytes);
    await fs.rename(temporary, target);
    results.push({ day, path: target, sha256: actualSha256, reused: false });
    onProgress({ day, reused: false, missing: false });
  }
  return results;
}

export async function loadBinanceCmBookDepth5m({
  directory,
  symbol = "BTCUSD_PERP",
  unzipBinary = "unzip",
}) {
  const prefix = `${symbol}-bookDepth-`;
  const names = (await fs.readdir(directory))
    .filter((name) => name.startsWith(prefix) && name.endsWith(".zip"))
    .sort();
  if (names.length === 0) throw new Error(`No Binance bookDepth archives found in ${directory}`);

  const byOpenedAt = new Map();
  for (const name of names) {
    const csv = execFileSync(unzipBinary, ["-p", path.join(directory, name)], {
      encoding: "utf8",
      maxBuffer: 16 * 1024 * 1024,
    });
    for (const row of parseBinanceCmBookDepth5m(csv)) byOpenedAt.set(row.openedAtMs, row);
  }
  return [...byOpenedAt.values()].sort((left, right) => left.openedAtMs - right.openedAtMs);
}

export function parseBinanceCmBookDepth5m(csv) {
  const buckets = new Map();
  let timestamp = null;
  let levels = new Map();

  const flushSnapshot = () => {
    if (timestamp == null || levels.size < 10) return;
    const openedAtMs = Math.floor(parseUtcTimestamp(timestamp) / 300_000) * 300_000;
    const bucket = buckets.get(openedAtMs) ?? emptyBucket(openedAtMs);
    bucket.snapshotCount += 1;
    for (const depthPct of [1, 2, 5]) {
      const bid = levels.get(-depthPct);
      const ask = levels.get(depthPct);
      if (bid == null || ask == null || bid + ask <= 0) continue;
      const imbalance = (bid - ask) / (bid + ask);
      bucket[`imbalance${depthPct}Sum`] += imbalance;
      if (bucket[`imbalance${depthPct}First`] == null) bucket[`imbalance${depthPct}First`] = imbalance;
      bucket[`imbalance${depthPct}Last`] = imbalance;
      bucket[`notional${depthPct}Sum`] += bid + ask;
    }
    buckets.set(openedAtMs, bucket);
  };

  for (const line of csv.trim().split(/\r?\n/)) {
    const columns = line.split(",");
    if (columns.length < 4 || columns[0] === "timestamp") continue;
    if (timestamp !== columns[0]) {
      flushSnapshot();
      timestamp = columns[0];
      levels = new Map();
    }
    const percentage = Number(columns[1]);
    const notional = Number(columns[3]);
    if (Number.isFinite(percentage) && Number.isFinite(notional)) levels.set(percentage, notional);
  }
  flushSnapshot();

  return [...buckets.values()]
    .sort((left, right) => left.openedAtMs - right.openedAtMs)
    .map((bucket) => ({
      openedAtMs: bucket.openedAtMs,
      snapshotCount: bucket.snapshotCount,
      complete: bucket.snapshotCount >= 9,
      imbalance1: bucket.snapshotCount > 0 ? bucket.imbalance1Sum / bucket.snapshotCount : null,
      imbalance2: bucket.snapshotCount > 0 ? bucket.imbalance2Sum / bucket.snapshotCount : null,
      imbalance5: bucket.snapshotCount > 0 ? bucket.imbalance5Sum / bucket.snapshotCount : null,
      imbalanceChange1: imbalanceChange(bucket, 1),
      imbalanceChange2: imbalanceChange(bucket, 2),
      imbalanceChange5: imbalanceChange(bucket, 5),
      totalNotional1: bucket.snapshotCount > 0 ? bucket.notional1Sum / bucket.snapshotCount : null,
      totalNotional2: bucket.snapshotCount > 0 ? bucket.notional2Sum / bucket.snapshotCount : null,
      totalNotional5: bucket.snapshotCount > 0 ? bucket.notional5Sum / bucket.snapshotCount : null,
    }));
}

export function attachBinanceDepth(candles, rows, { rollingWindow = 288 } = {}) {
  const byOpenedAt = new Map(rows.filter((row) => row.complete).map((row) => [row.openedAtMs, row]));
  const history = [];
  let matched = 0;
  for (const candle of candles) {
    const row = byOpenedAt.get(candle.openedAtMs);
    if (row == null) continue;
    matched += 1;
    candle.binanceDepthImbalance1 = row.imbalance1;
    candle.binanceDepthImbalance2 = row.imbalance2;
    candle.binanceDepthImbalance5 = row.imbalance5;
    candle.binanceDepthImbalanceChange1 = row.imbalanceChange1;
    candle.binanceDepthImbalanceChange2 = row.imbalanceChange2;
    candle.binanceDepthImbalanceChange5 = row.imbalanceChange5;
    candle.binanceDepthNotional1 = row.totalNotional1;
    candle.binanceDepthImbalanceZ = zScoreAgainstHistory(row.imbalance1, history);
    history.push(row.imbalance1);
    if (history.length > rollingWindow) history.shift();
  }
  return {
    matched,
    totalCandles: candles.length,
    coveragePct: candles.length > 0 ? (matched / candles.length) * 100 : 0,
  };
}

function emptyBucket(openedAtMs) {
  return {
    openedAtMs,
    snapshotCount: 0,
    imbalance1Sum: 0,
    imbalance2Sum: 0,
    imbalance5Sum: 0,
    imbalance1First: null,
    imbalance2First: null,
    imbalance5First: null,
    imbalance1Last: null,
    imbalance2Last: null,
    imbalance5Last: null,
    notional1Sum: 0,
    notional2Sum: 0,
    notional5Sum: 0,
  };
}

function imbalanceChange(bucket, depthPct) {
  const first = bucket[`imbalance${depthPct}First`];
  const last = bucket[`imbalance${depthPct}Last`];
  return first == null || last == null ? null : last - first;
}

function parseUtcTimestamp(value) {
  const parsed = Date.parse(`${value.replace(" ", "T")}Z`);
  if (!Number.isFinite(parsed)) throw new Error(`Invalid Binance depth timestamp: ${value}`);
  return parsed;
}

function zScoreAgainstHistory(value, history) {
  if (value == null || history.length < 48) return null;
  const mean = history.reduce((sum, item) => sum + item, 0) / history.length;
  const variance = history.reduce((sum, item) => sum + ((item - mean) ** 2), 0) / history.length;
  const standardDeviation = Math.sqrt(variance);
  return standardDeviation > 0 ? (value - mean) / standardDeviation : 0;
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
