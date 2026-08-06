#!/usr/bin/env node

import fs from "node:fs/promises";
import path from "node:path";

import {
  evaluateNestedWalkForward,
  expandCandidates,
  loadCandlesFromSqlite,
  runCandidateBatch,
  streamCandlesFromSqlite,
  validateDevelopmentProtocol,
} from "./lib/volume-impact-state-research.mjs";

const args = parseArgs(process.argv.slice(2));
const protocolPath = args.protocol ?? "config/volume-impact-state-development-v1.json";
const protocol = validateDevelopmentProtocol(JSON.parse(await fs.readFile(protocolPath, "utf8")));
const dbPath = args.db ?? protocol.sourceData.database;
const outDir = args.out ?? "build/research/volume-impact-state-development-v1";
const startAt = protocol.sourceData.warmupMayStartAt;
const endAt = protocol.sourceData.developmentReplayEndsAt;
const candidates = expandCandidates(protocol);

await fs.mkdir(outDir, { recursive: true });
console.error(`loading M5/M15 candidates=${candidates.length} developmentEnd=${endAt}`);
const m5Candles = loadCandlesFromSqlite({ dbPath, timeframe: "M5", startAt, endAt });
const m15Candles = loadCandlesFromSqlite({ dbPath, timeframe: "M15", startAt, endAt });
console.error(`loaded M5=${m5Candles.length} M15=${m15Candles.length}; streaming M1`);
const batch = await runCandidateBatch({
  m1Candles: streamCandlesFromSqlite({ dbPath, timeframe: "M1", startAt, endAt }),
  m5Candles,
  m15Candles,
  candidates,
  protocol,
});
const report = evaluateNestedWalkForward(batch, protocol);
const batchPath = path.join(outDir, "candidate-trades.json");
const reportPath = path.join(outDir, "nested-walk-forward.json");
await fs.writeFile(batchPath, `${JSON.stringify(batch, null, 2)}\n`);
await fs.writeFile(reportPath, `${JSON.stringify(report, null, 2)}\n`);

const summary = {
  protocolId: protocol.protocolId,
  status: report.status,
  candidateCount: batch.candidateCount,
  observedM1: batch.observedM1,
  reservedSealedWindowOpened: report.reservedSealedWindowOpened,
  automaticExecutionAllowed: false,
  families: report.familyReports.map((family) => ({
    family: family.family,
    status: family.status,
    positiveValidationFolds: family.positiveValidationFolds,
    pooledValidation: family.pooledValidation,
    frozenCandidateId: family.frozenCandidateId,
    failedChecks: Object.entries(family.gate.checks).filter(([, passed]) => !passed).map(([name]) => name),
  })),
  outputs: { batchPath, reportPath },
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
