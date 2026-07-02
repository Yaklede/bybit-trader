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
          best ? `bestMtmMdd=${fmt(best.summary.markToMarketMaxDrawdownPct)}` : "",
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
  variants.push(...wholePortfolioDrawdownThrottleVariants(config));
  variants.push(...wholePortfolioTrendBreakVariants(config));
  variants.push(...additiveCoverageVariants(config));
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

  variants.push(
    ...wholePortfolioDrawdownThrottleVariants(base).map((variant) => namedVariant(`${parentName}_mut_${variant.name}`, variant.config)),
  );

  for (let legIndex = 0; legIndex < base.legs.length; legIndex += 1) {
    const leg = base.legs[legIndex];
    for (const patch of legPatches(leg)) {
      variants.push(namedVariant(`${parentName}_mut_${leg.id}_${patch.name}`, withRunDefaults(withLegPatch(base, legIndex, patch.patch))));
    }
  }

  variants.push(...additiveCoverageVariants(base).map((variant) => namedVariant(`${parentName}_mut_${variant.name}`, variant.config)));

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
  for (const riskFraction of [0.07, 0.075, 0.08, 0.085, 0.09, 0.095, 0.1, 0.11, 0.12, 0.13, 0.14, 0.15]) {
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

function wholePortfolioDrawdownThrottleVariants(config) {
  const variants = [];
  const throttleThresholds = [25, 28, 30, 31, 32, 35];
  const riskMultipliers = [0.2, 0.25, 0.3, 0.35, 0.5, 0.75];
  const cooldownDays = [1, 2, 3];
  const combinedRiskMultipliers = [0.2, 0.25, 0.3, 0.35];
  const combinedCooldownDays = [1, 2];

  for (const portfolioDrawdownThrottlePct of throttleThresholds) {
    for (const portfolioDrawdownRiskMultiplier of riskMultipliers) {
      variants.push(
        namedVariant(
          `dd_throttle_${portfolioDrawdownThrottlePct}_m${portfolioDrawdownRiskMultiplier}`,
          withRunDefaults({
            ...config,
            portfolioDrawdownThrottlePct,
            portfolioDrawdownRiskMultiplier,
            portfolioDrawdownCooldownDays: 0,
          }),
        ),
      );
    }
    for (const portfolioDrawdownCooldownDays of cooldownDays) {
      variants.push(
        namedVariant(
          `dd_cooldown_${portfolioDrawdownThrottlePct}_d${portfolioDrawdownCooldownDays}`,
          withRunDefaults({
            ...config,
            portfolioDrawdownThrottlePct,
            portfolioDrawdownRiskMultiplier: 1.0,
            portfolioDrawdownCooldownDays,
          }),
        ),
      );
    }
    for (const portfolioDrawdownRiskMultiplier of combinedRiskMultipliers) {
      for (const portfolioDrawdownCooldownDays of combinedCooldownDays) {
        variants.push(
          namedVariant(
            `dd_combo_${portfolioDrawdownThrottlePct}_m${portfolioDrawdownRiskMultiplier}_d${portfolioDrawdownCooldownDays}`,
            withRunDefaults({
              ...config,
              portfolioDrawdownThrottlePct,
              portfolioDrawdownRiskMultiplier,
              portfolioDrawdownCooldownDays,
            }),
          ),
        );
      }
    }
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

function additiveCoverageVariants(config) {
  const templates = coverageLegTemplates();
  const variants = [];
  const available = templates.filter((template) => !hasLegId(config, template.id));

  for (const template of available) {
    variants.push(namedVariant(`add_${template.id}`, withRunDefaults(withAddedLegs(config, [template]))));
  }

  for (let leftIndex = 0; leftIndex < available.length; leftIndex += 1) {
    for (let rightIndex = leftIndex + 1; rightIndex < available.length; rightIndex += 1) {
      const pair = [available[leftIndex], available[rightIndex]];
      variants.push(namedVariant(`add_${pair.map((leg) => leg.id).join("_plus_")}`, withRunDefaults(withAddedLegs(config, pair))));
    }
  }

  return variants;
}

function coverageLegTemplates() {
  return [
    coverageLeg({
      id: "m1_range_failed_break_trend_break",
      setupMode: "FAILED_BREAK_REVERSAL",
      allowedMarketRegimes: ["RANGE"],
      relativeVolumeThreshold: 1.8,
      setupRangeLookback: 6,
      requireKeyLevelProximity: true,
      minBodyRatio: 0.15,
      minDirectionalCloseStrength: 0.55,
      minRejectionWickRatio: 0.15,
      targetR: 0.8,
      exitMode: "TREND_BREAK",
      runnerTrailActivationR: 0.8,
      trendBreakLookbackM1Candles: 8,
      maxHoldM1Candles: 140,
    }),
    coverageLeg({
      id: "m1_range_failed_break_runner",
      setupMode: "FAILED_BREAK_REVERSAL",
      allowedMarketRegimes: ["RANGE"],
      relativeVolumeThreshold: 1.8,
      setupRangeLookback: 6,
      requireKeyLevelProximity: false,
      minBodyRatio: 0.15,
      minDirectionalCloseStrength: 0.55,
      minRejectionWickRatio: 0.15,
      targetR: 1.0,
      exitMode: "RUNNER",
      runnerTrailActivationR: 0.8,
      runnerTrailDistanceR: 0.75,
      maxHoldM1Candles: 90,
    }),
    coverageLeg({
      id: "m1_chop_volume_rejection_trend_break",
      setupMode: "VOLUME_REJECTION_REVERSAL",
      allowedMarketRegimes: ["HIGH_VOLATILITY_CHOP"],
      relativeVolumeThreshold: 1.8,
      setupRangeLookback: 6,
      requireKeyLevelProximity: true,
      minBodyRatio: 0.1,
      minDirectionalCloseStrength: 0.55,
      minRejectionWickRatio: 0.2,
      targetR: 0.8,
      exitMode: "TREND_BREAK",
      runnerTrailActivationR: 0.8,
      trendBreakLookbackM1Candles: 8,
      maxHoldM1Candles: 140,
    }),
    coverageLeg({
      id: "m1_range_volume_rejection_trend_break",
      setupMode: "VOLUME_REJECTION_REVERSAL",
      allowedMarketRegimes: ["RANGE"],
      relativeVolumeThreshold: 1.8,
      setupRangeLookback: 6,
      requireKeyLevelProximity: true,
      minBodyRatio: 0.1,
      minDirectionalCloseStrength: 0.55,
      minRejectionWickRatio: 0.2,
      targetR: 0.8,
      exitMode: "TREND_BREAK",
      runnerTrailActivationR: 0.8,
      trendBreakLookbackM1Candles: 8,
      maxHoldM1Candles: 140,
    }),
    coverageLeg({
      id: "m1_trend_up_breakout_loose_runner",
      setupMode: "BREAKOUT_CONTINUATION",
      allowedMarketRegimes: ["TREND_UP"],
      requireContextVwap: true,
      requireContextTrend: true,
      requireRegimeSideAlignment: true,
      relativeVolumeThreshold: 1.6,
      setupRangeLookback: 6,
      requireKeyLevelProximity: false,
      minBodyRatio: 0.4,
      minDirectionalCloseStrength: 0.6,
      targetR: 0.8,
      exitMode: "RUNNER",
      runnerTrailActivationR: 0.8,
      runnerTrailDistanceR: 0.75,
      maxHoldM1Candles: 90,
    }),
    coverageLeg({
      id: "m1_trend_down_breakout_loose_runner",
      setupMode: "BREAKOUT_CONTINUATION",
      allowedMarketRegimes: ["TREND_DOWN"],
      requireContextVwap: true,
      requireContextTrend: true,
      requireRegimeSideAlignment: true,
      relativeVolumeThreshold: 1.8,
      setupRangeLookback: 6,
      requireKeyLevelProximity: false,
      minBodyRatio: 0.4,
      minDirectionalCloseStrength: 0.6,
      targetR: 0.8,
      exitMode: "RUNNER",
      runnerTrailActivationR: 0.8,
      runnerTrailDistanceR: 0.75,
      maxHoldM1Candles: 90,
    }),
    coverageLeg({
      id: "m5_trend_up_breakout_runner",
      setupMode: "BREAKOUT_CONTINUATION",
      setupTimeframe: "M5",
      entryMode: "CLOSE_CONFIRMATION",
      allowedMarketRegimes: ["TREND_UP"],
      requireContextVwap: true,
      requireContextTrend: true,
      requireRegimeSideAlignment: true,
      relativeVolumeThreshold: 2.5,
      setupRangeLookback: 8,
      requireKeyLevelProximity: true,
      minBodyRatio: 0.45,
      minDirectionalCloseStrength: 0.65,
      targetR: 1.2,
      exitMode: "RUNNER",
      runnerTrailActivationR: 0.8,
      runnerTrailDistanceR: 0.75,
      maxHoldM1Candles: 90,
    }),
    coverageLeg({
      id: "m5_chop_failed_break_trend_break",
      setupMode: "FAILED_BREAK_REVERSAL",
      setupTimeframe: "M5",
      entryMode: "CLOSE_CONFIRMATION",
      allowedMarketRegimes: ["HIGH_VOLATILITY_CHOP"],
      relativeVolumeThreshold: 2.0,
      setupRangeLookback: 8,
      requireKeyLevelProximity: true,
      minBodyRatio: 0.2,
      minDirectionalCloseStrength: 0.55,
      minRejectionWickRatio: 0.15,
      targetR: 1.0,
      exitMode: "TREND_BREAK",
      runnerTrailActivationR: 0.8,
      trendBreakLookbackM1Candles: 8,
      maxHoldM1Candles: 180,
    }),
  ];
}

function coverageLeg(overrides) {
  return {
    id: overrides.id,
    riskFraction: overrides.riskFraction ?? 0.05,
    setupMode: overrides.setupMode,
    entryMode: overrides.entryMode ?? "CLOSE_CONFIRMATION",
    sideMode: "BOTH",
    setupTimeframe: overrides.setupTimeframe ?? "M1",
    relativeVolumeThreshold: overrides.relativeVolumeThreshold,
    volumeZScoreThreshold: overrides.volumeZScoreThreshold ?? 0.5,
    setupRangeLookback: overrides.setupRangeLookback,
    requireM5Vwap: overrides.requireM5Vwap ?? false,
    requireContextVwap: overrides.requireContextVwap ?? false,
    requireContextTrend: overrides.requireContextTrend ?? false,
    allowedMarketRegimes: overrides.allowedMarketRegimes,
    requireRegimeSideAlignment: overrides.requireRegimeSideAlignment ?? false,
    requireKeyLevelProximity: overrides.requireKeyLevelProximity ?? false,
    keyLevelTolerancePct: overrides.keyLevelTolerancePct ?? 0.0025,
    avoidRangeMiddle: overrides.avoidRangeMiddle ?? true,
    minBodyRatio: overrides.minBodyRatio,
    minDirectionalCloseStrength: overrides.minDirectionalCloseStrength,
    minRejectionWickRatio: overrides.minRejectionWickRatio ?? 0.2,
    entryLookaheadM1Candles: overrides.entryLookaheadM1Candles ?? 5,
    minEntryRiskPct: overrides.minEntryRiskPct ?? 0.008,
    maxEntryRiskPct: overrides.maxEntryRiskPct ?? 0.015,
    maxEstimatedFeeR: overrides.maxEstimatedFeeR ?? 0.2,
    targetR: overrides.targetR,
    exitMode: overrides.exitMode,
    runnerTrailActivationR: overrides.runnerTrailActivationR ?? 1.0,
    runnerTrailDistanceR: overrides.runnerTrailDistanceR ?? 0.5,
    breakevenTriggerR: null,
    maxHoldM1Candles: overrides.maxHoldM1Candles,
    trendBreakLookbackM1Candles: overrides.trendBreakLookbackM1Candles ?? 5,
  };
}

function legPatches(leg) {
  return [
    {
      name: "risk_up",
      patch: {
        riskFraction: bounded((leg.riskFraction ?? 0.03) + 0.005, 0.005, 0.15),
      },
    },
    {
      name: "risk_max",
      patch: {
        riskFraction: 0.15,
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
      name: "follow_through_3_0_25",
      patch: {
        followThroughCheckM1Candles: 3,
        minFollowThroughR: 0.25,
        maxHoldM1Candles: Math.max(leg.maxHoldM1Candles ?? 30, 3),
      },
    },
    {
      name: "follow_through_5_0_35",
      patch: {
        followThroughCheckM1Candles: 5,
        minFollowThroughR: 0.35,
        maxHoldM1Candles: Math.max(leg.maxHoldM1Candles ?? 30, 5),
      },
    },
    {
      name: "follow_through_8_0_45",
      patch: {
        followThroughCheckM1Candles: 8,
        minFollowThroughR: 0.45,
        maxHoldM1Candles: Math.max(leg.maxHoldM1Candles ?? 30, 8),
      },
    },
    {
      name: "adverse_8_0_7_0_35",
      patch: {
        adverseExitCheckM1Candles: 8,
        maxAdverseRBeforeExit: 0.7,
        minFavorableRBeforeAdverseExit: 0.35,
        maxHoldM1Candles: Math.max(leg.maxHoldM1Candles ?? 30, 8),
      },
    },
    {
      name: "adverse_12_0_7_0_35",
      patch: {
        adverseExitCheckM1Candles: 12,
        maxAdverseRBeforeExit: 0.7,
        minFavorableRBeforeAdverseExit: 0.35,
        maxHoldM1Candles: Math.max(leg.maxHoldM1Candles ?? 30, 12),
      },
    },
    {
      name: "adverse_off",
      patch: {
        adverseExitCheckM1Candles: null,
        maxAdverseRBeforeExit: null,
        minFavorableRBeforeAdverseExit: null,
      },
    },
    {
      name: "profit_protect_0_5_0_1",
      patch: {
        profitProtectActivationR: 0.5,
        profitProtectFloorR: 0.1,
      },
    },
    {
      name: "profit_protect_0_7_0_2",
      patch: {
        profitProtectActivationR: 0.7,
        profitProtectFloorR: 0.2,
      },
    },
    {
      name: "profit_protect_off",
      patch: {
        profitProtectActivationR: null,
        profitProtectFloorR: null,
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
  const deployableDrawdownPct = deploymentDrawdownPct(summary);
  return (
    summary.compoundDailyReturnPct >= targetCompoundDailyReturnPct &&
    deployableDrawdownPct <= maxDeployableDrawdownPct &&
    summary.maxConsecutiveLosses <= maxDeployableConsecutiveLosses &&
    report.walkForwardPerformance.every((period) => period.returnPct > 0)
  );
}

function score(summary, report) {
  const rawTargetBonus = summary.compoundDailyReturnPct >= targetCompoundDailyReturnPct ? 1_000_000 : 0;
  const deployableBonus = passesDeployableTarget(summary, report) ? 2_000_000 : 0;
  const worstWalkForward = Math.min(...report.walkForwardPerformance.map((period) => period.returnPct));
  const deployableDrawdownPct = deploymentDrawdownPct(summary);
  const drawdownOverGate = Math.max(0, deployableDrawdownPct - maxDeployableDrawdownPct);
  return (
    rawTargetBonus +
    deployableBonus +
    summary.compoundDailyReturnPct * 500_000 +
    summary.expectancyR * 5_000 +
    (summary.profitFactor ?? 0) * 800 +
    summary.activeDayCoveragePct * 120 +
    worstWalkForward * 150 -
    deployableDrawdownPct * 450 -
    drawdownOverGate * 10_000 -
    exitRiskPenalty(report, 800, 120, 300, 80) -
    Math.max(0, summary.maxConsecutiveLosses - 8) * 2_000
  );
}

function exitRiskPenalty(report, fullRiskStopWeight, breakevenStopWeight, adverseInvalidationWeight, profitProtectWeight) {
  const fullRiskStop = exitSummary(report, "STOP");
  const breakevenStop = exitSummary(report, "BREAKEVEN_STOP");
  const adverseInvalidation = exitSummary(report, "ADVERSE_INVALIDATION");
  const profitProtect = exitSummary(report, "PROFIT_PROTECT");
  return (
    exitLossR(fullRiskStop) * fullRiskStopWeight +
    exitLossR(breakevenStop) * breakevenStopWeight +
    exitLossR(adverseInvalidation) * adverseInvalidationWeight +
    exitLossR(profitProtect) * profitProtectWeight
  );
}

function exitLossR(summary) {
  return Math.max(0, summary.tradeCount ?? 0) * Math.max(0, -(summary.expectancyR ?? 0));
}

function exitSummary(report, tag) {
  return report.performanceByExitReason?.find((summary) => summary.tag === tag) ?? { tradeCount: 0, expectancyR: 0 };
}

function deploymentDrawdownPct(summary) {
  return Math.max(summary.maxDrawdownPct ?? 0, summary.markToMarketMaxDrawdownPct ?? summary.maxDrawdownPct ?? 0);
}

function summarize(report) {
  return {
    finalEquity: report.finalEquity,
    netReturnPct: report.netReturnPct,
    compoundDailyReturnPct: report.compoundDailyReturnPct,
    targetCompoundDailyReturnPct,
    maxDrawdownPct: report.maxDrawdownPct,
    markToMarketMaxDrawdownPct: report.markToMarketMaxDrawdownPct,
    averageMaxFavorableExcursionR: report.averageMaxFavorableExcursionR,
    averageMaxAdverseExcursionR: report.averageMaxAdverseExcursionR,
    averageMfeCapturePct: report.averageMfeCapturePct,
    tradeCount: report.tradeCount,
    activeDayCoveragePct: report.activeDayCoveragePct,
    winRatePct: report.winRatePct,
    profitFactor: report.profitFactor,
    payoffRatio: report.payoffRatio,
    expectancyR: report.expectancyR,
    maxConsecutiveLosses: report.maxConsecutiveLosses,
    fullRiskStopTradeCount: exitSummary(report, "STOP").tradeCount ?? 0,
    fullRiskStopExpectancyR: exitSummary(report, "STOP").expectancyR ?? 0,
    breakevenStopTradeCount: exitSummary(report, "BREAKEVEN_STOP").tradeCount ?? 0,
    breakevenStopExpectancyR: exitSummary(report, "BREAKEVEN_STOP").expectancyR ?? 0,
    adverseInvalidationTradeCount: exitSummary(report, "ADVERSE_INVALIDATION").tradeCount ?? 0,
    adverseInvalidationExpectancyR: exitSummary(report, "ADVERSE_INVALIDATION").expectancyR ?? 0,
    profitProtectTradeCount: exitSummary(report, "PROFIT_PROTECT").tradeCount ?? 0,
    profitProtectExpectancyR: exitSummary(report, "PROFIT_PROTECT").expectancyR ?? 0,
    portfolioDrawdownCooldownSkipCount: report.noTradeReasonCounts?.PORTFOLIO_DRAWDOWN_COOLDOWN ?? 0,
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

function withAddedLegs(config, legs) {
  const next = structuredClone(config);
  const existingIds = new Set(next.legs.map((leg) => leg.id));
  const additions = legs.filter((leg) => !existingIds.has(leg.id));
  next.legs = [...next.legs, ...additions].slice(0, 10);
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

function hasLegId(config, id) {
  return config.legs.some((leg) => leg.id === id);
}

function fmt(value) {
  if (value == null || Number.isNaN(value)) return "n/a";
  return Number(value).toFixed(5);
}
