#!/usr/bin/env node

import { createHash } from "node:crypto";
import { readFile, rename, writeFile } from "node:fs/promises";
import { DatabaseSync } from "node:sqlite";
import { dirname, resolve, sep } from "node:path";
import { pathToFileURL } from "node:url";

import { sha256File } from "./event-flow-development-backfill.mjs";
import { expandFundingCandidates, loadFundingCrowdingProtocol } from "./funding-crowding-protocol.mjs";
import {
  evaluateFundingCandidates,
  loadFundingResearchInputs,
  rankFundingCandidates,
} from "./lib/funding-crowding-research.mjs";

const DEFAULT_PROTOCOL = "config/bybit-funding-crowding-development-v1.json";
const DEFAULT_RECEIPT = "config/bybit-funding-crowding-development-acquisition-receipt-v1.json";
const EXPECTED_RECEIPT_SHA256 = "397ca1f53908b8c013dfe7fa4db86a5977a46f542d20fb057684ede1c37819de";

export function parseArgs(argv) {
  const values = new Map();
  for (const argument of argv) {
    if (!argument.startsWith("--") || !argument.includes("=")) {
      throw new Error(`Invalid argument: ${argument}. Use --name=value.`);
    }
    const [name, ...rest] = argument.slice(2).split("=");
    if (!["protocol", "receipt", "output"].includes(name)) throw new Error(`Unsupported argument: --${name}.`);
    if (values.has(name)) throw new Error(`Duplicate argument: --${name}.`);
    values.set(name, rest.join("="));
  }
  const protocol = resolve(values.get("protocol") ?? DEFAULT_PROTOCOL);
  const repositoryRoot = resolve(dirname(protocol), "..");
  return {
    protocol,
    receipt: resolve(values.get("receipt") ?? resolve(repositoryRoot, DEFAULT_RECEIPT)),
    output: resolve(values.get("output") ?? resolve(
      repositoryRoot,
      "build/research/bybit-funding-crowding-development-v1-result.json",
    )),
  };
}

export async function runFundingDevelopment(options, dependencies = {}) {
  const repositoryRoot = resolve(dirname(options.protocol), "..");
  const loaded = await (dependencies.loadProtocol ?? loadFundingCrowdingProtocol)(options.protocol);
  const receiptBytes = await readFile(options.receipt);
  const receiptSha256 = sha256(receiptBytes);
  const receipt = JSON.parse(receiptBytes);
  validateReceipt(receipt, receiptSha256, loaded.sha256);
  const snapshotPath = resolve(repositoryRoot, receipt.stageSnapshot);
  if (!snapshotPath.startsWith(`${repositoryRoot}${sep}`)) throw new Error("Funding snapshot must remain inside the workspace.");
  const hashFile = dependencies.hashFile ?? sha256File;
  const snapshotSha256 = await hashFile(snapshotPath);
  if (snapshotSha256 !== receipt.stageSnapshotSha256) throw new Error("Funding development snapshot hash changed.");
  const candidates = expandFundingCandidates(loaded.protocol);
  if (candidates.length !== loaded.protocol.trialAccounting.newCandidateBudget) {
    throw new Error("Funding development candidate budget changed.");
  }
  const db = dependencies.database ?? new DatabaseSync(snapshotPath, { readOnly: true });
  const ownsDatabase = dependencies.database == null;
  let evaluated;
  try {
    const input = (dependencies.loadInput ?? loadFundingResearchInputs)(db, loaded.protocol);
    evaluated = (dependencies.evaluate ?? evaluateFundingCandidates)(db, loaded.protocol, candidates, input);
  } finally {
    if (ownsDatabase) db.close();
  }
  const ranked = (dependencies.rank ?? rankFundingCandidates)(evaluated);
  const selectedCandidateIds = ranked.selected.map((row) => row.candidateId);
  const result = {
    schemaVersion: 1,
    resultId: "bybit-funding-crowding-development-v1-result",
    status: selectedCandidateIds.length === 0
      ? "REJECTED_NO_FUNDING_CROWDING_DEVELOPMENT_CANDIDATE"
      : "FUNDING_CROWDING_DEVELOPMENT_CANDIDATE_FROZEN",
    protocolId: loaded.protocol.protocolId,
    protocolSha256: loaded.sha256,
    parentResultSha256: loaded.parentResultSha256,
    acquisitionReceiptSha256: receiptSha256,
    developmentSnapshotSha256: snapshotSha256,
    normalizedFeatureSha256: receipt.normalizedFeatureSha256,
    implementationSha256: await implementationFingerprint(repositoryRoot),
    generatedAt: new Date().toISOString(),
    priorObservedCandidateCount: loaded.protocol.trialAccounting.priorObservedCandidates,
    evaluatedCandidateCount: candidates.length,
    cumulativeObservedCandidateCount: loaded.protocol.trialAccounting.cumulativeCandidateCountAfterReplay,
    selectedCandidateIds,
    rankedCandidates: ranked.ranked,
    evidenceBoundary: {
      development2020Through2022Read: true,
      internalValidation2023Through2024Read: false,
      external2025Read: false,
      external2026Read: false,
      freshForwardSealRead: false,
    },
    internalValidationAcquisitionAllowed: selectedCandidateIds.length === 1,
    automaticExecutionAllowed: false,
    liveExecutionAllowed: false,
  };
  await writeJsonAtomic(options.output, result);
  return result;
}

export function validateReceipt(receipt, receiptSha256, protocolSha256) {
  if (receiptSha256 !== EXPECTED_RECEIPT_SHA256 ||
      receipt?.receiptId !== "bybit-funding-crowding-development-acquisition-receipt-v1" ||
      receipt.status !== "COMPLETE_DEVELOPMENT_EVIDENCE_SEALED" || receipt.stage !== "development" ||
      receipt.protocolSha256 !== protocolSha256 || receipt.developmentEvaluationAllowed !== true ||
      receipt.coverage?.missingDecisionInputCount !== 0 ||
      Object.values(receipt.integrity ?? {}).some((value) => value !== true) ||
      Object.values(receipt.lockedEvidence ?? {}).some((value) => value !== false) ||
      receipt.automaticExecutionAllowed !== false || receipt.liveExecutionAllowed !== false) {
    throw new Error("Funding development replay requires the committed sealed acquisition receipt.");
  }
}

export async function implementationFingerprint(repositoryRoot) {
  const paths = [
    "scripts/funding-crowding-protocol.mjs",
    "scripts/lib/funding-crowding-research.mjs",
    "scripts/funding-crowding-development-replay.mjs",
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

const invokedPath = process.argv[1] ? pathToFileURL(resolve(process.argv[1])).href : null;
if (invokedPath === import.meta.url) {
  const result = await runFundingDevelopment(parseArgs(process.argv.slice(2)));
  console.log(JSON.stringify({
    status: result.status,
    evaluatedCandidateCount: result.evaluatedCandidateCount,
    cumulativeObservedCandidateCount: result.cumulativeObservedCandidateCount,
    selectedCandidateIds: result.selectedCandidateIds,
    bestCandidateId: result.rankedCandidates[0]?.candidateId ?? null,
    bestCandidateNetReturnPct: result.rankedCandidates[0]?.netReturnPct ?? null,
    bestCandidateMeanNetR: result.rankedCandidates[0]?.meanNetR ?? null,
    bestCandidateBootstrapLowerMeanNetR: result.rankedCandidates[0]?.bootstrapLowerMeanNetR ?? null,
    resultPath: parseArgs(process.argv.slice(2)).output,
  }));
}
