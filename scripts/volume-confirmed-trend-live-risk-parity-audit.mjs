#!/usr/bin/env node

import { createHash } from "node:crypto";
import { readFile, mkdir, rename, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { DatabaseSync } from "node:sqlite";
import {
  buildTrendCommands,
  canonicalInstantString,
  normalizeH4Evidence,
  simulateTrendRun,
  validateTrendProtocol,
} from "./lib/volume-confirmed-trend-research.mjs";
import { auditFrozenTrendRiskPolicy } from "./lib/volume-confirmed-trend-risk-parity.mjs";

export function parseArgs(argv) {
  const values = new Map();
  for (const argument of argv) {
    if (!argument.startsWith("--") || !argument.includes("=")) {
      throw new Error(`Invalid argument: ${argument}. Use --name=value.`);
    }
    const [name, ...rest] = argument.slice(2).split("=");
    values.set(name, rest.join("="));
  }
  const maximumDailyLossFraction = Number(values.get("maximum-daily-loss-fraction"));
  const maximumConsecutiveLosses = Number(values.get("maximum-consecutive-losses"));
  if (!Number.isFinite(maximumDailyLossFraction)) {
    throw new Error("--maximum-daily-loss-fraction is required.");
  }
  if (!Number.isInteger(maximumConsecutiveLosses)) {
    throw new Error("--maximum-consecutive-losses is required.");
  }
  return {
    protocol: resolve(values.get("protocol") ?? "config/volume-confirmed-trend-ensemble-v1.json"),
    externalResult: resolve(
      values.get("external-result") ?? "config/volume-confirmed-trend-ensemble-v1-external-result.json",
    ),
    db: resolve(values.get("db") ?? "build/research/binance-volume-confirmed-trend-external-v1.sqlite"),
    out: resolve(
      values.get("out") ?? "build/research/volume-confirmed-trend-live-risk-parity-audit.json",
    ),
    maximumDailyLossFraction,
    maximumConsecutiveLosses,
  };
}

export async function runRiskParityAudit(options) {
  const protocolBytes = await readFile(options.protocol);
  const protocol = validateTrendProtocol(JSON.parse(protocolBytes));
  const externalResult = JSON.parse(await readFile(options.externalResult, "utf8"));
  const databaseBytes = await readFile(options.db);
  const protocolSha256 = sha256(protocolBytes);
  const databaseSha256 = sha256(databaseBytes);
  requireEqual(protocolSha256, externalResult.protocol?.sha256, "protocol SHA-256");
  requireEqual(databaseSha256, externalResult.acquisitionEvidence?.databaseSha256, "database SHA-256");

  const db = new DatabaseSync(options.db, { readOnly: true });
  let bars;
  let fundingRates;
  try {
    const startInclusive = canonicalInstantString(protocol.externalEvidence.startInclusive);
    const endExclusive = canonicalInstantString(protocol.externalEvidence.endExclusive);
    bars = normalizeH4Evidence(db.prepare(`
      SELECT opened_at,open,high,low,close,volume
      FROM marketCandles
      WHERE symbol=? AND timeframe='H4' AND opened_at>=? AND opened_at<?
      ORDER BY opened_at
    `).all(protocol.market.symbol, startInclusive, endExclusive));
    fundingRates = db.prepare(`
      SELECT timestamp,funding_rate AS rate
      FROM fundingRates
      WHERE symbol=? AND timestamp>=? AND timestamp<?
      ORDER BY timestamp
    `).all(protocol.market.symbol, startInclusive, endExclusive);
  } finally {
    db.close();
  }

  const startingEquity = externalResult.canonicalMetrics?.startingEquityUsdt;
  if (!Number.isFinite(startingEquity) || startingEquity <= 0) {
    throw new Error("Canonical external starting equity is missing or invalid.");
  }
  const run = simulateTrendRun({
    bars,
    fundingRates,
    commands: buildTrendCommands(bars, protocol.strategy, protocol.market.warmupDecisionBars),
    protocol,
    startingEquity,
    costMultiplier: 1,
  });
  verifyCanonicalBaseline(run, externalResult.canonicalMetrics?.baseline);
  const audit = auditFrozenTrendRiskPolicy({
    run,
    maximumDailyLossFraction: options.maximumDailyLossFraction,
    maximumConsecutiveLosses: options.maximumConsecutiveLosses,
  });
  return {
    schemaVersion: 1,
    auditId: `${protocol.protocolId}-live-risk-parity-audit`,
    generatedAt: new Date().toISOString(),
    protocolId: protocol.protocolId,
    candidateId: protocol.candidateId,
    sourceEvidence: {
      protocolPath: options.protocol,
      protocolSha256,
      externalResultPath: options.externalResult,
      databasePath: options.db,
      databaseSha256,
    },
    runtimeRiskPolicy: {
      maximumDailyLossFraction: options.maximumDailyLossFraction,
      maximumConsecutiveLosses: options.maximumConsecutiveLosses,
    },
    canonicalBaseline: {
      startingEquityUsdt: run.startingEquityUsdt,
      endingEquityUsdt: run.endingEquityUsdt,
      netReturnPct: run.netReturnPct,
      closedTradeCount: run.closedTradeCount,
    },
    audit,
    decision: audit.frozenPathReproducible ? "RISK_POLICY_PARITY_CONFIRMED" : "BLOCK_LIVE_EXECUTION",
  };
}

function verifyCanonicalBaseline(run, baseline) {
  if (baseline == null) throw new Error("Canonical external baseline is missing.");
  requireClose(run.endingEquityUsdt, baseline.endingEquityUsdt, "ending equity");
  requireClose(run.netReturnPct, baseline.netReturnPct, "net return");
  requireEqual(run.closedTradeCount, baseline.closedTradeCount, "closed trade count");
}

function requireClose(actual, expected, label) {
  if (!Number.isFinite(actual) || !Number.isFinite(expected) || Math.abs(actual - expected) > 1e-8) {
    throw new Error(`Canonical ${label} mismatch: expected ${expected}, got ${actual}.`);
  }
}

function requireEqual(actual, expected, label) {
  if (actual !== expected) throw new Error(`Canonical ${label} mismatch: expected ${expected}, got ${actual}.`);
}

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

async function writeAtomically(path, payload) {
  await mkdir(dirname(path), { recursive: true });
  const temporary = `${path}.tmp`;
  await writeFile(temporary, payload);
  await rename(temporary, path);
}

const invokedPath = process.argv[1] ? pathToFileURL(resolve(process.argv[1])).href : null;
if (invokedPath === import.meta.url) {
  const options = parseArgs(process.argv.slice(2));
  const result = await runRiskParityAudit(options);
  const payload = `${JSON.stringify(result, null, 2)}\n`;
  await writeAtomically(options.out, payload);
  console.log(payload);
}
