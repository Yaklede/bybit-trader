#!/usr/bin/env node

import { createHash } from "node:crypto";
import { readFile, rename, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { pathToFileURL } from "node:url";

import { loadAnalysisContract } from "./event-flow-development-analysis-contract.mjs";
import { sha256File } from "./event-flow-development-backfill.mjs";
import {
  buildEventCandidates,
  evaluateEventDevelopment,
  loadEventDevelopmentBlocks,
  runEventDevelopmentReplay,
} from "./lib/event-flow-development-research.mjs";
import { validateEventFlowProtocol } from "./event-flow-research-protocol.mjs";

const DEFAULT_PROTOCOL = "config/bybit-event-flow-development-v1.json";
const DEFAULT_ANALYSIS = "config/bybit-event-flow-development-analysis-v1.json";

export function parseArgs(argv) {
  const values = new Map();
  for (const argument of argv) {
    if (!argument.startsWith("--") || !argument.includes("=")) throw new Error(`Invalid argument: ${argument}. Use --name=value.`);
    const [name, ...rest] = argument.slice(2).split("=");
    if (!["protocol", "analysis", "acquisition-report", "output"].includes(name)) {
      throw new Error(`Unsupported argument: --${name}.`);
    }
    values.set(name, rest.join("="));
  }
  const protocol = resolve(values.get("protocol") ?? DEFAULT_PROTOCOL);
  const repositoryRoot = resolve(dirname(protocol), "..");
  return {
    protocol,
    analysis: resolve(values.get("analysis") ?? resolve(repositoryRoot, DEFAULT_ANALYSIS)),
    acquisitionReport: resolve(values.get("acquisition-report") ?? resolve(
      repositoryRoot,
      "build/research/bybit-event-flow-development-v1-acquisition.json",
    )),
    output: resolve(values.get("output") ?? resolve(
      repositoryRoot,
      "build/research/bybit-event-flow-development-v1-result.json",
    )),
  };
}

export async function runDevelopmentAnalysis(options, dependencies = {}) {
  const protocolBytes = await readFile(options.protocol);
  const protocolSha256 = sha256(protocolBytes);
  const protocol = validateEventFlowProtocol(JSON.parse(protocolBytes));
  const { contract: analysisContract, sha256: analysisContractSha256 } = await loadAnalysisContract(options.analysis);
  if (analysisContract.acquisitionProtocol.protocolSha256 !== protocolSha256) {
    throw new Error("Analysis contract does not match the selected acquisition protocol bytes.");
  }
  const acquisitionBytes = await readFile(options.acquisitionReport);
  const acquisitionReport = JSON.parse(acquisitionBytes);
  validateAcquisitionReport(acquisitionReport, protocol, protocolSha256);
  const repositoryRoot = resolve(dirname(options.protocol), "..");
  const databasePath = resolve(repositoryRoot, protocol.sourceData.researchDatabase);
  const hashFile = dependencies.hashFile ?? sha256File;
  const databaseSha256 = await hashFile(databasePath);
  if (databaseSha256 !== acquisitionReport.targetDatabaseSha256) {
    throw new Error("Event-flow research database changed after its acquisition receipt was sealed.");
  }
  const implementationSha256 = await implementationFingerprint(repositoryRoot);
  const loadBlocks = dependencies.loadBlocks ?? loadEventDevelopmentBlocks;
  const blocks = loadBlocks(databasePath, protocol);
  const candidates = buildEventCandidates(protocol, analysisContract);
  const replay = runEventDevelopmentReplay({ blocks, candidates, protocol, analysisContract });
  const evaluation = evaluateEventDevelopment(replay, protocol, analysisContract);
  const result = {
    schemaVersion: 1,
    protocolId: protocol.protocolId,
    protocolSha256,
    analysisId: analysisContract.analysisId,
    analysisContractSha256,
    implementationSha256,
    acquisitionReportSha256: sha256(acquisitionBytes),
    developmentSourceFingerprintSha256: acquisitionReport.developmentSourceFingerprintSha256,
    researchDatabaseSha256: databaseSha256,
    generatedAt: new Date().toISOString(),
    evaluation,
    replay: {
      candidateCount: replay.candidateCount,
      blockCount: replay.blockCount,
      candidates: replay.candidates,
      automaticExecutionAllowed: false,
    },
    validationDataRead: false,
    externalDataRead: false,
    freshSealedDataRead: false,
    automaticExecutionAllowed: false,
    liveExecutionAllowed: false,
  };
  await writeJsonAtomic(options.output, result);
  return result;
}

export function validateAcquisitionReport(report, protocol, protocolSha256) {
  if (report?.status !== "COMPLETE" || report.stage !== "development") {
    throw new Error("Development replay requires a complete development acquisition receipt.");
  }
  if (report.protocolId !== protocol.protocolId || report.protocolSha256 !== protocolSha256) {
    throw new Error("Acquisition receipt does not match the frozen protocol.");
  }
  const expectedIds = protocol.stages.development.primaryBlocks.map((block) => block.id);
  const actualIds = report.completedBlocks?.map((block) => block.id) ?? [];
  if (actualIds.join(",") !== expectedIds.join(",")) {
    throw new Error("Acquisition receipt does not contain every primary development block in order.");
  }
  if (!isSha256(report.targetDatabaseSha256) || !isSha256(report.developmentSourceFingerprintSha256)) {
    throw new Error("Acquisition receipt is missing a sealed database or source fingerprint.");
  }
  return report;
}

async function implementationFingerprint(repositoryRoot) {
  const paths = [
    "scripts/lib/event-flow-development-research.mjs",
    "scripts/event-flow-development-replay.mjs",
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
  const temporary = `${path}.tmp`;
  await writeFile(temporary, `${JSON.stringify(value, null, 2)}\n`);
  await rename(temporary, path);
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

function isSha256(value) {
  return typeof value === "string" && /^[a-f0-9]{64}$/.test(value);
}

const invokedPath = process.argv[1] ? pathToFileURL(resolve(process.argv[1])).href : null;
if (invokedPath === import.meta.url) {
  const result = await runDevelopmentAnalysis(parseArgs(process.argv.slice(2)));
  console.log(JSON.stringify({
    status: result.evaluation.status,
    freezeRecommendation: result.evaluation.freezeRecommendation,
    sourceFingerprintSha256: result.developmentSourceFingerprintSha256,
    resultPath: parseArgs(process.argv.slice(2)).output,
  }));
}
