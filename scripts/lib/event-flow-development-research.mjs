import { DatabaseSync } from "node:sqlite";

import { movingBlockBootstrap } from "./research-evidence.mjs";
import { expandEventFlowCandidates, validateEventFlowProtocol } from "../event-flow-research-protocol.mjs";
import { validateAnalysisContract } from "../event-flow-development-analysis-contract.mjs";

const MINUTE_MS = 60_000;
const DAY_MS = 86_400_000;

export function buildEventCandidates(protocol) {
  validateEventFlowProtocol(protocol);
  const candidates = expandEventFlowCandidates(protocol).map((candidate) => ({
    id: candidateId(candidate),
    ...candidate,
  }));
  if (new Set(candidates.map((candidate) => candidate.id)).size !== candidates.length) {
    throw new Error("Event-flow candidate IDs must be unique.");
  }
  return candidates;
}

export function candidateId(candidate) {
  if (candidate.family === "EVENT_DEPLETION_CONTINUATION") {
    return [
      "edc",
      `ti${numberId(candidate.minimumAbsoluteTakerImbalance)}`,
      `rn${numberId(candidate.minimumRelativeTakerNotional)}`,
      `ob${numberId(candidate.minimumAlignedTop5Imbalance)}`,
      `dp${numberId(candidate.minimumOpposingSideDepletion)}`,
    ].join("_");
  }
  if (candidate.family === "EVENT_ABSORPTION_REVERSAL") {
    return [
      "ear",
      `ti${numberId(candidate.minimumAbsoluteTakerImbalance)}`,
      `rn${numberId(candidate.minimumRelativeTakerNotional)}`,
      `pi${numberId(candidate.maximumDirectionalPriceImpactBps)}`,
      `rp${numberId(candidate.minimumConsumedSideReplenishment)}`,
    ].join("_");
  }
  throw new Error(`Unsupported event-flow family: ${candidate.family}.`);
}

export function loadEventDevelopmentBlocks(databasePath, protocol) {
  validateEventFlowProtocol(protocol);
  const db = new DatabaseSync(databasePath, { readOnly: true });
  try {
    return protocol.stages.development.primaryBlocks.map((block) => loadEventBlock(db, protocol.sourceData.symbol, block));
  } finally {
    db.close();
  }
}

export function loadEventBlock(db, symbol, block) {
  const sourceStartAt = `${block.sourceStartDate}T00:00:00Z`;
  const sourceEndAt = `${nextDate(block.sourceEndDate)}T00:00:00Z`;
  const m1Rows = db.prepare(`
    SELECT
      candle.opened_at,
      candle.open,
      candle.high,
      candle.low,
      candle.close,
      candle.volume,
      taker.taker_buy_notional,
      taker.taker_sell_notional,
      taker_event.trade_count,
      taker_event.open_trade_price,
      taker_event.high_trade_price,
      taker_event.low_trade_price,
      taker_event.close_trade_price,
      book.end_top5_imbalance,
      book.mean_microprice_edge_bps,
      book.bid_added_top5_notional,
      book.bid_removed_top5_notional,
      book.ask_added_top5_notional,
      book.ask_removed_top5_notional
    FROM marketCandles AS candle
    INNER JOIN takerFlowBars AS taker
      ON taker.symbol=candle.symbol AND taker.opened_at=candle.opened_at
    INNER JOIN takerEventFlowBars AS taker_event
      ON taker_event.symbol=candle.symbol AND taker_event.opened_at=candle.opened_at
    INNER JOIN orderBookEventFlowBars AS book
      ON book.symbol=candle.symbol AND book.opened_at=candle.opened_at
    WHERE candle.symbol=? AND candle.timeframe='M1' AND candle.opened_at>=? AND candle.opened_at<?
    ORDER BY candle.opened_at
  `).all(symbol, sourceStartAt, sourceEndAt);
  const m15Rows = db.prepare(`
    SELECT opened_at, open, high, low, close, volume
    FROM marketCandles
    WHERE symbol=? AND timeframe='M15' AND opened_at>=? AND opened_at<?
    ORDER BY opened_at
  `).all(symbol, sourceStartAt, sourceEndAt);
  const expectedDays = (Date.parse(sourceEndAt) - Date.parse(sourceStartAt)) / DAY_MS;
  if (m1Rows.length !== expectedDays * 1_440 || m15Rows.length !== expectedDays * 96) {
    throw new Error(`Event-flow source block ${block.id} does not have exact joined M1/M15 coverage.`);
  }
  return prepareEventBlock({
    ...block,
    m1Rows: m1Rows.map(normalizeJoinedM1),
    m15Rows: m15Rows.map(normalizeM15),
  });
}

export function prepareEventBlock(block) {
  assertContinuous(block.m1Rows, MINUTE_MS, `${block.id} M1`);
  assertContinuous(block.m15Rows, 15 * MINUTE_MS, `${block.id} M15`);
  const m15 = prepareM15Regimes(block.m15Rows);
  const trueRanges = [];
  const takerNotionals = [];
  let trueRangeSum = 0;
  let takerNotionalSum = 0;
  let previousClose = null;
  let m15Index = -1;
  const rows = block.m1Rows.map((row) => {
    const openedAtMs = row.openedAtMs ?? Date.parse(row.openedAt);
    const closeTimeMs = openedAtMs + MINUTE_MS;
    const trueRange = Math.max(
      row.high - row.low,
      Math.abs(row.high - (previousClose ?? row.open)),
      Math.abs(row.low - (previousClose ?? row.open)),
    );
    const totalTakerNotional = row.takerBuyNotional + row.takerSellNotional;
    const atr = trueRanges.length === 20 ? trueRangeSum / 20 : null;
    const relativeTakerNotional = takerNotionals.length === 60 && takerNotionalSum > 0
      ? totalTakerNotional / (takerNotionalSum / 60)
      : null;
    const takerImbalance = totalTakerNotional > 0
      ? (row.takerBuyNotional - row.takerSellNotional) / totalTakerNotional
      : 0;
    const takerDirection = takerImbalance > 0 ? "BUY" : takerImbalance < 0 ? "SELL" : "NEUTRAL";
    const directionSign = takerDirection === "BUY" ? 1 : takerDirection === "SELL" ? -1 : 0;
    const consumedAdded = takerDirection === "BUY" ? row.askAddedTop5Notional : row.bidAddedTop5Notional;
    const consumedRemoved = takerDirection === "BUY" ? row.askRemovedTop5Notional : row.bidRemovedTop5Notional;
    const mutationTotal = consumedAdded + consumedRemoved;
    while (m15Index + 1 < m15.length && m15[m15Index + 1].closeTimeMs <= closeTimeMs) m15Index += 1;
    const regime = m15[m15Index]?.regime ?? "NEUTRAL";
    const prepared = {
      ...row,
      openedAtMs,
      closeTimeMs,
      atr,
      totalTakerNotional,
      relativeTakerNotional,
      takerImbalance,
      takerDirection,
      alignedEndTop5Imbalance: directionSign * row.endTop5Imbalance,
      alignedMicropriceEdgeBps: directionSign * row.meanMicropriceEdgeBps,
      directionalPriceImpactBps: directionSign === 0 || row.openTradePrice <= 0
        ? null
        : directionSign * ((row.closeTradePrice - row.openTradePrice) / row.openTradePrice) * 10_000,
      opposingSideDepletion: mutationTotal > 0 ? (consumedRemoved - consumedAdded) / mutationTotal : null,
      consumedSideReplenishment: mutationTotal > 0 ? (consumedAdded - consumedRemoved) / mutationTotal : null,
      m15Regime: regime,
      m15SourceCloseTimeMs: m15[m15Index]?.closeTimeMs ?? null,
    };
    trueRanges.push(trueRange);
    trueRangeSum += trueRange;
    if (trueRanges.length > 20) trueRangeSum -= trueRanges.shift();
    takerNotionals.push(totalTakerNotional);
    takerNotionalSum += totalTakerNotional;
    if (takerNotionals.length > 60) takerNotionalSum -= takerNotionals.shift();
    previousClose = row.close;
    return prepared;
  });
  return {
    id: block.id,
    era: block.era,
    sourceStartDate: block.sourceStartDate,
    sourceEndDate: block.sourceEndDate,
    replayStartAt: block.replayStartAt,
    replayEndAt: block.replayEndAt,
    rows,
  };
}

export function detectEventSignal(candidate, row) {
  if (
    row.atr == null || row.atr <= 0 ||
    row.relativeTakerNotional == null ||
    row.takerDirection === "NEUTRAL" ||
    Math.abs(row.takerImbalance) < candidate.minimumAbsoluteTakerImbalance ||
    row.relativeTakerNotional < candidate.minimumRelativeTakerNotional
  ) return null;
  if (candidate.family === "EVENT_DEPLETION_CONTINUATION") {
    if (
      row.alignedEndTop5Imbalance < candidate.minimumAlignedTop5Imbalance ||
      row.alignedMicropriceEdgeBps < candidate.minimumAlignedMicropriceEdgeBps ||
      row.opposingSideDepletion == null ||
      row.opposingSideDepletion < candidate.minimumOpposingSideDepletion ||
      row.m15Regime !== row.takerDirection
    ) return null;
    return signalRecord(candidate, row, row.takerDirection);
  }
  if (candidate.family === "EVENT_ABSORPTION_REVERSAL") {
    const orderSide = opposite(row.takerDirection);
    if (
      row.directionalPriceImpactBps == null ||
      row.directionalPriceImpactBps > candidate.maximumDirectionalPriceImpactBps ||
      row.consumedSideReplenishment == null ||
      row.consumedSideReplenishment < candidate.minimumConsumedSideReplenishment ||
      row.alignedEndTop5Imbalance > -candidate.minimumOpposingEndTop5Imbalance ||
      row.m15Regime !== orderSide
    ) return null;
    return signalRecord(candidate, row, orderSide);
  }
  throw new Error(`Unsupported event-flow family: ${candidate.family}.`);
}

export function simulateEventCandidateBlock(candidate, block, executionContract) {
  const replayStartMs = Date.parse(block.replayStartAt);
  const replayEndMs = Date.parse(block.replayEndAt);
  const state = {
    equity: 1,
    peakEquity: 1,
    maxDrawdownPct: 0,
    pending: null,
    position: null,
    trades: [],
    tradesByDay: new Map(),
    cooldownUntilMs: -Infinity,
    rejectedDiscontinuousEntries: 0,
  };
  let lastReplayRow = null;
  for (const row of block.rows) {
    if (row.openedAtMs < replayStartMs) {
      if (row.closeTimeMs === replayStartMs) {
        const signal = detectEventSignal(candidate, row);
        if (signal != null) state.pending = { signal, expectedEntryAtMs: replayStartMs };
      }
      continue;
    }
    if (row.openedAtMs >= replayEndMs) break;
    lastReplayRow = row;
    fillPending(state, candidate, row, executionContract, block.id);
    processPosition(state, row, executionContract);
    if (state.position == null) {
      const signal = detectEventSignal(candidate, row);
      if (signal != null && row.closeTimeMs < replayEndMs) {
        state.pending = {
          signal,
          expectedEntryAtMs: row.closeTimeMs,
        };
      }
    }
    updateMarkToMarket(state, row, executionContract);
  }
  if (state.position != null && lastReplayRow != null) {
    closePosition(state, lastReplayRow.close, lastReplayRow.closeTimeMs, "BLOCK_END", executionContract);
  }
  return {
    blockId: block.id,
    era: block.era,
    candidateId: candidate.id,
    family: candidate.family,
    trades: state.trades,
    rejectedDiscontinuousEntries: state.rejectedDiscontinuousEntries,
    maxDrawdownPct: round(state.maxDrawdownPct),
  };
}

export function runEventDevelopmentReplay({ blocks, candidates, protocol, analysisContract }) {
  validateEventFlowProtocol(protocol);
  validateAnalysisContract(analysisContract);
  if (candidates.length !== protocol.trials.stageCandidateCount) throw new Error("Replay must run every frozen event-flow candidate.");
  const results = candidates.map((candidate) => {
    const blockResults = blocks.map((block) => simulateEventCandidateBlock(candidate, block, protocol.executionContract));
    return {
      id: candidate.id,
      family: candidate.family,
      candidate,
      blockResults,
      trades: blockResults.flatMap((result) => result.trades),
      rejectedDiscontinuousEntries: blockResults.reduce((sum, result) => sum + result.rejectedDiscontinuousEntries, 0),
    };
  });
  return {
    schemaVersion: 1,
    protocolId: protocol.protocolId,
    analysisId: analysisContract.analysisId,
    candidateCount: candidates.length,
    blockCount: blocks.length,
    candidates: results,
    automaticExecutionAllowed: false,
  };
}

export function evaluateEventDevelopment(replay, protocol, analysisContract) {
  validateEventFlowProtocol(protocol);
  validateAnalysisContract(analysisContract);
  const blocks = protocol.stages.development.primaryBlocks;
  const byEra = Map.groupBy(blocks, (block) => block.era);
  const familyReports = [];
  for (const family of ["EVENT_DEPLETION_CONTINUATION", "EVENT_ABSORPTION_REVERSAL"]) {
    const familyCandidates = replay.candidates.filter((candidate) => candidate.family === family);
    const folds = [];
    const pooledValidationTrades = [];
    for (const fold of protocol.developmentEvaluation.nestedEraFolds) {
      const trainingBlocks = fold.trainingEras.flatMap((era) => byEra.get(era) ?? []);
      const rankedTraining = familyCandidates
        .map((candidate) => ({
          candidateId: candidate.id,
          metrics: metricsForEventTrades(candidate.trades, trainingBlocks, protocol),
        }))
        .filter((entry) => trainingEligible(entry.metrics, protocol.developmentEvaluation.trainingEligibility))
        .sort(compareRanked);
      const selected = rankedTraining[0] ?? null;
      const validationBlocks = byEra.get(fold.validationEra) ?? [];
      const selectedResult = selected == null ? null : familyCandidates.find((candidate) => candidate.id === selected.candidateId);
      const validationTrades = selectedResult == null
        ? []
        : filterTradesByBlocks(selectedResult.trades, validationBlocks);
      pooledValidationTrades.push(...validationTrades);
      folds.push({
        id: fold.id,
        trainingEras: fold.trainingEras,
        validationEra: fold.validationEra,
        selectedCandidateId: selected?.candidateId ?? null,
        training: selected?.metrics ?? null,
        validation: metricsForEventTrades(validationTrades, validationBlocks, protocol, { alreadyFiltered: true }),
      });
    }
    pooledValidationTrades.sort(compareTrades);
    const validationBlocks = protocol.developmentEvaluation.nestedEraFolds.flatMap((fold) => byEra.get(fold.validationEra) ?? []);
    const pooledValidation = metricsForEventTrades(
      pooledValidationTrades,
      validationBlocks,
      protocol,
      { alreadyFiltered: true },
    );
    const positiveValidationFolds = folds.filter((fold) => fold.validation.netReturnPct > 0).length;
    const gate = developmentFamilyGate(pooledValidation, positiveValidationFolds, folds, protocol);
    const fullDevelopmentRanking = familyCandidates
      .map((candidate) => ({
        candidateId: candidate.id,
        metrics: metricsForEventTrades(candidate.trades, blocks, protocol),
      }))
      .filter((entry) => trainingEligible(entry.metrics, protocol.developmentEvaluation.trainingEligibility))
      .sort(compareRanked);
    familyReports.push({
      family,
      status: gate.passed ? "DEVELOPMENT_PASSED" : "REJECTED",
      gate,
      folds,
      pooledValidation,
      positiveValidationFolds,
      fullDevelopmentRanking,
      freezeCandidateId: gate.passed ? fullDevelopmentRanking[0]?.candidateId ?? null : null,
    });
  }
  const passing = familyReports.filter((report) => report.status === "DEVELOPMENT_PASSED" && report.freezeCandidateId != null);
  const freezeRecommendation = passing
    .map((report) => ({
      family: report.family,
      candidateId: report.freezeCandidateId,
      metrics: report.fullDevelopmentRanking[0].metrics,
    }))
    .sort((left, right) => compareRanked(left, right) || left.family.localeCompare(right.family))[0] ?? null;
  return {
    schemaVersion: 1,
    protocolId: protocol.protocolId,
    analysisId: analysisContract.analysisId,
    status: freezeRecommendation == null ? "REJECTED" : "CANDIDATE_FREEZE_REQUIRED",
    stageCandidateCount: replay.candidateCount,
    familyReports,
    freezeRecommendation,
    candidateFreezeCommitted: false,
    validationDataAcquisitionAllowed: false,
    externalDataAcquisitionAllowed: false,
    freshSealedDataOpened: false,
    automaticExecutionAllowed: false,
    liveExecutionAllowed: false,
  };
}

export function metricsForEventTrades(trades, blocks, protocol, { alreadyFiltered = false } = {}) {
  const selected = alreadyFiltered ? [...trades] : filterTradesByBlocks(trades, blocks);
  selected.sort(compareTrades);
  const riskFraction = protocol.executionContract.riskFraction;
  let equity = 1;
  let peak = 1;
  let maxDrawdownPct = 0;
  let grossProfitR = 0;
  let grossLossR = 0;
  let wins = 0;
  let liquidationCount = 0;
  for (const trade of selected) {
    const adverseEquity = equity * Math.max(0, 1 + riskFraction * trade.maeR);
    maxDrawdownPct = Math.max(maxDrawdownPct, peak > 0 ? ((peak - adverseEquity) / peak) * 100 : 100);
    equity *= Math.max(0, 1 + riskFraction * trade.netR);
    peak = Math.max(peak, equity);
    maxDrawdownPct = Math.max(maxDrawdownPct, peak > 0 ? ((peak - equity) / peak) * 100 : 100);
    if (trade.netR > 0) {
      wins += 1;
      grossProfitR += trade.netR;
    } else {
      grossLossR += Math.abs(trade.netR);
    }
    if (trade.exitReason === "LIQUIDATION") liquidationCount += 1;
  }
  const netRs = selected.map((trade) => trade.netR);
  const bootstrapConfig = protocol.statistics.bootstrap;
  const bootstrap = netRs.length < 2 ? null : movingBlockBootstrap(netRs, {
    iterations: bootstrapConfig.iterations,
    blockLength: bootstrapConfig.blockLengthTrades,
    confidenceLevel: bootstrapConfig.confidenceLevel,
    seed: bootstrapConfig.seed,
  });
  const observedDays = Math.max(1, blocks.length * protocol.blockContract.evaluationDaysPerBlock);
  const blockMeans = blocks.map((block) => {
    const values = selected.filter((trade) => trade.blockId === block.id).map((trade) => trade.netR);
    return values.length > 0 ? average(values) : 0;
  });
  return {
    blockIds: blocks.map((block) => block.id),
    observedDays,
    tradeCount: selected.length,
    wins,
    winRatePct: selected.length > 0 ? round((wins / selected.length) * 100) : 0,
    netReturnPct: round((equity - 1) * 100),
    compoundDailyReturnPct: equity > 0 ? round(((equity ** (1 / observedDays)) - 1) * 100) : -100,
    meanNetR: netRs.length > 0 ? round(average(netRs)) : 0,
    profitFactor: grossLossR > 0 ? round(grossProfitR / grossLossR) : grossProfitR > 0 ? 999 : 0,
    maxDrawdownPct: round(maxDrawdownPct),
    liquidationCount,
    worstBlockMeanNetR: blockMeans.length > 0 ? round(Math.min(...blockMeans)) : 0,
    maximumWinnerProfitConcentration: grossProfitR > 0 ? round(Math.max(0, ...netRs) / grossProfitR) : 0,
    bootstrap: bootstrap == null ? null : roundObject(bootstrap),
  };
}

function prepareM15Regimes(rows) {
  let fast = null;
  let slow = null;
  return rows.map((row, index) => {
    fast = nextEma(fast, row.close, 20);
    slow = nextEma(slow, row.close, 50);
    const regime = index < 49 ? "NEUTRAL" : fast > slow ? "BUY" : fast < slow ? "SELL" : "NEUTRAL";
    return {
      ...row,
      closeTimeMs: row.openedAtMs + 15 * MINUTE_MS,
      fastEma: fast,
      slowEma: slow,
      regime,
    };
  });
}

function signalRecord(candidate, row, orderSide) {
  return {
    candidateId: candidate.id,
    family: candidate.family,
    orderSide,
    signalOpenedAtMs: row.openedAtMs,
    signalCloseTimeMs: row.closeTimeMs,
    atr: row.atr,
    takerDirection: row.takerDirection,
    takerImbalance: row.takerImbalance,
    relativeTakerNotional: row.relativeTakerNotional,
    alignedEndTop5Imbalance: row.alignedEndTop5Imbalance,
    alignedMicropriceEdgeBps: row.alignedMicropriceEdgeBps,
    directionalPriceImpactBps: row.directionalPriceImpactBps,
    opposingSideDepletion: row.opposingSideDepletion,
    consumedSideReplenishment: row.consumedSideReplenishment,
    m15Regime: row.m15Regime,
  };
}

function fillPending(state, candidate, row, contract, blockId) {
  const pending = state.pending;
  if (pending == null) return;
  if (row.openedAtMs !== pending.expectedEntryAtMs) {
    state.pending = null;
    state.rejectedDiscontinuousEntries += 1;
    return;
  }
  state.pending = null;
  if (state.position != null || row.openedAtMs < state.cooldownUntilMs) return;
  const day = row.openedAt.slice(0, 10);
  const tradesToday = state.tradesByDay.get(day) ?? 0;
  if (tradesToday >= contract.maximumTradesPerUtcDay) return;
  const position = buildPosition(state, candidate, pending.signal, row, contract, blockId);
  if (position == null) return;
  state.position = position;
  state.tradesByDay.set(day, tradesToday + 1);
}

function buildPosition(state, candidate, signal, row, contract, blockId) {
  const side = signal.orderSide;
  const entryPrice = adverseEntryFill(row.open, side, contract.entrySlippageRate);
  const triggerRiskPerUnit = signal.atr * candidate.initialStopAtr;
  const triggerRiskPct = triggerRiskPerUnit / entryPrice;
  if (triggerRiskPct < contract.minimumInitialRiskPct || triggerRiskPct > contract.maximumInitialRiskPct) return null;
  const stopPrice = side === "BUY" ? entryPrice - triggerRiskPerUnit : entryPrice + triggerRiskPerUnit;
  const stopFill = adverseExitFill(stopPrice, side, contract.exitSlippageRate);
  const stopGrossLoss = side === "BUY" ? entryPrice - stopFill : stopFill - entryPrice;
  const stopFees = entryPrice * contract.entryFeeRate + stopFill * contract.exitFeeRate;
  const netRiskPerUnit = stopGrossLoss + stopFees;
  if (!Number.isFinite(netRiskPerUnit) || netRiskPerUnit <= 0) return null;
  const riskAmount = state.equity * contract.riskFraction;
  const quantity = riskAmount / netRiskPerUnit;
  const targetPrice = side === "BUY"
    ? entryPrice + triggerRiskPerUnit * candidate.targetR
    : entryPrice - triggerRiskPerUnit * candidate.targetR;
  const liquidationDistance = (1 / contract.researchLeverage) - contract.maintenanceMarginRate;
  return {
    blockId,
    candidateId: candidate.id,
    family: candidate.family,
    side,
    signal,
    openedAtMs: row.openedAtMs,
    entryPrice,
    triggerRiskPerUnit,
    riskAmount,
    quantity,
    stopPrice,
    targetPrice,
    liquidationPrice: side === "BUY"
      ? entryPrice * (1 - liquidationDistance)
      : entryPrice * (1 + liquidationDistance),
    maxCloseTimeMs: row.openedAtMs + candidate.maximumHoldingMinutes * MINUTE_MS,
    maeR: 0,
    mfeR: 0,
  };
}

function processPosition(state, row, contract) {
  const position = state.position;
  if (position == null) return;
  const favorablePrice = position.side === "BUY" ? row.high : row.low;
  const adversePrice = position.side === "BUY" ? row.low : row.high;
  position.mfeR = Math.max(position.mfeR, netPnl(position, favorablePrice, contract) / position.riskAmount);
  position.maeR = Math.min(position.maeR, netPnl(position, adversePrice, contract) / position.riskAmount);
  const exit = resolveExit(position, row);
  if (exit != null) closePosition(state, exit.price, row.closeTimeMs, exit.reason, contract);
}

export function resolveEventExit(position, row) {
  return resolveExit(position, row);
}

function resolveExit(position, row) {
  const long = position.side === "BUY";
  if (long ? row.open <= position.liquidationPrice : row.open >= position.liquidationPrice) {
    return { price: position.liquidationPrice, reason: "LIQUIDATION" };
  }
  if (long ? row.open <= position.stopPrice : row.open >= position.stopPrice) {
    return { price: row.open, reason: "STOP" };
  }
  if (long ? row.low <= position.stopPrice : row.high >= position.stopPrice) {
    return { price: position.stopPrice, reason: "STOP" };
  }
  if (long ? row.low <= position.liquidationPrice : row.high >= position.liquidationPrice) {
    return { price: position.liquidationPrice, reason: "LIQUIDATION" };
  }
  if (long ? row.high >= position.targetPrice : row.low <= position.targetPrice) {
    return { price: position.targetPrice, reason: "TARGET" };
  }
  if (row.closeTimeMs >= position.maxCloseTimeMs) return { price: row.close, reason: "TIME" };
  return null;
}

function closePosition(state, triggerPrice, closedAtMs, reason, contract) {
  const position = state.position;
  if (position == null) return;
  const exitPrice = reason === "LIQUIDATION"
    ? triggerPrice
    : adverseExitFill(triggerPrice, position.side, contract.exitSlippageRate);
  const pnl = netPnl(position, exitPrice, contract, { priceAlreadyFilled: true });
  const equityBefore = state.equity;
  const netR = pnl / position.riskAmount;
  state.equity = Math.max(0, state.equity + pnl);
  state.trades.push({
    blockId: position.blockId,
    candidateId: position.candidateId,
    family: position.family,
    side: position.side,
    signalAt: instantString(position.signal.signalCloseTimeMs),
    openedAt: instantString(position.openedAtMs),
    closedAt: instantString(closedAtMs),
    openedAtMs: position.openedAtMs,
    closedAtMs,
    exitReason: reason,
    entryPrice: round(position.entryPrice),
    exitPrice: round(exitPrice),
    stopPrice: round(position.stopPrice),
    targetPrice: round(position.targetPrice),
    liquidationPrice: round(position.liquidationPrice),
    quantity: round(position.quantity),
    riskAmount: round(position.riskAmount),
    netR: round(netR),
    maeR: round(Math.min(position.maeR, netR)),
    mfeR: round(Math.max(position.mfeR, netR)),
    returnPct: equityBefore > 0 ? round((pnl / equityBefore) * 100) : -100,
    equityAfterWithinBlock: round(state.equity),
    takerDirection: position.signal.takerDirection,
    takerImbalance: round(position.signal.takerImbalance),
    relativeTakerNotional: round(position.signal.relativeTakerNotional),
    alignedEndTop5Imbalance: round(position.signal.alignedEndTop5Imbalance),
    alignedMicropriceEdgeBps: round(position.signal.alignedMicropriceEdgeBps),
    directionalPriceImpactBps: nullableRound(position.signal.directionalPriceImpactBps),
    opposingSideDepletion: nullableRound(position.signal.opposingSideDepletion),
    consumedSideReplenishment: nullableRound(position.signal.consumedSideReplenishment),
    m15Regime: position.signal.m15Regime,
  });
  state.cooldownUntilMs = closedAtMs + contract.cooldownMinutes * MINUTE_MS;
  state.position = null;
  updateDrawdown(state, state.equity);
}

function netPnl(position, exitTriggerPrice, contract, { priceAlreadyFilled = false } = {}) {
  const exitPrice = priceAlreadyFilled
    ? exitTriggerPrice
    : adverseExitFill(exitTriggerPrice, position.side, contract.exitSlippageRate);
  const gross = position.side === "BUY"
    ? (exitPrice - position.entryPrice) * position.quantity
    : (position.entryPrice - exitPrice) * position.quantity;
  const fees = position.entryPrice * position.quantity * contract.entryFeeRate +
    exitPrice * position.quantity * contract.exitFeeRate;
  return gross - fees;
}

function updateMarkToMarket(state, row, contract) {
  const equity = state.position == null ? state.equity : state.equity + netPnl(state.position, row.close, contract);
  updateDrawdown(state, equity);
}

function updateDrawdown(state, equity) {
  state.peakEquity = Math.max(state.peakEquity, equity);
  if (state.peakEquity > 0) {
    state.maxDrawdownPct = Math.max(state.maxDrawdownPct, ((state.peakEquity - equity) / state.peakEquity) * 100);
  }
}

function developmentFamilyGate(metrics, positiveFolds, folds, protocol) {
  const gate = protocol.developmentEvaluation.familyGate;
  const checks = {
    everyFoldSelectedCandidate: folds.every((fold) => fold.selectedCandidateId != null),
    minimumPooledValidationTrades: metrics.tradeCount >= gate.minimumPooledValidationTrades,
    minimumPositiveValidationFolds: positiveFolds >= gate.minimumPositiveValidationFolds,
    minimumPooledProfitFactor: metrics.profitFactor >= gate.minimumPooledProfitFactor,
    minimumPooledMeanNetR: metrics.meanNetR > gate.minimumPooledMeanNetR,
    minimumBootstrapLowerMeanNetR: (metrics.bootstrap?.lowerBound ?? -Infinity) > gate.minimumBootstrapLowerMeanNetR,
    maximumPooledDrawdownPct: metrics.maxDrawdownPct <= gate.maximumPooledDrawdownPct,
    maximumLiquidationCount: metrics.liquidationCount <= gate.maximumLiquidationCount,
  };
  return { passed: Object.values(checks).every(Boolean), checks };
}

function trainingEligible(metrics, gate) {
  return metrics.tradeCount >= gate.minimumTrades &&
    metrics.profitFactor >= gate.minimumProfitFactor &&
    metrics.meanNetR > gate.minimumMeanNetR &&
    metrics.maxDrawdownPct <= gate.maximumDrawdownPct &&
    metrics.liquidationCount <= gate.maximumLiquidationCount;
}

function compareRanked(left, right) {
  const bootstrap = (right.metrics.bootstrap?.lowerBound ?? -Infinity) - (left.metrics.bootstrap?.lowerBound ?? -Infinity);
  if (bootstrap !== 0) return bootstrap;
  const worstBlock = right.metrics.worstBlockMeanNetR - left.metrics.worstBlockMeanNetR;
  if (worstBlock !== 0) return worstBlock;
  const profitFactor = right.metrics.profitFactor - left.metrics.profitFactor;
  if (profitFactor !== 0) return profitFactor;
  return left.candidateId.localeCompare(right.candidateId);
}

function filterTradesByBlocks(trades, blocks) {
  const ids = new Set(blocks.map((block) => block.id));
  return trades.filter((trade) => ids.has(trade.blockId));
}

function normalizeJoinedM1(row) {
  return validateNumericRow({
    openedAt: row.opened_at,
    openedAtMs: Date.parse(row.opened_at),
    open: Number(row.open),
    high: Number(row.high),
    low: Number(row.low),
    close: Number(row.close),
    volume: Number(row.volume),
    takerBuyNotional: Number(row.taker_buy_notional),
    takerSellNotional: Number(row.taker_sell_notional),
    tradeCount: Number(row.trade_count),
    openTradePrice: Number(row.open_trade_price),
    highTradePrice: Number(row.high_trade_price),
    lowTradePrice: Number(row.low_trade_price),
    closeTradePrice: Number(row.close_trade_price),
    endTop5Imbalance: Number(row.end_top5_imbalance),
    meanMicropriceEdgeBps: Number(row.mean_microprice_edge_bps),
    bidAddedTop5Notional: Number(row.bid_added_top5_notional),
    bidRemovedTop5Notional: Number(row.bid_removed_top5_notional),
    askAddedTop5Notional: Number(row.ask_added_top5_notional),
    askRemovedTop5Notional: Number(row.ask_removed_top5_notional),
  });
}

function normalizeM15(row) {
  return validateNumericRow({
    openedAt: row.opened_at,
    openedAtMs: Date.parse(row.opened_at),
    open: Number(row.open),
    high: Number(row.high),
    low: Number(row.low),
    close: Number(row.close),
    volume: Number(row.volume),
  });
}

function validateNumericRow(row) {
  for (const [key, value] of Object.entries(row)) {
    if (key !== "openedAt" && !Number.isFinite(value)) throw new Error(`Non-finite event-flow source value ${key} at ${row.openedAt}.`);
  }
  if (!Number.isFinite(row.openedAtMs) || row.open <= 0 || row.high < row.low || row.volume < 0) {
    throw new Error(`Invalid event-flow candle at ${row.openedAt}.`);
  }
  return row;
}

function assertContinuous(rows, intervalMs, label) {
  for (let index = 1; index < rows.length; index += 1) {
    if (rows[index].openedAtMs !== rows[index - 1].openedAtMs + intervalMs) {
      throw new Error(`${label} is discontinuous at ${rows[index].openedAt}.`);
    }
  }
}

function nextEma(previous, value, period) {
  return previous == null ? value : previous + (value - previous) * (2 / (period + 1));
}

function adverseEntryFill(price, side, rate) {
  return side === "BUY" ? price * (1 + rate) : price * (1 - rate);
}

function adverseExitFill(price, side, rate) {
  return side === "BUY" ? price * (1 - rate) : price * (1 + rate);
}

function opposite(side) {
  return side === "BUY" ? "SELL" : side === "SELL" ? "BUY" : "NEUTRAL";
}

function compareTrades(left, right) {
  return left.closedAtMs - right.closedAtMs || left.blockId.localeCompare(right.blockId);
}

function average(values) {
  return values.reduce((sum, value) => sum + value, 0) / values.length;
}

function numberId(value) {
  return String(value).replace(".", "p");
}

function nullableRound(value) {
  return value == null ? null : round(value);
}

function round(value) {
  return Number(Number(value).toFixed(8));
}

function roundObject(value) {
  return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, typeof item === "number" ? round(item) : item]));
}

function instantString(milliseconds) {
  return new Date(milliseconds).toISOString();
}

function nextDate(date) {
  const value = new Date(`${date}T00:00:00Z`);
  value.setUTCDate(value.getUTCDate() + 1);
  return value.toISOString().slice(0, 10);
}
