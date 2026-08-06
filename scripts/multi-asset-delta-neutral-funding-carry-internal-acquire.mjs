#!/usr/bin/env node

import { resolve, dirname } from "node:path";
import { pathToFileURL } from "node:url";

import {
  acquireMultiAssetEvidenceStage,
  stageProtocolFromInternal,
} from "./lib/multi-asset-evidence-stage.mjs";
import {
  loadMultiAssetDeltaNeutralFundingCarryInternalProtocol,
} from "./multi-asset-delta-neutral-funding-carry-internal-protocol.mjs";

const DEFAULT_PROTOCOL = "config/bybit-multi-asset-delta-neutral-funding-carry-internal-v1.json";

export function parseArgs(argv) {
  const values = new Map();
  for (const argument of argv) {
    if (!argument.startsWith("--") || !argument.includes("=")) {
      throw new Error(`Invalid argument: ${argument}. Use --name=value.`);
    }
    const [name, ...rest] = argument.slice(2).split("=");
    if (!["protocol", "report", "request-delay-ms"].includes(name)) {
      throw new Error(`Unsupported argument: --${name}.`);
    }
    if (values.has(name)) throw new Error(`Duplicate argument: --${name}.`);
    values.set(name, rest.join("="));
  }
  const requestDelayMs = Number(values.get("request-delay-ms") ?? 175);
  if (!Number.isInteger(requestDelayMs) || requestDelayMs < 0) {
    throw new Error("request-delay-ms must be a non-negative integer.");
  }
  return {
    protocol: resolve(values.get("protocol") ?? DEFAULT_PROTOCOL),
    report: values.has("report") ? resolve(values.get("report")) : null,
    requestDelayMs,
  };
}

export async function acquireMultiAssetDeltaNeutralFundingCarryInternal(options, dependencies = {}) {
  const loaded = await loadMultiAssetDeltaNeutralFundingCarryInternalProtocol(options.protocol);
  const protocol = loaded.protocol;
  const repositoryRoot = resolve(dirname(options.protocol), "..");
  const targetDatabasePath = resolve(repositoryRoot, protocol.sourceData.researchDatabase);
  const snapshotPath = resolve(repositoryRoot, `build/research/${protocol.protocolId}-snapshot.sqlite`);
  const reportPath = options.report ?? resolve(
    repositoryRoot,
    `build/research/${protocol.protocolId}-acquisition.json`,
  );
  return acquireMultiAssetEvidenceStage({
    protocol,
    protocolSha256: loaded.sha256,
    parentResultSha256: loaded.parentResultSha256,
    stageProtocol: stageProtocolFromInternal(protocol),
    stage: "internal",
    status: "COMPLETE_MULTI_ASSET_INTERNAL_EVIDENCE_SEALED",
    importerVersion: "multi-asset-delta-neutral-funding-carry-internal-v1",
    targetDatabasePath,
    snapshotPath,
    reportPath,
    repositoryRoot,
    implementationPaths: [
      "scripts/multi-asset-delta-neutral-funding-carry-internal-protocol.mjs",
      "scripts/multi-asset-delta-neutral-funding-carry-internal-acquire.mjs",
      "scripts/lib/multi-asset-evidence-stage.mjs",
      "scripts/multi-asset-delta-neutral-funding-carry-acquire.mjs",
      "scripts/delta-neutral-funding-carry-acquire.mjs"
    ],
    resultFields: {
      parentDevelopmentResultSha256: loaded.parentResultSha256,
      frozenCandidateSha256: protocol.selectedCandidateSha256,
      simulatorSha256: loaded.simulatorSha256,
      external2025Read: false,
      sealed2026Read: false,
      freshForwardSealRead: false,
      internalEvaluationAllowed: true
    }
  }, { ...dependencies, requestDelayMs: options.requestDelayMs });
}

const invokedPath = process.argv[1] == null ? null : pathToFileURL(resolve(process.argv[1])).href;
if (invokedPath === import.meta.url) {
  acquireMultiAssetDeltaNeutralFundingCarryInternal(parseArgs(process.argv.slice(2)))
    .then((result) => console.log(JSON.stringify(result, null, 2)))
    .catch((error) => {
      console.error(error.stack ?? error.message);
      process.exitCode = 1;
    });
}
