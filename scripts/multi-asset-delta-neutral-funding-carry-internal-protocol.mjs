import { createHash } from "node:crypto";
import fs from "node:fs/promises";
import { dirname, resolve } from "node:path";

import {
  expandMultiAssetDeltaNeutralFundingCarryCandidates,
} from "./multi-asset-delta-neutral-funding-carry-protocol.mjs";

const EXPECTED_PARENT_RESULT_SHA256 = "a7ad78340bd57fc74117cf9590705e29e028b5fec00292a5b15ba95ad245ab35";
const EXPECTED_DEVELOPMENT_PROTOCOL_SHA256 = "42e9a485e747cc12d50263fee26f20318c50e04108efeeed6fa1171811625479";
const EXPECTED_CANDIDATE_SHA256 = "2b46f1abe6caef9fb31eb6ad85de2ff4973985e52d76a1f377cc8ef2e6d974e9";
const EXPECTED_SIMULATOR_SHA256 = "561d3b11a9d73cd8f82e35338aafeaab92d55f65728015be7510c7abc1a92c5c";

export async function loadMultiAssetDeltaNeutralFundingCarryInternalProtocol(path) {
  const bytes = await fs.readFile(path);
  const protocol = JSON.parse(bytes);
  const root = resolve(dirname(path), "..");
  const parentBytes = await fs.readFile(resolve(root, protocol.parentDevelopmentResult.path));
  const developmentBytes = await fs.readFile(resolve(root, protocol.developmentProtocol.path));
  const simulatorBytes = await fs.readFile(resolve(root, protocol.implementationBinding.simulatorPath));
  validateMultiAssetDeltaNeutralFundingCarryInternalProtocol({
    protocol,
    parentResult: JSON.parse(parentBytes),
    parentResultSha256: sha256(parentBytes),
    developmentProtocol: JSON.parse(developmentBytes),
    developmentProtocolSha256: sha256(developmentBytes),
    simulatorSha256: sha256(simulatorBytes),
  });
  return {
    protocol,
    sha256: sha256(bytes),
    parentResultSha256: sha256(parentBytes),
    developmentProtocolSha256: sha256(developmentBytes),
    simulatorSha256: sha256(simulatorBytes),
  };
}

export function validateMultiAssetDeltaNeutralFundingCarryInternalProtocol(context) {
  const {
    protocol,
    parentResult,
    parentResultSha256,
    developmentProtocol,
    developmentProtocolSha256,
    simulatorSha256,
  } = context;
  if (protocol?.protocolId !== "bybit-multi-asset-delta-neutral-funding-carry-internal-v1" ||
      protocol.status !== "PREDECLARED_BEFORE_INTERNAL_MULTI_ASSET_EVIDENCE_ACQUISITION") {
    throw new Error("Multi-asset internal protocol must remain frozen before acquisition.");
  }
  if (parentResultSha256 !== EXPECTED_PARENT_RESULT_SHA256 ||
      protocol.parentDevelopmentResult?.sha256 !== EXPECTED_PARENT_RESULT_SHA256 ||
      parentResult?.programStatus !== "DEVELOPMENT_CANDIDATE_FROZEN_FOR_INTERNAL_VALIDATION" ||
      parentResult.internalValidation?.acquisitionAllowed !== true) {
    throw new Error("Internal validation must bind the eligible multi-asset development result.");
  }
  if (developmentProtocolSha256 !== EXPECTED_DEVELOPMENT_PROTOCOL_SHA256 ||
      protocol.developmentProtocol?.sha256 !== EXPECTED_DEVELOPMENT_PROTOCOL_SHA256) {
    throw new Error("Internal validation must bind the original multi-asset protocol.");
  }
  if (simulatorSha256 !== EXPECTED_SIMULATOR_SHA256 ||
      protocol.implementationBinding?.simulatorSha256 !== EXPECTED_SIMULATOR_SHA256 ||
      protocol.implementationBinding.simulatorMayChangeBeforeInternalOutcome !== false) {
    throw new Error("Internal validation simulator changed after development selection.");
  }
  validateBoundary(protocol.researchBoundary);
  validateTrials(protocol.trialAccounting);
  validateCandidate(protocol, parentResult, developmentProtocol);
  validateSource(protocol.sourceData);
  validateBlocks(protocol.internalValidationBlocks);
  if (JSON.stringify(protocol.internalValidationGate) !==
      JSON.stringify(developmentProtocol.internalValidationGate)) {
    throw new Error("Internal validation gate differs from the development declaration.");
  }
  validateOutcome(protocol.outcomePolicy);
  return protocol;
}

function validateBoundary(boundary) {
  if (boundary?.kind !== "FROZEN_MULTI_ASSET_CANDIDATE_CHRONOLOGICAL_INTERNAL_VALIDATION" ||
      boundary.btc2024SingleAssetPayloadAndOutcomePreviouslyObserved !== true ||
      boundary.ethAndSol2024OfficialPayloadsReadBeforeDeclaration !== false ||
      boundary.multiAsset2024PortfolioOutcomeReadBeforeDeclaration !== false ||
      boundary.developmentOutcomeMayRetuneCandidate !== false ||
      boundary.internalOutcomeMayRetuneCandidate !== false ||
      boundary.external2025RemainsLocked !== true || boundary.sealed2026RemainsLocked !== true ||
      boundary.freshForwardSealRequiredBeforeLive !== true) {
    throw new Error("Multi-asset internal evidence boundary changed.");
  }
}

function validateTrials(trials) {
  if (trials?.priorObservedCandidates !== 335 || trials.newCandidateBudget !== 0 ||
      trials.cumulativeCandidateCountAfterInternalValidation !== 335) {
    throw new Error("Internal validation cannot add or retune multi-asset candidates.");
  }
}

function validateCandidate(protocol, parentResult, developmentProtocol) {
  if (protocol.selectedCandidateSha256 !== EXPECTED_CANDIDATE_SHA256 ||
      sha256(JSON.stringify(protocol.selectedCandidate)) !== EXPECTED_CANDIDATE_SHA256 ||
      parentResult.selectedCandidate?.candidateSha256 !== EXPECTED_CANDIDATE_SHA256 ||
      JSON.stringify(parentResult.selectedCandidate.candidate) !== JSON.stringify(protocol.selectedCandidate)) {
    throw new Error("Internal candidate differs from the frozen development selection.");
  }
  const original = expandMultiAssetDeltaNeutralFundingCarryCandidates(developmentProtocol)
    .find((candidate) => candidate.id === protocol.selectedCandidate.id);
  if (JSON.stringify(original) !== JSON.stringify(protocol.selectedCandidate)) {
    throw new Error("Internal candidate does not exist in the original 24-candidate grid.");
  }
}

function validateSource(source) {
  if (source?.symbols?.join("|") !== "BTCUSDT|ETHUSDT|SOLUSDT" ||
      source.provider !== "BYBIT_V5_PUBLIC_REST" || source.baseUrl !== "https://api.bybit.com" ||
      source.spotKlineEndpoint !== "/v5/market/kline?category=spot" ||
      source.perpetualKlineEndpoint !== "/v5/market/kline?category=linear" ||
      source.markKlineEndpoint !== "/v5/market/mark-price-kline?category=linear" ||
      source.indexKlineEndpoint !== "/v5/market/index-price-kline?category=linear" ||
      source.fundingEndpoint !== "/v5/market/funding/history?category=linear" ||
      source.klineInterval !== "5" || source.requestLimit?.kline !== 1000 ||
      source.requestLimit?.funding !== 200 || source.stageStart !== "2024-01-01T00:00:00Z" ||
      source.stageEndExclusive !== "2025-01-01T00:00:00Z" ||
      source.requiredSeriesPerSymbol?.join("|") !==
        "SPOT_LAST|PERPETUAL_LAST|PERPETUAL_MARK|PERPETUAL_INDEX|FUNDING") {
    throw new Error("Multi-asset internal source contract changed.");
  }
}

function validateBlocks(blocks) {
  if (!Array.isArray(blocks) || blocks.length !== 4 ||
      blocks.some((block) => !block.era.startsWith("2024Q"))) {
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
  if (outcome?.candidateMayBeRetuned !== false || outcome.gateMayBeChanged !== false ||
      outcome.external2025MayBeAcquiredOnlyAfterEveryInternalGatePasses !== true ||
      outcome.sealed2026MayBeAcquiredOnlyAfterExternal2025PassAndRefreeze !== true ||
      outcome.freshForwardShadowAndPaperRequiredBeforeLive !== true ||
      outcome.automaticExecutionAllowed !== false || outcome.liveExecutionAllowed !== false) {
    throw new Error("Multi-asset internal outcome policy changed.");
  }
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}
