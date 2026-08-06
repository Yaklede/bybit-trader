import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";

const EXPECTED_PARENT_SHA256 = "ab8eb314aad8f6b0a203011657a1ae84a7c86e7f61a127c6fedcaec78a2de7f2";
const EXPECTED_BASE_PROTOCOL_SHA256 = "42e9a485e747cc12d50263fee26f20318c50e04108efeeed6fa1171811625479";
const EXPECTED_SIMULATOR_SHA256 = "561d3b11a9d73cd8f82e35338aafeaab92d55f65728015be7510c7abc1a92c5c";

export async function loadMultiAssetCostRecoveryCarryProtocol(path) {
  const bytes = await readFile(path);
  const protocol = JSON.parse(bytes);
  const root = resolve(dirname(path), "..");
  const parentBytes = await readFile(resolve(root, protocol.parentExternalResult.path));
  const baseProtocolBytes = await readFile(resolve(root, protocol.baseDevelopmentProtocol.path));
  const simulatorBytes = await readFile(resolve(root, protocol.implementationBinding.simulatorPath));
  const evidenceReceipts = await Promise.all(protocol.evidence.map(async (entry) => {
    const receiptBytes = await readFile(resolve(root, entry.receiptPath));
    return { entry, receipt: JSON.parse(receiptBytes), sha256: sha256(receiptBytes) };
  }));
  validateMultiAssetCostRecoveryCarryProtocol({
    protocol,
    parentResult: JSON.parse(parentBytes),
    parentSha256: sha256(parentBytes),
    baseProtocol: JSON.parse(baseProtocolBytes),
    baseProtocolSha256: sha256(baseProtocolBytes),
    simulatorSha256: sha256(simulatorBytes),
    evidenceReceipts,
  });
  return {
    protocol,
    sha256: sha256(bytes),
    parentResultSha256: sha256(parentBytes),
    baseProtocolSha256: sha256(baseProtocolBytes),
    simulatorSha256: sha256(simulatorBytes),
  };
}

export function expandMultiAssetCostRecoveryCarryCandidates(protocol) {
  const grid = protocol.candidateGrid;
  const candidates = [];
  let sequence = 1;
  for (const streak of grid.minimumPositiveFundingStreak) {
    for (const medianFunding of grid.minimumTrailingMedianFundingRate) {
      for (const holdingDays of grid.maximumHoldingDays) {
        for (const exitCount of grid.exitConsecutiveNonPositiveFundingCount) {
          candidates.push({
            id: `multi_asset_cost_recovery_carry_${String(sequence).padStart(3, "0")}`,
            ...grid.fixed,
            projectedCarryHorizonSettlements: Math.round(holdingDays * 1.5),
            minimumPositiveFundingStreak: streak,
            minimumTrailingMedianFundingRate: medianFunding,
            maximumHoldingDays: holdingDays,
            exitConsecutiveNonPositiveFundingCount: exitCount,
          });
          sequence += 1;
        }
      }
    }
  }
  return candidates;
}

export function validateMultiAssetCostRecoveryCarryProtocol(context) {
  const { protocol, parentResult, parentSha256, baseProtocol, baseProtocolSha256,
    simulatorSha256, evidenceReceipts } = context;
  if (protocol?.protocolId !== "bybit-multi-asset-cost-recovery-carry-development-v3" ||
      protocol.status !== "PREDECLARED_BEFORE_V3_DEVELOPMENT_GRID_REPLAY") {
    throw new Error("Cost-recovery carry protocol identity changed.");
  }
  if (parentSha256 !== EXPECTED_PARENT_SHA256 ||
      protocol.parentExternalResult?.sha256 !== EXPECTED_PARENT_SHA256 ||
      parentResult?.programStatus !== "REJECTED_MULTI_ASSET_EXTERNAL_VALIDATION" ||
      parentResult.decision?.successorResearchMayUse2023Through2025AsDisclosedDevelopmentEvidence !== true ||
      parentResult.evidenceBoundary?.sealed2026Read !== false) {
    throw new Error("Cost-recovery carry must follow the rejected v2 boundary.");
  }
  if (baseProtocolSha256 !== EXPECTED_BASE_PROTOCOL_SHA256 ||
      protocol.baseDevelopmentProtocol?.sha256 !== EXPECTED_BASE_PROTOCOL_SHA256 ||
      simulatorSha256 !== EXPECTED_SIMULATOR_SHA256 ||
      protocol.implementationBinding?.simulatorSha256 !== EXPECTED_SIMULATOR_SHA256 ||
      protocol.implementationBinding.simulatorMayChangeDuringGridReplay !== false) {
    throw new Error("Cost-recovery carry execution lineage changed.");
  }
  validateBoundary(protocol.researchBoundary);
  validateTrials(protocol.trialAccounting);
  validateEvidence(protocol.evidence, evidenceReceipts);
  validateGrid(protocol);
  validateGate(protocol.developmentGate);
  validateSelection(protocol.selectionPolicy);
  if (protocol.outcomePolicy?.sealed2026MayBeAcquiredOnlyAfterOneCandidatePassesEveryDevelopmentGate !== true ||
      protocol.outcomePolicy.freshForwardShadowAndPaperRequiredBeforeLive !== true ||
      protocol.outcomePolicy.automaticExecutionAllowed !== false ||
      protocol.outcomePolicy.liveExecutionAllowed !== false) {
    throw new Error("Cost-recovery carry outcome policy changed.");
  }
  if (baseProtocol.executionContract?.baseRoundTripCostRateOnMatchedNotional !== 0.0041 ||
      baseProtocol.executionContract.maximumTotalMatchedNotionalFractionOfEquity !== 0.4 ||
      baseProtocol.statistics?.bootstrapSamples !== 10000) {
    throw new Error("Cost-recovery carry base execution assumptions changed.");
  }
  return protocol;
}

function validateBoundary(boundary) {
  if (boundary?.kind !== "DISCLOSED_2023_2025_DEVELOPMENT_WITH_UNREAD_2026_SEAL" ||
      boundary.development2023Read !== true || boundary.development2024Read !== true ||
      boundary.development2025Read !== true ||
      boundary.sealed2026OfficialPayloadsReadBeforeDeclaration !== false ||
      boundary.sealed2026OutcomeReadBeforeDeclaration !== false ||
      boundary.freshForwardSealRequiredBeforeLive !== true) {
    throw new Error("Cost-recovery carry evidence boundary changed.");
  }
}

function validateTrials(trials) {
  if (trials?.priorObservedCandidatesAndProtocols !== 336 ||
      trials.frozenCandidatesToEvaluate !== 54 ||
      trials.cumulativeObservedCandidatesAndProtocolsAfterReplay !== 390 ||
      trials.gridMayExpandAfterOutcome !== false) {
    throw new Error("Cost-recovery carry trial accounting changed.");
  }
}

function validateEvidence(evidence, receipts) {
  if (evidence?.map((entry) => entry.year).join("|") !== "2023|2024|2025" ||
      receipts.length !== 3) {
    throw new Error("Cost-recovery carry requires exactly 2023 through 2025 evidence.");
  }
  for (const { entry, receipt, sha256: actualSha256 } of receipts) {
    if (entry.receiptSha256 !== actualSha256 || receipt.stageSnapshot !== entry.snapshotPath ||
        receipt.stageSnapshotSha256 !== entry.snapshotSha256 ||
        receipt.coverage?.missingDecisionInputCount !== 0 ||
        !Array.isArray(entry.blocks) || entry.blocks.length !== 4) {
      throw new Error(`Cost-recovery carry evidence ${entry.year} changed.`);
    }
  }
}

function validateGrid(protocol) {
  const grid = protocol.candidateGrid;
  const fixed = grid?.fixed;
  if (grid?.minimumPositiveFundingStreak?.join("|") !== "3|6|12" ||
      grid.minimumTrailingMedianFundingRate?.join("|") !== "0.000075|0.0001" ||
      grid.maximumHoldingDays?.join("|") !== "30|60|90" ||
      grid.exitConsecutiveNonPositiveFundingCount?.join("|") !== "6|12|24" ||
      grid.projectedCarryHorizonSettlementsFormula !== "ROUND_MAXIMUM_HOLDING_DAYS_TIMES_1_5" ||
      fixed?.minimumEntryBasisPct !== -0.001 || fixed.maximumEntryBasisPct !== 0.003 ||
      fixed.maximumAbsoluteMarkIndexPremiumPct !== 0.003 ||
      fixed.basisDivergenceStopPctFromEntry !== 0.01 || fixed.reentryCooldownHours !== 24 ||
      fixed.minimumProjectedNetCarryScore !== 0.001 || fixed.maximumConcurrentPairs !== 2 ||
      expandMultiAssetCostRecoveryCarryCandidates(protocol).length !== 54) {
    throw new Error("Cost-recovery carry candidate grid changed.");
  }
}

function validateGate(gate) {
  if (gate?.minimumTotalClosedPositions !== 15 || gate.minimumClosedPositionsPerYear !== 3 ||
      gate.minimumTradedAssetCountPerYear !== 3 || gate.minimumPositiveAssetCountPerYear !== 2 ||
      gate.minimumPositiveQuarterCountPerYear !== 2 || gate.minimumTotalPositiveQuarterCount !== 8 ||
      gate.minimumNetReturnPctPerYear !== 0 || gate.minimumProfitFactorPerYear !== 1.1 ||
      gate.minimumMeanDailyReturnPctPerYear !== 0 ||
      gate.minimumBootstrapLowerMeanDailyReturnPctPerYear !== 0 ||
      gate.maximumDrawdownPctPerYear !== 5 || gate.maximumLiquidationCount !== 0 ||
      gate.maximumPositivePositionProfitConcentrationPerYear !== 0.6 ||
      gate.maximumPositiveAssetProfitConcentrationPerYear !== 0.75 ||
      gate.minimumCostStressNetReturnPctPerYear !== 0 ||
      gate.minimumSecondLegDelayStressNetReturnPctPerYear !== 0) {
    throw new Error("Cost-recovery carry development gate changed.");
  }
}

function validateSelection(policy) {
  if (policy?.maximumSelectedCandidateCount !== 1 || policy.allDevelopmentGatesMustPass !== true ||
      policy.rankBy?.join("|") !==
        "WORST_YEAR_COST_STRESS_RETURN_DESC|WORST_YEAR_DELAY_STRESS_RETURN_DESC|WORST_YEAR_NET_RETURN_DESC|THREE_YEAR_COMPOUNDED_RETURN_DESC|CANDIDATE_ID_ASC" ||
      policy.candidateMayBeRetunedAfterOutcome !== false || policy.gateMayBeChangedAfterOutcome !== false) {
    throw new Error("Cost-recovery carry selection policy changed.");
  }
}

function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}
