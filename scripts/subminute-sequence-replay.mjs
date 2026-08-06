#!/usr/bin/env node

import { createHash } from "node:crypto";
import { DatabaseSync } from "node:sqlite";
import { readFile, rename, writeFile } from "node:fs/promises";
import { dirname, resolve, sep } from "node:path";
import { pathToFileURL } from "node:url";

import { sha256File } from "./event-flow-development-backfill.mjs";
import {
  expandSubminuteCandidates,
  loadSubminuteSequenceProtocol,
} from "./subminute-sequence-protocol.mjs";
import {
  evaluateSubminuteCandidates,
  loadSubminuteBlock,
  rankAndSelectCandidates,
} from "./lib/subminute-sequence-research.mjs";

const DEFAULT_PROTOCOL = "config/bybit-subminute-sequence-development-v1.json";
const DEFAULT_RECEIPT = "config/bybit-subminute-sequence-selection-acquisition-receipt-v1.json";
const EXPECTED_SELECTION_RECEIPT_SHA256 = "05e98d5b7628b3a75ff89f4150209538a21eb45058fc8856197a5a088eeb3ad9";

export function parseArgs(argv) {
  const values = new Map();
  for (const argument of argv) {
    if (!argument.startsWith("--") || !argument.includes("=")) {
      throw new Error(`Invalid argument: ${argument}. Use --name=value.`);
    }
    const [name, ...rest] = argument.slice(2).split("=");
    if (!["protocol", "receipt", "output"].includes(name)) {
      throw new Error(`Unsupported argument: --${name}.`);
    }
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
      "build/research/bybit-subminute-sequence-selection-v1-result.json",
    )),
  };
}

export function validateSelectionReceipt(protocol, protocolSha256, receipt, receiptSha256) {
  if (receiptSha256 !== EXPECTED_SELECTION_RECEIPT_SHA256 ||
      receipt?.receiptId !== "bybit-subminute-sequence-selection-acquisition-receipt-v1" ||
      receipt.status !== "COMPLETE_SELECTION_EVIDENCE_SEALED" || receipt.stage !== "selection" ||
      receipt.sourceYear !== 2023 || receipt.protocolId !== protocol.protocolId ||
      receipt.protocolSha256 !== protocolSha256 || receipt.selectionAllowed !== true ||
      receipt.automaticExecutionAllowed !== false || receipt.liveExecutionAllowed !== false) {
    throw new Error("Selection replay requires the committed 2023 acquisition receipt.");
  }
  const locked = receipt.lockedEvidence;
  if (locked?.internalValidation2024Read !== false || locked.expansionRead !== false ||
      locked.validation2025Read !== false || locked.external2026Read !== false ||
      locked.freshSealedRead !== false) {
    throw new Error("Selection replay cannot use internal, external, or sealed evidence.");
  }
  if (receipt.sourceDateCount !== 28 || receipt.evaluationDayCount !== 24 ||
      receipt.stageSnapshot !== "build/research/bybit-subminute-sequence-development-v1-selection.sqlite" ||
      !/^[a-f0-9]{64}$/.test(receipt.stageSnapshotSha256) ||
      !/^[a-f0-9]{64}$/.test(receipt.normalizedFeatureSha256)) {
    throw new Error("Selection acquisition coverage or snapshot identity changed.");
  }
  return receipt;
}

export async function runSubminuteSelection(options, dependencies = {}) {
  const repositoryRoot = resolve(dirname(options.protocol), "..");
  const loadProtocol = dependencies.loadProtocol ?? loadSubminuteSequenceProtocol;
  const loaded = await loadProtocol(options.protocol);
  const receiptBytes = await readFile(options.receipt);
  const receiptSha256 = sha256(receiptBytes);
  const receipt = validateSelectionReceipt(
    loaded.protocol,
    loaded.sha256,
    JSON.parse(receiptBytes),
    receiptSha256,
  );
  const snapshotPath = resolve(repositoryRoot, receipt.stageSnapshot);
  if (!snapshotPath.startsWith(`${repositoryRoot}${sep}`)) {
    throw new Error("Selection snapshot must remain inside the repository workspace.");
  }
  const hashFile = dependencies.hashFile ?? sha256File;
  const snapshotSha256 = await hashFile(snapshotPath);
  if (snapshotSha256 !== receipt.stageSnapshotSha256) {
    throw new Error("Immutable selection snapshot hash changed.");
  }

  const candidates = expandSubminuteCandidates(loaded.protocol);
  if (candidates.length !== loaded.protocol.trialAccounting.newProgramCandidateBudget) {
    throw new Error("Selection candidate count exceeds the frozen trial budget.");
  }
  const db = dependencies.database ?? new DatabaseSync(snapshotPath, { readOnly: true });
  const ownsDatabase = dependencies.database == null;
  let blocks;
  try {
    const blockLoader = dependencies.loadBlock ?? loadSubminuteBlock;
    blocks = loaded.protocol.acquisition.selectionBlocks.map((block) =>
      blockLoader(db, loaded.protocol.sourceData.symbol, block));
  } finally {
    if (ownsDatabase) db.close();
  }
  const evaluate = dependencies.evaluate ?? evaluateSubminuteCandidates;
  const ranking = dependencies.rank ?? rankAndSelectCandidates;
  const ranked = ranking(evaluate(loaded.protocol, candidates, blocks));
  const selectedCandidateIds = ranked.selected.map((candidate) => candidate.candidateId);
  const result = {
    schemaVersion: 1,
    resultId: "bybit-subminute-sequence-selection-result-v1",
    status: selectedCandidateIds.length === 0
      ? "REJECTED_NO_SELECTION_CANDIDATE"
      : "SELECTION_CANDIDATES_FROZEN",
    protocolId: loaded.protocol.protocolId,
    protocolSha256: loaded.sha256,
    hypothesisSha256: loaded.protocol.hypothesisSha256,
    acquisitionReceiptSha256: receiptSha256,
    selectionSnapshotSha256: snapshotSha256,
    normalizedFeatureSha256: receipt.normalizedFeatureSha256,
    implementationSha256: await implementationFingerprint(repositoryRoot),
    generatedAt: new Date().toISOString(),
    evaluatedCandidateCount: candidates.length,
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
    "scripts/lib/subminute-sequence-research.mjs",
    "scripts/subminute-sequence-replay.mjs",
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
  const result = await runSubminuteSelection(options);
  console.log(JSON.stringify({
    status: result.status,
    evaluatedCandidateCount: result.evaluatedCandidateCount,
    selectedCandidateIds: result.selectedCandidateIds,
    bestCandidateId: result.rankedCandidates[0]?.candidateId ?? null,
    bestCandidateNetReturnPct: result.rankedCandidates[0]?.netReturnPct ?? null,
    bestCandidateCompoundDailyReturnPct: result.rankedCandidates[0]?.compoundDailyReturnPct ?? null,
    resultPath: options.output,
  }));
}
