import {
  metricsForEventTrades,
  simulateEventCandidateBlock,
} from "./event-flow-development-research.mjs";
import {
  expandFailedSweepCandidates,
  validateFailedSweepProtocol,
} from "../event-flow-failed-sweep-protocol.mjs";

export function runFailedSweepReplay({ blocks, protocol, primaryProtocol, extensionProtocol }) {
  validateFailedSweepProtocol(protocol, primaryProtocol, extensionProtocol);
  const candidates = expandFailedSweepCandidates(protocol);
  const candidateResults = candidates.map((candidate) => {
    const blockResults = blocks.map((block) => simulateEventCandidateBlock(
      candidate,
      block,
      protocol.executionContract,
    ));
    return {
      id: candidate.id,
      family: candidate.family,
      candidate,
      blockResults,
      trades: blockResults.flatMap((result) => result.trades),
      rejectedDiscontinuousEntries: blockResults.reduce(
        (sum, result) => sum + result.rejectedDiscontinuousEntries,
        0,
      ),
    };
  });
  return {
    schemaVersion: 1,
    protocolId: protocol.protocolId,
    candidateCount: candidateResults.length,
    blockCount: blocks.length,
    candidates: candidateResults,
    validationDataRead: false,
    externalDataRead: false,
    freshSealedDataRead: false,
    automaticExecutionAllowed: false,
    liveExecutionAllowed: false,
  };
}

export function evaluateFailedSweep(replay, blocks, protocol, primaryProtocol, extensionProtocol) {
  validateFailedSweepProtocol(protocol, primaryProtocol, extensionProtocol);
  if (replay.candidateCount !== 16 || replay.candidates.length !== 16) {
    throw new Error("Failed-sweep evaluation requires all 16 frozen candidates.");
  }
  const blockById = new Map(blocks.map((block) => [block.id, block]));
  const selectionBlocks = resolveBlocks(
    blockById,
    protocol.chronologicalDevelopment.candidateSelection.blockIds,
  );
  const eligibility = protocol.chronologicalDevelopment.candidateSelection.eligibility;
  const selectionRanking = replay.candidates
    .map((candidate) => ({
      candidateId: candidate.id,
      metrics: metricsForEventTrades(candidate.trades, selectionBlocks, protocol),
    }))
    .map((entry) => ({ ...entry, eligible: isEligible(entry.metrics, eligibility) }))
    .sort(compareSelection);
  const selected = selectionRanking.find((entry) => entry.eligible) ?? null;
  const selectedReplay = selected == null
    ? null
    : replay.candidates.find((candidate) => candidate.id === selected.candidateId);
  const validationEras = protocol.chronologicalDevelopment.validationEras.map((era) => {
    const eraBlocks = resolveBlocks(blockById, era.blockIds);
    return {
      id: era.id,
      metrics: metricsForEventTrades(selectedReplay?.trades ?? [], eraBlocks, protocol),
    };
  });
  const validationBlocks = protocol.chronologicalDevelopment.validationEras.flatMap((era) =>
    resolveBlocks(blockById, era.blockIds));
  const validationIds = new Set(validationBlocks.map((block) => block.id));
  const validationTrades = (selectedReplay?.trades ?? []).filter((trade) => validationIds.has(trade.blockId));
  const pooledValidation = metricsForEventTrades(validationTrades, validationBlocks, protocol, {
    alreadyFiltered: true,
  });
  const longTrades = validationTrades.filter((trade) => trade.side === "BUY").length;
  const shortTrades = validationTrades.filter((trade) => trade.side === "SELL").length;
  const positiveEraCount = validationEras.filter((era) => era.metrics.netReturnPct > 0).length;
  const gate = protocol.chronologicalDevelopment.validationGate;
  const checks = {
    selectedCandidateExists: selected != null,
    minimumTrades: pooledValidation.tradeCount >= gate.minimumTrades,
    minimumLongTrades: longTrades >= gate.minimumLongTrades,
    minimumShortTrades: shortTrades >= gate.minimumShortTrades,
    minimumPositiveEraCount: positiveEraCount >= gate.minimumPositiveEraCount,
    minimumProfitFactor: pooledValidation.profitFactor >= gate.minimumProfitFactor,
    minimumMeanNetR: pooledValidation.meanNetR > gate.minimumMeanNetR,
    minimumBootstrapLowerMeanNetR: (pooledValidation.bootstrap?.lowerBound ?? -Infinity) >
      gate.minimumBootstrapLowerMeanNetR,
    maximumDrawdownPct: pooledValidation.maxDrawdownPct <= gate.maximumDrawdownPct,
    maximumLiquidationCount: pooledValidation.liquidationCount <= gate.maximumLiquidationCount,
    maximumWinnerProfitConcentration: pooledValidation.maximumWinnerProfitConcentration <=
      gate.maximumWinnerProfitConcentration,
  };
  const passed = Object.values(checks).every(Boolean);
  return {
    schemaVersion: 1,
    status: passed ? "CANDIDATE_FREEZE_REQUIRED" : "REJECTED_FAILED_SWEEP_REVERSAL",
    selectedCandidateId: selected?.candidateId ?? null,
    selection: selected?.metrics ?? null,
    selectionRanking,
    validationEras,
    pooledValidation,
    longTrades,
    shortTrades,
    positiveEraCount,
    gate: { passed, checks },
    freezeRecommendation: passed ? selectedReplay.candidate : null,
    candidateFreezeCommitted: false,
    validationDataAcquisitionAllowed: false,
    externalDataAcquisitionAllowed: false,
    freshSealedDataAcquisitionAllowed: false,
    automaticExecutionAllowed: false,
    liveExecutionAllowed: false,
  };
}

function resolveBlocks(blockById, ids) {
  return ids.map((id) => {
    const block = blockById.get(id);
    if (block == null) throw new Error(`Failed-sweep source block ${id} is missing.`);
    return block;
  });
}

function isEligible(metrics, gate) {
  return metrics.tradeCount >= gate.minimumTrades &&
    metrics.profitFactor >= gate.minimumProfitFactor &&
    metrics.meanNetR > gate.minimumMeanNetR &&
    metrics.maxDrawdownPct <= gate.maximumDrawdownPct &&
    metrics.liquidationCount <= gate.maximumLiquidationCount;
}

function compareSelection(left, right) {
  if (left.eligible !== right.eligible) return left.eligible ? -1 : 1;
  const bootstrap = (right.metrics.bootstrap?.lowerBound ?? -Infinity) -
    (left.metrics.bootstrap?.lowerBound ?? -Infinity);
  if (bootstrap !== 0) return bootstrap;
  const worstBlock = right.metrics.worstBlockMeanNetR - left.metrics.worstBlockMeanNetR;
  if (worstBlock !== 0) return worstBlock;
  const profitFactor = right.metrics.profitFactor - left.metrics.profitFactor;
  if (profitFactor !== 0) return profitFactor;
  return left.candidateId.localeCompare(right.candidateId);
}
