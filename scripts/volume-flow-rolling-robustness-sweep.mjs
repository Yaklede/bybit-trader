#!/usr/bin/env node

import fs from "node:fs/promises";
import path from "node:path";

const DEFAULT_LIMITS = {
  m1Limit: 6_000_000,
  m5Limit: 1_200_000,
  m15Limit: 400_000,
};

const DEFAULT_WINDOWS_PATH =
  "build/volume-flow-recursive-robustness-20260703/final-config-validation-20260703/summary.json";

const args = parseArgs(process.argv.slice(2));
const apiBase = args.api ?? process.env.VOLUME_FLOW_API ?? "http://127.0.0.1:18080";
const token = args.token ?? process.env.BOT_CONTROL_TOKEN ?? "local-test-token";
const configPath = args.config ?? "config/volume-flow-composite-current.json";
const windowsPath = args.windows ?? DEFAULT_WINDOWS_PATH;
const outDir = args.out ?? "build/volume-flow-rolling-robustness-sweep";
const maxCandidates = Number(args.maxCandidates ?? Number.POSITIVE_INFINITY);
const minReturnPct = Number(args.minReturnPct ?? 0);
const maxMtmMddPct = Number(args.maxMtmMddPct ?? 40);
const minTrades = Number(args.minTrades ?? 5);
const tradeLimit = Number(args.tradeLimit ?? 0);
const equityCurveLimit = Number(args.equityCurveLimit ?? 0);
const drawdownEventLimit = Number(args.drawdownEventLimit ?? 0);
const requireImprovement = args.requireImprovement === "true";
const keepBaseline = args.keepBaseline !== "false";

const baseConfig = JSON.parse(await fs.readFile(configPath, "utf8"));
const baselineWindows = JSON.parse(await fs.readFile(windowsPath, "utf8"));
const candidateDefs = args.candidates == null ? defaultCandidates(baseConfig) : JSON.parse(await fs.readFile(args.candidates, "utf8"));
const candidates = uniqueCandidates(candidateDefs).slice(0, maxCandidates);
const baselineFailureBudget = failureBudgetFor(baselineWindows);
const max180Failures = Number(
  args.max180Failures ?? (requireImprovement ? Math.max(0, baselineFailureBudget["180d"] - 1) : baselineFailureBudget["180d"]),
);
const max365Failures = Number(
  args.max365Failures ?? (requireImprovement ? Math.max(0, baselineFailureBudget["365d"] - 1) : baselineFailureBudget["365d"]),
);
const windowOrder = orderWindows(baselineWindows);

await fs.mkdir(outDir, { recursive: true });

console.log(
  [
    `api=${apiBase}`,
    `windows=${baselineWindows.length}`,
    `candidates=${candidates.length}`,
    `max180Failures=${max180Failures}`,
    `max365Failures=${max365Failures}`,
    `requireImprovement=${requireImprovement}`,
  ].join(" "),
);

const results = [];
for (let index = 0; index < candidates.length; index += 1) {
  const candidate = candidates[index];
  if (!keepBaseline && candidate.id === "baseline") continue;

  const result = await evaluateCandidate(candidate);
  results.push(result);
  await writeResults(results);

  const rollup = result.rollup;
  console.log(
    [
      `progress=${index + 1}/${candidates.length}`,
      `candidate=${candidate.id}`,
      `status=${result.status}`,
      `180=${rollup["180d"].passed}/${rollup["180d"].windows}`,
      `365=${rollup["365d"].passed}/${rollup["365d"].windows}`,
      `worst=${fmt(result.worstReturn?.returnPct)}`,
      result.rejectReason == null ? "reject=none" : `reject=${result.rejectReason}`,
    ].join(" "),
  );
}

await writeResults(results);
console.log(JSON.stringify(rankResults(results).slice(0, 20).map(toPrintable), null, 2));

async function evaluateCandidate(candidate) {
  const config = candidate.config ?? applyCandidate(baseConfig, candidate);
  const rows = [];
  const failures = { "180d": 0, "365d": 0 };
  let status = "complete";
  let rejectReason = null;
  let error = null;

  for (const baselineWindow of windowOrder) {
    let row;
    try {
      row = await runWindow(config, candidate.id, baselineWindow);
    } catch (caught) {
      status = "rejected";
      rejectReason = "REQUEST_FAILED";
      error = caught instanceof Error ? caught.message : String(caught);
      break;
    }
    rows.push(row);
    if (!row.passed) failures[row.kind] += 1;

    if (failures["180d"] > max180Failures) {
      status = "rejected";
      rejectReason = "180D_FAILURE_BUDGET_EXCEEDED";
      break;
    }
    if (failures["365d"] > max365Failures) {
      status = "rejected";
      rejectReason = "365D_FAILURE_BUDGET_EXCEEDED";
      break;
    }
  }

  const rollup = rollupFor(rows);
  const worstReturn = rows.toSorted((a, b) => a.returnPct - b.returnPct)[0] ?? null;
  const worstMdd = rows.toSorted((a, b) => b.mtmMdd - a.mtmMdd)[0] ?? null;
  const completedAllWindows = rows.length === baselineWindows.length;
  const meetsFailureBudget =
    rollup["180d"].failed <= max180Failures &&
    rollup["365d"].failed <= max365Failures;
  const improvesBaseline =
    completedAllWindows &&
    rollup["180d"].failed <= baselineFailureBudget["180d"] &&
    rollup["365d"].failed <= baselineFailureBudget["365d"] &&
    (
      rollup["180d"].failed < baselineFailureBudget["180d"] ||
        rollup["365d"].failed < baselineFailureBudget["365d"]
    );

  return {
    id: candidate.id,
    generatedAt: new Date().toISOString(),
    status,
    rejectReason,
    error,
    completedAllWindows,
    meetsFailureBudget,
    improvesBaseline,
    patches: candidate.patches ?? null,
    config,
    rollup,
    worstReturn,
    worstMdd,
    rows,
  };
}

async function runWindow(config, candidateId, window) {
  const request = {
    ...config,
    ...DEFAULT_LIMITS,
    replayStartAt: window.startAt,
    replayEndAt: window.endAt,
    tradeLimit,
    equityCurveLimit,
    drawdownEventLimit,
  };
  const response = await fetch(`${apiBase}/backtests/volume-flow/composite/run`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${token}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(request),
  });
  const body = await response.text();
  if (!response.ok) {
    throw new Error(`${candidateId} ${window.kind} ${window.period} HTTP ${response.status}: ${body.slice(0, 500)}`);
  }

  const report = JSON.parse(body);
  const worstLeg = report.performanceByLeg?.toSorted((a, b) => a.netPnl - b.netPnl)[0] ?? null;
  const failReasons = [
    ...(report.tradeCount < minTrades ? ["TOO_FEW_TRADES"] : []),
    ...(report.netReturnPct < minReturnPct ? ["RETURN_BELOW_MIN"] : []),
    ...(report.markToMarketMaxDrawdownPct > maxMtmMddPct ? ["DRAWDOWN_ABOVE_MAX"] : []),
  ];

  return {
    candidate: candidateId,
    kind: window.kind,
    period: window.period,
    startAt: window.startAt,
    endAt: window.endAt,
    returnPct: round(report.netReturnPct),
    mtmMdd: round(report.markToMarketMaxDrawdownPct),
    trades: report.tradeCount,
    winRate: round(report.winRatePct),
    pf: round(report.profitFactor),
    expectancyR: round(report.expectancyR),
    worstLeg: worstLeg?.tag ?? null,
    worstLegPnl: round(worstLeg?.netPnl),
    passed: failReasons.length === 0,
    failReasons,
    baselineReturnPct: window.returnPct,
    baselinePassed: window.passed,
    returnDeltaPct: round(report.netReturnPct - window.returnPct),
  };
}

function defaultCandidates(config) {
  const variants = [candidate("baseline", {})];
  const m1UpConditionalVolume = [];
  for (const threshold of [0.0045, 0.005, 0.0055]) {
    for (const minRelativeVolume of [4, 4.5, 5, 5.5]) {
      m1UpConditionalVolume.push({
        highContextRangeRelativeVolumeThresholdPct: threshold,
        highContextRangeRelativeVolumeMin: minRelativeVolume,
      });
    }
  }

  for (const patch of m1UpConditionalVolume) {
    variants.push(
      candidate(`m1up_ctxvol_${idNumber(patch.highContextRangeRelativeVolumeThresholdPct)}_${idNumber(patch.highContextRangeRelativeVolumeMin)}`, {
        m1_trend_up_breakout_scalp: patch,
      }),
    );
  }

  for (const threshold of [0.0035, 0.004]) {
    for (const multiplier of [0.4, 0.5, 0.65, 0.8]) {
      variants.push(
        candidate(`m5_ctxrisk_${idNumber(threshold)}_${idNumber(multiplier)}`, {
          trend_down_retest: {
            contextRangeRiskThresholdPct: threshold,
            contextRangeRiskMultiplier: multiplier,
          },
          trend_down_retest_runner: {
            contextRangeRiskThresholdPct: threshold,
            contextRangeRiskMultiplier: multiplier,
          },
        }),
      );
    }
  }

  for (const m1Patch of m1UpConditionalVolume.slice(0, 6)) {
    for (const m5Multiplier of [0.4, 0.5, 0.65]) {
      variants.push(
        candidate(
          `m1up_ctxvol_${idNumber(m1Patch.highContextRangeRelativeVolumeThresholdPct)}_${idNumber(
            m1Patch.highContextRangeRelativeVolumeMin,
          )}_m5ctx_${idNumber(m5Multiplier)}`,
          {
            m1_trend_up_breakout_scalp: m1Patch,
            trend_down_retest: {
              contextRangeRiskThresholdPct: 0.0035,
              contextRangeRiskMultiplier: m5Multiplier,
            },
            trend_down_retest_runner: {
              contextRangeRiskThresholdPct: 0.0035,
              contextRangeRiskMultiplier: m5Multiplier,
            },
          },
        ),
      );
    }
  }

  for (const m1Patch of m1UpConditionalVolume.slice(0, 4)) {
    for (const downMultiplier of [0.5, 0.65, 0.8]) {
      variants.push(
        candidate(
          `m1up_ctxvol_${idNumber(m1Patch.highContextRangeRelativeVolumeThresholdPct)}_${idNumber(
            m1Patch.highContextRangeRelativeVolumeMin,
          )}_downctx_${idNumber(downMultiplier)}`,
          {
            m1_trend_up_breakout_scalp: m1Patch,
            m1_trend_down_breakout_assist: {
              contextRangeRiskThresholdPct: 0.008,
              contextRangeRiskMultiplier: downMultiplier,
            },
          },
        ),
      );
    }
  }

  return variants.filter((variant) => variant.id === "baseline" || hasLegs(config, Object.keys(variant.patches)));
}

function candidate(id, patches) {
  return { id, patches };
}

function applyCandidate(config, candidateDef) {
  return {
    ...config,
    legs: config.legs.map((leg) => {
      const patch = candidateDef.patches?.[leg.id];
      return patch == null ? leg : { ...leg, ...patch };
    }),
  };
}

function orderWindows(windows) {
  return windows
    .map((window, index) => ({ ...window, index }))
    .toSorted((a, b) => {
      const failedDelta = Number(a.passed === true) - Number(b.passed === true);
      if (failedDelta !== 0) return failedDelta;
      const kindDelta = kindWeight(a.kind) - kindWeight(b.kind);
      if (kindDelta !== 0) return kindDelta;
      const returnDelta = a.returnPct - b.returnPct;
      if (returnDelta !== 0) return returnDelta;
      return a.index - b.index;
    });
}

function kindWeight(kind) {
  return kind === "365d" ? 0 : 1;
}

function failureBudgetFor(windows) {
  return {
    "180d": windows.filter((window) => window.kind === "180d" && !window.passed).length,
    "365d": windows.filter((window) => window.kind === "365d" && !window.passed).length,
  };
}

function rollupFor(rows) {
  const empty = { windows: 0, passed: 0, failed: 0, passRatePct: 0 };
  return {
    "180d": rollupKind(rows, "180d") ?? empty,
    "365d": rollupKind(rows, "365d") ?? empty,
  };
}

function rollupKind(rows, kind) {
  const selected = rows.filter((row) => row.kind === kind);
  if (selected.length === 0) return null;
  const passed = selected.filter((row) => row.passed).length;
  return {
    windows: selected.length,
    passed,
    failed: selected.length - passed,
    passRatePct: round((passed / selected.length) * 100),
    worstReturn: selected.toSorted((a, b) => a.returnPct - b.returnPct)[0],
    worstMdd: selected.toSorted((a, b) => b.mtmMdd - a.mtmMdd)[0],
  };
}

function rankResults(results) {
  return results.toSorted((a, b) => {
    const completedDelta = Number(b.completedAllWindows) - Number(a.completedAllWindows);
    if (completedDelta !== 0) return completedDelta;
    const improvementDelta = Number(b.improvesBaseline) - Number(a.improvesBaseline);
    if (improvementDelta !== 0) return improvementDelta;
    const passDelta = totalPassed(b) - totalPassed(a);
    if (passDelta !== 0) return passDelta;
    const failureDelta = totalFailed(a) - totalFailed(b);
    if (failureDelta !== 0) return failureDelta;
    return (b.worstReturn?.returnPct ?? -Infinity) - (a.worstReturn?.returnPct ?? -Infinity);
  });
}

function totalPassed(result) {
  return result.rollup["180d"].passed + result.rollup["365d"].passed;
}

function totalFailed(result) {
  return result.rollup["180d"].failed + result.rollup["365d"].failed;
}

function toPrintable(result) {
  return {
    id: result.id,
    status: result.status,
    completedAllWindows: result.completedAllWindows,
    meetsFailureBudget: result.meetsFailureBudget,
    improvesBaseline: result.improvesBaseline,
    "180d": `${result.rollup["180d"].passed}/${result.rollup["180d"].windows}`,
    "365d": `${result.rollup["365d"].passed}/${result.rollup["365d"].windows}`,
    worstReturnPct: result.worstReturn?.returnPct,
    worstMddPct: result.worstMdd?.mtmMdd,
    rejectReason: result.rejectReason,
  };
}

async function writeResults(results) {
  const ranked = rankResults(results);
  await fs.writeFile(path.join(outDir, "results.json"), JSON.stringify(ranked, null, 2));
  await fs.writeFile(path.join(outDir, "top.json"), JSON.stringify(ranked.slice(0, 25).map(toPrintable), null, 2));
  if (ranked[0]?.config != null) {
    await fs.writeFile(path.join(outDir, "best-config.json"), JSON.stringify(ranked[0].config, null, 2));
  }
}

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

function uniqueCandidates(items) {
  const seen = new Set();
  return items.filter((item) => {
    if (seen.has(item.id)) return false;
    seen.add(item.id);
    return true;
  });
}

function hasLegs(config, legIds) {
  const available = new Set(config.legs.map((leg) => leg.id));
  return legIds.every((legId) => available.has(legId));
}

function idNumber(value) {
  return String(value).replace(".", "p");
}

function fmt(value) {
  return value == null || !Number.isFinite(value) ? "n/a" : round(value);
}

function round(value) {
  return value == null || !Number.isFinite(value) ? value : Math.round(value * 100000) / 100000;
}
