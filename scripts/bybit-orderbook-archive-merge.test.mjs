import assert from "node:assert/strict";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { DatabaseSync } from "node:sqlite";
import { ensureSchema } from "./bybit-orderbook-backfill.mjs";
import {
  mergeArchiveDatabases,
  parseArgs,
  validateSourceDay,
} from "./bybit-orderbook-archive-merge.mjs";

test("merge arguments require one target and unique source databases", () => {
  assert.throws(() => parseArgs([]), /target-db is required/);
  assert.throws(() => parseArgs(["--target-db=/tmp/target.sqlite"]), /At least one source-db/);
  assert.throws(
    () => parseArgs(["--target-db=/tmp/target.sqlite", "--source-db=/tmp/source.sqlite", "--source-db=/tmp/source.sqlite"]),
    /must be unique/,
  );
});

test("merge copies only complete provenanced days and rejects a hash conflict", async () => {
  const directory = await mkdtemp(join(tmpdir(), "bybit-orderbook-merge-"));
  const targetPath = join(directory, "target.sqlite");
  const sourcePath = join(directory, "source.sqlite");
  const conflictingPath = join(directory, "conflicting.sqlite");
  try {
    createSource(sourcePath, "2024-01-01", "hash-a");
    const result = mergeArchiveDatabases({ targetDb: targetPath, sourceDbs: [sourcePath] });
    assert.deepEqual(result, { mergedDays: 1, mergedBars: 1_440, skippedDays: 0 });

    const target = new DatabaseSync(targetPath);
    assert.equal(target.prepare("SELECT count(*) AS count FROM orderBookImbalanceBars").get().count, 1_440);
    target.close();

    createSource(conflictingPath, "2024-01-01", "hash-b");
    assert.throws(
      () => mergeArchiveDatabases({ targetDb: targetPath, sourceDbs: [conflictingPath] }),
      /Archive hash conflict/,
    );
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("source validation rejects a declared full day with a missing minute", () => {
  const manifest = sourceManifest("2024-01-01", "hash-a");
  const bars = barsForDay("2024-01-01").slice(1);
  assert.throws(() => validateSourceDay(manifest, bars), /incomplete/);
});

function createSource(path, date, hash) {
  const db = new DatabaseSync(path);
  ensureSchema(db);
  const bars = barsForDay(date);
  const insertBar = db.prepare(`
    INSERT INTO orderBookImbalanceBars VALUES (NULL, ?, ?, 1, '100', '90', '0.0526', '1', '1')
  `);
  const manifest = sourceManifest(date, hash);
  db.exec("BEGIN");
  for (const bar of bars) insertBar.run(bar.symbol, bar.opened_at);
  db.prepare(`
    INSERT INTO historicalOrderBookImports(
      provider, dataset, symbol, source_date, source_url, archive_filename,
      archive_size_bytes, archive_sha256, event_count, first_event_at,
      last_event_at, minute_bar_count, imported_at, importer_version
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `).run(
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
  db.exec("COMMIT");
  db.close();
}

function sourceManifest(date, archiveHash) {
  return {
    provider: "bybit",
    dataset: "orderbook",
    symbol: "BTCUSDT",
    source_date: date,
    source_url: `https://quote-saver.bycsi.com/${date}.zip`,
    archive_filename: `${date}_BTCUSDT_ob500.data.zip`,
    archive_size_bytes: 1,
    archive_sha256: archiveHash,
    event_count: 1,
    first_event_at: `${date}T00:00:00Z`,
    last_event_at: `${date}T23:59:59Z`,
    minute_bar_count: 1_440,
    imported_at: "2026-07-11T00:00:00.000Z",
    importer_version: "test",
  };
}

function barsForDay(date) {
  const start = Date.parse(`${date}T00:00:00Z`);
  return Array.from({ length: 1_440 }, (_, offset) => ({
    symbol: "BTCUSDT",
    opened_at: new Date(start + offset * 60_000).toISOString().replace(".000Z", "Z"),
    sample_count: 1,
  }));
}
