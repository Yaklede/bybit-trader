#!/usr/bin/env node

import fs from "node:fs/promises";
import path from "node:path";
import { execFileSync } from "node:child_process";

const args = parseArgs(process.argv.slice(2));
const dbPath = args.db ?? "build/runtime-test/bybit-trader-full-history.sqlite";
const windowsPath = args.windows ?? "config/multi-horizon-momentum-parity-window-v2.json";
const outDir = args.out ?? "build/multi-horizon-momentum-parity-v2";
const candidateId = args.candidateId ?? "multi_momentum_scale0.75_votes3_stop8_trail16_long_only";

const windows = JSON.parse(await fs.readFile(windowsPath, "utf8"));
if (!Array.isArray(windows) || windows.length !== 1) {
  throw new Error(`Parity window file must contain exactly one window: ${windowsPath}`);
}
const window = windows[0];
if (window.historyStartAt == null) throw new Error("Parity window must declare historyStartAt.");

await fs.mkdir(outDir, { recursive: true });
const nodeTracePath = path.join(outDir, "node-trace.json");
const kotlinTracePath = path.join(outDir, "kotlin-trace.json");
const reportPath = path.join(outDir, "parity-report.json");

execFileSync(
  process.execPath,
  [
    "scripts/volume-flow-feature-discovery.mjs",
    "--db", dbPath,
    "--windows", windowsPath,
    "--out", outDir,
    "--profile", "multi-horizon-momentum",
    "--traceCandidateId", candidateId,
    "--traceWindowId", window.id,
    "--traceOut", nodeTracePath,
    "--historyStartAt", window.historyStartAt,
    "--quiet", "true",
  ],
  { encoding: "utf8", maxBuffer: 256 * 1024 * 1024 },
);

const kotlinArgs = [
  "--db", path.resolve(dbPath),
  "--start", window.replayStartAt,
  "--end", window.replayEndAt,
  "--historyStart", window.historyStartAt,
  "--windowId", window.id,
  "--out", path.resolve(kotlinTracePath),
].join(" ");
execFileSync(
  "./gradlew",
  [":modules:bot-app:runMultiHorizonParity", `--args=${kotlinArgs}`],
  { encoding: "utf8", maxBuffer: 256 * 1024 * 1024 },
);

const nodePayload = JSON.parse(await fs.readFile(nodeTracePath, "utf8"));
const kotlinPayload = JSON.parse(await fs.readFile(kotlinTracePath, "utf8"));
const nodeReport = nodePayload.reports[0];
const kotlinReport = kotlinPayload.report;
const mismatches = [];

compareExact(mismatches, "window.id", nodeReport.id, kotlinReport.id);
compareExact(mismatches, "window.replayStartAt", nodeReport.replayStartAt, kotlinReport.replayStartAt);
compareExact(mismatches, "window.replayEndAt", nodeReport.replayEndAt, kotlinReport.replayEndAt);
compareExact(mismatches, "tradeCount", nodeReport.tradeCount, kotlinReport.tradeCount);
compareNumber(mismatches, "finalEquity", nodeReport.finalEquity, kotlinReport.finalEquity);
compareNumber(mismatches, "netReturnPct", nodeReport.netReturnPct, kotlinReport.netReturnPct);
compareNumber(mismatches, "drawdownPct", nodeReport.drawdownPct, kotlinReport.drawdownPct);

const comparedTrades = Math.min(nodeReport.trades.length, kotlinReport.trades.length);
for (let index = 0; index < comparedTrades; index += 1) {
  const nodeTrade = nodeReport.trades[index];
  const kotlinTrade = kotlinReport.trades[index];
  const prefix = `trades[${index}]`;
  for (const field of ["signalAt", "openedAt", "closedAt", "side", "exitReason"]) {
    compareExact(mismatches, `${prefix}.${field}`, nodeTrade[field], kotlinTrade[field]);
  }
  for (const field of [
    "entryPrice",
    "stopPrice",
    "exitTriggerPrice",
    "exitPrice",
    "riskPerUnit",
    "riskFraction",
    "stopAtr",
    "targetR",
    "rMultipleGross",
    "rMultipleNet",
    "pnl",
    "equityAfter",
  ]) {
    compareNumber(mismatches, `${prefix}.${field}`, nodeTrade[field], kotlinTrade[field]);
  }
  compareNullableNumber(mismatches, `${prefix}.targetPrice`, nodeTrade.targetPrice, kotlinTrade.targetPrice);
}

const report = {
  status: mismatches.length === 0 ? "PASS" : "FAIL",
  candidateId,
  profileId: kotlinPayload.profileId,
  executionContract: kotlinPayload.executionContract,
  window,
  nodeTradeCount: nodeReport.tradeCount,
  kotlinTradeCount: kotlinReport.tradeCount,
  comparedTrades,
  numericTolerance: 0.00001,
  mismatches,
};
await fs.writeFile(reportPath, JSON.stringify(report, null, 2));
console.log(JSON.stringify(report, null, 2));
if (mismatches.length > 0) process.exitCode = 1;

function parseArgs(items) {
  const parsed = {};
  for (let index = 0; index < items.length; index += 1) {
    const token = items[index];
    if (!token.startsWith("--")) throw new Error(`Unexpected argument: ${token}`);
    const value = items[index + 1];
    if (value == null || value.startsWith("--")) throw new Error(`Missing value for ${token}`);
    parsed[token.slice(2)] = value;
    index += 1;
  }
  return parsed;
}

function compareExact(mismatches, field, expected, actual) {
  if (expected !== actual) mismatches.push({ field, expected, actual });
}

function compareNumber(mismatches, field, expected, actual) {
  if (!Number.isFinite(expected) || !Number.isFinite(actual) || Math.abs(expected - actual) > 0.00001) {
    mismatches.push({ field, expected, actual });
  }
}

function compareNullableNumber(mismatches, field, expected, actual) {
  if (expected == null || actual == null) {
    compareExact(mismatches, field, expected, actual);
    return;
  }
  compareNumber(mismatches, field, expected, actual);
}
