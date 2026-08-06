#!/usr/bin/env node

import { createHash } from "node:crypto";
import { createReadStream, rmSync } from "node:fs";
import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { DatabaseSync } from "node:sqlite";
import { dirname, resolve } from "node:path";
import { pathToFileURL } from "node:url";

import {
  normalizedMultiAssetEvidenceFingerprint,
} from "./multi-asset-delta-neutral-funding-carry-acquire.mjs";
import {
  loadFundingRatesBySymbol,
  loadPortfolioFrames,
} from "./multi-asset-delta-neutral-funding-carry-development-replay.mjs";
import {
  expandMultiAssetCostRecoveryCarryCandidates,
  loadMultiAssetCostRecoveryCarryProtocol,
} from "./multi-asset-cost-recovery-carry-protocol.mjs";
import {
  simulateMultiAssetFundingCarryCandidate,
} from "./lib/multi-asset-delta-neutral-funding-carry-research.mjs";

const DEFAULT_FREEZE = "config/bybit-multi-asset-cost-recovery-carry-replay-freeze-v3.json";

export function parseArgs(argv) {
  const values = new Map();
  for (const argument of argv) {
    if (!argument.startsWith("--") || !argument.includes("=")) {
      throw new Error(`Invalid argument: ${argument}. Use --name=value.`);
    }
    const [name, ...rest] = argument.slice(2).split("=");
    if (!["freeze", "report", "ranked", "trades"].includes(name)) {
      throw new Error(`Unsupported argument: --${name}.`);
    }
    if (values.has(name)) throw new Error(`Duplicate argument: --${name}.`);
    values.set(name, rest.join("="));
  }
  return {
    freeze: resolve(values.get("freeze") ?? DEFAULT_FREEZE),
    report: values.has("report") ? resolve(values.get("report")) : null,
    ranked: values.has("ranked") ? resolve(values.get("ranked")) : null,
    trades: values.has("trades") ? resolve(values.get("trades")) : null,
  };
}

export async function replayMultiAssetCostRecoveryCarry(options) {
  const freeze = await loadCostRecoveryReplayFreeze(options.freeze);
  const repositoryRoot = resolve(dirname(options.freeze), "..");
  const loaded = await loadMultiAssetCostRecoveryCarryProtocol(
    resolve(repositoryRoot, freeze.manifest.protocol.path),
  );
  if (loaded.sha256 !== freeze.manifest.protocol.sha256) {
    throw new Error("Cost-recovery replay protocol differs from the frozen manifest.");
  }
  const baseProtocolBytes = await readFile(resolve(
    repositoryRoot,
    loaded.protocol.baseDevelopmentProtocol.path,
  ));
  const baseProtocol = JSON.parse(baseProtocolBytes);
  if (sha256(baseProtocolBytes) !== loaded.baseProtocolSha256) {
    throw new Error("Cost-recovery base protocol changed.");
  }
  const evidenceByYear = {};
  for (const evidence of loaded.protocol.evidence) {
    evidenceByYear[evidence.year] = await loadYearEvidence(
      repositoryRoot,
      evidence,
      buildYearEvaluationProtocol(loaded.protocol, baseProtocol, evidence),
    );
  }

  const candidates = expandMultiAssetCostRecoveryCarryCandidates(loaded.protocol);
  const evaluations = [];
  for (const candidate of candidates) {
    const annual = [];
    for (const evidence of loaded.protocol.evidence) {
      const yearEvidence = evidenceByYear[evidence.year];
      annual.push({
        year: evidence.year,
        metrics: simulateMultiAssetFundingCarryCandidate({
          candidate,
          framesBySymbol: yearEvidence.framesBySymbol,
          fundingRatesBySymbol: yearEvidence.fundingRatesBySymbol,
          protocol: yearEvidence.evaluationProtocol,
        }),
      });
    }
    const summary = summarizeAnnualMetrics(annual);
    const gate = evaluateCostRecoveryDevelopmentGate(
      { ...summary, annual },
      loaded.protocol.developmentGate,
    );
    evaluations.push({ candidate, annual, summary, gate });
  }
  const ranked = rankCostRecoveryEvaluations(evaluations);
  const passed = ranked.filter((row) => row.gate.passed);
  const selected = passed.slice(0, loaded.protocol.selectionPolicy.maximumSelectedCandidateCount);
  const best = ranked[0] ?? null;
  const reportPath = options.report ?? resolve(
    repositoryRoot,
    `build/research/${loaded.protocol.protocolId}-result.json`,
  );
  const rankedPath = options.ranked ?? resolve(
    repositoryRoot,
    `build/research/${loaded.protocol.protocolId}-ranked-candidates.json`,
  );
  const tradesPath = options.trades ?? resolve(
    repositoryRoot,
    `build/research/${loaded.protocol.protocolId}-best-trades.json`,
  );
  const rankedArtifact = ranked.map((evaluation, index) => ({
    rank: index + 1,
    candidate: evaluation.candidate,
    candidateSha256: sha256(JSON.stringify(evaluation.candidate)),
    annual: evaluation.annual.map((row) => ({ year: row.year, metrics: withoutTrades(row.metrics) })),
    summary: evaluation.summary,
    gate: evaluation.gate,
  }));
  const tradesArtifact = {
    schemaVersion: 1,
    protocolId: loaded.protocol.protocolId,
    candidateId: best?.candidate.id ?? null,
    candidateSha256: best == null ? null : sha256(JSON.stringify(best.candidate)),
    annual: best?.annual.map((row) => ({ year: row.year, trades: row.metrics.trades })) ?? [],
  };
  await writeJsonAtomic(rankedPath, rankedArtifact);
  await writeJsonAtomic(tradesPath, tradesArtifact);
  const rankedCandidatesSha256 = await sha256File(rankedPath);
  const bestCandidateTradesSha256 = await sha256File(tradesPath);
  const report = {
    schemaVersion: 1,
    protocolId: loaded.protocol.protocolId,
    protocolSha256: loaded.sha256,
    replayFreezeSha256: freeze.sha256,
    parentExternalResultSha256: loaded.parentResultSha256,
    implementation: {
      replaySha256: freeze.manifest.implementation.replaySha256,
      simulatorSha256: freeze.manifest.implementation.simulatorSha256,
      compositeSha256: freeze.compositeImplementationSha256,
    },
    status: selected.length === 1
      ? "COST_RECOVERY_CARRY_DEVELOPMENT_CANDIDATE_FROZEN_FOR_2026_SEAL"
      : "REJECTED_NO_COST_RECOVERY_CARRY_DEVELOPMENT_CANDIDATE",
    trialAccounting: {
      priorObservedCandidatesAndProtocols:
        loaded.protocol.trialAccounting.priorObservedCandidatesAndProtocols,
      evaluatedCandidates: candidates.length,
      cumulativeObservedCandidatesAndProtocols:
        loaded.protocol.trialAccounting.cumulativeObservedCandidatesAndProtocolsAfterReplay,
      passedCandidates: passed.length,
    },
    evidence: Object.fromEntries(loaded.protocol.evidence.map((entry) => [entry.year, {
      receiptSha256: entry.receiptSha256,
      snapshotSha256: entry.snapshotSha256,
      frameCountBySymbol: Object.fromEntries(
        baseProtocol.sourceData.symbols.map(
          (symbol) => [symbol, evidenceByYear[entry.year].framesBySymbol[symbol].length],
        ),
      ),
      fundingSettlementCountBySymbol: Object.fromEntries(
        baseProtocol.sourceData.symbols.map(
          (symbol) => [symbol, evidenceByYear[entry.year].fundingRatesBySymbol[symbol].length],
        ),
      ),
    }])),
    bestCandidate: best == null ? null : {
      candidate: best.candidate,
      candidateSha256: sha256(JSON.stringify(best.candidate)),
      annual: best.annual.map((row) => ({ year: row.year, metrics: withoutTrades(row.metrics) })),
      summary: best.summary,
      gate: best.gate,
    },
    selectedCandidateIds: selected.map((row) => row.candidate.id),
    selectedCandidateSha256: selected.length === 1
      ? sha256(JSON.stringify(selected[0].candidate))
      : null,
    rankedCandidatesSha256,
    bestCandidateTradesSha256,
    evidenceBoundary: {
      development2023Through2025Read: true,
      sealed2026Read: false,
      freshForwardSealRead: false,
    },
    sealed2026AcquisitionAllowed: selected.length === 1,
    automaticExecutionAllowed: false,
    liveExecutionAllowed: false,
  };
  await writeJsonAtomic(reportPath, report);
  return { report, reportPath, rankedPath, tradesPath };
}

export function buildYearEvaluationProtocol(v3Protocol, baseProtocol, evidence) {
  return {
    ...baseProtocol,
    protocolId: `${v3Protocol.protocolId}-${evidence.year}`,
    sourceData: {
      ...baseProtocol.sourceData,
      developmentStart: evidence.startAt,
      developmentEndExclusive: evidence.endExclusive,
    },
    evidenceSchedule: {
      ...baseProtocol.evidenceSchedule,
      developmentBlocks: evidence.blocks,
    },
    statistics: {
      ...baseProtocol.statistics,
      randomSeed: `${v3Protocol.protocolId}|${evidence.year}`,
    },
  };
}

export function summarizeAnnualMetrics(annual) {
  const metrics = annual.map((row) => row.metrics);
  return {
    totalClosedPositions: metrics.reduce((sum, row) => sum + row.tradeCount, 0),
    totalPositiveQuarterCount: metrics.reduce((sum, row) => sum + row.positiveBlockCount, 0),
    threeYearCompoundedReturnPct: round8(
      (metrics.reduce((value, row) => value * (1 + row.netReturnPct / 100), 1) - 1) * 100,
    ),
    worstYearNetReturnPct: Math.min(...metrics.map((row) => row.netReturnPct)),
    worstYearCostStressNetReturnPct: Math.min(...metrics.map((row) => row.costStressNetReturnPct)),
    worstYearSecondLegDelayStressNetReturnPct: Math.min(
      ...metrics.map((row) => row.secondLegDelayStressNetReturnPct),
    ),
    maximumAnnualDrawdownPct: Math.max(...metrics.map((row) => row.maximumDrawdownPct)),
    totalLiquidationCount: metrics.reduce((sum, row) => sum + row.liquidationCount, 0),
  };
}

export function evaluateCostRecoveryDevelopmentGate(summary, gate) {
  const annual = summary.annual ?? [];
  const checks = {
    minimumTotalClosedPositions: summary.totalClosedPositions >= gate.minimumTotalClosedPositions,
    minimumClosedPositionsPerYear:
      annual.every((row) => row.metrics.tradeCount >= gate.minimumClosedPositionsPerYear),
    minimumTradedAssetCountPerYear:
      annual.every((row) => row.metrics.tradedAssetCount >= gate.minimumTradedAssetCountPerYear),
    minimumPositiveAssetCountPerYear:
      annual.every((row) => row.metrics.positiveAssetCount >= gate.minimumPositiveAssetCountPerYear),
    minimumPositiveQuarterCountPerYear:
      annual.every((row) => row.metrics.positiveBlockCount >= gate.minimumPositiveQuarterCountPerYear),
    minimumTotalPositiveQuarterCount:
      summary.totalPositiveQuarterCount >= gate.minimumTotalPositiveQuarterCount,
    minimumNetReturnPctPerYear:
      annual.every((row) => row.metrics.netReturnPct > gate.minimumNetReturnPctPerYear),
    minimumProfitFactorPerYear:
      annual.every((row) => row.metrics.profitFactor >= gate.minimumProfitFactorPerYear),
    minimumMeanDailyReturnPctPerYear:
      annual.every((row) => row.metrics.meanDailyReturnPct > gate.minimumMeanDailyReturnPctPerYear),
    minimumBootstrapLowerMeanDailyReturnPctPerYear: annual.every(
      (row) => row.metrics.bootstrapLowerMeanDailyReturnPct >
        gate.minimumBootstrapLowerMeanDailyReturnPctPerYear,
    ),
    maximumDrawdownPctPerYear:
      annual.every((row) => row.metrics.maximumDrawdownPct <= gate.maximumDrawdownPctPerYear),
    maximumLiquidationCount: summary.totalLiquidationCount <= gate.maximumLiquidationCount,
    maximumPositivePositionProfitConcentrationPerYear: annual.every(
      (row) => row.metrics.positivePositionProfitConcentration <=
        gate.maximumPositivePositionProfitConcentrationPerYear,
    ),
    maximumPositiveAssetProfitConcentrationPerYear: annual.every(
      (row) => row.metrics.positiveAssetProfitConcentration <=
        gate.maximumPositiveAssetProfitConcentrationPerYear,
    ),
    maximumNetHedgeMismatchBySymbol: annual.every((row) =>
      Object.entries(gate.maximumNetHedgeMismatchBySymbol).every(
        ([symbol, limit]) => (row.metrics.maximumNetHedgeMismatchBySymbol[symbol] ?? Infinity) <= limit,
      )),
    minimumCostStressNetReturnPctPerYear: annual.every(
      (row) => row.metrics.costStressNetReturnPct > gate.minimumCostStressNetReturnPctPerYear,
    ),
    minimumSecondLegDelayStressNetReturnPctPerYear: annual.every(
      (row) => row.metrics.secondLegDelayStressNetReturnPct >
        gate.minimumSecondLegDelayStressNetReturnPctPerYear,
    ),
  };
  return {
    passed: Object.values(checks).every(Boolean),
    checks,
    failedChecks: Object.entries(checks).filter(([, passed]) => !passed).map(([name]) => name),
  };
}

export function rankCostRecoveryEvaluations(evaluations) {
  return [...evaluations].sort((left, right) => {
    if (left.gate.passed !== right.gate.passed) return left.gate.passed ? -1 : 1;
    if (left.gate.failedChecks.length !== right.gate.failedChecks.length) {
      return left.gate.failedChecks.length - right.gate.failedChecks.length;
    }
    for (const metric of [
      "worstYearCostStressNetReturnPct",
      "worstYearSecondLegDelayStressNetReturnPct",
      "worstYearNetReturnPct",
      "threeYearCompoundedReturnPct",
    ]) {
      if (left.summary[metric] !== right.summary[metric]) {
        return right.summary[metric] - left.summary[metric];
      }
    }
    return left.candidate.id.localeCompare(right.candidate.id);
  });
}

export async function loadCostRecoveryReplayFreeze(path) {
  const bytes = await readFile(path);
  const manifest = JSON.parse(bytes);
  const root = resolve(dirname(path), "..");
  const actual = {
    protocolSha256: await sha256File(resolve(root, manifest.protocol.path)),
    simulatorSha256: await sha256File(resolve(root, manifest.implementation.simulatorPath)),
    replaySha256: await sha256File(resolve(root, manifest.implementation.replayPath)),
  };
  validateCostRecoveryReplayFreeze(manifest, actual);
  const compositeImplementationSha256 = sha256(
    `${actual.simulatorSha256}\0${actual.replaySha256}`,
  );
  if (manifest.implementation.compositeSha256 !== compositeImplementationSha256) {
    throw new Error("Cost-recovery replay composite implementation hash changed.");
  }
  return { manifest, sha256: sha256(bytes), compositeImplementationSha256 };
}

export function validateCostRecoveryReplayFreeze(manifest, actual) {
  if (manifest?.freezeId !== "bybit-multi-asset-cost-recovery-carry-replay-freeze-v3" ||
      manifest.status !== "FROZEN_BEFORE_COST_RECOVERY_DEVELOPMENT_REPLAY" ||
      manifest.outcomeBoundary?.developmentGridMetricsReadBeforeFreeze !== false ||
      manifest.outcomeBoundary.sealed2026Read !== false ||
      manifest.outcomeBoundary.freshForwardSealRead !== false ||
      manifest.trialAccounting?.frozenCandidatesToEvaluate !== 54 ||
      manifest.selectionPolicy?.maximumSelectedCandidateCount !== 1 ||
      manifest.selectionPolicy.candidateMayBeRetunedAfterOutcome !== false ||
      manifest.automaticExecutionAllowed !== false || manifest.liveExecutionAllowed !== false) {
    throw new Error("Cost-recovery replay freeze boundary changed.");
  }
  if (manifest.protocol?.sha256 !== actual.protocolSha256 ||
      manifest.implementation?.simulatorSha256 !== actual.simulatorSha256 ||
      manifest.implementation?.replaySha256 !== actual.replaySha256) {
    throw new Error("Cost-recovery replay freeze hashes changed.");
  }
  return manifest;
}

async function loadYearEvidence(repositoryRoot, evidence, evaluationProtocol) {
  const receiptBytes = await readFile(resolve(repositoryRoot, evidence.receiptPath));
  if (sha256(receiptBytes) !== evidence.receiptSha256) {
    throw new Error(`Evidence receipt ${evidence.year} changed.`);
  }
  const receipt = JSON.parse(receiptBytes);
  const snapshotPath = resolve(repositoryRoot, evidence.snapshotPath);
  if (await sha256File(snapshotPath) !== evidence.snapshotSha256 ||
      receipt.stageSnapshotSha256 !== evidence.snapshotSha256 ||
      receipt.normalizedEvidenceSha256 == null) {
    throw new Error(`Evidence snapshot ${evidence.year} changed.`);
  }
  const db = new DatabaseSync(snapshotPath, { readOnly: true });
  try {
    db.exec("PRAGMA query_only=ON");
    const metadata = db.prepare("SELECT * FROM multiAssetMetadata WHERE singleton=1").get();
    if (metadata?.protocol_sha256 !== receipt.protocolSha256 ||
        metadata.normalized_evidence_sha256 !== receipt.normalizedEvidenceSha256 ||
        normalizedMultiAssetEvidenceFingerprint(db, evaluationProtocol) !==
          receipt.normalizedEvidenceSha256) {
      throw new Error(`Evidence fingerprint ${evidence.year} changed.`);
    }
    return {
      evaluationProtocol,
      framesBySymbol: loadPortfolioFrames(db, evaluationProtocol),
      fundingRatesBySymbol: loadFundingRatesBySymbol(db, evaluationProtocol),
    };
  } finally {
    db.close();
    rmSync(`${snapshotPath}-shm`, { force: true });
    rmSync(`${snapshotPath}-wal`, { force: true });
  }
}

function withoutTrades(metrics) {
  const { trades, ...summary } = metrics;
  return summary;
}

async function writeJsonAtomic(path, value) {
  await mkdir(dirname(path), { recursive: true });
  const temporaryPath = `${path}.tmp-${process.pid}`;
  await writeFile(temporaryPath, `${JSON.stringify(value, null, 2)}\n`);
  await rename(temporaryPath, path);
}

async function sha256File(path) {
  const hash = createHash("sha256");
  for await (const chunk of createReadStream(path)) hash.update(chunk);
  return hash.digest("hex");
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

function round8(value) {
  return Number(value.toFixed(8));
}

const invokedPath = process.argv[1] == null ? null : pathToFileURL(resolve(process.argv[1])).href;
if (invokedPath === import.meta.url) {
  replayMultiAssetCostRecoveryCarry(parseArgs(process.argv.slice(2)))
    .then(({ report }) => console.log(JSON.stringify(report, null, 2)))
    .catch((error) => {
      console.error(error.stack ?? error.message);
      process.exitCode = 1;
    });
}
