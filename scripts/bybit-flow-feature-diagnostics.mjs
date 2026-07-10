#!/usr/bin/env node

import fs from "node:fs/promises";
import { DatabaseSync } from "node:sqlite";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const DEFAULT_DB = "build/runtime-test/bybit-trader-full-history.sqlite";
const DEFAULT_SYMBOL = "BTCUSDT";
const DEFAULT_HORIZON_M5 = 3;
const DEFAULT_ROUND_TRIP_COST_BPS = 12;
const DEFAULT_OI_LOOKBACK = 3;
const DEFAULT_MIN_SAMPLES = 30;
const SYMBOL_PATTERN = /^[A-Z0-9]{2,30}$/;

export function parseArgs(argv) {
  const values = new Map();
  for (const argument of argv) {
    if (!argument.startsWith("--") || !argument.includes("=")) {
      throw new Error(`Invalid argument: ${argument}. Use --name=value.`);
    }
    const [name, ...rest] = argument.slice(2).split("=");
    values.set(name, rest.join("="));
  }

  const options = {
    db: resolve(values.get("db") ?? DEFAULT_DB),
    symbol: (values.get("symbol") ?? DEFAULT_SYMBOL).toUpperCase(),
    start: values.get("start"),
    end: values.get("end"),
    horizonM5: Number(values.get("horizon-m5") ?? DEFAULT_HORIZON_M5),
    roundTripCostBps: Number(values.get("round-trip-cost-bps") ?? DEFAULT_ROUND_TRIP_COST_BPS),
    openInterestLookback: Number(values.get("oi-lookback") ?? DEFAULT_OI_LOOKBACK),
    minSamples: Number(values.get("min-samples") ?? DEFAULT_MIN_SAMPLES),
    out: values.get("out") == null ? null : resolve(values.get("out")),
  };
  if (!SYMBOL_PATTERN.test(options.symbol)) throw new Error("Symbol must contain only uppercase letters and numbers.");
  if (!isInstant(options.start) || !isInstant(options.end) || options.start >= options.end) {
    throw new Error("start/end must be valid ISO-8601 instants with start < end.");
  }
  if (!Number.isInteger(options.horizonM5) || ![1, 2, 3, 6, 12].includes(options.horizonM5)) {
    throw new Error("horizon-m5 must be one of 1, 2, 3, 6, or 12.");
  }
  if (!Number.isFinite(options.roundTripCostBps) || options.roundTripCostBps < 0 || options.roundTripCostBps > 500) {
    throw new Error("round-trip-cost-bps must be between 0 and 500.");
  }
  if (!Number.isInteger(options.openInterestLookback) || options.openInterestLookback < 2 || options.openInterestLookback > 100) {
    throw new Error("oi-lookback must be an integer between 2 and 100.");
  }
  if (!Number.isInteger(options.minSamples) || options.minSamples < 1 || options.minSamples > 100_000) {
    throw new Error("min-samples must be an integer between 1 and 100000.");
  }
  return options;
}

export function imbalanceBand(imbalance) {
  const absolute = Math.abs(imbalance);
  if (absolute < 0.25) return "LOW";
  if (absolute < 0.5) return "MEDIUM";
  if (absolute < 0.75) return "HIGH";
  return "EXTREME";
}

export function relativeVolumeBand(relativeVolume) {
  if (relativeVolume < 1) return "BELOW_AVERAGE";
  if (relativeVolume < 2) return "NORMAL";
  if (relativeVolume < 3) return "ELEVATED";
  return "SURGE";
}

export function oiChangeBand(changePct) {
  if (changePct == null) return "UNAVAILABLE";
  if (changePct <= -0.25) return "CONTRACTING";
  if (changePct >= 0.25) return "EXPANDING";
  return "FLAT";
}

export function accountCrowdingBand(buyRatio) {
  if (!Number.isFinite(buyRatio) || buyRatio < 0 || buyRatio > 1) return "UNAVAILABLE";
  if (buyRatio <= 0.45) return "SHORT_HEAVY";
  if (buyRatio >= 0.55) return "LONG_HEAVY";
  return "BALANCED";
}

export function summarizeReturns(returns, costPct) {
  if (returns.length === 0) return null;
  const netReturns = returns.map((value) => value - costPct);
  const sorted = [...netReturns].sort((left, right) => left - right);
  const wins = netReturns.filter((value) => value > 0).length;
  return {
    samples: netReturns.length,
    meanNetReturnPct: round(mean(netReturns)),
    medianNetReturnPct: round(median(sorted)),
    winRatePct: round((wins / netReturns.length) * 100),
    worstNetReturnPct: round(sorted[0]),
    bestNetReturnPct: round(sorted[sorted.length - 1]),
  };
}

export function buildDiagnosticReport(options, records) {
  const costPct = options.roundTripCostBps / 100;
  const flowGroups = new Map();
  const flowOiGroups = new Map();
  const flowOiCrowdingGroups = new Map();
  let skippedMissingFlow = 0;
  let skippedNoDirection = 0;
  let missingAccountRatio = 0;

  for (const record of records) {
    if (!record.completeFlow) {
      skippedMissingFlow += 1;
      continue;
    }
    if (record.candleSide == null || record.futureReturnPct == null) {
      skippedNoDirection += 1;
      continue;
    }
    const flowDirection = record.imbalance >= 0 ? "BUY" : "SELL";
    const alignment = flowDirection === record.candleSide ? "ALIGNED" : "OPPOSED";
    const flowKey = `flow=${alignment}|imbalance=${imbalanceBand(record.imbalance)}|volume=${relativeVolumeBand(record.relativeVolume)}`;
    appendReturns(flowGroups, flowKey, record.futureReturnPct);
    const flowOiKey = `${flowKey}|oi=${oiChangeBand(record.openInterestChangePct)}`;
    appendReturns(flowOiGroups, flowOiKey, record.futureReturnPct);
    const crowding = accountCrowdingBand(record.accountBuyRatio);
    if (crowding === "UNAVAILABLE") missingAccountRatio += 1;
    appendReturns(flowOiCrowdingGroups, `${flowOiKey}|crowding=${crowding}`, record.futureReturnPct);
  }

  return {
    metadata: {
      symbol: options.symbol,
      start: options.start,
      end: options.end,
      horizonM5Candles: options.horizonM5,
      roundTripCostBps: options.roundTripCostBps,
      minimumSamples: options.minSamples,
      interpretation: "Descriptive conditional return analysis only. It is not a fill, sizing, liquidation, or strategy backtest.",
    },
    coverage: {
      evaluatedM5Candles: records.length,
      eligibleM5Candles: records.length - skippedMissingFlow - skippedNoDirection,
      skippedMissingFlow,
      skippedNoDirection,
      missingAccountRatio,
    },
    flowOnly: summarizeGroups(flowGroups, costPct, options.minSamples),
    flowAndOpenInterest: summarizeGroups(flowOiGroups, costPct, options.minSamples),
    flowAndOpenInterestAndAccountRatio: summarizeGroups(flowOiCrowdingGroups, costPct, options.minSamples),
  };
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  const db = new DatabaseSync(options.db, { readOnly: true });
  try {
    const records = loadRecords(db, options);
    const report = buildDiagnosticReport(options, records);
    const output = JSON.stringify(report, null, 2);
    if (options.out != null) {
      await fs.mkdir(dirname(options.out), { recursive: true });
      await fs.writeFile(options.out, output);
    }
    console.log(output);
  } finally {
    db.close();
  }
}

function loadRecords(db, options) {
  const startAt = new Date(options.start);
  const replayEndAt = new Date(options.end);
  const candleEndAt = isoInstant(new Date(replayEndAt.getTime() + options.horizonM5 * 5 * 60_000));
  const candles = db.prepare(`
    SELECT opened_at, open, close, volume
    FROM marketCandles
    WHERE symbol = ? AND timeframe = 'M5' AND opened_at >= ? AND opened_at <= ?
    ORDER BY opened_at
  `).all(options.symbol, options.start, candleEndAt);
  const flowByBucket = loadFlowBuckets(db, options.symbol, options.start, isoInstant(replayEndAt));
  const openInterest = db.prepare(`
    SELECT timestamp, open_interest
    FROM openInterestSnapshots
    WHERE symbol = ? AND interval = 'M5' AND timestamp <= ?
    ORDER BY timestamp
  `).all(options.symbol, isoInstant(replayEndAt));
  const accountRatios = db.prepare(`
    SELECT timestamp, buy_ratio, sell_ratio
    FROM accountRatioSnapshots
    WHERE symbol = ? AND period = 'M5' AND timestamp <= ?
    ORDER BY timestamp
  `).all(options.symbol, isoInstant(replayEndAt));

  const indexByOpenedAt = new Map(candles.map((candle, index) => [candle.opened_at, index]));
  const rollingVolumes = rollingAverage(candles.map((candle) => Number(candle.volume)), 20);
  const oiLookup = new OpenInterestLookup(openInterest, options.openInterestLookback);
  const accountRatioLookup = new AccountRatioLookup(accountRatios);
  const records = [];

  for (let index = 0; index < candles.length - options.horizonM5; index += 1) {
    const candle = candles[index];
    const openedAt = new Date(candle.opened_at);
    if (openedAt < startAt || openedAt > replayEndAt) continue;
    const future = candles[index + options.horizonM5];
    const expectedFutureOpenedAt = isoInstant(new Date(openedAt.getTime() + options.horizonM5 * 5 * 60_000));
    if (future.opened_at !== expectedFutureOpenedAt || !indexByOpenedAt.has(expectedFutureOpenedAt)) continue;
    const open = Number(candle.open);
    const close = Number(candle.close);
    const futureClose = Number(future.close);
    if (!isPositiveFinite(open) || !isPositiveFinite(close) || !isPositiveFinite(futureClose)) continue;
    const bucket = flowByBucket.get(candle.opened_at);
    const candleSide = close > open ? "BUY" : close < open ? "SELL" : null;
    const rawReturnPct = ((futureClose - close) / close) * 100;
    const sideReturnPct = candleSide === "BUY" ? rawReturnPct : candleSide === "SELL" ? -rawReturnPct : null;
    const relativeVolume = rollingVolumes[index] == null || rollingVolumes[index] <= 0
      ? null
      : Number(candle.volume) / rollingVolumes[index];
    if (relativeVolume == null || !Number.isFinite(relativeVolume)) continue;
    const closedAt = new Date(openedAt.getTime() + 5 * 60_000);
    records.push({
      completeFlow: bucket?.complete ?? false,
      candleSide,
      imbalance: bucket?.imbalance ?? 0,
      relativeVolume,
      openInterestChangePct: oiLookup.changeAtOrBefore(closedAt),
      accountBuyRatio: accountRatioLookup.buyRatioAtOrBefore(closedAt),
      futureReturnPct: sideReturnPct,
    });
  }
  return records;
}

function loadFlowBuckets(db, symbol, start, end) {
  const rows = db.prepare(`
    SELECT
      strftime('%Y-%m-%dT%H:%M:%SZ', (CAST(strftime('%s', opened_at) AS INTEGER) / 300) * 300, 'unixepoch') AS bucket_opened_at,
      COUNT(*) AS minute_count,
      SUM(CAST(taker_buy_notional AS REAL)) AS buy_notional,
      SUM(CAST(taker_sell_notional AS REAL)) AS sell_notional
    FROM takerFlowBars
    WHERE symbol = ? AND opened_at >= ? AND opened_at <= ?
    GROUP BY bucket_opened_at
    ORDER BY bucket_opened_at
  `).all(symbol, start, end);
  const buckets = new Map();
  for (const row of rows) {
    const buyNotional = Number(row.buy_notional);
    const sellNotional = Number(row.sell_notional);
    const totalNotional = buyNotional + sellNotional;
    buckets.set(row.bucket_opened_at, {
      complete: Number(row.minute_count) === 5 && totalNotional > 0,
      imbalance: totalNotional <= 0 ? 0 : (buyNotional - sellNotional) / totalNotional,
    });
  }
  return buckets;
}

class OpenInterestLookup {
  constructor(rows, lookback) {
    this.rows = rows.map((row) => ({ timestamp: new Date(row.timestamp), openInterest: Number(row.open_interest) }));
    this.lookback = lookback;
    this.pointer = 0;
  }

  changeAtOrBefore(decisionAt) {
    while (this.pointer + 1 < this.rows.length && this.rows[this.pointer + 1].timestamp <= decisionAt) {
      this.pointer += 1;
    }
    if (this.rows.length === 0 || this.rows[this.pointer].timestamp > decisionAt || this.pointer + 1 < this.lookback) {
      return null;
    }
    const latest = this.rows[this.pointer].openInterest;
    const baseline = this.rows[this.pointer - this.lookback + 1].openInterest;
    if (!isPositiveFinite(latest) || !isPositiveFinite(baseline)) return null;
    return ((latest - baseline) / baseline) * 100;
  }
}

class AccountRatioLookup {
  constructor(rows) {
    this.rows = rows.map((row) => ({
      timestamp: new Date(row.timestamp),
      buyRatio: Number(row.buy_ratio),
      sellRatio: Number(row.sell_ratio),
    }));
    this.pointer = 0;
  }

  buyRatioAtOrBefore(decisionAt) {
    while (this.pointer + 1 < this.rows.length && this.rows[this.pointer + 1].timestamp <= decisionAt) {
      this.pointer += 1;
    }
    if (this.rows.length === 0 || this.rows[this.pointer].timestamp > decisionAt) return null;
    const snapshot = this.rows[this.pointer];
    if (!Number.isFinite(snapshot.buyRatio) || !Number.isFinite(snapshot.sellRatio)) return null;
    return snapshot.buyRatio;
  }
}

function appendReturns(groups, key, continuationReturnPct) {
  const group = groups.get(key) ?? { continuation: [], reversal: [] };
  group.continuation.push(continuationReturnPct);
  group.reversal.push(-continuationReturnPct);
  groups.set(key, group);
}

function summarizeGroups(groups, costPct, minSamples) {
  return [...groups.entries()]
    .map(([group, returns]) => ({
      group,
      continuation: summarizeReturns(returns.continuation, costPct),
      reversal: summarizeReturns(returns.reversal, costPct),
    }))
    .filter((summary) => summary.continuation.samples >= minSamples)
    .sort((left, right) => {
      const leftBest = Math.max(left.continuation.meanNetReturnPct, left.reversal.meanNetReturnPct);
      const rightBest = Math.max(right.continuation.meanNetReturnPct, right.reversal.meanNetReturnPct);
      return rightBest - leftBest;
    });
}

function rollingAverage(values, lookback) {
  const result = Array(values.length).fill(null);
  let sum = 0;
  for (let index = 0; index < values.length; index += 1) {
    if (index >= lookback) result[index] = sum / lookback;
    sum += values[index];
    if (index >= lookback) sum -= values[index - lookback];
  }
  return result;
}

function mean(values) {
  return values.reduce((sum, value) => sum + value, 0) / values.length;
}

function median(sortedValues) {
  const middle = Math.floor(sortedValues.length / 2);
  return sortedValues.length % 2 === 0
    ? (sortedValues[middle - 1] + sortedValues[middle]) / 2
    : sortedValues[middle];
}

function round(value) {
  return Math.round(value * 100000) / 100000;
}

function isInstant(value) {
  return typeof value === "string" && !Number.isNaN(Date.parse(value));
}

function isPositiveFinite(value) {
  return Number.isFinite(value) && value > 0;
}

function isoInstant(value) {
  return value.toISOString().replace(".000Z", "Z");
}

const scriptPath = fileURLToPath(import.meta.url);
if (process.argv[1] != null && resolve(process.argv[1]) === scriptPath) {
  main().catch((error) => {
    console.error(error.stack || error.message);
    process.exitCode = 1;
  });
}
