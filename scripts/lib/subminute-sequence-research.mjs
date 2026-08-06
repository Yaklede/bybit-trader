import { createHash } from "node:crypto";

const FIVE_SECONDS_MILLIS = 5_000;
const ONE_MINUTE_MILLIS = 60_000;
const ONE_DAY_MILLIS = 86_400_000;

export function loadSubminuteBlock(db, symbol, block) {
  const sourceStartAt = `${block.sourceStartDate}T00:00:00Z`;
  const sourceEndAt = `${addUtcDays(block.sourceEndDate, 1)}T00:00:00Z`;
  const rows = db.prepare(`
    SELECT
      b.opened_at,
      b.message_count,
      b.carried_forward,
      b.close_best_bid,
      b.close_best_ask,
      b.open_mid_price,
      b.high_mid_price,
      b.low_mid_price,
      b.close_mid_price,
      b.mean_top5_imbalance,
      b.mean_microprice_edge_bps,
      b.bid_added_top5_notional,
      b.bid_removed_top5_notional,
      b.ask_added_top5_notional,
      b.ask_removed_top5_notional,
      t.trade_count,
      t.buy_notional,
      t.sell_notional,
      t.buy_count,
      t.sell_count,
      t.open_price,
      t.high_price,
      t.low_price,
      t.close_price
    FROM subminuteOrderBookSlices b
    JOIN subminuteTradeSlices t ON t.symbol=b.symbol AND t.opened_at=b.opened_at
    WHERE b.symbol=? AND b.opened_at>=? AND b.opened_at<?
    ORDER BY b.opened_at
  `).all(symbol, sourceStartAt, sourceEndAt).map(parseSliceRow);
  assertContiguousSlices(rows, block.sourceStartDate, block.sourceEndDate);

  const m1Candles = loadCandles(db, symbol, "M1", sourceStartAt, sourceEndAt);
  const m15Candles = loadCandles(db, symbol, "M15", sourceStartAt, sourceEndAt);
  const openInterest = db.prepare(`
    SELECT timestamp, open_interest FROM openInterestSnapshots
    WHERE symbol=? AND interval='M5' AND timestamp>=? AND timestamp<? ORDER BY timestamp
  `).all(symbol, sourceStartAt, sourceEndAt).map((row) => ({
    timestamp: Date.parse(row.timestamp),
    value: Number(row.open_interest),
  }));
  const funding = db.prepare(`
    SELECT timestamp, funding_rate FROM fundingRates
    WHERE symbol=? AND timestamp>=? AND timestamp<? ORDER BY timestamp
  `).all(symbol, sourceStartAt, sourceEndAt).map((row) => ({
    timestamp: Date.parse(row.timestamp),
    rate: Number(row.funding_rate),
  }));
  return prepareBlockFeatures({ block, rows, m1Candles, m15Candles, openInterest, funding });
}

export function prepareBlockFeatures({ block, rows, m1Candles, m15Candles, openInterest, funding }) {
  const totalNotional = rows.map((row) => row.buyNotional + row.sellNotional);
  const baseline = rollingMedianBefore(totalNotional, 360, 300);
  const tradeImbalance = rows.map((row, index) => totalNotional[index] === 0
    ? 0
    : (row.buyNotional - row.sellNotional) / totalNotional[index]);
  const normalizedMicropriceEdge = rows.map(normalizeMicropriceEdge);
  const regimeTimeline = buildM15RegimeTimeline(m15Candles);
  const atrTimeline = buildAtrTimeline(m1Candles, 14);
  const regime = pointInTimeValues(rows, regimeTimeline, (value) => value.regime, 0);
  const atr = pointInTimeValues(rows, atrTimeline, (value) => value.atr, null);
  const openInterestChange = pointInTimeOpenInterestChange(rows, openInterest, 15, 10);
  return {
    block,
    rows,
    totalNotional,
    tradeImbalance,
    normalizedMicropriceEdge,
    baseline,
    regime,
    atr,
    openInterestChange,
    funding,
    prefix: buildPrefixes(rows),
    replayStartMillis: Date.parse(block.replayStartAt),
    replayEndMillis: Date.parse(block.replayEndAt),
  };
}

export function evaluateSubminuteCandidates(protocol, candidates, blocks) {
  return candidates.map((candidate) => evaluateSubminuteCandidate(protocol, candidate, blocks));
}

export function evaluateSubminuteCandidate(protocol, candidate, blocks) {
  const execution = protocol.executionContract;
  const state = {
    equity: execution.startingEquityUsdt,
    peakEquity: execution.startingEquityUsdt,
    maxDrawdownPct: 0,
    trades: [],
  };
  const blockResults = [];
  for (const features of blocks) {
    const blockStartEquity = state.equity;
    const blockTradesStart = state.trades.length;
    evaluateCandidateBlock(candidate, features, execution, state);
    const trades = state.trades.slice(blockTradesStart);
    blockResults.push({
      blockId: features.block.id,
      era: features.block.era,
      evaluationDays: (features.replayEndMillis - features.replayStartMillis) / ONE_DAY_MILLIS,
      tradeCount: trades.length,
      longTrades: trades.filter((trade) => trade.direction === 1).length,
      shortTrades: trades.filter((trade) => trade.direction === -1).length,
      netPnl: state.equity - blockStartEquity,
      netReturnPct: blockStartEquity === 0 ? 0 : ((state.equity / blockStartEquity) - 1) * 100,
      meanNetR: mean(trades.map((trade) => trade.netR)),
    });
  }
  return summarizeCandidate(protocol, candidate, state, blockResults);
}

function evaluateCandidateBlock(candidate, features, execution, state) {
  const rows = features.rows;
  const evaluationStartIndex = lowerBound(rows, features.replayStartMillis);
  const evaluationEndIndex = lowerBound(rows, features.replayEndMillis);
  const shockBuckets = candidate.shockWindowSeconds / 5;
  const confirmationBuckets = candidate.confirmationWindowSeconds / 5;
  const dailyTrades = new Map();
  let index = evaluationStartIndex;
  let cooldownUntil = -Infinity;
  while (index < evaluationEndIndex) {
    const decisionAt = rows[index].openedAt + FIVE_SECONDS_MILLIS;
    if (decisionAt < cooldownUntil) {
      index += 1;
      continue;
    }
    const day = new Date(decisionAt).toISOString().slice(0, 10);
    if ((dailyTrades.get(day) ?? 0) >= execution.maximumTradesPerUtcDay) {
      index += 1;
      continue;
    }
    const setup = detectSubminuteSetup(candidate, features, index, shockBuckets);
    if (setup == null) {
      index += 1;
      continue;
    }
    let confirmation = null;
    const confirmationEnd = Math.min(index + confirmationBuckets, evaluationEndIndex - 2);
    for (let confirmationIndex = index + 1; confirmationIndex <= confirmationEnd; confirmationIndex += 1) {
      if (isConfirmation(candidate, features, setup, index, confirmationIndex)) {
        confirmation = confirmationIndex;
        break;
      }
    }
    if (confirmation == null) {
      index = confirmationEnd + 1;
      continue;
    }
    const entryIndex = confirmation + 1;
    const entryAt = rows[entryIndex].openedAt + FIVE_SECONDS_MILLIS;
    if (entryAt - (rows[confirmation].openedAt + FIVE_SECONDS_MILLIS) > execution.maximumEntryDelaySeconds * 1_000 ||
        entryAt + execution.maximumHoldingMinutes * ONE_MINUTE_MILLIS > features.replayEndMillis) {
      index = entryIndex + 1;
      continue;
    }
    const trade = simulateSubminuteTrade({
      candidate,
      features,
      setup,
      confirmationIndex: confirmation,
      entryIndex,
      execution,
      equity: state.equity,
      evaluationEndIndex,
    });
    if (trade == null) {
      index = entryIndex + 1;
      continue;
    }
    state.maxDrawdownPct = Math.max(
      state.maxDrawdownPct,
      state.peakEquity === 0 ? 0 : ((state.peakEquity - (state.equity + trade.minimumOpenPnl)) / state.peakEquity) * 100,
    );
    state.equity += trade.netPnl;
    state.peakEquity = Math.max(state.peakEquity, state.equity);
    state.maxDrawdownPct = Math.max(
      state.maxDrawdownPct,
      state.peakEquity === 0 ? 0 : ((state.peakEquity - state.equity) / state.peakEquity) * 100,
    );
    state.trades.push({ ...trade, equityAfter: state.equity, blockId: features.block.id, era: features.block.era });
    dailyTrades.set(day, (dailyTrades.get(day) ?? 0) + 1);
    cooldownUntil = trade.exitAt + execution.cooldownAfterExitMinutes * ONE_MINUTE_MILLIS;
    index = Math.max(trade.exitIndex + 1, entryIndex + 1);
  }
}

export function detectSubminuteSetup(candidate, features, index, shockBuckets = candidate.shockWindowSeconds / 5) {
  const start = index - shockBuckets + 1;
  const before = start - 1;
  if (before < 0 || features.baseline[start] == null || features.regime[index] === 0 ||
      features.openInterestChange[index] == null) return null;
  const buy = rangeSum(features.prefix.buyNotional, start, index + 1);
  const sell = rangeSum(features.prefix.sellNotional, start, index + 1);
  const total = buy + sell;
  if (total <= 0) return null;
  const imbalance = (buy - sell) / total;
  const direction = imbalance > 0 ? 1 : -1;
  if (Math.abs(imbalance) < candidate.minimumAbsoluteTakerImbalance) return null;
  const baselineTotal = features.baseline[start] * shockBuckets;
  if (baselineTotal <= 0 || total / baselineTotal < candidate.minimumRelativeTakerNotional) return null;
  const startMid = features.rows[before].closeMidPrice;
  const endMid = features.rows[index].closeMidPrice;
  const directionalImpactBps = direction * ((endMid - startMid) / startMid) * 10_000;
  if (directionalImpactBps < 0) return null;
  const consumedAdded = direction === 1
    ? rangeSum(features.prefix.askAdded, start, index + 1)
    : rangeSum(features.prefix.bidAdded, start, index + 1);
  const consumedRemoved = direction === 1
    ? rangeSum(features.prefix.askRemoved, start, index + 1)
    : rangeSum(features.prefix.bidRemoved, start, index + 1);
  if (consumedRemoved <= 0) return null;
  const replenishmentRatio = consumedAdded / consumedRemoved;
  const regime = features.regime[index];
  const oiChange = features.openInterestChange[index];
  if (candidate.family === "SUBMINUTE_ABSORPTION_REVERSAL") {
    if (direction !== -regime || directionalImpactBps > candidate.maximumDirectionalPriceImpactBps ||
        replenishmentRatio < candidate.minimumConsumedSideReplenishmentRatio ||
        oiChange > candidate.maximumOpenInterestChangePct) return null;
    return { shockDirection: direction, entryDirection: regime, directionalImpactBps, replenishmentRatio, oiChange, start, end: index };
  }
  if (candidate.family === "SUBMINUTE_DEPLETION_CONTINUATION") {
    if (direction !== regime || directionalImpactBps < candidate.minimumDirectionalPriceImpactBps ||
        replenishmentRatio > candidate.maximumConsumedSideReplenishmentRatio ||
        oiChange < candidate.minimumOpenInterestChangePct) return null;
    return { shockDirection: direction, entryDirection: direction, directionalImpactBps, replenishmentRatio, oiChange, start, end: index };
  }
  throw new Error(`Unsupported subminute family: ${candidate.family}.`);
}

export function isConfirmation(candidate, features, setup, setupIndex, confirmationIndex) {
  const direction = setup.entryDirection;
  const imbalance = features.tradeImbalance[confirmationIndex];
  if (direction * imbalance < candidate.minimumConfirmationAbsoluteTakerImbalance) return false;
  const normalized = candidate.micropriceConfirmationMode === "CLOSE_SPREAD_NORMALIZED_CLAMPED";
  const microprice = normalized
    ? features.normalizedMicropriceEdge?.[confirmationIndex]
    : features.rows[confirmationIndex].meanMicropriceEdgeBps;
  const minimumMicroprice = normalized
    ? candidate.minimumNormalizedMicropriceEdge
    : candidate.minimumOpposingMicropriceEdgeBps ?? candidate.minimumAlignedMicropriceEdgeBps;
  if (!Number.isFinite(microprice) || !Number.isFinite(minimumMicroprice)) return false;
  if (direction * microprice < minimumMicroprice) return false;
  const priceMove = direction * (
    features.rows[confirmationIndex].closeMidPrice - features.rows[setupIndex].closeMidPrice
  );
  return priceMove > 0;
}

export function normalizeMicropriceEdge(row) {
  const midpoint = row.closeMidPrice;
  const spread = row.closeBestAsk - row.closeBestBid;
  if (!Number.isFinite(midpoint) || midpoint <= 0 || !Number.isFinite(spread) || spread <= 0 ||
      !Number.isFinite(row.meanMicropriceEdgeBps)) return null;
  const halfSpreadBps = (spread / midpoint) * 10_000 / 2;
  if (!Number.isFinite(halfSpreadBps) || halfSpreadBps <= 0) return null;
  return Math.max(-1, Math.min(1, row.meanMicropriceEdgeBps / halfSpreadBps));
}

export function simulateSubminuteTrade({
  features,
  setup,
  confirmationIndex,
  entryIndex,
  execution,
  equity,
  evaluationEndIndex,
}) {
  const rows = features.rows;
  const direction = setup.entryDirection;
  const priorBook = rows[confirmationIndex];
  const entryBook = rows[entryIndex];
  const rawEntry = direction === 1
    ? Math.max(priorBook.closeBestAsk, entryBook.closeBestAsk)
    : Math.min(priorBook.closeBestBid, entryBook.closeBestBid);
  const entryPrice = rawEntry * (1 + direction * execution.entrySlippageRate);
  const atr = features.atr[entryIndex];
  if (!Number.isFinite(atr) || atr <= 0 || !Number.isFinite(entryPrice) || entryPrice <= 0) return null;
  const stopDistance = Math.max(atr * execution.initialStopAtrMultiple, entryPrice * execution.minimumEffectiveStopPct);
  const stopPrice = entryPrice - direction * stopDistance;
  const targetPrice = entryPrice + direction * stopDistance * execution.targetR;
  const quantity = calculateQuantity({ equity, entryPrice, stopPrice, execution });
  if (quantity == null) return null;
  const plannedStopExit = stopPrice * (1 - direction * execution.exitSlippageRate);
  const plannedRisk = -netTradePnl({
    direction,
    quantity,
    entryPrice,
    exitPrice: plannedStopExit,
    entryFeeRate: execution.entryFeeRate,
    exitFeeRate: execution.exitFeeRate,
    fundingPnl: 0,
  });
  if (!Number.isFinite(plannedRisk) || plannedRisk <= 0) return null;
  const entryAt = rows[entryIndex].openedAt + FIVE_SECONDS_MILLIS;
  const expiryAt = entryAt + execution.maximumHoldingMinutes * ONE_MINUTE_MILLIS;
  const maximumExitIndex = Math.min(evaluationEndIndex - 1, lowerBound(rows, expiryAt));
  let minimumOpenPnl = -quantity * entryPrice * execution.entryFeeRate;
  let exit = null;
  for (let index = entryIndex + 1; index <= maximumExitIndex; index += 1) {
    const row = rows[index];
    if (row.tradeCount > 0) {
      const adversePrice = direction === 1 ? row.lowPrice : row.highPrice;
      const markedExit = adversePrice * (1 - direction * execution.exitSlippageRate);
      const marked = netTradePnl({
        direction,
        quantity,
        entryPrice,
        exitPrice: markedExit,
        entryFeeRate: execution.entryFeeRate,
        exitFeeRate: execution.exitFeeRate,
        fundingPnl: 0,
      });
      minimumOpenPnl = Math.min(minimumOpenPnl, marked);
      const gapLiquidationPrice = liquidationPrice(entryPrice, direction, execution.maximumLeverage, execution.liquidationBufferPct);
      const gapLiquidated = direction === 1 ? row.openPrice <= gapLiquidationPrice : row.openPrice >= gapLiquidationPrice;
      if (gapLiquidated) {
        exit = { index, price: gapLiquidationPrice, reason: "LIQUIDATION" };
        break;
      }
      const stopHit = direction === 1 ? row.lowPrice <= stopPrice : row.highPrice >= stopPrice;
      const targetHit = direction === 1 ? row.highPrice >= targetPrice : row.lowPrice <= targetPrice;
      if (stopHit) {
        exit = { index, price: stopPrice * (1 - direction * execution.exitSlippageRate), reason: "STOP" };
        break;
      }
      if (targetHit) {
        exit = { index, price: targetPrice * (1 - direction * execution.exitSlippageRate), reason: "TARGET" };
        break;
      }
    }
    if (row.openedAt + FIVE_SECONDS_MILLIS >= expiryAt) {
      const rawExit = direction === 1 ? row.closeBestBid : row.closeBestAsk;
      exit = { index, price: rawExit * (1 - direction * execution.exitSlippageRate), reason: "TIME" };
      break;
    }
  }
  if (exit == null) return null;
  const exitAt = rows[exit.index].openedAt + FIVE_SECONDS_MILLIS;
  const fundingPnl = fundingPnlBetween(features.funding, entryAt, exitAt, direction, quantity * entryPrice);
  const netPnl = exit.reason === "LIQUIDATION"
    ? -Math.min(equity, quantity * entryPrice / execution.maximumLeverage)
    : netTradePnl({
        direction,
        quantity,
        entryPrice,
        exitPrice: exit.price,
        entryFeeRate: execution.entryFeeRate,
        exitFeeRate: execution.exitFeeRate,
        fundingPnl,
      });
  return {
    direction,
    signalAt: rows[confirmationIndex].openedAt + FIVE_SECONDS_MILLIS,
    entryAt,
    exitAt,
    entryIndex,
    exitIndex: exit.index,
    entryPrice,
    stopPrice,
    targetPrice,
    exitPrice: exit.price,
    exitReason: exit.reason,
    quantity,
    notional: quantity * entryPrice,
    plannedRisk,
    netPnl,
    netR: netPnl / plannedRisk,
    fundingPnl,
    minimumOpenPnl,
    shockImpactBps: setup.directionalImpactBps,
    replenishmentRatio: setup.replenishmentRatio,
    openInterestChangePct: setup.oiChange,
  };
}

export function calculateQuantity({ equity, entryPrice, stopPrice, execution }) {
  const stopDistance = Math.abs(entryPrice - stopPrice);
  const estimatedLossPerBtc = stopDistance +
    entryPrice * execution.entryFeeRate +
    stopPrice * execution.exitFeeRate +
    stopPrice * execution.exitSlippageRate;
  const rawRiskQuantity = equity * execution.riskFractionPerTrade / estimatedLossPerBtc;
  const notionalQuantity = execution.maximumNotionalUsdt / entryPrice;
  const leverageQuantity = equity * execution.maximumLeverage / entryPrice;
  const rawQuantity = Math.min(rawRiskQuantity, notionalQuantity, leverageQuantity);
  const quantity = Math.floor((rawQuantity + 1e-12) / execution.quantityStepBtc) * execution.quantityStepBtc;
  return quantity + 1e-12 < execution.minimumQuantityBtc ? null : Number(quantity.toFixed(8));
}

function summarizeCandidate(protocol, candidate, state, blockResults) {
  const trades = state.trades;
  const wins = trades.filter((trade) => trade.netPnl > 0);
  const losses = trades.filter((trade) => trade.netPnl < 0);
  const grossProfit = sumValues(wins.map((trade) => trade.netPnl));
  const grossLoss = -sumValues(losses.map((trade) => trade.netPnl));
  const totalDays = blockResults.reduce((total, block) => total + block.evaluationDays, 0);
  const dailyNetR = dailyReturns(trades, protocol.acquisition.selectionBlocks);
  const bootstrap = movingBlockBootstrapMean(
    dailyNetR,
    protocol.statistics.bootstrapBlockDays,
    protocol.statistics.bootstrapSamples,
    `${protocol.statistics.randomSeed}|${candidate.id}`,
  );
  const summary = {
    candidateId: candidate.id,
    family: candidate.family,
    parameters: candidate,
    tradeCount: trades.length,
    longTrades: trades.filter((trade) => trade.direction === 1).length,
    shortTrades: trades.filter((trade) => trade.direction === -1).length,
    winRatePct: trades.length === 0 ? 0 : wins.length / trades.length * 100,
    netPnl: state.equity - protocol.executionContract.startingEquityUsdt,
    netReturnPct: ((state.equity / protocol.executionContract.startingEquityUsdt) - 1) * 100,
    compoundDailyReturnPct: totalDays === 0 ? 0 : (Math.pow(state.equity / protocol.executionContract.startingEquityUsdt, 1 / totalDays) - 1) * 100,
    meanNetR: mean(trades.map((trade) => trade.netR)),
    profitFactor: grossLoss === 0 ? null : grossProfit / grossLoss,
    profitFactorInfinite: grossLoss === 0 && grossProfit > 0,
    maxDrawdownPct: state.maxDrawdownPct,
    liquidationCount: trades.filter((trade) => trade.exitReason === "LIQUIDATION").length,
    winnerProfitConcentration: grossProfit === 0 ? 0 : Math.max(...wins.map((trade) => trade.netPnl)) / grossProfit,
    positiveQuarterCount: blockResults.filter((block) => block.netPnl > 0).length,
    bootstrapLowerMeanNetR: bootstrap.lower,
    bootstrapUpperMeanNetR: bootstrap.upper,
    blockResults,
    exitCounts: Object.fromEntries(Map.groupBy(trades, (trade) => trade.exitReason).entries().map(([reason, rows]) => [reason, rows.length])),
    trades,
  };
  summary.gate = selectionGate(protocol.selection2023, summary);
  return summary;
}

export function selectionGate(gate, summary) {
  const checks = {
    minimumTrades: summary.tradeCount >= gate.minimumTrades,
    minimumLongTrades: summary.longTrades >= gate.minimumLongTrades,
    minimumShortTrades: summary.shortTrades >= gate.minimumShortTrades,
    minimumPositiveQuarterCount: summary.positiveQuarterCount >= gate.minimumPositiveQuarterCount,
    minimumProfitFactor: summary.profitFactorInfinite === true ||
      (summary.profitFactor != null && summary.profitFactor >= gate.minimumProfitFactor),
    minimumMeanNetR: summary.meanNetR >= gate.minimumMeanNetR,
    minimumBootstrapLowerMeanNetR: summary.bootstrapLowerMeanNetR >= gate.minimumBootstrapLowerMeanNetR,
    maximumDrawdownPct: summary.maxDrawdownPct <= gate.maximumDrawdownPct,
    maximumLiquidationCount: summary.liquidationCount <= gate.maximumLiquidationCount,
    maximumWinnerProfitConcentration: summary.winnerProfitConcentration <= gate.maximumWinnerProfitConcentration,
  };
  return { passed: Object.values(checks).every(Boolean), checks };
}

export function rankAndSelectCandidates(results) {
  const ranked = [...results].sort((left, right) =>
    Number(right.gate.passed) - Number(left.gate.passed) ||
    right.bootstrapLowerMeanNetR - left.bootstrapLowerMeanNetR ||
    right.meanNetR - left.meanNetR ||
    numericProfitFactor(right.profitFactor, right.profitFactorInfinite) -
      numericProfitFactor(left.profitFactor, left.profitFactorInfinite) ||
    right.tradeCount - left.tradeCount ||
    left.candidateId.localeCompare(right.candidateId));
  const selected = [];
  for (const family of ["SUBMINUTE_ABSORPTION_REVERSAL", "SUBMINUTE_DEPLETION_CONTINUATION"]) {
    const candidate = ranked.find((row) => row.family === family && row.gate.passed);
    if (candidate != null) selected.push(candidate);
  }
  return { ranked, selected };
}

export function rollingMedianBefore(values, windowSize, minimumSize) {
  const sorted = [];
  const result = Array(values.length).fill(null);
  for (let index = 0; index < values.length; index += 1) {
    if (sorted.length >= minimumSize) result[index] = medianSorted(sorted);
    insertSorted(sorted, values[index]);
    if (sorted.length > windowSize) removeSorted(sorted, values[index - windowSize]);
  }
  return result;
}

export function buildM15RegimeTimeline(candles) {
  let ema20 = null;
  let ema50 = null;
  let previousEma20 = null;
  return candles.map((candle, index) => {
    ema20 = ema(ema20, candle.close, 20);
    ema50 = ema(ema50, candle.close, 50);
    let regime = 0;
    if (index >= 59 && previousEma20 != null) {
      if (candle.close > ema20 && ema20 > ema50 && ema20 > previousEma20) regime = 1;
      if (candle.close < ema20 && ema20 < ema50 && ema20 < previousEma20) regime = -1;
    }
    previousEma20 = ema20;
    return { availableAt: candle.openedAt + 15 * ONE_MINUTE_MILLIS, regime };
  });
}

export function buildAtrTimeline(candles, period) {
  const ranges = [];
  const timeline = [];
  let sum = 0;
  let previousClose = null;
  for (const candle of candles) {
    const trueRange = previousClose == null
      ? candle.high - candle.low
      : Math.max(candle.high - candle.low, Math.abs(candle.high - previousClose), Math.abs(candle.low - previousClose));
    ranges.push(trueRange);
    sum += trueRange;
    if (ranges.length > period) sum -= ranges.shift();
    timeline.push({
      availableAt: candle.openedAt + ONE_MINUTE_MILLIS,
      atr: ranges.length === period ? sum / period : null,
    });
    previousClose = candle.close;
  }
  return timeline;
}

export function movingBlockBootstrapMean(values, blockSize, samples, seedText) {
  if (values.length === 0) return { lower: 0, upper: 0 };
  const random = seededRandom(seedText);
  const estimates = [];
  for (let sample = 0; sample < samples; sample += 1) {
    const replay = [];
    while (replay.length < values.length) {
      const start = Math.floor(random() * values.length);
      for (let offset = 0; offset < blockSize && replay.length < values.length; offset += 1) {
        replay.push(values[(start + offset) % values.length]);
      }
    }
    estimates.push(mean(replay));
  }
  estimates.sort((left, right) => left - right);
  return {
    lower: quantileSorted(estimates, 0.025),
    upper: quantileSorted(estimates, 0.975),
  };
}

function pointInTimeValues(rows, timeline, value, fallback) {
  const result = [];
  let pointer = -1;
  for (const row of rows) {
    const decisionAt = row.openedAt + FIVE_SECONDS_MILLIS;
    while (pointer + 1 < timeline.length && timeline[pointer + 1].availableAt <= decisionAt) pointer += 1;
    result.push(pointer < 0 ? fallback : value(timeline[pointer]));
  }
  return result;
}

function pointInTimeOpenInterestChange(rows, observations, lookbackMinutes, maximumStalenessMinutes) {
  const result = [];
  let current = -1;
  let previous = -1;
  const lookback = lookbackMinutes * ONE_MINUTE_MILLIS;
  const maximumStaleness = maximumStalenessMinutes * ONE_MINUTE_MILLIS;
  for (const row of rows) {
    const decisionAt = row.openedAt + FIVE_SECONDS_MILLIS;
    while (current + 1 < observations.length && observations[current + 1].timestamp <= decisionAt) current += 1;
    const previousAt = decisionAt - lookback;
    while (previous + 1 < observations.length && observations[previous + 1].timestamp <= previousAt) previous += 1;
    if (current < 0 || previous < 0 || decisionAt - observations[current].timestamp > maximumStaleness ||
        previousAt - observations[previous].timestamp > maximumStaleness || observations[previous].value <= 0) {
      result.push(null);
    } else {
      result.push((observations[current].value - observations[previous].value) / observations[previous].value);
    }
  }
  return result;
}

function buildPrefixes(rows) {
  const fields = {
    buyNotional: (row) => row.buyNotional,
    sellNotional: (row) => row.sellNotional,
    bidAdded: (row) => row.bidAddedTop5Notional,
    bidRemoved: (row) => row.bidRemovedTop5Notional,
    askAdded: (row) => row.askAddedTop5Notional,
    askRemoved: (row) => row.askRemovedTop5Notional,
  };
  return Object.fromEntries(Object.entries(fields).map(([name, getter]) => {
    const prefix = [0];
    for (const row of rows) prefix.push(prefix.at(-1) + getter(row));
    return [name, prefix];
  }));
}

function loadCandles(db, symbol, timeframe, startAt, endAt) {
  return db.prepare(`
    SELECT opened_at, open, high, low, close, volume FROM marketCandles
    WHERE symbol=? AND timeframe=? AND opened_at>=? AND opened_at<? ORDER BY opened_at
  `).all(symbol, timeframe, startAt, endAt).map((row) => ({
    openedAt: Date.parse(row.opened_at),
    open: Number(row.open),
    high: Number(row.high),
    low: Number(row.low),
    close: Number(row.close),
    volume: Number(row.volume),
  }));
}

function parseSliceRow(row) {
  return {
    openedAt: Date.parse(row.opened_at),
    messageCount: Number(row.message_count),
    carriedForward: Number(row.carried_forward) !== 0,
    closeBestBid: Number(row.close_best_bid),
    closeBestAsk: Number(row.close_best_ask),
    openMidPrice: Number(row.open_mid_price),
    highMidPrice: Number(row.high_mid_price),
    lowMidPrice: Number(row.low_mid_price),
    closeMidPrice: Number(row.close_mid_price),
    meanTop5Imbalance: Number(row.mean_top5_imbalance),
    meanMicropriceEdgeBps: Number(row.mean_microprice_edge_bps),
    bidAddedTop5Notional: Number(row.bid_added_top5_notional),
    bidRemovedTop5Notional: Number(row.bid_removed_top5_notional),
    askAddedTop5Notional: Number(row.ask_added_top5_notional),
    askRemovedTop5Notional: Number(row.ask_removed_top5_notional),
    tradeCount: Number(row.trade_count),
    buyNotional: Number(row.buy_notional),
    sellNotional: Number(row.sell_notional),
    buyCount: Number(row.buy_count),
    sellCount: Number(row.sell_count),
    openPrice: row.open_price == null ? null : Number(row.open_price),
    highPrice: row.high_price == null ? null : Number(row.high_price),
    lowPrice: row.low_price == null ? null : Number(row.low_price),
    closePrice: row.close_price == null ? null : Number(row.close_price),
  };
}

function assertContiguousSlices(rows, startDate, endDate) {
  const expectedStart = Date.parse(`${startDate}T00:00:00Z`);
  const expectedCount = (Date.parse(`${addUtcDays(endDate, 1)}T00:00:00Z`) - expectedStart) / FIVE_SECONDS_MILLIS;
  if (rows.length !== expectedCount) throw new Error(`Subminute block expected ${expectedCount} joined slices, got ${rows.length}.`);
  for (let index = 0; index < rows.length; index += 1) {
    if (rows[index].openedAt !== expectedStart + index * FIVE_SECONDS_MILLIS) {
      throw new Error(`Subminute block is discontinuous at index=${index}.`);
    }
  }
}

function fundingPnlBetween(funding, entryAt, exitAt, direction, notional) {
  return funding
    .filter((row) => row.timestamp > entryAt && row.timestamp <= exitAt)
    .reduce((total, row) => total - direction * notional * row.rate, 0);
}

function netTradePnl({ direction, quantity, entryPrice, exitPrice, entryFeeRate, exitFeeRate, fundingPnl }) {
  const gross = direction * quantity * (exitPrice - entryPrice);
  const fees = quantity * entryPrice * entryFeeRate + quantity * exitPrice * exitFeeRate;
  return gross - fees + fundingPnl;
}

function liquidationPrice(entryPrice, direction, leverage, bufferPct) {
  const distance = Math.max(0, (1 / leverage) - bufferPct / 100);
  return entryPrice * (1 - direction * distance);
}

function dailyReturns(trades, blocks) {
  const byDate = new Map();
  for (const block of blocks) {
    for (let timestamp = Date.parse(block.replayStartAt); timestamp < Date.parse(block.replayEndAt); timestamp += ONE_DAY_MILLIS) {
      byDate.set(new Date(timestamp).toISOString().slice(0, 10), 0);
    }
  }
  for (const trade of trades) {
    const date = new Date(trade.entryAt).toISOString().slice(0, 10);
    byDate.set(date, (byDate.get(date) ?? 0) + trade.netR);
  }
  return [...byDate.entries()].sort(([left], [right]) => left.localeCompare(right)).map(([, value]) => value);
}

function lowerBound(rows, timestamp) {
  let low = 0;
  let high = rows.length;
  while (low < high) {
    const middle = Math.floor((low + high) / 2);
    if (rows[middle].openedAt < timestamp) low = middle + 1;
    else high = middle;
  }
  return low;
}

function rangeSum(prefix, start, endExclusive) {
  return prefix[endExclusive] - prefix[start];
}

function ema(previous, value, period) {
  if (previous == null) return value;
  const alpha = 2 / (period + 1);
  return value * alpha + previous * (1 - alpha);
}

function insertSorted(values, value) {
  let low = 0;
  let high = values.length;
  while (low < high) {
    const middle = Math.floor((low + high) / 2);
    if (values[middle] <= value) low = middle + 1;
    else high = middle;
  }
  values.splice(low, 0, value);
}

function removeSorted(values, value) {
  let low = 0;
  let high = values.length;
  while (low < high) {
    const middle = Math.floor((low + high) / 2);
    if (values[middle] < value) low = middle + 1;
    else high = middle;
  }
  if (values[low] !== value) throw new Error(`Rolling median value ${value} is missing.`);
  values.splice(low, 1);
}

function medianSorted(values) {
  const middle = Math.floor(values.length / 2);
  return values.length % 2 === 0 ? (values[middle - 1] + values[middle]) / 2 : values[middle];
}

function mean(values) {
  return values.length === 0 ? 0 : sumValues(values) / values.length;
}

function sumValues(values) {
  return values.reduce((total, value) => total + value, 0);
}

function quantileSorted(values, probability) {
  const index = (values.length - 1) * probability;
  const lower = Math.floor(index);
  const upper = Math.ceil(index);
  if (lower === upper) return values[lower];
  return values[lower] + (values[upper] - values[lower]) * (index - lower);
}

function seededRandom(seedText) {
  const seed = createHash("sha256").update(seedText).digest();
  let state = seed.readUInt32LE(0) || 1;
  return () => {
    state ^= state << 13;
    state ^= state >>> 17;
    state ^= state << 5;
    return (state >>> 0) / 0x1_0000_0000;
  };
}

function numericProfitFactor(value, infinite) {
  if (infinite === true) return Number.MAX_VALUE;
  return value ?? 0;
}

function addUtcDays(date, days) {
  const value = new Date(`${date}T00:00:00Z`);
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
}
