import { createHash } from "node:crypto";
import fs from "node:fs/promises";
import { dirname, resolve } from "node:path";

const EXPECTED_PARENT_SHA256 = "3b552d5c849a0f28bc1797b1709a050eebd91248c38fdd27bc6cf4d1b29c9582";
const EXPECTED_DEVELOPMENT_RESULT_SHA256 = "a7ad78340bd57fc74117cf9590705e29e028b5fec00292a5b15ba95ad245ab35";
const EXPECTED_DEVELOPMENT_PROTOCOL_SHA256 = "42e9a485e747cc12d50263fee26f20318c50e04108efeeed6fa1171811625479";
const EXPECTED_CANDIDATE_SHA256 = "2b46f1abe6caef9fb31eb6ad85de2ff4973985e52d76a1f377cc8ef2e6d974e9";
const EXPECTED_SIMULATOR_SHA256 = "561d3b11a9d73cd8f82e35338aafeaab92d55f65728015be7510c7abc1a92c5c";

export async function loadMultiAssetDeltaNeutralFundingCarryExternalProtocol(path) {
  const bytes = await fs.readFile(path);
  const protocol = JSON.parse(bytes);
  const root = resolve(dirname(path), "..");
  const parentBytes = await fs.readFile(resolve(root, protocol.parentInternalResult.path));
  const developmentResultBytes = await fs.readFile(resolve(root, protocol.developmentResult.path));
  const developmentProtocolBytes = await fs.readFile(resolve(root, protocol.developmentProtocol.path));
  const simulatorBytes = await fs.readFile(resolve(root, protocol.implementationBinding.simulatorPath));
  validateMultiAssetDeltaNeutralFundingCarryExternalProtocol({
    protocol,
    parentResult: JSON.parse(parentBytes),
    parentSha256: sha256(parentBytes),
    developmentResult: JSON.parse(developmentResultBytes),
    developmentResultSha256: sha256(developmentResultBytes),
    developmentProtocol: JSON.parse(developmentProtocolBytes),
    developmentProtocolSha256: sha256(developmentProtocolBytes),
    simulatorSha256: sha256(simulatorBytes),
  });
  return {
    protocol,
    sha256: sha256(bytes),
    parentResultSha256: sha256(parentBytes),
    developmentResultSha256: sha256(developmentResultBytes),
    developmentProtocolSha256: sha256(developmentProtocolBytes),
    simulatorSha256: sha256(simulatorBytes),
  };
}

export function validateMultiAssetDeltaNeutralFundingCarryExternalProtocol(context) {
  const {
    protocol,
    parentResult,
    parentSha256,
    developmentResult,
    developmentResultSha256,
    developmentProtocol,
    developmentProtocolSha256,
    simulatorSha256,
  } = context;
  if (protocol?.protocolId !== "bybit-multi-asset-delta-neutral-funding-carry-external-v2" ||
      protocol.status !== "PREDECLARED_BEFORE_EXTERNAL_2025_EVIDENCE_ACQUISITION") {
    throw new Error("Multi-asset external v2 protocol identity changed.");
  }
  if (parentSha256 !== EXPECTED_PARENT_SHA256 ||
      protocol.parentInternalResult?.sha256 !== EXPECTED_PARENT_SHA256 ||
      parentResult?.programStatus !== "REJECTED_MULTI_ASSET_INTERNAL_VALIDATION" ||
      parentResult.decision?.successorProtocolMayUse2023And2024AsDisclosedDevelopmentEvidence !== true ||
      parentResult.evidenceBoundary?.external2025Read !== false) {
    throw new Error("External v2 must follow the disclosed rejected v1 result.");
  }
  if (developmentResultSha256 !== EXPECTED_DEVELOPMENT_RESULT_SHA256 ||
      protocol.developmentResult?.sha256 !== EXPECTED_DEVELOPMENT_RESULT_SHA256 ||
      developmentProtocolSha256 !== EXPECTED_DEVELOPMENT_PROTOCOL_SHA256 ||
      protocol.developmentProtocol?.sha256 !== EXPECTED_DEVELOPMENT_PROTOCOL_SHA256) {
    throw new Error("External v2 development lineage changed.");
  }
  if (simulatorSha256 !== EXPECTED_SIMULATOR_SHA256 ||
      protocol.implementationBinding?.simulatorSha256 !== EXPECTED_SIMULATOR_SHA256 ||
      protocol.implementationBinding.simulatorMayChangeBeforeExternalOutcome !== false) {
    throw new Error("External v2 simulator changed before its outcome.");
  }
  validateBoundary(protocol.researchBoundary);
  validateTrials(protocol.trialAccounting);
  validateQualification(protocol.disclosedDevelopmentQualification);
  validateCandidate(protocol, developmentResult);
  validateSource(protocol.sourceData);
  validateBlocks(protocol.externalValidationBlocks, "2025Q", 4);
  validateAnnualGate(protocol.externalValidationGate);
  validateSealedContract(protocol.sealedValidationContract);
  validateOutcome(protocol.outcomePolicy);
  assertExecutionLineage(developmentProtocol);
  return protocol;
}

function validateBoundary(boundary) {
  if (boundary?.kind !== "DISCLOSED_TWO_YEAR_DEVELOPMENT_THEN_CHRONOLOGICAL_EXTERNAL_VALIDATION" ||
      boundary.development2023Read !== true || boundary.development2024Read !== true ||
      boundary.external2025OfficialPayloadsReadBeforeDeclaration !== false ||
      boundary.external2025PortfolioOutcomeReadBeforeDeclaration !== false ||
      boundary.sealed2026OfficialPayloadsReadBeforeDeclaration !== false ||
      boundary.sealed2026PortfolioOutcomeReadBeforeDeclaration !== false ||
      boundary.candidateRetunedFrom2024Outcome !== false ||
      boundary.annualSampleGateRedesignedAfter2024Outcome !== true ||
      boundary.freshForwardSealRequiredBeforeLive !== true) {
    throw new Error("External v2 evidence boundary changed.");
  }
}

function validateTrials(trials) {
  if (trials?.priorObservedCandidatesAndProtocols !== 335 || trials.newCandidateCount !== 0 ||
      trials.newProtocolDecisionCount !== 1 ||
      trials.cumulativeObservedCandidatesAndProtocolsAfterExternal !== 336) {
    throw new Error("External v2 trial accounting changed.");
  }
}

function validateQualification(value) {
  if (value?.calendarYears !== 2 || value.totalClosedPositions !== 41 ||
      value.minimumRequiredTotalClosedPositions !== 40 || value.positiveCalendarYears !== 2 ||
      value.minimumRequiredPositiveCalendarYears !== 2 || value.positiveQuarters !== 7 ||
      value.totalQuarters !== 8 || value.compoundedNetReturnPct !== 4.68412645 ||
      value.maximumObservedDrawdownPct !== 0.44111425 ||
      value.bothYearsCostStressPositive !== true ||
      value.bothYearsSecondLegDelayStressPositive !== true ||
      value.bothYearsAllAssetsTraded !== true || value.bothYearsAtLeastTwoPositiveAssets !== true ||
      value.liquidationCount !== 0 || value.qualifiedForOneExternalProtocolTrial !== true) {
    throw new Error("External v2 disclosed development qualification changed.");
  }
}

function validateCandidate(protocol, developmentResult) {
  if (protocol.selectedCandidateSha256 !== EXPECTED_CANDIDATE_SHA256 ||
      sha256(JSON.stringify(protocol.selectedCandidate)) !== EXPECTED_CANDIDATE_SHA256 ||
      developmentResult.selectedCandidate?.candidateSha256 !== EXPECTED_CANDIDATE_SHA256 ||
      JSON.stringify(developmentResult.selectedCandidate.candidate) !==
        JSON.stringify(protocol.selectedCandidate)) {
    throw new Error("External v2 candidate differs from the frozen development candidate.");
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
      source.requestLimit?.funding !== 200 || source.stageStart !== "2025-01-01T00:00:00Z" ||
      source.stageEndExclusive !== "2026-01-01T00:00:00Z") {
    throw new Error("External v2 source contract changed.");
  }
}

function validateBlocks(blocks, eraPrefix, count) {
  if (!Array.isArray(blocks) || blocks.length !== count ||
      blocks.some((block) => !block.era.startsWith(eraPrefix))) {
    throw new Error(`Expected ${count} contiguous ${eraPrefix} blocks.`);
  }
  for (let index = 0; index < blocks.length; index += 1) {
    if (Date.parse(blocks[index].startAt) >= Date.parse(blocks[index].endAt) ||
        (index > 0 && blocks[index - 1].endAt !== blocks[index].startAt)) {
      throw new Error(`External v2 block ${blocks[index].id} is invalid or discontinuous.`);
    }
  }
}

function validateAnnualGate(gate) {
  validateCommonGate(gate);
  if (gate.minimumClosedPositions !== 12 || gate.minimumActiveCalendarDays !== 120 ||
      gate.minimumCapturedFundingSettlements !== 365 || gate.minimumPositiveBlockCount !== 3 ||
      gate.totalBlockCount !== 4 || gate.maximumPositivePositionProfitConcentration !== 0.25 ||
      gate.maximumPositiveAssetProfitConcentration !== 0.6) {
    throw new Error("External v2 annual gate changed.");
  }
}

function validateSealedContract(sealed) {
  if (sealed?.stageStart !== "2026-01-01T00:00:00Z" ||
      sealed.stageEndExclusive !== "2026-07-01T00:00:00Z") {
    throw new Error("External v2 sealed range changed.");
  }
  validateBlocks(sealed.blocks, "2026Q", 2);
  validateCommonGate(sealed.gate);
  if (sealed.gate.minimumClosedPositions !== 6 ||
      sealed.gate.minimumActiveCalendarDays !== 60 ||
      sealed.gate.minimumCapturedFundingSettlements !== 180 ||
      sealed.gate.minimumPositiveBlockCount !== 1 || sealed.gate.totalBlockCount !== 2 ||
      sealed.gate.maximumPositivePositionProfitConcentration !== 0.35 ||
      sealed.gate.maximumPositiveAssetProfitConcentration !== 0.65) {
    throw new Error("External v2 sealed gate changed.");
  }
}

function validateCommonGate(gate) {
  if (gate?.minimumTradedAssetCount !== 3 || gate.minimumPositiveAssetCount !== 2 ||
      gate.minimumNetReturnPct !== 0 || gate.minimumProfitFactor !== 1.1 ||
      gate.minimumMeanDailyReturnPct !== 0 || gate.minimumBootstrapLowerMeanDailyReturnPct !== 0 ||
      gate.maximumDrawdownPct !== 5 || gate.maximumLiquidationCount !== 0 ||
      JSON.stringify(gate.maximumNetHedgeMismatchBySymbol) !==
        JSON.stringify({ BTCUSDT: 0.000001, ETHUSDT: 0.00001, SOLUSDT: 0.0001 }) ||
      gate.costStressMultiplier !== 1.5 || gate.costStressMinimumNetReturnPct !== 0 ||
      gate.secondLegDelayStressMinimumNetReturnPct !== 0) {
    throw new Error("External v2 common economic gate changed.");
  }
}

function validateOutcome(outcome) {
  if (outcome?.candidateMayBeRetuned !== false || outcome.externalGateMayBeChanged !== false ||
      outcome.sealedGateMayBeChanged !== false ||
      outcome.sealed2026MayBeAcquiredOnlyAfterEveryExternalGatePasses !== true ||
      outcome.freshForwardShadowAndPaperRequiredBeforeLive !== true ||
      outcome.automaticExecutionAllowed !== false || outcome.liveExecutionAllowed !== false) {
    throw new Error("External v2 outcome policy changed.");
  }
}

function assertExecutionLineage(developmentProtocol) {
  if (developmentProtocol.executionContract?.startingEquityUsdt !== 660 ||
      developmentProtocol.executionContract.baseRoundTripCostRateOnMatchedNotional !== 0.0041 ||
      developmentProtocol.executionContract.maximumTotalMatchedNotionalFractionOfEquity !== 0.4 ||
      developmentProtocol.statistics?.bootstrapSamples !== 10000) {
    throw new Error("External v2 execution lineage changed.");
  }
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}
