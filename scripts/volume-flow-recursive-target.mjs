#!/usr/bin/env node

import fs from "node:fs/promises";
import path from "node:path";

const DEFAULT_LIMITS = {
  m1Limit: 1_578_240,
  m5Limit: 315_648,
  m15Limit: 105_216,
};

const args = parseArgs(process.argv.slice(2));
const apiBase = args.api ?? process.env.VOLUME_FLOW_API ?? "http://127.0.0.1:18080";
const token = args.token ?? process.env.BOT_CONTROL_TOKEN ?? "local-test-token";
const configPath = args.config ?? "config/volume-flow-composite-current.json";
const outDir = args.out ?? "build/volume-flow-target-search";
const rounds = Number(args.rounds ?? 3);
const beam = Number(args.beam ?? 8);
const maxPerRound = Number(args.maxPerRound ?? 40);
const initialEquity = Number(args.initialEquity ?? 1_000_000);
const targetMultiple = Number(args.targetMultiple ?? 10_000);
const targetDays = Number(args.days ?? 1096);
const maxDeployableDrawdownPct = Number(args.maxDeployableDrawdownPct ?? 40);
const maxDeployableConsecutiveLosses = Number(args.maxDeployableConsecutiveLosses ?? 20);
const targetCompoundDailyReturnPct = (Math.pow(targetMultiple, 1 / targetDays) - 1) * 100;

const baseConfig = JSON.parse(await fs.readFile(configPath, "utf8"));
await fs.mkdir(outDir, { recursive: true });

const seen = new Set();
const results = [];
let frontier = seedVariants(baseConfig);

console.log(
  [
    `targetMultiple=${targetMultiple}`,
    `targetCompoundDailyReturnPct=${fmt(targetCompoundDailyReturnPct)}`,
    `rounds=${rounds}`,
    `beam=${beam}`,
    `maxPerRound=${maxPerRound}`,
    `maxDeployableDrawdownPct=${maxDeployableDrawdownPct}`,
  ].join(" "),
);

for (let round = 1; round <= rounds; round += 1) {
  const candidates = nextCandidates(frontier, maxPerRound);
  console.log(`round=${round} candidates=${candidates.length}`);

  for (let index = 0; index < candidates.length; index += 1) {
    const candidate = candidates[index];
    const result = await runComposite(candidate, round);
    results.push(result);

    if ((index + 1) % 5 === 0 || result.deployableTargetHit || result.rawTargetHit || index === candidates.length - 1) {
      const best = rankResults(results)[0];
      console.log(
        [
          `round=${round}`,
          `progress=${index + 1}/${candidates.length}`,
          `latest=${candidate.name}`,
          `rawHit=${result.rawTargetHit === true}`,
          `deployableHit=${result.deployableTargetHit === true}`,
          best ? `best=${best.name}` : "best=n/a",
          best ? `bestCdr=${fmt(best.summary.compoundDailyReturnPct)}` : "",
          best ? `bestMdd=${fmt(best.summary.maxDrawdownPct)}` : "",
        ].join(" "),
      );
    }
  }

  const ranked = rankResults(results);
  await fs.writeFile(path.join(outDir, `round-${round}-ranked.json`), JSON.stringify(ranked, null, 2));
  if (ranked[0]?.config) {
    await fs.writeFile(path.join(outDir, `round-${round}-best-config.json`), JSON.stringify(ranked[0].config, null, 2));
  }

  if (ranked.some((result) => result.deployableTargetHit)) break;
  frontier = ranked.slice(0, beam).flatMap((result) => mutateConfig(result.name, result.config));
}

const ranked = rankResults(results);
const printable = ranked.slice(0, 25).map(toPrintableResult);
await fs.writeFile(path.join(outDir, "ranked.json"), JSON.stringify(ranked, null, 2));
await fs.writeFile(path.join(outDir, "top.json"), JSON.stringify(printable, null, 2));
if (ranked[0]?.config) {
  await fs.writeFile(path.join(outDir, "best-config.json"), JSON.stringify(ranked[0].config, null, 2));
}
console.log(JSON.stringify(printable, null, 2));

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

function seedVariants(config) {
  const variants = [namedVariant("baseline", withRunDefaults(config))];
  const executionVariants = [];

  for (const dailyStopPct of [1, 2, 3, 5, 10]) {
    for (const maxConsecutiveLosses of [1, 2, 3, 5, 8]) {
      for (const maxTradesPerDay of [3, 5, 8]) {
        for (const maxConcurrentPositions of [3, 5, 8, 10]) {
          executionVariants.push(
            namedVariant(
              `exec_ds${dailyStopPct}_l${maxConsecutiveLosses}_d${maxTradesPerDay}_c${maxConcurrentPositions}`,
              withRunDefaults({
                ...config,
                dailyStopPct,
                maxConsecutiveLosses,
                maxTradesPerDay,
                maxConcurrentPositions,
              }),
            ),
          );
        }
      }
    }
  }

  variants.push(...trendUpMirrorVariants(config));
  variants.push(...wholePortfolioTargetVariants(config));
  variants.push(...wholePortfolioRiskVariants(config));
  variants.push(...wholePortfolioTrendBreakVariants(config));
  variants.push(...executionVariants);
  return variants;
}

function mutateConfig(parentName, config) {
  const variants = [];
  const base = stripRuntimeFields(config);

  for (const dailyStopPct of nearby(base.dailyStopPct, [1, 2, 3, 5, 10])) {
    for (const maxConsecutiveLosses of nearby(base.maxConsecutiveLosses, [1, 2, 3, 5, 8, 13])) {
      variants.push(
        namedVariant(
          `${parentName}_mut_exec_ds${dailyStopPct}_l${maxConsecutiveLosses}`,
          withRunDefaults({ ...base, dailyStopPct, maxConsecutiveLosses }),
        ),
      );
    }
  }

  for (let legIndex = 0; legIndex < base.legs.length; legIndex += 1) {
    const leg = base.legs[legIndex];
    for (const patch of legPatches(leg)) {
      variants.push(namedVariant(`${parentName}_mut_${leg.id}_${patch.name}`, withRunDefaults(withLegPatch(base, legIndex, patch.patch))));
    }
  }

  return variants;
}

function trendUpMirrorVariants(config) {
  const variants = [];
  const trendDownLegs = config.legs.filter((leg) => leg.allowedMarketRegimes?.includes("TREND_DOWN"));
  for (const source of trendDownLegs) {
    const mirrored = {
      ...source,
      id: source.id.replace("trend_down", "trend_up").replace("down", "up"),
      allowedMarketRegimes: ["TREND_UP"],
      relativeVolumeThreshold: Math.max(1.5, (source.relativeVolumeThreshold ?? 3) - 0.5),
      minDirectionalCloseStrength: Math.max(0.55, (source.minDirectionalCloseStrength ?? 0.7) - 0.1),
      targetR: source.targetR ?? 1.0,
    };
    variants.push(namedVariant(`add_${mirrored.id}`, withRunDefaults({ ...config, legs: [...config.legs, mirrored] })));
  }
  return variants;
}

function wholePortfolioTargetVariants(config) {
  const variants = [];
  for (const targetR of [0.6, 0.8, 1.0, 1.2, 1.5]) {
    variants.push(
      namedVariant(
        `all_target_${targetR}`,
        withRunDefaults({
          ...config,
          legs: config.legs.map((leg) => ({
            ...leg,
            targetR,
            maxHoldM1Candles: Math.min((leg.maxHoldM1Candles ?? 30) + 10, 60),
          })),
        }),
      ),
    );
  }
  return variants;
}

function wholePortfolioRiskVariants(config) {
  const variants = [];
  for (const riskFraction of [0.03, 0.035, 0.04, 0.045, 0.05]) {
    variants.push(
      namedVariant(
        `all_risk_${riskFraction}`,
        withRunDefaults({
          ...config,
          legs: config.legs.map((leg) => ({
            ...leg,
            riskFraction,
          })),
        }),
      ),
    );
  }
  return variants;
}

function wholePortfolioTrendBreakVariants(config) {
  const variants = [];
  for (const trendBreakLookbackM1Candles of [3, 5, 8]) {
    for (const runnerTrailActivationR of [0.6, 0.8, 1.0]) {
      variants.push(
        namedVariant(
          `all_trend_break_l${trendBreakLookbackM1Candles}_a${runnerTrailActivationR}`,
          withRunDefaults({
            ...config,
            legs: config.legs.map((leg) =>
              leg.setupMode === "BREAKOUT_CONTINUATION"
                ? {
                    ...leg,
                    exitMode: "TREND_BREAK",
                    runnerTrailActivationR,
                    trendBreakLookbackM1Candles,
                    breakevenTriggerR: leg.breakevenTriggerR ?? null,
                    maxHoldM1Candles: Math.min((leg.maxHoldM1Candles ?? 30) + 90, 180),
                  }
                : leg,
            ),
          }),
        ),
      );
    }
  }
  return variants;
}

function legPatches(leg) {
  return [
    {
      name: "risk_up",
      patch: {
        riskFraction: bounded((leg.riskFraction ?? 0.03) + 0.005, 0.005, 0.05),
      },
    },
    {
      name: "risk_max",
      patch: {
        riskFraction: 0.05,
      },
    },
    {
      name: "looser_volume",
      patch: {
        relativeVolumeThreshold: bounded((leg.relativeVolumeThreshold ?? 3) - 0.5, 1.5, 8),
        volumeZScoreThreshold: Math.max(0, Math.min(leg.volumeZScoreThreshold ?? 0.5, 0.5)),
      },
    },
    {
      name: "looser_shape",
      patch: {
        minBodyRatio: bounded((leg.minBodyRatio ?? 0.45) - 0.1, 0.1, 1),
        minDirectionalCloseStrength: bounded((leg.minDirectionalCloseStrength ?? 0.7) - 0.1, 0.5, 1),
      },
    },
    {
      name: "wider_risk",
      patch: {
        minEntryRiskPct: Math.min(leg.minEntryRiskPct ?? 0.008, 0.004),
        maxEntryRiskPct: Math.max(leg.maxEntryRiskPct ?? 0.015, 0.025),
        maxEstimatedFeeR: Math.max(leg.maxEstimatedFeeR ?? 0.2, 0.35),
      },
    },
    {
      name: "runner",
      patch: {
        exitMode: "RUNNER",
        runnerTrailActivationR: 0.8,
        runnerTrailDistanceR: 0.75,
        maxHoldM1Candles: Math.min((leg.maxHoldM1Candles ?? 30) + 15, 75),
      },
    },
    {
      name: "trend_break_l3",
      patch: {
        exitMode: "TREND_BREAK",
        runnerTrailActivationR: 0.6,
        trendBreakLookbackM1Candles: 3,
        breakevenTriggerR: null,
        maxHoldM1Candles: Math.min((leg.maxHoldM1Candles ?? 30) + 90, 180),
      },
    },
    {
      name: "trend_break_l5",
      patch: {
        exitMode: "TREND_BREAK",
        runnerTrailActivationR: 0.8,
        trendBreakLookbackM1Candles: 5,
        breakevenTriggerR: null,
        maxHoldM1Candles: Math.min((leg.maxHoldM1Candles ?? 30) + 90, 180),
      },
    },
    {
      name: "trend_break_l8",
      patch: {
        exitMode: "TREND_BREAK",
        runnerTrailActivationR: 1.0,
        trendBreakLookbackM1Candles: 8,
        breakevenTriggerR: null,
        maxHoldM1Candles: Math.min((leg.maxHoldM1Candles ?? 30) + 120, 240),
      },
    },
    {
      name: "coverage_expand",
      patch: {
        requireKeyLevelProximity: false,
        avoidRangeMiddle: false,
        keyLevelTolerancePct: Math.max(leg.keyLevelTolerancePct ?? 0.0025, 0.006),
        relativeVolumeThreshold: bounded((leg.relativeVolumeThreshold ?? 3) - 0.5, 1.5, 8),
        minBodyRatio: bounded((leg.minBodyRatio ?? 0.45) - 0.1, 0.1, 1),
        minEntryRiskPct: Math.min(leg.minEntryRiskPct ?? 0.008, 0.004),
        maxEntryRiskPct: Math.max(leg.maxEntryRiskPct ?? 0.015, 0.025),
        maxHoldM1Candles: Math.min((leg.maxHoldM1Candles ?? 30) + 10, 75),
      },
    },
    {
      name: "setup_close",
      patch: {
        entryMode: "SETUP_CLOSE_CONFIRMATION",
        maxHoldM1Candles: Math.min((leg.maxHoldM1Candles ?? 30) + 10, 75),
      },
    },
    {
      name: "target_1_5",
      patch: {
        targetR: 1.5,
        maxHoldM1Candles: Math.min((leg.maxHoldM1Candles ?? 30) + 15, 75),
      },
    },
    {
      name: "target_0_8",
      patch: {
        targetR: 0.8,
      },
    },
  ];
}

async function runComposite(variant, round) {
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
        round,
        error: `HTTP ${response.status}: ${body.slice(0, 500)}`,
      };
    }
    const report = JSON.parse(body);
    const summary = summarize(report);
    return {
      name: variant.name,
      round,
      rawTargetHit: summary.compoundDailyReturnPct >= targetCompoundDailyReturnPct,
      deployableTargetHit: passesDeployableTarget(summary, report),
      score: score(summary, report),
      summary,
      config: variant.config,
    };
  } catch (error) {
    return {
      name: variant.name,
      round,
      error: error instanceof Error ? error.message : String(error),
    };
  }
}

function passesDeployableTarget(summary, report) {
  return (
    summary.compoundDailyReturnPct >= targetCompoundDailyReturnPct &&
    summary.maxDrawdownPct <= maxDeployableDrawdownPct &&
    summary.maxConsecutiveLosses <= maxDeployableConsecutiveLosses &&
    report.walkForwardPerformance.every((period) => period.returnPct > 0)
  );
}

function score(summary, report) {
  const rawTargetBonus = summary.compoundDailyReturnPct >= targetCompoundDailyReturnPct ? 1_000_000 : 0;
  const deployableBonus = passesDeployableTarget(summary, report) ? 2_000_000 : 0;
  const worstWalkForward = Math.min(...report.walkForwardPerformance.map((period) => period.returnPct));
  return (
    rawTargetBonus +
    deployableBonus +
    summary.compoundDailyReturnPct * 500_000 +
    summary.expectancyR * 5_000 +
    (summary.profitFactor ?? 0) * 800 +
    summary.activeDayCoveragePct * 120 +
    worstWalkForward * 150 -
    summary.maxDrawdownPct * 450 -
    Math.max(0, summary.maxConsecutiveLosses - 8) * 2_000
  );
}

function summarize(report) {
  return {
    finalEquity: report.finalEquity,
    netReturnPct: report.netReturnPct,
    compoundDailyReturnPct: report.compoundDailyReturnPct,
    targetCompoundDailyReturnPct,
    maxDrawdownPct: report.maxDrawdownPct,
    tradeCount: report.tradeCount,
    activeDayCoveragePct: report.activeDayCoveragePct,
    winRatePct: report.winRatePct,
    profitFactor: report.profitFactor,
    payoffRatio: report.payoffRatio,
    expectancyR: report.expectancyR,
    maxConsecutiveLosses: report.maxConsecutiveLosses,
    worstWalkForwardReturnPct: Math.min(...report.walkForwardPerformance.map((period) => period.returnPct)),
  };
}

function namedVariant(name, config) {
  return {
    name,
    config: withRunDefaults(config),
  };
}

function withRunDefaults(config) {
  return {
    ...structuredClone(config),
    ...DEFAULT_LIMITS,
    initialEquity,
    tradeLimit: 0,
  };
}

function stripRuntimeFields(config) {
  const next = structuredClone(config);
  delete next.tradeLimit;
  return next;
}

function withLegPatch(config, legIndex, patch) {
  const next = structuredClone(config);
  next.legs[legIndex] = {
    ...next.legs[legIndex],
    ...patch,
  };
  return next;
}

function nextCandidates(variants, limit) {
  const candidates = [];
  for (const variant of variants) {
    const key = JSON.stringify(variant.config);
    if (seen.has(key)) continue;
    seen.add(key);
    candidates.push(variant);
    if (candidates.length >= limit) break;
  }
  return candidates;
}

function rankResults(items) {
  return [...items]
    .filter((item) => !item.error)
    .sort((left, right) => right.score - left.score);
}

function toPrintableResult(result) {
  return {
    name: result.name,
    round: result.round,
    rawTargetHit: result.rawTargetHit,
    deployableTargetHit: result.deployableTargetHit,
    score: Number(result.score.toFixed(5)),
    ...result.summary,
  };
}

function nearby(current, values) {
  const index = values.findIndex((value) => value === current);
  if (index < 0) return values;
  return values.slice(Math.max(0, index - 1), Math.min(values.length, index + 2));
}

function bounded(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

function fmt(value) {
  if (value == null || Number.isNaN(value)) return "n/a";
  return Number(value).toFixed(5);
}
