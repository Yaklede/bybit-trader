#!/usr/bin/env node

import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { parseArgs as parseSealedArgs, verifyProtocol } from "./volume-flow-sealed-evaluate.mjs";

const DEFAULT_PROTOCOL_PATH = "config/volume-flow-sealed-windows-v1.json";
const DEFAULT_CONTRACT_PATH = "config/aggressive-runtime-replay-contract-v1.json";
const MAX_REPLAY_LIMITS = { m1Limit: 6_000_000, m5Limit: 1_200_000 };
const COVERAGE_TOLERANCE_MILLIS = 15 * 60_000;

export function parseArgs(argv) {
  const base = parseSealedArgs(argv);
  const values = new Map(argv.map((argument) => argument.slice(2).split("=", 2)));
  const options = {
    ...base,
    contract: path.resolve(values.get("contract") ?? DEFAULT_CONTRACT_PATH),
  };
  if (values.has("strategy")) {
    throw new Error("The runtime audit uses --contract, not --strategy.");
  }
  return options;
}

export function verifyContract(contract) {
  if (contract?.schemaVersion !== 1 || contract?.status !== "FROZEN") {
    throw new Error("Runtime replay contract must be schemaVersion=1 and status=FROZEN.");
  }
  const runtime = contract.runtimeProfile;
  if (
    !runtime?.profileId ||
    !runtime.endpoint?.startsWith("/") ||
    !Number.isFinite(runtime.initialEquity) ||
    runtime.initialEquity <= 0 ||
    !Number.isFinite(runtime.riskFraction) ||
    runtime.riskFraction <= 0 ||
    !Number.isFinite(runtime.maxNotional) ||
    runtime.maxNotional <= 0 ||
    !Number.isFinite(runtime.leverage) ||
    runtime.leverage <= 1
  ) {
    throw new Error("Runtime replay contract has incomplete execution settings.");
  }
  const gates = contract.gates;
  if (
    !gates?.requiredFillModelVersion ||
    !gates?.requiredValidationStatus ||
    !Number.isFinite(gates.minCompoundDailyReturnPct) ||
    !Number.isFinite(gates.maxDrawdownPct) ||
    !Number.isInteger(gates.maxLiquidationCount) ||
    !Number.isInteger(gates.minTradeCount) ||
    !Number.isFinite(gates.minActiveDayCoveragePct)
  ) {
    throw new Error("Runtime replay contract gates are incomplete.");
  }
  return contract;
}

export function buildRuntimeRequest(contract, window) {
  const { profileId, endpoint, ...executionSettings } = contract.runtimeProfile;
  return {
    symbol: "BTCUSDT",
    ...MAX_REPLAY_LIMITS,
    replayStartAt: window.replayStartAt,
    replayEndAt: window.replayEndAt,
    ...structuredClone(executionSettings),
    tradeLimit: 0,
  };
}

export function evaluateRuntimeWindow(window, result, gates, profileId) {
  const reasons = [];
  if (!result.ok) {
    reasons.push(result.reason ?? "REQUEST_FAILED");
    return failedWindow(window, reasons);
  }

  const response = result.response;
  if (response.profileId !== profileId) reasons.push("PROFILE_MISMATCH");
  if (response.fillModelVersion !== gates.requiredFillModelVersion) reasons.push("FILL_MODEL_MISMATCH");
  if (response.validationStatus !== gates.requiredValidationStatus) reasons.push("PROFILE_UNVERIFIED");
  if (response.compoundDailyReturnPct < gates.minCompoundDailyReturnPct) reasons.push("CDR_BELOW_TARGET");
  if (response.maxDrawdownPct > gates.maxDrawdownPct) reasons.push("MDD_ABOVE_LIMIT");
  if (response.liquidationCount > gates.maxLiquidationCount) reasons.push("LIQUIDATION_DETECTED");
  if (response.tradeCount < gates.minTradeCount) reasons.push("TRADE_COVERAGE_INSUFFICIENT");
  if (response.activeDayCoveragePct < gates.minActiveDayCoveragePct) reasons.push("ACTIVE_DAY_COVERAGE_INSUFFICIENT");
  if (!hasRequestedCoverage(window, response)) reasons.push("REPLAY_COVERAGE_INSUFFICIENT");

  return {
    ...failedWindow(window, reasons),
    engineVersion: response.engineVersion,
    fillModelVersion: response.fillModelVersion,
    validationStatus: response.validationStatus,
    liveExpansionAllowed: response.liveExpansionAllowed,
    effectiveStartAt: response.startAt ?? null,
    effectiveEndAt: response.endAt ?? null,
    compoundDailyReturnPct: response.compoundDailyReturnPct,
    maxDrawdownPct: response.maxDrawdownPct,
    liquidationCount: response.liquidationCount,
    tradeCount: response.tradeCount,
    activeDayCoveragePct: response.activeDayCoveragePct,
    netReturnPct: response.netReturnPct,
    finalEquity: response.finalEquity,
  };
}

export async function evaluateRuntimeProtocol(protocol, contract, request, concurrency = 1) {
  const results = await mapWithConcurrency(protocol.windows, concurrency, async (window) => {
    try {
      const response = await request(buildRuntimeRequest(contract, window));
      return evaluateRuntimeWindow(window, { ok: true, response }, contract.gates, contract.runtimeProfile.profileId);
    } catch (error) {
      return evaluateRuntimeWindow(
        window,
        { ok: false, reason: `REQUEST_FAILED:${String(error.message ?? error).slice(0, 180)}` },
        contract.gates,
        contract.runtimeProfile.profileId,
      );
    }
  });
  const failed = results.filter((result) => !result.passed);
  return {
    passed: failed.length === 0,
    windowCount: results.length,
    passedWindowCount: results.length - failed.length,
    failedWindowCount: failed.length,
    profileId: contract.runtimeProfile.profileId,
    runtime: contract.runtimeProfile,
    gates: contract.gates,
    results,
  };
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  const protocol = verifyProtocol(JSON.parse(await fs.readFile(options.protocol, "utf8")));
  const contract = verifyContract(JSON.parse(await fs.readFile(options.contract, "utf8")));
  const report = await evaluateRuntimeProtocol(
    protocol,
    contract,
    (request) => requestRuntime(options, contract.runtimeProfile.endpoint, request),
    options.concurrency,
  );
  const output = JSON.stringify({ generatedAt: new Date().toISOString(), protocolHash: protocol.actualHash, ...report }, null, 2);
  if (options.out != null) {
    await fs.mkdir(path.dirname(options.out), { recursive: true });
    await fs.writeFile(options.out, output);
  }
  console.log(output);
  if (!report.passed) process.exitCode = 2;
}

async function requestRuntime(options, endpoint, request) {
  const response = await fetch(`${options.api.replace(/\/$/, "")}${endpoint}`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${options.token}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(request),
  });
  const body = await response.text();
  if (!response.ok) throw new Error(`HTTP ${response.status}: ${body.slice(0, 1_000)}`);
  return JSON.parse(body);
}

function failedWindow(window, reasons) {
  return {
    id: window.id,
    durationMonths: window.durationMonths,
    replayStartAt: window.replayStartAt,
    replayEndAt: window.replayEndAt,
    passed: reasons.length === 0,
    reasons,
  };
}

function hasRequestedCoverage(window, response) {
  const requestedStart = Date.parse(window.replayStartAt);
  const requestedEnd = Date.parse(window.replayEndAt);
  const actualStart = Date.parse(response.startAt);
  const actualEnd = Date.parse(response.endAt);
  return (
    Number.isFinite(actualStart) &&
    Number.isFinite(actualEnd) &&
    actualStart <= requestedStart + COVERAGE_TOLERANCE_MILLIS &&
    actualEnd >= requestedEnd - COVERAGE_TOLERANCE_MILLIS
  );
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
