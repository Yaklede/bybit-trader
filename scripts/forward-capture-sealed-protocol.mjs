#!/usr/bin/env node

import { createHash } from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { DatabaseSync } from "node:sqlite";

const DEFAULT_WINDOW_COUNT = 40;
const MIN_WINDOW_MONTHS = 1;
const MAX_WINDOW_MONTHS = 60;
const MAX_WINDOW_ATTEMPTS = 100_000;
const SYMBOL_PATTERN = /^[A-Z0-9]{2,30}$/;
const DEFAULT_SOURCE = "forward-order-book-and-taker-capture";
const ARCHIVE_SOURCE = "bybit-official-orderbook-archive-and-taker-history";
const TARDIS_MACHINE_SOURCE = "tardis-machine-orderbook-plus-bybit-taker-history";
const TARDIS_MACHINE_DATASET = "machine-normalized-book-snapshot-1m-v1";
const ALLOWED_SOURCES = new Set([DEFAULT_SOURCE, ARCHIVE_SOURCE, TARDIS_MACHINE_SOURCE]);
const MINUTE_MILLIS = 60_000;
const TIMEFRAMES = [
  { name: "M1", minutes: 1 },
  { name: "M5", minutes: 5 },
  { name: "M15", minutes: 15 },
];

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
    db: values.get("db") == null ? null : path.resolve(values.get("db")),
    symbol: (values.get("symbol") ?? "BTCUSDT").trim().toUpperCase(),
    start: values.get("start"),
    end: values.get("end"),
    seed: Number(values.get("seed")),
    windowCount: Number(values.get("window-count") ?? DEFAULT_WINDOW_COUNT),
    minMonths: Number(values.get("min-months") ?? MIN_WINDOW_MONTHS),
    maxMonths: Number(values.get("max-months") ?? MAX_WINDOW_MONTHS),
    source: values.get("source") ?? DEFAULT_SOURCE,
    out: values.get("out") == null ? null : path.resolve(values.get("out")),
  };

  if (options.db == null) throw new Error("db is required.");
  if (!SYMBOL_PATTERN.test(options.symbol)) throw new Error("Symbol must contain only uppercase letters and numbers.");
  if (!isUtcMonthBoundary(options.start) || !isUtcMonthBoundary(options.end)) {
    throw new Error("start/end must be ISO-8601 UTC month boundaries.");
  }
  if (Date.parse(options.end) <= Date.parse(options.start)) throw new Error("end must be after start.");
  if (!Number.isInteger(options.seed) || options.seed < 0 || options.seed > 0xffff_ffff) {
    throw new Error("seed must be an integer between 0 and 4294967295.");
  }
  if (!Number.isInteger(options.windowCount) || options.windowCount < 20 || options.windowCount > 40) {
    throw new Error("window-count must be an integer between 20 and 40.");
  }
  if (!Number.isInteger(options.minMonths) || options.minMonths < 1 || options.minMonths > MAX_WINDOW_MONTHS) {
    throw new Error("min-months must be an integer between 1 and 60.");
  }
  if (!Number.isInteger(options.maxMonths) || options.maxMonths < options.minMonths || options.maxMonths > MAX_WINDOW_MONTHS) {
    throw new Error("max-months must be an integer between min-months and 60.");
  }
  if (calendarMonthDifference(options.start, options.end) < options.maxMonths) {
    throw new Error("The observation window must cover at least max-months full calendar months.");
  }
  if (!ALLOWED_SOURCES.has(options.source)) {
    throw new Error(`source must be one of: ${[...ALLOWED_SOURCES].join(", ")}.`);
  }
  return options;
}

export function expectedCoverage(options) {
  const startMillis = Date.parse(options.start);
  const endMillis = Date.parse(options.end);
  const expectedM1Bars = (endMillis - startMillis) / MINUTE_MILLIS;
  return {
    orderBook: expectedM1Bars,
    takerFlow: expectedM1Bars,
    candles: Object.fromEntries(TIMEFRAMES.map((timeframe) => [timeframe.name, expectedM1Bars / timeframe.minutes])),
  };
}

export function verifyCoverageStats(options, stats) {
  const expected = expectedCoverage(options);
  assertDatasetCoverage("orderBookImbalanceBars", stats.orderBook, expected.orderBook, options.start, options.end, 1);
  assertDatasetCoverage("takerFlowBars", stats.takerFlow, expected.takerFlow, options.start, options.end, 1);
  for (const timeframe of TIMEFRAMES) {
    assertDatasetCoverage(
      `marketCandles.${timeframe.name}`,
      stats.candles[timeframe.name],
      expected.candles[timeframe.name],
      options.start,
      options.end,
      timeframe.minutes,
    );
  }
  if (!stats.liquidationTablePresent) {
    throw new Error("liquidationFlowBars table is required even when no liquidation event is observed.");
  }
  return {
    expectedM1Bars: expected.orderBook,
    orderBookMinuteBars: stats.orderBook.count,
    takerFlowMinuteBars: stats.takerFlow.count,
    candles: Object.fromEntries(TIMEFRAMES.map((timeframe) => [timeframe.name, stats.candles[timeframe.name].count])),
    liquidationTablePresent: true,
  };
}

export function generateWindows(options) {
  const totalMonths = calendarMonthDifference(options.start, options.end);
  const random = mulberry32(options.seed);
  const windows = [];
  const seen = new Set();
  for (let attempts = 0; windows.length < options.windowCount && attempts < MAX_WINDOW_ATTEMPTS; attempts += 1) {
    const durationMonths = randomInt(random, options.minMonths, options.maxMonths);
    const monthOffset = randomInt(random, 0, totalMonths - durationMonths);
    const replayStartAt = addUtcMonths(options.start, monthOffset);
    const replayEndAt = addUtcMonths(replayStartAt, durationMonths);
    const key = `${replayStartAt}:${replayEndAt}`;
    if (seen.has(key)) continue;
    seen.add(key);
    windows.push({
      id: `S${String(windows.length + 1).padStart(2, "0")}`,
      durationMonths,
      replayStartAt,
      replayEndAt,
    });
  }
  if (windows.length !== options.windowCount) {
    throw new Error("Could not generate the requested number of unique forward-capture windows.");
  }
  return windows;
}

export function buildProtocol(options, coverage, archiveProvenance = null, tardisMachineProvenance = null) {
  const windows = generateWindows(options);
  return {
    schemaVersion: 1,
    status: "SEALED",
    purpose: "FORWARD_CAPTURE_VALIDATION",
    generation: {
      algorithm: "mulberry32-v1",
      seed: options.seed,
      tuningAllowed: false,
    },
    sourceData: {
      symbol: options.symbol,
      earliestAt: options.start,
      latestAt: options.end,
      source: options.source,
      captureCoverage: coverage,
      ...(archiveProvenance == null ? {} : { archiveProvenance }),
      ...(tardisMachineProvenance == null ? {} : { tardisMachineProvenance }),
    },
    gates: {
      minCompoundDailyReturnPct: 0.8,
      maxMarkToMarketDrawdownPct: 40,
      maxLiquidationCount: 0,
      minTradeCount: 3,
      minActiveDayCoveragePct: 2,
      requiredFillModelVersion: "causal-next-m1-open-v2",
    },
    windows,
    windowsSha256: sha256(windows),
  };
}

export function readCoverageStats(db, options) {
  for (const table of ["orderBookImbalanceBars", "takerFlowBars", "liquidationFlowBars", "marketCandles"]) {
    if (!tableExists(db, table)) throw new Error(`${table} table is required.`);
  }
  return {
    orderBook: readDatasetStats(db, "orderBookImbalanceBars", options.symbol, options.start, options.end, 1),
    takerFlow: readDatasetStats(db, "takerFlowBars", options.symbol, options.start, options.end, 1),
    candles: Object.fromEntries(
      TIMEFRAMES.map((timeframe) => [
        timeframe.name,
        readDatasetStats(db, "marketCandles", options.symbol, options.start, options.end, timeframe.minutes, timeframe.name),
      ]),
    ),
    liquidationTablePresent: true,
  };
}

export function verifyArchiveProvenance(db, options) {
  return verifyOrderBookImportProvenance(db, options, {
    provider: "bybit",
    dataset: "orderbook",
    label: "Archive",
  });
}

export function verifyTardisMachineProvenance(db, options) {
  return verifyOrderBookImportProvenance(db, options, {
    provider: "tardis",
    dataset: TARDIS_MACHINE_DATASET,
    label: "Tardis Machine",
  });
}

function verifyOrderBookImportProvenance(db, options, expected) {
  if (!tableExists(db, "historicalOrderBookImports")) {
    throw new Error(`historicalOrderBookImports table is required for a ${expected.label} source.`);
  }
  const manifests = db.prepare(`
    SELECT source_date, minute_bar_count, archive_sha256
    FROM historicalOrderBookImports
    WHERE provider=? AND dataset=? AND symbol=?
      AND source_date>=? AND source_date<?
    ORDER BY source_date ASC
  `).all(expected.provider, expected.dataset, options.symbol, options.start.slice(0, 10), options.end.slice(0, 10));
  const byDate = new Map(manifests.map((manifest) => [manifest.source_date, manifest]));
  let verifiedDays = 0;
  for (const sourceDate of utcDatesBetween(options.start, options.end)) {
    const manifest = byDate.get(sourceDate);
    if (manifest == null) throw new Error(`${expected.label} provenance is missing for ${sourceDate}.`);
    if (Number(manifest.minute_bar_count) !== 1_440 || !isSha256(manifest.archive_sha256)) {
      throw new Error(`${expected.label} provenance is invalid for ${sourceDate}.`);
    }
    verifiedDays += 1;
  }
  return {
    provider: expected.provider,
    dataset: expected.dataset,
    verifiedDays,
    firstSourceDate: options.start.slice(0, 10),
    lastSourceDate: previousUtcDate(options.end),
  };
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  await fs.access(options.db);
  const db = new DatabaseSync(options.db);
  try {
    const coverage = verifyCoverageStats(options, readCoverageStats(db, options));
    const archiveProvenance = options.source === ARCHIVE_SOURCE ? verifyArchiveProvenance(db, options) : null;
    const tardisMachineProvenance = options.source === TARDIS_MACHINE_SOURCE ? verifyTardisMachineProvenance(db, options) : null;
    const protocol = buildProtocol(options, coverage, archiveProvenance, tardisMachineProvenance);
    const output = `${JSON.stringify(protocol, null, 2)}\n`;
    if (options.out != null) {
      await fs.mkdir(path.dirname(options.out), { recursive: true });
      await fs.writeFile(options.out, output);
    }
    console.log(output);
  } finally {
    db.close();
  }
}

function assertDatasetCoverage(name, stats, expectedCount, start, end, intervalMinutes) {
  if (stats == null) throw new Error(`${name} coverage is unavailable.`);
  const expectedLastOpenedAt = new Date(Date.parse(end) - intervalMinutes * MINUTE_MILLIS).toISOString();
  if (
    stats.count !== expectedCount ||
    stats.firstOpenedAt !== start ||
    stats.lastOpenedAt !== expectedLastOpenedAt ||
    stats.gapCount !== 0
  ) {
    throw new Error(
      `${name} must be continuous from ${start} through ${expectedLastOpenedAt}; ` +
        `observed count=${stats.count}, first=${stats.firstOpenedAt}, last=${stats.lastOpenedAt}, gaps=${stats.gapCount}.`,
    );
  }
}

function readDatasetStats(db, table, symbol, start, end, intervalMinutes, timeframe = null) {
  const timeframeClause = timeframe == null ? "" : " AND timeframe = ?";
  const parameters = timeframe == null ? [symbol, start, end] : [symbol, start, end, timeframe];
  const row = db
    .prepare(
      `
        WITH captured AS (
          SELECT opened_at, strftime('%s', opened_at) AS opened_epoch
          FROM ${table}
          WHERE symbol = ? AND opened_at >= ? AND opened_at < ?${timeframeClause}
        ), ordered AS (
          SELECT opened_at, opened_epoch, LAG(opened_epoch) OVER (ORDER BY opened_at) AS previous_epoch
          FROM captured
        )
        SELECT
          count(*) AS count,
          min(opened_at) AS first_opened_at,
          max(opened_at) AS last_opened_at,
          coalesce(sum(CASE WHEN previous_epoch IS NOT NULL AND opened_epoch - previous_epoch != ? THEN 1 ELSE 0 END), 0) AS gap_count
        FROM ordered
      `,
    )
    .get(...parameters, intervalMinutes * 60);
  return {
    count: Number(row.count),
    firstOpenedAt: row.first_opened_at,
    lastOpenedAt: row.last_opened_at,
    gapCount: Number(row.gap_count),
  };
}

function tableExists(db, table) {
  return db.prepare("SELECT 1 AS present FROM sqlite_master WHERE type = 'table' AND name = ?").get(table) != null;
}

function isUtcMonthBoundary(value) {
  const date = new Date(value);
  return (
    Number.isFinite(date.getTime()) &&
    date.getUTCDate() === 1 &&
    date.getUTCHours() === 0 &&
    date.getUTCMinutes() === 0 &&
    date.getUTCSeconds() === 0 &&
    date.getUTCMilliseconds() === 0 &&
    date.toISOString() === value
  );
}

function addUtcMonths(value, months) {
  const date = new Date(value);
  return new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth() + months, 1)).toISOString();
}

function calendarMonthDifference(start, end) {
  const startDate = new Date(start);
  const endDate = new Date(end);
  return (endDate.getUTCFullYear() - startDate.getUTCFullYear()) * 12 + endDate.getUTCMonth() - startDate.getUTCMonth();
}

function* utcDatesBetween(start, end) {
  let date = start.slice(0, 10);
  const endDate = end.slice(0, 10);
  while (date < endDate) {
    yield date;
    const value = new Date(`${date}T00:00:00Z`);
    value.setUTCDate(value.getUTCDate() + 1);
    date = value.toISOString().slice(0, 10);
  }
}

function previousUtcDate(value) {
  const date = new Date(value);
  date.setUTCDate(date.getUTCDate() - 1);
  return date.toISOString().slice(0, 10);
}

function isSha256(value) {
  return typeof value === "string" && /^[a-f0-9]{64}$/.test(value);
}

function randomInt(random, minimum, maximum) {
  return minimum + Math.floor(random() * (maximum - minimum + 1));
}

function mulberry32(seed) {
  let state = seed >>> 0;
  return () => {
    state += 0x6d2b79f5;
    let value = state;
    value = Math.imul(value ^ (value >>> 15), value | 1);
    value ^= value + Math.imul(value ^ (value >>> 7), value | 61);
    return ((value ^ (value >>> 14)) >>> 0) / 4_294_967_296;
  };
}

function sha256(value) {
  return createHash("sha256").update(JSON.stringify(value)).digest("hex");
}

const scriptPath = fileURLToPath(import.meta.url);
if (process.argv[1] != null && path.resolve(process.argv[1]) === scriptPath) {
  main().catch((error) => {
    console.error(error.stack || error.message);
    process.exitCode = 1;
  });
}
