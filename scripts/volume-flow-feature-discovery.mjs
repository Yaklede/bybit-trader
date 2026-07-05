#!/usr/bin/env node

import fs from "node:fs/promises";
import path from "node:path";
import { execFileSync } from "node:child_process";

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

await fs.mkdir(outDir, { recursive: true });

const windows = JSON.parse(await fs.readFile(windowsPath, "utf8"));
const candles = loadCandles({ dbPath, timeframe });
attachIndicators(candles);

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

    cumulativeVolume += candle.volume;
    cumulativeRangePct += candle.rangePct;
    candle.cumulativeVolume = cumulativeVolume;
    candle.cumulativeRangePct = cumulativeRangePct;

    volumeQueue.push(candle.volume);
    volumeSum += candle.volume;
    if (volumeQueue.length > 20) volumeSum -= volumeQueue.shift();

    trQueue.push(trueRange);
    trSum += trueRange;
    if (trQueue.length > 20) trSum -= trQueue.shift();
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
    case "RANGE_REJECTION":
      return rangeRejectionSetup(candidate, index);
    case "EXHAUSTION_REVERSAL":
      return exhaustionReversalSetup(candidate, index, endIndex);
    default:
      return null;
  }
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

  const latestEntry = Math.min(index + candidate.entryLookaheadCandles, endIndex - 1);
  for (let entryIndex = index + 1; entryIndex <= latestEntry; entryIndex += 1) {
    const entry = candles[entryIndex];
    if (!sessionAllows(candidate.session, entry.hour)) continue;
    if (candidate.sideMode !== "SHORT_ONLY" && entry.close > cluster.high) {
      return buildSetup(candidate, { side: "BUY", entryIndex, entry, stopReference: cluster });
    }
    if (candidate.sideMode !== "LONG_ONLY" && entry.close < cluster.low) {
      return buildSetup(candidate, { side: "SELL", entryIndex, entry, stopReference: cluster });
    }
  }
  return null;
}

function exhaustionReversalSetup(candidate, index, endIndex) {
  const candle = candles[index];
  if ((candle.high - candle.low) / candle.atr20 < candidate.minRangeAtr) return null;
  if (candle.bodyRatio < candidate.minBodyRatio || candle.side == null) return null;

  const latestEntry = Math.min(index + candidate.entryLookaheadCandles, endIndex - 1);
  for (let entryIndex = index + 1; entryIndex <= latestEntry; entryIndex += 1) {
    const entry = candles[entryIndex];
    if (!sessionAllows(candidate.session, entry.hour)) continue;
    if (
      candle.side === "BUY" &&
      candle.closeLocation >= candidate.closeLocationExtreme &&
      entry.close < (candle.high + candle.low) / 2.0 &&
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
      entry.close > (candle.high + candle.low) / 2.0 &&
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
