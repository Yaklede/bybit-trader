import { createHash } from "node:crypto";
import fs from "node:fs/promises";
import { dirname, resolve } from "node:path";

const EXPECTED_RESULT_SHA256 = "e281833750f30e1b6188244525d2b694f36938e249d7d22fc667f25410c106dc";
const EXPECTED_DEVELOPMENT_PROTOCOL_SHA256 = "cc79d223ed4c4cf3e68b9d8bf449513f18d37e7f6a988083996279bd4652f7f2";
const EXPECTED_CANDIDATE_SHA256 = "46bac80f02e00cdbcb4605e785a0f2dee6865d983c9082e326615381dd91dfcf";
const EXPECTED_SIMULATOR_SHA256 = "561d3b11a9d73cd8f82e35338aafeaab92d55f65728015be7510c7abc1a92c5c";

export async function loadCostRecoveryCarrySealedProtocol(path) {
  const bytes = await fs.readFile(path);
  const protocol = JSON.parse(bytes);
  const repositoryRoot = resolve(dirname(path), "..");
  const resultBytes = await fs.readFile(resolve(repositoryRoot, protocol.parentDevelopmentResult.path));
  const developmentProtocolBytes = await fs.readFile(resolve(
    repositoryRoot,
    protocol.baseDevelopmentProtocol.path,
  ));
  const simulatorBytes = await fs.readFile(resolve(
    repositoryRoot,
    protocol.implementationBinding.simulatorPath,
  ));
  const context = {
    protocol,
    developmentResult: JSON.parse(resultBytes),
    developmentResultSha256: sha256(resultBytes),
    developmentProtocol: JSON.parse(developmentProtocolBytes),
    developmentProtocolSha256: sha256(developmentProtocolBytes),
    simulatorSha256: sha256(simulatorBytes),
  };
  validateCostRecoveryCarrySealedProtocol(context);
  return {
    ...context,
    sha256: sha256(bytes),
  };
}

export function validateCostRecoveryCarrySealedProtocol(context) {
  const {
    protocol,
    developmentResult,
    developmentResultSha256,
    developmentProtocolSha256,
    simulatorSha256,
  } = context;
  if (protocol?.protocolId !== "bybit-multi-asset-cost-recovery-carry-sealed-2026-h1-v3" ||
      protocol.status !== "PREDECLARED_BEFORE_SEALED_2026_H1_EVIDENCE_ACQUISITION") {
    throw new Error("Cost-recovery sealed protocol identity changed.");
  }
  if (developmentResultSha256 !== EXPECTED_RESULT_SHA256 ||
      protocol.parentDevelopmentResult?.sha256 !== EXPECTED_RESULT_SHA256 ||
      developmentResult?.programStatus !== "PASSED_DEVELOPMENT_ONLY_FROZEN_FOR_2026_SEAL" ||
      developmentResult.developmentGate?.passed !== true ||
      developmentResult.decision?.sealed2026AcquisitionAllowed !== true ||
      developmentResult.evidenceBoundary?.sealed2026Read !== false) {
    throw new Error("Sealed protocol development lineage changed.");
  }
  if (developmentProtocolSha256 !== EXPECTED_DEVELOPMENT_PROTOCOL_SHA256 ||
      protocol.baseDevelopmentProtocol?.sha256 !== EXPECTED_DEVELOPMENT_PROTOCOL_SHA256) {
    throw new Error("Sealed protocol execution lineage changed.");
  }
  if (simulatorSha256 !== EXPECTED_SIMULATOR_SHA256 ||
      protocol.implementationBinding?.simulatorSha256 !== EXPECTED_SIMULATOR_SHA256 ||
      protocol.implementationBinding.simulatorMayChangeBeforeSealedOutcome !== false) {
    throw new Error("Sealed protocol simulator changed.");
  }
  validateBoundary(protocol.researchBoundary);
  validateTrials(protocol.trialAccounting);
  validateCandidate(protocol, developmentResult);
  validateSource(protocol.sourceData);
  validateBlocks(protocol.sealedValidationBlocks);
  validateGate(protocol.sealedValidationGate);
  validateOutcomePolicy(protocol.outcomePolicy);
  return protocol;
}

function validateBoundary(boundary) {
  if (boundary?.kind !== "DISCLOSED_2023_2025_DEVELOPMENT_THEN_ONE_TIME_2026_H1_SEAL" ||
      boundary.development2023Through2025Read !== true ||
      boundary.sealed2026OfficialPayloadsReadBeforeDeclaration !== false ||
      boundary.sealed2026PortfolioOutcomeReadBeforeDeclaration !== false ||
      boundary.candidateRetunedAfterDevelopmentSelection !== false ||
      boundary.sealedGateDesignedAfterSealedOutcome !== false ||
      boundary.freshForwardShadowAndPaperRequiredBeforeLive !== true) {
    throw new Error("Sealed evidence boundary changed.");
  }
}

function validateTrials(trials) {
  if (trials?.priorObservedCandidatesAndProtocols !== 390 ||
      trials.evaluatedCandidateCount !== 1 || trials.newProtocolDecisionCount !== 1 ||
      trials.cumulativeObservedCandidatesAndProtocolsAfterSeal !== 391) {
    throw new Error("Sealed trial accounting changed.");
  }
}

function validateCandidate(protocol, developmentResult) {
  const expected = {
    id: developmentResult.selectedCandidate.id,
    family: "MULTI_ASSET_COST_RECOVERY_POSITIVE_FUNDING_CARRY",
    ...developmentResult.selectedCandidate.parameters,
  };
  if (protocol.selectedCandidateSha256 !== EXPECTED_CANDIDATE_SHA256 ||
      sha256(JSON.stringify(protocol.selectedCandidate)) !== EXPECTED_CANDIDATE_SHA256 ||
      JSON.stringify(protocol.selectedCandidate) !== JSON.stringify(expected) ||
      developmentResult.selectedCandidate.sha256 !== EXPECTED_CANDIDATE_SHA256) {
    throw new Error("Sealed candidate differs from the frozen development selection.");
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
      source.requestLimit?.funding !== 200 || source.stageStart !== "2026-01-01T00:00:00Z" ||
      source.stageEndExclusive !== "2026-07-01T00:00:00Z") {
    throw new Error("Sealed source contract changed.");
  }
}

function validateBlocks(blocks) {
  const expected = [
    ["S01", "2026Q1", "2026-01-01T00:00:00Z", "2026-04-01T00:00:00Z"],
    ["S02", "2026Q2", "2026-04-01T00:00:00Z", "2026-07-01T00:00:00Z"],
  ];
  if (!Array.isArray(blocks) || JSON.stringify(blocks.map((block) => [
    block.id,
    block.era,
    block.startAt,
    block.endAt,
  ])) !== JSON.stringify(expected)) {
    throw new Error("Sealed validation blocks changed.");
  }
}

function validateGate(gate) {
  const expected = {
    minimumClosedPositions: 3,
    minimumActiveCalendarDays: 90,
    minimumCapturedFundingSettlements: 270,
    minimumTradedAssetCount: 2,
    minimumPositiveAssetCount: 2,
    minimumPositiveBlockCount: 1,
    totalBlockCount: 2,
    minimumNetReturnPct: 0,
    minimumProfitFactor: 1.1,
    minimumMeanDailyReturnPct: 0,
    minimumBootstrapLowerMeanDailyReturnPct: 0,
    maximumDrawdownPct: 5,
    maximumLiquidationCount: 0,
    maximumPositivePositionProfitConcentration: 0.6,
    maximumPositiveAssetProfitConcentration: 0.75,
    maximumNetHedgeMismatchBySymbol: { BTCUSDT: 0.000001, ETHUSDT: 0.00001, SOLUSDT: 0.0001 },
    costStressMultiplier: 1.5,
    costStressMinimumNetReturnPct: 0,
    secondLegDelayStressMinimumNetReturnPct: 0,
  };
  if (JSON.stringify(gate) !== JSON.stringify(expected)) {
    throw new Error("Sealed validation gate changed.");
  }
}

function validateOutcomePolicy(policy) {
  if (policy?.oneTimeSealedReplay !== true || policy.candidateMayBeRetunedAfterOutcome !== false ||
      policy.gateMayBeChangedAfterOutcome !== false || policy.failedCandidateMustBeRejected !== true ||
      policy.passedCandidateRequiresFreshForwardShadow !== true ||
      policy.passedCandidateRequiresPaperExecutionParity !== true ||
      policy.sealedPassGrantsLiveExecution !== false ||
      policy.automaticExecutionAllowed !== false || policy.liveExecutionAllowed !== false) {
    throw new Error("Sealed outcome policy changed.");
  }
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}
