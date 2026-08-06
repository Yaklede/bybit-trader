import { createHash } from "node:crypto";
import fs from "node:fs/promises";

const EXPECTED_PARENT_RESULT_SHA256 = "b71d0d3288c558851385fc415554b4d80ad4d302e12d8abfd0f5612abc5c96f1";
const EXPECTED_HYPOTHESIS_SHA256 = "23603b750ad2619450372a8eace8350927ca1194a85e1faedb605fef154b6cfb";
const SELECTION_IDS = [
  "D01", "D02", "D03", "D04", "D05", "D06",
  "X01", "X02", "X03", "X04", "X05", "X06", "X07", "X08",
];
const VALIDATION_IDS = [
  ["D07", "D08", "D09", "X09", "X10", "X11", "X12"],
  ["D10", "D11", "D12", "X13", "X14", "X15", "X16"],
];

export async function loadPullbackProtocol(path, primaryProtocolPath, extensionProtocolPath) {
  const [protocolBytes, primaryBytes, extensionBytes] = await Promise.all([
    fs.readFile(path),
    fs.readFile(primaryProtocolPath),
    fs.readFile(extensionProtocolPath),
  ]);
  return {
    protocol: validatePullbackProtocol(
      JSON.parse(protocolBytes),
      JSON.parse(primaryBytes),
      JSON.parse(extensionBytes),
    ),
    sha256: sha256(protocolBytes),
    sourceProtocolSha256: [sha256(primaryBytes), sha256(extensionBytes)],
  };
}

export function validatePullbackProtocol(protocol, primaryProtocol, extensionProtocol) {
  if (protocol?.protocolId !== "bybit-event-flow-pullback-reacceleration-v1" ||
      protocol.status !== "PREDECLARED_FINAL_DEVELOPMENT_HYPOTHESIS_BEFORE_REPLAY") {
    throw new Error("Pullback protocol must be frozen before replay.");
  }
  if (protocol.parentResult?.resultReceiptSha256 !== EXPECTED_PARENT_RESULT_SHA256 ||
      protocol.parentResult?.requiredStatus !== "REJECTED_FAILED_SWEEP_REVERSAL") {
    throw new Error("Pullback protocol must bind the rejected failed-sweep result.");
  }
  const trials = protocol.trialAccounting;
  if (trials?.priorEvidenceContractCandidates !== 173 || trials.stageCandidates !== 18 ||
      trials.cumulativeCandidates !== 191 || trials.maximumCumulativeCandidates !== 192 ||
      trials.remainingCandidatesAfterStage !== 1) {
    throw new Error("Pullback trial ledger must remain 173 + 18 = 191 of 192.");
  }
  if (sha256(JSON.stringify(protocol.hypothesis)) !== EXPECTED_HYPOTHESIS_SHA256 ||
      protocol.hypothesisSha256 !== EXPECTED_HYPOTHESIS_SHA256) {
    throw new Error("Pullback hypothesis changed after declaration.");
  }
  const candidates = expandPullbackCandidates(protocol);
  if (candidates.length !== 18 || new Set(candidates.map((candidate) => candidate.id)).size !== 18) {
    throw new Error("Pullback grid must contain exactly 18 unique candidates.");
  }
  validateSources(protocol, primaryProtocol, extensionProtocol);
  validateSchedule(protocol, primaryProtocol, extensionProtocol);
  if (canonicalJson(protocol.executionContract) !== canonicalJson(primaryProtocol.executionContract) ||
      canonicalJson(protocol.statistics) !== canonicalJson(primaryProtocol.statistics)) {
    throw new Error("Pullback execution and statistics contracts must remain inherited.");
  }
  validateGates(protocol);
  const outcome = protocol.outcomePolicy;
  if (outcome?.retuneFrom2024Validation !== false ||
      outcome.validationDataMayBeAcquiredDirectly !== false ||
      outcome.externalDataMayBeAcquiredDirectly !== false ||
      outcome.freshSealedDataMayBeAcquiredDirectly !== false ||
      outcome.automaticExecutionAllowed !== false || outcome.liveExecutionAllowed !== false) {
    throw new Error("Pullback development cannot unlock later evidence or execution.");
  }
  return protocol;
}

export function expandPullbackCandidates(protocol) {
  const fixed = protocol.hypothesis.fixed;
  const grid = protocol.hypothesis.grid;
  const candidates = [];
  for (const sequenceWindowMinutes of grid.sequenceWindowMinutes) {
    for (const minimumPullbackFraction of grid.minimumPullbackFraction) {
      for (const minimumReaccelerationAbsoluteTakerImbalance of grid.minimumReaccelerationAbsoluteTakerImbalance) {
        candidates.push({
          id: [
            "spr",
            `sw${numberId(sequenceWindowMinutes)}`,
            `pb${numberId(minimumPullbackFraction)}`,
            `ri${numberId(minimumReaccelerationAbsoluteTakerImbalance)}`,
          ].join("_"),
          family: protocol.hypothesis.family,
          ...fixed,
          sequenceWindowMinutes,
          minimumPullbackFraction,
          minimumReaccelerationAbsoluteTakerImbalance,
        });
      }
    }
  }
  return candidates;
}

function validateSources(protocol, primaryProtocol, extensionProtocol) {
  const [primary, extension] = protocol.sourceEvidence ?? [];
  if (protocol.sourceEvidence?.length !== 2 ||
      primary?.protocolId !== primaryProtocol?.protocolId ||
      primary.protocolSha256 !== "0568fe88bacc55d6ab83f79e642d14a832716b06bc6b036116b298ef481e8a2d" ||
      primary.acquisitionReceiptSha256 !== "73419fc5cdddb2b1f04e53868a1bbd5d5ba3b2799e83d0d8c4a964a179ce6ee5" ||
      primary.databaseSha256 !== "054e55a07629e17a50131e06c444b044089de70a9d6511f6256e55d63278500d" ||
      extension?.protocolId !== extensionProtocol?.protocolId ||
      extension.protocolSha256 !== "41f109407b8e9076331f6c097573f789957b04e4de5ba58c3add5b08746c9049" ||
      extension.acquisitionReceiptSha256 !== "61ec8dfb8192ad27e03b0359a728f6ece78001639ad3ce69531b981169463b0a" ||
      extension.databaseSha256 !== "44724180f0150ac935d90514e98f09562632b5653e9ad76480519e71e37fe923") {
    throw new Error("Pullback source evidence binding changed.");
  }
  if (primary.blockIds.join(",") !== primaryProtocol.stages.development.primaryBlocks.map((block) => block.id).join(",") ||
      extension.blockIds.join(",") !== extensionProtocol.blocks.map((block) => block.id).join(",")) {
    throw new Error("Pullback source block inventory changed.");
  }
}

function validateSchedule(protocol, primaryProtocol, extensionProtocol) {
  const schedule = protocol.chronologicalDevelopment;
  if (schedule?.candidateSelection?.blockIds?.join(",") !== SELECTION_IDS.join(",") ||
      schedule.candidateSelection.evaluationDays !== 60 || schedule.candidateSelection.selectCandidateOnce !== true ||
      schedule.validationEras?.length !== 2) {
    throw new Error("Pullback selection schedule changed.");
  }
  for (const [index, era] of schedule.validationEras.entries()) {
    if (era.blockIds.join(",") !== VALIDATION_IDS[index].join(",") || era.evaluationDays !== 30) {
      throw new Error("Pullback validation schedule changed.");
    }
  }
  const blocks = new Map([
    ...primaryProtocol.stages.development.primaryBlocks,
    ...extensionProtocol.blocks,
  ].map((block) => [block.id, block]));
  const allIds = [...SELECTION_IDS, ...VALIDATION_IDS.flat()];
  if (allIds.length !== 28 || new Set(allIds).size !== 28 || allIds.some((id) => !blocks.has(id))) {
    throw new Error("Pullback schedule must contain every development block exactly once.");
  }
  const selectionEnd = Math.max(...SELECTION_IDS.map((id) => Date.parse(blocks.get(id).replayEndAt)));
  const validationStart = Math.min(...VALIDATION_IDS.flat().map((id) => Date.parse(blocks.get(id).replayStartAt)));
  if (selectionEnd >= validationStart || selectionEnd >= Date.parse("2024-01-01T00:00:00Z")) {
    throw new Error("Pullback selection must end before 2024 validation.");
  }
}

function validateGates(protocol) {
  const audit = protocol.setupFrequencyAudit;
  if (audit?.usedReturnsOrExitOutcomes !== false || audit.selection2023RawSetups !== 381 ||
      audit.validation2024RawSetups !== 81 || audit.auditPerformedBeforeProtocolFreeze !== true) {
    throw new Error("Pullback setup-frequency audit changed.");
  }
  const fixed = protocol.hypothesis.fixed;
  if (fixed.targetR !== 3.5 || fixed.maximumHoldingMinutes !== 360) {
    throw new Error("Pullback right-tail exit contract changed.");
  }
  const eligibility = protocol.chronologicalDevelopment.candidateSelection.eligibility;
  if (eligibility?.minimumTrades !== 15 || eligibility.minimumProfitFactor !== 1 ||
      eligibility.minimumMeanNetR !== 0 || eligibility.maximumDrawdownPct !== 10 ||
      eligibility.maximumLiquidationCount !== 0) {
    throw new Error("Pullback selection eligibility changed.");
  }
  const gate = protocol.chronologicalDevelopment.validationGate;
  if (gate?.minimumTrades !== 12 || gate.minimumLongTrades !== 4 || gate.minimumShortTrades !== 4 ||
      gate.minimumPositiveEraCount !== 2 || gate.totalEraCount !== 2 || gate.minimumProfitFactor !== 1.2 ||
      gate.minimumMeanNetR !== 0 || gate.minimumBootstrapLowerMeanNetR !== 0 ||
      gate.maximumDrawdownPct !== 15 || gate.maximumLiquidationCount !== 0 ||
      gate.maximumWinnerProfitConcentration !== 0.35) {
    throw new Error("Pullback validation gate changed.");
  }
}

function canonicalJson(value) {
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
  if (value != null && typeof value === "object") {
    return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`).join(",")}}`;
  }
  return JSON.stringify(value);
}

function numberId(value) {
  return String(value).replace(".", "p");
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}
