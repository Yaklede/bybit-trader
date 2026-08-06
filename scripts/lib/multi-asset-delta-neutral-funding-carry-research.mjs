import { createHash } from "node:crypto";

const M5_MILLIS = 5 * 60 * 1_000;
const DAY_MILLIS = 24 * 60 * 60 * 1_000;

export function calculateAssetPairSizing({
  equityUsdt,
  existingCommittedCapitalUsdt,
  spotReferencePrice,
  perpetualReferencePrice,
  execution,
  instrument,
  maximumConcurrentPairs,
  costMultiplier = 1,
}) {
  const pairMatchedFraction = execution.maximumTotalMatchedNotionalFractionOfEquity /
    maximumConcurrentPairs;
  const maximumMatchedNotional = equityUsdt * pairMatchedFraction;
  const maximumCommittedCapital = equityUsdt * (1 - execution.minimumUncommittedEquityFraction);
  const availableCommittedCapital = Math.max(0, maximumCommittedCapital - existingCommittedCapitalUsdt);
  const quantityStep = instrument.perpetual.quantityStepBase;
  let quantity = floorStep(
    maximumMatchedNotional / Math.max(spotReferencePrice, perpetualReferencePrice),
    quantityStep,
  );
  while (quantity >= instrument.perpetual.minimumOrderQuantityBase - 1e-12) {
    const sizing = assetPairSizing(
      quantity,
      spotReferencePrice,
      perpetualReferencePrice,
      execution,
      instrument,
      costMultiplier,
    );
    if (sizing.committedCapitalUsdt <= availableCommittedCapital + 1e-9 &&
        sizing.perpetualNotionalUsdt >= instrument.perpetual.minimumNotionalUsdt &&
        sizing.spotOrderAmountUsdt >= instrument.spot.minimumOrderAmountUsdt &&
        sizing.netHedgeMismatchBase <= instrument.maximumNetHedgeMismatchBase + 1e-12) {
      return sizing;
    }
    quantity = round12(quantity - quantityStep);
  }
  return null;
}

export function assetPairSizing(
  quantity,
  spotReferencePrice,
  perpetualReferencePrice,
  execution,
  instrument,
  costMultiplier = 1,
) {
  const spotFeeRate = execution.spotTakerFeeRate * costMultiplier;
  const perpetualFeeRate = execution.perpetualTakerFeeRate * costMultiplier;
  const spotEntryFillPrice = spotReferencePrice *
    (1 + execution.spotSlippageRatePerLeg * costMultiplier);
  const perpetualEntryFillPrice = perpetualReferencePrice *
    (1 - execution.perpetualSlippageRatePerLeg * costMultiplier);
  const grossSpotQuantityBase = ceilStep(
    quantity / (1 - spotFeeRate),
    instrument.spot.basePrecision,
  );
  const netSpotQuantityBase = grossSpotQuantityBase * (1 - spotFeeRate);
  const netHedgeMismatchBase = Math.abs(netSpotQuantityBase - quantity);
  const spotOrderAmountUsdt = grossSpotQuantityBase * spotEntryFillPrice;
  const perpetualNotionalUsdt = quantity * perpetualEntryFillPrice;
  const perpetualEntryFeeUsdt = perpetualNotionalUsdt * perpetualFeeRate;
  const perpetualInitialMarginUsdt = perpetualNotionalUsdt / execution.perpetualLeverage;
  return {
    targetNetQuantityBase: quantity,
    grossSpotQuantityBase,
    netSpotQuantityBase,
    netHedgeMismatchBase,
    spotEntryFillPrice,
    perpetualEntryFillPrice,
    spotOrderAmountUsdt,
    perpetualNotionalUsdt,
    perpetualEntryFeeUsdt,
    perpetualInitialMarginUsdt,
    matchedNotionalUsdt: Math.max(spotOrderAmountUsdt, perpetualNotionalUsdt),
    committedCapitalUsdt: spotOrderAmountUsdt + perpetualInitialMarginUsdt + perpetualEntryFeeUsdt,
  };
}

export function evaluateMultiAssetFundingCarrySignal({
  candidate,
  fundingRates,
  fundingIndex,
  decisionFrame,
  execution,
}) {
  const streakLength = candidate.minimumPositiveFundingStreak;
  if (fundingIndex + 1 < streakLength) return null;
  const streak = fundingRates.slice(fundingIndex + 1 - streakLength, fundingIndex + 1);
  if (streak.some((row) => row.rate <= 0)) return null;
  const trailingMedianFundingRate = median(streak.map((row) => row.rate));
  if (trailingMedianFundingRate + 1e-15 < candidate.minimumTrailingMedianFundingRate) return null;
  const entryBasisPct = decisionFrame.perpetual.close / decisionFrame.spot.close - 1;
  const markIndexPremiumPct = decisionFrame.mark.close / decisionFrame.index.close - 1;
  if (entryBasisPct + 1e-15 < candidate.minimumEntryBasisPct ||
      entryBasisPct > candidate.maximumEntryBasisPct + 1e-15 ||
      Math.abs(markIndexPremiumPct) > candidate.maximumAbsoluteMarkIndexPremiumPct + 1e-15) {
    return null;
  }
  const projectedNetCarryScore = trailingMedianFundingRate *
    candidate.projectedCarryHorizonSettlements + Math.max(entryBasisPct, 0) -
    execution.baseRoundTripCostRateOnMatchedNotional;
  if (projectedNetCarryScore <= candidate.minimumProjectedNetCarryScore + 1e-15) return null;
  return {
    trailingMedianFundingRate,
    entryBasisPct,
    markIndexPremiumPct,
    projectedNetCarryScore,
  };
}

export function rankEligibleSignals(signals) {
  return [...signals].sort((left, right) => {
    if (left.signal.projectedNetCarryScore !== right.signal.projectedNetCarryScore) {
      return right.signal.projectedNetCarryScore - left.signal.projectedNetCarryScore;
    }
    return left.symbol.localeCompare(right.symbol);
  });
}

export function simulateMultiAssetFundingCarryCandidate({
  candidate,
  framesBySymbol,
  fundingRatesBySymbol,
  protocol,
}) {
  const execution = protocol.executionContract;
  const symbols = protocol.sourceData.symbols;
  validateAlignedFrames(symbols, framesBySymbol);
  const referenceFrames = framesBySymbol[symbols[0]];
  const frameIndexByTimestamp = new Map(referenceFrames.map((frame, index) => [frame.timestamp, index]));
  const fundingMaps = {};
  for (const symbol of symbols) {
    const fundingRates = fundingRatesBySymbol[symbol] ?? [];
    fundingMaps[symbol] = new Map(
      fundingRates.map((row, index) => [row.timestamp, { ...row, index }]),
    );
  }

  const initialEquity = execution.startingEquityUsdt;
  let realizedEquity = initialEquity;
  let currentEquity = initialEquity;
  let highWaterEquity = initialEquity;
  let maximumDrawdownPct = 0;
  let liquidationCount = 0;
  let noTradeSignalCount = 0;
  let signalCount = 0;
  let maximumConcurrentPositionCount = 0;
  let maximumTotalMatchedNotionalFraction = 0;
  const positions = new Map();
  const pendingEntries = new Map();
  const pendingExits = new Map();
  const cooldownUntil = new Map(symbols.map((symbol) => [symbol, Number.NEGATIVE_INFINITY]));
  const maximumNetHedgeMismatchBySymbol = Object.fromEntries(symbols.map((symbol) => [symbol, 0]));
  const trades = [];
  const activeDays = new Set();
  const dailyEquity = [];

  for (let frameIndex = 0; frameIndex < referenceFrames.length; frameIndex += 1) {
    const timestamp = referenceFrames[frameIndex].timestamp;
    const frames = Object.fromEntries(symbols.map((symbol) => [symbol, framesBySymbol[symbol][frameIndex]]));

    for (const symbol of symbols) {
      const funding = fundingMaps[symbol].get(timestamp);
      const position = positions.get(symbol);
      if (funding == null || position == null) continue;
      const settlementMark = frameIndex === 0
        ? frames[symbol].mark.open
        : framesBySymbol[symbol][frameIndex - 1].mark.close;
      position.fundingPnlUsdt += position.quantityBase * settlementMark * funding.rate;
      position.capturedFundingSettlements += 1;
      position.nonPositiveFundingCount = funding.rate <= 0
        ? position.nonPositiveFundingCount + 1
        : 0;
      if (position.nonPositiveFundingCount >= candidate.exitConsecutiveNonPositiveFundingCount) {
        pendingExits.set(symbol, {
          timestamp: timestamp + candidate.entryDelayMinutes * 60 * 1_000,
          reason: "FUNDING_NO_LONGER_POSITIVE",
        });
      }
    }

    const eligibleSignals = [];
    for (const symbol of symbols) {
      const funding = fundingMaps[symbol].get(timestamp);
      if (funding == null || positions.has(symbol) || pendingEntries.has(symbol) ||
          timestamp < cooldownUntil.get(symbol)) continue;
      const decisionIndex = frameIndexByTimestamp.get(timestamp - M5_MILLIS);
      if (decisionIndex == null) continue;
      const signal = evaluateMultiAssetFundingCarrySignal({
        candidate,
        fundingRates: fundingRatesBySymbol[symbol],
        fundingIndex: funding.index,
        decisionFrame: framesBySymbol[symbol][decisionIndex],
        execution,
      });
      if (signal != null) eligibleSignals.push({ symbol, fundingTimestamp: timestamp, signal });
    }
    signalCount += eligibleSignals.length;
    let availableSlots = candidate.maximumConcurrentPairs - positions.size - pendingEntries.size;
    for (const eligible of rankEligibleSignals(eligibleSignals)) {
      if (availableSlots <= 0) break;
      pendingEntries.set(eligible.symbol, {
        timestamp: timestamp + candidate.entryDelayMinutes * 60 * 1_000,
        fundingTimestamp: eligible.fundingTimestamp,
        signal: eligible.signal,
      });
      availableSlots -= 1;
    }

    for (const symbol of symbols) {
      const pending = pendingEntries.get(symbol);
      if (pending?.timestamp !== timestamp || positions.has(symbol)) continue;
      const entryEquity = portfolioEquity(realizedEquity, positions, frames, "open");
      const existingCommitted = [...positions.values()]
        .reduce((sum, position) => sum + position.committedCapitalUsdt, 0);
      const sizing = calculateAssetPairSizing({
        equityUsdt: entryEquity,
        existingCommittedCapitalUsdt: existingCommitted,
        spotReferencePrice: frames[symbol].spot.open,
        perpetualReferencePrice: frames[symbol].perpetual.open,
        execution,
        instrument: protocol.observedInstrumentRules[symbol],
        maximumConcurrentPairs: candidate.maximumConcurrentPairs,
      });
      if (sizing == null) {
        noTradeSignalCount += 1;
      } else {
        positions.set(symbol, openPosition({
          candidate,
          symbol,
          frame: frames[symbol],
          frameIndex,
          entryEquity,
          existingCommitted,
          sizing,
          pending,
        }));
        maximumNetHedgeMismatchBySymbol[symbol] = Math.max(
          maximumNetHedgeMismatchBySymbol[symbol],
          sizing.netHedgeMismatchBase,
        );
        maximumConcurrentPositionCount = Math.max(maximumConcurrentPositionCount, positions.size);
        const totalMatched = [...positions.values()]
          .reduce((sum, position) => sum + position.matchedNotionalUsdt, 0);
        maximumTotalMatchedNotionalFraction = Math.max(
          maximumTotalMatchedNotionalFraction,
          totalMatched / entryEquity,
        );
      }
      pendingEntries.delete(symbol);
    }

    for (const symbol of symbols) {
      const position = positions.get(symbol);
      if (position == null) continue;
      activeDays.add(utcDay(timestamp));
      const frame = frames[symbol];
      const liquidationBoundary = position.perpetualEntryFillPrice *
        execution.conservativeLiquidationPriceMultiple;
      const maximumHoldingTimestamp = position.entryTimestamp + candidate.maximumHoldingDays * DAY_MILLIS;
      const pendingExit = pendingExits.get(symbol);
      let exit = null;
      if (frame.mark.high >= liquidationBoundary) {
        liquidationCount += 1;
        exit = {
          reason: "LIQUIDATION",
          spotReferencePrice: frame.spot.low,
          perpetualReferencePrice: liquidationBoundary,
        };
      } else if (pendingExit?.timestamp === timestamp) {
        exit = {
          reason: pendingExit.reason,
          spotReferencePrice: frame.spot.open,
          perpetualReferencePrice: frame.perpetual.open,
        };
      } else if (timestamp >= maximumHoldingTimestamp) {
        exit = {
          reason: "MAXIMUM_HOLDING_TIME",
          spotReferencePrice: frame.spot.open,
          perpetualReferencePrice: frame.perpetual.open,
        };
      }
      if (exit != null) {
        const trade = closePosition(position, frame, frameIndex, exit, execution);
        trades.push(trade);
        realizedEquity += trade.netPnlUsdt;
        cooldownUntil.set(symbol, timestamp + candidate.reentryCooldownHours * 60 * 60 * 1_000);
        positions.delete(symbol);
        pendingExits.delete(symbol);
      } else {
        const closedBasisPct = frame.perpetual.close / frame.spot.close - 1;
        if (closedBasisPct - position.entryBasisPct >= candidate.basisDivergenceStopPctFromEntry) {
          pendingExits.set(symbol, {
            timestamp: timestamp + M5_MILLIS,
            reason: "BASIS_DIVERGENCE_STOP",
          });
        }
      }
    }

    currentEquity = portfolioEquity(realizedEquity, positions, frames, "close");
    highWaterEquity = Math.max(highWaterEquity, currentEquity);
    if (highWaterEquity > 0) {
      maximumDrawdownPct = Math.max(
        maximumDrawdownPct,
        (highWaterEquity - currentEquity) / highWaterEquity * 100,
      );
    }
    const nextFrame = referenceFrames[frameIndex + 1];
    if (nextFrame == null || utcDay(nextFrame.timestamp) !== utcDay(timestamp)) {
      dailyEquity.push({ day: utcDay(timestamp), timestamp, equityUsdt: currentEquity });
    }
  }

  const finalFrameIndex = referenceFrames.length - 1;
  if (finalFrameIndex >= 0) {
    for (const [symbol, position] of [...positions.entries()]) {
      const frame = framesBySymbol[symbol][finalFrameIndex];
      const trade = closePosition(position, frame, finalFrameIndex, {
        reason: "END_OF_DEVELOPMENT_DATA",
        spotReferencePrice: frame.spot.close,
        perpetualReferencePrice: frame.perpetual.close,
      }, execution);
      trades.push(trade);
      realizedEquity += trade.netPnlUsdt;
      positions.delete(symbol);
    }
    currentEquity = realizedEquity;
    highWaterEquity = Math.max(highWaterEquity, currentEquity);
    if (highWaterEquity > 0) {
      maximumDrawdownPct = Math.max(
        maximumDrawdownPct,
        (highWaterEquity - currentEquity) / highWaterEquity * 100,
      );
    }
    if (dailyEquity.length > 0) dailyEquity.at(-1).equityUsdt = realizedEquity;
  }

  return summarizeSimulation({
    candidate,
    protocol,
    framesBySymbol,
    trades,
    dailyEquity,
    activeDays,
    initialEquity,
    realizedEquity,
    maximumDrawdownPct,
    liquidationCount,
    signalCount,
    noTradeSignalCount,
    maximumConcurrentPositionCount,
    maximumTotalMatchedNotionalFraction,
    maximumNetHedgeMismatchBySymbol,
  });
}

export function evaluateMultiAssetDevelopmentGate(metrics, gate) {
  const hedgeChecks = Object.entries(gate.maximumNetHedgeMismatchBySymbol).map(
    ([symbol, limit]) => (metrics.maximumNetHedgeMismatchBySymbol[symbol] ?? Number.POSITIVE_INFINITY) <= limit,
  );
  const checks = {
    minimumClosedPositions: metrics.tradeCount >= gate.minimumClosedPositions,
    minimumActiveCalendarDays: metrics.activeCalendarDays >= gate.minimumActiveCalendarDays,
    minimumCapturedFundingSettlements:
      metrics.capturedFundingSettlements >= gate.minimumCapturedFundingSettlements,
    minimumTradedAssetCount: metrics.tradedAssetCount >= gate.minimumTradedAssetCount,
    minimumPositiveAssetCount: metrics.positiveAssetCount >= gate.minimumPositiveAssetCount,
    minimumPositiveBlockCount: metrics.positiveBlockCount >= gate.minimumPositiveBlockCount,
    totalBlockCount: metrics.totalBlockCount === gate.totalBlockCount,
    minimumNetReturnPct: metrics.netReturnPct > gate.minimumNetReturnPct,
    minimumProfitFactor: metrics.profitFactor >= gate.minimumProfitFactor,
    minimumMeanDailyReturnPct: metrics.meanDailyReturnPct > gate.minimumMeanDailyReturnPct,
    minimumBootstrapLowerMeanDailyReturnPct:
      metrics.bootstrapLowerMeanDailyReturnPct > gate.minimumBootstrapLowerMeanDailyReturnPct,
    maximumDrawdownPct: metrics.maximumDrawdownPct <= gate.maximumDrawdownPct,
    maximumLiquidationCount: metrics.liquidationCount <= gate.maximumLiquidationCount,
    maximumPositivePositionProfitConcentration:
      metrics.positivePositionProfitConcentration <= gate.maximumPositivePositionProfitConcentration,
    maximumPositiveAssetProfitConcentration:
      metrics.positiveAssetProfitConcentration <= gate.maximumPositiveAssetProfitConcentration,
    maximumNetHedgeMismatchBySymbol: hedgeChecks.every(Boolean),
    costStressMinimumNetReturnPct: metrics.costStressNetReturnPct > gate.costStressMinimumNetReturnPct,
    secondLegDelayStressMinimumNetReturnPct:
      metrics.secondLegDelayStressNetReturnPct > gate.secondLegDelayStressMinimumNetReturnPct,
  };
  return {
    passed: Object.values(checks).every(Boolean),
    checks,
    failedChecks: Object.entries(checks).filter(([, passed]) => !passed).map(([name]) => name),
    minimumGateMargin: round8(minimumGateMargin(metrics, gate)),
  };
}

export function movingBlockBootstrapMean(values, options) {
  if (values.length === 0) {
    return { lower: Number.NEGATIVE_INFINITY, upper: Number.POSITIVE_INFINITY };
  }
  const random = seededRandom(options.seed);
  const means = new Array(options.samples);
  for (let sample = 0; sample < options.samples; sample += 1) {
    let sum = 0;
    let count = 0;
    while (count < values.length) {
      const start = Math.floor(random() * values.length);
      for (let offset = 0; offset < options.blockLength && count < values.length; offset += 1) {
        sum += values[(start + offset) % values.length];
        count += 1;
      }
    }
    means[sample] = sum / values.length;
  }
  means.sort((left, right) => left - right);
  const alpha = (1 - options.confidence) / 2;
  return {
    lower: percentile(means, alpha),
    upper: percentile(means, 1 - alpha),
  };
}

function validateAlignedFrames(symbols, framesBySymbol) {
  const reference = framesBySymbol[symbols[0]];
  if (!Array.isArray(reference) || reference.length === 0) throw new Error("Reference frames are empty.");
  for (const symbol of symbols) {
    const frames = framesBySymbol[symbol];
    if (!Array.isArray(frames) || frames.length !== reference.length) {
      throw new Error(`${symbol} frames do not match the portfolio timeline.`);
    }
    for (let index = 0; index < frames.length; index += 1) {
      if (frames[index].timestamp !== reference[index].timestamp) {
        throw new Error(`${symbol} frame timeline diverges at index ${index}.`);
      }
    }
  }
}

function openPosition({ candidate, symbol, frame, frameIndex, entryEquity, existingCommitted, sizing, pending }) {
  return {
    candidateId: candidate.id,
    symbol,
    entryTimestamp: frame.timestamp,
    entryFrameIndex: frameIndex,
    signalFundingTimestamp: pending.fundingTimestamp,
    signal: pending.signal,
    entryPortfolioEquityUsdt: entryEquity,
    existingCommittedCapitalAtEntryUsdt: existingCommitted,
    quantityBase: sizing.targetNetQuantityBase,
    grossSpotQuantityBase: sizing.grossSpotQuantityBase,
    netSpotQuantityBase: sizing.netSpotQuantityBase,
    netHedgeMismatchBase: sizing.netHedgeMismatchBase,
    spotEntryReferencePrice: frame.spot.open,
    perpetualEntryReferencePrice: frame.perpetual.open,
    spotEntryFillPrice: sizing.spotEntryFillPrice,
    perpetualEntryFillPrice: sizing.perpetualEntryFillPrice,
    spotEntryCostUsdt: sizing.spotOrderAmountUsdt,
    perpetualEntryFeeUsdt: sizing.perpetualEntryFeeUsdt,
    committedCapitalUsdt: sizing.committedCapitalUsdt,
    matchedNotionalUsdt: sizing.matchedNotionalUsdt,
    entryBasisPct: frame.perpetual.open / frame.spot.open - 1,
    fundingPnlUsdt: 0,
    capturedFundingSettlements: 0,
    nonPositiveFundingCount: 0,
  };
}

function portfolioEquity(realizedEquity, positions, frames, pricePoint) {
  let equity = realizedEquity;
  for (const [symbol, position] of positions) {
    equity += markPositionPnl(position, frames[symbol], pricePoint);
  }
  return equity;
}

function markPositionPnl(position, frame, pricePoint) {
  const spotPrice = frame.spot[pricePoint];
  const markPrice = frame.mark[pricePoint];
  const spotPnl = position.netSpotQuantityBase * spotPrice - position.spotEntryCostUsdt;
  const perpetualPnl = position.quantityBase * (position.perpetualEntryFillPrice - markPrice);
  return spotPnl + perpetualPnl + position.fundingPnlUsdt - position.perpetualEntryFeeUsdt;
}

function closePosition(position, frame, frameIndex, exit, execution) {
  const spotExitFillPrice = exit.spotReferencePrice * (1 - execution.spotSlippageRatePerLeg);
  const perpetualExitFillPrice = exit.perpetualReferencePrice *
    (1 + execution.perpetualSlippageRatePerLeg);
  const spotExitGrossProceeds = position.netSpotQuantityBase * spotExitFillPrice;
  const spotExitFeeUsdt = spotExitGrossProceeds * execution.spotTakerFeeRate;
  const spotExitNetProceeds = spotExitGrossProceeds - spotExitFeeUsdt;
  const perpetualExitFeeUsdt = position.quantityBase * perpetualExitFillPrice *
    execution.perpetualTakerFeeRate;
  const spotPnl = spotExitNetProceeds - position.spotEntryCostUsdt;
  const perpetualPnl = position.quantityBase *
    (position.perpetualEntryFillPrice - perpetualExitFillPrice);
  const netPnlUsdt = spotPnl + perpetualPnl + position.fundingPnlUsdt -
    position.perpetualEntryFeeUsdt - perpetualExitFeeUsdt;
  const spotEntryFeeValue = (position.grossSpotQuantityBase - position.netSpotQuantityBase) *
    position.spotEntryReferencePrice;
  const spotExitFeeValue = position.netSpotQuantityBase * exit.spotReferencePrice *
    execution.spotTakerFeeRate;
  const perpetualEntryFeeValue = position.quantityBase * position.perpetualEntryReferencePrice *
    execution.perpetualTakerFeeRate;
  const perpetualExitFeeValue = position.quantityBase * exit.perpetualReferencePrice *
    execution.perpetualTakerFeeRate;
  const feeCostUsdt = spotEntryFeeValue + spotExitFeeValue +
    perpetualEntryFeeValue + perpetualExitFeeValue;
  const rawPricePnl = position.netSpotQuantityBase *
    (exit.spotReferencePrice - position.spotEntryReferencePrice) +
    position.quantityBase * (position.perpetualEntryReferencePrice - exit.perpetualReferencePrice);
  const slippageCostUsdt = Math.max(
    0,
    rawPricePnl + position.fundingPnlUsdt - feeCostUsdt - netPnlUsdt,
  );
  return {
    candidateId: position.candidateId,
    symbol: position.symbol,
    entryTimestamp: instantString(position.entryTimestamp),
    exitTimestamp: instantString(frame.timestamp),
    entryFrameIndex: position.entryFrameIndex,
    exitFrameIndex: frameIndex,
    exitReason: exit.reason,
    entryPortfolioEquityUsdt: round8(position.entryPortfolioEquityUsdt),
    existingCommittedCapitalAtEntryUsdt: round8(position.existingCommittedCapitalAtEntryUsdt),
    committedCapitalUsdt: round8(position.committedCapitalUsdt),
    matchedNotionalUsdt: round8(position.matchedNotionalUsdt),
    quantityBase: round12(position.quantityBase),
    grossSpotQuantityBase: round12(position.grossSpotQuantityBase),
    netSpotQuantityBase: round12(position.netSpotQuantityBase),
    netHedgeMismatchBase: round12(position.netHedgeMismatchBase),
    spotEntryReferencePrice: position.spotEntryReferencePrice,
    perpetualEntryReferencePrice: position.perpetualEntryReferencePrice,
    spotExitReferencePrice: exit.spotReferencePrice,
    perpetualExitReferencePrice: exit.perpetualReferencePrice,
    spotEntryFillPrice: round8(position.spotEntryFillPrice),
    perpetualEntryFillPrice: round8(position.perpetualEntryFillPrice),
    spotExitFillPrice: round8(spotExitFillPrice),
    perpetualExitFillPrice: round8(perpetualExitFillPrice),
    entryBasisPct: round8(position.entryBasisPct),
    holdingHours: round8((frame.timestamp - position.entryTimestamp) / (60 * 60 * 1_000)),
    capturedFundingSettlements: position.capturedFundingSettlements,
    fundingPnlUsdt: round8(position.fundingPnlUsdt),
    pricePnlUsdt: round8(rawPricePnl),
    feeCostUsdt: round8(feeCostUsdt),
    slippageCostUsdt: round8(slippageCostUsdt),
    netPnlUsdt: round8(netPnlUsdt),
    returnOnEntryEquityPct: round8(netPnlUsdt / position.entryPortfolioEquityUsdt * 100),
  };
}

function summarizeSimulation(context) {
  const {
    candidate,
    protocol,
    framesBySymbol,
    trades,
    dailyEquity,
    activeDays,
    initialEquity,
    realizedEquity,
    maximumDrawdownPct,
    liquidationCount,
    signalCount,
    noTradeSignalCount,
    maximumConcurrentPositionCount,
    maximumTotalMatchedNotionalFraction,
    maximumNetHedgeMismatchBySymbol,
  } = context;
  const dailyReturns = dailyEquity.map((row, index) => {
    const previous = index === 0 ? initialEquity : dailyEquity[index - 1].equityUsdt;
    return previous === 0 ? 0 : row.equityUsdt / previous - 1;
  });
  const bootstrap = movingBlockBootstrapMean(dailyReturns, {
    samples: protocol.statistics.bootstrapSamples,
    confidence: protocol.statistics.bootstrapConfidence,
    blockLength: protocol.statistics.bootstrapBlockDays,
    seed: `${protocol.statistics.randomSeed}|${candidate.id}`,
  });
  const costStress = repriceTrades(trades, framesBySymbol, protocol, candidate, {
    costMultiplier: protocol.executionContract.costStressMultiplier,
    secondLegDelayBars: 0,
    enforceAtomicity: false,
  });
  const delayStress = repriceTrades(trades, framesBySymbol, protocol, candidate, {
    costMultiplier: 1,
    secondLegDelayBars: protocol.executionContract.secondLegDelayStressBars,
    enforceAtomicity: true,
  });
  const winning = trades.filter((trade) => trade.netPnlUsdt > 0);
  const losing = trades.filter((trade) => trade.netPnlUsdt < 0);
  const grossProfit = winning.reduce((sum, trade) => sum + trade.netPnlUsdt, 0);
  const grossLoss = -losing.reduce((sum, trade) => sum + trade.netPnlUsdt, 0);
  const assetNetPnl = Object.fromEntries(protocol.sourceData.symbols.map((symbol) => [
    symbol,
    round8(trades.filter((trade) => trade.symbol === symbol)
      .reduce((sum, trade) => sum + trade.netPnlUsdt, 0)),
  ]));
  const positiveAssetProfits = Object.values(assetNetPnl).filter((value) => value > 0);
  const positiveAssetGross = positiveAssetProfits.reduce((sum, value) => sum + value, 0);
  const positivePositionProfitConcentration = grossProfit <= 0
    ? 1
    : Math.max(...winning.map((trade) => trade.netPnlUsdt), 0) / grossProfit;
  const positiveAssetProfitConcentration = positiveAssetGross <= 0
    ? 1
    : Math.max(...positiveAssetProfits, 0) / positiveAssetGross;
  const blocks = protocol.evidenceSchedule.developmentBlocks;
  const blockReturns = calculateBlockReturns(dailyEquity, blocks, initialEquity);
  const endingEquityUsdt = realizedEquity;
  return {
    candidateId: candidate.id,
    tradeCount: trades.length,
    signalCount,
    noTradeSignalCount,
    activeCalendarDays: activeDays.size,
    capturedFundingSettlements: trades.reduce(
      (sum, trade) => sum + trade.capturedFundingSettlements,
      0,
    ),
    tradedAssetCount: new Set(trades.map((trade) => trade.symbol)).size,
    positiveAssetCount: positiveAssetProfits.length,
    startingEquityUsdt: round8(initialEquity),
    endingEquityUsdt: round8(endingEquityUsdt),
    netReturnPct: round8((endingEquityUsdt / initialEquity - 1) * 100),
    compoundDailyReturnPct: round8(
      (Math.pow(endingEquityUsdt / initialEquity, 1 / Math.max(1, dailyReturns.length)) - 1) * 100,
    ),
    meanDailyReturnPct: round8(mean(dailyReturns) * 100),
    bootstrapLowerMeanDailyReturnPct: round8(bootstrap.lower * 100),
    bootstrapUpperMeanDailyReturnPct: round8(bootstrap.upper * 100),
    profitFactor: round8(grossLoss === 0 ? (grossProfit > 0 ? 999 : 0) : grossProfit / grossLoss),
    winRatePct: round8(trades.length === 0 ? 0 : winning.length / trades.length * 100),
    maximumDrawdownPct: round8(maximumDrawdownPct),
    liquidationCount,
    maximumConcurrentPositionCount,
    maximumTotalMatchedNotionalFraction: round8(maximumTotalMatchedNotionalFraction),
    maximumNetHedgeMismatchBySymbol: Object.fromEntries(
      Object.entries(maximumNetHedgeMismatchBySymbol).map(([symbol, value]) => [symbol, round12(value)]),
    ),
    positivePositionProfitConcentration: round8(positivePositionProfitConcentration),
    positiveAssetProfitConcentration: round8(positiveAssetProfitConcentration),
    fundingPnlUsdt: round8(trades.reduce((sum, trade) => sum + trade.fundingPnlUsdt, 0)),
    pricePnlUsdt: round8(trades.reduce((sum, trade) => sum + trade.pricePnlUsdt, 0)),
    feeCostUsdt: round8(trades.reduce((sum, trade) => sum + trade.feeCostUsdt, 0)),
    slippageCostUsdt: round8(trades.reduce((sum, trade) => sum + trade.slippageCostUsdt, 0)),
    costStressNetReturnPct: round8(costStress.netPnlUsdt / initialEquity * 100),
    secondLegDelayStressNetReturnPct: round8(delayStress.netPnlUsdt / initialEquity * 100),
    secondLegDelayAtomicityFailureCount: delayStress.atomicityFailureCount,
    assetNetPnlUsdt: assetNetPnl,
    tradesByAsset: countBy(trades, (trade) => trade.symbol),
    exitReasons: countBy(trades, (trade) => trade.exitReason),
    blockReturns,
    positiveBlockCount: blockReturns.filter((block) => block.returnPct > 0).length,
    totalBlockCount: blockReturns.length,
    dailyReturnCount: dailyReturns.length,
    trades,
  };
}

function repriceTrades(trades, framesBySymbol, protocol, candidate, options) {
  const execution = protocol.executionContract;
  let netPnlUsdt = 0;
  let atomicityFailureCount = 0;
  for (const trade of trades) {
    const frames = framesBySymbol[trade.symbol];
    const entryFrame = frames[trade.entryFrameIndex];
    const delayedEntry = frames[Math.min(frames.length - 1, trade.entryFrameIndex + options.secondLegDelayBars)];
    const delayedExit = frames[Math.min(frames.length - 1, trade.exitFrameIndex + options.secondLegDelayBars)];
    const spotEntryReference = Math.max(trade.spotEntryReferencePrice, delayedEntry.spot.open);
    const perpetualExitReference = Math.max(trade.perpetualExitReferencePrice, delayedExit.perpetual.open);
    const sizing = assetPairSizing(
      trade.quantityBase,
      spotEntryReference,
      entryFrame.perpetual.open,
      execution,
      protocol.observedInstrumentRules[trade.symbol],
      options.costMultiplier,
    );
    const maximumCommitted = trade.entryPortfolioEquityUsdt *
      (1 - execution.minimumUncommittedEquityFraction);
    if (options.enforceAtomicity &&
        (sizing.committedCapitalUsdt + trade.existingCommittedCapitalAtEntryUsdt > maximumCommitted + 1e-9 ||
          sizing.netHedgeMismatchBase >
            protocol.observedInstrumentRules[trade.symbol].maximumNetHedgeMismatchBase + 1e-12)) {
      atomicityFailureCount += 1;
      const shortEntry = sizing.perpetualEntryFillPrice;
      const shortExit = delayedEntry.perpetual.open *
        (1 + execution.perpetualSlippageRatePerLeg * options.costMultiplier);
      const entryFee = trade.quantityBase * shortEntry * execution.perpetualTakerFeeRate *
        options.costMultiplier;
      const exitFee = trade.quantityBase * shortExit * execution.perpetualTakerFeeRate *
        options.costMultiplier;
      netPnlUsdt += trade.quantityBase * (shortEntry - shortExit) - entryFee - exitFee;
      continue;
    }
    const spotExitFill = trade.spotExitReferencePrice *
      (1 - execution.spotSlippageRatePerLeg * options.costMultiplier);
    const perpetualExitFill = perpetualExitReference *
      (1 + execution.perpetualSlippageRatePerLeg * options.costMultiplier);
    const spotExitGross = sizing.netSpotQuantityBase * spotExitFill;
    const spotExitNet = spotExitGross * (1 - execution.spotTakerFeeRate * options.costMultiplier);
    const spotPnl = spotExitNet - sizing.spotOrderAmountUsdt;
    const perpetualExitFee = trade.quantityBase * perpetualExitFill *
      execution.perpetualTakerFeeRate * options.costMultiplier;
    const perpetualPnl = trade.quantityBase *
      (sizing.perpetualEntryFillPrice - perpetualExitFill);
    netPnlUsdt += spotPnl + perpetualPnl + trade.fundingPnlUsdt -
      sizing.perpetualEntryFeeUsdt - perpetualExitFee;
  }
  return { netPnlUsdt, atomicityFailureCount };
}

function calculateBlockReturns(dailyEquity, blocks, initialEquity) {
  let previousEquity = initialEquity;
  return blocks.map((block) => {
    const rows = dailyEquity.filter(
      (row) => row.day >= block.startAt.slice(0, 10) && row.day < block.endAt.slice(0, 10),
    );
    const endingEquity = rows.at(-1)?.equityUsdt ?? previousEquity;
    const result = {
      blockId: block.id,
      era: block.era,
      startingEquityUsdt: round8(previousEquity),
      endingEquityUsdt: round8(endingEquity),
      returnPct: round8((endingEquity / previousEquity - 1) * 100),
    };
    previousEquity = endingEquity;
    return result;
  });
}

function minimumGateMargin(metrics, gate) {
  const hedgeMargins = Object.entries(gate.maximumNetHedgeMismatchBySymbol).map(
    ([symbol, limit]) => limit - (metrics.maximumNetHedgeMismatchBySymbol[symbol] ?? Infinity),
  );
  return Math.min(
    metrics.tradeCount / gate.minimumClosedPositions - 1,
    metrics.activeCalendarDays / gate.minimumActiveCalendarDays - 1,
    metrics.capturedFundingSettlements / gate.minimumCapturedFundingSettlements - 1,
    metrics.tradedAssetCount - gate.minimumTradedAssetCount,
    metrics.positiveAssetCount - gate.minimumPositiveAssetCount,
    metrics.positiveBlockCount - gate.minimumPositiveBlockCount,
    metrics.netReturnPct / 100,
    metrics.profitFactor / gate.minimumProfitFactor - 1,
    metrics.meanDailyReturnPct,
    metrics.bootstrapLowerMeanDailyReturnPct,
    (gate.maximumDrawdownPct - metrics.maximumDrawdownPct) / gate.maximumDrawdownPct,
    gate.maximumLiquidationCount - metrics.liquidationCount,
    gate.maximumPositivePositionProfitConcentration - metrics.positivePositionProfitConcentration,
    gate.maximumPositiveAssetProfitConcentration - metrics.positiveAssetProfitConcentration,
    ...hedgeMargins,
    metrics.costStressNetReturnPct / 100,
    metrics.secondLegDelayStressNetReturnPct / 100,
  );
}

function countBy(values, keyOf) {
  const counts = {};
  for (const value of values) {
    const key = keyOf(value);
    counts[key] = (counts[key] ?? 0) + 1;
  }
  return counts;
}

function seededRandom(seed) {
  let state = createHash("sha256").update(seed).digest().readUInt32LE(0) || 1;
  return () => {
    state ^= state << 13;
    state ^= state >>> 17;
    state ^= state << 5;
    return (state >>> 0) / 0x1_0000_0000;
  };
}

function percentile(sorted, probability) {
  const index = Math.min(sorted.length - 1, Math.max(0, Math.floor(probability * sorted.length)));
  return sorted[index];
}

function floorStep(value, step) {
  return round12(Math.floor((value + 1e-12) / step) * step);
}

function ceilStep(value, step) {
  return round12(Math.ceil((value - 1e-12) / step) * step);
}

function median(values) {
  const sorted = [...values].sort((left, right) => left - right);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0
    ? (sorted[middle - 1] + sorted[middle]) / 2
    : sorted[middle];
}

function mean(values) {
  return values.length === 0 ? 0 : values.reduce((sum, value) => sum + value, 0) / values.length;
}

function utcDay(timestamp) {
  return instantString(timestamp).slice(0, 10);
}

function instantString(timestamp) {
  return new Date(timestamp).toISOString().replace(".000Z", "Z");
}

function round8(value) {
  return Number(value.toFixed(8));
}

function round12(value) {
  return Number(value.toFixed(12));
}
