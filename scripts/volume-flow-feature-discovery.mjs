#!/usr/bin/env node

import fs from "node:fs/promises";
import path from "node:path";
import { execFileSync } from "node:child_process";
import {
  attachBinanceFlow,
  loadBinanceUmKlines,
} from "./lib/binance-um-kline-archive.mjs";
import {
  attachBinanceDepth,
  loadBinanceCmBookDepth5m,
} from "./lib/binance-cm-book-depth-archive.mjs";
import {
  attachBinanceMetrics,
  loadBinanceUmMetrics,
} from "./lib/binance-um-metrics-archive.mjs";

const args = parseArgs(process.argv.slice(2));
const dbPath = args.db ?? "build/runtime-test/bybit-trader-full-history.sqlite";
const windowsPath = args.windows ?? "build/volume-flow-random-window-tune-20260705/baseline/windows.json";
const outDir = args.out ?? "build/volume-flow-feature-discovery";
const timeframe = args.timeframe ?? "M5";
const maxCandidates = Number(args.maxCandidates ?? 120);
const candidateOffset = Number(args.offset ?? 0);
const familyFilter = (args.family ?? "")
  .split(",")
  .map((item) => item.trim())
  .filter(Boolean);
const targetCdrPct = Number(args.targetCdrPct ?? 0.8);
const profile = args.profile ?? "default";
const quiet = args.quiet === "true";
const candidateId = args.candidateId;
const traceCandidateId = args.traceCandidateId;
const traceWindowId = args.traceWindowId;
const traceOut = args.traceOut ?? path.join(outDir, "trace.json");
const binanceDir = args.binanceDir;
const binanceDepthDir = args.binanceDepthDir;
const binanceMetricsDir = args.binanceMetricsDir;

await fs.mkdir(outDir, { recursive: true });

const windowsPayload = JSON.parse(await fs.readFile(windowsPath, "utf8"));
const windows = Array.isArray(windowsPayload) ? windowsPayload : windowsPayload.folds;
if (!Array.isArray(windows)) throw new Error(`Window file must be an array or contain folds: ${windowsPath}`);
const candles = loadCandles({ dbPath, timeframe });
attachIndicators(candles);
attachDonchianChannels(candles, [864, 1_440, 2_016, 4_032, 8_640]);
let binanceCoverage = null;
if (binanceDir != null) {
  const binanceRows = await loadBinanceUmKlines({ directory: binanceDir, interval: "5m" });
  binanceCoverage = attachBinanceFlow(candles, binanceRows);
}
if (profile.startsWith("cross-venue-") && binanceCoverage == null) {
  if (!profile.startsWith("cross-venue-depth") && !profile.startsWith("cross-venue-oi")) {
    throw new Error(`Profile ${profile} requires --binanceDir`);
  }
}
if (profile.startsWith("cross-venue-oi-kline") && binanceCoverage == null) {
  throw new Error(`Profile ${profile} requires --binanceDir`);
}
let binanceDepthCoverage = null;
if (binanceDepthDir != null) {
  const depthRows = await loadBinanceCmBookDepth5m({ directory: binanceDepthDir });
  binanceDepthCoverage = attachBinanceDepth(candles, depthRows);
}
if (profile.startsWith("cross-venue-depth") && binanceDepthCoverage == null) {
  throw new Error(`Profile ${profile} requires --binanceDepthDir`);
}
let binanceMetricsCoverage = null;
if (binanceMetricsDir != null) {
  const metricRows = await loadBinanceUmMetrics({ directory: binanceMetricsDir });
  binanceMetricsCoverage = attachBinanceMetrics(candles, metricRows);
}
if (profile.startsWith("cross-venue-oi") && binanceMetricsCoverage == null) {
  throw new Error(`Profile ${profile} requires --binanceMetricsDir`);
}

const allCandidates = buildCandidates()
  .filter((candidate) => familyFilter.length === 0 || familyFilter.includes(candidate.family));
const selectedCandidateId = traceCandidateId ?? candidateId;
const candidates = selectedCandidateId == null
  ? allCandidates.slice(candidateOffset, candidateOffset + maxCandidates)
  : allCandidates.filter((candidate) => candidate.id === selectedCandidateId);
const results = [];

console.log(
  [
    `timeframe=${timeframe}`,
    `candles=${candles.length}`,
    `windows=${windows.length}`,
    `candidates=${candidates.length}`,
    `offset=${candidateOffset}`,
    `family=${familyFilter.length === 0 ? "all" : familyFilter.join(",")}`,
    `profile=${profile}`,
    `targetCdrPct=${targetCdrPct}`,
    `binanceCoverage=${binanceCoverage == null ? "disabled" : fmt(binanceCoverage.coveragePct)}`,
    `binanceDepthCoverage=${binanceDepthCoverage == null ? "disabled" : fmt(binanceDepthCoverage.coveragePct)}`,
    `binanceMetricsCoverage=${binanceMetricsCoverage == null ? "disabled" : fmt(binanceMetricsCoverage.coveragePct)}`,
  ].join(" "),
);

if (traceCandidateId != null || traceWindowId != null) {
  if (candidates.length !== 1) {
    throw new Error(`traceCandidateId must match exactly one candidate, matched=${candidates.length}`);
  }
  const traceWindows = traceWindowId == null
    ? windows
    : windows.filter((window) => window.id === traceWindowId);
  if (traceWindows.length === 0) {
    throw new Error(`traceWindowId did not match any window: ${traceWindowId}`);
  }
  const candidate = candidates[0];
  const reports = traceWindows.map((window) => evaluateWindow(candidate, window, { traceTrades: true }));
  const payload = {
    candidate: printableCandidate(candidate),
    reports,
    sideSummary: summarizeTraceSides(reports),
    exitSummary: summarizeTraceExits(reports),
  };
  await fs.writeFile(traceOut, JSON.stringify(payload, null, 2));
  console.log(JSON.stringify(payload, null, 2));
  process.exit(0);
}

for (let index = 0; index < candidates.length; index += 1) {
  const candidate = candidates[index];
  const reports = windows.map((window) => evaluateWindow(candidate, window));
  const result = summarizeCandidate(candidate, reports);
  results.push(result);
  if (!quiet) console.log(
    [
      `${index + 1}/${candidates.length}`,
      candidate.id,
      `pass=${result.passCount}/${windows.length}`,
      `worst=${fmt(result.worstCompoundDailyReturnPct)}`,
      `median=${fmt(result.medianCompoundDailyReturnPct)}`,
      `avg=${fmt(result.averageCompoundDailyReturnPct)}`,
      `mdd=${fmt(result.maxDrawdownPct)}`,
      `active=${fmt(result.averageActiveDayCoveragePct)}`,
    ].join(" "),
  );
}

const ranked = rankResults(results);
await fs.writeFile(path.join(outDir, "ranked.json"), JSON.stringify(ranked, null, 2));
await fs.writeFile(path.join(outDir, "top.json"), JSON.stringify(ranked.slice(0, 30), null, 2));
console.log(JSON.stringify(ranked.slice(0, 15), null, 2));

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

function loadCandles({ dbPath, timeframe }) {
  const sql = [
    `select opened_at, open, high, low, close, volume from marketCandles`,
    `where symbol = 'BTCUSDT' and timeframe = '${timeframe}'`,
    `order by opened_at;`,
  ].join(" ");
  const output = execFileSync("sqlite3", ["-tabs", "-noheader", dbPath, sql], {
    encoding: "utf8",
    maxBuffer: 256 * 1024 * 1024,
  });
  return output
    .trim()
    .split("\n")
    .filter(Boolean)
    .map((line, index) => {
      const [openedAt, open, high, low, close, volume] = line.split("\t");
      const openedAtMs = Date.parse(openedAt);
      return {
        index,
        openedAt,
        openedAtMs,
        open: Number(open),
        high: Number(high),
        low: Number(low),
        close: Number(close),
        volume: Number(volume),
        hour: new Date(openedAtMs).getUTCHours(),
        day: openedAt.slice(0, 10),
      };
    });
}

function attachIndicators(items) {
  const volumeQueue = [];
  const trQueue = [];
  let volumeSum = 0.0;
  let trSum = 0.0;
  let cumulativeVolume = 0.0;
  let cumulativeRangePct = 0.0;
  let cumulativeAbsoluteCloseChange = 0.0;
  let ema20 = null;
  let ema50 = null;
  let ema200 = null;
  let ema288 = null;
  let ema1152 = null;
  for (let index = 0; index < items.length; index += 1) {
    const candle = items[index];
    const prevClose = index > 0 ? items[index - 1].close : candle.close;
    const trueRange = Math.max(
      candle.high - candle.low,
      Math.abs(candle.high - prevClose),
      Math.abs(candle.low - prevClose),
    );

    candle.avgVolume20 = volumeQueue.length === 20 ? volumeSum / 20 : null;
    candle.atr20 = trQueue.length === 20 ? trSum / 20 : null;
    candle.relativeVolume = candle.avgVolume20 != null && candle.avgVolume20 > 0
      ? candle.volume / candle.avgVolume20
      : null;
    candle.bodyRatio = candle.high > candle.low
      ? Math.abs(candle.close - candle.open) / (candle.high - candle.low)
      : 0.0;
    candle.closeLocation = candle.high > candle.low
      ? (candle.close - candle.low) / (candle.high - candle.low)
      : 0.5;
    candle.side = candle.close > candle.open ? "BUY" : candle.close < candle.open ? "SELL" : null;
    candle.rangePct = candle.close > 0.0 ? ((candle.high - candle.low) / candle.close) * 100.0 : 0.0;

    ema20 = nextEma(ema20, candle.close, 20);
    ema50 = nextEma(ema50, candle.close, 50);
    ema200 = nextEma(ema200, candle.close, 200);
    ema288 = nextEma(ema288, candle.close, 288);
    ema1152 = nextEma(ema1152, candle.close, 1_152);
    candle.ema20 = index >= 19 ? ema20 : null;
    candle.ema50 = index >= 49 ? ema50 : null;
    candle.ema200 = index >= 199 ? ema200 : null;
    candle.ema288 = index >= 287 ? ema288 : null;
    candle.ema1152 = index >= 1_151 ? ema1152 : null;

    cumulativeVolume += candle.volume;
    cumulativeRangePct += candle.rangePct;
    cumulativeAbsoluteCloseChange += Math.abs(candle.close - prevClose);
    candle.cumulativeVolume = cumulativeVolume;
    candle.cumulativeRangePct = cumulativeRangePct;
    candle.cumulativeAbsoluteCloseChange = cumulativeAbsoluteCloseChange;

    volumeQueue.push(candle.volume);
    volumeSum += candle.volume;
    if (volumeQueue.length > 20) volumeSum -= volumeQueue.shift();

    trQueue.push(trueRange);
    trSum += trueRange;
    if (trQueue.length > 20) trSum -= trQueue.shift();
  }
}

function nextEma(previous, value, period) {
  if (previous == null) return value;
  const multiplier = 2.0 / (period + 1.0);
  return previous + ((value - previous) * multiplier);
}

function attachDonchianChannels(items, periods) {
  for (const period of periods) {
    const highDeque = [];
    const lowDeque = [];
    for (let index = 0; index < items.length; index += 1) {
      const oldestAllowed = index - period;
      while (highDeque.length > 0 && highDeque[0] < oldestAllowed) highDeque.shift();
      while (lowDeque.length > 0 && lowDeque[0] < oldestAllowed) lowDeque.shift();

      const candle = items[index];
      candle[`donchianHigh${period}`] = index >= period ? items[highDeque[0]].high : null;
      candle[`donchianLow${period}`] = index >= period ? items[lowDeque[0]].low : null;

      while (highDeque.length > 0 && items[highDeque.at(-1)].high <= candle.high) highDeque.pop();
      while (lowDeque.length > 0 && items[lowDeque.at(-1)].low >= candle.low) lowDeque.pop();
      highDeque.push(index);
      lowDeque.push(index);
    }
  }
}

function buildCandidates() {
  if (profile === "absorption-expanded") return buildExpandedAbsorptionCandidates();
  if (profile === "absorption-aggressive") return buildAggressiveAbsorptionCandidates();
  if (profile === "absorption-adaptive") return buildAdaptiveAbsorptionCandidates();
  if (profile === "absorption-adaptive-focused") return buildFocusedAdaptiveAbsorptionCandidates();
  if (profile === "absorption-adaptive-regime") return buildRegimeAdaptiveAbsorptionCandidates();
  if (profile === "absorption-adaptive-regime-side") return buildSideRegimeAbsorptionCandidates();
  if (profile === "absorption-adaptive-regime-final") return [buildFinalSideRegimeAbsorptionCandidate()];
  if (profile === "trend-trail") return buildTrendTrailCandidates();
  if (profile === "trend-pullback-acceptance") return buildTrendPullbackAcceptanceCandidates();
  if (profile === "trend-pullback-acceptance-focused") return buildFocusedTrendPullbackAcceptanceCandidates();
  if (profile === "macro-trend-breakout") return buildMacroTrendBreakoutCandidates();
  if (profile === "macro-donchian-trend") return buildMacroDonchianTrendCandidates();
  if (profile === "macro-donchian-aggressive") return buildAggressiveMacroDonchianCandidates();
  if (profile === "macro-donchian-stop-focused") return buildStopFocusedMacroDonchianCandidates();
  if (profile === "cross-venue-flow-trend") return buildCrossVenueFlowTrendCandidates();
  if (profile === "cross-venue-flow-trend-focused") return buildFocusedCrossVenueFlowTrendCandidates();
  if (profile === "cross-venue-flow-absorption") return buildCrossVenueFlowAbsorptionCandidates();
  if (profile === "cross-venue-depth-continuation") return buildCrossVenueDepthContinuationCandidates();
  if (profile === "cross-venue-depth-failure") return buildCrossVenueDepthFailureCandidates();
  if (profile === "cross-venue-depth-shift") return buildCrossVenueDepthShiftCandidates();
  if (profile === "cross-venue-oi-flow") return buildCrossVenueOiFlowCandidates();
  if (profile === "cross-venue-oi-kline-flow") {
    return buildCrossVenueOiFlowCandidates({ flowSource: "KLINE", directionModes: ["CONTINUE", "FADE"] });
  }
  if (profile === "cross-venue-oi-kline-unwind") {
    return buildCrossVenueOiFlowCandidates({
      flowSource: "KLINE",
      directionModes: ["CONTINUE", "FADE"],
      oiMode: "UNWIND",
    });
  }
  if (profile === "rejection-probe") return buildRejectionProbeCandidates();

  const candidates = [];
  const sessions = [
    { id: "all", hours: null },
    { id: "us", hours: new Set([13, 14, 15, 16, 17, 18, 19, 20, 21]) },
    { id: "asia", hours: new Set([0, 1, 2, 3, 4, 5, 6, 7, 8]) },
    { id: "london_us", hours: new Set([7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19]) },
  ];

  for (const session of sessions) {
    for (const lookback of [12, 24, 48]) {
      for (const relativeVolumeMin of [2.0, 3.0, 4.0]) {
        for (const targetR of [1.2, 1.8, 2.5]) {
          candidates.push({
            id: `cluster_breakout_${session.id}_l${lookback}_rv${relativeVolumeMin}_t${targetR}`,
            family: "CLUSTER_BREAKOUT",
            session,
            riskFraction: 0.02,
            lookback,
            relativeVolumeMin,
            clusterCandles: 3,
            clusterVolumeMin: 1.5,
            minBreakAtr: 0.05,
            stopAtr: 1.2,
            targetR,
            maxHoldCandles: 36,
            maxTradesPerDay: 3,
            sideMode: "BOTH",
          });
        }
      }
    }
  }

  for (const session of sessions) {
    for (const clusterCandles of [3, 6]) {
      for (const clusterVolumeMin of [1.5, 2.0, 3.0]) {
        for (const maxDisplacementAtr of [0.5, 0.8, 1.2]) {
          candidates.push({
            id: `absorption_${session.id}_c${clusterCandles}_cv${clusterVolumeMin}_d${maxDisplacementAtr}`,
            family: "ABSORPTION_BREAKOUT",
            session,
            riskFraction: 0.02,
            lookback: 24,
            relativeVolumeMin: 1.2,
            clusterCandles,
            clusterVolumeMin,
            maxDisplacementAtr,
            maxRangeAtr: 3.0,
            stopAtr: 1.0,
            targetR: 1.5,
            entryLookaheadCandles: 3,
            maxHoldCandles: 36,
            maxTradesPerDay: 3,
            sideMode: "BOTH",
          });
        }
      }
    }
  }

  for (const session of sessions) {
    for (const relativeVolumeMin of [3.0, 5.0, 8.0]) {
      for (const minRangeAtr of [1.2, 1.8, 2.5]) {
        for (const targetR of [0.8, 1.2, 1.8]) {
          candidates.push({
            id: `exhaustion_reversal_${session.id}_rv${relativeVolumeMin}_ra${minRangeAtr}_t${targetR}`,
            family: "EXHAUSTION_REVERSAL",
            session,
            riskFraction: 0.015,
            lookback: 24,
            relativeVolumeMin,
            minRangeAtr,
            minBodyRatio: 0.55,
            closeLocationExtreme: 0.78,
            stopAtr: 0.8,
            targetR,
            entryLookaheadCandles: 2,
            maxHoldCandles: 24,
            maxTradesPerDay: 3,
            sideMode: "BOTH",
          });
        }
      }
    }
  }

  return candidates;
}

function buildExpandedAbsorptionCandidates() {
  const candidates = [];
  const sessions = [
    { id: "all", hours: null },
    { id: "us", hours: new Set([13, 14, 15, 16, 17, 18, 19, 20, 21]) },
    { id: "london_us", hours: new Set([7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19]) },
  ];
  for (const session of sessions) {
    for (const clusterVolumeMin of [1.5, 2.0]) {
      for (const maxDisplacementAtr of [0.5, 0.8, 1.2]) {
        for (const stopAtr of [0.8, 1.0, 1.2]) {
          for (const targetR of [1.2, 1.5, 1.8, 2.2]) {
            for (const riskFraction of [0.015, 0.02, 0.03]) {
              for (const maxTradesPerDay of [1, 2, 3]) {
                candidates.push({
                  id:
                    `absx_${session.id}` +
                    `_cv${clusterVolumeMin}` +
                    `_d${maxDisplacementAtr}` +
                    `_s${stopAtr}` +
                    `_t${targetR}` +
                    `_r${riskFraction}` +
                    `_mtd${maxTradesPerDay}`,
                  family: "ABSORPTION_BREAKOUT",
                  session,
                  riskFraction,
                  lookback: 24,
                  relativeVolumeMin: 1.2,
                  clusterCandles: 3,
                  clusterVolumeMin,
                  maxDisplacementAtr,
                  maxRangeAtr: 3.0,
                  stopAtr,
                  targetR,
                  entryLookaheadCandles: 3,
                  maxHoldCandles: 36,
                  maxTradesPerDay,
                  sideMode: "BOTH",
                });
              }
            }
          }
        }
      }
    }
  }
  return candidates;
}

function buildAggressiveAbsorptionCandidates() {
  const candidates = [];
  const sessions = [
    { id: "all", hours: null },
    { id: "us", hours: new Set([13, 14, 15, 16, 17, 18, 19, 20, 21]) },
  ];

  for (const session of sessions) {
    for (const relativeVolumeMin of [1.0, 1.2]) {
      for (const clusterCandles of [2, 3, 4]) {
        for (const clusterVolumeMin of [1.2, 1.5, 2.0]) {
          for (const maxDisplacementAtr of [0.5, 0.8, 1.2]) {
            for (const stopAtr of [0.8, 1.0, 1.2]) {
              for (const targetR of [1.2, 1.5, 1.8, 2.2]) {
                for (const riskFraction of [0.03, 0.05, 0.07, 0.1]) {
                  for (const maxTradesPerDay of [3, 5]) {
                    candidates.push({
                      id:
                        `absa_${session.id}` +
                        `_rv${relativeVolumeMin}` +
                        `_c${clusterCandles}` +
                        `_cv${clusterVolumeMin}` +
                        `_d${maxDisplacementAtr}` +
                        `_s${stopAtr}` +
                        `_t${targetR}` +
                        `_r${riskFraction}` +
                        `_mtd${maxTradesPerDay}`,
                      family: "ABSORPTION_BREAKOUT",
                      session,
                      riskFraction,
                      lookback: 24,
                      relativeVolumeMin,
                      clusterCandles,
                      clusterVolumeMin,
                      maxDisplacementAtr,
                      maxRangeAtr: 3.0,
                      stopAtr,
                      targetR,
                      entryLookaheadCandles: 3,
                      maxHoldCandles: 36,
                      maxTradesPerDay,
                      sideMode: "BOTH",
                    });
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  return candidates;
}

function buildAdaptiveAbsorptionCandidates() {
  const candidates = [];
  const sessions = [
    { id: "us", hours: new Set([13, 14, 15, 16, 17, 18, 19, 20, 21]) },
  ];

  for (const session of sessions) {
    for (const relativeVolumeMin of [1.0, 1.2]) {
      for (const stopAtr of [1.0, 1.2]) {
        for (const riskFraction of [0.05, 0.07]) {
          for (const adaptiveTargetR of [1.5, 1.8]) {
            for (const adaptiveLookbackCandles of [2_016, 4_032, 8_640]) {
              for (const adaptiveReturnMaxPct of [-4.0, -6.0, -8.0, -10.0, -12.0]) {
                for (const adaptiveAvgVolumeMin of [300.0, 400.0, 500.0, 600.0]) {
                  for (const adaptiveAvgRangePctMin of [0.10, 0.13, 0.15]) {
                    candidates.push({
                      id:
                        `absa_adapt_${session.id}` +
                        `_rv${relativeVolumeMin}` +
                        `_s${stopAtr}` +
                        `_base2.2` +
                        `_low${adaptiveTargetR}` +
                        `_r${riskFraction}` +
                        `_lb${adaptiveLookbackCandles}` +
                        `_ret${adaptiveReturnMaxPct}` +
                        `_vol${adaptiveAvgVolumeMin}` +
                        `_rng${adaptiveAvgRangePctMin}`,
                      family: "ABSORPTION_BREAKOUT",
                      session,
                      riskFraction,
                      lookback: 24,
                      relativeVolumeMin,
                      clusterCandles: 2,
                      clusterVolumeMin: 1.2,
                      maxDisplacementAtr: 1.2,
                      maxRangeAtr: 3.0,
                      stopAtr,
                      targetR: 2.2,
                      adaptiveTarget: {
                        targetR: adaptiveTargetR,
                        lookbackCandles: adaptiveLookbackCandles,
                        returnMaxPct: adaptiveReturnMaxPct,
                        avgVolumeMin: adaptiveAvgVolumeMin,
                        avgRangePctMin: adaptiveAvgRangePctMin,
                      },
                      entryLookaheadCandles: 3,
                      maxHoldCandles: 36,
                      maxTradesPerDay: 5,
                      sideMode: "BOTH",
                    });
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  return candidates;
}

function buildFocusedAdaptiveAbsorptionCandidates() {
  const candidates = [];
  const session = { id: "us", hours: new Set([13, 14, 15, 16, 17, 18, 19, 20, 21]) };

  for (const relativeVolumeMin of [1.0, 1.2]) {
    for (const stopAtr of [1.0, 1.2]) {
      for (const riskFraction of [0.05, 0.0525, 0.055, 0.0575, 0.06, 0.0625, 0.065, 0.0675, 0.07]) {
        for (const adaptiveTargetR of [1.5, 1.8]) {
          for (const adaptiveLookbackCandles of [2_016, 4_032, 8_640]) {
            for (const adaptiveReturnMaxPct of [-4.0, -6.0, -8.0, -10.0, -12.0]) {
              for (const adaptiveAvgRangePctMin of [0.10, 0.13, 0.15]) {
                candidates.push({
                  id:
                    `absa_focus_${session.id}` +
                    `_rv${relativeVolumeMin}` +
                    `_s${stopAtr}` +
                    `_base2.2` +
                    `_low${adaptiveTargetR}` +
                    `_r${riskFraction}` +
                    `_lb${adaptiveLookbackCandles}` +
                    `_ret${adaptiveReturnMaxPct}` +
                    `_vol300` +
                    `_rng${adaptiveAvgRangePctMin}`,
                  family: "ABSORPTION_BREAKOUT",
                  session,
                  riskFraction,
                  lookback: 24,
                  relativeVolumeMin,
                  clusterCandles: 2,
                  clusterVolumeMin: 1.2,
                  maxDisplacementAtr: 1.2,
                  maxRangeAtr: 3.0,
                  stopAtr,
                  targetR: 2.2,
                  adaptiveTarget: {
                    targetR: adaptiveTargetR,
                    lookbackCandles: adaptiveLookbackCandles,
                    returnMaxPct: adaptiveReturnMaxPct,
                    avgVolumeMin: 300.0,
                    avgRangePctMin: adaptiveAvgRangePctMin,
                  },
                  entryLookaheadCandles: 3,
                  maxHoldCandles: 36,
                  maxTradesPerDay: 5,
                  sideMode: "BOTH",
                });
              }
            }
          }
        }
      }
    }
  }

  return candidates;
}

function buildRegimeAdaptiveAbsorptionCandidates() {
  const candidates = [];
  const session = { id: "us", hours: new Set([13, 14, 15, 16, 17, 18, 19, 20, 21]) };

  for (const riskFraction of [0.05, 0.0525, 0.055, 0.0575, 0.06, 0.065, 0.07]) {
    for (const adaptiveTargetR of [1.5, 1.8]) {
      for (const adaptiveTargetLookbackCandles of [2_016, 4_032]) {
        for (const adaptiveReturnMaxPct of [-8.0, -10.0, -12.0]) {
          for (const adaptiveStopAtr of [1.2, 1.4]) {
            for (const adaptiveStopLookbackCandles of [8_640, 17_280]) {
              for (const adaptiveStopReturnMinPct of [15.0, 20.0, 25.0]) {
                for (const adaptiveStopAvgVolumeMin of [200.0, 250.0, 300.0]) {
                  for (const adaptiveStopAvgRangePctMin of [0.12, 0.14, 0.16]) {
                    candidates.push({
                      id:
                        `absa_regime_${session.id}` +
                        `_rv1_s1` +
                        `_wide${adaptiveStopAtr}` +
                        `_r${riskFraction}` +
                        `_low${adaptiveTargetR}` +
                        `_tlb${adaptiveTargetLookbackCandles}` +
                        `_tret${adaptiveReturnMaxPct}` +
                        `_slb${adaptiveStopLookbackCandles}` +
                        `_sret${adaptiveStopReturnMinPct}` +
                        `_svol${adaptiveStopAvgVolumeMin}` +
                        `_srng${adaptiveStopAvgRangePctMin}`,
                      family: "ABSORPTION_BREAKOUT",
                      session,
                      riskFraction,
                      lookback: 24,
                      relativeVolumeMin: 1.0,
                      clusterCandles: 2,
                      clusterVolumeMin: 1.2,
                      maxDisplacementAtr: 1.2,
                      maxRangeAtr: 3.0,
                      stopAtr: 1.0,
                      adaptiveStop: {
                        stopAtr: adaptiveStopAtr,
                        lookbackCandles: adaptiveStopLookbackCandles,
                        returnMinPct: adaptiveStopReturnMinPct,
                        avgVolumeMin: adaptiveStopAvgVolumeMin,
                        avgRangePctMin: adaptiveStopAvgRangePctMin,
                      },
                      targetR: 2.2,
                      adaptiveTarget: {
                        targetR: adaptiveTargetR,
                        lookbackCandles: adaptiveTargetLookbackCandles,
                        returnMaxPct: adaptiveReturnMaxPct,
                        avgVolumeMin: 300.0,
                        avgRangePctMin: 0.10,
                      },
                      entryLookaheadCandles: 3,
                      maxHoldCandles: 36,
                      maxTradesPerDay: 5,
                      sideMode: "BOTH",
                    });
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  return candidates;
}

function buildSideRegimeAbsorptionCandidates() {
  const candidates = [];
  const session = { id: "us", hours: new Set([13, 14, 15, 16, 17, 18, 19, 20, 21]) };
  const sideBlockSets = buildSideBlockSets();

  for (const riskFraction of [0.05, 0.0525, 0.055, 0.0575, 0.06, 0.065, 0.07]) {
    for (const adaptiveStopAtr of [1.2, 1.4]) {
      for (const adaptiveStopLookbackCandles of [8_640, 17_280]) {
        for (const adaptiveStopReturnMinPct of [20.0, 25.0]) {
          for (const adaptiveStopAvgVolumeMin of [200.0, 250.0, 300.0]) {
            for (const adaptiveStopAvgRangePctMin of [0.12, 0.14, 0.16]) {
              for (const sideBlockSet of sideBlockSets) {
                candidates.push({
                  id:
                    `absa_side_${session.id}` +
                    `_rv1_s1` +
                    `_wide${adaptiveStopAtr}` +
                    `_r${riskFraction}` +
                    `_low1.5_tlb4032_tret-10` +
                    `_slb${adaptiveStopLookbackCandles}` +
                    `_sret${adaptiveStopReturnMinPct}` +
                    `_svol${adaptiveStopAvgVolumeMin}` +
                    `_srng${adaptiveStopAvgRangePctMin}` +
                    `_side${sideBlockSet.id}`,
                  family: "ABSORPTION_BREAKOUT",
                  session,
                  riskFraction,
                  lookback: 24,
                  relativeVolumeMin: 1.0,
                  clusterCandles: 2,
                  clusterVolumeMin: 1.2,
                  maxDisplacementAtr: 1.2,
                  maxRangeAtr: 3.0,
                  stopAtr: 1.0,
                  adaptiveStop: {
                    stopAtr: adaptiveStopAtr,
                    lookbackCandles: adaptiveStopLookbackCandles,
                    returnMinPct: adaptiveStopReturnMinPct,
                    avgVolumeMin: adaptiveStopAvgVolumeMin,
                    avgRangePctMin: adaptiveStopAvgRangePctMin,
                  },
                  targetR: 2.2,
                  adaptiveTarget: {
                    targetR: 1.5,
                    lookbackCandles: 4_032,
                    returnMaxPct: -10.0,
                    avgVolumeMin: 300.0,
                    avgRangePctMin: 0.10,
                  },
                  sideRegimeBlocks: sideBlockSet.rules,
                  entryLookaheadCandles: 3,
                  maxHoldCandles: 36,
                  maxTradesPerDay: 5,
                  sideMode: "BOTH",
                });
              }
            }
          }
        }
      }
    }
  }

  return candidates;
}

function buildFinalSideRegimeAbsorptionCandidate() {
  const session = { id: "us", hours: new Set([13, 14, 15, 16, 17, 18, 19, 20, 21]) };
  return {
    id: "absa_final_us_v1",
    family: "ABSORPTION_BREAKOUT",
    session,
    riskFraction: 0.055,
    lookback: 24,
    relativeVolumeMin: 1.0,
    clusterCandles: 2,
    clusterVolumeMin: 1.2,
    maxDisplacementAtr: 1.2,
    maxRangeAtr: 3.0,
    stopAtr: 1.0,
    adaptiveStop: {
      stopAtr: 1.2,
      lookbackCandles: 8_640,
      returnMinPct: 25.0,
      avgVolumeMin: 200.0,
      avgRangePctMin: 0.12,
    },
    targetR: 2.2,
    adaptiveTarget: {
      targetR: 1.5,
      lookbackCandles: 4_032,
      returnMaxPct: -10.0,
      avgVolumeMin: 300.0,
      avgRangePctMin: 0.10,
    },
    sideRegimeBlocks: [
      {
        side: "SELL",
        lookbackCandles: 17_280,
        returnMaxPct: -25.0,
      },
      {
        side: "BUY",
        lookbackCandles: 17_280,
        returnMinPct: -12.0,
        returnMaxPct: 2.0,
        avgVolumeMin: 200.0,
        avgVolumeMax: 300.0,
      },
      {
        side: "BUY",
        lookbackCandles: 4_032,
        returnMinPct: 0.0,
        returnMaxPct: 10.0,
        confirmLookbackCandles: 17_280,
        confirmReturnMinPct: 20.0,
        confirmAvgVolumeMax: 300.0,
      },
      {
        side: "BUY",
        lookbackCandles: 17_280,
        returnMinPct: 10.0,
        returnMaxPct: 20.0,
        avgVolumeMax: 300.0,
        confirmLookbackCandles: 4_032,
        confirmReturnMinPct: 0.0,
        confirmReturnMaxPct: 10.0,
      },
      {
        side: "SELL",
        lookbackCandles: 17_280,
        returnMinPct: 0.0,
        returnMaxPct: 10.0,
        avgVolumeMin: 300.0,
        confirmLookbackCandles: 4_032,
        confirmReturnMinPct: 4.0,
      },
      {
        side: "BUY",
        lookbackCandles: 17_280,
        returnMinPct: 20.0,
        confirmLookbackCandles: 4_032,
        confirmReturnMinPct: 20.0,
      },
    ],
    entryLookaheadCandles: 3,
    maxHoldCandles: 36,
    maxTradesPerDay: 5,
    sideMode: "BOTH",
  };
}

function buildSideBlockSets() {
  const shortCrashBlocks = [
    { id: "sc15", rules: [{ side: "SELL", lookbackCandles: 17_280, returnMaxPct: -15.0 }] },
    { id: "sc20", rules: [{ side: "SELL", lookbackCandles: 17_280, returnMaxPct: -20.0 }] },
    { id: "sc25", rules: [{ side: "SELL", lookbackCandles: 17_280, returnMaxPct: -25.0 }] },
  ];
  const buyWeakBlocks = [
    { id: "bw10_0", rules: [{ side: "BUY", lookbackCandles: 17_280, returnMinPct: -10.0, returnMaxPct: 0.0 }] },
    { id: "bw12_2", rules: [{ side: "BUY", lookbackCandles: 17_280, returnMinPct: -12.0, returnMaxPct: 2.0 }] },
    { id: "bw15_5", rules: [{ side: "BUY", lookbackCandles: 17_280, returnMinPct: -15.0, returnMaxPct: 5.0 }] },
    {
      id: "bw12_2_vlt300",
      rules: [{
        side: "BUY",
        lookbackCandles: 17_280,
        returnMinPct: -12.0,
        returnMaxPct: 2.0,
        avgVolumeMax: 300.0,
      }],
    },
    {
      id: "bw12_2_v200_300",
      rules: [{
        side: "BUY",
        lookbackCandles: 17_280,
        returnMinPct: -12.0,
        returnMaxPct: 2.0,
        avgVolumeMin: 200.0,
        avgVolumeMax: 300.0,
      }],
    },
  ];
  const buyHotBlocks = [
    { id: "bh8_20", rules: [{ side: "BUY", lookbackCandles: 17_280, returnMinPct: 8.0, returnMaxPct: 20.0 }] },
    { id: "bh10_20", rules: [{ side: "BUY", lookbackCandles: 17_280, returnMinPct: 10.0, returnMaxPct: 20.0 }] },
    { id: "bh12_25", rules: [{ side: "BUY", lookbackCandles: 17_280, returnMinPct: 12.0, returnMaxPct: 25.0 }] },
    { id: "ss0_10", rules: [{ side: "SELL", lookbackCandles: 17_280, returnMinPct: 0.0, returnMaxPct: 10.0 }] },
    { id: "bh20p", rules: [{ side: "BUY", lookbackCandles: 17_280, returnMinPct: 20.0 }] },
    {
      id: "bh60_20_14_20",
      rules: [{
        side: "BUY",
        lookbackCandles: 17_280,
        returnMinPct: 20.0,
        confirmLookbackCandles: 4_032,
        confirmReturnMinPct: 20.0,
      }],
    },
    {
      id: "ss0_10_bh60_20_14_20",
      rules: [
        { side: "SELL", lookbackCandles: 17_280, returnMinPct: 0.0, returnMaxPct: 10.0 },
        {
          side: "BUY",
          lookbackCandles: 17_280,
          returnMinPct: 20.0,
          confirmLookbackCandles: 4_032,
          confirmReturnMinPct: 20.0,
        },
      ],
    },
    {
      id: "ss0_10_v300_14_4_bh60_20_14_20",
      rules: [
        {
          side: "SELL",
          lookbackCandles: 17_280,
          returnMinPct: 0.0,
          returnMaxPct: 10.0,
          avgVolumeMin: 300.0,
          confirmLookbackCandles: 4_032,
          confirmReturnMinPct: 4.0,
        },
        {
          side: "BUY",
          lookbackCandles: 17_280,
          returnMinPct: 20.0,
          confirmLookbackCandles: 4_032,
          confirmReturnMinPct: 20.0,
        },
      ],
    },
    {
      id: "bb10_20_vlt300_14_0_10_ss0_10_v300_14_4_bh60_20_14_20",
      rules: [
        {
          side: "BUY",
          lookbackCandles: 17_280,
          returnMinPct: 10.0,
          returnMaxPct: 20.0,
          avgVolumeMax: 300.0,
          confirmLookbackCandles: 4_032,
          confirmReturnMinPct: 0.0,
          confirmReturnMaxPct: 10.0,
        },
        {
          side: "SELL",
          lookbackCandles: 17_280,
          returnMinPct: 0.0,
          returnMaxPct: 10.0,
          avgVolumeMin: 300.0,
          confirmLookbackCandles: 4_032,
          confirmReturnMinPct: 4.0,
        },
        {
          side: "BUY",
          lookbackCandles: 17_280,
          returnMinPct: 20.0,
          confirmLookbackCandles: 4_032,
          confirmReturnMinPct: 20.0,
        },
      ],
    },
    {
      id: "bb14_0_10_v60lt300_bb10_20_vlt300_14_0_10_ss0_10_v300_14_4_bh60_20_14_20",
      rules: [
        {
          side: "BUY",
          lookbackCandles: 4_032,
          returnMinPct: 0.0,
          returnMaxPct: 10.0,
          confirmLookbackCandles: 17_280,
          confirmAvgVolumeMax: 300.0,
        },
        {
          side: "BUY",
          lookbackCandles: 17_280,
          returnMinPct: 10.0,
          returnMaxPct: 20.0,
          avgVolumeMax: 300.0,
          confirmLookbackCandles: 4_032,
          confirmReturnMinPct: 0.0,
          confirmReturnMaxPct: 10.0,
        },
        {
          side: "SELL",
          lookbackCandles: 17_280,
          returnMinPct: 0.0,
          returnMaxPct: 10.0,
          avgVolumeMin: 300.0,
          confirmLookbackCandles: 4_032,
          confirmReturnMinPct: 4.0,
        },
        {
          side: "BUY",
          lookbackCandles: 17_280,
          returnMinPct: 20.0,
          confirmLookbackCandles: 4_032,
          confirmReturnMinPct: 20.0,
        },
      ],
    },
    {
      id: "bb14_0_10_v60lt300_r60_20p_bb10_20_vlt300_14_0_10_ss0_10_v300_14_4_bh60_20_14_20",
      rules: [
        {
          side: "BUY",
          lookbackCandles: 4_032,
          returnMinPct: 0.0,
          returnMaxPct: 10.0,
          confirmLookbackCandles: 17_280,
          confirmReturnMinPct: 20.0,
          confirmAvgVolumeMax: 300.0,
        },
        {
          side: "BUY",
          lookbackCandles: 17_280,
          returnMinPct: 10.0,
          returnMaxPct: 20.0,
          avgVolumeMax: 300.0,
          confirmLookbackCandles: 4_032,
          confirmReturnMinPct: 0.0,
          confirmReturnMaxPct: 10.0,
        },
        {
          side: "SELL",
          lookbackCandles: 17_280,
          returnMinPct: 0.0,
          returnMaxPct: 10.0,
          avgVolumeMin: 300.0,
          confirmLookbackCandles: 4_032,
          confirmReturnMinPct: 4.0,
        },
        {
          side: "BUY",
          lookbackCandles: 17_280,
          returnMinPct: 20.0,
          confirmLookbackCandles: 4_032,
          confirmReturnMinPct: 20.0,
        },
      ],
    },
  ];
  const sets = [{ id: "none", rules: [] }];
  for (const crash of shortCrashBlocks) {
    sets.push(crash);
    for (const weak of buyWeakBlocks) {
      sets.push({
        id: `${crash.id}_${weak.id}`,
        rules: [...crash.rules, ...weak.rules],
      });
      for (const hot of buyHotBlocks) {
        sets.push({
          id: `${crash.id}_${weak.id}_${hot.id}`,
          rules: [...crash.rules, ...weak.rules, ...hot.rules],
        });
      }
    }
    for (const hot of buyHotBlocks) {
      sets.push({
        id: `${crash.id}_${hot.id}`,
        rules: [...crash.rules, ...hot.rules],
      });
    }
  }
  return sets;
}

function buildTrendTrailCandidates() {
  const candidates = [];
  const sessions = [
    { id: "all", hours: null },
    { id: "us", hours: new Set([13, 14, 15, 16, 17, 18, 19, 20, 21]) },
    { id: "london_us", hours: new Set([7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19]) },
  ];

  for (const session of sessions) {
    for (const lookback of [12, 24, 48]) {
      for (const relativeVolumeMin of [1.2, 1.5, 2.0]) {
        for (const minBreakAtr of [0.0, 0.05, 0.1]) {
          for (const stopAtr of [1.0, 1.5, 2.0]) {
            for (const trailAtr of [1.5, 2.0, 2.5, 3.0]) {
              for (const maxHoldCandles of [72, 144, 288, 576]) {
                for (const riskFraction of [0.015, 0.02, 0.03]) {
                  for (const maxTradesPerDay of [1, 2]) {
                    candidates.push({
                      id:
                        `trail_${session.id}` +
                        `_l${lookback}` +
                        `_rv${relativeVolumeMin}` +
                        `_b${minBreakAtr}` +
                        `_s${stopAtr}` +
                        `_tr${trailAtr}` +
                        `_h${maxHoldCandles}` +
                        `_r${riskFraction}` +
                        `_mtd${maxTradesPerDay}`,
                      family: "TREND_TRAIL_BREAKOUT",
                      session,
                      riskFraction,
                      lookback,
                      relativeVolumeMin,
                      clusterCandles: 3,
                      clusterVolumeMin: 1.2,
                      minBreakAtr,
                      stopAtr,
                      trailAtr,
                      maxHoldCandles,
                      maxTradesPerDay,
                      sideMode: "BOTH",
                    });
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  return candidates;
}

function buildTrendPullbackAcceptanceCandidates() {
  const candidates = [];
  const sessions = [
    { id: "all", hours: null },
    { id: "us", hours: new Set([13, 14, 15, 16, 17, 18, 19, 20, 21]) },
  ];

  for (const session of sessions) {
    for (const trendLookback of [48, 96]) {
      for (const minTrendEfficiency of [0.10, 0.20]) {
        for (const pullbackCandles of [3, 6]) {
          for (const relativeVolumeMin of [0.8, 1.0]) {
            for (const stopAtr of [1.0, 1.5]) {
              for (const targetR of [2.0, 3.0]) {
                candidates.push({
                  id:
                    `trend_pullback_${session.id}` +
                    `_l${trendLookback}` +
                    `_e${minTrendEfficiency}` +
                    `_p${pullbackCandles}` +
                    `_rv${relativeVolumeMin}` +
                    `_s${stopAtr}` +
                    `_t${targetR}`,
                  family: "TREND_PULLBACK_ACCEPTANCE",
                  session,
                  riskFraction: 0.01,
                  relativeVolumeMin,
                  trendLookback,
                  minTrendEfficiency,
                  minTrendMoveAtr: 1.0,
                  pullbackCandles,
                  pullbackVolumeMax: 1.5,
                  pullbackToleranceAtr: 1.5,
                  maxConfirmationDistanceAtr: 2.5,
                  minConfirmationBodyRatio: 0.30,
                  minConfirmationCloseLocation: 0.60,
                  stopAtr,
                  targetR,
                  maxHoldCandles: 72,
                  maxTradesPerDay: 2,
                  sideMode: "BOTH",
                });
              }
            }
          }
        }
      }
    }
  }
  return candidates;
}

function buildFocusedTrendPullbackAcceptanceCandidates() {
  const session = { id: "us", hours: new Set([13, 14, 15, 16, 17, 18, 19, 20, 21]) };
  const candidates = [];
  for (const sideMode of ["BOTH", "LONG_ONLY", "SHORT_ONLY"]) {
    for (const stopAtr of [1.0, 1.5, 2.0]) {
      for (const targetR of [3.0, 4.0, 5.0]) {
        for (const maxHoldCandles of [72, 144, 288]) {
          candidates.push({
            ...focusedTrendPullbackBase(session, sideMode, stopAtr, maxHoldCandles),
            id: `trend_pullback_focus_${sideMode}_s${stopAtr}_t${targetR}_h${maxHoldCandles}`,
            targetR,
          });
        }
      }
      for (const trailAtr of [1.5, 2.0, 2.5, 3.0]) {
        for (const maxHoldCandles of [144, 288]) {
          candidates.push({
            ...focusedTrendPullbackBase(session, sideMode, stopAtr, maxHoldCandles),
            id: `trend_pullback_focus_${sideMode}_s${stopAtr}_trail${trailAtr}_h${maxHoldCandles}`,
            targetR: 5.0,
            trailAtr,
          });
        }
      }
    }
  }
  return candidates;
}

function focusedTrendPullbackBase(session, sideMode, stopAtr, maxHoldCandles) {
  return {
    family: "TREND_PULLBACK_ACCEPTANCE",
    session,
    riskFraction: 0.01,
    relativeVolumeMin: 1.0,
    trendLookback: 96,
    minTrendEfficiency: 0.20,
    minTrendMoveAtr: 1.0,
    pullbackCandles: 6,
    pullbackVolumeMax: 1.5,
    pullbackToleranceAtr: 1.5,
    maxConfirmationDistanceAtr: 2.5,
    minConfirmationBodyRatio: 0.30,
    minConfirmationCloseLocation: 0.60,
    stopAtr,
    maxHoldCandles,
    maxTradesPerDay: 2,
    sideMode,
  };
}

function buildMacroTrendBreakoutCandidates() {
  const candidates = [];
  const session = { id: "all", hours: null };
  for (const sideMode of ["BOTH", "LONG_ONLY", "SHORT_ONLY"]) {
    for (const lookback of [144, 288, 576]) {
      for (const relativeVolumeMin of [0.8, 1.0]) {
        for (const minBreakAtr of [0.0, 0.25]) {
          for (const stopAtr of [2.0, 3.0, 4.0]) {
            for (const trailAtr of [3.0, 5.0, 8.0]) {
              for (const maxHoldCandles of [2_016, 4_032]) {
                candidates.push({
                  id:
                    `macro_break_${sideMode}` +
                    `_l${lookback}` +
                    `_rv${relativeVolumeMin}` +
                    `_b${minBreakAtr}` +
                    `_s${stopAtr}` +
                    `_tr${trailAtr}` +
                    `_h${maxHoldCandles}`,
                  family: "MACRO_TREND_BREAKOUT",
                  session,
                  riskFraction: 0.01,
                  relativeVolumeMin,
                  lookback,
                  minBreakAtr,
                  stopAtr,
                  trailAtr,
                  targetR: 8.0,
                  maxHoldCandles,
                  maxTradesPerDay: 1,
                  sideMode,
                });
              }
            }
          }
        }
      }
    }
  }
  return candidates;
}

function buildMacroDonchianTrendCandidates() {
  const candidates = [];
  const session = { id: "all", hours: null };
  for (const sideMode of ["BOTH", "LONG_ONLY", "SHORT_ONLY"]) {
    for (const lookback of [2_016, 4_032, 8_640]) {
      for (const relativeVolumeMin of [1.0, 1.5]) {
        for (const stopAtr of [8.0, 16.0]) {
          for (const trailAtr of [12.0, 24.0, 48.0]) {
            candidates.push({
              id:
                `macro_donchian_${sideMode}` +
                `_l${lookback}` +
                `_rv${relativeVolumeMin}` +
                `_s${stopAtr}` +
                `_tr${trailAtr}`,
              family: "MACRO_DONCHIAN_TREND",
              session,
              riskFraction: 0.01,
              relativeVolumeMin,
              lookback,
              stopAtr,
              trailAtr,
              targetR: 12.0,
              maxHoldCandles: 17_280,
              maxTradesPerDay: 1,
              sideMode,
            });
          }
        }
      }
    }
  }
  return candidates;
}

function buildAggressiveMacroDonchianCandidates() {
  const candidates = [];
  const session = { id: "all", hours: null };
  for (const lookback of [864, 1_440, 2_016]) {
    for (const riskFraction of [0.03, 0.055, 0.08]) {
      for (const stopAtr of [8.0, 16.0]) {
        for (const trailAtr of [36.0, 48.0, 72.0]) {
          candidates.push({
            id:
              `macro_donchian_aggressive_l${lookback}` +
              `_r${riskFraction}` +
              `_s${stopAtr}` +
              `_tr${trailAtr}`,
            family: "MACRO_DONCHIAN_TREND",
            session,
            riskFraction,
            relativeVolumeMin: 1.0,
            lookback,
            stopAtr,
            trailAtr,
            targetR: 12.0,
            maxHoldCandles: 17_280,
            maxTradesPerDay: 1,
            sideMode: "BOTH",
          });
        }
      }
    }
  }
  return candidates;
}

function buildStopFocusedMacroDonchianCandidates() {
  const candidates = [];
  const session = { id: "all", hours: null };
  for (const riskFraction of [0.055, 0.08]) {
    for (const stopReferenceCandles of [72, 144, 288]) {
      for (const stopAtr of [8.0, 16.0]) {
        for (const trailAtr of [36.0, 48.0, 72.0]) {
          candidates.push({
            id:
              `macro_donchian_stop_focus_r${riskFraction}` +
              `_sr${stopReferenceCandles}` +
              `_s${stopAtr}` +
              `_tr${trailAtr}`,
            family: "MACRO_DONCHIAN_TREND",
            session,
            riskFraction,
            relativeVolumeMin: 1.0,
            lookback: 1_440,
            stopReferenceCandles,
            stopAtr,
            trailAtr,
            targetR: 12.0,
            maxHoldCandles: 17_280,
            maxTradesPerDay: 1,
            sideMode: "BOTH",
          });
        }
      }
    }
  }
  return candidates;
}

function buildCrossVenueFlowTrendCandidates() {
  const candidates = [];
  const session = { id: "all", hours: null };
  for (const flowWindow of [3, 6, 12]) {
    for (const flowThreshold of [0.03, 0.08, 0.15]) {
      for (const binanceRelativeVolumeMin of [1.0, 1.5]) {
        for (const stopAtr of [1.5, 2.5]) {
          for (const trailAtr of [2.5, 4.0]) {
            candidates.push({
              id:
                `cross_flow_trend_w${flowWindow}` +
                `_f${flowThreshold}` +
                `_rv${binanceRelativeVolumeMin}` +
                `_s${stopAtr}` +
                `_tr${trailAtr}`,
              family: "CROSS_VENUE_FLOW_TREND",
              session,
              riskFraction: 0.01,
              relativeVolumeMin: 0.8,
              flowWindow,
              flowThreshold,
              binanceRelativeVolumeMin,
              stopAtr,
              trailAtr,
              targetR: 8.0,
              maxHoldCandles: 288,
              maxTradesPerDay: 3,
              sideMode: "BOTH",
            });
          }
        }
      }
    }
  }
  return candidates;
}

function buildCrossVenueFlowAbsorptionCandidates() {
  const candidates = [];
  const session = { id: "all", hours: null };
  for (const flowThreshold of [0.15, 0.25, 0.35]) {
    for (const binanceRelativeVolumeMin of [1.5, 2.5, 4.0]) {
      for (const closeLocationExtreme of [0.60, 0.72]) {
        for (const stopAtr of [1.5, 2.5]) {
          for (const targetR of [1.5, 2.5, 4.0]) {
            candidates.push({
              id:
                `cross_flow_absorb_f${flowThreshold}` +
                `_rv${binanceRelativeVolumeMin}` +
                `_cl${closeLocationExtreme}` +
                `_s${stopAtr}` +
                `_t${targetR}`,
              family: "CROSS_VENUE_FLOW_ABSORPTION",
              session,
              riskFraction: 0.01,
              relativeVolumeMin: 0.8,
              flowThreshold,
              binanceRelativeVolumeMin,
              closeLocationExtreme,
              stopAtr,
              targetR,
              maxHoldCandles: 72,
              maxTradesPerDay: 2,
              sideMode: "BOTH",
            });
          }
        }
      }
    }
  }
  return candidates;
}

function buildFocusedCrossVenueFlowTrendCandidates() {
  const candidates = [];
  const session = { id: "all", hours: null };
  for (const sideMode of ["LONG_ONLY", "SHORT_ONLY"]) {
    for (const flowWindow of [12, 24, 36]) {
      for (const flowThreshold of [0.12, 0.18, 0.24]) {
        for (const binanceRelativeVolumeMin of [1.5, 2.0]) {
          for (const stopAtr of [2.5, 4.0]) {
            for (const trailAtr of [4.0, 6.0]) {
              candidates.push({
                id:
                  `cross_flow_focus_${sideMode}` +
                  `_w${flowWindow}` +
                  `_f${flowThreshold}` +
                  `_rv${binanceRelativeVolumeMin}` +
                  `_s${stopAtr}` +
                  `_tr${trailAtr}`,
                family: "CROSS_VENUE_FLOW_TREND",
                session,
                riskFraction: 0.01,
                relativeVolumeMin: 0.8,
                flowWindow,
                flowThreshold,
                binanceRelativeVolumeMin,
                stopAtr,
                trailAtr,
                targetR: 8.0,
                maxHoldCandles: 576,
                maxTradesPerDay: 1,
                sideMode,
              });
            }
          }
        }
      }
    }
  }
  return candidates;
}

function buildCrossVenueDepthContinuationCandidates() {
  const candidates = [];
  const session = { id: "all", hours: null };
  for (const depthLevel of [1, 2, 5]) {
    for (const imbalanceThreshold of [0.10, 0.20, 0.30]) {
      for (const trendAligned of [false, true]) {
        for (const stopAtr of [1.5, 2.5]) {
          for (const targetR of [2.5, 4.0]) {
            candidates.push({
              id:
                `cross_depth_continue_l${depthLevel}` +
                `_i${imbalanceThreshold}` +
                `_trend${trendAligned}` +
                `_s${stopAtr}` +
                `_t${targetR}`,
              family: "CROSS_VENUE_DEPTH_CONTINUATION",
              session,
              riskFraction: 0.01,
              relativeVolumeMin: 1.0,
              depthLevel,
              imbalanceThreshold,
              trendAligned,
              stopAtr,
              targetR,
              maxHoldCandles: 72,
              maxTradesPerDay: 3,
              sideMode: "BOTH",
            });
          }
        }
      }
    }
  }
  return candidates;
}

function buildCrossVenueDepthFailureCandidates() {
  const candidates = [];
  const session = { id: "all", hours: null };
  for (const depthLevel of [1, 2, 5]) {
    for (const imbalanceThreshold of [0.10, 0.20, 0.30]) {
      for (const closeLocationExtreme of [0.60, 0.70]) {
        for (const stopAtr of [1.5, 2.5]) {
          for (const targetR of [2.5, 4.0]) {
            candidates.push({
              id:
                `cross_depth_failure_l${depthLevel}` +
                `_i${imbalanceThreshold}` +
                `_cl${closeLocationExtreme}` +
                `_s${stopAtr}` +
                `_t${targetR}`,
              family: "CROSS_VENUE_DEPTH_FAILURE",
              session,
              riskFraction: 0.01,
              relativeVolumeMin: 1.0,
              depthLevel,
              imbalanceThreshold,
              closeLocationExtreme,
              stopAtr,
              targetR,
              maxHoldCandles: 72,
              maxTradesPerDay: 3,
              sideMode: "BOTH",
            });
          }
        }
      }
    }
  }
  return candidates;
}

function buildCrossVenueDepthShiftCandidates() {
  const candidates = [];
  const session = { id: "all", hours: null };
  for (const depthLevel of [1, 2, 5]) {
    for (const changeThreshold of [0.05, 0.10, 0.20]) {
      for (const directionMode of ["CONTINUE", "FADE"]) {
        for (const stopAtr of [1.5, 2.5]) {
          for (const targetR of [2.5, 4.0]) {
            candidates.push({
              id:
                `cross_depth_shift_l${depthLevel}` +
                `_d${changeThreshold}` +
                `_${directionMode.toLowerCase()}` +
                `_s${stopAtr}` +
                `_t${targetR}`,
              family: "CROSS_VENUE_DEPTH_SHIFT",
              session,
              riskFraction: 0.01,
              relativeVolumeMin: 1.0,
              depthLevel,
              changeThreshold,
              directionMode,
              stopAtr,
              targetR,
              maxHoldCandles: 72,
              maxTradesPerDay: 3,
              sideMode: "BOTH",
            });
          }
        }
      }
    }
  }
  return candidates;
}

function buildCrossVenueOiFlowCandidates({
  flowSource = "METRICS",
  directionModes = ["CONTINUE"],
  oiMode = "BUILD",
} = {}) {
  const candidates = [];
  const session = { id: "all", hours: null };
  const idPrefix = flowSource === "KLINE"
    ? oiMode === "UNWIND" ? "cross_oi_kline_unwind" : "cross_oi_kline_flow"
    : "cross_oi_flow";
  const oiThresholds = new Map([
    [3, [0.32, 0.62]],
    [12, [0.80, 1.47]],
    [36, [1.65, 2.88]],
  ]);
  for (const [oiWindow, thresholds] of oiThresholds) {
    for (const oiChangeThreshold of thresholds) {
      for (const flowThreshold of [0.14, 0.24, 0.34]) {
        for (const directionMode of directionModes) {
          for (const stopAtr of [1.5, 2.5]) {
            for (const targetR of [2.5, 4.0]) {
              candidates.push({
                id:
                  `${idPrefix}_w${oiWindow}` +
                  `_oi${oiChangeThreshold}` +
                  `_f${flowThreshold}` +
                  `_${directionMode.toLowerCase()}` +
                  `_s${stopAtr}` +
                  `_t${targetR}`,
                family: "CROSS_VENUE_OI_FLOW",
                session,
                riskFraction: 0.01,
                relativeVolumeMin: 1.0,
                oiWindow,
                oiChangeThreshold,
                flowThreshold,
                flowSource,
                directionMode,
                oiMode,
                stopAtr,
                targetR,
                maxHoldCandles: 72,
                maxTradesPerDay: 3,
                sideMode: "BOTH",
              });
            }
          }
        }
      }
    }
  }
  return candidates;
}

function buildRejectionProbeCandidates() {
  const candidates = [];
  const sessions = [
    { id: "all", hours: null },
    { id: "us", hours: new Set([13, 14, 15, 16, 17, 18, 19, 20, 21]) },
    { id: "london_us", hours: new Set([7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19]) },
  ];

  for (const session of sessions) {
    for (const lookback of [24, 48]) {
      for (const relativeVolumeMin of [1.2, 1.5]) {
        for (const rejectBreakAtr of [0.0, 0.05]) {
          for (const stopAtr of [0.5, 0.8, 1.0]) {
            for (const targetR of [0.8, 1.2, 1.5]) {
              for (const maxHoldCandles of [12, 24, 36]) {
                for (const riskFraction of [0.015, 0.02, 0.03]) {
                  for (const maxTradesPerDay of [1, 2]) {
                    candidates.push({
                      id:
                        `reject_${session.id}` +
                        `_l${lookback}` +
                        `_rv${relativeVolumeMin}` +
                        `_b${rejectBreakAtr}` +
                        `_s${stopAtr}` +
                        `_t${targetR}` +
                        `_h${maxHoldCandles}` +
                        `_r${riskFraction}` +
                        `_mtd${maxTradesPerDay}`,
                      family: "RANGE_REJECTION",
                      session,
                      riskFraction,
                      lookback,
                      relativeVolumeMin,
                      rejectBreakAtr,
                      stopAtr,
                      targetR,
                      maxHoldCandles,
                      maxTradesPerDay,
                      sideMode: "BOTH",
                    });
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  return candidates;
}

function evaluateWindow(candidate, window, options = {}) {
  const startMs = Date.parse(window.replayStartAt);
  const endMs = Date.parse(window.replayEndAt);
  const startIndex = lowerBound(candles, startMs);
  const endIndex = upperBound(candles, endMs);
  const observedDays = Math.max(1, Math.round((endMs - startMs) / 86_400_000));

  let equity = 1_000_000.0;
  let peakEquity = equity;
  let maxDrawdownPct = 0.0;
  let tradeCount = 0;
  let wins = 0;
  let losses = 0;
  let grossProfit = 0.0;
  let grossLoss = 0.0;
  const activeDays = new Set();
  const tradesByDay = new Map();
  const trades = [];
  let index = Math.max(startIndex + 60, 60);

  while (index < endIndex - candidate.maxHoldCandles - 2) {
    const setup = findSetup(candidate, index, endIndex);
    if (setup == null) {
      index += 1;
      continue;
    }

    const dayTrades = tradesByDay.get(setup.entry.day) ?? 0;
    if (dayTrades >= candidate.maxTradesPerDay) {
      index += 1;
      continue;
    }

    const exit = simulateExit(candidate, setup, endIndex);
    if (exit == null) {
      index += 1;
      continue;
    }

    const riskAmount = equity * candidate.riskFraction;
    const quantity = riskAmount / setup.riskPerUnit;
    const grossPnl = setup.side === "BUY"
      ? (exit.exitPrice - setup.entryPrice) * quantity
      : (setup.entryPrice - exit.exitPrice) * quantity;
    const fees = ((setup.entryPrice + exit.exitPrice) * quantity) * 0.0006;
    const pnl = grossPnl - fees;
    const equityBefore = equity;

    equity += pnl;
    peakEquity = Math.max(peakEquity, equity);
    maxDrawdownPct = Math.max(maxDrawdownPct, ((peakEquity - equity) / peakEquity) * 100.0);
    tradeCount += 1;
    if (pnl > 0) {
      wins += 1;
      grossProfit += pnl;
    } else {
      losses += 1;
      grossLoss += Math.abs(pnl);
    }
    activeDays.add(setup.entry.day);
    tradesByDay.set(setup.entry.day, dayTrades + 1);
    if (options.traceTrades) {
      trades.push({
        openedAt: setup.entry.openedAt,
        closedAt: candles[exit.exitIndex].openedAt,
        side: setup.side,
        exitReason: exit.reason,
        entryPrice: round(setup.entryPrice),
        stopPrice: round(setup.stopPrice),
        targetPrice: setup.targetPrice == null ? null : round(setup.targetPrice),
        exitPrice: round(exit.exitPrice),
        riskPerUnit: round(setup.riskPerUnit),
        riskFraction: round(candidate.riskFraction),
        stopAtr: round(setup.stopAtr),
        targetR: round(setup.targetR),
        rMultipleGross: round(grossPnl / riskAmount),
        rMultipleNet: round(pnl / riskAmount),
        pnl: round(pnl),
        returnPct: round((pnl / equityBefore) * 100.0),
        equityAfter: round(equity),
        drawdownPct: round(((peakEquity - equity) / peakEquity) * 100.0),
        entryRelativeVolume: round(setup.entry.relativeVolume ?? 0.0),
        entryRangePct: round(setup.entry.rangePct ?? 0.0),
        entryBodyRatio: round(setup.entry.bodyRatio ?? 0.0),
        entryCloseLocation: round(setup.entry.closeLocation ?? 0.0),
        prior: {
          m5_7d: priorStatsFor(setup.entryIndex, 2_016),
          m5_14d: priorStatsFor(setup.entryIndex, 4_032),
          m5_30d: priorStatsFor(setup.entryIndex, 8_640),
          m5_60d: priorStatsFor(setup.entryIndex, 17_280),
        },
      });
    }
    index = exit.exitIndex + 1;
  }

  const netReturnPct = ((equity - 1_000_000.0) / 1_000_000.0) * 100.0;
  const compoundDailyReturnPct = equity > 0
    ? ((equity / 1_000_000.0) ** (1.0 / observedDays) - 1.0) * 100.0
    : -100.0;

  const report = {
    id: window.id,
    durationMonths: window.durationMonths,
    replayStartAt: window.replayStartAt,
    replayEndAt: window.replayEndAt,
    finalEquity: round(equity),
    netReturnPct: round(netReturnPct),
    compoundDailyReturnPct: round(compoundDailyReturnPct),
    drawdownPct: round(maxDrawdownPct),
    tradeCount,
    activeDayCoveragePct: round((activeDays.size / observedDays) * 100.0),
    winRatePct: tradeCount > 0 ? round((wins / tradeCount) * 100.0) : 0.0,
    profitFactor: grossLoss > 0 ? round(grossProfit / grossLoss) : grossProfit > 0 ? 999.0 : null,
  };
  if (options.traceTrades) report.trades = trades;
  return report;
}

function findSetup(candidate, index, endIndex) {
  const candle = candles[index];
  if (!sessionAllows(candidate.session, candle.hour)) return null;
  if (candle.atr20 == null || candle.atr20 <= 0.0) return null;
  if (candle.relativeVolume == null || candle.relativeVolume < candidate.relativeVolumeMin) return null;

  return switchFamily(candidate, index, endIndex);
}

function switchFamily(candidate, index, endIndex) {
  switch (candidate.family) {
    case "CLUSTER_BREAKOUT":
      return clusterBreakoutSetup(candidate, index);
    case "ABSORPTION_BREAKOUT":
      return absorptionBreakoutSetup(candidate, index, endIndex);
    case "TREND_TRAIL_BREAKOUT":
      return trendTrailBreakoutSetup(candidate, index);
    case "TREND_PULLBACK_ACCEPTANCE":
      return trendPullbackAcceptanceSetup(candidate, index);
    case "MACRO_TREND_BREAKOUT":
      return macroTrendBreakoutSetup(candidate, index);
    case "MACRO_DONCHIAN_TREND":
      return macroDonchianTrendSetup(candidate, index);
    case "CROSS_VENUE_FLOW_TREND":
      return crossVenueFlowTrendSetup(candidate, index);
    case "CROSS_VENUE_FLOW_ABSORPTION":
      return crossVenueFlowAbsorptionSetup(candidate, index);
    case "CROSS_VENUE_DEPTH_CONTINUATION":
      return crossVenueDepthContinuationSetup(candidate, index);
    case "CROSS_VENUE_DEPTH_FAILURE":
      return crossVenueDepthFailureSetup(candidate, index);
    case "CROSS_VENUE_DEPTH_SHIFT":
      return crossVenueDepthShiftSetup(candidate, index);
    case "CROSS_VENUE_OI_FLOW":
      return crossVenueOiFlowSetup(candidate, index);
    case "RANGE_REJECTION":
      return rangeRejectionSetup(candidate, index);
    case "EXHAUSTION_REVERSAL":
      return exhaustionReversalSetup(candidate, index, endIndex);
    default:
      return null;
  }
}

function macroDonchianTrendSetup(candidate, index) {
  const candle = candles[index];
  if (candle.ema288 == null || candle.ema1152 == null) return null;
  const channelHigh = candle[`donchianHigh${candidate.lookback}`];
  const channelLow = candle[`donchianLow${candidate.lookback}`];
  const slopeReference = candles[index - 288]?.ema288;
  if (channelHigh == null || channelLow == null || slopeReference == null) return null;
  const entryIndex = index + 1;
  const entry = candles[entryIndex];
  if (entry == null) return null;

  const longBreakout =
    candidate.sideMode !== "SHORT_ONLY" &&
    candle.ema288 > candle.ema1152 &&
    candle.ema288 > slopeReference &&
    candle.close > channelHigh;
  const shortBreakout =
    candidate.sideMode !== "LONG_ONLY" &&
    candle.ema288 < candle.ema1152 &&
    candle.ema288 < slopeReference &&
    candle.close < channelLow;
  if (!longBreakout && !shortBreakout) return null;

  const stopReferenceCandles = candidate.stopReferenceCandles ?? 288;
  const stopReference = rangeFor(index - stopReferenceCandles, index + 1);
  if (stopReference == null) return null;
  return buildSetup(candidate, {
    side: longBreakout ? "BUY" : "SELL",
    entryIndex,
    entry,
    stopReference,
  });
}

function crossVenueFlowTrendSetup(candidate, index) {
  const candle = candles[index];
  if (
    candle.ema20 == null ||
    candle.ema50 == null ||
    candle.ema200 == null ||
    candle.binanceRelativeQuoteVolume == null
  ) return null;
  const flow = candle[`binanceFlow${candidate.flowWindow}`];
  if (flow == null || candle.binanceRelativeQuoteVolume < candidate.binanceRelativeVolumeMin) return null;
  const slopeReference = candles[index - 12]?.ema50;
  const stopReference = rangeFor(index - 6, index + 1);
  const entryIndex = index + 1;
  const entry = candles[entryIndex];
  if (slopeReference == null || stopReference == null || entry == null) return null;

  const longTrend =
    candle.ema20 > candle.ema50 &&
    candle.ema50 > candle.ema200 &&
    candle.ema50 > slopeReference &&
    candle.close > candle.ema20;
  const longFlow =
    flow >= candidate.flowThreshold &&
    candle.binanceTakerImbalance > 0 &&
    candle.binanceReturnPct > 0;
  if (candidate.sideMode !== "SHORT_ONLY" && longTrend && longFlow) {
    return buildSetup(candidate, { side: "BUY", entryIndex, entry, stopReference });
  }

  const shortTrend =
    candle.ema20 < candle.ema50 &&
    candle.ema50 < candle.ema200 &&
    candle.ema50 < slopeReference &&
    candle.close < candle.ema20;
  const shortFlow =
    flow <= -candidate.flowThreshold &&
    candle.binanceTakerImbalance < 0 &&
    candle.binanceReturnPct < 0;
  if (candidate.sideMode !== "LONG_ONLY" && shortTrend && shortFlow) {
    return buildSetup(candidate, { side: "SELL", entryIndex, entry, stopReference });
  }
  return null;
}

function crossVenueFlowAbsorptionSetup(candidate, index) {
  const candle = candles[index];
  if (
    candle.binanceRelativeQuoteVolume == null ||
    candle.binanceRelativeQuoteVolume < candidate.binanceRelativeVolumeMin ||
    candle.binanceTakerImbalance == null
  ) return null;
  const entryIndex = index + 1;
  const entry = candles[entryIndex];
  if (entry == null) return null;
  const stopReference = { high: candle.high, low: candle.low };

  const sellFlowAbsorbed =
    candle.binanceTakerImbalance <= -candidate.flowThreshold &&
    candle.closeLocation >= candidate.closeLocationExtreme &&
    candle.close > candle.open;
  if (sellFlowAbsorbed) {
    return buildSetup(candidate, { side: "BUY", entryIndex, entry, stopReference });
  }

  const buyFlowAbsorbed =
    candle.binanceTakerImbalance >= candidate.flowThreshold &&
    candle.closeLocation <= 1.0 - candidate.closeLocationExtreme &&
    candle.close < candle.open;
  if (buyFlowAbsorbed) {
    return buildSetup(candidate, { side: "SELL", entryIndex, entry, stopReference });
  }
  return null;
}

function crossVenueDepthContinuationSetup(candidate, index) {
  const candle = candles[index];
  const imbalance = candle[`binanceDepthImbalance${candidate.depthLevel}`];
  if (imbalance == null) return null;
  const entryIndex = index + 1;
  const entry = candles[entryIndex];
  const stopReference = rangeFor(index - 6, index + 1);
  if (entry == null || stopReference == null) return null;

  const longTrend =
    !candidate.trendAligned ||
    (candle.ema20 != null && candle.ema50 != null && candle.ema20 > candle.ema50 && candle.close > candle.ema20);
  if (imbalance >= candidate.imbalanceThreshold && longTrend) {
    return buildSetup(candidate, { side: "BUY", entryIndex, entry, stopReference });
  }

  const shortTrend =
    !candidate.trendAligned ||
    (candle.ema20 != null && candle.ema50 != null && candle.ema20 < candle.ema50 && candle.close < candle.ema20);
  if (imbalance <= -candidate.imbalanceThreshold && shortTrend) {
    return buildSetup(candidate, { side: "SELL", entryIndex, entry, stopReference });
  }
  return null;
}

function crossVenueDepthFailureSetup(candidate, index) {
  const candle = candles[index];
  const imbalance = candle[`binanceDepthImbalance${candidate.depthLevel}`];
  if (imbalance == null) return null;
  const entryIndex = index + 1;
  const entry = candles[entryIndex];
  if (entry == null) return null;
  const stopReference = { high: candle.high, low: candle.low };

  const bidSupportFailed =
    imbalance >= candidate.imbalanceThreshold &&
    candle.side === "SELL" &&
    candle.closeLocation <= 1.0 - candidate.closeLocationExtreme;
  if (bidSupportFailed) {
    return buildSetup(candidate, { side: "SELL", entryIndex, entry, stopReference });
  }

  const askResistanceFailed =
    imbalance <= -candidate.imbalanceThreshold &&
    candle.side === "BUY" &&
    candle.closeLocation >= candidate.closeLocationExtreme;
  if (askResistanceFailed) {
    return buildSetup(candidate, { side: "BUY", entryIndex, entry, stopReference });
  }
  return null;
}

function crossVenueDepthShiftSetup(candidate, index) {
  const candle = candles[index];
  const change = candle[`binanceDepthImbalanceChange${candidate.depthLevel}`];
  if (change == null || Math.abs(change) < candidate.changeThreshold) return null;
  const entryIndex = index + 1;
  const entry = candles[entryIndex];
  if (entry == null) return null;
  const stopReference = { high: candle.high, low: candle.low };
  const changeSide = change > 0 ? "BUY" : "SELL";
  const side = candidate.directionMode === "CONTINUE"
    ? changeSide
    : changeSide === "BUY" ? "SELL" : "BUY";
  return buildSetup(candidate, { side, entryIndex, entry, stopReference });
}

function crossVenueOiFlowSetup(candidate, index) {
  const candle = candles[index];
  const oiChange = candle[`binanceOpenInterestChange${candidate.oiWindow}Pct`];
  const flow = candidate.flowSource === "KLINE"
    ? candle.binanceTakerImbalance
    : candle.binanceMetricsTakerImbalance;
  const oiTriggered = candidate.oiMode === "UNWIND"
    ? oiChange != null && oiChange <= -candidate.oiChangeThreshold
    : oiChange != null && oiChange >= candidate.oiChangeThreshold;
  if (!oiTriggered || flow == null) return null;
  const entryIndex = index + 1;
  const entry = candles[entryIndex];
  if (entry == null) return null;
  const stopReference = { high: candle.high, low: candle.low };
  const sideForFlow = (side) => candidate.directionMode === "FADE"
    ? side === "BUY" ? "SELL" : "BUY"
    : side;
  if (flow >= candidate.flowThreshold) {
    return buildSetup(candidate, { side: sideForFlow("BUY"), entryIndex, entry, stopReference });
  }
  if (flow <= -candidate.flowThreshold) {
    return buildSetup(candidate, { side: sideForFlow("SELL"), entryIndex, entry, stopReference });
  }
  return null;
}

function macroTrendBreakoutSetup(candidate, index) {
  const candle = candles[index];
  if (candle.ema288 == null || candle.ema1152 == null) return null;
  const slopeReference = candles[index - 144]?.ema288;
  if (slopeReference == null) return null;
  const channel = rangeFor(index - candidate.lookback, index);
  const stopReference = rangeFor(index - 12, index + 1);
  if (channel == null || stopReference == null) return null;
  const breakBuffer = candle.atr20 * candidate.minBreakAtr;
  const entryIndex = index + 1;
  const entry = candles[entryIndex];
  if (entry == null) return null;

  if (
    candidate.sideMode !== "SHORT_ONLY" &&
    candle.ema288 > candle.ema1152 &&
    candle.ema288 > slopeReference &&
    candle.close > channel.high + breakBuffer &&
    candle.close > candle.open &&
    candle.closeLocation >= 0.60
  ) {
    return buildSetup(candidate, { side: "BUY", entryIndex, entry, stopReference });
  }
  if (
    candidate.sideMode !== "LONG_ONLY" &&
    candle.ema288 < candle.ema1152 &&
    candle.ema288 < slopeReference &&
    candle.close < channel.low - breakBuffer &&
    candle.close < candle.open &&
    candle.closeLocation <= 0.40
  ) {
    return buildSetup(candidate, { side: "SELL", entryIndex, entry, stopReference });
  }
  return null;
}

function trendPullbackAcceptanceSetup(candidate, index) {
  const confirmation = candles[index];
  const trendStartIndex = index - candidate.trendLookback;
  const pullbackStartIndex = index - candidate.pullbackCandles;
  if (trendStartIndex < 0 || pullbackStartIndex < 0) return null;
  if (confirmation.ema20 == null || confirmation.ema50 == null || confirmation.ema200 == null) return null;

  const trendStart = candles[trendStartIndex];
  const pullbackCandles = candles.slice(pullbackStartIndex, index);
  if (pullbackCandles.length !== candidate.pullbackCandles) return null;
  const pullback = rangeFor(pullbackStartIndex, index);
  if (pullback == null) return null;
  const efficiency = trendEfficiency(index, candidate.trendLookback);
  if (efficiency == null || efficiency < candidate.minTrendEfficiency) return null;
  const atr = confirmation.atr20;
  const trendMoveAtr = Math.abs(confirmation.close - trendStart.close) / atr;
  if (trendMoveAtr < candidate.minTrendMoveAtr) return null;

  const averagePullbackRelativeVolume = average(
    pullbackCandles.map((candle) => candle.relativeVolume ?? Number.POSITIVE_INFINITY),
  );
  if (averagePullbackRelativeVolume > candidate.pullbackVolumeMax) return null;
  if (confirmation.bodyRatio < candidate.minConfirmationBodyRatio) return null;

  const prior = pullbackCandles[pullbackCandles.length - 1];
  const emaSlopeReference = candles[Math.max(0, index - 12)].ema50;
  if (emaSlopeReference == null) return null;
  const entryIndex = index + 1;
  const entry = candles[entryIndex];
  if (entry == null) return null;

  const longAligned =
    confirmation.ema20 > confirmation.ema50 &&
    confirmation.ema50 > confirmation.ema200 &&
    confirmation.ema50 > emaSlopeReference &&
    confirmation.close > confirmation.ema20;
  const longPullbackAccepted =
    pullback.low <= confirmation.ema20 + (atr * candidate.pullbackToleranceAtr) &&
    pullback.low >= confirmation.ema50 - (atr * candidate.pullbackToleranceAtr) &&
    confirmation.close > prior.high &&
    confirmation.closeLocation >= candidate.minConfirmationCloseLocation &&
    (confirmation.close - confirmation.ema20) / atr <= candidate.maxConfirmationDistanceAtr;
  if (candidate.sideMode !== "SHORT_ONLY" && longAligned && longPullbackAccepted) {
    return buildSetup(candidate, { side: "BUY", entryIndex, entry, stopReference: pullback });
  }

  const shortAligned =
    confirmation.ema20 < confirmation.ema50 &&
    confirmation.ema50 < confirmation.ema200 &&
    confirmation.ema50 < emaSlopeReference &&
    confirmation.close < confirmation.ema20;
  const shortPullbackAccepted =
    pullback.high >= confirmation.ema20 - (atr * candidate.pullbackToleranceAtr) &&
    pullback.high <= confirmation.ema50 + (atr * candidate.pullbackToleranceAtr) &&
    confirmation.close < prior.low &&
    confirmation.closeLocation <= 1.0 - candidate.minConfirmationCloseLocation &&
    (confirmation.ema20 - confirmation.close) / atr <= candidate.maxConfirmationDistanceAtr;
  if (candidate.sideMode !== "LONG_ONLY" && shortAligned && shortPullbackAccepted) {
    return buildSetup(candidate, { side: "SELL", entryIndex, entry, stopReference: pullback });
  }
  return null;
}

function trendEfficiency(index, lookback) {
  const startIndex = index - lookback;
  if (startIndex < 0) return null;
  const previous = startIndex > 0 ? candles[startIndex].cumulativeAbsoluteCloseChange : 0.0;
  const travelled = candles[index].cumulativeAbsoluteCloseChange - previous;
  if (travelled <= 0.0) return 0.0;
  return Math.abs(candles[index].close - candles[startIndex].close) / travelled;
}

function clusterBreakoutSetup(candidate, index) {
  const candle = candles[index];
  const previous = rangeFor(index - candidate.lookback, index);
  if (previous == null) return null;
  const cluster = rangeFor(index - candidate.clusterCandles + 1, index + 1);
  if (cluster == null) return null;
  const avgVolume = candles[index].avgVolume20;
  if (avgVolume == null || avgVolume <= 0.0) return null;
  const clusterVolumeRatio = cluster.volume / (avgVolume * candidate.clusterCandles);
  if (clusterVolumeRatio < candidate.clusterVolumeMin) return null;

  const atr = candle.atr20;
  const breakBuffer = atr * candidate.minBreakAtr;
  let side = null;
  if (candidate.sideMode !== "SHORT_ONLY" && candle.close > previous.high + breakBuffer) side = "BUY";
  if (candidate.sideMode !== "LONG_ONLY" && candle.close < previous.low - breakBuffer) side = "SELL";
  if (side == null) return null;

  const entryIndex = index + 1;
  const entry = candles[entryIndex];
  return buildSetup(candidate, { side, entryIndex, entry, stopReference: cluster });
}

function trendTrailBreakoutSetup(candidate, index) {
  const candle = candles[index];
  const previous = rangeFor(index - candidate.lookback, index);
  if (previous == null) return null;
  const cluster = rangeFor(index - candidate.clusterCandles + 1, index + 1);
  if (cluster == null) return null;
  const avgVolume = candle.avgVolume20;
  if (avgVolume == null || avgVolume <= 0.0) return null;
  const clusterVolumeRatio = cluster.volume / (avgVolume * candidate.clusterCandles);
  if (clusterVolumeRatio < candidate.clusterVolumeMin) return null;

  const breakBuffer = candle.atr20 * candidate.minBreakAtr;
  let side = null;
  if (
    candidate.sideMode !== "SHORT_ONLY" &&
    candle.close > previous.high + breakBuffer &&
    candle.close > candle.open
  ) {
    side = "BUY";
  }
  if (
    candidate.sideMode !== "LONG_ONLY" &&
    candle.close < previous.low - breakBuffer &&
    candle.close < candle.open
  ) {
    side = "SELL";
  }
  if (side == null) return null;

  const entryIndex = index + 1;
  const entry = candles[entryIndex];
  return buildSetup(candidate, { side, entryIndex, entry, stopReference: cluster });
}

function rangeRejectionSetup(candidate, index) {
  const candle = candles[index];
  const previous = rangeFor(index - candidate.lookback, index);
  if (previous == null) return null;

  const breakBuffer = candle.atr20 * candidate.rejectBreakAtr;
  const entryIndex = index + 1;
  const entry = candles[entryIndex];

  if (
    candidate.sideMode !== "LONG_ONLY" &&
    candle.high > previous.high + breakBuffer &&
    candle.close < previous.high &&
    candle.close < candle.open
  ) {
    return buildSetup(candidate, {
      side: "SELL",
      entryIndex,
      entry,
      stopReference: { high: candle.high, low: Math.min(candle.low, previous.low) },
    });
  }

  if (
    candidate.sideMode !== "SHORT_ONLY" &&
    candle.low < previous.low - breakBuffer &&
    candle.close > previous.low &&
    candle.close > candle.open
  ) {
    return buildSetup(candidate, {
      side: "BUY",
      entryIndex,
      entry,
      stopReference: { high: Math.max(candle.high, previous.high), low: candle.low },
    });
  }

  return null;
}

function absorptionBreakoutSetup(candidate, index, endIndex) {
  const candle = candles[index];
  const cluster = rangeFor(index - candidate.clusterCandles + 1, index + 1);
  if (cluster == null) return null;
  const avgVolume = candle.avgVolume20;
  if (avgVolume == null || avgVolume <= 0.0) return null;
  const clusterVolumeRatio = cluster.volume / (avgVolume * candidate.clusterCandles);
  if (clusterVolumeRatio < candidate.clusterVolumeMin) return null;
  const displacementAtr = Math.abs(candle.close - candles[index - candidate.clusterCandles + 1].open) / candle.atr20;
  const rangeAtr = (cluster.high - cluster.low) / candle.atr20;
  if (displacementAtr > candidate.maxDisplacementAtr || rangeAtr > candidate.maxRangeAtr) return null;

  const latestConfirmation = Math.min(index + candidate.entryLookaheadCandles, endIndex - 2);
  for (let confirmationIndex = index + 1; confirmationIndex <= latestConfirmation; confirmationIndex += 1) {
    const confirmation = candles[confirmationIndex];
    if (!sessionAllows(candidate.session, confirmation.hour)) continue;
    const entryIndex = causalFillIndex(confirmationIndex, endIndex);
    if (entryIndex == null) continue;
    const entry = candles[entryIndex];
    if (candidate.sideMode !== "SHORT_ONLY" && confirmation.close > cluster.high) {
      return buildSetup(candidate, { side: "BUY", entryIndex, entry, stopReference: cluster });
    }
    if (candidate.sideMode !== "LONG_ONLY" && confirmation.close < cluster.low) {
      return buildSetup(candidate, { side: "SELL", entryIndex, entry, stopReference: cluster });
    }
  }
  return null;
}

function exhaustionReversalSetup(candidate, index, endIndex) {
  const candle = candles[index];
  if ((candle.high - candle.low) / candle.atr20 < candidate.minRangeAtr) return null;
  if (candle.bodyRatio < candidate.minBodyRatio || candle.side == null) return null;

  const latestConfirmation = Math.min(index + candidate.entryLookaheadCandles, endIndex - 2);
  for (let confirmationIndex = index + 1; confirmationIndex <= latestConfirmation; confirmationIndex += 1) {
    const confirmation = candles[confirmationIndex];
    if (!sessionAllows(candidate.session, confirmation.hour)) continue;
    const entryIndex = causalFillIndex(confirmationIndex, endIndex);
    if (entryIndex == null) continue;
    const entry = candles[entryIndex];
    if (
      candle.side === "BUY" &&
      candle.closeLocation >= candidate.closeLocationExtreme &&
      confirmation.close < (candle.high + candle.low) / 2.0 &&
      candidate.sideMode !== "LONG_ONLY"
    ) {
      return buildSetup(candidate, {
        side: "SELL",
        entryIndex,
        entry,
        stopReference: { high: candle.high, low: candle.low },
      });
    }
    if (
      candle.side === "SELL" &&
      candle.closeLocation <= 1.0 - candidate.closeLocationExtreme &&
      confirmation.close > (candle.high + candle.low) / 2.0 &&
      candidate.sideMode !== "SHORT_ONLY"
    ) {
      return buildSetup(candidate, {
        side: "BUY",
        entryIndex,
        entry,
        stopReference: { high: candle.high, low: candle.low },
      });
    }
  }
  return null;
}

function causalFillIndex(confirmationIndex, endIndex) {
  const fillIndex = confirmationIndex + 1;
  return fillIndex < endIndex ? fillIndex : null;
}

function buildSetup(candidate, { side, entryIndex, entry, stopReference }) {
  if (!sideAllowedForRegime(candidate, side, entryIndex)) return null;
  const entryPrice = side === "BUY" ? entry.open * 1.0002 : entry.open * 0.9998;
  const stopAtr = stopAtrFor(candidate, entryIndex);
  const atrStop = entry.atr20 != null ? entry.atr20 * stopAtr : 0.0;
  const structuralStop = side === "BUY" ? stopReference.low : stopReference.high;
  const stopPrice = side === "BUY"
    ? Math.min(structuralStop, entryPrice - atrStop)
    : Math.max(structuralStop, entryPrice + atrStop);
  const riskPerUnit = Math.abs(entryPrice - stopPrice);
  const entryRiskPct = riskPerUnit / entryPrice;
  if (riskPerUnit <= 0.0 || entryRiskPct < 0.002 || entryRiskPct > 0.035) return null;
  const targetR = targetRFor(candidate, entryIndex);
  const targetPrice = side === "BUY"
    ? entryPrice + riskPerUnit * targetR
    : entryPrice - riskPerUnit * targetR;
  return {
    side,
    entryIndex,
    entry,
    entryPrice,
    stopPrice,
    targetPrice: candidate.trailAtr == null ? targetPrice : null,
    riskPerUnit,
    stopAtr,
    targetR,
  };
}

function sideAllowedForRegime(candidate, side, entryIndex) {
  if (candidate.sideRegimeBlocks == null || candidate.sideRegimeBlocks.length === 0) return true;
  for (const rule of candidate.sideRegimeBlocks) {
    if (rule.side !== side) continue;
    const stats = priorStatsFor(entryIndex, rule.lookbackCandles);
    if (stats == null) continue;
    if (rule.returnMinPct != null && stats.returnPct < rule.returnMinPct) continue;
    if (rule.returnMaxPct != null && stats.returnPct >= rule.returnMaxPct) continue;
    if (rule.avgVolumeMin != null && stats.avgVolume < rule.avgVolumeMin) continue;
    if (rule.avgVolumeMax != null && stats.avgVolume >= rule.avgVolumeMax) continue;
    if (rule.avgRangePctMin != null && stats.avgRangePct < rule.avgRangePctMin) continue;
    if (rule.confirmLookbackCandles != null) {
      const confirmStats = priorStatsFor(entryIndex, rule.confirmLookbackCandles);
      if (confirmStats == null) continue;
      if (rule.confirmReturnMinPct != null && confirmStats.returnPct < rule.confirmReturnMinPct) continue;
      if (rule.confirmReturnMaxPct != null && confirmStats.returnPct >= rule.confirmReturnMaxPct) continue;
      if (rule.confirmAvgVolumeMin != null && confirmStats.avgVolume < rule.confirmAvgVolumeMin) continue;
      if (rule.confirmAvgVolumeMax != null && confirmStats.avgVolume >= rule.confirmAvgVolumeMax) continue;
      if (rule.confirmAvgRangePctMin != null && confirmStats.avgRangePct < rule.confirmAvgRangePctMin) continue;
    }
    return false;
  }
  return true;
}

function targetRFor(candidate, entryIndex) {
  const adaptive = candidate.adaptiveTarget;
  if (adaptive == null) return candidate.targetR;
  const stats = priorStatsFor(entryIndex, adaptive.lookbackCandles);
  if (stats == null) return candidate.targetR;
  if (stats.returnPct > adaptive.returnMaxPct) return candidate.targetR;
  if (stats.avgVolume < adaptive.avgVolumeMin) return candidate.targetR;
  if (stats.avgRangePct < adaptive.avgRangePctMin) return candidate.targetR;
  return adaptive.targetR;
}

function stopAtrFor(candidate, entryIndex) {
  const adaptive = candidate.adaptiveStop;
  if (adaptive == null) return candidate.stopAtr;
  const stats = priorStatsFor(entryIndex, adaptive.lookbackCandles);
  if (stats == null) return candidate.stopAtr;
  if (stats.returnPct < adaptive.returnMinPct) return candidate.stopAtr;
  if (stats.avgVolume < adaptive.avgVolumeMin) return candidate.stopAtr;
  if (stats.avgRangePct < adaptive.avgRangePctMin) return candidate.stopAtr;
  return adaptive.stopAtr;
}

function priorStatsFor(index, lookbackCandles) {
  const start = index - lookbackCandles;
  if (start < 0 || start >= index) return null;
  const first = candles[start];
  const last = candles[index - 1];
  if (first.open <= 0.0) return null;
  const previous = start > 0 ? candles[start - 1] : null;
  const volume = last.cumulativeVolume - (previous?.cumulativeVolume ?? 0.0);
  const rangePct = last.cumulativeRangePct - (previous?.cumulativeRangePct ?? 0.0);
  return {
    returnPct: ((last.close / first.open) - 1.0) * 100.0,
    avgVolume: volume / lookbackCandles,
    avgRangePct: rangePct / lookbackCandles,
  };
}

function simulateExit(candidate, setup, endIndex) {
  if (candidate.trailAtr != null) return simulateTrailingExit(candidate, setup, endIndex);

  const end = Math.min(setup.entryIndex + candidate.maxHoldCandles, endIndex - 1);
  for (let index = setup.entryIndex; index <= end; index += 1) {
    const candle = candles[index];
    if (setup.side === "BUY") {
      if (candle.low <= setup.stopPrice) {
        return { exitIndex: index, exitPrice: setup.stopPrice, reason: "STOP" };
      }
      if (candle.high >= setup.targetPrice) {
        return { exitIndex: index, exitPrice: setup.targetPrice, reason: "TARGET" };
      }
    } else {
      if (candle.high >= setup.stopPrice) {
        return { exitIndex: index, exitPrice: setup.stopPrice, reason: "STOP" };
      }
      if (candle.low <= setup.targetPrice) {
        return { exitIndex: index, exitPrice: setup.targetPrice, reason: "TARGET" };
      }
    }
  }
  return { exitIndex: end, exitPrice: candles[end].close, reason: "TIME" };
}

function simulateTrailingExit(candidate, setup, endIndex) {
  const end = Math.min(setup.entryIndex + candidate.maxHoldCandles, endIndex - 1);
  let trailingStop = setup.stopPrice;
  let bestHigh = setup.entryPrice;
  let bestLow = setup.entryPrice;

  for (let index = setup.entryIndex; index <= end; index += 1) {
    const candle = candles[index];
    if (setup.side === "BUY") {
      if (candle.low <= trailingStop) {
        return { exitIndex: index, exitPrice: trailingStop, reason: "TRAILING_STOP" };
      }
      bestHigh = Math.max(bestHigh, candle.high);
      const atr = candle.atr20 ?? candles[setup.entryIndex].atr20;
      if (atr != null && atr > 0.0) {
        trailingStop = Math.max(trailingStop, bestHigh - atr * candidate.trailAtr);
      }
    } else {
      if (candle.high >= trailingStop) {
        return { exitIndex: index, exitPrice: trailingStop, reason: "TRAILING_STOP" };
      }
      bestLow = Math.min(bestLow, candle.low);
      const atr = candle.atr20 ?? candles[setup.entryIndex].atr20;
      if (atr != null && atr > 0.0) {
        trailingStop = Math.min(trailingStop, bestLow + atr * candidate.trailAtr);
      }
    }
  }
  return { exitIndex: end, exitPrice: candles[end].close, reason: "TIME" };
}

function rangeFor(start, end) {
  if (start < 0 || end > candles.length || start >= end) return null;
  let high = -Infinity;
  let low = Infinity;
  let volume = 0.0;
  for (let index = start; index < end; index += 1) {
    const candle = candles[index];
    high = Math.max(high, candle.high);
    low = Math.min(low, candle.low);
    volume += candle.volume;
  }
  return { high, low, volume };
}

function sessionAllows(session, hour) {
  return session.hours == null || session.hours.has(hour);
}

function summarizeCandidate(candidate, reports) {
  const cdrValues = reports.map((report) => report.compoundDailyReturnPct);
  const drawdowns = reports.map((report) => report.drawdownPct);
  const active = reports.map((report) => report.activeDayCoveragePct);
  const passCount = reports.filter((report) => report.compoundDailyReturnPct >= targetCdrPct).length;
  return {
    id: candidate.id,
    family: candidate.family,
    passCount,
    failCount: reports.length - passCount,
    targetCdrPct,
    worstCompoundDailyReturnPct: round(Math.min(...cdrValues)),
    medianCompoundDailyReturnPct: round(median(cdrValues)),
    averageCompoundDailyReturnPct: round(average(cdrValues)),
    maxDrawdownPct: round(Math.max(...drawdowns)),
    averageActiveDayCoveragePct: round(average(active)),
    reports,
    candidate: printableCandidate(candidate),
  };
}

function summarizeTraceSides(reports) {
  const groups = new Map();
  for (const trade of reports.flatMap((report) => report.trades ?? [])) {
    const current = groups.get(trade.side) ?? { side: trade.side, trades: 0, wins: 0, rNet: 0.0, pnl: 0.0 };
    current.trades += 1;
    if (trade.pnl > 0.0) current.wins += 1;
    current.rNet += trade.rMultipleNet;
    current.pnl += trade.pnl;
    groups.set(trade.side, current);
  }
  return [...groups.values()].map((item) => ({
    ...item,
    winRatePct: item.trades > 0 ? round((item.wins / item.trades) * 100.0) : 0.0,
    avgRNet: item.trades > 0 ? round(item.rNet / item.trades) : 0.0,
    rNet: round(item.rNet),
    pnl: round(item.pnl),
  }));
}

function summarizeTraceExits(reports) {
  const groups = new Map();
  for (const trade of reports.flatMap((report) => report.trades ?? [])) {
    const key = `${trade.side}_${trade.exitReason}`;
    const current = groups.get(key) ?? {
      side: trade.side,
      exitReason: trade.exitReason,
      trades: 0,
      wins: 0,
      rNet: 0.0,
      pnl: 0.0,
    };
    current.trades += 1;
    if (trade.pnl > 0.0) current.wins += 1;
    current.rNet += trade.rMultipleNet;
    current.pnl += trade.pnl;
    groups.set(key, current);
  }
  return [...groups.values()].map((item) => ({
    ...item,
    winRatePct: item.trades > 0 ? round((item.wins / item.trades) * 100.0) : 0.0,
    avgRNet: item.trades > 0 ? round(item.rNet / item.trades) : 0.0,
    rNet: round(item.rNet),
    pnl: round(item.pnl),
  }));
}

function printableCandidate(candidate) {
  const copy = { ...candidate, session: candidate.session.id };
  delete copy.session.hours;
  return copy;
}

function rankResults(items) {
  return [...items].sort((left, right) =>
    right.passCount - left.passCount ||
    right.worstCompoundDailyReturnPct - left.worstCompoundDailyReturnPct ||
    right.medianCompoundDailyReturnPct - left.medianCompoundDailyReturnPct ||
    right.averageCompoundDailyReturnPct - left.averageCompoundDailyReturnPct ||
    left.maxDrawdownPct - right.maxDrawdownPct,
  );
}

function lowerBound(items, ms) {
  let low = 0;
  let high = items.length;
  while (low < high) {
    const mid = Math.floor((low + high) / 2);
    if (items[mid].openedAtMs < ms) low = mid + 1;
    else high = mid;
  }
  return low;
}

function upperBound(items, ms) {
  let low = 0;
  let high = items.length;
  while (low < high) {
    const mid = Math.floor((low + high) / 2);
    if (items[mid].openedAtMs <= ms) low = mid + 1;
    else high = mid;
  }
  return low;
}

function average(values) {
  return values.length === 0 ? 0 : values.reduce((total, value) => total + value, 0) / values.length;
}

function median(values) {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((left, right) => left - right);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
}

function fmt(value) {
  return Number.isFinite(value) ? value.toFixed(5) : "n/a";
}

function round(value) {
  return Number.isFinite(value) ? Number(value.toFixed(5)) : value;
}
