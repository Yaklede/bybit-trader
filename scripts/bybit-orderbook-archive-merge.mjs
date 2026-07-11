#!/usr/bin/env node

import { DatabaseSync } from "node:sqlite";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { ensureSchema } from "./bybit-orderbook-backfill.mjs";

const MINUTES_PER_DAY = 1_440;

export function parseArgs(argv) {
  let targetDb = null;
  const sourceDbs = [];
  for (const argument of argv) {
    if (!argument.startsWith("--") || !argument.includes("=")) {
      throw new Error(`Invalid argument: ${argument}. Use --name=value.`);
    }
    const [name, ...rest] = argument.slice(2).split("=");
    const value = rest.join("=");
    if (name === "target-db") targetDb = resolve(value);
    else if (name === "source-db") sourceDbs.push(resolve(value));
    else throw new Error(`Unsupported argument: --${name}.`);
  }
  if (targetDb == null) throw new Error("target-db is required.");
  if (sourceDbs.length === 0) throw new Error("At least one source-db is required.");
  if (new Set(sourceDbs).size !== sourceDbs.length) throw new Error("source-db values must be unique.");
  if (sourceDbs.includes(targetDb)) throw new Error("target-db cannot also be a source-db.");
  return { targetDb, sourceDbs };
}

export function mergeArchiveDatabases(options, dependencies = {}) {
  const target = dependencies.targetDb ?? new DatabaseSync(options.targetDb);
  const openSource = dependencies.openSource ?? ((path) => new DatabaseSync(path));
  const ownsTarget = dependencies.targetDb == null;
  ensureSchema(target);
  try {
    const summary = { mergedDays: 0, mergedBars: 0, skippedDays: 0 };
    for (const sourcePath of options.sourceDbs) {
      const source = openSource(sourcePath);
      try {
        mergeSource(target, source, summary);
      } finally {
        source.close?.();
      }
    }
    return summary;
  } finally {
    if (ownsTarget) target.close();
  }
}

export function mergeSource(target, source, summary) {
  const manifests = source.prepare(`
    SELECT
      provider, dataset, symbol, source_date, source_url, archive_filename,
      archive_size_bytes, archive_sha256, event_count, first_event_at,
      last_event_at, minute_bar_count, imported_at, importer_version
    FROM historicalOrderBookImports
    ORDER BY source_date ASC
  `).all();
  if (manifests.length === 0) throw new Error("Source database has no completed order-book import manifest.");

  for (const manifest of manifests) {
    const bars = source.prepare(`
      SELECT
        symbol, opened_at, sample_count, mean_bid_notional, mean_ask_notional,
        mean_imbalance, mean_spread_bps, max_spread_bps
      FROM orderBookImbalanceBars
      WHERE symbol=? AND opened_at>=? AND opened_at<?
      ORDER BY opened_at ASC
    `).all(manifest.symbol, `${manifest.source_date}T00:00:00Z`, `${nextDate(manifest.source_date)}T00:00:00Z`);
    validateSourceDay(manifest, bars);

    const existing = target.prepare(`
      SELECT archive_sha256 FROM historicalOrderBookImports
      WHERE provider=? AND dataset=? AND symbol=? AND source_date=?
      LIMIT 1
    `).get(manifest.provider, manifest.dataset, manifest.symbol, manifest.source_date);
    if (existing != null && existing.archive_sha256 !== manifest.archive_sha256) {
      throw new Error(`Archive hash conflict for ${manifest.symbol} ${manifest.source_date}.`);
    }
    if (existing != null) {
      summary.skippedDays += 1;
      continue;
    }
    persistSourceDay(target, manifest, bars);
    summary.mergedDays += 1;
    summary.mergedBars += bars.length;
  }
}

export function validateSourceDay(manifest, bars) {
  if (manifest.provider !== "bybit" || manifest.dataset !== "orderbook") {
    throw new Error(`Unexpected source manifest provider=${manifest.provider} dataset=${manifest.dataset}.`);
  }
  if (!isDate(manifest.source_date) || !manifest.symbol || !manifest.archive_sha256 || !manifest.source_url?.startsWith("https://")) {
    throw new Error("Source manifest is missing required provenance fields.");
  }
  if (Number(manifest.minute_bar_count) !== MINUTES_PER_DAY || bars.length !== MINUTES_PER_DAY) {
    throw new Error(`Source day ${manifest.source_date} is incomplete.`);
  }
  const dayStart = Date.parse(`${manifest.source_date}T00:00:00Z`);
  for (let minute = 0; minute < bars.length; minute += 1) {
    const bar = bars[minute];
    const expected = new Date(dayStart + minute * 60_000).toISOString().replace(".000Z", "Z");
    if (bar.symbol !== manifest.symbol || bar.opened_at !== expected || Number(bar.sample_count) < 1) {
      throw new Error(`Source day ${manifest.source_date} has invalid bar coverage at minute offset=${minute}.`);
    }
  }
}

function persistSourceDay(target, manifest, bars) {
  const insertBar = target.prepare(`
    INSERT INTO orderBookImbalanceBars(
      symbol, opened_at, sample_count, mean_bid_notional, mean_ask_notional,
      mean_imbalance, mean_spread_bps, max_spread_bps
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
  `);
  const insertManifest = target.prepare(`
    INSERT INTO historicalOrderBookImports(
      provider, dataset, symbol, source_date, source_url, archive_filename,
      archive_size_bytes, archive_sha256, event_count, first_event_at,
      last_event_at, minute_bar_count, imported_at, importer_version
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `);
  inTransaction(target, () => {
    for (const bar of bars) {
      insertBar.run(
        bar.symbol,
        bar.opened_at,
        bar.sample_count,
        bar.mean_bid_notional,
        bar.mean_ask_notional,
        bar.mean_imbalance,
        bar.mean_spread_bps,
        bar.max_spread_bps,
      );
    }
    insertManifest.run(
      manifest.provider,
      manifest.dataset,
      manifest.symbol,
      manifest.source_date,
      manifest.source_url,
      manifest.archive_filename,
      manifest.archive_size_bytes,
      manifest.archive_sha256,
      manifest.event_count,
      manifest.first_event_at,
      manifest.last_event_at,
      manifest.minute_bar_count,
      manifest.imported_at,
      manifest.importer_version,
    );
  });
}

function inTransaction(db, action) {
  db.exec("BEGIN IMMEDIATE");
  try {
    action();
    db.exec("COMMIT");
  } catch (error) {
    db.exec("ROLLBACK");
    throw error;
  }
}

function nextDate(date) {
  const value = new Date(`${date}T00:00:00Z`);
  value.setUTCDate(value.getUTCDate() + 1);
  return value.toISOString().slice(0, 10);
}

function isDate(value) {
  return /^\d{4}-\d{2}-\d{2}$/.test(value) && new Date(`${value}T00:00:00Z`).toISOString().slice(0, 10) === value;
}

const invokedPath = process.argv[1] ? pathToFileURL(resolve(process.argv[1])).href : null;
if (invokedPath === import.meta.url) {
  const options = parseArgs(process.argv.slice(2));
  console.log(JSON.stringify(mergeArchiveDatabases(options)));
}
