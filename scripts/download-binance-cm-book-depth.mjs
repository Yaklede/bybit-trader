#!/usr/bin/env node

import { downloadBinanceCmBookDepthArchives } from "./lib/binance-cm-book-depth-archive.mjs";

const args = parseArgs(process.argv.slice(2));
const symbol = args.symbol ?? "BTCUSD_PERP";
const startAt = args.start ?? "2023-01-01T00:00:00Z";
const endAt = args.end ?? "2024-01-01T00:00:00Z";
const outDir = args.out ?? "build/research/binance-cm-book-depth";

const archives = await downloadBinanceCmBookDepthArchives({
  symbol,
  startAt,
  endAt,
  outDir,
  allowMissing: true,
  onProgress: ({ day, reused, missing }) =>
    console.log(`${day} ${missing ? "missing" : reused ? "verified" : "downloaded"}`),
});
console.log(JSON.stringify({
  symbol,
  startAt,
  endAt,
  outDir,
  archiveCount: archives.filter((archive) => !archive.missing).length,
  missingDays: archives.filter((archive) => archive.missing).map((archive) => archive.day),
}, null, 2));

function parseArgs(items) {
  const parsed = {};
  for (let index = 0; index < items.length; index += 1) {
    const item = items[index];
    if (!item.startsWith("--")) continue;
    const key = item.slice(2);
    const next = items[index + 1];
    if (next == null || next.startsWith("--")) {
      parsed[key] = "true";
    } else {
      parsed[key] = next;
      index += 1;
    }
  }
  return parsed;
}
