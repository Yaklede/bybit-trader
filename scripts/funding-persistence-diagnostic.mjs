#!/usr/bin/env node

import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import { mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { DatabaseSync } from "node:sqlite";
import { dirname, resolve } from "node:path";
import { pathToFileURL } from "node:url";

const DEFAULT_PROTOCOL = "config/bybit-funding-persistence-diagnostic-v1.json";

export function parseArgs(argv) {
  const values = new Map();
  for (const argument of argv) {
    if (!argument.startsWith("--") || !argument.includes("=")) {
      throw new Error(`Invalid argument: ${argument}. Use --name=value.`);
    }
    const [name, ...rest] = argument.slice(2).split("=");
    if (!["protocol", "report"].includes(name)) throw new Error(`Unsupported argument: --${name}.`);
    if (values.has(name)) throw new Error(`Duplicate argument: --${name}.`);
    values.set(name, rest.join("="));
  }
  return {
    protocol: resolve(values.get("protocol") ?? DEFAULT_PROTOCOL),
    report: values.has("report") ? resolve(values.get("report")) : null,
  };
}

export async function runFundingPersistenceDiagnostic(options) {
  const protocolBytes = await readFile(options.protocol);
  const protocol = JSON.parse(protocolBytes);
  const repositoryRoot = resolve(dirname(options.protocol), "..");
  await validateProtocol(protocol, repositoryRoot);

  const developmentBySymbol = Object.fromEntries(protocol.symbols.map((symbol) => [symbol, []]));
  for (const evidence of protocol.developmentEvidence) {
    const rows = await loadFundingRows(repositoryRoot, evidence, protocol.symbols);
    for (const symbol of protocol.symbols) developmentBySymbol[symbol].push(...rows[symbol]);
  }
  const diagnosticBySymbol = await loadFundingRows(
    repositoryRoot,
    protocol.diagnosticEvidence,
    protocol.symbols,
  );

  const analyses = {};
  for (const symbol of protocol.symbols) {
    analyses[symbol] = analyzeSymbol({
      developmentRates: developmentBySymbol[symbol],
      diagnosticRates: diagnosticBySymbol[symbol],
      analysis: protocol.analysis,
    });
  }
  const gate = evaluateFundingPersistenceGate(analyses, protocol.viabilityGate);
  const report = {
    schemaVersion: 1,
    diagnosticId: protocol.diagnosticId,
    protocolSha256: sha256(protocolBytes),
    status: gate.passed
      ? "FUNDING_PERSISTENCE_SUPPORTS_BOUNDED_CARRY_V4_RESEARCH"
      : "CLOSED_NO_FUNDING_PERSISTENCE_FOR_CARRY_V4",
    evidenceBoundary: {
      development2023Through2025Read: true,
      diagnostic2026H1Read: true,
      future2026AfterH1Read: false,
    },
    analysis: protocol.analysis,
    symbols: analyses,
    gate,
    carryV4ResearchAllowed: gate.passed,
    automaticExecutionAllowed: false,
    liveExecutionAllowed: false,
  };
  const reportPath = options.report ?? resolve(
    repositoryRoot,
    `build/research/${protocol.diagnosticId}-result.json`,
  );
  await writeJsonAtomic(reportPath, report);
  return { report, reportPath };
}

export function analyzeSymbol({ developmentRates, diagnosticRates, analysis }) {
  const combinations = [];
  for (const trailing of analysis.trailingSettlementWindows) {
    for (const forward of analysis.forwardSettlementWindows) {
      const observations = buildWindowObservations(developmentRates, trailing, forward);
      const anchored = observations.filter((_, index) => index % analysis.nonOverlappingAnchorStrideSettlements === 0);
      combinations.push({
        trailingSettlements: trailing,
        forwardSettlements: forward,
        observationCount: observations.length,
        anchoredObservationCount: anchored.length,
        pearsonCorrelation: round8(pearsonCorrelation(
          observations.map((row) => row.trailingSum),
          observations.map((row) => row.forwardSum),
        )),
        anchoredPearsonCorrelation: round8(pearsonCorrelation(
          anchored.map((row) => row.trailingSum),
          anchored.map((row) => row.forwardSum),
        )),
      });
    }
  }

  const trailing = analysis.primaryTrailingSettlements;
  const forward = analysis.primaryForwardSettlements;
  const development = buildWindowObservations(developmentRates, trailing, forward)
    .filter((_, index) => index % analysis.nonOverlappingAnchorStrideSettlements === 0);
  const threshold = quantile(
    development.map((row) => row.trailingSum),
    analysis.highPersistenceQuantile,
  );
  const diagnostic = buildWindowObservations(diagnosticRates, trailing, forward)
    .filter((_, index) => index % analysis.nonOverlappingAnchorStrideSettlements === 0);
  const developmentHigh = development.filter((row) => row.trailingSum >= threshold);
  const diagnosticHigh = diagnostic.filter((row) => row.trailingSum >= threshold);
  return {
    developmentSettlementCount: developmentRates.length,
    diagnosticSettlementCount: diagnosticRates.length,
    combinations,
    primary: {
      trailingSettlements: trailing,
      forwardSettlements: forward,
      developmentHighPersistenceThreshold: round8(threshold),
      developmentAnchoredObservationCount: development.length,
      developmentHighPersistence: summarizeHighPersistence(
        developmentHigh,
        analysis.baseRoundTripCostRateOnMatchedNotional,
      ),
      diagnosticAnchoredObservationCount: diagnostic.length,
      diagnosticHighPersistence: summarizeHighPersistence(
        diagnosticHigh,
        analysis.baseRoundTripCostRateOnMatchedNotional,
      ),
    },
  };
}

export function buildWindowObservations(rates, trailingCount, forwardCount) {
  if (!Number.isInteger(trailingCount) || trailingCount <= 0 ||
      !Number.isInteger(forwardCount) || forwardCount <= 0) {
    throw new Error("Funding windows must be positive integers.");
  }
  const prefix = [0];
  for (const row of rates) prefix.push(prefix.at(-1) + row.rate);
  const observations = [];
  for (let index = trailingCount - 1; index + forwardCount < rates.length; index += 1) {
    observations.push({
      timestamp: rates[index].timestamp,
      trailingSum: prefix[index + 1] - prefix[index + 1 - trailingCount],
      forwardSum: prefix[index + 1 + forwardCount] - prefix[index + 1],
    });
  }
  return observations;
}

export function pearsonCorrelation(left, right) {
  if (left.length !== right.length || left.length < 2) return 0;
  const leftMean = left.reduce((sum, value) => sum + value, 0) / left.length;
  const rightMean = right.reduce((sum, value) => sum + value, 0) / right.length;
  let covariance = 0;
  let leftVariance = 0;
  let rightVariance = 0;
  for (let index = 0; index < left.length; index += 1) {
    const leftDelta = left[index] - leftMean;
    const rightDelta = right[index] - rightMean;
    covariance += leftDelta * rightDelta;
    leftVariance += leftDelta ** 2;
    rightVariance += rightDelta ** 2;
  }
  if (leftVariance === 0 || rightVariance === 0) return 0;
  return covariance / Math.sqrt(leftVariance * rightVariance);
}

export function quantile(values, probability) {
  if (values.length === 0) return Number.POSITIVE_INFINITY;
  if (probability < 0 || probability > 1) throw new Error("Quantile probability must be in [0,1].");
  const sorted = [...values].sort((left, right) => left - right);
  const position = (sorted.length - 1) * probability;
  const lower = Math.floor(position);
  const upper = Math.ceil(position);
  if (lower === upper) return sorted[lower];
  return sorted[lower] + (sorted[upper] - sorted[lower]) * (position - lower);
}

export function evaluateFundingPersistenceGate(analyses, gate) {
  const eligibleSymbols = Object.entries(analyses).filter(([, result]) => {
    const primaryCorrelation = result.combinations.find((row) =>
      row.trailingSettlements === result.primary.trailingSettlements &&
      row.forwardSettlements === result.primary.forwardSettlements)?.anchoredPearsonCorrelation ?? 0;
    const development = result.primary.developmentHighPersistence;
    const diagnostic = result.primary.diagnosticHighPersistence;
    return primaryCorrelation >= gate.minimumAnchoredPearsonCorrelation &&
      development.observationCount >= gate.minimumDevelopmentHighPersistenceObservationCount &&
      development.costRecoveryRate >= gate.minimumDevelopmentCostRecoveryRate &&
      diagnostic.observationCount >= gate.minimumDiagnosticHighPersistenceObservationCount &&
      diagnostic.costRecoveryRate >= gate.minimumDiagnosticCostRecoveryRate;
  }).map(([symbol]) => symbol);
  return {
    passed: eligibleSymbols.length >= gate.minimumEligibleSymbolCount,
    eligibleSymbols,
    requiredEligibleSymbolCount: gate.minimumEligibleSymbolCount,
  };
}

async function validateProtocol(protocol, repositoryRoot) {
  if (protocol?.diagnosticId !== "bybit-funding-persistence-diagnostic-v1" ||
      protocol.status !== "PREDECLARED_ON_DISCLOSED_2023_THROUGH_2026_H1_EVIDENCE" ||
      protocol.symbols?.join("|") !== "BTCUSDT|ETHUSDT|SOLUSDT" ||
      JSON.stringify(protocol.analysis?.trailingSettlementWindows) !== "[21,45,90]" ||
      JSON.stringify(protocol.analysis?.forwardSettlementWindows) !== "[90,180,270]" ||
      protocol.analysis.primaryTrailingSettlements !== 90 ||
      protocol.analysis.primaryForwardSettlements !== 90 ||
      protocol.analysis.nonOverlappingAnchorStrideSettlements !== 90 ||
      protocol.analysis.highPersistenceQuantile !== 0.8 ||
      protocol.analysis.baseRoundTripCostRateOnMatchedNotional !== 0.0041 ||
      protocol.outcomePolicy?.future2026AfterH1Read !== false ||
      protocol.outcomePolicy.automaticExecutionAllowed !== false ||
      protocol.outcomePolicy.liveExecutionAllowed !== false) {
    throw new Error("Funding persistence diagnostic contract changed.");
  }
  const parentBytes = await readFile(resolve(repositoryRoot, protocol.parentSealedResult.path));
  const parent = JSON.parse(parentBytes);
  if (sha256(parentBytes) !== protocol.parentSealedResult.sha256 ||
      parent.programStatus !== protocol.parentSealedResult.requiredProgramStatus) {
    throw new Error("Funding persistence parent result changed.");
  }
  for (const evidence of [...protocol.developmentEvidence, protocol.diagnosticEvidence]) {
    if (await sha256File(resolve(repositoryRoot, evidence.snapshotPath)) !== evidence.snapshotSha256) {
      throw new Error(`Funding persistence snapshot changed: ${evidence.snapshotPath}.`);
    }
  }
}

async function loadFundingRows(repositoryRoot, evidence, symbols) {
  const db = new DatabaseSync(resolve(repositoryRoot, evidence.snapshotPath), { readOnly: true });
  try {
    db.exec("PRAGMA query_only=ON");
    return Object.fromEntries(symbols.map((symbol) => [symbol, db.prepare(`
      SELECT timestamp, funding_rate FROM fundingRates WHERE symbol=? ORDER BY timestamp
    `).all(symbol).map((row) => ({ timestamp: Date.parse(row.timestamp), rate: Number(row.funding_rate) }))]));
  } finally {
    db.close();
  }
}

function summarizeHighPersistence(observations, costRate) {
  const recovered = observations.filter((row) => row.forwardSum > costRate);
  return {
    observationCount: observations.length,
    meanTrailingFundingRate: round8(mean(observations.map((row) => row.trailingSum))),
    meanForwardFundingRate: round8(mean(observations.map((row) => row.forwardSum))),
    meanNetAfterBaseCostRate: round8(mean(observations.map((row) => row.forwardSum - costRate))),
    costRecoveryCount: recovered.length,
    costRecoveryRate: observations.length === 0 ? 0 : round8(recovered.length / observations.length),
  };
}

function mean(values) {
  return values.length === 0 ? 0 : values.reduce((sum, value) => sum + value, 0) / values.length;
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
  runFundingPersistenceDiagnostic(parseArgs(process.argv.slice(2)))
    .then(({ report }) => console.log(JSON.stringify(report, null, 2)))
    .catch((error) => {
      console.error(error.stack ?? error.message);
      process.exitCode = 1;
    });
}
