import { createHash } from "node:crypto";
import fs from "node:fs/promises";

const EXPECTED_PARENT_RESULT_SHA256 = "454d747ac8092ff8d3145805b7c21c4a18bdc834439e83ec16bd83ebf4365cfe";
const EXPECTED_CANDLE_DATABASE_SHA256 = "b8b07efa6c2215afe371d8c52c710aa86a630a4f1cd68c9d2b57c41a94fe8798";
const EXPECTED_AGGREGATE_DATABASE_SHA256 = "44724180f0150ac935d90514e98f09562632b5653e9ad76480519e71e37fe923";
const EXPECTED_ACQUISITION_RECEIPT_SHA256 = "61ec8dfb8192ad27e03b0359a728f6ece78001639ad3ce69531b981169463b0a";
const EXPECTED_HYPOTHESIS_SHA256 = "5459ad198ea5432e4abe76c7dedfee45069a207dc24bb5905a2883aba0978547";

export async function loadSubminuteSequenceProtocol(path) {
  const bytes = await fs.readFile(path);
  return {
    protocol: validateSubminuteSequenceProtocol(JSON.parse(bytes)),
    sha256: sha256(bytes),
  };
}

export function validateSubminuteSequenceProtocol(protocol) {
  if (protocol?.protocolId !== "bybit-subminute-sequence-development-v1" ||
      protocol.status !== "PREDECLARED_BEFORE_SUBMINUTE_SOURCE_REPLAY") {
    throw new Error("Subminute sequence protocol must be frozen before source replay.");
  }
  if (protocol.parentResult?.resultReceiptSha256 !== EXPECTED_PARENT_RESULT_SHA256 ||
      protocol.parentResult?.requiredProgramStatus !== "CLOSED_NO_APPROVABLE_EVENT_FLOW_STRATEGY") {
    throw new Error("Subminute sequence research must bind the closed event-flow program.");
  }
  validateBoundary(protocol.researchBoundary);
  validateTrials(protocol.trialAccounting, protocol.hypotheses);
  validateSources(protocol.sourceData);
  validateAcquisition(protocol.acquisition);
  validateFeatures(protocol.featureContract);
  validateHypotheses(protocol);
  validateExecution(protocol.executionContract);
  validateGates(protocol.selection2023, protocol.internalValidation2024);
  validateOutcome(protocol.outcomePolicy);
  return protocol;
}

export function expandSubminuteCandidates(protocol) {
  return protocol.hypotheses.flatMap((hypothesis) => {
    const entries = Object.entries(hypothesis.grid);
    let rows = [{}];
    for (const [name, values] of entries) {
      rows = rows.flatMap((row) => values.map((value) => ({ ...row, [name]: value })));
    }
    return rows.map((row, index) => ({
      id: `${hypothesis.family.toLowerCase()}_${String(index + 1).padStart(2, "0")}`,
      family: hypothesis.family,
      ...hypothesis.fixed,
      ...row,
    }));
  });
}

function validateBoundary(boundary) {
  if (boundary?.kind !== "NEW_SUBMINUTE_FEATURE_CONTRACT" ||
      boundary.priorMinuteAggregateOutcomesMaySelectThresholds !== false ||
      boundary.subminuteSourcePayloadsReadBeforeDeclaration !== false ||
      boundary.selectionUses2023Only !== true || boundary.internalValidationUses2024Only !== true ||
      boundary.locked2025And2026DataMayBeAcquiredBeforeInternalPass !== false) {
    throw new Error("Subminute evidence boundary changed.");
  }
}

function validateTrials(trials, hypotheses) {
  if (trials?.priorClosedProgramCandidates !== 191 || trials.newProgramCandidateBudget !== 32 ||
      trials.absorptionReversalCandidates !== 16 || trials.depletionContinuationCandidates !== 16 ||
      trials.candidateBudgetMayIncreaseAfterOutcomes !== false || hypotheses?.length !== 2 ||
      hypotheses.reduce((sum, hypothesis) => sum + hypothesis.candidateCount, 0) !== 32) {
    throw new Error("Subminute candidate budget must remain fixed at 32.");
  }
}

function validateSources(source) {
  if (source?.canonicalCandleDatabaseSha256 !== EXPECTED_CANDLE_DATABASE_SHA256 ||
      source.aggregateEvidenceDatabaseSha256 !== EXPECTED_AGGREGATE_DATABASE_SHA256 ||
      source.aggregateAcquisitionReceiptSha256 !== EXPECTED_ACQUISITION_RECEIPT_SHA256 ||
      source.retainedOrderBookDepth !== 50 || source.bucketMillis !== 5000 ||
      source.bucketsPerUtcDay !== 17280 || source.openInterestInterval !== "M5") {
    throw new Error("Subminute source contract changed.");
  }
}

function validateAcquisition(acquisition) {
  const selection = acquisition?.selectionBlocks ?? [];
  const validation = acquisition?.internalValidationBlocks ?? [];
  const all = [...selection, ...validation];
  if (selection.length !== 4 || validation.length !== 4 || acquisition.sourceDays !== 56 ||
      acquisition.selectionEvaluationDays !== 24 || acquisition.internalValidationEvaluationDays !== 24 ||
      acquisition.warmupDaysPerBlock !== 1 || new Set(all.map((block) => block.id)).size !== 8) {
    throw new Error("Subminute acquisition schedule changed.");
  }
  if (selection.some((block) => !block.era.startsWith("2023")) ||
      validation.some((block) => !block.era.startsWith("2024"))) {
    throw new Error("Subminute selection and validation years must remain chronological.");
  }
  for (const block of all) {
    const sourceStart = Date.parse(`${block.sourceStartDate}T00:00:00Z`);
    const sourceEndExclusive = Date.parse(`${addUtcDays(block.sourceEndDate, 1)}T00:00:00Z`);
    if (Date.parse(block.replayStartAt) !== sourceStart + 86_400_000 ||
        Date.parse(block.replayEndAt) !== sourceEndExclusive) {
      throw new Error(`Subminute block ${block.id} must contain one warmup day and six evaluation days.`);
    }
  }
  if (acquisition.lockedExpansionBlockIds?.length !== 8 ||
      acquisition.lockedValidationBlockIds?.length !== 8 || acquisition.lockedExternalBlockIds?.length !== 8) {
    throw new Error("Later evidence inventories must remain locked.");
  }
}

function validateFeatures(features) {
  if (features?.bookSlices?.bucket !== "HALF_OPEN_UTC_5_SECOND" ||
      features.tradeSlices?.bucket !== "HALF_OPEN_UTC_5_SECOND" ||
      features.relativeFlowBaseline?.lookbackBuckets !== 360 ||
      features.relativeFlowBaseline?.currentBucketExcluded !== true ||
      features.openInterestChange?.lookbackMinutes !== 15 ||
      features.openInterestChange?.latestObservationMustBeAtOrBeforeDecision !== true ||
      features.m15Regime?.closedCandlesOnly !== true ||
      features.liquidationFeature?.historicalSourceStatus !== "ABSENT_NOT_ZERO" ||
      features.liquidationFeature?.candidateUseAllowed !== false) {
    throw new Error("Subminute causal feature contract changed.");
  }
}

function validateHypotheses(protocol) {
  const candidates = expandSubminuteCandidates(protocol);
  if (candidates.length !== 32 || new Set(candidates.map((candidate) => candidate.id)).size !== 32) {
    throw new Error("Subminute grid must expand to 32 unique candidates.");
  }
  const actualHash = sha256(JSON.stringify(protocol.hypotheses));
  if (protocol.hypothesisSha256 !== EXPECTED_HYPOTHESIS_SHA256 || actualHash !== EXPECTED_HYPOTHESIS_SHA256) {
    throw new Error("Subminute hypotheses changed after declaration.");
  }
}

function validateExecution(execution) {
  if (execution?.entry !== "FIRST_CAUSALLY_OBSERVED_BOOK_AFTER_DECISION_WITH_MARKET_TAKER_SLIPPAGE" ||
      execution.maximumEntryDelaySeconds !== 10 || execution.sameSliceConflict !== "STOP_FIRST" ||
      execution.entryFeeRate !== 0.0006 || execution.exitFeeRate !== 0.0006 ||
      execution.entrySlippageRate !== 0.0002 || execution.exitSlippageRate !== 0.0002 ||
      execution.minimumEffectiveStopPct !== 0.004 || execution.targetR !== 3 ||
      execution.maximumHoldingMinutes !== 60 || execution.maximumTradesPerUtcDay !== 5 ||
      execution.overlappingPositionsAllowed !== false || execution.startingEquityUsdt !== 100 ||
      execution.minimumQuantityBtc !== 0.001 || execution.quantityStepBtc !== 0.001 ||
      execution.maximumNotionalUsdt !== 100 || execution.maximumLeverage !== 15) {
    throw new Error("Subminute execution contract changed.");
  }
}

function validateGates(selection, validation) {
  if (selection?.minimumTrades !== 24 || selection.minimumLongTrades !== 8 ||
      selection.minimumShortTrades !== 8 || selection.minimumPositiveQuarterCount !== 3 ||
      selection.totalQuarterCount !== 4 || selection.minimumProfitFactor !== 1.15 ||
      selection.minimumMeanNetR !== 0 || selection.minimumBootstrapLowerMeanNetR !== 0 ||
      selection.maximumDrawdownPct !== 15 || selection.maximumLiquidationCount !== 0 ||
      selection.maximumWinnerProfitConcentration !== 0.35) {
    throw new Error("Subminute selection gate changed.");
  }
  if (validation?.minimumTrades !== 20 || validation.minimumLongTrades !== 6 ||
      validation.minimumShortTrades !== 6 || validation.minimumPositiveQuarterCount !== 3 ||
      validation.totalQuarterCount !== 4 || validation.minimumProfitFactor !== 1.15 ||
      validation.minimumMeanNetR !== 0 || validation.minimumBootstrapLowerMeanNetR !== 0 ||
      validation.maximumDrawdownPct !== 15 || validation.maximumLiquidationCount !== 0 ||
      validation.maximumWinnerProfitConcentration !== 0.35 || validation.costStressMultiplier !== 1.5 ||
      validation.costStressMinimumMeanNetR !== 0) {
    throw new Error("Subminute internal validation gate changed.");
  }
}

function validateOutcome(outcome) {
  if (outcome?.retuneFromInternalValidation !== false ||
      outcome.lockedExpansionMayBeAcquiredOnlyAfterInternalPass !== true ||
      outcome.validation2025MayBeAcquiredOnlyAfterExpansionPass !== true ||
      outcome.external2026MayBeAcquiredOnlyAfterValidationPassAndRefreeze !== true ||
      outcome.freshSealedMayBeAcquiredOnlyAfterExternalPassAndRefreeze !== true ||
      outcome.automaticExecutionAllowed !== false || outcome.liveExecutionAllowed !== false) {
    throw new Error("Subminute outcome policy changed.");
  }
}

function addUtcDays(date, days) {
  const value = new Date(`${date}T00:00:00Z`);
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}
