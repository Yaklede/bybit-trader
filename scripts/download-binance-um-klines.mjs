#!/usr/bin/env node

import { downloadBinanceUmKlineArchives } from "./lib/binance-um-kline-archive.mjs";

const args = parseArgs(process.argv.slice(2));
const symbol = args.symbol ?? "BTCUSDT";
const interval = args.interval ?? "5m";
const startAt = args.start ?? "2020-06-01T00:00:00Z";
const endAt = args.end ?? "2024-01-01T00:00:00Z";
const outDir = args.out ?? "build/research/binance-um-klines";

const archives = await downloadBinanceUmKlineArchives({
  symbol,
  interval,
  startAt,
  endAt,
  outDir,
  onProgress: ({ month, reused }) => console.log(`${month} ${reused ? "verified" : "downloaded"}`),
});

console.log(JSON.stringify({ symbol, interval, startAt, endAt, outDir, archiveCount: archives.length }, null, 2));

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
