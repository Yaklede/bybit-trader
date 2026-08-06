import { createHash } from "node:crypto";

const MINUTE_MILLIS = 60_000;
const DAY_MILLIS = 86_400_000;
const PREMIUM_INTERVAL_MILLIS = 15 * MINUTE_MILLIS;

export function loadFundingResearchInputs(db, protocol) {
  const source = protocol.sourceData;
  const evaluationStart = protocol.evidenceSchedule.developmentBlocks[0].startAt;
  const funding = loadRowsInBatches(
    db,
    `SELECT timestamp,funding_rate FROM fundingRates
     WHERE symbol=? AND timestamp>=? AND timestamp<? AND timestamp>?
     ORDER BY timestamp LIMIT ?`,
    [source.symbol, source.developmentStart, source.developmentEndExclusive],
    "timestamp",
  ).map((row, index) => ({ index, timestamp: Date.parse(row.timestamp), rate: finite(row.funding_rate) }));
  const premium = loadRowsInBatches(
    db,
    `SELECT opened_at,close FROM premiumIndexBars
     WHERE symbol=? AND timeframe='M15' AND opened_at>=? AND opened_at<? AND opened_at>?
     ORDER BY opened_at LIMIT ?`,
    [source.symbol, source.developmentStart, source.developmentEndExclusive],
    "opened_at",
  ).map((row) => ({ openedAt: Date.parse(row.opened_at), close: finite(row.close) }));
  const m5 = loadRowsInBatches(
    db,
    `SELECT opened_at,open FROM marketCandles
     WHERE symbol=? AND timeframe='M5' AND opened_at>=? AND opened_at<? AND opened_at>?
     ORDER BY opened_at LIMIT ?`,
    [source.symbol, evaluationStart, source.developmentEndExclusive],
    "opened_at",
  ).map((row) => ({ openedAt: Date.parse(row.opened_at), open: positive(row.open) }));
  const m15 = loadRowsInBatches(
    db,
    `SELECT opened_at,high,low,close FROM marketCandles
     WHERE symbol=? AND timeframe='M15' AND opened_at>=? AND opened_at<? AND opened_at>?
     ORDER BY opened_at LIMIT ?`,
    [source.symbol, source.developmentStart, source.developmentEndExclusive],
    "opened_at",
  ).map((row) => ({
    openedAt: Date.parse(row.opened_at),
    high: positive(row.high),
    low: positive(row.low),
    close: positive(row.close),
  }));
  assertStrictTimeline(funding, "timestamp", 8 * 60 * MINUTE_MILLIS, "funding");
  assertStrictTimeline(m5, "openedAt", 5 * MINUTE_MILLIS, "M5");
  assertStrictTimeline(m15, "openedAt", 15 * MINUTE_MILLIS, "M15");
  return {
    funding,
    premium,
    m5,
    m15,
    events: prepareFundingEvents({ funding, premium, m5, m15 }, protocol),
  };
}

export function prepareFundingEvents(input, protocol) {
  const lookbacks = [...new Set(protocol.candidateDefinition.grid.fundingLookbackSettlements)];
  const m5ByOpenedAt = new Map(input.m5.map((row) => [row.openedAt, row]));
  const atrTimeline = buildAtrTimeline(input.m15, protocol.candidateDefinition.fixed.initialStopAtrPeriod);
  const blocks = protocol.evidenceSchedule.developmentBlocks.map((block) => ({
    ...block,
    startMillis: Date.parse(block.startAt),
    endMillis: Date.parse(block.endAt),
  }));
  const events = [];
  let premiumIndex = -1;
  let atrIndex = -1;
  for (let index = 0; index < input.funding.length; index += 1) {
    const settlement = input.funding[index];
    while (premiumIndex + 1 < input.premium.length &&
        input.premium[premiumIndex + 1].openedAt + PREMIUM_INTERVAL_MILLIS <= settlement.timestamp) {
      premiumIndex += 1;
    }
    const entryAt = settlement.timestamp + protocol.candidateDefinition.fixed.entryDelayMinutes * MINUTE_MILLIS;
    while (atrIndex + 1 < atrTimeline.length && atrTimeline[atrIndex + 1].availableAt <= entryAt) atrIndex += 1;
    const latestPremium = input.premium[premiumIndex] ?? null;
    const block = blocks.find((value) => settlement.timestamp >= value.startMillis && settlement.timestamp < value.endMillis);
    if (block == null) continue;
    const scores = {};
    for (const lookback of lookbacks) {
      const prior = input.funding.slice(Math.max(0, index - lookback), index).map((row) => row.rate);
      scores[lookback] = prior.length === lookback ? robustFundingScore(prior, settlement.rate) : null;
    }
    events.push({
      fundingIndex: index,
      blockId: block.id,
      era: block.era,
      settlementAt: settlement.timestamp,
      fundingRate: settlement.rate,
      robustScores: scores,
      premiumOpenedAt: latestPremium?.openedAt ?? null,
      premiumClose: latestPremium?.close ?? null,
      premiumStalenessMillis: latestPremium == null
        ? null
        : settlement.timestamp - (latestPremium.openedAt + PREMIUM_INTERVAL_MILLIS),
      entryAt,
      rawEntryPrice: m5ByOpenedAt.get(entryAt)?.open ?? null,
      atr: atrIndex < 0 ? null : atrTimeline[atrIndex].atr,
    });
  }
  return events;
}

export function robustFundingScore(priorValues, currentValue) {
  if (priorValues.length === 0 || priorValues.some((value) => !Number.isFinite(value)) ||
      !Number.isFinite(currentValue)) return null;
  const center = median(priorValues);
  const mad = median(priorValues.map((value) => Math.abs(value - center)));
  const scale = mad * 1.4826;
  return scale > 0 ? (currentValue - center) / scale : null;
}

export function qualifyFundingEvent(event, candidate) {
  const score = event.robustScores[candidate.fundingLookbackSettlements];
  if (!Number.isFinite(score) || Math.abs(score) < candidate.minimumAbsoluteRobustZ) return "ROBUST_Z";
  if (Math.abs(event.fundingRate) < candidate.minimumAbsoluteFundingRate) return "ABSOLUTE_FUNDING";
  if (!Number.isFinite(event.premiumClose) || !Number.isFinite(event.premiumStalenessMillis) ||
      event.premiumStalenessMillis < 0 || event.premiumStalenessMillis > 30 * MINUTE_MILLIS) {
    return "PREMIUM_UNAVAILABLE";
  }
  if (Math.sign(event.premiumClose) === 0 || Math.sign(event.premiumClose) !== Math.sign(event.fundingRate)) {
    return "PREMIUM_SIGN";
  }
  if (Math.abs(event.premiumClose) < candidate.minimumAbsolutePremium) return "ABSOLUTE_PREMIUM";
  if (!Number.isFinite(event.rawEntryPrice) || event.rawEntryPrice <= 0) return "ENTRY_CANDLE_MISSING";
  if (!Number.isFinite(event.atr) || event.atr <= 0) return "ATR_UNAVAILABLE";
  return null;
}

export function buildFundingTradePlan(event, candidate, execution, equity) {
  const direction = event.fundingRate > 0 ? -1 : event.fundingRate < 0 ? 1 : 0;
  if (direction === 0 || !Number.isFinite(equity) || equity <= 0) return null;
  const entryPrice = event.rawEntryPrice * (1 + direction * execution.entrySlippageRate);
  const stopDistance = Math.max(
    event.atr * candidate.initialStopAtrMultiple,
    entryPrice * candidate.minimumStopPct,
  );
  const stopPrice = entryPrice - direction * stopDistance;
  const targetPrice = entryPrice + direction * stopDistance * candidate.targetR;
  if (!(stopPrice > 0) || !(targetPrice > 0)) return null;
  const liquidationDistance = Math.max(0, (1 / execution.maximumLeverage) - execution.liquidationBufferPct / 100);
  const liquidationPrice = entryPrice * (1 - direction * liquidationDistance);
  const stopIsProtected = direction === 1 ? stopPrice > liquidationPrice : stopPrice < liquidationPrice;
  if (!stopIsProtected) return null;
  const stopFillPrice = adverseExitFill(stopPrice, direction, execution.exitSlippageRate);
  const riskPerBtc = -(direction * (stopFillPrice - entryPrice) -
    entryPrice * execution.entryFeeRate - stopFillPrice * execution.exitFeeRate);
  if (!Number.isFinite(riskPerBtc) || riskPerBtc <= 0) return null;
  const quantity = calculateFundingQuantity({ equity, entryPrice, riskPerBtc, execution });
  if (quantity == null) return null;
  return {
    direction,
    entryAt: event.entryAt,
    rawEntryPrice: event.rawEntryPrice,
    entryPrice,
    stopPrice,
    targetPrice,
    liquidationPrice,
    riskPerBtc,
    quantity,
    plannedRisk: quantity * riskPerBtc,
    maximumExitAt: event.entryAt + candidate.maximumHoldingHours * 60 * MINUTE_MILLIS,
  };
}

export function calculateFundingQuantity({ equity, entryPrice, riskPerBtc, execution }) {
  if (![equity, entryPrice, riskPerBtc].every((value) => Number.isFinite(value) && value > 0)) return null;
  const riskQuantity = equity * execution.riskFractionPerTrade / riskPerBtc;
  const notionalQuantity = execution.maximumNotionalUsdt / entryPrice;
  const leverageQuantity = equity * execution.maximumLeverage / entryPrice;
  const raw = Math.min(riskQuantity, notionalQuantity, leverageQuantity);
  const quantity = Math.floor((raw + 1e-12) / execution.quantityStepBtc) * execution.quantityStepBtc;
  if (quantity + 1e-12 < execution.minimumQuantityBtc) return null;
  return Number(quantity.toFixed(8));
}

export function simulateFundingPath(db, symbol, plan, funding, execution) {
  const endExclusive = plan.maximumExitAt + MINUTE_MILLIS;
  const rows = db.prepare(`
    SELECT opened_at,open,high,low,close FROM marketCandles
    WHERE symbol=? AND timeframe='M1' AND opened_at>=? AND opened_at<? ORDER BY opened_at
  `).all(symbol, instant(plan.entryAt), instant(endExclusive)).map(parseM1);
  if (rows.length === 0 || rows[0].openedAt !== plan.entryAt) {
    throw new Error(`M1 entry path is missing at ${instant(plan.entryAt)}.`);
  }
  if (!Number.isFinite(plan.rawEntryPrice) ||
      Math.abs(rows[0].open - plan.rawEntryPrice) > Math.max(1e-9, plan.rawEntryPrice * 1e-12)) {
    throw new Error(`M1 and M5 entry prices differ at ${instant(plan.entryAt)}.`);
  }
  const fundingByTimestamp = new Map(funding
    .filter((row) => row.timestamp > plan.entryAt && row.timestamp <= plan.maximumExitAt)
    .map((row) => [row.timestamp, row.rate]));
  const markRangesPerBtc = [{ favorable: -plan.entryPrice * execution.entryFeeRate, adverse: -plan.entryPrice * execution.entryFeeRate }];
  let fundingPnlPerBtc = 0;
  let fundingSettlementCount = 0;
  let scheduledFundingExitAt = null;
  for (let index = 0; index < rows.length; index += 1) {
    const row = rows[index];
    if (index > 0 && row.openedAt !== rows[index - 1].openedAt + MINUTE_MILLIS) {
      throw new Error(`M1 path is discontinuous at ${instant(row.openedAt)}.`);
    }
    if (scheduledFundingExitAt != null && row.openedAt >= scheduledFundingExitAt) {
      return closePath(plan, row, "FUNDING_CHANGE", row.open, row.openedAt, fundingPnlPerBtc,
        fundingSettlementCount, markRangesPerBtc, execution);
    }
    if (row.openedAt >= plan.maximumExitAt) {
      return closePath(plan, row, "TIME", row.open, row.openedAt, fundingPnlPerBtc,
        fundingSettlementCount, markRangesPerBtc, execution);
    }
    const exit = resolveFundingExit(plan, row);
    if (exit != null) {
      return closePath(
        plan,
        row,
        exit.reason,
        exit.price,
        row.openedAt + MINUTE_MILLIS,
        fundingPnlPerBtc,
        fundingSettlementCount,
        markRangesPerBtc,
        execution,
      );
    }
    markRangesPerBtc.push(markRange(plan, row, fundingPnlPerBtc, execution));
    const settlementAt = row.openedAt + MINUTE_MILLIS;
    const rate = fundingByTimestamp.get(settlementAt);
    if (rate != null) {
      fundingPnlPerBtc += -plan.direction * row.close * rate;
      fundingSettlementCount += 1;
      const markedAfterFunding = netPerBtc(plan, row.close, fundingPnlPerBtc, execution);
      markRangesPerBtc.push({ favorable: markedAfterFunding, adverse: markedAfterFunding });
      if (-plan.direction * rate <= 0) scheduledFundingExitAt = settlementAt + 5 * MINUTE_MILLIS;
    }
  }
  throw new Error(`M1 path ended before an exit at ${instant(plan.maximumExitAt)}.`);
}

export function evaluateFundingCandidates(db, protocol, candidates, input) {
  return candidates.map((candidate) => evaluateFundingCandidate(db, protocol, candidate, input));
}

export function evaluateFundingCandidate(db, protocol, candidate, input) {
  const execution = protocol.executionContract;
  const state = {
    equity: execution.startingEquityUsdt,
    peakEquity: execution.startingEquityUsdt,
    maxDrawdownPct: 0,
    unavailableThrough: -Infinity,
    dailyEntries: new Map(),
    trades: [],
    noTradeReasons: new Map(),
  };
  const pathCache = new Map();
  for (const event of input.events) {
    if (event.settlementAt <= state.unavailableThrough) {
      increment(state.noTradeReasons, "POSITION_OVERLAP");
      continue;
    }
    const rejection = qualifyFundingEvent(event, candidate);
    if (rejection != null) {
      increment(state.noTradeReasons, rejection);
      continue;
    }
    if (event.entryAt + candidate.maximumHoldingHours * 60 * MINUTE_MILLIS >=
        Date.parse(protocol.sourceData.developmentEndExclusive)) {
      increment(state.noTradeReasons, "INCOMPLETE_HORIZON");
      continue;
    }
    const entryDay = instant(event.entryAt).slice(0, 10);
    if ((state.dailyEntries.get(entryDay) ?? 0) >= execution.maximumTradesPerUtcDay) {
      increment(state.noTradeReasons, "DAILY_LIMIT");
      continue;
    }
    const plan = buildFundingTradePlan(event, candidate, execution, state.equity);
    if (plan == null) {
      increment(state.noTradeReasons, "CAPITAL_OR_TRADE_PLAN");
      continue;
    }
    const cacheKey = `${event.fundingIndex}|${candidate.maximumHoldingHours}`;
    let path = pathCache.get(cacheKey);
    if (path == null) {
      path = simulateFundingPath(db, protocol.sourceData.symbol, plan, input.funding, execution);
      pathCache.set(cacheKey, path);
    }
    const trade = materializeTrade(event, candidate, plan, path, state.equity, execution);
    applyMarkedDrawdown(state, trade);
    state.equity = Math.max(0, state.equity + trade.netPnl);
    state.peakEquity = Math.max(state.peakEquity, state.equity);
    state.maxDrawdownPct = Math.max(state.maxDrawdownPct, drawdownPct(state.peakEquity, state.equity));
    trade.equityAfter = state.equity;
    state.trades.push(trade);
    state.unavailableThrough = trade.exitAt;
    state.dailyEntries.set(entryDay, (state.dailyEntries.get(entryDay) ?? 0) + 1);
    if (state.equity <= 0) break;
  }
  return summarizeFundingCandidate(protocol, candidate, state);
}

export function fundingDevelopmentGate(gate, summary) {
  const checks = {
    minimumTrades: summary.tradeCount >= gate.minimumTrades,
    minimumLongTrades: summary.longTrades >= gate.minimumLongTrades,
    minimumShortTrades: summary.shortTrades >= gate.minimumShortTrades,
    minimumPositiveBlockCount: summary.positiveBlockCount >= gate.minimumPositiveBlockCount,
    minimumProfitFactor: summary.profitFactorInfinite ||
      (summary.profitFactor != null && summary.profitFactor >= gate.minimumProfitFactor),
    minimumMeanNetR: summary.meanNetR >= gate.minimumMeanNetR,
    minimumBootstrapLowerMeanNetR: summary.bootstrapLowerMeanNetR >= gate.minimumBootstrapLowerMeanNetR,
    maximumDrawdownPct: summary.maxDrawdownPct <= gate.maximumDrawdownPct,
    maximumLiquidationCount: summary.liquidationCount <= gate.maximumLiquidationCount,
    maximumWinnerProfitConcentration:
      summary.winnerProfitConcentration <= gate.maximumWinnerProfitConcentration,
  };
  return { passed: Object.values(checks).every(Boolean), checks };
}

export function rankFundingCandidates(results) {
  const ranked = [...results].sort((left, right) =>
    Number(right.gate.passed) - Number(left.gate.passed) ||
    right.bootstrapLowerMeanNetR - left.bootstrapLowerMeanNetR ||
    right.meanNetR - left.meanNetR ||
    numericProfitFactor(right) - numericProfitFactor(left) ||
    right.tradeCount - left.tradeCount ||
    left.candidateId.localeCompare(right.candidateId));
  return { ranked, selected: ranked.filter((row) => row.gate.passed).slice(0, 1) };
}

export function movingBlockBootstrapDailyMean(values, options) {
  if (values.length === 0) return { lower: 0, upper: 0 };
  const blockSize = Math.min(options.blockDays, values.length);
  const circularBlockSums = values.map((_, start) => {
    let sum = 0;
    for (let offset = 0; offset < blockSize; offset += 1) sum += values[(start + offset) % values.length];
    return sum;
  });
  const random = seededRandom(options.seed);
  const estimates = [];
  for (let sample = 0; sample < options.samples; sample += 1) {
    let replayed = 0;
    let sum = 0;
    while (replayed + blockSize <= values.length) {
      sum += circularBlockSums[Math.floor(random() * values.length)];
      replayed += blockSize;
    }
    if (replayed < values.length) {
      const start = Math.floor(random() * values.length);
      for (let offset = 0; replayed < values.length; offset += 1, replayed += 1) {
        sum += values[(start + offset) % values.length];
      }
    }
    estimates.push(sum / values.length);
  }
  estimates.sort((left, right) => left - right);
  const tail = (1 - options.confidence) / 2;
  return { lower: quantile(estimates, tail), upper: quantile(estimates, 1 - tail) };
}

function summarizeFundingCandidate(protocol, candidate, state) {
  const trades = state.trades;
  const wins = trades.filter((trade) => trade.netPnl > 0);
  const losses = trades.filter((trade) => trade.netPnl < 0);
  const grossProfit = sum(wins.map((trade) => trade.netPnl));
  const grossLoss = -sum(losses.map((trade) => trade.netPnl));
  const blocks = protocol.evidenceSchedule.developmentBlocks.map((block) => {
    const rows = trades.filter((trade) => trade.blockId === block.id);
    return {
      blockId: block.id,
      era: block.era,
      tradeCount: rows.length,
      longTrades: rows.filter((trade) => trade.direction === 1).length,
      shortTrades: rows.filter((trade) => trade.direction === -1).length,
      netPnl: round(sum(rows.map((trade) => trade.netPnl))),
      meanNetR: round(mean(rows.map((trade) => trade.netR))),
    };
  });
  const dailyNetR = dailyNetRSeries(trades, protocol.evidenceSchedule.developmentBlocks);
  const bootstrap = movingBlockBootstrapDailyMean(dailyNetR, {
    blockDays: protocol.statistics.bootstrapBlockDays,
    samples: protocol.statistics.bootstrapSamples,
    confidence: protocol.statistics.bootstrapConfidence,
    seed: `${protocol.statistics.randomSeed}|${candidate.id}`,
  });
  const observedDays = dailyNetR.length;
  const summary = {
    candidateId: candidate.id,
    family: candidate.family,
    parameters: candidate,
    tradeCount: trades.length,
    longTrades: trades.filter((trade) => trade.direction === 1).length,
    shortTrades: trades.filter((trade) => trade.direction === -1).length,
    winRatePct: round(trades.length === 0 ? 0 : wins.length / trades.length * 100),
    endingEquityUsdt: round(state.equity),
    netPnlUsdt: round(state.equity - protocol.executionContract.startingEquityUsdt),
    netReturnPct: round((state.equity / protocol.executionContract.startingEquityUsdt - 1) * 100),
    compoundDailyReturnPct: round(state.equity > 0
      ? (Math.pow(state.equity / protocol.executionContract.startingEquityUsdt, 1 / observedDays) - 1) * 100
      : -100),
    meanNetR: round(mean(trades.map((trade) => trade.netR))),
    profitFactor: grossLoss > 0 ? round(grossProfit / grossLoss) : null,
    profitFactorInfinite: grossLoss === 0 && grossProfit > 0,
    maxDrawdownPct: round(state.maxDrawdownPct),
    liquidationCount: trades.filter((trade) => trade.exitReason === "LIQUIDATION").length,
    winnerProfitConcentration: round(grossProfit > 0
      ? Math.max(...wins.map((trade) => trade.netPnl)) / grossProfit
      : 0),
    positiveBlockCount: blocks.filter((block) => block.netPnl > 0).length,
    bootstrapLowerMeanNetR: round(bootstrap.lower),
    bootstrapUpperMeanNetR: round(bootstrap.upper),
    grossPricePnlUsdt: round(sum(trades.map((trade) => trade.grossPricePnl))),
    fundingPnlUsdt: round(sum(trades.map((trade) => trade.fundingPnl))),
    feeCostUsdt: round(sum(trades.map((trade) => trade.feeCost))),
    blockResults: blocks,
    exitCounts: countBy(trades, (trade) => trade.exitReason),
    noTradeReasons: Object.fromEntries([...state.noTradeReasons.entries()].sort()),
    trades,
  };
  summary.gate = fundingDevelopmentGate(protocol.developmentGate, summary);
  return summary;
}

function materializeTrade(event, candidate, plan, path, equityBefore, execution) {
  const liquidation = path.exitReason === "LIQUIDATION";
  const grossPricePnl = liquidation
    ? -Math.min(equityBefore, plan.quantity * plan.entryPrice / execution.maximumLeverage)
    : plan.quantity * path.grossPricePnlPerBtc;
  const fundingPnl = plan.quantity * path.fundingPnlPerBtc;
  const feeCost = plan.quantity * path.feeCostPerBtc;
  const netPnl = Math.max(-equityBefore, grossPricePnl + fundingPnl - feeCost);
  return {
    candidateId: candidate.id,
    blockId: event.blockId,
    era: event.era,
    direction: plan.direction,
    signalAt: event.settlementAt,
    entryAt: plan.entryAt,
    exitAt: path.exitAt,
    entryPrice: round(plan.entryPrice),
    exitPrice: round(path.exitPrice),
    stopPrice: round(plan.stopPrice),
    targetPrice: round(plan.targetPrice),
    liquidationPrice: round(plan.liquidationPrice),
    quantity: plan.quantity,
    notionalUsdt: round(plan.quantity * plan.entryPrice),
    plannedRisk: round(plan.plannedRisk),
    grossPricePnl: round(grossPricePnl),
    fundingPnl: round(fundingPnl),
    feeCost: round(feeCost),
    netPnl: round(netPnl),
    netR: round(netPnl / plan.plannedRisk),
    exitReason: path.exitReason,
    fundingSettlementCount: path.fundingSettlementCount,
    markRanges: path.markRangesPerBtc.map((range) => ({
      favorable: range.favorable * plan.quantity,
      adverse: range.adverse * plan.quantity,
    })),
  };
}

function applyMarkedDrawdown(state, trade) {
  const equityBefore = state.equity;
  for (const range of trade.markRanges) {
    const favorableEquity = Math.max(0, equityBefore + range.favorable);
    state.peakEquity = Math.max(state.peakEquity, favorableEquity);
    const adverseEquity = Math.max(0, equityBefore + range.adverse);
    state.maxDrawdownPct = Math.max(state.maxDrawdownPct, drawdownPct(state.peakEquity, adverseEquity));
  }
  delete trade.markRanges;
}

function closePath(
  plan,
  row,
  reason,
  triggerPrice,
  exitAt,
  fundingPnlPerBtc,
  fundingSettlementCount,
  markRangesPerBtc,
  execution,
) {
  if (reason === "LIQUIDATION") {
    const feeCostPerBtc = plan.entryPrice * execution.entryFeeRate;
    const net = -plan.entryPrice / execution.maximumLeverage + fundingPnlPerBtc - feeCostPerBtc;
    markRangesPerBtc.push({
      favorable: net,
      adverse: net,
    });
    return {
      exitReason: reason,
      exitAt,
      exitPrice: triggerPrice,
      grossPricePnlPerBtc: -plan.entryPrice / execution.maximumLeverage,
      fundingPnlPerBtc,
      feeCostPerBtc,
      fundingSettlementCount,
      markRangesPerBtc,
    };
  }
  const exitPrice = adverseExitFill(triggerPrice, plan.direction, execution.exitSlippageRate);
  const grossPricePnlPerBtc = plan.direction * (exitPrice - plan.entryPrice);
  const feeCostPerBtc = plan.entryPrice * execution.entryFeeRate + exitPrice * execution.exitFeeRate;
  const net = grossPricePnlPerBtc + fundingPnlPerBtc - feeCostPerBtc;
  markRangesPerBtc.push({ favorable: net, adverse: net });
  return {
    exitReason: reason,
    exitAt,
    exitPrice,
    grossPricePnlPerBtc,
    fundingPnlPerBtc,
    feeCostPerBtc,
    fundingSettlementCount,
    markRangesPerBtc,
  };
}

function resolveFundingExit(plan, row) {
  const long = plan.direction === 1;
  if (long ? row.open <= plan.liquidationPrice : row.open >= plan.liquidationPrice) {
    return { price: plan.liquidationPrice, reason: "LIQUIDATION" };
  }
  if (long ? row.open <= plan.stopPrice : row.open >= plan.stopPrice) {
    return { price: row.open, reason: "STOP" };
  }
  if (long ? row.low <= plan.stopPrice : row.high >= plan.stopPrice) {
    return { price: plan.stopPrice, reason: "STOP" };
  }
  if (long ? row.low <= plan.liquidationPrice : row.high >= plan.liquidationPrice) {
    return { price: plan.liquidationPrice, reason: "LIQUIDATION" };
  }
  if (long ? row.high >= plan.targetPrice : row.low <= plan.targetPrice) {
    return { price: plan.targetPrice, reason: "TARGET" };
  }
  return null;
}

function markRange(plan, row, fundingPnlPerBtc, execution) {
  const favorablePrice = plan.direction === 1 ? row.high : row.low;
  const adversePrice = plan.direction === 1 ? row.low : row.high;
  return {
    favorable: netPerBtc(plan, favorablePrice, fundingPnlPerBtc, execution),
    adverse: netPerBtc(plan, adversePrice, fundingPnlPerBtc, execution),
  };
}

function netPerBtc(plan, rawExitPrice, fundingPnlPerBtc, execution) {
  const exitPrice = adverseExitFill(rawExitPrice, plan.direction, execution.exitSlippageRate);
  return plan.direction * (exitPrice - plan.entryPrice) + fundingPnlPerBtc -
    plan.entryPrice * execution.entryFeeRate - exitPrice * execution.exitFeeRate;
}

function buildAtrTimeline(candles, period) {
  const ranges = [];
  const result = [];
  let rolling = 0;
  let previousClose = null;
  for (const candle of candles) {
    const trueRange = previousClose == null
      ? candle.high - candle.low
      : Math.max(candle.high - candle.low, Math.abs(candle.high - previousClose), Math.abs(candle.low - previousClose));
    ranges.push(trueRange);
    rolling += trueRange;
    if (ranges.length > period) rolling -= ranges.shift();
    result.push({
      availableAt: candle.openedAt + PREMIUM_INTERVAL_MILLIS,
      atr: ranges.length === period ? rolling / period : null,
    });
    previousClose = candle.close;
  }
  return result;
}

function loadRowsInBatches(db, query, parameters, cursorField) {
  const statement = db.prepare(query);
  const result = [];
  let cursor = "";
  while (true) {
    const rows = statement.all(...parameters, cursor, 10_000);
    if (rows.length === 0) break;
    result.push(...rows);
    const next = rows.at(-1)[cursorField];
    if (next <= cursor) throw new Error(`${cursorField} input pagination did not advance.`);
    cursor = next;
  }
  return result;
}

function dailyNetRSeries(trades, blocks) {
  const byDay = new Map();
  for (let timestamp = Date.parse(blocks[0].startAt); timestamp < Date.parse(blocks.at(-1).endAt); timestamp += DAY_MILLIS) {
    byDay.set(instant(timestamp).slice(0, 10), 0);
  }
  for (const trade of trades) {
    const day = instant(trade.exitAt).slice(0, 10);
    if (byDay.has(day)) byDay.set(day, byDay.get(day) + trade.netR);
  }
  return [...byDay.values()];
}

function adverseExitFill(price, direction, slippageRate) {
  return price * (1 - direction * slippageRate);
}

function parseM1(row) {
  return {
    openedAt: Date.parse(row.opened_at),
    open: positive(row.open),
    high: positive(row.high),
    low: positive(row.low),
    close: positive(row.close),
  };
}

function assertStrictTimeline(rows, field, interval, label) {
  for (let index = 1; index < rows.length; index += 1) {
    if (rows[index][field] !== rows[index - 1][field] + interval) {
      throw new Error(`${label} timeline is discontinuous at index ${index}.`);
    }
  }
}

function median(values) {
  const sorted = [...values].sort((left, right) => left - right);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0 ? (sorted[middle - 1] + sorted[middle]) / 2 : sorted[middle];
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

function quantile(sorted, probability) {
  const index = (sorted.length - 1) * probability;
  const lower = Math.floor(index);
  const upper = Math.ceil(index);
  if (lower === upper) return sorted[lower];
  return sorted[lower] + (sorted[upper] - sorted[lower]) * (index - lower);
}

function countBy(rows, key) {
  const counts = new Map();
  for (const row of rows) increment(counts, key(row));
  return Object.fromEntries([...counts.entries()].sort());
}

function increment(map, key) {
  map.set(key, (map.get(key) ?? 0) + 1);
}

function numericProfitFactor(summary) {
  return summary.profitFactorInfinite ? Number.MAX_VALUE : summary.profitFactor ?? 0;
}

function drawdownPct(peak, equity) {
  return peak > 0 ? (peak - equity) / peak * 100 : 100;
}

function sum(values) {
  return values.reduce((total, value) => total + value, 0);
}

function mean(values) {
  return values.length === 0 ? 0 : sum(values) / values.length;
}

function finite(value) {
  const result = Number(value);
  if (!Number.isFinite(result)) throw new Error(`Expected finite number, got ${value}.`);
  return result;
}

function positive(value) {
  const result = finite(value);
  if (result <= 0) throw new Error(`Expected positive number, got ${value}.`);
  return result;
}

function round(value) {
  return Number(Number(value).toFixed(8));
}

function instant(timestamp) {
  return new Date(timestamp).toISOString().replace(".000Z", "Z");
}
