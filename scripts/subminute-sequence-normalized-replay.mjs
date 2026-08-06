#!/usr/bin/env node

import { createHash } from "node:crypto";
import { DatabaseSync } from "node:sqlite";
import { readFile, rename, writeFile } from "node:fs/promises";
import { dirname, resolve, sep } from "node:path";
import { pathToFileURL } from "node:url";

import { sha256File } from "./event-flow-development-backfill.mjs";
import { expandSubminuteCandidates } from "./subminute-sequence-protocol.mjs";
import { loadNormalizedSubminuteProtocol } from "./subminute-sequence-normalized-protocol.mjs";
import {
  evaluateSubminuteCandidates,
  loadSubminuteBlock,
  rankAndSelectCandidates,
} from "./lib/subminute-sequence-research.mjs";

const DEFAULT_PROTOCOL = "config/bybit-subminute-sequence-normalized-v2.json";

export function parseArgs(argv) {
  const values = new Map();
  for (const argument of argv) {
    if (!argument.startsWith("--") || !argument.includes("=")) {
      throw new Error(`Invalid argument: ${argument}. Use --name=value.`);
    }
    const [name, ...rest] = argument.slice(2).split("=");
    if (!["protocol", "output"].includes(name)) throw new Error(`Unsupported argument: --${name}.`);
    if (values.has(name)) throw new Error(`Duplicate argument: --${name}.`);
    values.set(name, rest.join("="));
  }
  const protocol = resolve(values.get("protocol") ?? DEFAULT_PROTOCOL);
  const repositoryRoot = resolve(dirname(protocol), "..");
  return {
    protocol,
    output: resolve(values.get("output") ?? resolve(
      repositoryRoot,
      "build/research/bybit-subminute-sequence-normalized-v2-selection-result.json",
    )),
  };
}

export async function runNormalizedSelection(options, dependencies = {}) {
  const repositoryRoot = resolve(dirname(options.protocol), "..");
  const loadProtocol = dependencies.loadProtocol ?? loadNormalizedSubminuteProtocol;
  const loaded = await loadProtocol(options.protocol);
  const declaration = loaded.declaration;
  const protocol = loaded.effectiveProtocol;
  const snapshotPath = resolve(repositoryRoot, declaration.parentEvidence.selectionSnapshot);
  if (!snapshotPath.startsWith(`${repositoryRoot}${sep}`)) {
    throw new Error("Normalized selection snapshot must remain inside the repository workspace.");
  }
  const hashFile = dependencies.hashFile ?? sha256File;
  const snapshotSha256 = await hashFile(snapshotPath);
  if (snapshotSha256 !== declaration.parentEvidence.selectionSnapshotSha256) {
    throw new Error("Normalized v2 immutable 2023 snapshot hash changed.");
  }
  const candidates = expandSubminuteCandidates(protocol);
  if (candidates.length !== declaration.trialAccounting.newCandidateBudget) {
    throw new Error("Normalized v2 candidate count changed after declaration.");
  }
  const db = dependencies.database ?? new DatabaseSync(snapshotPath, { readOnly: true });
  const ownsDatabase = dependencies.database == null;
  let blocks;
  try {
    const blockLoader = dependencies.loadBlock ?? loadSubminuteBlock;
    blocks = protocol.acquisition.selectionBlocks.map((block) =>
      blockLoader(db, protocol.sourceData.symbol, block));
  } finally {
    if (ownsDatabase) db.close();
  }
  const evaluate = dependencies.evaluate ?? evaluateSubminuteCandidates;
  const ranking = dependencies.rank ?? rankAndSelectCandidates;
  const ranked = ranking(evaluate(protocol, candidates, blocks));
  const selectedCandidateIds = ranked.selected.map((candidate) => candidate.candidateId);
  const result = {
    schemaVersion: 1,
    resultId: "bybit-subminute-sequence-normalized-v2-selection-result",
    status: selectedCandidateIds.length === 0
      ? "REJECTED_NO_NORMALIZED_SELECTION_CANDIDATE"
      : "NORMALIZED_SELECTION_CANDIDATES_FROZEN",
    protocolId: declaration.protocolId,
    protocolSha256: loaded.sha256,
    parentResultSha256: loaded.parentResultSha256,
    parentProtocolSha256: loaded.parentProtocolSha256,
    acquisitionReceiptSha256: loaded.acquisitionReceiptSha256,
    selectionSnapshotSha256: snapshotSha256,
    normalizedSourceFeatureSha256: declaration.parentEvidence.normalizedSourceFeatureSha256,
    hypothesisSha256: declaration.hypothesisSha256,
    implementationSha256: await implementationFingerprint(repositoryRoot),
    generatedAt: new Date().toISOString(),
    priorObservedCandidateCount: declaration.trialAccounting.priorObservedCandidates,
    evaluatedCandidateCount: candidates.length,
    cumulativeObservedCandidateCount: declaration.trialAccounting.cumulativeCandidateCountAfterReplay,
    selectedCandidateIds,
    rankedCandidates: ranked.ranked,
    evidenceBoundary: {
      selection2023Read: true,
      internalValidation2024Read: false,
      expansionRead: false,
      validation2025Read: false,
      external2026Read: false,
      freshSealedRead: false,
    },
    internalValidationAcquisitionAllowed: selectedCandidateIds.length > 0,
    automaticExecutionAllowed: false,
    liveExecutionAllowed: false,
  };
  await writeJsonAtomic(options.output, result);
  return result;
}

async function implementationFingerprint(repositoryRoot) {
  const paths = [
    "scripts/subminute-sequence-protocol.mjs",
    "scripts/subminute-sequence-normalized-protocol.mjs",
    "scripts/lib/subminute-sequence-research.mjs",
    "scripts/subminute-sequence-normalized-replay.mjs",
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

const invokedPath = process.argv[1] ? pathToFileURL(resolve(process.argv[1])).href : null;
if (invokedPath === import.meta.url) {
  const options = parseArgs(process.argv.slice(2));
  const result = await runNormalizedSelection(options);
  console.log(JSON.stringify({
    status: result.status,
    evaluatedCandidateCount: result.evaluatedCandidateCount,
    cumulativeObservedCandidateCount: result.cumulativeObservedCandidateCount,
    selectedCandidateIds: result.selectedCandidateIds,
    bestCandidateId: result.rankedCandidates[0]?.candidateId ?? null,
    bestCandidateNetReturnPct: result.rankedCandidates[0]?.netReturnPct ?? null,
    bestCandidateCompoundDailyReturnPct: result.rankedCandidates[0]?.compoundDailyReturnPct ?? null,
    resultPath: options.output,
  }));
}
