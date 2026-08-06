import { createHash } from "node:crypto";
import fs from "node:fs/promises";
import { dirname, resolve } from "node:path";

import {
  expandSubminuteCandidates,
  validateSubminuteSequenceProtocol,
} from "./subminute-sequence-protocol.mjs";

const EXPECTED_PARENT_RESULT_SHA256 = "b20b5da6298194da4c3c4951b985976f98f208b743050283173787a88f3d38a0";
const EXPECTED_PARENT_PROTOCOL_SHA256 = "24b5ffe11cf86ecf9452cfb5caa937e49358729198cf4e9b5aad074693e5dbb9";
const EXPECTED_ACQUISITION_RECEIPT_SHA256 = "05e98d5b7628b3a75ff89f4150209538a21eb45058fc8856197a5a088eeb3ad9";
const EXPECTED_HYPOTHESIS_SHA256 = "95259c390ba2be6b8791f999f45a0bf5b77fc0996378b54cd69cfb4b4cebc777";

export async function loadNormalizedSubminuteProtocol(path) {
  const repositoryRoot = resolve(dirname(path), "..");
  const declarationBytes = await fs.readFile(path);
  const declaration = JSON.parse(declarationBytes);
  const [parentProtocolBytes, parentResultBytes, receiptBytes] = await Promise.all([
    fs.readFile(resolve(repositoryRoot, declaration.parentEvidence.protocolPath)),
    fs.readFile(resolve(repositoryRoot, declaration.parentEvidence.resultPath)),
    fs.readFile(resolve(repositoryRoot, declaration.parentEvidence.acquisitionReceiptPath)),
  ]);
  const parentProtocol = validateSubminuteSequenceProtocol(JSON.parse(parentProtocolBytes));
  validateNormalizedSubminuteProtocol({
    declaration,
    parentProtocol,
    parentProtocolSha256: sha256(parentProtocolBytes),
    parentResult: JSON.parse(parentResultBytes),
    parentResultSha256: sha256(parentResultBytes),
    receipt: JSON.parse(receiptBytes),
    receiptSha256: sha256(receiptBytes),
  });
  return {
    declaration,
    effectiveProtocol: buildEffectiveProtocol(declaration, parentProtocol),
    sha256: sha256(declarationBytes),
    parentProtocolSha256: sha256(parentProtocolBytes),
    parentResultSha256: sha256(parentResultBytes),
    acquisitionReceiptSha256: sha256(receiptBytes),
  };
}

export function validateNormalizedSubminuteProtocol({
  declaration,
  parentProtocol,
  parentProtocolSha256,
  parentResult,
  parentResultSha256,
  receipt,
  receiptSha256,
}) {
  if (declaration?.protocolId !== "bybit-subminute-sequence-normalized-v2" ||
      declaration.status !== "PREDECLARED_AFTER_V1_SCALE_FAILURE_BEFORE_NORMALIZED_OUTCOME_REPLAY") {
    throw new Error("Normalized subminute v2 must remain predeclared before outcome replay.");
  }
  if (parentResultSha256 !== EXPECTED_PARENT_RESULT_SHA256 ||
      declaration.parentEvidence?.resultSha256 !== EXPECTED_PARENT_RESULT_SHA256 ||
      parentResult?.programStatus !== "CLOSED_NO_APPROVABLE_SUBMINUTE_SEQUENCE_STRATEGY_V1") {
    throw new Error("Normalized v2 must bind the closed v1 result.");
  }
  if (parentProtocolSha256 !== EXPECTED_PARENT_PROTOCOL_SHA256 ||
      declaration.parentEvidence.protocolSha256 !== EXPECTED_PARENT_PROTOCOL_SHA256 ||
      receiptSha256 !== EXPECTED_ACQUISITION_RECEIPT_SHA256 ||
      declaration.parentEvidence.acquisitionReceiptSha256 !== EXPECTED_ACQUISITION_RECEIPT_SHA256 ||
      receipt?.status !== "COMPLETE_SELECTION_EVIDENCE_SEALED") {
    throw new Error("Normalized v2 parent protocol or acquisition evidence changed.");
  }
  if (declaration.parentEvidence.selectionSnapshot !== receipt.stageSnapshot ||
      declaration.parentEvidence.selectionSnapshotSha256 !== receipt.stageSnapshotSha256 ||
      declaration.parentEvidence.normalizedSourceFeatureSha256 !== receipt.normalizedFeatureSha256) {
    throw new Error("Normalized v2 must reuse the immutable 2023 source snapshot.");
  }
  validateResearchBoundary(declaration.researchBoundary);
  validateTrialAccounting(declaration.trialAccounting);
  validateInheritedContracts(declaration.inheritedContracts, parentProtocol);
  validateNormalizedFeature(declaration.featureContract);
  validateHypotheses(declaration, parentProtocol);
  validateOutcome(declaration.outcomePolicy);
  return declaration;
}

export function buildEffectiveProtocol(declaration, parentProtocol) {
  return {
    ...parentProtocol,
    protocolId: declaration.protocolId,
    status: declaration.status,
    trialAccounting: {
      priorClosedProgramCandidates: declaration.trialAccounting.priorObservedCandidates,
      newProgramCandidateBudget: declaration.trialAccounting.newCandidateBudget,
      absorptionReversalCandidates: declaration.trialAccounting.absorptionReversalCandidates,
      depletionContinuationCandidates: declaration.trialAccounting.depletionContinuationCandidates,
      candidateBudgetMayIncreaseAfterOutcomes: false,
    },
    hypotheses: declaration.hypotheses,
    hypothesisSha256: declaration.hypothesisSha256,
    outcomePolicy: declaration.outcomePolicy,
  };
}

function validateResearchBoundary(boundary) {
  if (boundary?.v1OutcomesMayMotivateScaleNormalizationOnly !== true ||
      boundary.v1OutcomesMayRetuneSetupOrExitThresholds !== false ||
      boundary.selectionReusesExplicit2023DevelopmentEvidence !== true ||
      boundary.internalValidation2024RemainsUnread !== true ||
      boundary.normalizedCandidateOutcomesReadBeforeDeclaration !== false ||
      boundary.locked2025And2026DataMayBeRead !== false) {
    throw new Error("Normalized v2 research boundary changed.");
  }
}

function validateTrialAccounting(trials) {
  if (trials?.priorObservedCandidates !== 223 || trials.newCandidateBudget !== 32 ||
      trials.absorptionReversalCandidates !== 16 || trials.depletionContinuationCandidates !== 16 ||
      trials.cumulativeCandidateCountAfterReplay !== 255 ||
      trials.candidateBudgetMayIncreaseAfterOutcomes !== false) {
    throw new Error("Normalized v2 must account for exactly 32 new candidates.");
  }
}

function validateInheritedContracts(inherited, parent) {
  const actual = {
    acquisitionSha256: hashObject(parent.acquisition),
    executionContractSha256: hashObject(parent.executionContract),
    selectionGateSha256: hashObject(parent.selection2023),
    internalValidationGateSha256: hashObject(parent.internalValidation2024),
    statisticsSha256: hashObject(parent.statistics),
  };
  for (const [name, value] of Object.entries(actual)) {
    if (inherited?.[name] !== value) throw new Error(`Normalized v2 inherited ${name} changed.`);
  }
}

function validateNormalizedFeature(features) {
  const microprice = features?.micropriceConfirmation;
  if (microprice?.mode !== "CLOSE_SPREAD_NORMALIZED_CLAMPED" ||
      microprice.formula !== "clamp(meanMicropriceEdgeBps / (closeSpreadBps / 2), -1, 1)" ||
      microprice.minimumDirectionalValue !== 0.2 ||
      microprice.sameClosedFiveSecondBucketOnly !== true ||
      microprice.zeroOrCrossedSpreadPolicy !== "NO_CONFIRMATION" ||
      features.allOtherFeaturesInheritedWithoutChange !== true) {
    throw new Error("Normalized microprice feature contract changed.");
  }
}

function validateHypotheses(declaration, parent) {
  if (declaration.hypothesisSha256 !== EXPECTED_HYPOTHESIS_SHA256 ||
      hashObject(declaration.hypotheses) !== EXPECTED_HYPOTHESIS_SHA256 ||
      declaration.hypotheses?.length !== parent.hypotheses.length) {
    throw new Error("Normalized v2 hypotheses changed after declaration.");
  }
  const parentByFamily = new Map(parent.hypotheses.map((hypothesis) => [hypothesis.family, hypothesis]));
  for (const hypothesis of declaration.hypotheses) {
    const source = parentByFamily.get(hypothesis.parentFamily);
    if (source == null || hypothesis.family !== source.family ||
        JSON.stringify(hypothesis.grid) !== JSON.stringify(source.grid) ||
        hypothesis.candidateCount !== source.candidateCount ||
        hypothesis.fixed.minimumAbsoluteTakerImbalance !== source.fixed.minimumAbsoluteTakerImbalance ||
        hypothesis.fixed.minimumRelativeTakerNotional !== source.fixed.minimumRelativeTakerNotional ||
        hypothesis.fixed.confirmationWindowSeconds !== source.fixed.confirmationWindowSeconds ||
        hypothesis.fixed.minimumConfirmationAbsoluteTakerImbalance !== source.fixed.minimumConfirmationAbsoluteTakerImbalance ||
        hypothesis.fixed.micropriceConfirmationMode !== "CLOSE_SPREAD_NORMALIZED_CLAMPED" ||
        hypothesis.fixed.minimumNormalizedMicropriceEdge !== 0.2) {
      throw new Error(`Normalized v2 family ${hypothesis.family} retuned more than microprice scale.`);
    }
  }
  const candidates = expandSubminuteCandidates({ hypotheses: declaration.hypotheses });
  if (candidates.length !== 32 || new Set(candidates.map((candidate) => candidate.id)).size !== 32) {
    throw new Error("Normalized v2 grid must expand to 32 candidates.");
  }
}

function validateOutcome(outcome) {
  if (outcome?.selectionUsesParent2023SnapshotOnly !== true ||
      outcome.oneCandidatePerFamilyMaximum !== true || outcome.retuneFromSelection !== false ||
      outcome.internalValidation2024MayBeAcquiredOnlyAfterSelectionPass !== true ||
      outcome.retuneFromInternalValidation !== false ||
      outcome.lockedExpansionMayBeAcquiredOnlyAfterInternalPass !== true ||
      outcome.validation2025MayBeAcquiredOnlyAfterExpansionPass !== true ||
      outcome.external2026MayBeAcquiredOnlyAfterValidationPassAndRefreeze !== true ||
      outcome.freshSealedMayBeAcquiredOnlyAfterExternalPassAndRefreeze !== true ||
      outcome.automaticExecutionAllowed !== false || outcome.liveExecutionAllowed !== false) {
    throw new Error("Normalized v2 outcome policy changed.");
  }
}

function hashObject(value) {
  return sha256(JSON.stringify(value));
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}
