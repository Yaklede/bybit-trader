#!/usr/bin/env node

import fs from "node:fs/promises";
import path from "node:path";

const DEFAULT_LIMITS = {
  m1Limit: 1_578_240,
  m5Limit: 315_648,
  m15Limit: 105_216,
};

const FULL_REPLAY_LIMITS = {
  m1Limit: 6_000_000,
  m5Limit: 1_200_000,
  m15Limit: 400_000,
};

const DEFAULT_SEGMENTS = [
  {
    id: "S1",
    role: "stress-train",
    replayStartAt: "2020-03-25T10:36:00Z",
    replayEndAt: "2021-10-18T23:59:59Z",
  },
  {
    id: "S2",
    role: "stress-validation",
    replayStartAt: "2021-10-19T00:00:00Z",
    replayEndAt: "2023-05-28T23:59:59Z",
  },
  {
    id: "S3",
    role: "validation",
    replayStartAt: "2023-05-29T00:00:00Z",
    replayEndAt: "2024-12-31T23:59:59Z",
  },
  {
    id: "S4",
    role: "validation",
    replayStartAt: "2025-01-01T00:00:00Z",
    replayEndAt: "2026-07-02T05:40:00Z",
  },
  {
    id: "FULL",
    role: "final-gate",
    replayStartAt: "2020-03-25T10:36:00Z",
    replayEndAt: "2026-07-02T05:40:00Z",
  },
];

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
const segmented = args.segmented === "true";
const segments = args.segments == null ? DEFAULT_SEGMENTS : JSON.parse(await fs.readFile(args.segments, "utf8"));
const defaultReplayLimits = segmented ? FULL_REPLAY_LIMITS : DEFAULT_LIMITS;
const minSegmentReturnPct = Number(args.minSegmentReturnPct ?? 0);
const minSegmentExpectancyR = Number(args.minSegmentExpectancyR ?? 0);
const minSegmentProfitFactor = Number(args.minSegmentProfitFactor ?? 1);
const minSegmentTrades = Number(args.minSegmentTrades ?? 1);
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
    `segmented=${segmented}`,
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

  variants.push(...stressDefenseVariants(config));
  variants.push(...trendUpMirrorVariants(config));
  variants.push(...wholePortfolioTargetVariants(config));
  variants.push(...wholePortfolioRiskVariants(config));
  variants.push(...wholePortfolioLegRiskCapVariants(config));
  variants.push(...trendDownAssistRiskCapVariants(config));
  variants.push(...wholePortfolioDrawdownThrottleVariants(config));
  variants.push(...wholePortfolioMonthlyStopVariants(config));
  variants.push(...wholePortfolioTrendBreakVariants(config));
  variants.push(...additiveCoverageVariants(config));
  variants.push(...executionVariants);
  return variants;
}

function mutateConfig(parentName, config) {
  const variants = [];
  const base = stripRuntimeFields(config);

  variants.push(
    ...stressDefenseVariants(base).map((variant) => namedVariant(`${parentName}_mut_${variant.name}`, variant.config)),
  );

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
  variants.push(
    ...wholePortfolioMonthlyStopVariants(base).map((variant) => namedVariant(`${parentName}_mut_${variant.name}`, variant.config)),
  );
  variants.push(
    ...wholePortfolioLegRiskCapVariants(base).map((variant) => namedVariant(`${parentName}_mut_${variant.name}`, variant.config)),
  );
  variants.push(
    ...trendDownAssistRiskCapVariants(base).map((variant) => namedVariant(`${parentName}_mut_${variant.name}`, variant.config)),
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

function stressDefenseVariants(config) {
  const variants = [];
  variants.push(...volumeQualityStressVariants(config));
  const removalSpecs = [
    {
      name: "drop_range",
      shouldKeep: (leg) => !leg.allowedMarketRegimes?.includes("RANGE"),
    },
    {
      name: "drop_chop",
      shouldKeep: (leg) => !leg.allowedMarketRegimes?.includes("HIGH_VOLATILITY_CHOP"),
    },
    {
      name: "drop_range_chop",
      shouldKeep: (leg) =>
        !leg.allowedMarketRegimes?.some((regime) => regime === "RANGE" || regime === "HIGH_VOLATILITY_CHOP"),
    },
    {
      name: "drop_trend_up",
      shouldKeep: (leg) => !leg.allowedMarketRegimes?.includes("TREND_UP"),
    },
    {
      name: "trend_down_only",
      shouldKeep: (leg) => leg.allowedMarketRegimes?.includes("TREND_DOWN") === true,
    },
    {
      name: "m1_down_assist_only",
      shouldKeep: (leg) => leg.id === "m1_trend_down_breakout_assist",
    },
    {
      name: "drop_m1_scalps",
      shouldKeep: (leg) =>
        leg.id !== "m1_trend_up_breakout_scalp" &&
        leg.id !== "m1_trend_down_breakout_assist" &&
        leg.id !== "m1_failed_break_chop_scalp",
    },
    {
      name: "drop_s1_loss_cluster",
      shouldKeep: (leg) =>
        leg.id !== "range_failed_break_loose" &&
        leg.id !== "m1_failed_break_chop_scalp" &&
        leg.id !== "trend_down_close",
    },
  ];

  for (const spec of removalSpecs) {
    const pruned = withFilteredLegs(config, spec.shouldKeep);
    if (pruned == null) continue;
    variants.push(namedVariant(spec.name, withRunDefaults(pruned)));

    for (const riskFraction of [0.03, 0.05, 0.07, 0.09]) {
      variants.push(namedVariant(`${spec.name}_risk_${riskFraction}`, withRunDefaults(withLegRisk(pruned, riskFraction))));
    }
  }

  for (const riskFraction of [0.025, 0.03, 0.05, 0.07, 0.09, 0.11]) {
    variants.push(namedVariant(`all_risk_defense_${riskFraction}`, withRunDefaults(withLegRisk(config, riskFraction))));
  }

  for (const stressRiskFraction of [0.015, 0.02, 0.03, 0.05]) {
    variants.push(
      namedVariant(
        `stress_regime_cap_${stressRiskFraction}`,
        withRunDefaults({
          ...config,
          legs: config.legs.map((leg) =>
            isStressRegimeLeg(leg)
              ? {
                  ...leg,
                  riskFraction: stressRiskFraction,
                  adverseExitCheckM1Candles: leg.adverseExitCheckM1Candles ?? 8,
                  maxAdverseRBeforeExit: Math.min(leg.maxAdverseRBeforeExit ?? 0.8, 0.7),
                  minFavorableRBeforeAdverseExit: leg.minFavorableRBeforeAdverseExit ?? 0.25,
                }
              : leg,
          ),
        }),
      ),
    );
  }

  for (const extraVolume of [0.5, 1.0]) {
    variants.push(
      namedVariant(
        `stricter_signal_v${extraVolume}`,
        withRunDefaults({
          ...config,
          legs: config.legs.map((leg) => ({
            ...leg,
            relativeVolumeThreshold: bounded((leg.relativeVolumeThreshold ?? 2) + extraVolume, 1.5, 8),
            volumeZScoreThreshold: bounded((leg.volumeZScoreThreshold ?? 0.5) + 0.25, 0, 5),
            minDirectionalCloseStrength: bounded((leg.minDirectionalCloseStrength ?? 0.6) + 0.05, 0.5, 1),
          })),
        }),
      ),
    );
  }

  return variants;
}

function volumeQualityStressVariants(config) {
  const variants = [];

  for (const maxRelativeVolumeThreshold of [6, 8, 10, 12, 15]) {
    variants.push(
      namedVariant(
        `all_volume_cap_${maxRelativeVolumeThreshold}`,
        withRunDefaults(withVolumeCap(config, () => true, maxRelativeVolumeThreshold)),
      ),
    );
  }

  for (const maxRelativeVolumeThreshold of [6, 8, 10]) {
    variants.push(
      namedVariant(
        `trend_up_volume_cap_${maxRelativeVolumeThreshold}`,
        withRunDefaults(
          withVolumeCap(
            config,
            (leg) => leg.allowedMarketRegimes?.includes("TREND_UP") === true,
            maxRelativeVolumeThreshold,
          ),
        ),
      ),
    );
  }

  for (const maxRelativeVolumeThreshold of [4, 6, 8]) {
    variants.push(
      namedVariant(
        `range_chop_volume_cap_${maxRelativeVolumeThreshold}`,
        withRunDefaults(
          withVolumeCap(
            config,
            (leg) =>
              leg.allowedMarketRegimes?.some(
                (regime) => regime === "RANGE" || regime === "HIGH_VOLATILITY_CHOP",
              ) === true,
            maxRelativeVolumeThreshold,
          ),
        ),
      ),
    );
  }

  for (const minTrendEfficiency of [0.45, 0.55, 0.65]) {
    for (const minTrendMovePct of [0.005, 0.008]) {
      variants.push(
        namedVariant(
          `trend_down_quality_eff${minTrendEfficiency}_move${minTrendMovePct}`,
          withRunDefaults({
            ...config,
            legs: config.legs.map((leg) =>
              leg.allowedMarketRegimes?.includes("TREND_DOWN") === true
                ? {
                    ...leg,
                    minTrendEfficiency,
                    minTrendMovePct,
                    maxRelativeVolumeThreshold: Math.max((leg.relativeVolumeThreshold ?? 1.0) + 1.0, 12),
                  }
                : leg,
            ),
          }),
        ),
      );
    }
  }

  for (const maxContextRangePct of [0.006, 0.008, 0.01, 0.012, 0.015, 0.02]) {
    variants.push(
      namedVariant(
        `all_context_range_cap_${maxContextRangePct}`,
        withRunDefaults(withContextRangeCap(config, () => true, maxContextRangePct)),
      ),
    );
  }

  for (const maxContextRangePct of [0.008, 0.01, 0.012, 0.015]) {
    variants.push(
      namedVariant(
        `trend_context_range_cap_${maxContextRangePct}`,
        withRunDefaults(
          withContextRangeCap(
            config,
            (leg) =>
              leg.allowedMarketRegimes?.some((regime) => regime === "TREND_UP" || regime === "TREND_DOWN") ===
              true,
            maxContextRangePct,
          ),
        ),
      ),
    );
  }

  for (const maxContextRangePct of [0.008, 0.01, 0.012]) {
    variants.push(
      namedVariant(
        `range_chop_cap8_context_range_cap_${maxContextRangePct}`,
        withRunDefaults(
          withContextRangeCap(
            withVolumeCap(
              config,
              (leg) =>
                leg.allowedMarketRegimes?.some(
                  (regime) => regime === "RANGE" || regime === "HIGH_VOLATILITY_CHOP",
                ) === true,
              8,
            ),
            () => true,
            maxContextRangePct,
          ),
        ),
      ),
    );
  }

  for (const minContextQuoteVolume of [20_000_000, 40_000_000, 60_000_000, 80_000_000]) {
    variants.push(
      namedVariant(
        `all_context_quote_floor_${minContextQuoteVolume}`,
        withRunDefaults(withContextQuoteVolumeFloor(config, () => true, minContextQuoteVolume)),
      ),
    );
  }

  for (const minContextQuoteVolume of [40_000_000, 60_000_000, 80_000_000]) {
    variants.push(
      namedVariant(
        `trend_down_context_quote_floor_${minContextQuoteVolume}`,
        withRunDefaults(
          withContextQuoteVolumeFloor(
            config,
            (leg) => leg.allowedMarketRegimes?.some((regime) => regime === "TREND_DOWN") === true,
            minContextQuoteVolume,
          ),
        ),
      ),
    );
  }

  variants.push(
    namedVariant(
      "m1_chop_rv_3_6",
      withRunDefaults(
        withLegIdPatch(config, "m1_failed_break_chop_scalp", {
          relativeVolumeThreshold: 3,
          maxRelativeVolumeThreshold: 6,
        }),
      ),
    ),
  );

  variants.push(
    namedVariant(
      "m1_chop_rv_3_8",
      withRunDefaults(
        withLegIdPatch(config, "m1_failed_break_chop_scalp", {
          relativeVolumeThreshold: 3,
          maxRelativeVolumeThreshold: 8,
        }),
      ),
    ),
  );

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

function wholePortfolioLegRiskCapVariants(config) {
  const variants = [];
  for (const baseRiskFraction of [0.142, 0.143, 0.144, 0.1445, 0.1446, 0.145]) {
    for (const cappedRiskFraction of [0.10, 0.11, 0.12, 0.125]) {
      for (const portfolioDrawdownThrottlePct of [31, 32]) {
        for (const portfolioDrawdownRiskMultiplier of [0.2, 0.25, 0.3]) {
          variants.push(
            namedVariant(
              `up_risk_cap_base${baseRiskFraction}_up${cappedRiskFraction}_dd${portfolioDrawdownThrottlePct}_m${portfolioDrawdownRiskMultiplier}`,
              withRunDefaults({
                ...config,
                portfolioDrawdownThrottlePct,
                portfolioDrawdownRiskMultiplier,
                portfolioDrawdownCooldownDays: 1,
                legs: config.legs.map((leg) => ({
                  ...leg,
                  riskFraction: leg.id === "m1_trend_up_breakout_scalp" ? cappedRiskFraction : baseRiskFraction,
                })),
              }),
            ),
          );
        }
      }
    }
  }
  return variants;
}

function trendDownAssistRiskCapVariants(config) {
  const variants = [];
  for (const baseRiskFraction of [0.146, 0.147, 0.148]) {
    for (const trendDownAssistRiskFraction of [0.13, 0.136, 0.14, 0.142]) {
      for (const useAdverseInvalidation of [false, true]) {
        variants.push(
          namedVariant(
            `down_assist_cap_base${baseRiskFraction}_down${trendDownAssistRiskFraction}${useAdverseInvalidation ? "_adv" : ""}`,
            withRunDefaults({
              ...config,
              portfolioDrawdownThrottlePct: 32,
              portfolioDrawdownRiskMultiplier: 0.2,
              portfolioDrawdownCooldownDays: 1,
              legs: config.legs.map((leg) => ({
                ...leg,
                riskFraction:
                  leg.id === "m1_trend_up_breakout_scalp"
                    ? 0.12
                    : leg.id === "m1_trend_down_breakout_assist"
                      ? trendDownAssistRiskFraction
                      : baseRiskFraction,
                ...(leg.id === "m1_trend_down_breakout_assist" && useAdverseInvalidation
                  ? {
                      adverseExitCheckM1Candles: 5,
                      maxAdverseRBeforeExit: 0.9,
                      minFavorableRBeforeAdverseExit: 0.35,
                    }
                  : {}),
              })),
            }),
          ),
        );
      }
    }
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

function wholePortfolioMonthlyStopVariants(config) {
  const variants = [];
  for (const monthlyStopPct of [5, 8, 10, 12, 15, 20, 30]) {
    variants.push(
      namedVariant(
        `monthly_stop_${monthlyStopPct}`,
        withRunDefaults({
          ...config,
          monthlyStopPct,
        }),
      ),
    );
  }

  for (const monthlyStopPct of [8, 10, 12, 15]) {
    for (const portfolioDrawdownThrottlePct of [30, 32, 35]) {
      variants.push(
        namedVariant(
          `monthly_stop_${monthlyStopPct}_dd${portfolioDrawdownThrottlePct}`,
          withRunDefaults({
            ...config,
            monthlyStopPct,
            portfolioDrawdownThrottlePct,
            portfolioDrawdownRiskMultiplier: 0.2,
            portfolioDrawdownCooldownDays: 1,
          }),
        ),
      );
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
      maxRelativeVolumeThreshold: overrides.maxRelativeVolumeThreshold ?? null,
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
        maxRelativeVolumeThreshold: null,
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
        maxRelativeVolumeThreshold: null,
        minBodyRatio: bounded((leg.minBodyRatio ?? 0.45) - 0.1, 0.1, 1),
        minEntryRiskPct: Math.min(leg.minEntryRiskPct ?? 0.008, 0.004),
        maxEntryRiskPct: Math.max(leg.maxEntryRiskPct ?? 0.015, 0.025),
        maxHoldM1Candles: Math.min((leg.maxHoldM1Candles ?? 30) + 10, 75),
      },
    },
    {
      name: "volume_cap_8",
      patch: {
        maxRelativeVolumeThreshold: Math.max((leg.relativeVolumeThreshold ?? 3) + 1.0, 8),
      },
    },
    {
      name: "volume_cap_12",
      patch: {
        maxRelativeVolumeThreshold: Math.max((leg.relativeVolumeThreshold ?? 3) + 1.0, 12),
      },
    },
    {
      name: "volume_cap_off",
      patch: {
        maxRelativeVolumeThreshold: null,
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
  if (segmented) {
    return runSegmentedComposite(variant, round);
  }

  try {
    const report = await requestComposite(variant.config);
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

async function runSegmentedComposite(variant, round) {
  try {
    const segmentResults = [];
    for (const segment of segments) {
      const report = await requestComposite({
        ...variant.config,
        replayStartAt: segment.replayStartAt,
        replayEndAt: segment.replayEndAt,
        tradeLimit: 0,
        equityCurveLimit: 0,
        drawdownEventLimit: 20,
      });
      const summary = summarize(report);
      segmentResults.push({
        id: segment.id,
        role: segment.role,
        replayStartAt: segment.replayStartAt,
        replayEndAt: segment.replayEndAt,
        passed: segmentRejectionReasons(summary).length === 0,
        rejectionReasons: segmentRejectionReasons(summary),
        summary,
      });
    }

    const fullSegment = segmentResults.find((segment) => segment.id === "FULL") ?? segmentResults.at(-1);
    const summary = {
      ...fullSegment.summary,
      worstSegmentReturnPct: Math.min(...segmentResults.map((segment) => segment.summary.netReturnPct)),
      worstSegmentCompoundDailyReturnPct: Math.min(
        ...segmentResults.map((segment) => segment.summary.compoundDailyReturnPct),
      ),
      worstSegmentMarkToMarketMaxDrawdownPct: Math.max(
        ...segmentResults.map((segment) => segment.summary.markToMarketMaxDrawdownPct),
      ),
      failingSegments: segmentResults.filter((segment) => !segment.passed).map((segment) => segment.id),
    };
    return {
      name: variant.name,
      round,
      rawTargetHit: summary.compoundDailyReturnPct >= targetCompoundDailyReturnPct,
      deployableTargetHit: passesSegmentedDeployableTarget(summary, segmentResults),
      score: segmentedScore(summary, segmentResults),
      summary,
      segmentResults,
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

function passesDeployableTarget(summary, report) {
  const deployableDrawdownPct = deploymentDrawdownPct(summary);
  return (
    summary.compoundDailyReturnPct >= targetCompoundDailyReturnPct &&
    deployableDrawdownPct <= maxDeployableDrawdownPct &&
    summary.maxConsecutiveLosses <= maxDeployableConsecutiveLosses &&
    report.walkForwardPerformance.every((period) => period.returnPct > 0)
  );
}

function passesSegmentedDeployableTarget(summary, segmentResults) {
  return (
    summary.compoundDailyReturnPct >= targetCompoundDailyReturnPct &&
    deploymentDrawdownPct(summary) <= maxDeployableDrawdownPct &&
    summary.maxConsecutiveLosses <= maxDeployableConsecutiveLosses &&
    segmentResults.every((segment) => segment.passed)
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

function segmentedScore(summary, segmentResults) {
  const rawTargetBonus = summary.compoundDailyReturnPct >= targetCompoundDailyReturnPct ? 1_000_000 : 0;
  const deployableBonus = passesSegmentedDeployableTarget(summary, segmentResults) ? 3_000_000 : 0;
  const failedSegmentPenalty = segmentResults.filter((segment) => !segment.passed).length * 750_000;
  const rejectionPenalty =
    segmentResults.reduce((total, segment) => total + segment.rejectionReasons.length, 0) * 125_000;
  const worstSegmentCdr = Math.min(...segmentResults.map((segment) => segment.summary.compoundDailyReturnPct));
  const medianSegmentCdr = median(segmentResults.map((segment) => segment.summary.compoundDailyReturnPct));
  const worstSegmentMdd = Math.max(...segmentResults.map((segment) => segment.summary.markToMarketMaxDrawdownPct));
  const worstSegmentExpectancy = Math.min(...segmentResults.map((segment) => segment.summary.expectancyR));
  const drawdownOverGate = Math.max(0, worstSegmentMdd - maxDeployableDrawdownPct);
  return (
    rawTargetBonus +
    deployableBonus +
    summary.compoundDailyReturnPct * 350_000 +
    medianSegmentCdr * 250_000 +
    worstSegmentCdr * 500_000 +
    worstSegmentExpectancy * 100_000 +
    (summary.profitFactor ?? 0) * 500 +
    summary.activeDayCoveragePct * 80 -
    worstSegmentMdd * 800 -
    drawdownOverGate * 25_000 -
    failedSegmentPenalty -
    rejectionPenalty -
    Math.max(0, summary.maxConsecutiveLosses - maxDeployableConsecutiveLosses) * 10_000
  );
}

function segmentRejectionReasons(summary) {
  const reasons = [];
  if (summary.tradeCount < minSegmentTrades) {
    reasons.push(`TRADE_COUNT_LT_${minSegmentTrades}`);
  }
  if (summary.netReturnPct < minSegmentReturnPct) {
    reasons.push(`RETURN_LT_${minSegmentReturnPct}`);
  }
  if (summary.expectancyR <= minSegmentExpectancyR) {
    reasons.push(`EXPECTANCY_LE_${minSegmentExpectancyR}`);
  }
  if (summary.profitFactor != null && summary.profitFactor < minSegmentProfitFactor) {
    reasons.push(`PROFIT_FACTOR_LT_${minSegmentProfitFactor}`);
  }
  if (deploymentDrawdownPct(summary) > maxDeployableDrawdownPct) {
    reasons.push(`MDD_GT_${maxDeployableDrawdownPct}`);
  }
  if (summary.maxConsecutiveLosses > maxDeployableConsecutiveLosses) {
    reasons.push(`LOSS_STREAK_GT_${maxDeployableConsecutiveLosses}`);
  }
  return reasons;
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
    ...defaultReplayLimits,
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

function withFilteredLegs(config, shouldKeep) {
  const next = structuredClone(config);
  next.legs = next.legs.filter(shouldKeep);
  if (next.legs.length === 0 || next.legs.length === config.legs.length) return null;
  return next;
}

function withLegRisk(config, riskFraction) {
  return {
    ...structuredClone(config),
    legs: config.legs.map((leg) => ({
      ...leg,
      riskFraction,
    })),
  };
}

function withVolumeCap(config, shouldCap, maxRelativeVolumeThreshold) {
  return {
    ...structuredClone(config),
    legs: config.legs.map((leg) =>
      shouldCap(leg)
        ? {
            ...leg,
            maxRelativeVolumeThreshold: Math.max(
              (leg.relativeVolumeThreshold ?? 1.0) + 0.1,
              maxRelativeVolumeThreshold,
            ),
          }
        : leg,
    ),
  };
}

function withContextRangeCap(config, shouldCap, maxContextRangePct) {
  return {
    ...structuredClone(config),
    legs: config.legs.map((leg) =>
      shouldCap(leg)
        ? {
            ...leg,
            maxContextRangePct,
          }
        : leg,
    ),
  };
}

function withContextQuoteVolumeFloor(config, shouldApply, minContextQuoteVolume) {
  return {
    ...structuredClone(config),
    legs: config.legs.map((leg) =>
      shouldApply(leg)
        ? {
            ...leg,
            minContextQuoteVolume,
          }
        : leg,
    ),
  };
}

function withLegIdPatch(config, legId, patch) {
  return {
    ...structuredClone(config),
    legs: config.legs.map((leg) =>
      leg.id === legId
        ? {
            ...leg,
            ...patch,
          }
        : leg,
    ),
  };
}

function isStressRegimeLeg(leg) {
  return (
    leg.allowedMarketRegimes?.some((regime) => regime === "RANGE" || regime === "HIGH_VOLATILITY_CHOP") === true ||
    leg.id === "m1_trend_up_breakout_scalp"
  );
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
    segmentResults:
      result.segmentResults?.map((segment) => ({
        id: segment.id,
        passed: segment.passed,
        rejectionReasons: segment.rejectionReasons,
        compoundDailyReturnPct: segment.summary.compoundDailyReturnPct,
        markToMarketMaxDrawdownPct: segment.summary.markToMarketMaxDrawdownPct,
        netReturnPct: segment.summary.netReturnPct,
        expectancyR: segment.summary.expectancyR,
      })) ?? undefined,
  };
}

function median(values) {
  const sorted = [...values].sort((left, right) => left - right);
  if (sorted.length === 0) return 0;
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0 ? (sorted[middle - 1] + sorted[middle]) / 2 : sorted[middle];
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
