import { createHash } from "node:crypto";
import fs from "node:fs/promises";
import { dirname, resolve } from "node:path";

const EXPECTED_PARENT_RESULT_SHA256 = "6e3163198e0e2873c7cfa400a5c87f99f97f49071c2a385f93b77812a69f1ee4";
const EXPECTED_CANDIDATE_DEFINITION_SHA256 = "b49417ec8fd931d58305cfd5225d03e233e23edc09198621e6ed0f070ea6f778";
const EXPECTED_SYMBOLS = ["BTCUSDT", "ETHUSDT", "SOLUSDT"];
const EXPECTED_INSTRUMENT_RULES = {
  BTCUSDT: {
    spot: { minimumOrderAmountUsdt: 5, minimumOrderQuantityBase: 0.000001, basePrecision: 0.000001 },
    perpetual: { minimumOrderQuantityBase: 0.001, quantityStepBase: 0.001, minimumNotionalUsdt: 5 },
    maximumNetHedgeMismatchBase: 0.000001,
  },
  ETHUSDT: {
    spot: { minimumOrderAmountUsdt: 5, minimumOrderQuantityBase: 0.00001, basePrecision: 0.00001 },
    perpetual: { minimumOrderQuantityBase: 0.01, quantityStepBase: 0.01, minimumNotionalUsdt: 5 },
    maximumNetHedgeMismatchBase: 0.00001,
  },
  SOLUSDT: {
    spot: { minimumOrderAmountUsdt: 5, minimumOrderQuantityBase: 0.0001, basePrecision: 0.0001 },
    perpetual: { minimumOrderQuantityBase: 0.1, quantityStepBase: 0.1, minimumNotionalUsdt: 5 },
    maximumNetHedgeMismatchBase: 0.0001,
  },
};

export async function loadMultiAssetDeltaNeutralFundingCarryProtocol(path) {
  const bytes = await fs.readFile(path);
  const protocol = JSON.parse(bytes);
  const repositoryRoot = resolve(dirname(path), "..");
  const parentBytes = await fs.readFile(resolve(repositoryRoot, protocol.parentResult.path));
  validateMultiAssetDeltaNeutralFundingCarryProtocol(
    protocol,
    JSON.parse(parentBytes),
    sha256(parentBytes),
  );
  return { protocol, sha256: sha256(bytes), parentResultSha256: sha256(parentBytes) };
}

export function validateMultiAssetDeltaNeutralFundingCarryProtocol(
  protocol,
  parentResult,
  parentResultSha256,
) {
  if (protocol?.protocolId !== "bybit-multi-asset-delta-neutral-funding-carry-development-v1" ||
      protocol.status !== "PREDECLARED_BEFORE_BULK_MULTI_ASSET_DEVELOPMENT_ACQUISITION") {
    throw new Error("Multi-asset carry protocol must remain frozen before bulk acquisition.");
  }
  if (parentResultSha256 !== EXPECTED_PARENT_RESULT_SHA256 ||
      protocol.parentResult?.sha256 !== EXPECTED_PARENT_RESULT_SHA256 ||
      parentResult?.programStatus !== "CLOSED_NO_APPROVABLE_DELTA_NEUTRAL_FUNDING_CARRY_V1") {
    throw new Error("Multi-asset research must bind the rejected single-asset carry result.");
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

export function expandMultiAssetDeltaNeutralFundingCarryCandidates(protocol) {
  const definition = protocol.candidateDefinition;
  let rows = [{}];
  for (const [name, values] of Object.entries(definition.grid)) {
    rows = rows.flatMap((row) => values.map((value) => ({ ...row, [name]: value })));
  }
  return rows.map((row, index) => ({
    id: `multi_asset_delta_neutral_carry_${String(index + 1).padStart(2, "0")}`,
    family: definition.family,
    ...definition.fixed,
    ...row,
  }));
}

function validateBoundary(boundary) {
  if (boundary?.kind !== "MULTI_ASSET_SPOT_PERPETUAL_DELTA_NEUTRAL_POSITIVE_FUNDING_CARRY" ||
      boundary.singleAssetBtcDevelopmentAndInternalOutcomesPreviouslyObserved !== true ||
      boundary.singleAssetOutcomeMayRetuneRejectedCandidate !== false ||
      boundary.availabilityProbeReadBeforeDeclaration !== true ||
      boundary.availabilityProbeMaximumRowsPerSeries !== 3 ||
      boundary.availabilityProbePurpose !== "ENDPOINT_SCHEMA_AND_MINIMUM_ORDER_AVAILABILITY_ONLY" ||
      boundary.bulkMultiAssetDevelopmentPayloadsReadBeforeDeclaration !== false ||
      boundary.developmentUses2023Only !== true ||
      boundary.internalValidation2024RemainsUnreadForMultiAssetFamily !== true ||
      boundary.external2025RemainsLocked !== true || boundary.sealed2026RemainsLocked !== true ||
      boundary.freshForwardSealRequiredBeforeLive !== true) {
    throw new Error("Multi-asset evidence boundary changed.");
  }
}

function validateTrials(trials) {
  if (trials?.priorObservedCandidates !== 311 || trials.newCandidateBudget !== 24 ||
      trials.cumulativeCandidateCountAfterReplay !== 335 ||
      trials.candidateBudgetMayIncreaseAfterOutcomes !== false) {
    throw new Error("Multi-asset candidate budget must remain fixed at 24.");
  }
}

function validateSource(source) {
  if (source?.symbols?.join("|") !== EXPECTED_SYMBOLS.join("|") ||
      source.provider !== "BYBIT_V5_PUBLIC_REST" || source.baseUrl !== "https://api.bybit.com" ||
      source.spotKlineEndpoint !== "/v5/market/kline?category=spot" ||
      source.perpetualKlineEndpoint !== "/v5/market/kline?category=linear" ||
      source.markKlineEndpoint !== "/v5/market/mark-price-kline?category=linear" ||
      source.indexKlineEndpoint !== "/v5/market/index-price-kline?category=linear" ||
      source.fundingEndpoint !== "/v5/market/funding/history?category=linear" ||
      source.instrumentEndpoint !== "/v5/market/instruments-info" ||
      source.klineInterval !== "5" || source.requestLimit?.kline !== 1000 ||
      source.requestLimit?.funding !== 200 || source.developmentStart !== "2023-01-01T00:00:00Z" ||
      source.developmentEndExclusive !== "2024-01-01T00:00:00Z" ||
      source.requiredSeriesPerSymbol?.join("|") !==
        "SPOT_LAST|PERPETUAL_LAST|PERPETUAL_MARK|PERPETUAL_INDEX|FUNDING") {
    throw new Error("Multi-asset source contract changed.");
  }
}

function validateInstrumentRules(rules) {
  for (const symbol of EXPECTED_SYMBOLS) {
    const actual = rules?.[symbol];
    const expected = EXPECTED_INSTRUMENT_RULES[symbol];
    for (const [name, value] of Object.entries(expected.spot)) {
      if (actual?.spot?.[name] !== value) throw new Error(`${symbol} spot ${name} changed.`);
    }
    for (const [name, value] of Object.entries(expected.perpetual)) {
      if (actual?.perpetual?.[name] !== value) throw new Error(`${symbol} perpetual ${name} changed.`);
    }
    if (actual.perpetual.minimumLeverage !== 1 || actual.perpetual.fundingIntervalMinutes !== 480 ||
        actual.maximumNetHedgeMismatchBase !== expected.maximumNetHedgeMismatchBase) {
      throw new Error(`${symbol} leverage, funding, or hedge rule changed.`);
    }
  }
  if (rules?.runtimePolicy !==
      "REFRESH_PRIVATE_FEES_AND_PUBLIC_INSTRUMENT_RULES_BEFORE_EVERY_LIVE_START") {
    throw new Error("Runtime instrument refresh policy changed.");
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
    throw new Error("Multi-asset chronological schedule changed.");
  }
  for (const blocks of [development, internal]) {
    for (let index = 0; index < blocks.length; index += 1) {
      const block = blocks[index];
      if (!Number.isFinite(Date.parse(block.startAt)) || !Number.isFinite(Date.parse(block.endAt)) ||
          Date.parse(block.startAt) >= Date.parse(block.endAt) ||
          (index > 0 && blocks[index - 1].endAt !== block.startAt)) {
        throw new Error(`Multi-asset block ${block.id} is invalid or discontinuous.`);
      }
    }
  }
}

function validateFeatures(features) {
  if (features?.decision !== "AFTER_EACH_SYMBOL_SETTLED_FUNDING_AND_ALL_MATCHING_M5_BARS_ARE_CLOSED" ||
      features.fundingSignal?.direction !== "POSITIVE_ONLY" ||
      features.fundingSignal.history !==
        "CONSECUTIVE_SETTLED_RATES_ENDING_AT_CURRENT_SETTLEMENT_PER_SYMBOL" ||
      features.fundingSignal.summary !== "MEDIAN_OF_REQUIRED_POSITIVE_STREAK" ||
      features.fundingSignal.currentRateAvailableOnlyAtOrAfterSettlement !== true ||
      features.basisSignal?.formula !== "PERPETUAL_LAST_CLOSE_DIVIDED_BY_SPOT_LAST_CLOSE_MINUS_ONE" ||
      features.basisSignal.minimumEntryBasisPct !== 0 || features.basisSignal.maximumEntryBasisPct !== 0.03 ||
      features.ranking?.formula !==
        "MEDIAN_FUNDING_RATE_TIMES_90_PLUS_MAX_ENTRY_BASIS_ZERO_MINUS_BASE_ROUND_TRIP_COST_RATE" ||
      features.ranking.order !== "DESCENDING_SCORE_THEN_ASCENDING_SYMBOL" ||
      features.ranking.nonPositiveScoreIsIneligible !== true ||
      features.position !== "LONG_SPOT_AND_SHORT_EQUAL_NET_BASE_QUANTITY_PER_SYMBOL") {
    throw new Error("Multi-asset feature or ranking contract changed.");
  }
}

function validateCandidates(protocol) {
  const definition = protocol.candidateDefinition;
  if (protocol.candidateDefinitionSha256 !== EXPECTED_CANDIDATE_DEFINITION_SHA256 ||
      hashObject(definition) !== EXPECTED_CANDIDATE_DEFINITION_SHA256 ||
      definition?.family !== "MULTI_ASSET_DELTA_NEUTRAL_POSITIVE_FUNDING_CARRY" ||
      definition.candidateCount !== 24 || definition.fixed?.symbols?.join("|") !== EXPECTED_SYMBOLS.join("|") ||
      definition.fixed.entryDelayMinutes !== 5 || definition.fixed.maximumHoldingDays !== 30 ||
      definition.fixed.minimumEntryBasisPct !== 0 || definition.fixed.maximumEntryBasisPct !== 0.03 ||
      definition.fixed.maximumAbsoluteMarkIndexPremiumPct !== 0.01 ||
      definition.fixed.basisDivergenceStopPctFromEntry !== 0.03 ||
      definition.fixed.reentryCooldownHours !== 8 ||
      definition.fixed.projectedCarryHorizonSettlements !== 90 ||
      definition.fixed.minimumProjectedNetCarryScore !== 0) {
    throw new Error("Multi-asset candidate definition changed after declaration.");
  }
  const candidates = expandMultiAssetDeltaNeutralFundingCarryCandidates(protocol);
  if (candidates.length !== 24 || new Set(candidates.map((candidate) => candidate.id)).size !== 24 ||
      candidates.some((candidate) => ![1, 2].includes(candidate.maximumConcurrentPairs))) {
    throw new Error("Multi-asset grid must expand to 24 unique candidates.");
  }
}

function validateExecution(execution) {
  if (execution?.entryDecision !== "CLOSED_SETTLEMENT_SNAPSHOT_ONLY" ||
      execution.baseEntry !==
        "BOTH_LEGS_AT_NEXT_CONTIGUOUS_M5_OPEN_AT_LEAST_FIVE_MINUTES_AFTER_SETTLEMENT" ||
      execution.baseExit !== "BOTH_LEGS_AT_NEXT_CONTIGUOUS_M5_OPEN_AFTER_EXIT_DECISION" ||
      execution.legOrder !== "PERPETUAL_SHORT_FIRST_THEN_SPOT_LONG" ||
      execution.secondLegFailure !== "IMMEDIATE_REDUCE_ONLY_FLATTEN_AND_DISABLE_NEW_ENTRIES" ||
      execution.spotBuyFeeAsset !==
        "BASE_ASSET_WITH_GROSS_BUY_QUANTITY_ADJUSTED_TO_TARGET_NET_BASE_QUANTITY" ||
      execution.spotTakerFeeRate !== 0.001 || execution.perpetualTakerFeeRate !== 0.00055 ||
      execution.spotSlippageRatePerLeg !== 0.0003 || execution.perpetualSlippageRatePerLeg !== 0.0002 ||
      execution.baseRoundTripCostRateOnMatchedNotional !== 0.0041 || execution.costStressMultiplier !== 1.5 ||
      execution.secondLegDelayStressBars !== 1 || execution.startingEquityUsdt !== 660 ||
      execution.maximumTotalMatchedNotionalFractionOfEquity !== 0.4 ||
      execution.absoluteMaximumConcurrentPairs !== 2 || execution.perpetualLeverage !== 1 ||
      execution.minimumUncommittedEquityFraction !== 0.2 ||
      execution.conservativeLiquidationPriceMultiple !== 1.98 ||
      execution.minimumQuantityPolicy !==
        "FLOOR_TO_PERPETUAL_STEP_AND_NO_TRADE_WHEN_MINIMUM_QUANTITY_OR_RESERVE_FAILS") {
    throw new Error("Multi-asset execution contract changed.");
  }
}

function validateGates(development, internal) {
  const expected = {
    minimumClosedPositions: 20,
    minimumActiveCalendarDays: 180,
    minimumCapturedFundingSettlements: 60,
    minimumTradedAssetCount: 3,
    minimumPositiveAssetCount: 2,
    minimumPositiveBlockCount: 3,
    totalBlockCount: 4,
    minimumNetReturnPct: 0,
    minimumProfitFactor: 1.1,
    minimumMeanDailyReturnPct: 0,
    minimumBootstrapLowerMeanDailyReturnPct: 0,
    maximumDrawdownPct: 15,
    maximumLiquidationCount: 0,
    maximumPositivePositionProfitConcentration: 0.25,
    maximumPositiveAssetProfitConcentration: 0.6,
    maximumNetHedgeMismatchBySymbol: {
      BTCUSDT: 0.000001,
      ETHUSDT: 0.00001,
      SOLUSDT: 0.0001,
    },
    costStressMinimumNetReturnPct: 0,
    secondLegDelayStressMinimumNetReturnPct: 0,
  };
  for (const [name, gate] of [["development", development], ["internal", internal]]) {
    for (const [key, value] of Object.entries(expected)) {
      if (JSON.stringify(gate?.[key]) !== JSON.stringify(value)) {
        throw new Error(`${name} gate ${key} changed.`);
      }
    }
  }
  if (internal?.costStressMultiplier !== 1.5) {
    throw new Error("Internal cost stress multiplier changed.");
  }
}

function validateStatistics(statistics) {
  if (statistics?.bootstrapKind !==
      "MOVING_BLOCK_BOOTSTRAP_DAILY_PORTFOLIO_MARK_TO_MARKET_RETURN" ||
      statistics.bootstrapSamples !== 10000 || statistics.bootstrapConfidence !== 0.95 ||
      statistics.bootstrapBlockDays !== 7 ||
      statistics.randomSeed !==
        "bybit-multi-asset-delta-neutral-funding-carry-development-v1|20260806" ||
      statistics.compoundDailyReturnIsSelectionObjective !== false) {
    throw new Error("Multi-asset statistics contract changed.");
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
    throw new Error("Multi-asset outcome policy changed.");
  }
}

function hashObject(value) {
  return sha256(JSON.stringify(value));
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}
