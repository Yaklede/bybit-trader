import { createHash } from "node:crypto";
import { execFileSync } from "node:child_process";
import fs from "node:fs/promises";
import path from "node:path";

const ARCHIVE_ROOT = "https://data.binance.vision/data/futures/um/monthly/klines";

export function enumerateUtcMonths(startAt, endAt) {
  const start = new Date(startAt);
  const end = new Date(endAt);
  if (!Number.isFinite(start.getTime()) || !Number.isFinite(end.getTime()) || start >= end) {
    throw new Error(`Invalid month range: ${startAt}..${endAt}`);
  }

  const months = [];
  const cursor = new Date(Date.UTC(start.getUTCFullYear(), start.getUTCMonth(), 1));
  while (cursor < end) {
    months.push(`${cursor.getUTCFullYear()}-${String(cursor.getUTCMonth() + 1).padStart(2, "0")}`);
    cursor.setUTCMonth(cursor.getUTCMonth() + 1);
  }
  return months;
}

export function archiveName(symbol, interval, month) {
  return `${symbol}-${interval}-${month}.zip`;
}

export function archiveUrl(symbol, interval, month) {
  return `${ARCHIVE_ROOT}/${symbol}/${interval}/${archiveName(symbol, interval, month)}`;
}

export async function downloadBinanceUmKlineArchives({
  symbol = "BTCUSDT",
  interval = "5m",
  startAt,
  endAt,
  outDir,
  fetchImpl = globalThis.fetch,
  onProgress = () => {},
}) {
  if (typeof fetchImpl !== "function") throw new Error("fetch implementation is required");
  await fs.mkdir(outDir, { recursive: true });

  const downloaded = [];
  for (const month of enumerateUtcMonths(startAt, endAt)) {
    const name = archiveName(symbol, interval, month);
    const target = path.join(outDir, name);
    const url = archiveUrl(symbol, interval, month);
    const checksumUrl = `${url}.CHECKSUM`;
    const checksumResponse = await fetchImpl(checksumUrl);
    if (!checksumResponse.ok) {
      throw new Error(`Binance checksum download failed: ${checksumResponse.status} ${checksumUrl}`);
    }
    const checksumText = await checksumResponse.text();
    const expectedSha256 = parseChecksum(checksumText, name);

    const existingSha256 = await sha256FileOrNull(target);
    if (existingSha256 === expectedSha256) {
      downloaded.push({ month, path: target, sha256: expectedSha256, reused: true });
      onProgress({ month, reused: true, target });
      continue;
    }

    const response = await fetchImpl(url);
    if (!response.ok) throw new Error(`Binance archive download failed: ${response.status} ${url}`);
    const bytes = Buffer.from(await response.arrayBuffer());
    const actualSha256 = sha256(bytes);
    if (actualSha256 !== expectedSha256) {
      throw new Error(`Binance archive checksum mismatch for ${name}`);
    }

    const temporary = `${target}.part`;
    await fs.writeFile(temporary, bytes);
    await fs.rename(temporary, target);
    downloaded.push({ month, path: target, sha256: actualSha256, reused: false });
    onProgress({ month, reused: false, target });
  }
  return downloaded;
}

export async function loadBinanceUmKlines({
  directory,
  symbol = "BTCUSDT",
  interval = "5m",
  unzipBinary = "unzip",
}) {
  const prefix = `${symbol}-${interval}-`;
  const names = (await fs.readdir(directory))
    .filter((name) => name.startsWith(prefix) && name.endsWith(".zip"))
    .sort();
  if (names.length === 0) {
    throw new Error(`No Binance archives found in ${directory} for ${symbol} ${interval}`);
  }

  const byOpenedAt = new Map();
  for (const name of names) {
    const csv = execFileSync(unzipBinary, ["-p", path.join(directory, name)], {
      encoding: "utf8",
      maxBuffer: 64 * 1024 * 1024,
    });
    for (const row of parseBinanceUmKlineCsv(csv)) byOpenedAt.set(row.openedAtMs, row);
  }
  return [...byOpenedAt.values()].sort((left, right) => left.openedAtMs - right.openedAtMs);
}

export function parseBinanceUmKlineCsv(csv) {
  return csv
    .trim()
    .split(/\r?\n/)
    .filter(Boolean)
    .map((line) => parseBinanceUmKlineLine(line))
    .filter((row) => row != null);
}

export function attachBinanceFlow(candles, rows, { rollingWindow = 48 } = {}) {
  const byOpenedAt = new Map(rows.map((row) => [row.openedAtMs, row]));
  const imbalanceHistory = [];
  const quoteVolumeHistory = [];
  let matched = 0;

  for (const candle of candles) {
    const row = byOpenedAt.get(candle.openedAtMs);
    if (row == null) continue;
    matched += 1;

    candle.binanceClose = row.close;
    candle.binanceReturnPct = row.open > 0 ? ((row.close / row.open) - 1) * 100 : 0;
    candle.binanceQuoteVolume = row.quoteVolume;
    candle.binanceTakerImbalance = row.takerImbalance;
    candle.binanceFlowZ = zScoreAgainstHistory(row.takerImbalance, imbalanceHistory);
    candle.binanceRelativeQuoteVolume = ratioAgainstHistory(row.quoteVolume, quoteVolumeHistory);

    imbalanceHistory.push(row.takerImbalance);
    quoteVolumeHistory.push(row.quoteVolume);
    if (imbalanceHistory.length > rollingWindow) imbalanceHistory.shift();
    if (quoteVolumeHistory.length > rollingWindow) quoteVolumeHistory.shift();

    candle.binanceFlow3 = weightedFlow(candles, candle.index, 3);
    candle.binanceFlow6 = weightedFlow(candles, candle.index, 6);
    candle.binanceFlow12 = weightedFlow(candles, candle.index, 12);
    candle.binanceFlow24 = weightedFlow(candles, candle.index, 24);
    candle.binanceFlow36 = weightedFlow(candles, candle.index, 36);
  }

  return {
    matched,
    totalCandles: candles.length,
    coveragePct: candles.length > 0 ? (matched / candles.length) * 100 : 0,
  };
}

function parseBinanceUmKlineLine(line) {
  const columns = line.split(",");
  if (columns.length < 11 || !/^\d+$/.test(columns[0])) return null;
  const openedAtRaw = Number(columns[0]);
  const openedAtMs = openedAtRaw >= 100_000_000_000_000 ? Math.trunc(openedAtRaw / 1_000) : openedAtRaw;
  const volume = Number(columns[5]);
  const takerBuyBaseVolume = Number(columns[9]);
  const takerSellBaseVolume = Math.max(0, volume - takerBuyBaseVolume);
  return {
    openedAtMs,
    open: Number(columns[1]),
    high: Number(columns[2]),
    low: Number(columns[3]),
    close: Number(columns[4]),
    volume,
    quoteVolume: Number(columns[7]),
    tradeCount: Number(columns[8]),
    takerBuyBaseVolume,
    takerSellBaseVolume,
    takerImbalance: volume > 0 ? (takerBuyBaseVolume - takerSellBaseVolume) / volume : 0,
  };
}

function weightedFlow(candles, endIndex, length) {
  const startIndex = endIndex - length + 1;
  if (startIndex < 0) return null;
  let signedQuoteVolume = 0;
  let quoteVolume = 0;
  for (let index = startIndex; index <= endIndex; index += 1) {
    const candle = candles[index];
    if (candle.binanceTakerImbalance == null || candle.binanceQuoteVolume == null) return null;
    signedQuoteVolume += candle.binanceTakerImbalance * candle.binanceQuoteVolume;
    quoteVolume += candle.binanceQuoteVolume;
  }
  return quoteVolume > 0 ? signedQuoteVolume / quoteVolume : 0;
}

function zScoreAgainstHistory(value, history) {
  if (history.length < 20) return null;
  const mean = history.reduce((sum, item) => sum + item, 0) / history.length;
  const variance = history.reduce((sum, item) => sum + ((item - mean) ** 2), 0) / history.length;
  const standardDeviation = Math.sqrt(variance);
  return standardDeviation > 0 ? (value - mean) / standardDeviation : 0;
}

function ratioAgainstHistory(value, history) {
  if (history.length < 20) return null;
  const mean = history.reduce((sum, item) => sum + item, 0) / history.length;
  return mean > 0 ? value / mean : null;
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
