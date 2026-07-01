#!/usr/bin/env node

import fs from "node:fs/promises";
import path from "node:path";

const DEFAULT_TARGET = {
  compoundDailyReturnPct: 0.35,
  maxDrawdownPct: 6,
  activeDayCoveragePct: 20,
  minWinRateEdgePct: 0,
  maxConsecutiveLosses: 4,
};

const args = parseArgs(process.argv.slice(2));
const apiBase = args.api ?? process.env.VOLUME_FLOW_API ?? "http://127.0.0.1:18080";
const token = args.token ?? process.env.BOT_CONTROL_TOKEN ?? "local-test-token";
const configPath = args.config ?? "config/volume-flow-composite-current.json";
const outDir = args.out ?? "build/volume-flow-tuning";
const maxVariants = Number(args.max ?? 160);
const topCount = Number(args.top ?? 20);

const baseConfig = JSON.parse(await fs.readFile(configPath, "utf8"));
const variantFilter = args.match;
const generatedVariants = uniqueVariants(generateVariants(baseConfig));
const variants = (variantFilter == null ? generatedVariants : generatedVariants.filter((variant) => variant.name.includes(variantFilter)))
  .slice(0, maxVariants);
await fs.mkdir(outDir, { recursive: true });

const results = [];
console.log(`Evaluating ${variants.length} composite variants against ${apiBase}`);

for (let index = 0; index < variants.length; index += 1) {
  const variant = variants[index];
  const result = await runComposite(variant);
  results.push(result);

  if ((index + 1) % 10 === 0 || result.passesTarget || index === variants.length - 1) {
    const best = rankResults(results)[0];
    const bestSummary = best?.summary;
    console.log(
      [
        `progress=${index + 1}/${variants.length}`,
        `latest=${result.name}`,
        `pass=${result.passesTarget}`,
        bestSummary
          ? `best=${best.name} cdr=${fmt(bestSummary.compoundDailyReturnPct)} coverage=${fmt(
              bestSummary.activeDayCoveragePct,
            )} mdd=${fmt(bestSummary.maxDrawdownPct)} mtmMdd=${fmt(
              bestSummary.markToMarketMaxDrawdownPct,
            )} maxLoss=${bestSummary.maxConsecutiveLosses}`
          : "best=n/a",
      ].join(" "),
    );
  }
}

const ranked = rankResults(results);
await fs.writeFile(path.join(outDir, "volume-flow-tuning-results.json"), JSON.stringify(ranked, null, 2));
if (ranked[0]?.config) {
  await fs.writeFile(path.join(outDir, "volume-flow-best-config.json"), JSON.stringify(ranked[0].config, null, 2));
}

console.log(JSON.stringify(ranked.slice(0, topCount).map(toPrintableResult), null, 2));

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

function generateVariants(config) {
  const variants = [];
  variants.push(namedVariant("baseline", config));

  for (const boosterLegId of ["range_failed_break", "range_failed_break_loose"]) {
    const legIndex = config.legs.findIndex((leg) => leg.id === boosterLegId);
    if (legIndex < 0) continue;
    const leg = config.legs[legIndex];
    for (const boosterRiskFraction of [0.00025, 0.0005, 0.001, 0.0015, 0.002, 0.003, 0.004]) {
      for (const maxConsecutiveLosses of [1, 2, 3]) {
        for (const maxTradesPerDay of [3, 4]) {
          variants.push(
            namedVariant(
              `portfolio_core3_${leg.id}_br${boosterRiskFraction}_l${maxConsecutiveLosses}_d${maxTradesPerDay}`,
              withLegPatch(
                withGlobal(config, {
                  riskFraction: 0.03,
                  maxConcurrentPositions: 3,
                  maxTradesPerDay,
                  maxConsecutiveLosses,
                  dedupeSameSetupSignals: false,
                }),
                legIndex,
                {
                  ...relaxedLegPatch(leg),
                  riskFraction: boosterRiskFraction,
                },
              ),
            ),
          );
        }
      }
    }
  }

  for (const legId of [
    "range_failed_break",
    "range_failed_break_loose",
    "trend_down_retest_runner",
    "m1_trend_up_breakout_scalp",
    "m1_chop_volume_rejection_scalp",
  ]) {
    const legIndex = config.legs.findIndex((leg) => leg.id === legId);
    if (legIndex < 0) continue;
    const leg = config.legs[legIndex];
    for (const riskFraction of [0.023, 0.027, 0.03]) {
      for (const maxConcurrentPositions of [1, 2, 3]) {
        for (const maxTradesPerDay of [2, 3]) {
          for (const maxConsecutiveLosses of [1, 2, 3]) {
            for (const dedupeSameSetupSignals of [false, true]) {
              variants.push(
                namedVariant(
                  `coverage_${leg.id}_r${riskFraction}_c${maxConcurrentPositions}_d${maxTradesPerDay}_l${maxConsecutiveLosses}${dedupeSameSetupSignals ? "_dedupe" : ""}`,
                  withLegPatch(
                    withGlobal(config, {
                      riskFraction,
                      maxConcurrentPositions,
                      maxTradesPerDay,
                      maxConsecutiveLosses,
                      dedupeSameSetupSignals,
                    }),
                    legIndex,
                    relaxedLegPatch(leg),
                  ),
                ),
              );
            }
          }
        }
      }
    }
  }

  for (const [firstLegId, secondLegId] of [
    ["m1_chop_volume_rejection_scalp", "range_failed_break"],
    ["m1_chop_volume_rejection_scalp", "m1_trend_up_breakout_scalp"],
    ["m1_chop_volume_rejection_scalp", "trend_down_retest_runner"],
    ["range_failed_break", "m1_trend_up_breakout_scalp"],
  ]) {
    const firstLegIndex = config.legs.findIndex((leg) => leg.id === firstLegId);
    const secondLegIndex = config.legs.findIndex((leg) => leg.id === secondLegId);
    if (firstLegIndex < 0 || secondLegIndex < 0) continue;
    for (const riskFraction of [0.027, 0.03]) {
      for (const maxConcurrentPositions of [2, 3]) {
        for (const maxTradesPerDay of [2, 3]) {
          for (const maxConsecutiveLosses of [1, 2]) {
            const globalConfig = withGlobal(config, {
              riskFraction,
              maxConcurrentPositions,
              maxTradesPerDay,
              maxConsecutiveLosses,
              dedupeSameSetupSignals: true,
            });
            const firstPatched = withLegPatch(
              globalConfig,
              firstLegIndex,
              relaxedLegPatch(config.legs[firstLegIndex]),
            );
            variants.push(
              namedVariant(
                `pair_${firstLegId}_${secondLegId}_r${riskFraction}_c${maxConcurrentPositions}_d${maxTradesPerDay}_l${maxConsecutiveLosses}`,
                withLegPatch(firstPatched, secondLegIndex, relaxedLegPatch(config.legs[secondLegIndex])),
              ),
            );
          }
        }
      }
    }
  }

  for (const riskFraction of [0.023, 0.025, 0.027, 0.03]) {
    for (const maxConcurrentPositions of [2, 3, 4]) {
      for (const maxTradesPerDay of [3, 4, 5]) {
        for (const maxConsecutiveLosses of [2, 3, 4]) {
          for (const dedupeSameSetupSignals of [false, true]) {
            variants.push(
              namedVariant(
                `global_r${riskFraction}_c${maxConcurrentPositions}_d${maxTradesPerDay}_l${maxConsecutiveLosses}${dedupeSameSetupSignals ? "_dedupe" : ""}`,
                withGlobal(config, {
                  riskFraction,
                  maxConcurrentPositions,
                  maxTradesPerDay,
                  maxConsecutiveLosses,
                  dedupeSameSetupSignals,
                }),
              ),
            );
          }
        }
      }
    }
  }

  config.legs.forEach((leg, legIndex) => {
    for (const riskFraction of [0.023, 0.027, 0.03]) {
      variants.push(
        namedVariant(
          `relax_${leg.id}_r${riskFraction}`,
          withLegPatch(withGlobal(config, { riskFraction }), legIndex, relaxedLegPatch(leg)),
        ),
      );
    }

    for (const targetR of targetCandidates(leg.targetR ?? 1.0)) {
      variants.push(
        namedVariant(
          `target_${leg.id}_${targetR}`,
          withLegPatch(config, legIndex, { targetR, maxHoldM1Candles: Math.min((leg.maxHoldM1Candles ?? 30) + 5, 45) }),
        ),
      );
    }

    for (const [checkCandles, minR] of [
      [3, 0.25],
      [5, 0.35],
      [8, 0.45],
    ]) {
      variants.push(
        namedVariant(
          `follow_through_${leg.id}_${checkCandles}_${minR}`,
          withLegPatch(config, legIndex, {
            followThroughCheckM1Candles: checkCandles,
            minFollowThroughR: minR,
            maxHoldM1Candles: Math.max(leg.maxHoldM1Candles ?? 30, checkCandles),
          }),
        ),
      );
    }
  });

  return variants;
}

function relaxedLegPatch(leg) {
  return {
    relativeVolumeThreshold: bounded(leg.relativeVolumeThreshold - 0.5, 1.5, 10),
    volumeZScoreThreshold: Math.min(leg.volumeZScoreThreshold ?? 0.5, 0.5),
    minBodyRatio: bounded((leg.minBodyRatio ?? 0.45) - 0.1, 0.1, 1),
    minDirectionalCloseStrength: bounded((leg.minDirectionalCloseStrength ?? 0.7) - 0.1, 0.5, 1),
    minRejectionWickRatio: bounded((leg.minRejectionWickRatio ?? 0.25) - 0.05, 0.05, 1),
    keyLevelTolerancePct: Math.max(leg.keyLevelTolerancePct ?? 0.0025, 0.0035),
    minEntryRiskPct: Math.min(leg.minEntryRiskPct ?? 0.008, 0.006),
    maxEntryRiskPct: Math.max(leg.maxEntryRiskPct ?? 0.015, 0.02),
    maxHoldM1Candles: Math.min((leg.maxHoldM1Candles ?? 30) + 5, 45),
  };
}

function namedVariant(name, config) {
  return {
    name,
    config: {
      ...config,
      tradeLimit: 0,
    },
  };
}

function withGlobal(config, patch) {
  const riskFraction = patch.riskFraction;
  return {
    ...structuredClone(config),
    maxConcurrentPositions: patch.maxConcurrentPositions ?? config.maxConcurrentPositions,
    maxTradesPerDay: patch.maxTradesPerDay ?? config.maxTradesPerDay,
    maxConsecutiveLosses: patch.maxConsecutiveLosses ?? config.maxConsecutiveLosses,
    dedupeSameSetupSignals: patch.dedupeSameSetupSignals ?? config.dedupeSameSetupSignals,
    legs: config.legs.map((leg) => ({
      ...leg,
      riskFraction: riskFraction ?? leg.riskFraction,
    })),
  };
}

function withLegPatch(config, legIndex, patch) {
  const next = structuredClone(config);
  next.legs[legIndex] = {
    ...next.legs[legIndex],
    ...patch,
  };
  return next;
}

function targetCandidates(current) {
  return [0.6, 0.8, 1.0, 1.2, 1.5].filter((candidate) => Math.abs(candidate - current) > 0.0001);
}

function uniqueVariants(variants) {
  const seen = new Set();
  return variants.filter((variant) => {
    const key = JSON.stringify(variant.config);
    if (seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

async function runComposite(variant) {
  try {
    const response = await fetch(`${apiBase}/backtests/volume-flow/composite/run`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(variant.config),
    });
    const body = await response.text();
    if (!response.ok) {
      return {
        name: variant.name,
        error: `HTTP ${response.status}: ${body.slice(0, 500)}`,
      };
    }
    const report = JSON.parse(body);
    return {
      name: variant.name,
      passesTarget: passesTarget(report),
      score: score(report),
      summary: summarize(report),
      config: variant.config,
    };
  } catch (error) {
    return {
      name: variant.name,
      error: error instanceof Error ? error.message : String(error),
    };
  }
}

function passesTarget(report) {
  const deployableDrawdownPct = deploymentDrawdownPct(report);
  return (
    report.compoundDailyReturnPct >= DEFAULT_TARGET.compoundDailyReturnPct &&
    deployableDrawdownPct <= DEFAULT_TARGET.maxDrawdownPct &&
    report.activeDayCoveragePct >= DEFAULT_TARGET.activeDayCoveragePct &&
    (report.winRateEdgePct ?? -100) > DEFAULT_TARGET.minWinRateEdgePct &&
    report.maxConsecutiveLosses <= DEFAULT_TARGET.maxConsecutiveLosses
  );
}

function score(report) {
  const targetBonus = passesTarget(report) ? 100000 : 0;
  const cdrScore = report.compoundDailyReturnPct * 10000;
  const coverageScore = report.activeDayCoveragePct * 80;
  const edgeScore = (report.winRateEdgePct ?? -50) * 25;
  const expectancyScore = report.expectancyR * 1000;
  const drawdownPenalty = deploymentDrawdownPct(report) * 300;
  const lossPenalty = Math.max(0, report.maxConsecutiveLosses - DEFAULT_TARGET.maxConsecutiveLosses) * 1200;
  return targetBonus + cdrScore + coverageScore + edgeScore + expectancyScore - drawdownPenalty - lossPenalty;
}

function deploymentDrawdownPct(report) {
  return Math.max(report.maxDrawdownPct ?? 0, report.markToMarketMaxDrawdownPct ?? report.maxDrawdownPct ?? 0);
}

function summarize(report) {
  return {
    netReturnPct: report.netReturnPct,
    compoundDailyReturnPct: report.compoundDailyReturnPct,
    maxDrawdownPct: report.maxDrawdownPct,
    markToMarketMaxDrawdownPct: report.markToMarketMaxDrawdownPct,
    averageMaxFavorableExcursionR: report.averageMaxFavorableExcursionR,
    averageMaxAdverseExcursionR: report.averageMaxAdverseExcursionR,
    averageMfeCapturePct: report.averageMfeCapturePct,
    tradeCount: report.tradeCount,
    winRatePct: report.winRatePct,
    payoffRatio: report.payoffRatio,
    winRateEdgePct: report.winRateEdgePct,
    expectancyR: report.expectancyR,
    maxConsecutiveLosses: report.maxConsecutiveLosses,
    activeDays: report.activeDays,
    observedDays: report.observedDays,
    activeDayCoveragePct: report.activeDayCoveragePct,
    averageTradesPerActiveDay: report.averageTradesPerActiveDay,
  };
}

function rankResults(results) {
  return [...results]
    .filter((result) => !result.error)
    .sort((left, right) => right.score - left.score);
}

function toPrintableResult(result) {
  return {
    name: result.name,
    passesTarget: result.passesTarget,
    score: Number(result.score.toFixed(5)),
    ...result.summary,
  };
}

function bounded(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

function fmt(value) {
  if (value == null || Number.isNaN(value)) return "n/a";
  return Number(value).toFixed(5);
}
