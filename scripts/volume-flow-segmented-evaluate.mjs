#!/usr/bin/env node

import fs from "node:fs/promises";
import path from "node:path";

const MAX_REPLAY_LIMITS = {
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
    purpose: "early failure profile and high-risk compounding stress",
  },
  {
    id: "S2",
    role: "stress-validation",
    replayStartAt: "2021-10-19T00:00:00Z",
    replayEndAt: "2023-05-28T23:59:59Z",
    purpose: "drawdown regime where the current config collapses",
  },
  {
    id: "S3",
    role: "validation",
    replayStartAt: "2023-05-29T00:00:00Z",
    replayEndAt: "2024-12-31T23:59:59Z",
    purpose: "transition into the favorable regime",
  },
  {
    id: "S4",
    role: "validation",
    replayStartAt: "2025-01-01T00:00:00Z",
    replayEndAt: "2026-07-02T05:40:00Z",
    purpose: "recent regime, should not dominate all score",
  },
  {
    id: "FULL",
    role: "final-gate",
    replayStartAt: "2020-03-25T10:36:00Z",
    replayEndAt: "2026-07-02T05:40:00Z",
    purpose: "full available survival and deployment sanity check",
  },
];

const DEFAULT_GATES = {
  maxMarkToMarketDrawdownPct: 40,
  minReturnPct: 0,
  minExpectancyR: 0,
  minProfitFactor: 1,
  maxConsecutiveLosses: 10,
  minTradeCount: 1,
  minContributorTrades: 5,
};

const args = parseArgs(process.argv.slice(2));
const apiBase = args.api ?? process.env.VOLUME_FLOW_API ?? "http://127.0.0.1:18080";
const token = args.token ?? process.env.BOT_CONTROL_TOKEN ?? "local-test-token";
const configPath = args.config ?? "config/volume-flow-composite-current.json";
const outDir = args.out ?? "build/volume-flow-segmented-evaluation";
const useConfigLimits = args.useConfigLimits === "true";
const tradeLimit = Number(args.tradeLimit ?? 0);
const equityCurveLimit = Number(args.equityCurveLimit ?? 0);
const drawdownEventLimit = Number(args.drawdownEventLimit ?? 20);
const gates = {
  ...DEFAULT_GATES,
  maxMarkToMarketDrawdownPct: Number(args.maxMtmMddPct ?? DEFAULT_GATES.maxMarkToMarketDrawdownPct),
  minReturnPct: Number(args.minReturnPct ?? DEFAULT_GATES.minReturnPct),
  minExpectancyR: Number(args.minExpectancyR ?? DEFAULT_GATES.minExpectancyR),
  minProfitFactor: Number(args.minProfitFactor ?? DEFAULT_GATES.minProfitFactor),
  maxConsecutiveLosses: Number(args.maxConsecutiveLosses ?? DEFAULT_GATES.maxConsecutiveLosses),
  minTradeCount: Number(args.minTradeCount ?? DEFAULT_GATES.minTradeCount),
  minContributorTrades: Number(args.minContributorTrades ?? DEFAULT_GATES.minContributorTrades),
};

const baseConfig = JSON.parse(await fs.readFile(configPath, "utf8"));
const segments = args.segments == null ? DEFAULT_SEGMENTS : JSON.parse(await fs.readFile(args.segments, "utf8"));
await fs.mkdir(outDir, { recursive: true });

console.log(`Evaluating ${segments.length} segments against ${apiBase}`);

const results = [];
for (const segment of segments) {
  const result = await evaluateSegment(segment);
  results.push(result);
  console.log(
    [
      `segment=${segment.id}`,
      `pass=${result.passed}`,
      result.summary ? `cdr=${fmt(result.summary.compoundDailyReturnPct)}` : "cdr=n/a",
      result.summary ? `mtmMdd=${fmt(result.summary.markToMarketMaxDrawdownPct)}` : "mtmMdd=n/a",
      result.rejectionReasons.length > 0 ? `reject=${result.rejectionReasons.join("|")}` : "reject=none",
    ].join(" "),
  );
}

const report = {
  generatedAt: new Date().toISOString(),
  apiBase,
  configPath,
  gates,
  passed: results.every((result) => result.passed),
  results,
};

await fs.writeFile(path.join(outDir, "segmented-evaluation.json"), JSON.stringify(report, null, 2));
await fs.writeFile(path.join(outDir, "segmented-evaluation.md"), renderMarkdown(report));
console.log(JSON.stringify(toPrintable(report), null, 2));

async function evaluateSegment(segment) {
  const request = buildRequest(segment);
  try {
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
      return {
        segment,
        passed: false,
        rejectionReasons: [`HTTP_${response.status}`],
        error: body.slice(0, 1000),
        request: requestSummary(request),
      };
    }

    const rawReport = JSON.parse(body);
    const summary = summarize(rawReport);
    const diagnostics = diagnosticsFor(rawReport);
    const rejectionReasons = rejectionReasonsFor(summary, diagnostics);
    return {
      segment,
      passed: rejectionReasons.length === 0,
      rejectionReasons,
      request: requestSummary(request),
      coverage: {
        requested: rawReport.requestedCoverage,
        effective: rawReport.effectiveCoverage,
        commonReplayWindow: rawReport.commonReplayWindow,
      },
      summary,
      diagnostics,
    };
  } catch (error) {
    return {
      segment,
      passed: false,
      rejectionReasons: ["REQUEST_FAILED"],
      error: error instanceof Error ? error.message : String(error),
      request: requestSummary(request),
    };
  }
}

function buildRequest(segment) {
  const limits = useConfigLimits
    ? {
        m1Limit: baseConfig.m1Limit,
        m5Limit: baseConfig.m5Limit,
        m15Limit: baseConfig.m15Limit,
      }
    : MAX_REPLAY_LIMITS;

  return {
    ...baseConfig,
    ...limits,
    replayStartAt: segment.replayStartAt,
    replayEndAt: segment.replayEndAt,
    tradeLimit,
    equityCurveLimit,
    drawdownEventLimit,
  };
}

function requestSummary(request) {
  return {
    symbol: request.symbol,
    m1Limit: request.m1Limit,
    m5Limit: request.m5Limit,
    m15Limit: request.m15Limit,
    replayStartAt: request.replayStartAt,
    replayEndAt: request.replayEndAt,
    tradeLimit: request.tradeLimit,
    equityCurveLimit: request.equityCurveLimit,
    drawdownEventLimit: request.drawdownEventLimit,
  };
}

function summarize(report) {
  return {
    finalEquity: report.finalEquity,
    netReturnPct: report.netReturnPct,
    compoundDailyReturnPct: report.compoundDailyReturnPct,
    maxDrawdownPct: report.maxDrawdownPct,
    markToMarketMaxDrawdownPct: report.markToMarketMaxDrawdownPct,
    tradeCount: report.tradeCount,
    activeDayCoveragePct: report.activeDayCoveragePct,
    averageTradesPerActiveDay: report.averageTradesPerActiveDay,
    winRatePct: report.winRatePct,
    profitFactor: report.profitFactor,
    expectancyR: report.expectancyR,
    averageWinR: report.averageWinR,
    averageLossR: report.averageLossR,
    payoffRatio: report.payoffRatio,
    winRateEdgePct: report.winRateEdgePct,
    maxConsecutiveLosses: report.maxConsecutiveLosses,
    averageMfeCapturePct: report.averageMfeCapturePct,
    observedDays: report.observedDays,
    activeDays: report.activeDays,
  };
}

function diagnosticsFor(report) {
  return {
    worstDrawdowns: report.drawdownEvents ?? [],
    negativeLegs: negativeContributors(report.performanceByLeg ?? []),
    negativeSides: negativeContributors(report.performanceBySide ?? []),
    negativeRegimes: negativeContributors(report.performanceByMarketRegime ?? []),
    negativeExits: negativeContributors(report.performanceByExitReason ?? []),
  };
}

function negativeContributors(items) {
  return items
    .filter((item) => item.tradeCount >= gates.minContributorTrades)
    .filter((item) => (item.netPnl ?? 0) < 0 || (item.expectancyR ?? 0) <= gates.minExpectancyR)
    .map((item) => ({
      tag: item.tag,
      tradeCount: item.tradeCount,
      netPnl: item.netPnl,
      profitFactor: item.profitFactor,
      expectancyR: item.expectancyR,
    }));
}

function rejectionReasonsFor(summary, diagnostics) {
  const reasons = [];
  if (summary.tradeCount < gates.minTradeCount) {
    reasons.push(`TRADE_COUNT_LT_${gates.minTradeCount}`);
  }
  if (summary.netReturnPct < gates.minReturnPct) {
    reasons.push(`RETURN_LT_${gates.minReturnPct}`);
  }
  if (summary.expectancyR <= gates.minExpectancyR) {
    reasons.push(`EXPECTANCY_LE_${gates.minExpectancyR}`);
  }
  if (summary.profitFactor != null && summary.profitFactor < gates.minProfitFactor) {
    reasons.push(`PROFIT_FACTOR_LT_${gates.minProfitFactor}`);
  }
  if (summary.markToMarketMaxDrawdownPct > gates.maxMarkToMarketDrawdownPct) {
    reasons.push(`MTM_MDD_GT_${gates.maxMarkToMarketDrawdownPct}`);
  }
  if (summary.maxConsecutiveLosses > gates.maxConsecutiveLosses) {
    reasons.push(`LOSS_STREAK_GT_${gates.maxConsecutiveLosses}`);
  }
  if (diagnostics.negativeSides.length > 0) {
    reasons.push(`NEGATIVE_SIDE_${diagnostics.negativeSides.map((item) => item.tag).join("_")}`);
  }
  if (diagnostics.negativeRegimes.length > 0) {
    reasons.push(`NEGATIVE_REGIME_${diagnostics.negativeRegimes.map((item) => item.tag).join("_")}`);
  }
  return reasons;
}

function renderMarkdown(report) {
  const lines = [
    "# Volume Flow Segmented Evaluation",
    "",
    `Generated at: \`${report.generatedAt}\``,
    `Config: \`${report.configPath}\``,
    `Overall: \`${report.passed ? "PASS" : "FAIL"}\``,
    "",
    "## Gates",
    "",
    `- Max MTM MDD: \`${report.gates.maxMarkToMarketDrawdownPct}%\``,
    `- Min return: \`${report.gates.minReturnPct}%\``,
    `- Min expectancyR: \`> ${report.gates.minExpectancyR}\``,
    `- Min profit factor: \`${report.gates.minProfitFactor}\``,
    `- Max consecutive losses: \`${report.gates.maxConsecutiveLosses}\``,
    "",
    "## Segments",
    "",
    [
      "| Segment | Role | Window | Pass | Final equity | Return | CDR | MTM MDD | Trades | Win | PF | ExpR | Rejections |",
      "| --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |",
      ...report.results.map((result) => {
        const summary = result.summary;
        const window = `${result.segment.replayStartAt}..${result.segment.replayEndAt}`;
        if (summary == null) {
          return `| ${result.segment.id} | ${result.segment.role} | ${window} | FAIL | n/a | n/a | n/a | n/a | n/a | n/a | n/a | n/a | ${result.rejectionReasons.join(", ")} |`;
        }
        return `| ${[
          result.segment.id,
          result.segment.role,
          window,
          result.passed ? "PASS" : "FAIL",
          fmt(summary.finalEquity),
          pct(summary.netReturnPct),
          pct(summary.compoundDailyReturnPct),
          pct(summary.markToMarketMaxDrawdownPct),
          summary.tradeCount,
          pct(summary.winRatePct),
          fmt(summary.profitFactor),
          fmt(summary.expectancyR),
          result.rejectionReasons.length === 0 ? "none" : result.rejectionReasons.join(", "),
        ].join(" | ")} |`;
      }),
    ].join("\n"),
    "",
    "## Negative Contributors",
    "",
    ...report.results.flatMap(renderNegativeContributors),
  ];
  return `${lines.join("\n")}\n`;
}

function renderNegativeContributors(result) {
  if (result.summary == null) {
    return [`### ${result.segment.id}`, "", `- Error: \`${result.error ?? "unknown"}\``, ""];
  }

  const groups = [
    ["Leg", result.diagnostics.negativeLegs],
    ["Side", result.diagnostics.negativeSides],
    ["Regime", result.diagnostics.negativeRegimes],
    ["Exit", result.diagnostics.negativeExits],
  ];
  const lines = [`### ${result.segment.id}`, ""];
  for (const [name, items] of groups) {
    if (items.length === 0) {
      lines.push(`- ${name}: none`);
      continue;
    }
    lines.push(`- ${name}: ${items.map((item) => `${item.tag}(trades=${item.tradeCount}, expR=${fmt(item.expectancyR)})`).join(", ")}`);
  }
  lines.push("");
  return lines;
}

function toPrintable(report) {
  return {
    passed: report.passed,
    results: report.results.map((result) => ({
      segment: result.segment.id,
      role: result.segment.role,
      passed: result.passed,
      rejectionReasons: result.rejectionReasons,
      summary: result.summary,
    })),
  };
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

function pct(value) {
  return value == null ? "n/a" : `${fmt(value)}%`;
}

function fmt(value) {
  if (value == null || !Number.isFinite(Number(value))) return "n/a";
  return Number(value).toFixed(5);
}
