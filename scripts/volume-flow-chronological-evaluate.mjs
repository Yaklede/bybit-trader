#!/usr/bin/env node

import { createHash } from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import { fileURLToPath } from "node:url";
import {
  buildRequest,
  evaluateWindow,
  parseArgs as parseSealedArgs,
} from "./volume-flow-sealed-evaluate.mjs";

const DEFAULT_PROTOCOL_PATH = "config/volume-flow-development-folds-v2.json";
const DEFAULT_STRATEGY_PATH = "config/volume-flow-composite-v2-bidirectional-hypothesis.json";

export function parseArgs(argv) {
  const base = parseSealedArgs(argv);
  const values = new Map(argv.map((argument) => argument.slice(2).split("=", 2)));
  return {
    ...base,
    protocol: path.resolve(values.get("protocol") ?? DEFAULT_PROTOCOL_PATH),
    strategy: path.resolve(values.get("strategy") ?? DEFAULT_STRATEGY_PATH),
  };
}

export function verifyDevelopmentProtocol(protocol) {
  if (protocol?.schemaVersion !== 1 || protocol?.status !== "DEVELOPMENT") {
    throw new Error("Development protocol must be schemaVersion=1 and status=DEVELOPMENT.");
  }
  if (protocol.selectionPolicy?.parameterSelectionAllowed !== true || protocol.selectionPolicy?.promotionAllowed !== false) {
    throw new Error("Development protocol must allow selection but prohibit promotion.");
  }
  if (!Array.isArray(protocol.folds) || protocol.folds.length < 3) {
    throw new Error("Development protocol must contain at least three chronological folds.");
  }
  const actualHash = sha256(protocol.folds);
  if (actualHash !== protocol.foldsSha256) {
    throw new Error(`Development fold hash mismatch. Expected ${protocol.foldsSha256}, got ${actualHash}.`);
  }
  const earliestAt = Date.parse(protocol.sourceData?.earliestAt);
  const developmentEndAt = Date.parse(protocol.sourceData?.developmentDataEndAt);
  if (!protocol.sourceData?.symbol || !Number.isFinite(earliestAt) || !Number.isFinite(developmentEndAt) || developmentEndAt <= earliestAt) {
    throw new Error("Development protocol has an invalid source-data range.");
  }
  const ids = new Set();
  let previousEndAt = earliestAt;
  for (const fold of protocol.folds) {
    const startAt = Date.parse(fold.replayStartAt);
    const endAt = Date.parse(fold.replayEndAt);
    if (!/^D\d{2}$/.test(fold.id) || ids.has(fold.id)) throw new Error("Development folds must use unique DNN ids.");
    if (fold.purpose !== "development" || !Number.isFinite(startAt) || !Number.isFinite(endAt) || endAt <= startAt) {
      throw new Error(`Development fold ${fold.id} is invalid.`);
    }
    if (startAt < earliestAt || endAt > developmentEndAt || startAt < previousEndAt) {
      throw new Error(`Development fold ${fold.id} is outside or before the chronological development range.`);
    }
    ids.add(fold.id);
    previousEndAt = endAt;
  }
  verifyGates(protocol.gates);
  return { ...protocol, actualHash };
}

export async function evaluateDevelopmentProtocol(protocol, strategy, request, concurrency = 1) {
  const results = await mapWithConcurrency(protocol.folds, concurrency, async (fold) => {
    const window = { ...fold, durationMonths: calendarMonthDifference(fold.replayStartAt, fold.replayEndAt) };
    const response = await request(buildRequest(strategy, window));
    return evaluateWindow(window, response, protocol.gates);
  });
  const failed = results.filter((result) => !result.passed);
  return {
    developmentOnly: true,
    protocolHash: protocol.actualHash,
    strategyHash: sha256(strategy),
    passed: failed.length === 0,
    foldCount: results.length,
    passedFoldCount: results.length - failed.length,
    failedFoldCount: failed.length,
    gates: protocol.gates,
    results,
  };
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  const protocol = verifyDevelopmentProtocol(JSON.parse(await fs.readFile(options.protocol, "utf8")));
  const strategy = JSON.parse(await fs.readFile(options.strategy, "utf8"));
  const report = await evaluateDevelopmentProtocol(
    protocol,
    strategy,
    (request) => requestComposite(options, request),
    options.concurrency,
  );
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
    headers: { Authorization: `Bearer ${options.token}`, "Content-Type": "application/json" },
    body: JSON.stringify(request),
  });
  const body = await response.text();
  if (!response.ok) throw new Error(`HTTP ${response.status}: ${body.slice(0, 1_000)}`);
  return JSON.parse(body);
}

function verifyGates(gates) {
  if (
    !Number.isFinite(gates?.minCompoundDailyReturnPct) ||
    !Number.isFinite(gates?.maxMarkToMarketDrawdownPct) ||
    !Number.isInteger(gates?.maxLiquidationCount) ||
    !Number.isInteger(gates?.minTradeCount) ||
    !Number.isFinite(gates?.minActiveDayCoveragePct) ||
    typeof gates?.requiredFillModelVersion !== "string"
  ) {
    throw new Error("Development protocol gates are incomplete.");
  }
}

function calendarMonthDifference(startAt, endAt) {
  const start = new Date(startAt);
  const end = new Date(endAt);
  return (end.getUTCFullYear() - start.getUTCFullYear()) * 12 + end.getUTCMonth() - start.getUTCMonth();
}

function sha256(value) {
  return createHash("sha256").update(JSON.stringify(value)).digest("hex");
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
