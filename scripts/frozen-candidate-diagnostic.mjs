#!/usr/bin/env node

import fs from "node:fs/promises";
import path from "node:path";

import {
  evaluateFrozenDiagnosticRuns,
  loadCandlesFromSqlite,
  runCandidateBatch,
  scaledCostContract,
  streamCandlesFromSqlite,
  validateFrozenDiagnosticProtocol,
} from "./lib/volume-impact-state-research.mjs";

const args = parseArgs(process.argv.slice(2));
const protocolPath = args.protocol ?? "config/asymmetric-cluster-post2024-diagnostic-v1.json";
const protocol = validateFrozenDiagnosticProtocol(JSON.parse(await fs.readFile(protocolPath, "utf8")));
const dbPath = args.db ?? protocol.sourceData.database;
const outDir = args.out ?? "build/research/asymmetric-cluster-post2024-diagnostic-v1";
const startAt = protocol.sourceData.warmupMayStartAt;
const endAt = protocol.sourceData.diagnosticReplayEndsAt;
await fs.mkdir(outDir, { recursive: true });

console.error(`loading diagnostic M5/M15 candidate=${protocol.candidate.id} end=${endAt}`);
const m5Candles = loadCandlesFromSqlite({ dbPath, timeframe: "M5", startAt, endAt });
const m15Candles = loadCandlesFromSqlite({ dbPath, timeframe: "M15", startAt, endAt });
const runs = [];
for (const costMultiplier of protocol.costMultipliers) {
  console.error(`streaming diagnostic M1 costMultiplier=${costMultiplier}`);
  const batch = await runCandidateBatch({
    m1Candles: streamCandlesFromSqlite({ dbPath, timeframe: "M1", startAt, endAt }),
    m5Candles,
    m15Candles,
    candidates: [protocol.candidate],
    protocol,
    executionContract: scaledCostContract(protocol, costMultiplier),
  });
  runs.push({ costMultiplier, candidateResult: batch.candidates[0], observedM1: batch.observedM1 });
}

const report = evaluateFrozenDiagnosticRuns(runs, protocol);
const payload = {
  ...report,
  independence: protocol.independence,
  runs,
};
const reportPath = path.join(outDir, "diagnostic.json");
await fs.writeFile(reportPath, `${JSON.stringify(payload, null, 2)}\n`);
const summary = {
  protocolId: protocol.protocolId,
  candidateId: protocol.candidate.id,
  status: report.status,
  independence: protocol.independence,
  gate: report.gate,
  costs: report.costReports.map((cost) => ({
    costMultiplier: cost.costMultiplier,
    positiveWindowCount: cost.positiveWindowCount,
    pooled: cost.pooled,
  })),
  reservedSealedWindowOpened: false,
  automaticExecutionAllowed: false,
  reportPath,
};
await fs.writeFile(path.join(outDir, "summary.json"), `${JSON.stringify(summary, null, 2)}\n`);
console.log(JSON.stringify(summary, null, 2));

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
