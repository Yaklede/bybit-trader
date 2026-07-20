#!/usr/bin/env node

import { createHash } from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";

const outputPath = process.argv[2] ?? "config/macro-donchian-sealed-windows-v1.json";
const earliestAt = new Date("2024-01-01T00:00:00Z");
const latestAt = new Date("2026-07-02T00:00:00Z");
const seed = 20_260_713;
const recurringDurations = [1, 2, 3, 4, 6, 9, 12, 18, 24];
const durations = Array.from({ length: 40 }, (_, index) =>
  index === 0 ? 30 : recurringDurations[(index - 1) % recurringDurations.length]);
const random = mulberry32(seed);
const windows = [];
const usedRanges = new Set();

for (let index = 0; index < 40; index += 1) {
  const durationMonths = durations[index];
  const latestStart = new Date(latestAt);
  latestStart.setUTCMonth(latestStart.getUTCMonth() - durationMonths);
  const availableDays = Math.floor((latestStart.getTime() - earliestAt.getTime()) / 86_400_000);
  let dayOffset;
  let rangeKey;
  do {
    dayOffset = availableDays > 0 ? Math.floor(random() * (availableDays + 1)) : 0;
    rangeKey = `${durationMonths}:${dayOffset}`;
  } while (usedRanges.has(rangeKey));
  usedRanges.add(rangeKey);
  const replayStart = new Date(earliestAt.getTime() + (dayOffset * 86_400_000));
  const replayEnd = new Date(replayStart);
  replayEnd.setUTCMonth(replayEnd.getUTCMonth() + durationMonths);
  windows.push({
    id: `S${String(index + 1).padStart(2, "0")}`,
    durationMonths,
    replayStartAt: replayStart.toISOString(),
    replayEndAt: replayEnd.toISOString(),
  });
}

windows.sort((left, right) => left.replayStartAt.localeCompare(right.replayStartAt));
const windowsSha256 = createHash("sha256").update(JSON.stringify(windows)).digest("hex");
const protocol = {
  schemaVersion: 1,
  status: "SEALED",
  sourceData: {
    symbol: "BTCUSDT",
    earliestAt: earliestAt.toISOString(),
    latestAt: latestAt.toISOString(),
  },
  generation: {
    algorithm: "mulberry32-v1-fixed-duration-strata",
    seed,
    selectedAt: "2026-07-13T00:00:00Z",
    caseCount: windows.length,
    durationMonths: "1-30",
    tuningAllowed: false,
    candidateContract: "config/aggressive-macro-donchian-candidate-v1.json",
  },
  gates: {
    minCompoundDailyReturnPct: 0.2,
    maxMarkToMarketDrawdownPct: 40,
    maxLiquidationCount: 0,
    minTradeCount: 3,
    minActiveDayCoveragePct: 2,
    requiredFillModelVersion: "causal-m1-path-v2",
  },
  windowsSha256,
  windows,
};

await fs.mkdir(path.dirname(outputPath), { recursive: true });
await fs.writeFile(outputPath, `${JSON.stringify(protocol, null, 2)}\n`);
console.log(JSON.stringify({ outputPath, windowsSha256, windowCount: windows.length }, null, 2));

function mulberry32(initialSeed) {
  let state = initialSeed >>> 0;
  return () => {
    state += 0x6D2B79F5;
    let value = state;
    value = Math.imul(value ^ (value >>> 15), value | 1);
    value ^= value + Math.imul(value ^ (value >>> 7), value | 61);
    return ((value ^ (value >>> 14)) >>> 0) / 4_294_967_296;
  };
}
