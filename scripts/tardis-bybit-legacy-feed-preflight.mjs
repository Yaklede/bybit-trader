#!/usr/bin/env node

import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const DEFAULT_API_URL = "https://api.tardis.dev/v1/data-feeds/bybit";
const SYMBOL_PATTERN = /^[A-Z0-9]{2,30}$/;
const MIN_ORDER_BOOK_DEPTH = 50;

export function parseArgs(argv) {
  const values = new Map();
  for (const argument of argv) {
    if (!argument.startsWith("--") || !argument.includes("=")) {
      throw new Error(`Invalid argument: ${argument}. Use --name=value.`);
    }
    const [name, ...rest] = argument.slice(2).split("=");
    if (!["symbol", "date", "api-url", "api-key-env", "out"].includes(name)) {
      throw new Error(`Unsupported argument: --${name}.`);
    }
    values.set(name, rest.join("="));
  }
  const options = {
    symbol: (values.get("symbol") ?? "BTCUSDT").toUpperCase(),
    date: values.get("date") ?? null,
    apiUrl: values.get("api-url") ?? DEFAULT_API_URL,
    apiKeyEnv: values.get("api-key-env") ?? "TARDIS_API_KEY",
    out: values.get("out") == null ? null : path.resolve(values.get("out")),
  };
  if (!SYMBOL_PATTERN.test(options.symbol)) throw new Error("Symbol must contain only uppercase letters and numbers.");
  if (!isDate(options.date)) throw new Error("date must be a valid YYYY-MM-DD value.");
  if (!options.apiUrl.startsWith("https://")) throw new Error("api-url must use HTTPS.");
  if (!/^[A-Z][A-Z0-9_]*$/.test(options.apiKeyEnv)) throw new Error("api-key-env must be an environment variable name.");
  return options;
}

export function buildLegacyFeedUrl(options) {
  const url = new URL(options.apiUrl);
  url.searchParams.set("from", options.date);
  url.searchParams.set("offset", "0");
  url.searchParams.set("filters", JSON.stringify([
    { channel: "orderBook_200", symbols: [options.symbol] },
    { channel: "trade", symbols: [options.symbol] },
  ]));
  return url;
}

export async function fetchLegacyFeedSample(options, fetchImpl = fetch, environment = process.env) {
  const headers = { "Accept-Encoding": "gzip" };
  const apiKey = environment[options.apiKeyEnv];
  if (apiKey != null && apiKey.trim()) headers.Authorization = `Bearer ${apiKey.trim()}`;
  const response = await fetchImpl(buildLegacyFeedUrl(options), { headers });
  if (!response.ok) throw new Error(`Tardis legacy feed request failed with HTTP ${response.status}.`);
  return response.text();
}

export function validateLegacyFeedSample(ndjson, { symbol, minimumDepth = MIN_ORDER_BOOK_DEPTH }) {
  const records = parseNdjson(ndjson);
  const book = { bids: new Map(), asks: new Map() };
  let lastCaptureAt = null;
  let snapshotCount = 0;
  let deltaCount = 0;
  let tradeCount = 0;
  let firstSnapshotAt = null;

  for (const record of records) {
    const capturedAt = instantNanos(record.capturedAt);
    if (lastCaptureAt != null && capturedAt < lastCaptureAt) {
      throw new Error("Tardis local capture timestamps must be nondecreasing.");
    }
    lastCaptureAt = capturedAt;
    if (record.message.topic === `trade.${symbol}`) {
      validateTrades(record.message.data, symbol);
      tradeCount += record.message.data.length;
      continue;
    }
    if (record.message.topic !== `orderBook_200.100ms.${symbol}`) continue;
    if (record.message.type === "snapshot") {
      applySnapshot(book, record.message.data, symbol);
      snapshotCount += 1;
      firstSnapshotAt ??= record.capturedAt;
      continue;
    }
    if (record.message.type === "delta") {
      if (snapshotCount === 0) throw new Error("Order-book delta arrived before an initial snapshot.");
      applyDelta(book, record.message.data, symbol);
      deltaCount += 1;
      continue;
    }
    throw new Error(`Unsupported legacy order-book message type: ${record.message.type}.`);
  }
  if (snapshotCount === 0) throw new Error("Legacy feed did not contain an order-book snapshot.");
  if (book.bids.size < minimumDepth || book.asks.size < minimumDepth) {
    throw new Error(`Legacy order book has insufficient depth: bids=${book.bids.size} asks=${book.asks.size} required=${minimumDepth}.`);
  }
  return {
    capturedRecordCount: records.length,
    snapshotCount,
    deltaCount,
    tradeCount,
    firstSnapshotAt,
    lastCapturedAt: records.at(-1).capturedAt,
    retainedBidLevels: book.bids.size,
    retainedAskLevels: book.asks.size,
  };
}

export function parseNdjson(ndjson) {
  const records = [];
  for (const line of ndjson.split(/\r?\n/)) {
    if (!line) continue;
    const separator = line.indexOf(" ");
    if (separator < 1) throw new Error("Tardis feed line is missing its local capture timestamp.");
    const capturedAt = line.slice(0, separator);
    const message = JSON.parse(line.slice(separator + 1));
    records.push({ capturedAt, message });
  }
  if (records.length === 0) throw new Error("Tardis feed sample is empty.");
  return records;
}

function applySnapshot(book, data, symbol) {
  if (!Array.isArray(data?.order_book)) throw new Error("Legacy order-book snapshot is malformed.");
  book.bids.clear();
  book.asks.clear();
  for (const level of data.order_book) setLevel(book, level, symbol);
}

function applyDelta(book, data, symbol) {
  if (data == null || typeof data !== "object") throw new Error("Legacy order-book delta is malformed.");
  for (const level of data?.delete ?? []) deleteLevel(book, level, symbol);
  for (const level of data?.update ?? []) setLevel(book, level, symbol);
  for (const level of data?.insert ?? []) setLevel(book, level, symbol);
}

function setLevel(book, level, symbol) {
  validateLevel(level, symbol);
  const target = level.side === "Buy" ? book.bids : book.asks;
  target.set(level.price, Number(level.size));
}

function deleteLevel(book, level, symbol) {
  validateLevel(level, symbol, false);
  const target = level.side === "Buy" ? book.bids : book.asks;
  target.delete(level.price);
}

function validateLevel(level, symbol, requireSize = true) {
  if (level?.symbol !== symbol || !["Buy", "Sell"].includes(level.side) || !isPositiveNumber(level.price) ||
    (requireSize && !isPositiveNumber(level.size))) {
    throw new Error("Legacy order-book level is malformed.");
  }
}

function validateTrades(trades, symbol) {
  if (!Array.isArray(trades) || trades.some((trade) => trade.symbol !== symbol || !["Buy", "Sell"].includes(trade.side) || !isPositiveNumber(trade.price) || !isPositiveNumber(trade.size))) {
    throw new Error("Legacy trade message is malformed.");
  }
}

function instantNanos(value) {
  const match = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d+))?Z$/.exec(value);
  if (match == null) throw new Error(`Invalid Tardis local capture timestamp: ${value}.`);
  const seconds = Date.parse(`${match[1]}.000Z`);
  if (!Number.isFinite(seconds)) throw new Error(`Invalid Tardis local capture timestamp: ${value}.`);
  const fraction = (match[2] ?? "").padEnd(9, "0").slice(0, 9);
  return BigInt(seconds) * 1_000_000n + BigInt(fraction);
}

function isPositiveNumber(value) {
  return Number.isFinite(Number(value)) && Number(value) > 0;
}

function isDate(value) {
  return typeof value === "string" && /^\d{4}-\d{2}-\d{2}$/.test(value)
    && new Date(`${value}T00:00:00Z`).toISOString().slice(0, 10) === value;
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  const report = {
    schemaVersion: 1,
    provider: "tardis",
    exchange: "bybit",
    symbol: options.symbol,
    date: options.date,
    usedApiCredential: Boolean(process.env[options.apiKeyEnv]?.trim()),
    ...validateLegacyFeedSample(await fetchLegacyFeedSample(options), options),
  };
  const output = `${JSON.stringify(report, null, 2)}\n`;
  if (options.out != null) {
    await fs.mkdir(path.dirname(options.out), { recursive: true });
    await fs.writeFile(options.out, output);
  }
  console.log(output);
}

const scriptPath = fileURLToPath(import.meta.url);
if (process.argv[1] != null && path.resolve(process.argv[1]) === scriptPath) {
  main().catch((error) => {
    console.error(error.stack || error.message);
    process.exitCode = 1;
  });
}
