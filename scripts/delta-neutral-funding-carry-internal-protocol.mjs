import { createHash } from "node:crypto";
import fs from "node:fs/promises";
import { dirname, resolve } from "node:path";

import { expandDeltaNeutralFundingCarryCandidates } from "./delta-neutral-funding-carry-protocol.mjs";

const EXPECTED_PARENT_RESULT_SHA256 = "793ea67b27c41e07cb2e7add4516f81618436f0d9af31cf27080482d5d4acd81";
const EXPECTED_DEVELOPMENT_PROTOCOL_SHA256 = "6a3cdf6c4f56c37343e106b54fda9474f1b08d1b78e85fa73e6752c0d9c961b3";
const EXPECTED_CANDIDATE_SHA256 = "eba52739bfa5d266de0e3b21f49a71637c9080e44cfd3821f601b3d2ef6be509";
const EXPECTED_RESEARCH_LIBRARY_SHA256 = "9622e0c7d17163e244bde2094a910f524ff6bc046491d41a968afe1847d46424";

export async function loadDeltaNeutralFundingCarryInternalProtocol(path) {
  const bytes = await fs.readFile(path);
  const protocol = JSON.parse(bytes);
  const root = resolve(dirname(path), "..");
  const parentBytes = await fs.readFile(resolve(root, protocol.parentDevelopmentResult.path));
  const developmentBytes = await fs.readFile(resolve(root, protocol.developmentProtocol.path));
  const libraryBytes = await fs.readFile(resolve(root, protocol.implementationBinding.researchLibraryPath));
  validateDeltaNeutralFundingCarryInternalProtocol({
    protocol,
    parentResult: JSON.parse(parentBytes),
    parentResultSha256: sha256(parentBytes),
    developmentProtocol: JSON.parse(developmentBytes),
    developmentProtocolSha256: sha256(developmentBytes),
    researchLibrarySha256: sha256(libraryBytes),
  });
  return {
    protocol,
    sha256: sha256(bytes),
    parentResultSha256: sha256(parentBytes),
    developmentProtocolSha256: sha256(developmentBytes),
    researchLibrarySha256: sha256(libraryBytes),
  };
}

export function validateDeltaNeutralFundingCarryInternalProtocol(context) {
  const { protocol, parentResult, parentResultSha256, developmentProtocol,
    developmentProtocolSha256, researchLibrarySha256 } = context;
  if (protocol?.protocolId !== "bybit-delta-neutral-funding-carry-internal-v1" ||
      protocol.status !== "PREDECLARED_BEFORE_INTERNAL_TWO_LEG_EVIDENCE_ACQUISITION") {
    throw new Error("Delta-neutral internal protocol must remain frozen before acquisition.");
  }
  if (parentResultSha256 !== EXPECTED_PARENT_RESULT_SHA256 ||
      protocol.parentDevelopmentResult?.sha256 !== EXPECTED_PARENT_RESULT_SHA256 ||
      parentResult?.programStatus !== "DEVELOPMENT_CANDIDATE_FROZEN_FOR_INTERNAL_VALIDATION" ||
      parentResult.internalValidation?.acquisitionAllowed !== true) {
    throw new Error("Internal validation must bind an eligible development result.");
  }
  if (developmentProtocolSha256 !== EXPECTED_DEVELOPMENT_PROTOCOL_SHA256 ||
      protocol.developmentProtocol?.sha256 !== EXPECTED_DEVELOPMENT_PROTOCOL_SHA256) {
    throw new Error("Internal validation must bind the original development protocol.");
  }
  if (researchLibrarySha256 !== EXPECTED_RESEARCH_LIBRARY_SHA256 ||
      protocol.implementationBinding?.researchLibrarySha256 !== EXPECTED_RESEARCH_LIBRARY_SHA256 ||
      protocol.implementationBinding.simulatorMayChangeBeforeInternalOutcome !== false) {
    throw new Error("Internal validation simulator changed after candidate selection.");
  }
  validateBoundary(protocol.researchBoundary);
  validateTrials(protocol.trialAccounting);
  validateCandidate(protocol, parentResult, developmentProtocol);
  validateSource(protocol.sourceData);
  validateBlocks(protocol.internalValidationBlocks);
  if (JSON.stringify(protocol.internalValidationGate) !== JSON.stringify(developmentProtocol.internalValidationGate)) {
    throw new Error("Internal validation gate differs from the development declaration.");
  }
  validateOutcome(protocol.outcomePolicy);
  return protocol;
}

function validateBoundary(boundary) {
  if (boundary?.kind !== "FROZEN_CANDIDATE_CHRONOLOGICAL_INTERNAL_VALIDATION" ||
      boundary.historicalBtcPriceEraPreviouslyObserved !== true ||
      boundary.officialSpotPerpetualMarkIndexFunding2024PayloadsReadBeforeDeclaration !== false ||
      boundary.developmentOutcomeMayRetuneCandidate !== false || boundary.internalOutcomeMayRetuneCandidate !== false ||
      boundary.external2025RemainsLocked !== true || boundary.sealed2026RemainsLocked !== true ||
      boundary.freshForwardSealRequiredBeforeLive !== true) {
    throw new Error("Internal validation evidence boundary changed.");
  }
}

function validateTrials(trials) {
  if (trials?.priorObservedCandidates !== 311 || trials.newCandidateBudget !== 0 ||
      trials.cumulativeCandidateCountAfterInternalValidation !== 311) {
    throw new Error("Internal validation cannot add or retune candidates.");
  }
}

function validateCandidate(protocol, parentResult, developmentProtocol) {
  if (protocol.selectedCandidateSha256 !== EXPECTED_CANDIDATE_SHA256 ||
      sha256(JSON.stringify(protocol.selectedCandidate)) !== EXPECTED_CANDIDATE_SHA256 ||
      parentResult.selectedCandidate?.candidateSha256 !== EXPECTED_CANDIDATE_SHA256 ||
      JSON.stringify(parentResult.selectedCandidate.candidate) !== JSON.stringify(protocol.selectedCandidate)) {
    throw new Error("Internal candidate differs from the frozen development selection.");
  }
  const original = expandDeltaNeutralFundingCarryCandidates(developmentProtocol)
    .find((candidate) => candidate.id === protocol.selectedCandidate.id);
  if (JSON.stringify(original) !== JSON.stringify(protocol.selectedCandidate)) {
    throw new Error("Internal candidate does not exist in the original 24-candidate grid.");
  }
}

function validateSource(source) {
  if (source?.provider !== "BYBIT_V5_PUBLIC_REST" || source.baseUrl !== "https://api.bybit.com" ||
      source.spotSymbol !== "BTCUSDT" || source.perpetualSymbol !== "BTCUSDT" ||
      source.spotKlineEndpoint !== "/v5/market/kline?category=spot" ||
      source.perpetualKlineEndpoint !== "/v5/market/kline?category=linear" ||
      source.markKlineEndpoint !== "/v5/market/mark-price-kline?category=linear" ||
      source.indexKlineEndpoint !== "/v5/market/index-price-kline?category=linear" ||
      source.fundingEndpoint !== "/v5/market/funding/history?category=linear" ||
      source.klineInterval !== "5" || source.requestLimit?.kline !== 1000 || source.requestLimit?.funding !== 200 ||
      source.stageStart !== "2024-01-01T00:00:00Z" || source.stageEndExclusive !== "2025-01-01T00:00:00Z" ||
      source.requiredSeries?.join("|") !== "SPOT_LAST|PERPETUAL_LAST|PERPETUAL_MARK|PERPETUAL_INDEX|FUNDING") {
    throw new Error("Internal validation source contract changed.");
  }
}

function validateBlocks(blocks) {
  if (!Array.isArray(blocks) || blocks.length !== 4 || blocks.some((block) => !block.era.startsWith("2024Q"))) {
    throw new Error("Internal validation must contain the four 2024 quarters.");
  }
  for (let index = 0; index < blocks.length; index += 1) {
    if (Date.parse(blocks[index].startAt) >= Date.parse(blocks[index].endAt) ||
        (index > 0 && blocks[index - 1].endAt !== blocks[index].startAt)) {
      throw new Error(`Internal block ${blocks[index].id} is invalid or discontinuous.`);
    }
  }
}

function validateOutcome(outcome) {
  if (outcome?.candidateMayBeRetuned !== false ||
      outcome.external2025MayBeAcquiredOnlyAfterEveryInternalGatePasses !== true ||
      outcome.sealed2026MayBeAcquiredOnlyAfterExternal2025PassAndRefreeze !== true ||
      outcome.freshForwardShadowAndPaperRequiredBeforeLive !== true ||
      outcome.automaticExecutionAllowed !== false || outcome.liveExecutionAllowed !== false) {
    throw new Error("Internal validation outcome policy changed.");
  }
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}
