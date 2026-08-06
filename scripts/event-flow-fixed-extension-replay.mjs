#!/usr/bin/env node

import { createHash } from "node:crypto";
import { DatabaseSync } from "node:sqlite";
import { readFile, rename, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { pathToFileURL } from "node:url";

import { sha256File } from "./event-flow-development-backfill.mjs";
import { validateFixedExtensionProtocol } from "./event-flow-fixed-extension-protocol.mjs";
import {
  loadEventBlock,
  metricsForEventTrades,
  simulateEventCandidateBlock,
} from "./lib/event-flow-development-research.mjs";

const DEFAULT_PROTOCOL = "config/bybit-event-flow-fixed-extension-v1.json";
const PARENT_PROTOCOL = "config/bybit-event-flow-development-v1.json";
const DEFAULT_RECEIPT = "config/bybit-event-flow-fixed-extension-acquisition-receipt-v1.json";
const EXPECTED_RECEIPT_SHA256 = "61ec8dfb8192ad27e03b0359a728f6ece78001639ad3ce69531b981169463b0a";

export function parseArgs(argv) {
  const values = new Map();
  for (const argument of argv) {
    if (!argument.startsWith("--") || !argument.includes("=")) {
      throw new Error(`Invalid argument: ${argument}. Use --name=value.`);
    }
    const [name, ...rest] = argument.slice(2).split("=");
    if (!["protocol", "receipt", "acquisition-report", "output"].includes(name)) {
      throw new Error(`Unsupported argument: --${name}.`);
    }
    values.set(name, rest.join("="));
  }
  const protocol = resolve(values.get("protocol") ?? DEFAULT_PROTOCOL);
  const repositoryRoot = resolve(dirname(protocol), "..");
  return {
    protocol,
    receipt: resolve(values.get("receipt") ?? resolve(repositoryRoot, DEFAULT_RECEIPT)),
    acquisitionReport: resolve(values.get("acquisition-report") ?? resolve(
      repositoryRoot,
      "build/research/bybit-event-flow-fixed-extension-v1-acquisition.json",
    )),
    output: resolve(values.get("output") ?? resolve(
      repositoryRoot,
      "build/research/bybit-event-flow-fixed-extension-v1-result.json",
    )),
  };
}

export async function runFixedExtension(options, dependencies = {}) {
  const repositoryRoot = resolve(dirname(options.protocol), "..");
  const [protocolBytes, parentBytes, receiptBytes, reportBytes] = await Promise.all([
    readFile(options.protocol),
    readFile(resolve(repositoryRoot, PARENT_PROTOCOL)),
    readFile(options.receipt),
    readFile(options.acquisitionReport),
  ]);
  if (sha256(receiptBytes) !== EXPECTED_RECEIPT_SHA256) {
    throw new Error("Fixed extension acquisition receipt hash changed after it was committed.");
  }
  const parentProtocol = JSON.parse(parentBytes);
  const protocol = validateFixedExtensionProtocol(JSON.parse(protocolBytes), parentProtocol);
  const protocolSha256 = sha256(protocolBytes);
  const receipt = JSON.parse(receiptBytes);
  const report = JSON.parse(reportBytes);
  validateFixedExtensionAcquisition(report, receipt, protocol, protocolSha256, sha256(reportBytes));

  const databasePath = resolve(repositoryRoot, protocol.sourceData.researchDatabase);
  const hashFile = dependencies.hashFile ?? sha256File;
  const databaseSha256 = await hashFile(databasePath);
  if (databaseSha256 !== receipt.researchDatabaseSha256 || databaseSha256 !== report.targetDatabaseSha256) {
    throw new Error("Fixed extension research database changed after acquisition was sealed.");
  }

  const db = dependencies.db ?? new DatabaseSync(databasePath, { readOnly: true });
  const ownsDb = dependencies.db == null;
  let blocks;
  try {
    const loadBlock = dependencies.loadBlock ?? loadEventBlock;
    blocks = protocol.blocks.map((block) => loadBlock(db, protocol.sourceData.symbol, block));
  } finally {
    if (ownsDb) db.close();
  }
  const blockResults = blocks.map((block) => simulateEventCandidateBlock(
    protocol.fixedCandidate,
    block,
    protocol.executionContract,
  ));
  const trades = blockResults.flatMap((block) => block.trades);
  const evaluation = evaluateFixedExtension(trades, protocol);
  const result = {
    schemaVersion: 1,
    protocolId: protocol.protocolId,
    protocolSha256,
    fixedCandidateId: protocol.fixedCandidate.id,
    fixedCandidateSha256: protocol.fixedCandidateSha256,
    acquisitionReceiptSha256: EXPECTED_RECEIPT_SHA256,
    acquisitionReportSha256: sha256(reportBytes),
    extensionSourceFingerprintSha256: report.extensionSourceFingerprintSha256,
    researchDatabaseSha256: databaseSha256,
    implementationSha256: await implementationFingerprint(repositoryRoot),
    generatedAt: new Date().toISOString(),
    evaluation,
    replay: {
      blockCount: blockResults.length,
      blockResults,
      trades,
    },
    validationDataRead: false,
    externalDataRead: false,
    freshSealedDataRead: false,
    validationDataAcquisitionAllowed: false,
    automaticExecutionAllowed: false,
    liveExecutionAllowed: false,
  };
  await writeJsonAtomic(options.output, result);
  return result;
}

export function evaluateFixedExtension(trades, protocol) {
  const metrics = metricsForEventTrades(trades, protocol.blocks, protocol);
  const quarterReports = [...Map.groupBy(protocol.blocks, (block) => block.era)]
    .map(([era, blocks]) => ({ era, metrics: metricsForEventTrades(trades, blocks, protocol) }));
  const yearReports = [...Map.groupBy(protocol.blocks, (block) => block.era.slice(0, 4))]
    .map(([year, blocks]) => ({ year, metrics: metricsForEventTrades(trades, blocks, protocol) }));
  const longTrades = trades.filter((trade) => trade.side === "BUY").length;
  const shortTrades = trades.filter((trade) => trade.side === "SELL").length;
  const positiveQuarters = quarterReports.filter((report) => report.metrics.netReturnPct > 0).length;
  const positiveYears = yearReports.filter((report) => report.metrics.netReturnPct > 0).length;
  const gate = protocol.extensionGate;
  const checks = {
    minimumTrades: metrics.tradeCount >= gate.minimumTrades,
    minimumLongTrades: longTrades >= gate.minimumLongTrades,
    minimumShortTrades: shortTrades >= gate.minimumShortTrades,
    minimumPositiveQuarters: positiveQuarters >= gate.minimumPositiveQuarters,
    minimumPositiveYears: positiveYears >= gate.minimumPositiveYears,
    minimumProfitFactor: metrics.profitFactor >= gate.minimumProfitFactor,
    minimumMeanNetR: metrics.meanNetR > gate.minimumMeanNetR,
    minimumBootstrapLowerMeanNetR: (metrics.bootstrap?.lowerBound ?? -Infinity) > gate.minimumBootstrapLowerMeanNetR,
    maximumDrawdownPct: metrics.maxDrawdownPct <= gate.maximumDrawdownPct,
    maximumLiquidationCount: metrics.liquidationCount <= gate.maximumLiquidationCount,
    maximumWinnerProfitConcentration: metrics.maximumWinnerProfitConcentration <= gate.maximumWinnerProfitConcentration,
  };
  const passed = Object.values(checks).every(Boolean);
  return {
    status: passed ? "FIXED_EXTENSION_PASSED_REFREEZE_REQUIRED" : "REJECTED_FIXED_EXTENSION",
    candidateId: protocol.fixedCandidate.id,
    metrics,
    longTrades,
    shortTrades,
    quarterReports,
    positiveQuarters,
    yearReports,
    positiveYears,
    gate: { passed, checks },
    candidateRefreezeRequired: passed,
    validationDataAcquisitionAllowed: false,
    automaticExecutionAllowed: false,
    liveExecutionAllowed: false,
  };
}

export function validateFixedExtensionAcquisition(report, receipt, protocol, protocolSha256, reportSha256) {
  if (report?.status !== "COMPLETE" || report.stage !== "fixed-candidate-extension" ||
      report.protocolId !== protocol.protocolId || report.protocolSha256 !== protocolSha256) {
    throw new Error("Fixed extension replay requires the complete matching acquisition report.");
  }
  if (receipt?.status !== "COMPLETE_FIXED_EXTENSION_EVENT_DATA_ACQUISITION" ||
      receipt.protocolId !== protocol.protocolId || receipt.protocolSha256 !== protocolSha256 ||
      receipt.acquisitionReportSha256 !== reportSha256) {
    throw new Error("Fixed extension committed receipt does not match the acquisition report.");
  }
  const expectedIds = protocol.blocks.map((block) => block.id);
  const reportIds = report.completedBlocks?.map((block) => block.id) ?? [];
  const receiptIds = receipt.blockFingerprints?.map((block) => block.id) ?? [];
  if (reportIds.join(",") !== expectedIds.join(",") || receiptIds.join(",") !== expectedIds.join(",")) {
    throw new Error("Fixed extension acquisition evidence does not contain every declared block in order.");
  }
  for (let index = 0; index < expectedIds.length; index += 1) {
    if (report.completedBlocks[index].sourceFingerprintSha256 !== receipt.blockFingerprints[index].sha256) {
      throw new Error(`Fixed extension block fingerprint mismatch for ${expectedIds[index]}.`);
    }
  }
  if (report.extensionSourceFingerprintSha256 !== receipt.extensionSourceFingerprintSha256 ||
      report.targetDatabaseSha256 !== receipt.researchDatabaseSha256 ||
      receipt.candidateReplayPerformed !== false) {
    throw new Error("Fixed extension source or database fingerprint changed before replay.");
  }
  return report;
}

async function implementationFingerprint(repositoryRoot) {
  const paths = [
    "scripts/lib/event-flow-development-research.mjs",
    "scripts/event-flow-fixed-extension-protocol.mjs",
    "scripts/event-flow-fixed-extension-replay.mjs",
  ];
  const hash = createHash("sha256");
  for (const path of paths) {
    hash.update(path);
    hash.update("\0");
    hash.update(await readFile(resolve(repositoryRoot, path)));
    hash.update("\0");
  }
  return hash.digest("hex");
}

async function writeJsonAtomic(path, value) {
  const temporaryPath = `${path}.tmp`;
  await writeFile(temporaryPath, `${JSON.stringify(value, null, 2)}\n`);
  await rename(temporaryPath, path);
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

const invokedPath = process.argv[1] ? pathToFileURL(resolve(process.argv[1])).href : null;
if (invokedPath === import.meta.url) {
  const options = parseArgs(process.argv.slice(2));
  const result = await runFixedExtension(options);
  console.log(JSON.stringify({
    status: result.evaluation.status,
    tradeCount: result.evaluation.metrics.tradeCount,
    netReturnPct: result.evaluation.metrics.netReturnPct,
    compoundDailyReturnPct: result.evaluation.metrics.compoundDailyReturnPct,
    resultPath: options.output,
  }));
}
