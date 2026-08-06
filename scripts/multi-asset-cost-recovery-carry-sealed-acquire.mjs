#!/usr/bin/env node

import { dirname, resolve } from "node:path";
import { pathToFileURL } from "node:url";

import {
  acquireMultiAssetEvidenceStage,
} from "./lib/multi-asset-evidence-stage.mjs";
import {
  loadCostRecoveryCarrySealedProtocol,
} from "./multi-asset-cost-recovery-carry-sealed-protocol.mjs";

const DEFAULT_PROTOCOL =
  "config/bybit-multi-asset-cost-recovery-carry-sealed-2026-h1-v3.json";

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

export async function acquireCostRecoveryCarrySealedEvidence(options, dependencies = {}) {
  const loaded = await loadCostRecoveryCarrySealedProtocol(options.protocol);
  const protocol = loaded.protocol;
  const repositoryRoot = resolve(dirname(options.protocol), "..");
  const targetDatabasePath = resolve(repositoryRoot, protocol.sourceData.researchDatabase);
  const snapshotPath = resolve(
    repositoryRoot,
    `build/research/${protocol.protocolId}-snapshot.sqlite`,
  );
  const reportPath = options.report ?? resolve(
    repositoryRoot,
    `build/research/${protocol.protocolId}-acquisition.json`,
  );

  return acquireMultiAssetEvidenceStage({
    protocol,
    protocolSha256: loaded.sha256,
    parentResultSha256: loaded.developmentResultSha256,
    stageProtocol: stageProtocolFromSealed(protocol),
    stage: "sealed-2026-h1",
    status: "COMPLETE_MULTI_ASSET_SEALED_2026_H1_EVIDENCE",
    importerVersion: "multi-asset-cost-recovery-carry-sealed-2026-h1-v3",
    targetDatabasePath,
    snapshotPath,
    reportPath,
    repositoryRoot,
    implementationPaths: [
      "scripts/multi-asset-cost-recovery-carry-sealed-protocol.mjs",
      "scripts/multi-asset-cost-recovery-carry-sealed-acquire.mjs",
      "scripts/lib/multi-asset-evidence-stage.mjs",
      "scripts/multi-asset-delta-neutral-funding-carry-acquire.mjs",
      "scripts/delta-neutral-funding-carry-acquire.mjs",
    ],
    resultFields: {
      developmentResultSha256: loaded.developmentResultSha256,
      selectedCandidateSha256: protocol.selectedCandidateSha256,
      simulatorSha256: loaded.simulatorSha256,
      sealed2026H1PortfolioMetricsReadBeforeReceipt: false,
      freshForwardSealRead: false,
      sealedEvaluationAllowed: true,
    },
  }, { ...dependencies, requestDelayMs: options.requestDelayMs });
}

export function stageProtocolFromSealed(protocol) {
  return {
    sourceData: {
      ...protocol.sourceData,
      developmentStart: protocol.sourceData.stageStart,
      developmentEndExclusive: protocol.sourceData.stageEndExclusive,
    },
    evidenceSchedule: {
      developmentBlocks: protocol.sealedValidationBlocks,
    },
  };
}

const invokedPath = process.argv[1] == null ? null : pathToFileURL(resolve(process.argv[1])).href;
if (invokedPath === import.meta.url) {
  acquireCostRecoveryCarrySealedEvidence(parseArgs(process.argv.slice(2)))
    .then((result) => console.log(JSON.stringify(result, null, 2)))
    .catch((error) => {
      console.error(error.stack ?? error.message);
      process.exitCode = 1;
    });
}
