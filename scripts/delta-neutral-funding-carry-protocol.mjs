import { createHash } from "node:crypto";
import fs from "node:fs/promises";
import { dirname, resolve } from "node:path";

const EXPECTED_PARENT_RESULT_SHA256 = "7f4ff15d0842cba7c7453b743dbcba387b780eae74e9838719b12346ef77d1e2";
const EXPECTED_CANDIDATE_DEFINITION_SHA256 = "6026a18686f95204cfe937147acda5fc7470b3df0737545a9d0f705abf4228ae";

export async function loadDeltaNeutralFundingCarryProtocol(path) {
  const bytes = await fs.readFile(path);
  const protocol = JSON.parse(bytes);
  const repositoryRoot = resolve(dirname(path), "..");
  const parentBytes = await fs.readFile(resolve(repositoryRoot, protocol.parentResult.path));
  validateDeltaNeutralFundingCarryProtocol(protocol, JSON.parse(parentBytes), sha256(parentBytes));
  return { protocol, sha256: sha256(bytes), parentResultSha256: sha256(parentBytes) };
}

export function validateDeltaNeutralFundingCarryProtocol(protocol, parentResult, parentResultSha256) {
  if (protocol?.protocolId !== "bybit-delta-neutral-funding-carry-development-v1" ||
      protocol.status !== "PREDECLARED_BEFORE_BULK_DELTA_NEUTRAL_DEVELOPMENT_ACQUISITION") {
    throw new Error("Delta-neutral funding carry protocol must remain frozen before bulk acquisition.");
  }
  if (parentResultSha256 !== EXPECTED_PARENT_RESULT_SHA256 ||
      protocol.parentResult?.sha256 !== EXPECTED_PARENT_RESULT_SHA256 ||
      parentResult?.programStatus !== "CLOSED_NO_APPROVABLE_FUNDING_CROWDING_STRATEGY_V1") {
    throw new Error("Delta-neutral research must bind the closed funding-crowding result.");
  }
  validateBoundary(protocol.researchBoundary);
  validateTrials(protocol.trialAccounting);
  validateSource(protocol.sourceData);
  validateInstrumentRules(protocol.observedInstrumentRules);
  validateSchedule(protocol.evidenceSchedule);
  validateFeatures(protocol.featureContract);
  validateCandidates(protocol);
  validateExecution(protocol.executionContract);
  validateGates(protocol.developmentGate, protocol.internalValidationGate);
  validateStatistics(protocol.statistics);
  validateOutcome(protocol.outcomePolicy);
  return protocol;
}

export function expandDeltaNeutralFundingCarryCandidates(protocol) {
  const definition = protocol.candidateDefinition;
  let rows = [{}];
  for (const [name, values] of Object.entries(definition.grid)) {
    rows = rows.flatMap((row) => values.map((value) => ({ ...row, [name]: value })));
  }
  return rows.map((row, index) => ({
    id: `delta_neutral_funding_carry_${String(index + 1).padStart(2, "0")}`,
    family: definition.family,
    ...definition.fixed,
    ...row,
  }));
}

function validateBoundary(boundary) {
  if (boundary?.kind !== "SPOT_PERPETUAL_DELTA_NEUTRAL_POSITIVE_FUNDING_CARRY" ||
      boundary.priorFundingCrowdingOutcomesMayRetuneCandidates !== false ||
      boundary.availabilityProbeReadBeforeDeclaration !== true ||
      boundary.availabilityProbePurpose !== "ENDPOINT_AND_SCHEMA_AVAILABILITY_ONLY" ||
      boundary.bulkDevelopmentPayloadsReadBeforeDeclaration !== false ||
      boundary.developmentUses2023Only !== true ||
      boundary.internalValidation2024RemainsUnread !== true ||
      boundary.external2025RemainsLocked !== true || boundary.sealed2026RemainsLocked !== true ||
      boundary.freshForwardSealRequiredBeforeLive !== true) {
    throw new Error("Delta-neutral evidence boundary changed.");
  }
}

function validateTrials(trials) {
  if (trials?.priorObservedCandidates !== 287 || trials.newCandidateBudget !== 24 ||
      trials.cumulativeCandidateCountAfterReplay !== 311 ||
      trials.candidateBudgetMayIncreaseAfterOutcomes !== false) {
    throw new Error("Delta-neutral trial budget must remain fixed at 24.");
  }
}

function validateSource(source) {
  if (source?.spotSymbol !== "BTCUSDT" || source.perpetualSymbol !== "BTCUSDT" ||
      source.provider !== "BYBIT_V5_PUBLIC_REST" || source.baseUrl !== "https://api.bybit.com" ||
      source.spotKlineEndpoint !== "/v5/market/kline?category=spot" ||
      source.perpetualKlineEndpoint !== "/v5/market/kline?category=linear" ||
      source.markKlineEndpoint !== "/v5/market/mark-price-kline?category=linear" ||
      source.indexKlineEndpoint !== "/v5/market/index-price-kline?category=linear" ||
      source.fundingEndpoint !== "/v5/market/funding/history?category=linear" ||
      source.klineInterval !== "5" || source.requestLimit?.kline !== 1000 ||
      source.requestLimit?.funding !== 200 || source.developmentStart !== "2023-01-01T00:00:00Z" ||
      source.developmentEndExclusive !== "2024-01-01T00:00:00Z" ||
      source.requiredSeries?.join("|") !== "SPOT_LAST|PERPETUAL_LAST|PERPETUAL_MARK|PERPETUAL_INDEX|FUNDING") {
    throw new Error("Delta-neutral source contract changed.");
  }
}

function validateInstrumentRules(rules) {
  if (rules?.spot?.minimumOrderAmountUsdt !== 5 || rules.spot.minimumOrderQuantityBtc !== 0.000001 ||
      rules.spot.basePrecisionBtc !== 0.000001 || rules.perpetual?.minimumOrderQuantityBtc !== 0.001 ||
      rules.perpetual.quantityStepBtc !== 0.001 || rules.perpetual.minimumNotionalUsdt !== 5 ||
      rules.perpetual.minimumLeverage !== 1 || rules.perpetual.fundingIntervalMinutes !== 480 ||
      rules.runtimePolicy !== "REFRESH_PRIVATE_FEES_AND_PUBLIC_INSTRUMENT_RULES_BEFORE_EVERY_LIVE_START") {
    throw new Error("Observed instrument rules changed.");
  }
}

function validateSchedule(schedule) {
  const development = schedule?.developmentBlocks ?? [];
  const internal = schedule?.internalValidationBlocks ?? [];
  if (development.length !== 4 || internal.length !== 4 ||
      development.some((block) => !block.era.startsWith("2023Q")) ||
      internal.some((block) => !block.era.startsWith("2024Q")) ||
      schedule.lockedExternal2025BlockIds?.length !== 4 ||
      schedule.lockedSealed2026BlockIds?.length !== 4 ||
      schedule.freshForwardSeal !== "REQUIRED_AFTER_ALL_HISTORICAL_APPROVALS") {
    throw new Error("Delta-neutral chronological schedule changed.");
  }
  for (const blocks of [development, internal]) {
    for (let index = 0; index < blocks.length; index += 1) {
      const block = blocks[index];
      if (!Number.isFinite(Date.parse(block.startAt)) || !Number.isFinite(Date.parse(block.endAt)) ||
          Date.parse(block.startAt) >= Date.parse(block.endAt) ||
          (index > 0 && blocks[index - 1].endAt !== block.startAt)) {
        throw new Error(`Delta-neutral block ${block.id} is invalid or discontinuous.`);
      }
    }
  }
}

function validateFeatures(features) {
  if (features?.decision !== "AFTER_SETTLED_FUNDING_AND_ALL_MATCHING_M5_BARS_ARE_CLOSED" ||
      features.fundingSignal?.direction !== "POSITIVE_ONLY" ||
      features.fundingSignal.history !== "CONSECUTIVE_SETTLED_RATES_ENDING_AT_CURRENT_SETTLEMENT" ||
      features.fundingSignal.summary !== "MEDIAN_OF_REQUIRED_POSITIVE_STREAK" ||
      features.fundingSignal.currentRateAvailableOnlyAtOrAfterSettlement !== true ||
      features.basisSignal?.formula !== "PERPETUAL_LAST_CLOSE_DIVIDED_BY_SPOT_LAST_CLOSE_MINUS_ONE" ||
      features.basisSignal.source !== "LATEST_MATCHING_CLOSED_M5_BAR_AT_OR_BEFORE_SETTLEMENT" ||
      features.position !== "LONG_SPOT_AND_SHORT_EQUAL_NET_BTC_PERPETUAL") {
    throw new Error("Delta-neutral feature contract changed.");
  }
}

function validateCandidates(protocol) {
  const definition = protocol.candidateDefinition;
  if (protocol.candidateDefinitionSha256 !== EXPECTED_CANDIDATE_DEFINITION_SHA256 ||
      hashObject(definition) !== EXPECTED_CANDIDATE_DEFINITION_SHA256 ||
      definition?.family !== "DELTA_NEUTRAL_POSITIVE_FUNDING_CARRY" || definition.candidateCount !== 24 ||
      definition.fixed?.entryDelayMinutes !== 5 || definition.fixed.maximumHoldingDays !== 30 ||
      definition.fixed.maximumEntryBasisPct !== 0.03 ||
      definition.fixed.maximumAbsoluteMarkIndexPremiumPct !== 0.01 ||
      definition.fixed.basisDivergenceStopPctFromEntry !== 0.03 ||
      definition.fixed.reentryCooldownHours !== 8 ||
      definition.fixed.minimumProjectedGrossCarryToBaseCostRatio !== 1) {
    throw new Error("Delta-neutral candidate definition changed after declaration.");
  }
  const candidates = expandDeltaNeutralFundingCarryCandidates(protocol);
  if (candidates.length !== 24 || new Set(candidates.map((candidate) => candidate.id)).size !== 24) {
    throw new Error("Delta-neutral grid must expand to 24 unique candidates.");
  }
}

function validateExecution(execution) {
  if (execution?.entryDecision !== "CLOSED_SETTLEMENT_SNAPSHOT_ONLY" ||
      execution.baseEntry !== "BOTH_LEGS_AT_NEXT_CONTIGUOUS_M5_OPEN_AT_LEAST_FIVE_MINUTES_AFTER_SETTLEMENT" ||
      execution.legOrder !== "PERPETUAL_SHORT_FIRST_THEN_SPOT_LONG" ||
      execution.secondLegFailure !== "IMMEDIATE_REDUCE_ONLY_FLATTEN_AND_DISABLE_NEW_ENTRIES" ||
      execution.spotBuyFeeAsset !== "BASE_ASSET_WITH_GROSS_BUY_QUANTITY_ADJUSTED_TO_TARGET_NET_BTC" ||
      execution.spotTakerFeeRate !== 0.001 || execution.perpetualTakerFeeRate !== 0.00055 ||
      execution.spotSlippageRatePerLeg !== 0.0003 || execution.perpetualSlippageRatePerLeg !== 0.0002 ||
      execution.baseRoundTripCostRateOnMatchedNotional !== 0.0041 || execution.costStressMultiplier !== 1.5 ||
      execution.secondLegDelayStressBars !== 1 || execution.startingEquityUsdt !== 660 ||
      execution.maximumMatchedNotionalFractionOfEquity !== 0.4 || execution.perpetualLeverage !== 1 ||
      execution.minimumUncommittedEquityFraction !== 0.2 ||
      execution.conservativeLiquidationPriceMultiple !== 1.98 ||
      execution.perpetualMinimumQuantityBtc !== 0.001 || execution.perpetualQuantityStepBtc !== 0.001 ||
      execution.spotBasePrecisionBtc !== 0.000001 || execution.maximumNetHedgeMismatchBtc !== 0.000001 ||
      execution.maximumConcurrentPositions !== 1 ||
      execution.oneHundredUsdtPolicy !== "NO_TRADE_WHEN_EQUAL_MINIMUM_PERPETUAL_QUANTITY_CANNOT_BE_FUNDED_WITH_RESERVE") {
    throw new Error("Delta-neutral execution contract changed.");
  }
}

function validateGates(development, internal) {
  for (const [name, gate] of [["development", development], ["internal", internal]]) {
    if (gate?.minimumClosedPositions !== 8 || gate.minimumActiveCalendarDays !== 90 ||
        gate.minimumCapturedFundingSettlements !== 24 || gate.minimumPositiveBlockCount !== 3 ||
        gate.totalBlockCount !== 4 || gate.minimumNetReturnPct !== 0 || gate.minimumProfitFactor !== 1.1 ||
        gate.minimumMeanDailyReturnPct !== 0 || gate.minimumBootstrapLowerMeanDailyReturnPct !== 0 ||
        gate.maximumDrawdownPct !== 15 || gate.maximumLiquidationCount !== 0 ||
        gate.maximumPositivePositionProfitConcentration !== 0.35 ||
        gate.maximumNetHedgeMismatchBtc !== 0.000001 || gate.costStressMinimumNetReturnPct !== 0 ||
        gate.secondLegDelayStressMinimumNetReturnPct !== 0) {
      throw new Error(`Delta-neutral ${name} gate changed.`);
    }
  }
  if (internal.costStressMultiplier !== 1.5) {
    throw new Error("Delta-neutral internal cost stress changed.");
  }
}

function validateStatistics(statistics) {
  if (statistics?.bootstrapKind !== "MOVING_BLOCK_BOOTSTRAP_DAILY_MARK_TO_MARKET_RETURN" ||
      statistics.bootstrapSamples !== 10000 || statistics.bootstrapConfidence !== 0.95 ||
      statistics.bootstrapBlockDays !== 7 ||
      statistics.randomSeed !== "bybit-delta-neutral-funding-carry-development-v1|20260806" ||
      statistics.compoundDailyReturnIsSelectionObjective !== false) {
    throw new Error("Delta-neutral statistics contract changed.");
  }
}

function validateOutcome(outcome) {
  if (outcome?.oneCandidateMaximum !== true || outcome.retuneFromDevelopment !== false ||
      outcome.internalValidationMayBeAcquiredOnlyAfterDevelopmentPass !== true ||
      outcome.retuneFromInternalValidation !== false ||
      outcome.external2025MayBeAcquiredOnlyAfterInternalPass !== true ||
      outcome.sealed2026MayBeAcquiredOnlyAfterExternal2025PassAndRefreeze !== true ||
      outcome.freshForwardShadowAndPaperRequiredBeforeLive !== true ||
      outcome.automaticExecutionAllowed !== false || outcome.liveExecutionAllowed !== false) {
    throw new Error("Delta-neutral outcome policy changed.");
  }
}

function hashObject(value) {
  return sha256(JSON.stringify(value));
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}
