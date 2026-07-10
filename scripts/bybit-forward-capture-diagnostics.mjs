#!/usr/bin/env node

import fs from "node:fs/promises";
import { DatabaseSync } from "node:sqlite";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const DEFAULT_DB = "data/bybit-trader.sqlite";
const DEFAULT_SYMBOL = "BTCUSDT";
const DEFAULT_HORIZONS_M5 = [1, 3, 12];
const DEFAULT_ROUND_TRIP_COST_BPS = 12;
const DEFAULT_MIN_SAMPLES = 30;
const ORDER_BOOK_DIRECTION_THRESHOLD = 0.1;
const TAKER_FLOW_DIRECTION_THRESHOLD = 0.1;
const LIQUIDATION_DOMINANCE_THRESHOLD = 0.5;
const FIVE_MINUTES_MILLIS = 5 * 60_000;
const ONE_MINUTE_MILLIS = 60_000;
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

  const horizonsM5 = (values.get("horizons-m5") ?? DEFAULT_HORIZONS_M5.join(","))
    .split(",")
    .map((value) => Number(value.trim()));
  const options = {
    db: resolve(values.get("db") ?? DEFAULT_DB),
    symbol: (values.get("symbol") ?? DEFAULT_SYMBOL).trim().toUpperCase(),
    start: values.get("start"),
    end: values.get("end"),
    horizonsM5,
    roundTripCostBps: Number(values.get("round-trip-cost-bps") ?? DEFAULT_ROUND_TRIP_COST_BPS),
    minSamples: Number(values.get("min-samples") ?? DEFAULT_MIN_SAMPLES),
    out: values.get("out") == null ? null : resolve(values.get("out")),
  };

  if (!SYMBOL_PATTERN.test(options.symbol)) throw new Error("Symbol must contain only uppercase letters and numbers.");
  if (!isInstant(options.start) || !isInstant(options.end) || options.start > options.end) {
    throw new Error("start/end must be valid ISO-8601 instants with start <= end.");
  }
  if (
    options.horizonsM5.length === 0 ||
    options.horizonsM5.some((horizon) => !Number.isInteger(horizon) || horizon < 1 || horizon > 288)
  ) {
    throw new Error("horizons-m5 must contain unique integer values between 1 and 288.");
  }
  if (new Set(options.horizonsM5).size !== options.horizonsM5.length) {
    throw new Error("horizons-m5 must not contain duplicate values.");
  }
  if (!Number.isFinite(options.roundTripCostBps) || options.roundTripCostBps < 0 || options.roundTripCostBps > 500) {
    throw new Error("round-trip-cost-bps must be between 0 and 500.");
  }
  if (!Number.isInteger(options.minSamples) || options.minSamples < 1 || options.minSamples > 100_000) {
    throw new Error("min-samples must be an integer between 1 and 100000.");
  }
  return options;
}

export function orderBookDirection(imbalance) {
  if (!Number.isFinite(imbalance)) return "UNAVAILABLE";
  if (imbalance >= ORDER_BOOK_DIRECTION_THRESHOLD) return "BUY";
  if (imbalance <= -ORDER_BOOK_DIRECTION_THRESHOLD) return "SELL";
  return "NEUTRAL";
}

export function liquidationBand(longNotional, shortNotional) {
  const longValue = Number(longNotional);
  const shortValue = Number(shortNotional);
  if (!Number.isFinite(longValue) || !Number.isFinite(shortValue) || longValue < 0 || shortValue < 0) {
    return "UNAVAILABLE";
  }
  const total = longValue + shortValue;
  if (total === 0) return "NONE";
  const imbalance = (shortValue - longValue) / total;
  if (imbalance >= LIQUIDATION_DOMINANCE_THRESHOLD) return "SHORT_FLUSH";
  if (imbalance <= -LIQUIDATION_DOMINANCE_THRESHOLD) return "LONG_FLUSH";
  return "MIXED";
}

export function takerFlowDirection(buyNotional, sellNotional) {
  const buyValue = Number(buyNotional);
  const sellValue = Number(sellNotional);
  if (!Number.isFinite(buyValue) || !Number.isFinite(sellValue) || buyValue < 0 || sellValue < 0) {
    return "UNAVAILABLE";
  }
  const total = buyValue + sellValue;
  if (total === 0) return "NEUTRAL";
  const imbalance = (buyValue - sellValue) / total;
  if (imbalance >= TAKER_FLOW_DIRECTION_THRESHOLD) return "BUY";
  if (imbalance <= -TAKER_FLOW_DIRECTION_THRESHOLD) return "SELL";
  return "NEUTRAL";
}

export function aggregateOrderBookBuckets(rows) {
  const buckets = new Map();
  for (const row of rows) {
    const openedAt = new Date(row.opened_at);
    const openedAtMillis = openedAt.getTime();
    const sampleCount = Number(row.sample_count);
    if (!Number.isFinite(openedAtMillis) || !Number.isInteger(sampleCount) || sampleCount <= 0) continue;
    const bucketMillis = Math.floor(openedAtMillis / FIVE_MINUTES_MILLIS) * FIVE_MINUTES_MILLIS;
    const bucket =
      buckets.get(bucketMillis) ?? {
        openedAt: isoInstant(new Date(bucketMillis)),
        minuteStarts: new Set(),
        sampleCount: 0,
        weightedImbalance: 0,
        weightedSpreadBps: 0,
      };
    bucket.minuteStarts.add(openedAtMillis);
    bucket.sampleCount += sampleCount;
    bucket.weightedImbalance += Number(row.mean_imbalance) * sampleCount;
    bucket.weightedSpreadBps += Number(row.mean_spread_bps) * sampleCount;
    buckets.set(bucketMillis, bucket);
  }

  return [...buckets.values()]
    .map((bucket) => {
      const bucketMillis = new Date(bucket.openedAt).getTime();
      const complete =
        bucket.sampleCount > 0 &&
        [0, 1, 2, 3, 4].every((offset) => bucket.minuteStarts.has(bucketMillis + offset * ONE_MINUTE_MILLIS));
      return {
        openedAt: bucket.openedAt,
        complete,
        sampleCount: bucket.sampleCount,
        meanImbalance: bucket.weightedImbalance / bucket.sampleCount,
        meanSpreadBps: bucket.weightedSpreadBps / bucket.sampleCount,
      };
    })
    .sort((left, right) => left.openedAt.localeCompare(right.openedAt));
}

export function aggregateTakerFlowBuckets(rows) {
  const buckets = new Map();
  for (const row of rows) {
    const openedAtMillis = new Date(row.opened_at).getTime();
    const buyNotional = Number(row.taker_buy_notional);
    const sellNotional = Number(row.taker_sell_notional);
    const buyTradeCount = Number(row.buy_trade_count);
    const sellTradeCount = Number(row.sell_trade_count);
    if (
      !Number.isFinite(openedAtMillis) ||
      !Number.isFinite(buyNotional) ||
      !Number.isFinite(sellNotional) ||
      buyNotional < 0 ||
      sellNotional < 0 ||
      !Number.isInteger(buyTradeCount) ||
      !Number.isInteger(sellTradeCount) ||
      buyTradeCount < 0 ||
      sellTradeCount < 0
    ) {
      continue;
    }
    const bucketMillis = Math.floor(openedAtMillis / FIVE_MINUTES_MILLIS) * FIVE_MINUTES_MILLIS;
    const bucket =
      buckets.get(bucketMillis) ?? {
        openedAt: isoInstant(new Date(bucketMillis)),
        minuteStarts: new Set(),
        buyNotional: 0,
        sellNotional: 0,
        buyTradeCount: 0,
        sellTradeCount: 0,
      };
    bucket.minuteStarts.add(openedAtMillis);
    bucket.buyNotional += buyNotional;
    bucket.sellNotional += sellNotional;
    bucket.buyTradeCount += buyTradeCount;
    bucket.sellTradeCount += sellTradeCount;
    buckets.set(bucketMillis, bucket);
  }

  return [...buckets.values()]
    .map((bucket) => {
      const bucketMillis = new Date(bucket.openedAt).getTime();
      return {
        ...bucket,
        complete: [0, 1, 2, 3, 4].every((offset) => bucket.minuteStarts.has(bucketMillis + offset * ONE_MINUTE_MILLIS)),
      };
    })
    .sort((left, right) => left.openedAt.localeCompare(right.openedAt));
}

export function buildDiagnosticReport(options, loaded) {
  const costPct = options.roundTripCostBps / 100;
  const horizons = options.horizonsM5.map((horizonM5) => ({
    horizonM5,
    bookOnly: summarizeGroups(loaded.records, horizonM5, costPct, options.minSamples, (record) => `book=${record.direction}`),
    bookAndTaker: summarizeGroups(
      loaded.records,
      horizonM5,
      costPct,
      options.minSamples,
      (record) => `book=${record.direction}|taker=${record.takerDirection}`,
    ),
    bookTakerAndLiquidation: summarizeGroups(
      loaded.records,
      horizonM5,
      costPct,
      options.minSamples,
      (record) => `book=${record.direction}|taker=${record.takerDirection}|liquidation=${record.liquidationBand}`,
    ),
  }));

  return {
    metadata: {
      symbol: options.symbol,
      start: options.start,
      end: options.end,
      horizonsM5: options.horizonsM5,
      roundTripCostBps: options.roundTripCostBps,
      minimumSamples: options.minSamples,
      orderBookDirectionThreshold: ORDER_BOOK_DIRECTION_THRESHOLD,
      takerFlowDirectionThreshold: TAKER_FLOW_DIRECTION_THRESHOLD,
      liquidationDominanceThreshold: LIQUIDATION_DOMINANCE_THRESHOLD,
      causalEntryRule:
        "Aggregate complete five-minute order-book and taker-flow capture buckets, then enter at the next M5 candle open.",
      returnOrientation: "Returns are oriented by the order-book direction. Taker direction is a fixed conditioning dimension only.",
      interpretation: "Descriptive forward-data analysis only. It is not a strategy backtest, an execution decision, or a profitability claim.",
    },
    coverage: loaded.coverage,
    horizons,
  };
}

export function loadRecords(db, options) {
  const maxHorizonM5 = Math.max(...options.horizonsM5);
  const startAt = new Date(options.start);
  const endAt = new Date(options.end);
  const extendedEndAt = isoInstant(new Date(endAt.getTime() + (maxHorizonM5 + 1) * FIVE_MINUTES_MILLIS));
  const orderBookRows = db.prepare(`
    SELECT opened_at, sample_count, mean_imbalance, mean_spread_bps
    FROM orderBookImbalanceBars
    WHERE symbol = ? AND opened_at >= ? AND opened_at <= ?
    ORDER BY opened_at
  `).all(options.symbol, options.start, options.end);
  const liquidationRows = db.prepare(`
    SELECT opened_at, long_liquidation_notional, short_liquidation_notional
    FROM liquidationFlowBars
    WHERE symbol = ? AND opened_at >= ? AND opened_at <= ?
    ORDER BY opened_at
  `).all(options.symbol, options.start, options.end);
  const takerFlowRows = db.prepare(`
    SELECT opened_at, taker_buy_notional, taker_sell_notional, buy_trade_count, sell_trade_count
    FROM takerFlowBars
    WHERE symbol = ? AND opened_at >= ? AND opened_at <= ?
    ORDER BY opened_at
  `).all(options.symbol, options.start, options.end);
  const candles = db.prepare(`
    SELECT opened_at, open, close
    FROM marketCandles
    WHERE symbol = ? AND timeframe = 'M5' AND opened_at >= ? AND opened_at <= ?
    ORDER BY opened_at
  `).all(options.symbol, isoInstant(startAt), extendedEndAt);

  const liquidationByBucket = aggregateLiquidations(liquidationRows);
  const takerFlowByBucket = new Map(aggregateTakerFlowBuckets(takerFlowRows).map((bucket) => [bucket.openedAt, bucket]));
  const candlesByOpenedAt = new Map(candles.map((candle) => [candle.opened_at, candle]));
  const buckets = aggregateOrderBookBuckets(orderBookRows);
  const coverage = {
    orderBookMinuteBars: orderBookRows.length,
    completeOrderBookM5Buckets: 0,
    incompleteOrderBookM5Buckets: 0,
    takerFlowMinuteBars: takerFlowRows.length,
    completeTakerFlowM5Buckets: [...takerFlowByBucket.values()].filter((bucket) => bucket.complete).length,
    incompleteTakerFlowM5Buckets: [...takerFlowByBucket.values()].filter((bucket) => !bucket.complete).length,
    completeCommonM5Buckets: 0,
    skippedIncompleteCommonCapture: 0,
    liquidationsObservedM5Buckets: liquidationByBucket.size,
    usableDirectionalBuckets: 0,
    skippedNeutralOrderBook: 0,
    neutralTakerFlowBuckets: 0,
    skippedMissingEntryCandle: 0,
    skippedMissingExitCandle: 0,
  };
  const records = [];

  for (const bucket of buckets) {
    if (!bucket.complete) {
      coverage.incompleteOrderBookM5Buckets += 1;
      continue;
    }
    coverage.completeOrderBookM5Buckets += 1;
    const takerFlow = takerFlowByBucket.get(bucket.openedAt);
    if (takerFlow == null || !takerFlow.complete) {
      coverage.skippedIncompleteCommonCapture += 1;
      continue;
    }
    coverage.completeCommonM5Buckets += 1;
    const direction = orderBookDirection(bucket.meanImbalance);
    if (direction === "NEUTRAL" || direction === "UNAVAILABLE") {
      coverage.skippedNeutralOrderBook += 1;
      continue;
    }
    const entryOpenedAt = isoInstant(new Date(new Date(bucket.openedAt).getTime() + FIVE_MINUTES_MILLIS));
    const entryCandle = candlesByOpenedAt.get(entryOpenedAt);
    if (entryCandle == null || !isPositiveFinite(Number(entryCandle.open))) {
      coverage.skippedMissingEntryCandle += 1;
      continue;
    }
    const returnsByHorizon = {};
    let completeFuture = true;
    for (const horizonM5 of options.horizonsM5) {
      const exitOpenedAt = isoInstant(new Date(new Date(entryOpenedAt).getTime() + (horizonM5 - 1) * FIVE_MINUTES_MILLIS));
      const exitCandle = candlesByOpenedAt.get(exitOpenedAt);
      if (exitCandle == null || !isPositiveFinite(Number(exitCandle.close))) {
        completeFuture = false;
        continue;
      }
      const rawReturnPct = ((Number(exitCandle.close) - Number(entryCandle.open)) / Number(entryCandle.open)) * 100;
      returnsByHorizon[horizonM5] = direction === "BUY" ? rawReturnPct : -rawReturnPct;
    }
    if (!completeFuture) coverage.skippedMissingExitCandle += 1;
    coverage.usableDirectionalBuckets += 1;
    const liquidation = liquidationByBucket.get(bucket.openedAt) ?? { longNotional: 0, shortNotional: 0 };
    const takerDirection = takerFlowDirection(takerFlow.buyNotional, takerFlow.sellNotional);
    if (takerDirection === "NEUTRAL") coverage.neutralTakerFlowBuckets += 1;
    records.push({
      openedAt: bucket.openedAt,
      direction,
      meanImbalance: bucket.meanImbalance,
      meanSpreadBps: bucket.meanSpreadBps,
      takerDirection,
      takerBuyNotional: takerFlow.buyNotional,
      takerSellNotional: takerFlow.sellNotional,
      takerBuyTradeCount: takerFlow.buyTradeCount,
      takerSellTradeCount: takerFlow.sellTradeCount,
      liquidationBand: liquidationBand(liquidation.longNotional, liquidation.shortNotional),
      returnsByHorizon,
    });
  }

  return { records, coverage };
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  const db = new DatabaseSync(options.db, { readOnly: true });
  try {
    const report = buildDiagnosticReport(options, loadRecords(db, options));
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

function aggregateLiquidations(rows) {
  const buckets = new Map();
  for (const row of rows) {
    const openedAtMillis = new Date(row.opened_at).getTime();
    if (!Number.isFinite(openedAtMillis)) continue;
    const bucketOpenedAt = isoInstant(new Date(Math.floor(openedAtMillis / FIVE_MINUTES_MILLIS) * FIVE_MINUTES_MILLIS));
    const bucket = buckets.get(bucketOpenedAt) ?? { longNotional: 0, shortNotional: 0 };
    bucket.longNotional += Number(row.long_liquidation_notional);
    bucket.shortNotional += Number(row.short_liquidation_notional);
    buckets.set(bucketOpenedAt, bucket);
  }
  return buckets;
}

function summarizeGroups(records, horizonM5, costPct, minSamples, keyFor) {
  const groups = new Map();
  for (const record of records) {
    const grossReturnPct = record.returnsByHorizon[horizonM5];
    if (!Number.isFinite(grossReturnPct)) continue;
    const key = keyFor(record);
    const returns = groups.get(key) ?? [];
    returns.push(grossReturnPct - costPct);
    groups.set(key, returns);
  }
  return [...groups.entries()]
    .map(([group, returns]) => ({ group, ...summarizeReturns(returns) }))
    .filter((summary) => summary.samples >= minSamples)
    .sort((left, right) => right.meanNetReturnPct - left.meanNetReturnPct);
}

function summarizeReturns(returns) {
  const sorted = [...returns].sort((left, right) => left - right);
  const wins = returns.filter((value) => value > 0).length;
  return {
    samples: returns.length,
    meanNetReturnPct: round(mean(returns)),
    medianNetReturnPct: round(median(sorted)),
    winRatePct: round((wins / returns.length) * 100),
    worstNetReturnPct: round(sorted[0]),
    bestNetReturnPct: round(sorted[sorted.length - 1]),
  };
}

function isInstant(value) {
  return typeof value === "string" && !Number.isNaN(Date.parse(value));
}

function isPositiveFinite(value) {
  return Number.isFinite(value) && value > 0;
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
