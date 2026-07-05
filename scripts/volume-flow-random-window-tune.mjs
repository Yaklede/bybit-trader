#!/usr/bin/env node

import fs from "node:fs/promises";
import path from "node:path";

const args = parseArgs(process.argv.slice(2));
const apiBase = args.api ?? process.env.VOLUME_FLOW_API ?? "http://127.0.0.1:18080";
const token = args.token ?? process.env.BOT_CONTROL_TOKEN ?? "local-test-token";
const configPath = args.config ?? "config/volume-flow-composite-current.json";
const outDir = args.out ?? "build/volume-flow-random-window-tune";
const seed = Number(args.seed ?? 20260705);
const caseCount = Number(args.cases ?? 20);
const maxCandidates = Number(args.maxCandidates ?? 60);
const candidateOffset = Number(args.offset ?? 0);
const concurrency = Number(args.concurrency ?? 4);
const targetCdrPct = Number(args.targetCdrPct ?? 0.8);
const mode = args.mode ?? "tune";
const latestAt = new Date(args.latestAt ?? "2026-07-02T05:40:00Z");
const earliestAt = new Date(args.earliestAt ?? "2020-03-25T10:36:00Z");

const baseConfig = JSON.parse(await fs.readFile(configPath, "utf8"));
await fs.mkdir(outDir, { recursive: true });

const windows = generateWindows({
  seed,
  count: caseCount,
  earliestAt,
  latestAt,
});
await fs.writeFile(path.join(outDir, "windows.json"), JSON.stringify(windows, null, 2));

const variants = mode === "baseline"
  ? [namedVariant("baseline", baseConfig)]
  : buildVariants(baseConfig).slice(candidateOffset, candidateOffset + maxCandidates);

console.log(
  [
    `mode=${mode}`,
    `seed=${seed}`,
    `cases=${windows.length}`,
    `targetCdrPct=${targetCdrPct}`,
    `variants=${variants.length}`,
    `offset=${candidateOffset}`,
    `concurrency=${concurrency}`,
  ].join(" "),
);

const results = [];
for (let index = 0; index < variants.length; index += 1) {
  const variant = variants[index];
  const result = await evaluateVariant(variant, windows);
  results.push(result);

  const progress = `${index + 1}/${variants.length}`;
  console.log(
    [
      progress,
      variant.name,
      `pass=${result.passCount}/${windows.length}`,
      `worstCdr=${fmt(result.worstCompoundDailyReturnPct)}`,
      `medianCdr=${fmt(result.medianCompoundDailyReturnPct)}`,
      `avgCdr=${fmt(result.averageCompoundDailyReturnPct)}`,
      `maxMdd=${fmt(result.maxDrawdownPct)}`,
      `score=${fmt(result.score)}`,
    ].join(" "),
  );
}

const ranked = rankResults(results);
await fs.writeFile(path.join(outDir, "ranked.json"), JSON.stringify(ranked, null, 2));
await fs.writeFile(path.join(outDir, "top.json"), JSON.stringify(ranked.slice(0, 20).map(printableResult), null, 2));
if (ranked[0]?.config) {
  await fs.writeFile(path.join(outDir, "best-config.json"), JSON.stringify(ranked[0].config, null, 2));
}

console.log(JSON.stringify(ranked.slice(0, 10).map(printableResult), null, 2));

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

function generateWindows({ seed, count, earliestAt, latestAt }) {
  const rng = mulberry32(seed);
  const monthBuckets = [
    [1, 3],
    [4, 6],
    [7, 12],
    [13, 24],
    [25, 36],
    [37, 48],
    [49, 60],
  ];
  const windows = [];
  const seen = new Set();
  let attempts = 0;
  while (windows.length < count && attempts < count * 100) {
    attempts += 1;
    const bucket = monthBuckets[windows.length % monthBuckets.length];
    const durationMonths = randomInt(rng, bucket[0], bucket[1]);
    const latestStart = addMonthsUtc(latestAt, -durationMonths);
    const startAt = randomDateUtc(rng, earliestAt, latestStart);
    const normalizedStart = new Date(Date.UTC(
      startAt.getUTCFullYear(),
      startAt.getUTCMonth(),
      startAt.getUTCDate(),
      0,
      0,
      0,
    ));
    const endAt = minDate(addMonthsUtc(normalizedStart, durationMonths), latestAt);
    const key = `${iso(normalizedStart)}_${iso(endAt)}`;
    if (seen.has(key)) continue;
    seen.add(key);
    windows.push({
      id: `W${String(windows.length + 1).padStart(2, "0")}`,
      durationMonths,
      replayStartAt: iso(normalizedStart),
      replayEndAt: iso(endAt),
    });
  }
  return windows.sort((left, right) => left.replayStartAt.localeCompare(right.replayStartAt));
}

function buildVariants(config) {
  const variants = [namedVariant("baseline", config)];

  for (const m5Risk of [0.04, 0.08, 0.12, 0.16, 0.2]) {
    variants.push(namedVariant(`m5_open_r${m5Risk}`, patchM5Open(config, m5Risk)));
  }

  for (const downRisk of [0.02, 0.04, 0.06, 0.08]) {
    for (const rv of [1.2, 1.5, 1.8, 2.2]) {
      variants.push(
        namedVariant(
          `add_down_loose_r${downRisk}_rv${rv}`,
          withAddedLeg(
            patchM5Open(config, 0.08),
            trendM1LooseLeg({
              id: `add_m1_down_loose_r${downRisk}_rv${rv}`,
              riskFraction: downRisk,
              relativeVolumeThreshold: rv,
              allowedMarketRegimes: ["TREND_DOWN"],
              minTrendMovePct: 0.01,
            }),
          ),
        ),
      );
    }
  }

  for (const upRisk of [0.02, 0.04, 0.06, 0.08]) {
    for (const rv of [1.1, 1.2, 1.5, 1.8]) {
      variants.push(
        namedVariant(
          `add_up_loose_r${upRisk}_rv${rv}`,
          withAddedLeg(
            patchM5Open(config, 0.08),
            trendM1LooseLeg({
              id: `add_m1_up_loose_r${upRisk}_rv${rv}`,
              riskFraction: upRisk,
              relativeVolumeThreshold: rv,
              allowedMarketRegimes: ["TREND_UP"],
              minTrendMovePct: 0.002,
            }),
          ),
        ),
      );
    }
  }

  for (const risk of [0.04, 0.08, 0.12]) {
    variants.push(
      namedVariant(
        `add_m5_up_retest_r${risk}`,
        withAddedLeg(
          patchM5Open(config, 0.08),
          mirrorM5TrendUpLeg(config.legs.find((leg) => leg.id === "trend_down_retest"), {
            id: `trend_up_retest_r${risk}`,
            riskFraction: risk,
          }),
        ),
      ),
    );
    variants.push(
      namedVariant(
        `add_m5_up_runner_r${risk}`,
        withAddedLeg(
          patchM5Open(config, 0.08),
          mirrorM5TrendUpLeg(config.legs.find((leg) => leg.id === "trend_down_retest_runner"), {
            id: `trend_up_runner_r${risk}`,
            riskFraction: risk,
          }),
        ),
      ),
    );
  }

  for (const dailyStopPct of [2, 3, 5]) {
    for (const maxConsecutiveLosses of [2, 3, 5]) {
      variants.push(
        namedVariant(
          `exec_ds${dailyStopPct}_loss${maxConsecutiveLosses}`,
          withExecutionPatch(patchM5Open(config, 0.08), {
            dailyStopPct,
            maxConsecutiveLosses,
            maxTradesPerDay: 8,
            maxConcurrentPositions: 8,
          }),
        ),
      );
    }
  }

  const unique = [];
  const seen = new Set();
  for (const variant of variants) {
    const key = JSON.stringify(cleanNulls(variant.config));
    if (seen.has(key)) continue;
    seen.add(key);
    unique.push({ ...variant, config: cleanNulls(variant.config) });
  }
  return unique;
}

async function evaluateVariant(variant, windows) {
  const reports = await mapWithConcurrency(windows, concurrency, async (window) => {
    const report = await requestComposite({
      ...variant.config,
      replayStartAt: window.replayStartAt,
      replayEndAt: window.replayEndAt,
      m1Limit: 6_000_000,
      m5Limit: 1_200_000,
      m15Limit: 400_000,
      tradeLimit: 0,
      equityCurveLimit: 0,
      drawdownEventLimit: 0,
    });
    return summarizeWindow(window, report);
  });
  const cdrValues = reports.map((report) => report.compoundDailyReturnPct);
  const drawdowns = reports.map((report) => report.drawdownPct);
  const passCount = reports.filter((report) => report.compoundDailyReturnPct >= targetCdrPct).length;
  const failCount = reports.length - passCount;
  const worstCompoundDailyReturnPct = Math.min(...cdrValues);
  const averageCompoundDailyReturnPct = average(cdrValues);
  const medianCompoundDailyReturnPct = median(cdrValues);
  const maxDrawdownPct = Math.max(...drawdowns);
  const averageActiveDayCoveragePct = average(reports.map((report) => report.activeDayCoveragePct));
  const score =
    passCount * 1_000_000 +
    worstCompoundDailyReturnPct * 700_000 +
    medianCompoundDailyReturnPct * 250_000 +
    averageCompoundDailyReturnPct * 150_000 +
    averageActiveDayCoveragePct * 1_000 -
    failCount * 150_000 -
    Math.max(0, maxDrawdownPct - 40) * 30_000;

  return {
    name: variant.name,
    passCount,
    failCount,
    targetCdrPct,
    worstCompoundDailyReturnPct,
    medianCompoundDailyReturnPct,
    averageCompoundDailyReturnPct,
    maxDrawdownPct,
    averageActiveDayCoveragePct,
    score,
    windows: reports,
    config: variant.config,
  };
}

async function mapWithConcurrency(items, concurrency, mapper) {
  const results = new Array(items.length);
  let nextIndex = 0;
  const workerCount = Math.max(1, Math.min(concurrency, items.length));
  await Promise.all(
    Array.from({ length: workerCount }, async () => {
      while (nextIndex < items.length) {
        const currentIndex = nextIndex;
        nextIndex += 1;
        results[currentIndex] = await mapper(items[currentIndex], currentIndex);
      }
    }),
  );
  return results;
}

async function requestComposite(config) {
  const response = await fetch(`${apiBase}/backtests/volume-flow/composite/run`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(config),
  });
  const body = await response.text();
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}: ${body.slice(0, 500)}`);
  }
  return JSON.parse(body);
}

function summarizeWindow(window, report) {
  return {
    ...window,
    effectiveStartAt: report.startAt,
    effectiveEndAt: report.endAt,
    finalEquity: report.finalEquity,
    netReturnPct: report.netReturnPct,
    compoundDailyReturnPct: report.compoundDailyReturnPct,
    drawdownPct: Math.max(report.maxDrawdownPct ?? 0, report.markToMarketMaxDrawdownPct ?? 0),
    tradeCount: report.tradeCount,
    activeDayCoveragePct: report.activeDayCoveragePct,
    winRatePct: report.winRatePct,
    profitFactor: report.profitFactor,
    expectancyR: report.expectancyR,
  };
}

function namedVariant(name, config) {
  return {
    name,
    config: cleanNulls(structuredClone(config)),
  };
}

function patchM5Open(config, riskFraction) {
  const next = structuredClone(config);
  next.legs = next.legs.map((leg) => {
    if (!leg.id.startsWith("trend_down_retest")) return leg;
    const patched = {
      ...leg,
      riskFraction,
      minTrendMovePct: null,
      minTrendEfficiency: null,
      runnerTrailActivationR: 0.8,
      runnerTrailDistanceR: 0.75,
    };
    return cleanNulls(patched);
  });
  return cleanNulls(next);
}

function trendM1LooseLeg({
  id,
  riskFraction,
  relativeVolumeThreshold,
  allowedMarketRegimes,
  minTrendMovePct,
}) {
  return cleanNulls({
    id,
    riskFraction,
    setupMode: "BREAKOUT_CONTINUATION",
    entryMode: "CLOSE_CONFIRMATION",
    sideMode: "BOTH",
    setupTimeframe: "M1",
    relativeVolumeThreshold,
    volumeZScoreThreshold: 0.5,
    setupRangeLookback: 6,
    requireM5Vwap: false,
    requireContextVwap: true,
    requireContextTrend: true,
    allowedMarketRegimes,
    requireRegimeSideAlignment: true,
    requireKeyLevelProximity: false,
    keyLevelTolerancePct: 0.0025,
    avoidRangeMiddle: true,
    minBodyRatio: 0.35,
    minDirectionalCloseStrength: 0.6,
    minEntryRiskPct: 0.008,
    maxEntryRiskPct: 0.015,
    maxEstimatedFeeR: 0.2,
    targetR: 0.8,
    exitMode: "RUNNER",
    runnerTrailActivationR: 0.8,
    runnerTrailDistanceR: 0.75,
    maxHoldM1Candles: 60,
    trendBreakLookbackM1Candles: 8,
    minTrendMovePct,
    minTrendEfficiency: 0.35,
  });
}

function mirrorM5TrendUpLeg(source, overrides) {
  return cleanNulls({
    ...structuredClone(source),
    ...overrides,
    allowedMarketRegimes: ["TREND_UP"],
    riskFraction: overrides.riskFraction,
    minTrendMovePct: null,
    minTrendEfficiency: null,
    runnerTrailActivationR: 0.8,
    runnerTrailDistanceR: 0.75,
  });
}

function withAddedLeg(config, leg) {
  const next = structuredClone(config);
  const ids = new Set(next.legs.map((existing) => existing.id));
  if (!ids.has(leg.id)) next.legs.push(leg);
  return cleanNulls(next);
}

function withExecutionPatch(config, patch) {
  return cleanNulls({
    ...structuredClone(config),
    ...patch,
  });
}

function cleanNulls(value) {
  if (Array.isArray(value)) return value.map(cleanNulls);
  if (value && typeof value === "object") {
    const next = {};
    for (const [key, item] of Object.entries(value)) {
      if (item !== null) next[key] = cleanNulls(item);
    }
    return next;
  }
  return value;
}

function rankResults(items) {
  return [...items].sort((left, right) => right.score - left.score);
}

function printableResult(result) {
  return {
    name: result.name,
    passCount: result.passCount,
    failCount: result.failCount,
    targetCdrPct: result.targetCdrPct,
    worstCompoundDailyReturnPct: round(result.worstCompoundDailyReturnPct),
    medianCompoundDailyReturnPct: round(result.medianCompoundDailyReturnPct),
    averageCompoundDailyReturnPct: round(result.averageCompoundDailyReturnPct),
    maxDrawdownPct: round(result.maxDrawdownPct),
    averageActiveDayCoveragePct: round(result.averageActiveDayCoveragePct),
    score: round(result.score),
    windows: result.windows.map((window) => ({
      id: window.id,
      durationMonths: window.durationMonths,
      replayStartAt: window.replayStartAt,
      replayEndAt: window.replayEndAt,
      finalEquity: round(window.finalEquity),
      netReturnPct: round(window.netReturnPct),
      compoundDailyReturnPct: round(window.compoundDailyReturnPct),
      drawdownPct: round(window.drawdownPct),
      tradeCount: window.tradeCount,
      activeDayCoveragePct: round(window.activeDayCoveragePct),
    })),
  };
}

function mulberry32(seed) {
  return function random() {
    let value = (seed += 0x6d2b79f5);
    value = Math.imul(value ^ (value >>> 15), value | 1);
    value ^= value + Math.imul(value ^ (value >>> 7), value | 61);
    return ((value ^ (value >>> 14)) >>> 0) / 4294967296;
  };
}

function randomInt(rng, min, max) {
  return Math.floor(rng() * (max - min + 1)) + min;
}

function randomDateUtc(rng, min, max) {
  const minMs = min.getTime();
  const maxMs = max.getTime();
  return new Date(minMs + Math.floor(rng() * (maxMs - minMs)));
}

function addMonthsUtc(date, months) {
  return new Date(Date.UTC(
    date.getUTCFullYear(),
    date.getUTCMonth() + months,
    date.getUTCDate(),
    date.getUTCHours(),
    date.getUTCMinutes(),
    date.getUTCSeconds(),
  ));
}

function minDate(left, right) {
  return left.getTime() <= right.getTime() ? left : right;
}

function iso(date) {
  return date.toISOString().replace(".000Z", "Z");
}

function average(values) {
  if (values.length === 0) return 0;
  return values.reduce((total, value) => total + value, 0) / values.length;
}

function median(values) {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((left, right) => left - right);
  const mid = Math.floor(sorted.length / 2);
  if (sorted.length % 2 === 1) return sorted[mid];
  return (sorted[mid - 1] + sorted[mid]) / 2;
}

function fmt(value) {
  return Number.isFinite(value) ? value.toFixed(5) : "n/a";
}

function round(value) {
  return Number.isFinite(value) ? Number(value.toFixed(5)) : value;
}
