#!/usr/bin/env node

import { createHash } from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";

const DEFAULT_PROTOCOL_PATH = "config/volume-flow-sealed-windows-v1.json";
const DEFAULT_STRATEGY_PATH = "config/volume-flow-composite-causal-unverified.json";
const MAX_REPLAY_LIMITS = { m1Limit: 6_000_000, m5Limit: 1_200_000, m15Limit: 400_000 };
const COVERAGE_TOLERANCE_MILLIS = 15 * 60_000;

export function parseArgs(argv) {
  const values = new Map();
  for (const argument of argv) {
    if (!argument.startsWith("--") || !argument.includes("=")) {
      throw new Error(`Invalid argument: ${argument}. Use --name=value.`);
    }
    const [name, ...rest] = argument.slice(2).split("=");
    values.set(name, rest.join("="));
  }
  const options = {
    api: values.get("api") ?? process.env.VOLUME_FLOW_API ?? "http://127.0.0.1:18080",
    token: values.get("token") ?? process.env.BOT_CONTROL_TOKEN ?? "local-test-token",
    protocol: path.resolve(values.get("protocol") ?? DEFAULT_PROTOCOL_PATH),
    strategy: path.resolve(values.get("strategy") ?? DEFAULT_STRATEGY_PATH),
    out: values.get("out") == null ? null : path.resolve(values.get("out")),
    concurrency: Number(values.get("concurrency") ?? 1),
  };
  if (!Number.isInteger(options.concurrency) || options.concurrency < 1 || options.concurrency > 4) {
    throw new Error("concurrency must be an integer between 1 and 4.");
  }
  return options;
}

export function verifyProtocol(protocol) {
  if (protocol?.schemaVersion !== 1 || protocol?.status !== "SEALED" || protocol?.generation?.tuningAllowed !== false) {
    throw new Error("Protocol must be schemaVersion=1, status=SEALED, and tuningAllowed=false.");
  }
  if (!Array.isArray(protocol.windows) || protocol.windows.length < 20 || protocol.windows.length > 40) {
    throw new Error("Sealed protocol must contain 20 to 40 windows.");
  }
  const earliestAt = Date.parse(protocol.sourceData?.earliestAt);
  const latestAt = Date.parse(protocol.sourceData?.latestAt);
  if (!protocol.sourceData?.symbol || !Number.isFinite(earliestAt) || !Number.isFinite(latestAt) || latestAt <= earliestAt) {
    throw new Error("Sealed protocol must define a valid source-data symbol and time range.");
  }
  const actualHash = sha256(protocol.windows);
  if (actualHash !== protocol.windowsSha256) {
    throw new Error(`Sealed window hash mismatch. Expected ${protocol.windowsSha256}, got ${actualHash}.`);
  }
  const ids = new Set();
  const ranges = new Set();
  for (const window of protocol.windows) {
    if (!/^S\d{2}$/.test(window.id) || ids.has(window.id)) throw new Error("Sealed windows must use unique SNN ids.");
    if (!Number.isInteger(window.durationMonths) || window.durationMonths < 1 || window.durationMonths > 60) {
      throw new Error(`Window ${window.id} must have a duration between 1 and 60 months.`);
    }
    const start = Date.parse(window.replayStartAt);
    const end = Date.parse(window.replayEndAt);
    if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) throw new Error(`Window ${window.id} has an invalid range.`);
    if (start < earliestAt || end > latestAt) throw new Error(`Window ${window.id} is outside the sealed source-data range.`);
    if (calendarMonthDifference(window.replayStartAt, window.replayEndAt) !== window.durationMonths) {
      throw new Error(`Window ${window.id} durationMonths does not match its calendar range.`);
    }
    const key = `${window.replayStartAt}:${window.replayEndAt}`;
    if (ranges.has(key)) throw new Error(`Window ${window.id} duplicates an existing range.`);
    ids.add(window.id);
    ranges.add(key);
  }
  const gates = protocol.gates;
  if (
    !Number.isFinite(gates?.minCompoundDailyReturnPct) ||
    gates.minCompoundDailyReturnPct <= 0 ||
    !Number.isFinite(gates?.maxMarkToMarketDrawdownPct) ||
    gates.maxMarkToMarketDrawdownPct <= 0 ||
    !Number.isInteger(gates?.maxLiquidationCount) ||
    gates.maxLiquidationCount < 0 ||
    !Number.isInteger(gates?.minTradeCount) ||
    gates.minTradeCount < 1 ||
    !Number.isFinite(gates?.minActiveDayCoveragePct) ||
    gates.minActiveDayCoveragePct < 0 ||
    typeof gates?.requiredFillModelVersion !== "string"
  ) {
    throw new Error("Sealed protocol gates are incomplete.");
  }
  return { ...protocol, actualHash };
}

export function buildRequest(strategy, window) {
  return {
    ...structuredClone(strategy),
    ...MAX_REPLAY_LIMITS,
    replayStartAt: window.replayStartAt,
    replayEndAt: window.replayEndAt,
    tradeLimit: 0,
    equityCurveLimit: 0,
    drawdownEventLimit: 0,
  };
}

export function evaluateWindow(window, response, gates) {
  const reasons = [];
  if (response.fillModelVersion !== gates.requiredFillModelVersion) reasons.push("FILL_MODEL_MISMATCH");
  if (response.compoundDailyReturnPct < gates.minCompoundDailyReturnPct) reasons.push("CDR_BELOW_TARGET");
  if (Math.max(response.maxDrawdownPct ?? 0, response.markToMarketMaxDrawdownPct ?? 0) > gates.maxMarkToMarketDrawdownPct) {
    reasons.push("MDD_ABOVE_LIMIT");
  }
  if (response.liquidationCount > gates.maxLiquidationCount) reasons.push("LIQUIDATION_DETECTED");
  if (response.tradeCount < gates.minTradeCount) reasons.push("TRADE_COVERAGE_INSUFFICIENT");
  if (response.activeDayCoveragePct < gates.minActiveDayCoveragePct) reasons.push("ACTIVE_DAY_COVERAGE_INSUFFICIENT");
  if (!hasRequestedCoverage(window, response.commonReplayWindow)) reasons.push("REPLAY_COVERAGE_INSUFFICIENT");

  return {
    id: window.id,
    durationMonths: window.durationMonths,
    replayStartAt: window.replayStartAt,
    replayEndAt: window.replayEndAt,
    passed: reasons.length === 0,
    reasons,
    engineVersion: response.engineVersion,
    fillModelVersion: response.fillModelVersion,
    effectiveStartAt: response.commonReplayWindow?.startAt ?? null,
    effectiveEndAt: response.commonReplayWindow?.endAt ?? null,
    compoundDailyReturnPct: response.compoundDailyReturnPct,
    maxDrawdownPct: response.maxDrawdownPct,
    markToMarketMaxDrawdownPct: response.markToMarketMaxDrawdownPct,
    liquidationCount: response.liquidationCount,
    tradeCount: response.tradeCount,
    activeDayCoveragePct: response.activeDayCoveragePct,
    netReturnPct: response.netReturnPct,
    finalEquity: response.finalEquity,
  };
}

export async function evaluateProtocol(protocol, strategy, request, concurrency = 1) {
  const results = await mapWithConcurrency(protocol.windows, concurrency, async (window) => {
    const response = await request(buildRequest(strategy, window));
    return evaluateWindow(window, response, protocol.gates);
  });
  const failed = results.filter((result) => !result.passed);
  return {
    protocolHash: protocol.actualHash,
    strategyHash: sha256(strategy),
    passed: failed.length === 0,
    windowCount: results.length,
    passedWindowCount: results.length - failed.length,
    failedWindowCount: failed.length,
    gates: protocol.gates,
    results,
  };
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  const protocol = verifyProtocol(JSON.parse(await fs.readFile(options.protocol, "utf8")));
  const strategy = JSON.parse(await fs.readFile(options.strategy, "utf8"));
  const report = await evaluateProtocol(protocol, strategy, request => requestComposite(options, request), options.concurrency);
  const output = JSON.stringify({ generatedAt: new Date().toISOString(), ...report }, null, 2);
  if (options.out != null) {
    await fs.mkdir(path.dirname(options.out), { recursive: true });
    await fs.writeFile(options.out, output);
  }
  console.log(output);
  if (!report.passed) process.exitCode = 2;
}

async function requestComposite(options, request) {
  const response = await fetch(`${options.api.replace(/\/$/, "")}/backtests/volume-flow/composite/run`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${options.token}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(request),
  });
  const body = await response.text();
  if (!response.ok) throw new Error(`HTTP ${response.status}: ${body.slice(0, 1000)}`);
  return JSON.parse(body);
}

function hasRequestedCoverage(window, commonReplayWindow) {
  const actualStart = Date.parse(commonReplayWindow?.startAt);
  const actualEnd = Date.parse(commonReplayWindow?.endAt);
  const requestedStart = Date.parse(window.replayStartAt);
  const requestedEnd = Date.parse(window.replayEndAt);
  return (
    Number.isFinite(actualStart) &&
    Number.isFinite(actualEnd) &&
    actualStart <= requestedStart + COVERAGE_TOLERANCE_MILLIS &&
    actualEnd >= requestedEnd - COVERAGE_TOLERANCE_MILLIS
  );
}

function sha256(value) {
  return createHash("sha256").update(JSON.stringify(value)).digest("hex");
}

function calendarMonthDifference(startAt, endAt) {
  const start = new Date(startAt);
  const end = new Date(endAt);
  return (end.getUTCFullYear() - start.getUTCFullYear()) * 12 + end.getUTCMonth() - start.getUTCMonth();
}

async function mapWithConcurrency(items, concurrency, mapper) {
  const results = new Array(items.length);
  let nextIndex = 0;
  await Promise.all(
    Array.from({ length: Math.min(concurrency, items.length) }, async () => {
      while (nextIndex < items.length) {
        const currentIndex = nextIndex;
        nextIndex += 1;
        results[currentIndex] = await mapper(items[currentIndex]);
      }
    }),
  );
  return results;
}

const scriptPath = fileURLToPath(import.meta.url);
if (process.argv[1] != null && path.resolve(process.argv[1]) === scriptPath) {
  main().catch((error) => {
    console.error(error.stack || error.message);
    process.exitCode = 1;
  });
}
