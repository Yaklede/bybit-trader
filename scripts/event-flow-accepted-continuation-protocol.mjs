import { createHash } from "node:crypto";
import fs from "node:fs/promises";

const EXPECTED_PARENT_RESULT_SHA256 = "ab41a1a0cb58697c98a192193879748872f5c1b8fe9a4d9b15413002bbaedacd";
const EXPECTED_HYPOTHESIS_SHA256 = "305f33d62eabf4feedabeab43b727cf3c14b969bd35af65681e9dde4b3b1b72a";
const EXPECTED_SELECTION_IDS = [
  "D01", "D02", "D03", "D04", "D05", "D06",
  "X01", "X02", "X03", "X04", "X05", "X06", "X07", "X08",
];
const EXPECTED_VALIDATION_IDS = [
  ["D07", "D08", "D09", "X09", "X10", "X11", "X12"],
  ["D10", "D11", "D12", "X13", "X14", "X15", "X16"],
];

export async function loadAcceptedContinuationProtocol(path, primaryProtocolPath, extensionProtocolPath) {
  const [protocolBytes, primaryBytes, extensionBytes] = await Promise.all([
    fs.readFile(path),
    fs.readFile(primaryProtocolPath),
    fs.readFile(extensionProtocolPath),
  ]);
  return {
    protocol: validateAcceptedContinuationProtocol(
      JSON.parse(protocolBytes),
      JSON.parse(primaryBytes),
      JSON.parse(extensionBytes),
    ),
    sha256: sha256(protocolBytes),
    sourceProtocolSha256: [sha256(primaryBytes), sha256(extensionBytes)],
  };
}

export function validateAcceptedContinuationProtocol(protocol, primaryProtocol, extensionProtocol) {
  if (protocol?.protocolId !== "bybit-event-flow-accepted-continuation-v1" ||
      protocol.status !== "PREDECLARED_DEVELOPMENT_HYPOTHESIS_BEFORE_REPLAY") {
    throw new Error("Accepted continuation protocol must be frozen before replay.");
  }
  if (protocol.parentResult?.resultReceiptSha256 !== EXPECTED_PARENT_RESULT_SHA256 ||
      protocol.parentResult?.requiredStatus !== "REJECTED_FIXED_EXTENSION" ||
      protocol.parentResult?.extensionEvidenceIsDevelopmentOnly !== true) {
    throw new Error("Accepted continuation protocol must bind the rejected fixed-extension result.");
  }
  const trials = protocol.trialAccounting;
  if (trials?.priorEvidenceContractCandidates !== 141 || trials.stageCandidates !== 16 ||
      trials.cumulativeCandidates !== 157 || trials.maximumCumulativeCandidates !== 192 ||
      trials.remainingCandidatesAfterStage !== 35) {
    throw new Error("Accepted continuation trial ledger must remain 141 + 16 = 157 of 192.");
  }
  if (sha256(JSON.stringify(protocol.hypothesis)) !== EXPECTED_HYPOTHESIS_SHA256 ||
      protocol.hypothesisSha256 !== EXPECTED_HYPOTHESIS_SHA256) {
    throw new Error("Accepted continuation hypothesis changed after declaration.");
  }
  const candidates = expandAcceptedContinuationCandidates(protocol);
  if (candidates.length !== 16 || new Set(candidates.map((candidate) => candidate.id)).size !== 16) {
    throw new Error("Accepted continuation candidate grid must contain exactly 16 unique candidates.");
  }
  validateSources(protocol, primaryProtocol, extensionProtocol);
  validateChronology(protocol, primaryProtocol, extensionProtocol);
  if (canonicalJson(protocol.executionContract) !== canonicalJson(primaryProtocol.executionContract) ||
      canonicalJson(protocol.statistics) !== canonicalJson(primaryProtocol.statistics)) {
    throw new Error("Accepted continuation execution and statistics contracts must remain inherited.");
  }
  validateGates(protocol);
  const outcome = protocol.outcomePolicy;
  if (outcome?.retuneFrom2024Validation !== false ||
      outcome.validationDataMayBeAcquiredDirectly !== false ||
      outcome.externalDataMayBeAcquiredDirectly !== false ||
      outcome.freshSealedDataMayBeAcquiredDirectly !== false ||
      outcome.automaticExecutionAllowed !== false || outcome.liveExecutionAllowed !== false) {
    throw new Error("Accepted continuation development cannot unlock later evidence or execution.");
  }
  return protocol;
}

export function expandAcceptedContinuationCandidates(protocol) {
  const fixed = protocol.hypothesis.fixed;
  const grid = protocol.hypothesis.grid;
  const candidates = [];
  for (const confirmationWindowMinutes of grid.confirmationWindowMinutes) {
    for (const minimumConfirmationAbsoluteTakerImbalance of grid.minimumConfirmationAbsoluteTakerImbalance) {
      for (const minimumConfirmationRelativeTakerNotional of grid.minimumConfirmationRelativeTakerNotional) {
        for (const minimumConfirmationAlignedTop5Imbalance of grid.minimumConfirmationAlignedTop5Imbalance) {
          candidates.push({
            id: [
              "adc",
              `cw${numberId(confirmationWindowMinutes)}`,
              `ci${numberId(minimumConfirmationAbsoluteTakerImbalance)}`,
              `rn${numberId(minimumConfirmationRelativeTakerNotional)}`,
              `ob${numberId(minimumConfirmationAlignedTop5Imbalance)}`,
            ].join("_"),
            family: protocol.hypothesis.family,
            ...fixed,
            confirmationWindowMinutes,
            minimumConfirmationAbsoluteTakerImbalance,
            minimumConfirmationRelativeTakerNotional,
            minimumConfirmationAlignedTop5Imbalance,
          });
        }
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
    throw new Error("Accepted continuation source evidence binding changed.");
  }
  const primaryIds = primaryProtocol.stages.development.primaryBlocks.map((block) => block.id);
  const extensionIds = extensionProtocol.blocks.map((block) => block.id);
  if (primary.blockIds.join(",") !== primaryIds.join(",") || extension.blockIds.join(",") !== extensionIds.join(",")) {
    throw new Error("Accepted continuation source block inventory changed.");
  }
}

function validateChronology(protocol, primaryProtocol, extensionProtocol) {
  const schedule = protocol.chronologicalDevelopment;
  if (schedule?.candidateSelection?.blockIds?.join(",") !== EXPECTED_SELECTION_IDS.join(",") ||
      schedule.candidateSelection.evaluationDays !== 60 ||
      schedule.candidateSelection.selectCandidateOnce !== true ||
      schedule.validationEras?.length !== 2) {
    throw new Error("Accepted continuation selection schedule changed.");
  }
  for (const [index, era] of schedule.validationEras.entries()) {
    if (era.blockIds.join(",") !== EXPECTED_VALIDATION_IDS[index].join(",") || era.evaluationDays !== 30) {
      throw new Error("Accepted continuation validation schedule changed.");
    }
  }
  const blocks = new Map([
    ...primaryProtocol.stages.development.primaryBlocks,
    ...extensionProtocol.blocks,
  ].map((block) => [block.id, block]));
  const allIds = [
    ...schedule.candidateSelection.blockIds,
    ...schedule.validationEras.flatMap((era) => era.blockIds),
  ];
  if (allIds.length !== 28 || new Set(allIds).size !== 28 || allIds.some((id) => !blocks.has(id))) {
    throw new Error("Accepted continuation chronological blocks must be complete and unique.");
  }
  const selectionEnd = Math.max(...schedule.candidateSelection.blockIds.map((id) => Date.parse(blocks.get(id).replayEndAt)));
  const validationStart = Math.min(...schedule.validationEras.flatMap((era) => era.blockIds)
    .map((id) => Date.parse(blocks.get(id).replayStartAt)));
  if (selectionEnd >= validationStart || selectionEnd >= Date.parse("2024-01-01T00:00:00Z")) {
    throw new Error("Accepted continuation candidate selection must end before 2024 validation.");
  }
  const days = (ids) => ids.reduce((sum, id) => sum +
    (Date.parse(blocks.get(id).replayEndAt) - Date.parse(blocks.get(id).replayStartAt)) / 86_400_000, 0);
  if (days(schedule.candidateSelection.blockIds) !== 60 ||
      schedule.validationEras.some((era) => days(era.blockIds) !== 30)) {
    throw new Error("Accepted continuation evaluation-day accounting changed.");
  }
}

function validateGates(protocol) {
  const eligibility = protocol.chronologicalDevelopment.candidateSelection.eligibility;
  if (eligibility?.minimumTrades !== 20 || eligibility.minimumProfitFactor !== 1 ||
      eligibility.minimumMeanNetR !== 0 || eligibility.maximumDrawdownPct !== 10 ||
      eligibility.maximumLiquidationCount !== 0) {
    throw new Error("Accepted continuation selection eligibility changed.");
  }
  const gate = protocol.chronologicalDevelopment.validationGate;
  if (gate?.minimumTrades !== 15 || gate.minimumLongTrades !== 5 || gate.minimumShortTrades !== 5 ||
      gate.minimumPositiveEraCount !== 2 || gate.totalEraCount !== 2 || gate.minimumProfitFactor !== 1.2 ||
      gate.minimumMeanNetR !== 0 || gate.minimumBootstrapLowerMeanNetR !== 0 ||
      gate.maximumDrawdownPct !== 15 || gate.maximumLiquidationCount !== 0 ||
      gate.maximumWinnerProfitConcentration !== 0.3) {
    throw new Error("Accepted continuation validation gate changed.");
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
