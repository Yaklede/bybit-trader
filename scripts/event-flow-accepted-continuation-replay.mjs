#!/usr/bin/env node

import { createHash } from "node:crypto";
import { DatabaseSync } from "node:sqlite";
import { readFile, rename, writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { pathToFileURL } from "node:url";

import { sha256File } from "./event-flow-development-backfill.mjs";
import { loadAcceptedContinuationProtocol } from "./event-flow-accepted-continuation-protocol.mjs";
import { loadEventBlock } from "./lib/event-flow-development-research.mjs";
import {
  evaluateAcceptedContinuation,
  runAcceptedContinuationReplay,
} from "./lib/event-flow-accepted-continuation-research.mjs";

const DEFAULT_PROTOCOL = "config/bybit-event-flow-accepted-continuation-v1.json";
const PRIMARY_PROTOCOL = "config/bybit-event-flow-development-v1.json";
const EXTENSION_PROTOCOL = "config/bybit-event-flow-fixed-extension-v1.json";
const PARENT_RESULT = "config/bybit-event-flow-fixed-extension-result-v1.json";
const EXPECTED_PARENT_RESULT_SHA256 = "ab41a1a0cb58697c98a192193879748872f5c1b8fe9a4d9b15413002bbaedacd";

export function parseArgs(argv) {
  const values = new Map();
  for (const argument of argv) {
    if (!argument.startsWith("--") || !argument.includes("=")) {
      throw new Error(`Invalid argument: ${argument}. Use --name=value.`);
    }
    const [name, ...rest] = argument.slice(2).split("=");
    if (!["protocol", "output"].includes(name)) throw new Error(`Unsupported argument: --${name}.`);
    values.set(name, rest.join("="));
  }
  const protocol = resolve(values.get("protocol") ?? DEFAULT_PROTOCOL);
  const repositoryRoot = resolve(dirname(protocol), "..");
  return {
    protocol,
    output: resolve(values.get("output") ?? resolve(
      repositoryRoot,
      "build/research/bybit-event-flow-accepted-continuation-v1-result.json",
    )),
  };
}

export async function runAcceptedContinuation(options, dependencies = {}) {
  const repositoryRoot = resolve(dirname(options.protocol), "..");
  const primaryProtocolPath = resolve(repositoryRoot, PRIMARY_PROTOCOL);
  const extensionProtocolPath = resolve(repositoryRoot, EXTENSION_PROTOCOL);
  const loaded = await loadAcceptedContinuationProtocol(
    options.protocol,
    primaryProtocolPath,
    extensionProtocolPath,
  );
  const [primaryBytes, extensionBytes, parentResultBytes] = await Promise.all([
    readFile(primaryProtocolPath),
    readFile(extensionProtocolPath),
    readFile(resolve(repositoryRoot, PARENT_RESULT)),
  ]);
  if (sha256(parentResultBytes) !== EXPECTED_PARENT_RESULT_SHA256 ||
      JSON.parse(parentResultBytes).status !== "REJECTED_FIXED_EXTENSION") {
    throw new Error("Accepted continuation replay requires the committed rejected parent result.");
  }
  const primaryProtocol = JSON.parse(primaryBytes);
  const extensionProtocol = JSON.parse(extensionBytes);
  const hashFile = dependencies.hashFile ?? sha256File;
  const blocks = [];
  const sourceDatabaseHashes = {};
  for (const source of loaded.protocol.sourceEvidence) {
    const databasePath = resolve(repositoryRoot, source.databasePath);
    const databaseSha256 = await hashFile(databasePath);
    if (databaseSha256 !== source.databaseSha256) {
      throw new Error(`Accepted continuation source database changed: ${source.id}.`);
    }
    sourceDatabaseHashes[source.id] = databaseSha256;
    const sourceProtocol = source.id === "PRIMARY_DEVELOPMENT" ? primaryProtocol : extensionProtocol;
    const definitions = source.id === "PRIMARY_DEVELOPMENT"
      ? sourceProtocol.stages.development.primaryBlocks
      : sourceProtocol.blocks;
    const definitionsById = new Map(definitions.map((block) => [block.id, block]));
    const db = dependencies.databases?.[source.id] ?? new DatabaseSync(databasePath, { readOnly: true });
    const ownsDb = dependencies.databases?.[source.id] == null;
    try {
      const loadBlock = dependencies.loadBlock ?? loadEventBlock;
      for (const id of source.blockIds) {
        const definition = definitionsById.get(id);
        if (definition == null) throw new Error(`Accepted continuation block definition ${id} is missing.`);
        blocks.push(loadBlock(db, primaryProtocol.sourceData.symbol, definition));
      }
    } finally {
      if (ownsDb) db.close();
    }
  }
  const replay = runAcceptedContinuationReplay({
    blocks,
    protocol: loaded.protocol,
    primaryProtocol,
    extensionProtocol,
  });
  const evaluation = evaluateAcceptedContinuation(
    replay,
    blocks,
    loaded.protocol,
    primaryProtocol,
    extensionProtocol,
  );
  const result = {
    schemaVersion: 1,
    protocolId: loaded.protocol.protocolId,
    protocolSha256: loaded.sha256,
    parentResultSha256: EXPECTED_PARENT_RESULT_SHA256,
    sourceProtocolSha256: loaded.sourceProtocolSha256,
    sourceDatabaseHashes,
    implementationSha256: await implementationFingerprint(repositoryRoot),
    generatedAt: new Date().toISOString(),
    evaluation,
    replay,
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

async function implementationFingerprint(repositoryRoot) {
  const paths = [
    "scripts/lib/event-flow-development-research.mjs",
    "scripts/event-flow-accepted-continuation-protocol.mjs",
    "scripts/lib/event-flow-accepted-continuation-research.mjs",
    "scripts/event-flow-accepted-continuation-replay.mjs",
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
  const result = await runAcceptedContinuation(options);
  console.log(JSON.stringify({
    status: result.evaluation.status,
    selectedCandidateId: result.evaluation.selectedCandidateId,
    validationTradeCount: result.evaluation.pooledValidation.tradeCount,
    validationNetReturnPct: result.evaluation.pooledValidation.netReturnPct,
    validationCompoundDailyReturnPct: result.evaluation.pooledValidation.compoundDailyReturnPct,
    resultPath: options.output,
  }));
}
