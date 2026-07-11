#!/usr/bin/env node

import { DatabaseSync } from "node:sqlite";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";

const MINUTES_PER_DAY = 1_440;
const ONE_MINUTE_MILLIS = 60_000;
const SYMBOL_PATTERN = /^[A-Z0-9]{2,30}$/;

export function parseArgs(argv) {
  const values = new Map();
  for (const argument of argv) {
    if (!argument.startsWith("--") || !argument.includes("=")) {
      throw new Error(`Invalid argument: ${argument}. Use --name=value.`);
    }
    const [name, ...rest] = argument.slice(2).split("=");
    if (!["db", "symbol", "start", "end"].includes(name)) {
      throw new Error(`Unsupported argument: --${name}.`);
    }
    values.set(name, rest.join("="));
  }
  const options = {
    db: values.get("db") == null ? null : resolve(values.get("db")),
    symbol: (values.get("symbol") ?? "BTCUSDT").toUpperCase(),
    start: values.get("start") ?? null,
    end: values.get("end") ?? null,
  };
  if (options.db == null) throw new Error("db is required.");
  if (!SYMBOL_PATTERN.test(options.symbol)) throw new Error("Symbol must contain only uppercase letters and numbers.");
  if (!isDate(options.start) || !isDate(options.end) || options.start > options.end) {
    throw new Error("start/end must be valid YYYY-MM-DD values with start <= end.");
  }
  return options;
}

export function auditArchiveCoverage(options, dependencies = {}) {
  const db = dependencies.db ?? new DatabaseSync(options.db);
  const ownsDatabase = dependencies.db == null;
  try {
    const manifests = db.prepare(`
      SELECT source_date, minute_bar_count, archive_sha256
      FROM historicalOrderBookImports
      WHERE provider='bybit' AND dataset='orderbook' AND symbol=?
        AND source_date>=? AND source_date<=?
      ORDER BY source_date ASC
    `).all(options.symbol, options.start, options.end);
    const manifestsByDate = new Map(manifests.map((manifest) => [manifest.source_date, manifest]));
    const days = [];
    for (const date of datesBetween(options.start, options.end)) {
      const manifest = manifestsByDate.get(date);
      if (manifest == null) {
        days.push({ date, status: "missing-manifest" });
        continue;
      }
      if (Number(manifest.minute_bar_count) !== MINUTES_PER_DAY || !isSha256(manifest.archive_sha256)) {
        days.push({ date, status: "invalid-manifest" });
        continue;
      }
      const bars = db.prepare(`
        SELECT opened_at FROM orderBookImbalanceBars
        WHERE symbol=? AND opened_at>=? AND opened_at<?
        ORDER BY opened_at ASC
      `).all(options.symbol, `${date}T00:00:00Z`, `${nextDate(date)}T00:00:00Z`);
      if (!hasContinuousMinutes(bars, date)) {
        days.push({ date, status: "stored-bars-invalid" });
        continue;
      }
      days.push({ date, status: "complete", archiveSha256: manifest.archive_sha256 });
    }
    const completeRanges = toRanges(days.filter((day) => day.status === "complete"));
    const gapRanges = toRanges(days.filter((day) => day.status !== "complete"), true);
    const statusCounts = Object.groupBy(days, (day) => day.status);
    return {
      schemaVersion: 1,
      symbol: options.symbol,
      requestedRange: { start: options.start, end: options.end, days: days.length },
      manifestDays: manifests.length,
      completeDays: statusCounts.complete?.length ?? 0,
      invalidDays: days.length - (statusCounts.complete?.length ?? 0),
      completeRanges,
      gapRanges,
    };
  } finally {
    if (ownsDatabase) db.close();
  }
}

export function hasContinuousMinutes(bars, date) {
  if (bars.length !== MINUTES_PER_DAY) return false;
  const start = Date.parse(`${date}T00:00:00Z`);
  return bars.every((bar, offset) => bar.opened_at === instantString(start + offset * ONE_MINUTE_MILLIS));
}

export function toRanges(days, includeReasons = false) {
  const ranges = [];
  for (const day of days) {
    const previous = ranges.at(-1);
    if (previous != null && previous.end === previousDate(day.date)) {
      previous.end = day.date;
      previous.days += 1;
      if (includeReasons && !previous.reasons.includes(day.status)) previous.reasons.push(day.status);
      continue;
    }
    ranges.push({
      start: day.date,
      end: day.date,
      days: 1,
      ...(includeReasons ? { reasons: [day.status] } : {}),
    });
  }
  return ranges;
}

function* datesBetween(start, end) {
  let date = start;
  while (date <= end) {
    yield date;
    date = nextDate(date);
  }
}

function nextDate(date) {
  const value = new Date(`${date}T00:00:00Z`);
  value.setUTCDate(value.getUTCDate() + 1);
  return value.toISOString().slice(0, 10);
}

function previousDate(date) {
  const value = new Date(`${date}T00:00:00Z`);
  value.setUTCDate(value.getUTCDate() - 1);
  return value.toISOString().slice(0, 10);
}

function instantString(epochMillis) {
  return new Date(epochMillis).toISOString().replace(".000Z", "Z");
}

function isDate(value) {
  return typeof value === "string" && /^\d{4}-\d{2}-\d{2}$/.test(value)
    && new Date(`${value}T00:00:00Z`).toISOString().slice(0, 10) === value;
}

function isSha256(value) {
  return typeof value === "string" && /^[a-f0-9]{64}$/.test(value);
}

const invokedPath = process.argv[1] ? pathToFileURL(resolve(process.argv[1])).href : null;
if (invokedPath === import.meta.url) {
  const report = auditArchiveCoverage(parseArgs(process.argv.slice(2)));
  console.log(JSON.stringify(report, null, 2));
}
