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
import { compareVolumeConfirmedTrendParity } from "./lib/volume-confirmed-trend-parity.mjs";
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
  const maximumDailyLossFraction = optionalRiskLimit(
    values.get("maximum-daily-loss-fraction"),
    "--maximum-daily-loss-fraction",
  );
  const maximumAccountDrawdownFraction = Number(values.get("maximum-account-drawdown-fraction"));
  const maximumConsecutiveLosses = optionalRiskLimit(
    values.get("maximum-consecutive-losses"),
    "--maximum-consecutive-losses",
  );
  const riskStateMaximumAgeSeconds = Number(values.get("risk-state-maximum-age-seconds"));
  const walletReconciliationMaximumAgeSeconds = Number(
    values.get("wallet-reconciliation-maximum-age-seconds"),
  );
  const walletReconciliationConfirmedMismatchCount = Number(
    values.get("wallet-reconciliation-confirmed-mismatch-count"),
  );
  if (maximumConsecutiveLosses != null && !Number.isInteger(maximumConsecutiveLosses)) {
    throw new Error("--maximum-consecutive-losses must be disabled or an integer.");
  }
  if (!Number.isFinite(maximumAccountDrawdownFraction)) {
    throw new Error("--maximum-account-drawdown-fraction is required.");
  }
  if (!Number.isInteger(riskStateMaximumAgeSeconds)) {
    throw new Error("--risk-state-maximum-age-seconds is required.");
  }
  if (!Number.isInteger(walletReconciliationMaximumAgeSeconds)) {
    throw new Error("--wallet-reconciliation-maximum-age-seconds is required.");
  }
  if (!Number.isInteger(walletReconciliationConfirmedMismatchCount)) {
    throw new Error("--wallet-reconciliation-confirmed-mismatch-count is required.");
  }
  if (maximumAccountDrawdownFraction <= 0 || maximumAccountDrawdownFraction > 1) {
    throw new Error("--maximum-account-drawdown-fraction must be in (0, 1].");
  }
  if (riskStateMaximumAgeSeconds <= 0 || walletReconciliationMaximumAgeSeconds <= 0) {
    throw new Error("Risk and wallet maximum ages must be positive seconds.");
  }
  if (walletReconciliationConfirmedMismatchCount <= 0) {
    throw new Error("--wallet-reconciliation-confirmed-mismatch-count must be positive.");
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
    nodeRiskParity: resolve(
      values.get("node-risk-parity") ?? "build/research/volume-confirmed-trend-node-risk-parity.json",
    ),
    kotlinRiskParity: resolve(
      values.get("kotlin-risk-parity") ?? "build/research/volume-confirmed-trend-kotlin-risk-parity.json",
    ),
    maximumDailyLossFraction,
    maximumAccountDrawdownFraction,
    maximumConsecutiveLosses,
    riskStateMaximumAgeSeconds,
    walletReconciliationMaximumAgeSeconds,
    walletReconciliationConfirmedMismatchCount,
  };
}

function optionalRiskLimit(value, name) {
  if (value == null) throw new Error(`${name} is required.`);
  return value === "disabled" ? null : Number(value);
}

export async function runRiskParityAudit(options) {
  const protocolBytes = await readFile(options.protocol);
  const protocol = validateTrendProtocol(JSON.parse(protocolBytes));
  const externalResultBytes = await readFile(options.externalResult);
  const externalResult = JSON.parse(externalResultBytes.toString("utf8"));
  const databaseBytes = await readFile(options.db);
  const nodeRiskParityBytes = await readFile(options.nodeRiskParity);
  const kotlinRiskParityBytes = await readFile(options.kotlinRiskParity);
  const nodeRiskParity = JSON.parse(nodeRiskParityBytes.toString("utf8"));
  const kotlinRiskParity = JSON.parse(kotlinRiskParityBytes.toString("utf8"));
  const protocolSha256 = sha256(protocolBytes);
  const externalResultSha256 = sha256(externalResultBytes);
  const databaseSha256 = sha256(databaseBytes);
  requireEqual(protocolSha256, externalResult.protocol?.sha256, "protocol SHA-256");
  requireEqual(databaseSha256, externalResult.acquisitionEvidence?.databaseSha256, "database SHA-256");
  requireEqual(protocolSha256, nodeRiskParity.protocolSha256, "Node risk parity protocol SHA-256");
  const parityMismatches = compareVolumeConfirmedTrendParity(nodeRiskParity, kotlinRiskParity);
  if (parityMismatches.length > 0) {
    throw new Error(`Volume-confirmed trend risk replay parity failed:\n${parityMismatches.slice(0, 50).join("\n")}`);
  }

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
  const commands = buildTrendCommands(bars, protocol.strategy, protocol.market.warmupDecisionBars);
  const run = simulateTrendRun({
    bars,
    fundingRates,
    commands,
    protocol,
    startingEquity,
    costMultiplier: 1,
  });
  verifyCanonicalBaseline(run, externalResult.canonicalMetrics?.baseline);
  const audit = auditFrozenTrendRiskPolicy({
    run,
    maximumDailyLossFraction: options.maximumDailyLossFraction,
    maximumAccountDrawdownFraction: options.maximumAccountDrawdownFraction,
    maximumConsecutiveLosses: options.maximumConsecutiveLosses,
  });
  const riskPolicy = {
    maximumDailyLossFraction: options.maximumDailyLossFraction,
    maximumAccountDrawdownFraction: options.maximumAccountDrawdownFraction,
    maximumConsecutiveLosses: options.maximumConsecutiveLosses,
  };
  for (const candidate of nodeRiskParity.runs) {
    const policyMismatches = compareVolumeConfirmedTrendParity(riskPolicy, candidate.riskPolicyReplay?.policy);
    if (policyMismatches.length > 0) {
      throw new Error(`Risk parity run policy mismatch:\n${policyMismatches.join("\n")}`);
    }
  }
  const policyRun = simulateTrendRun({
    bars,
    fundingRates,
    commands,
    protocol,
    startingEquity,
    costMultiplier: 1,
    riskPolicy,
  });
  const canonicalParityRun = requireParityRun(nodeRiskParity.runs, String(startingEquity), "1");
  verifyRiskPolicyReplay(policyRun, canonicalParityRun);
  const policyReplay = {
    simulationKind: "H4_DECISION_BOUNDARY_RISK_POLICY_REPLAY",
    livePathSimulation: false,
    crossRuntimeParity: {
      status: "PASS",
      nodeResultSha256: sha256(nodeRiskParityBytes),
      kotlinResultSha256: sha256(kotlinRiskParityBytes),
      numericTolerance: 1e-8,
      commandCount: nodeRiskParity.commands.length,
      runCount: nodeRiskParity.runs.length,
      tradeCount: nodeRiskParity.runs.reduce((total, candidate) => total + candidate.trades.length, 0),
    },
    canonical: compactRiskReplayRun(canonicalParityRun),
    stressMatrix: nodeRiskParity.runs.map(compactRiskReplayRun),
    limitation:
      "The replay applies the exact threshold reason codes at causal H4 entry boundaries and is cross-runtime deterministic. Live wallet snapshots can observe additional intrabar equity states, so this is a conservative execution-contract replay rather than a prediction of exchange fills.",
  };
  return {
    schemaVersion: 2,
    resultId: `${protocol.protocolId}-live-risk-parity-result`,
    protocol: {
      id: protocol.protocolId,
      candidateId: protocol.candidateId,
      sha256: protocolSha256,
    },
    sourceEvidence: {
      externalResultSha256,
      databaseSha256,
    },
    runtimeRiskPolicy: {
      maximumDailyLossFraction: options.maximumDailyLossFraction,
      maximumAccountDrawdownFraction: options.maximumAccountDrawdownFraction,
      maximumConsecutiveLosses: options.maximumConsecutiveLosses,
      riskStateMaximumAgeSeconds: options.riskStateMaximumAgeSeconds,
      walletReconciliationMaximumAgeSeconds: options.walletReconciliationMaximumAgeSeconds,
      walletReconciliationConfirmedMismatchCount: options.walletReconciliationConfirmedMismatchCount,
    },
    canonicalBaseline: {
      startingEquityUsdt: run.startingEquityUsdt,
      endingEquityUsdt: run.endingEquityUsdt,
      netReturnPct: run.netReturnPct,
      closedTradeCount: run.closedTradeCount,
    },
    audit,
    policyReplay,
    status: audit.frozenPathReproducible ? "PASS" : "FAIL",
    decision: {
      riskPolicyParityPassed: audit.frozenPathReproducible,
      automaticExecutionAllowed: false,
      liveExecutionAllowed: false,
      reasonCodes: audit.reasonCodes,
    },
  };
}

function requireParityRun(runs, startingEquityUsdt, costMultiplier) {
  const run = runs.find((candidate) =>
    candidate.startingEquityUsdt === startingEquityUsdt && candidate.costMultiplier === costMultiplier);
  if (run == null) {
    throw new Error(`Missing risk parity run equity=${startingEquityUsdt} cost=${costMultiplier}.`);
  }
  return run;
}

function verifyRiskPolicyReplay(run, parityRun) {
  const expected = {
    endingEquityUsdt: run.endingEquityUsdt,
    netReturnPct: run.netReturnPct,
    compoundDailyReturnPct: run.compoundDailyReturnPct,
    maximumConservativeIntrabarDrawdownPct: run.maximumConservativeIntrabarDrawdownPct,
    closedTradeCount: run.trades.length,
    riskPolicyReplay: run.riskPolicyReplay,
  };
  const actual = {
    endingEquityUsdt: parityRun.endingEquityUsdt,
    netReturnPct: parityRun.netReturnPct,
    compoundDailyReturnPct: parityRun.compoundDailyReturnPct,
    maximumConservativeIntrabarDrawdownPct: parityRun.maximumConservativeIntrabarDrawdownPct,
    closedTradeCount: parityRun.trades.length,
    riskPolicyReplay: parityRun.riskPolicyReplay,
  };
  const mismatches = compareVolumeConfirmedTrendParity(expected, actual);
  if (mismatches.length > 0) {
    throw new Error(`Canonical risk replay mismatch:\n${mismatches.join("\n")}`);
  }
}

function compactRiskReplayRun(run) {
  if (run.riskPolicyReplay == null) throw new Error("Risk parity run has no policy replay evidence.");
  return {
    startingEquityUsdt: Number(run.startingEquityUsdt),
    costMultiplier: Number(run.costMultiplier),
    endingEquityUsdt: run.endingEquityUsdt,
    netReturnPct: run.netReturnPct,
    compoundDailyReturnPct: run.compoundDailyReturnPct,
    maximumConservativeIntrabarDrawdownPct: run.maximumConservativeIntrabarDrawdownPct,
    closedTradeCount: run.trades.length,
    blockedEntryCount: run.riskPolicyReplay.blockedEntryCount,
    blockedEntryReasonCounts: run.riskPolicyReplay.blockedEntryReasonCounts,
    firstBlockedEntry: run.riskPolicyReplay.firstBlockedEntry,
    maximumObservedConsecutiveLosses: run.riskPolicyReplay.maximumObservedConsecutiveLosses,
    finalConsecutiveLosses: run.riskPolicyReplay.finalConsecutiveLosses,
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
