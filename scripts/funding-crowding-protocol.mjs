import { createHash } from "node:crypto";
import fs from "node:fs/promises";
import { dirname, resolve } from "node:path";

const EXPECTED_PARENT_RESULT_SHA256 = "4d3bd81884f5110bd4cb0804d44d5643fff629d931815f913fc8ad51f93b10f3";
const EXPECTED_CANDLE_DATABASE_SHA256 = "b8b07efa6c2215afe371d8c52c710aa86a630a4f1cd68c9d2b57c41a94fe8798";
const EXPECTED_CANDIDATE_DEFINITION_SHA256 = "acb619a51706222df7fef6965844ab45e1fc67b323620cc62ed63928748bab80";

export async function loadFundingCrowdingProtocol(path) {
  const bytes = await fs.readFile(path);
  const protocol = JSON.parse(bytes);
  const repositoryRoot = resolve(dirname(path), "..");
  const parentBytes = await fs.readFile(resolve(repositoryRoot, protocol.parentResult.path));
  validateFundingCrowdingProtocol(protocol, JSON.parse(parentBytes), sha256(parentBytes));
  return { protocol, sha256: sha256(bytes), parentResultSha256: sha256(parentBytes) };
}

export function validateFundingCrowdingProtocol(protocol, parentResult, parentResultSha256) {
  if (protocol?.protocolId !== "bybit-funding-crowding-development-v1" ||
      protocol.status !== "PREDECLARED_BEFORE_FUNDING_PREMIUM_DEVELOPMENT_ACQUISITION") {
    throw new Error("Funding crowding protocol must remain frozen before development acquisition.");
  }
  if (parentResultSha256 !== EXPECTED_PARENT_RESULT_SHA256 ||
      protocol.parentResult?.sha256 !== EXPECTED_PARENT_RESULT_SHA256 ||
      parentResult?.programStatus !== "CLOSED_NO_APPROVABLE_SUBMINUTE_SEQUENCE_STRATEGY_V2") {
    throw new Error("Funding crowding research must bind the closed subminute v2 result.");
  }
  validateBoundary(protocol.researchBoundary);
  validateTrials(protocol.trialAccounting);
  validateSource(protocol.sourceData);
  validateSchedule(protocol.evidenceSchedule);
  validateFeatures(protocol.featureContract);
  validateCandidates(protocol);
  validateExecution(protocol.executionContract);
  validateGates(protocol.developmentGate, protocol.internalValidationGate);
  validateStatistics(protocol.statistics);
  validateOutcome(protocol.outcomePolicy);
  return protocol;
}

export function expandFundingCandidates(protocol) {
  const definition = protocol.candidateDefinition;
  let rows = [{}];
  for (const [name, values] of Object.entries(definition.grid)) {
    rows = rows.flatMap((row) => values.map((value) => ({ ...row, [name]: value })));
  }
  return rows.map((row, index) => ({
    id: `funding_crowding_carry_${String(index + 1).padStart(2, "0")}`,
    family: definition.family,
    ...definition.fixed,
    ...row,
  }));
}

function validateBoundary(boundary) {
  if (boundary?.kind !== "ECONOMICALLY_DISTINCT_LOW_TURNOVER_FUNDING_CARRY" ||
      boundary.priorEventFlowOutcomesMayRetuneFundingCandidates !== false ||
      boundary.fundingPremiumDevelopmentPayloadsReadBeforeDeclaration !== false ||
      boundary.developmentUses2020Through2022Only !== true ||
      boundary.internalValidation2023Through2024RemainsUnread !== true ||
      boundary.external2025And2026RemainLocked !== true ||
      boundary.freshForwardSealRequiredBeforeLive !== true) {
    throw new Error("Funding crowding evidence boundary changed.");
  }
}

function validateTrials(trials) {
  if (trials?.priorObservedCandidates !== 255 || trials.newCandidateBudget !== 32 ||
      trials.cumulativeCandidateCountAfterReplay !== 287 ||
      trials.candidateBudgetMayIncreaseAfterOutcomes !== false) {
    throw new Error("Funding crowding trial budget must remain fixed at 32.");
  }
}

function validateSource(source) {
  if (source?.symbol !== "BTCUSDT" ||
      source.canonicalCandleDatabaseSha256 !== EXPECTED_CANDLE_DATABASE_SHA256 ||
      source.provider !== "BYBIT_V5_PUBLIC_REST" || source.baseUrl !== "https://api.bybit.com" ||
      source.fundingEndpoint !== "/v5/market/funding/history" ||
      source.premiumEndpoint !== "/v5/market/premium-index-price-kline" ||
      source.premiumInterval !== "15" || source.requestLimit?.funding !== 200 ||
      source.requestLimit?.premium !== 1000 ||
      source.developmentStart !== "2020-03-25T00:00:00Z" ||
      source.developmentEndExclusive !== "2023-01-01T00:00:00Z") {
    throw new Error("Funding crowding source contract changed.");
  }
}

function validateSchedule(schedule) {
  const development = schedule?.developmentBlocks ?? [];
  const internal = schedule?.internalValidationBlocks ?? [];
  if (development.length !== 5 || internal.length !== 8 ||
      new Set([...development, ...internal].map((block) => block.id)).size !== 13 ||
      development.some((block) => !/^202[0-2]/.test(block.era)) ||
      internal.some((block) => !/^202[34]/.test(block.era)) ||
      schedule.lockedExternal2025BlockIds?.length !== 4 ||
      schedule.lockedExternal2026BlockIds?.length !== 4 ||
      schedule.freshForwardSeal !== "REQUIRED_AFTER_ALL_HISTORICAL_APPROVALS") {
    throw new Error("Funding crowding chronological schedule changed.");
  }
  for (const blocks of [development, internal]) {
    for (let index = 0; index < blocks.length; index += 1) {
      const block = blocks[index];
      if (!Number.isFinite(Date.parse(block.startAt)) || !Number.isFinite(Date.parse(block.endAt)) ||
          Date.parse(block.startAt) >= Date.parse(block.endAt) ||
          (index > 0 && blocks[index - 1].endAt !== block.startAt)) {
        throw new Error(`Funding crowding block ${block.id} is invalid or discontinuous.`);
      }
    }
  }
}

function validateFeatures(features) {
  if (features?.fundingDecision?.currentSettledRateAvailableOnlyAtOrAfterTimestamp !== true ||
      features.fundingDecision.baseline !== "PRIOR_SETTLED_RATES_EXCLUDING_CURRENT" ||
      features.fundingDecision.robustCenter !== "MEDIAN" ||
      features.fundingDecision.robustScale !== "MAD_TIMES_1_4826" ||
      features.fundingDecision.zeroMadPolicy !== "NO_SIGNAL" ||
      features.premiumConfirmation?.source !== "LATEST_CLOSED_M15_PREMIUM_BAR_AT_OR_BEFORE_SETTLEMENT" ||
      features.premiumConfirmation.mustShareFundingSign !== true ||
      features.premiumConfirmation.maximumStalenessMinutes !== 30 ||
      features.direction !== "OPPOSITE_SETTLED_FUNDING_SIGN") {
    throw new Error("Funding crowding causal feature contract changed.");
  }
}

function validateCandidates(protocol) {
  const definition = protocol.candidateDefinition;
  if (protocol.candidateDefinitionSha256 !== EXPECTED_CANDIDATE_DEFINITION_SHA256 ||
      hashObject(definition) !== EXPECTED_CANDIDATE_DEFINITION_SHA256 ||
      definition?.family !== "FUNDING_CROWDING_CONTRARIAN_CARRY" || definition.candidateCount !== 32 ||
      definition.fixed?.entryDelayMinutes !== 5 || definition.fixed.initialStopAtrPeriod !== 14 ||
      definition.fixed.initialStopAtrMultiple !== 4 || definition.fixed.minimumStopPct !== 0.02 ||
      definition.fixed.targetR !== 2 || definition.fixed.exitWhenFundingNoLongerFavorable !== true) {
    throw new Error("Funding crowding candidate definition changed after declaration.");
  }
  const candidates = expandFundingCandidates(protocol);
  if (candidates.length !== 32 || new Set(candidates.map((candidate) => candidate.id)).size !== 32) {
    throw new Error("Funding crowding grid must expand to 32 unique candidates.");
  }
}

function validateExecution(execution) {
  if (execution?.decision !== "AFTER_FUNDING_SETTLEMENT_AND_CLOSED_PREMIUM_BAR" ||
      execution.entry !== "NEXT_CONTIGUOUS_M5_OPEN_AT_LEAST_FIVE_MINUTES_AFTER_SETTLEMENT" ||
      execution.sameM1Conflict !== "STOP_FIRST" || execution.entryFeeRate !== 0.0006 ||
      execution.exitFeeRate !== 0.0006 || execution.entrySlippageRate !== 0.0002 ||
      execution.exitSlippageRate !== 0.0002 || execution.maximumTradesPerUtcDay !== 1 ||
      execution.overlappingPositionsAllowed !== false || execution.startingEquityUsdt !== 660 ||
      execution.riskFractionPerTrade !== 0.01 || execution.minimumQuantityBtc !== 0.001 ||
      execution.quantityStepBtc !== 0.001 || execution.maximumNotionalUsdt !== 660 ||
      execution.maximumLeverage !== 3 || execution.funding !== "ACTUAL_SUBSEQUENT_SETTLED_RATES_ONLY" ||
      execution.oneHundredUsdtPolicy !== "NO_TRADE_WHEN_MINIMUM_QUANTITY_EXCEEDS_RISK_OR_NOTIONAL_LIMIT") {
    throw new Error("Funding crowding execution contract changed.");
  }
}

function validateGates(development, internal) {
  if (development?.minimumTrades !== 30 || development.minimumLongTrades !== 5 ||
      development.minimumShortTrades !== 5 || development.minimumPositiveBlockCount !== 4 ||
      development.totalBlockCount !== 5 || development.minimumProfitFactor !== 1.15 ||
      development.minimumMeanNetR !== 0 || development.minimumBootstrapLowerMeanNetR !== 0 ||
      development.maximumDrawdownPct !== 20 || development.maximumLiquidationCount !== 0 ||
      development.maximumWinnerProfitConcentration !== 0.25) {
    throw new Error("Funding crowding development gate changed.");
  }
  if (internal?.minimumTrades !== 40 || internal.minimumLongTrades !== 8 ||
      internal.minimumShortTrades !== 8 || internal.minimumPositiveBlockCount !== 6 ||
      internal.totalBlockCount !== 8 || internal.minimumProfitFactor !== 1.15 ||
      internal.minimumMeanNetR !== 0 || internal.minimumBootstrapLowerMeanNetR !== 0 ||
      internal.maximumDrawdownPct !== 20 || internal.maximumLiquidationCount !== 0 ||
      internal.maximumWinnerProfitConcentration !== 0.25 || internal.costStressMultiplier !== 1.5 ||
      internal.costStressMinimumMeanNetR !== 0) {
    throw new Error("Funding crowding internal gate changed.");
  }
}

function validateStatistics(statistics) {
  if (statistics?.bootstrapKind !== "MOVING_BLOCK_BOOTSTRAP_DAILY_NET_R" ||
      statistics.bootstrapSamples !== 10000 || statistics.bootstrapConfidence !== 0.95 ||
      statistics.bootstrapBlockDays !== 7 ||
      statistics.randomSeed !== "bybit-funding-crowding-development-v1|20260806" ||
      statistics.compoundDailyReturnIsSelectionObjective !== false) {
    throw new Error("Funding crowding statistics contract changed.");
  }
}

function validateOutcome(outcome) {
  if (outcome?.oneCandidateMaximum !== true || outcome.retuneFromDevelopment !== false ||
      outcome.internalValidationMayBeAcquiredOnlyAfterDevelopmentPass !== true ||
      outcome.retuneFromInternalValidation !== false ||
      outcome.external2025MayBeAcquiredOnlyAfterInternalPass !== true ||
      outcome.external2026MayBeAcquiredOnlyAfter2025PassAndRefreeze !== true ||
      outcome.freshForwardSealRequiredBeforeLive !== true ||
      outcome.automaticExecutionAllowed !== false || outcome.liveExecutionAllowed !== false) {
    throw new Error("Funding crowding outcome policy changed.");
  }
}

function hashObject(value) {
  return sha256(JSON.stringify(value));
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}
