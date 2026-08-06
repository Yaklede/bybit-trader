import { createHash } from "node:crypto";

const M5_MILLIS = 5 * 60 * 1_000;
const DAY_MILLIS = 24 * 60 * 60 * 1_000;
const MINIMUM_PERPETUAL_NOTIONAL_USDT = 5;
const MINIMUM_SPOT_ORDER_AMOUNT_USDT = 5;

export function calculatePairQuantity(equityUsdt, spotReferencePrice, perpetualReferencePrice, execution, costMultiplier = 1) {
  const maximumMatchedNotional = equityUsdt * execution.maximumMatchedNotionalFractionOfEquity;
  const quantityStep = execution.perpetualQuantityStepBtc;
  let quantity = floorStep(maximumMatchedNotional / Math.max(spotReferencePrice, perpetualReferencePrice), quantityStep);
  while (quantity >= execution.perpetualMinimumQuantityBtc - 1e-12) {
    const sizing = pairSizing(quantity, spotReferencePrice, perpetualReferencePrice, execution, costMultiplier);
    const maximumCommittedCapital = equityUsdt * (1 - execution.minimumUncommittedEquityFraction);
    if (sizing.committedCapitalUsdt <= maximumCommittedCapital + 1e-9 &&
        sizing.perpetualNotionalUsdt >= MINIMUM_PERPETUAL_NOTIONAL_USDT &&
        sizing.spotOrderAmountUsdt >= MINIMUM_SPOT_ORDER_AMOUNT_USDT &&
        sizing.netHedgeMismatchBtc <= execution.maximumNetHedgeMismatchBtc + 1e-12) {
      return sizing;
    }
    quantity = round12(quantity - quantityStep);
  }
  return null;
}

export function pairSizing(quantity, spotReferencePrice, perpetualReferencePrice, execution, costMultiplier = 1) {
  const spotFeeRate = execution.spotTakerFeeRate * costMultiplier;
  const perpetualFeeRate = execution.perpetualTakerFeeRate * costMultiplier;
  const spotEntryFillPrice = spotReferencePrice * (1 + execution.spotSlippageRatePerLeg * costMultiplier);
  const perpetualEntryFillPrice = perpetualReferencePrice *
    (1 - execution.perpetualSlippageRatePerLeg * costMultiplier);
  const grossSpotQuantityBtc = ceilStep(quantity / (1 - spotFeeRate), execution.spotBasePrecisionBtc);
  const netSpotQuantityBtc = grossSpotQuantityBtc * (1 - spotFeeRate);
  const netHedgeMismatchBtc = Math.abs(netSpotQuantityBtc - quantity);
  const spotOrderAmountUsdt = grossSpotQuantityBtc * spotEntryFillPrice;
  const perpetualNotionalUsdt = quantity * perpetualEntryFillPrice;
  const perpetualEntryFeeUsdt = perpetualNotionalUsdt * perpetualFeeRate;
  const perpetualInitialMarginUsdt = perpetualNotionalUsdt / execution.perpetualLeverage;
  return {
    targetNetQuantityBtc: quantity,
    grossSpotQuantityBtc,
    netSpotQuantityBtc,
    netHedgeMismatchBtc,
    spotEntryFillPrice,
    perpetualEntryFillPrice,
    spotOrderAmountUsdt,
    perpetualNotionalUsdt,
    perpetualEntryFeeUsdt,
    perpetualInitialMarginUsdt,
    committedCapitalUsdt: spotOrderAmountUsdt + perpetualInitialMarginUsdt + perpetualEntryFeeUsdt,
  };
}

export function evaluateFundingCarrySignal({ candidate, fundingRates, fundingIndex, decisionFrame, execution }) {
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
  const projectedFundingSettlements = candidate.projectedCarryHorizonDays * 3;
  const projectedGrossCarryToBaseCostRatio = trailingMedianFundingRate * projectedFundingSettlements /
    execution.baseRoundTripCostRateOnMatchedNotional;
  if (projectedGrossCarryToBaseCostRatio + 1e-15 < candidate.minimumProjectedGrossCarryToBaseCostRatio) {
    return null;
  }
  return {
    trailingMedianFundingRate,
    entryBasisPct,
    markIndexPremiumPct,
    projectedGrossCarryToBaseCostRatio,
  };
}

export function simulateFundingCarryCandidate({ candidate, frames, fundingRates, protocol }) {
  const execution = protocol.executionContract;
  const fundingByTimestamp = new Map(fundingRates.map((row, index) => [row.timestamp, { ...row, index }]));
  const frameIndexByTimestamp = new Map(frames.map((frame, index) => [frame.timestamp, index]));
  const initialEquity = execution.startingEquityUsdt;
  let realizedEquity = initialEquity;
  let currentEquity = initialEquity;
  let highWaterEquity = initialEquity;
  let maximumDrawdownPct = 0;
  let position = null;
  let pendingEntry = null;
  let pendingExit = null;
  let cooldownUntil = Number.NEGATIVE_INFINITY;
  let liquidationCount = 0;
  let noTradeSignalCount = 0;
  let signalCount = 0;
  let maximumNetHedgeMismatchBtc = 0;
  const trades = [];
  const activeDays = new Set();
  const dailyEquity = [];

  for (let frameIndex = 0; frameIndex < frames.length; frameIndex += 1) {
    const frame = frames[frameIndex];
    const timestamp = frame.timestamp;
    const funding = fundingByTimestamp.get(timestamp);
    if (funding != null && position != null) {
      const settlementMark = frameIndex === 0 ? frame.mark.open : frames[frameIndex - 1].mark.close;
      const payment = position.quantityBtc * settlementMark * funding.rate;
      position.fundingPnlUsdt += payment;
      position.capturedFundingSettlements += 1;
      position.nonPositiveFundingCount = funding.rate <= 0
        ? position.nonPositiveFundingCount + 1
        : 0;
      if (position.nonPositiveFundingCount >= candidate.exitConsecutiveNonPositiveFundingCount) {
        pendingExit = {
          timestamp: timestamp + candidate.entryDelayMinutes * 60 * 1_000,
          reason: "FUNDING_NO_LONGER_POSITIVE",
        };
      }
    }

    if (funding != null && position == null && pendingEntry == null && timestamp >= cooldownUntil) {
      const decisionIndex = frameIndexByTimestamp.get(timestamp - M5_MILLIS);
      if (decisionIndex != null) {
        const signal = evaluateFundingCarrySignal({
          candidate,
          fundingRates,
          fundingIndex: funding.index,
          decisionFrame: frames[decisionIndex],
          execution,
        });
        if (signal != null) {
          signalCount += 1;
          pendingEntry = {
            timestamp: timestamp + candidate.entryDelayMinutes * 60 * 1_000,
            fundingTimestamp: timestamp,
            signal,
          };
        }
      }
    }

    if (pendingEntry?.timestamp === timestamp && position == null) {
      const sizing = calculatePairQuantity(realizedEquity, frame.spot.open, frame.perpetual.open, execution);
      if (sizing == null) {
        noTradeSignalCount += 1;
      } else {
        position = openPosition(candidate, frame, frameIndex, realizedEquity, sizing, pendingEntry);
        maximumNetHedgeMismatchBtc = Math.max(maximumNetHedgeMismatchBtc, sizing.netHedgeMismatchBtc);
      }
      pendingEntry = null;
    }

    let closedThisFrame = false;
    if (position != null) {
      activeDays.add(utcDay(timestamp));
      const liquidationBoundary = position.perpetualEntryFillPrice * execution.conservativeLiquidationPriceMultiple;
      const worstBasisPct = frame.perpetual.high / frame.spot.low - 1;
      const maximumHoldingTimestamp = position.entryTimestamp + candidate.maximumHoldingDays * DAY_MILLIS;
      let exit = null;
      if (frame.mark.high >= liquidationBoundary) {
        liquidationCount += 1;
        exit = {
          reason: "LIQUIDATION",
          spotReferencePrice: frame.spot.low,
          perpetualReferencePrice: liquidationBoundary,
        };
      } else if (worstBasisPct - position.entryBasisPct >= candidate.basisDivergenceStopPctFromEntry) {
        exit = {
          reason: "BASIS_DIVERGENCE_STOP",
          spotReferencePrice: frame.spot.low,
          perpetualReferencePrice: frame.perpetual.high,
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
        realizedEquity = position.entryEquityUsdt + trade.netPnlUsdt;
        currentEquity = realizedEquity;
        cooldownUntil = timestamp + candidate.reentryCooldownHours * 60 * 60 * 1_000;
        position = null;
        pendingExit = null;
        closedThisFrame = true;
      }
    }

    if (position != null) currentEquity = markPositionEquity(position, frame);
    else if (!closedThisFrame) currentEquity = realizedEquity;
    highWaterEquity = Math.max(highWaterEquity, currentEquity);
    if (highWaterEquity > 0) {
      maximumDrawdownPct = Math.max(maximumDrawdownPct, (highWaterEquity - currentEquity) / highWaterEquity * 100);
    }
    const nextFrame = frames[frameIndex + 1];
    if (nextFrame == null || utcDay(nextFrame.timestamp) !== utcDay(timestamp)) {
      dailyEquity.push({ day: utcDay(timestamp), timestamp, equityUsdt: currentEquity });
    }
  }

  if (position != null) {
    const frameIndex = frames.length - 1;
    const frame = frames[frameIndex];
    const trade = closePosition(position, frame, frameIndex, {
      reason: "END_OF_DEVELOPMENT_DATA",
      spotReferencePrice: frame.spot.close,
      perpetualReferencePrice: frame.perpetual.close,
    }, execution);
    trades.push(trade);
    realizedEquity = position.entryEquityUsdt + trade.netPnlUsdt;
    currentEquity = realizedEquity;
    highWaterEquity = Math.max(highWaterEquity, currentEquity);
    if (highWaterEquity > 0) {
      maximumDrawdownPct = Math.max(maximumDrawdownPct, (highWaterEquity - currentEquity) / highWaterEquity * 100);
    }
    if (dailyEquity.length > 0) dailyEquity.at(-1).equityUsdt = realizedEquity;
    position = null;
  }

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
  const costStress = repriceTrades(trades, frames, execution, {
    costMultiplier: execution.costStressMultiplier,
    secondLegDelayBars: 0,
    enforceAtomicity: false,
  });
  const secondLegDelayStress = repriceTrades(trades, frames, execution, {
    costMultiplier: 1,
    secondLegDelayBars: execution.secondLegDelayStressBars,
    enforceAtomicity: true,
  });
  const grossProfit = trades.filter((trade) => trade.netPnlUsdt > 0)
    .reduce((sum, trade) => sum + trade.netPnlUsdt, 0);
  const grossLoss = -trades.filter((trade) => trade.netPnlUsdt < 0)
    .reduce((sum, trade) => sum + trade.netPnlUsdt, 0);
  const fundingPnlUsdt = trades.reduce((sum, trade) => sum + trade.fundingPnlUsdt, 0);
  const pricePnlUsdt = trades.reduce((sum, trade) => sum + trade.pricePnlUsdt, 0);
  const feeCostUsdt = trades.reduce((sum, trade) => sum + trade.feeCostUsdt, 0);
  const slippageCostUsdt = trades.reduce((sum, trade) => sum + trade.slippageCostUsdt, 0);
  const positivePositionProfitConcentration = grossProfit <= 0
    ? 1
    : Math.max(...trades.map((trade) => Math.max(0, trade.netPnlUsdt)), 0) / grossProfit;
  const blockReturns = calculateBlockReturns(dailyEquity, protocol.evidenceSchedule.developmentBlocks, initialEquity);
  const endingEquityUsdt = realizedEquity;
  return {
    candidateId: candidate.id,
    tradeCount: trades.length,
    signalCount,
    noTradeSignalCount,
    activeCalendarDays: activeDays.size,
    capturedFundingSettlements: trades.reduce((sum, trade) => sum + trade.capturedFundingSettlements, 0),
    startingEquityUsdt: round8(initialEquity),
    endingEquityUsdt: round8(endingEquityUsdt),
    netReturnPct: round8((endingEquityUsdt / initialEquity - 1) * 100),
    compoundDailyReturnPct: round8((Math.pow(endingEquityUsdt / initialEquity, 1 / dailyReturns.length) - 1) * 100),
    meanDailyReturnPct: round8(mean(dailyReturns) * 100),
    bootstrapLowerMeanDailyReturnPct: round8(bootstrap.lower * 100),
    bootstrapUpperMeanDailyReturnPct: round8(bootstrap.upper * 100),
    profitFactor: round8(grossLoss === 0 ? (grossProfit > 0 ? 999 : 0) : grossProfit / grossLoss),
    winRatePct: round8(trades.length === 0 ? 0 : trades.filter((trade) => trade.netPnlUsdt > 0).length / trades.length * 100),
    maximumDrawdownPct: round8(maximumDrawdownPct),
    liquidationCount,
    maximumNetHedgeMismatchBtc: round12(maximumNetHedgeMismatchBtc),
    positivePositionProfitConcentration: round8(positivePositionProfitConcentration),
    positiveBlockCount: blockReturns.filter((block) => block.returnPct > 0).length,
    totalBlockCount: blockReturns.length,
    blockReturns,
    fundingPnlUsdt: round8(fundingPnlUsdt),
    pricePnlUsdt: round8(pricePnlUsdt),
    feeCostUsdt: round8(feeCostUsdt),
    slippageCostUsdt: round8(slippageCostUsdt),
    costStressNetReturnPct: round8(costStress.netPnlUsdt / initialEquity * 100),
    secondLegDelayStressNetReturnPct: round8(secondLegDelayStress.netPnlUsdt / initialEquity * 100),
    secondLegDelayAtomicityFailureCount: secondLegDelayStress.atomicityFailureCount,
    exitReasons: countBy(trades, (trade) => trade.exitReason),
    dailyReturnCount: dailyReturns.length,
    trades,
  };
}

export function evaluateDevelopmentGate(metrics, gate) {
  const checks = {
    minimumClosedPositions: metrics.tradeCount >= gate.minimumClosedPositions,
    minimumActiveCalendarDays: metrics.activeCalendarDays >= gate.minimumActiveCalendarDays,
    minimumCapturedFundingSettlements: metrics.capturedFundingSettlements >= gate.minimumCapturedFundingSettlements,
    minimumPositiveBlockCount: metrics.positiveBlockCount >= gate.minimumPositiveBlockCount &&
      metrics.totalBlockCount === gate.totalBlockCount,
    minimumNetReturnPct: metrics.netReturnPct > gate.minimumNetReturnPct,
    minimumProfitFactor: metrics.profitFactor >= gate.minimumProfitFactor,
    minimumMeanDailyReturnPct: metrics.meanDailyReturnPct > gate.minimumMeanDailyReturnPct,
    minimumBootstrapLowerMeanDailyReturnPct:
      metrics.bootstrapLowerMeanDailyReturnPct > gate.minimumBootstrapLowerMeanDailyReturnPct,
    maximumDrawdownPct: metrics.maximumDrawdownPct <= gate.maximumDrawdownPct,
    maximumLiquidationCount: metrics.liquidationCount <= gate.maximumLiquidationCount,
    maximumPositivePositionProfitConcentration:
      metrics.positivePositionProfitConcentration <= gate.maximumPositivePositionProfitConcentration,
    maximumNetHedgeMismatchBtc: metrics.maximumNetHedgeMismatchBtc <= gate.maximumNetHedgeMismatchBtc,
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
  if (values.length === 0) return { lower: Number.NEGATIVE_INFINITY, upper: Number.POSITIVE_INFINITY };
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

function openPosition(candidate, frame, frameIndex, equity, sizing, pendingEntry) {
  return {
    candidateId: candidate.id,
    entryTimestamp: frame.timestamp,
    entryFrameIndex: frameIndex,
    signalFundingTimestamp: pendingEntry.fundingTimestamp,
    signal: pendingEntry.signal,
    entryEquityUsdt: equity,
    quantityBtc: sizing.targetNetQuantityBtc,
    grossSpotQuantityBtc: sizing.grossSpotQuantityBtc,
    netSpotQuantityBtc: sizing.netSpotQuantityBtc,
    netHedgeMismatchBtc: sizing.netHedgeMismatchBtc,
    spotEntryReferencePrice: frame.spot.open,
    perpetualEntryReferencePrice: frame.perpetual.open,
    spotEntryFillPrice: sizing.spotEntryFillPrice,
    perpetualEntryFillPrice: sizing.perpetualEntryFillPrice,
    spotEntryCostUsdt: sizing.spotOrderAmountUsdt,
    perpetualEntryFeeUsdt: sizing.perpetualEntryFeeUsdt,
    entryBasisPct: frame.perpetual.open / frame.spot.open - 1,
    fundingPnlUsdt: 0,
    capturedFundingSettlements: 0,
    nonPositiveFundingCount: 0,
  };
}

function markPositionEquity(position, frame) {
  const spotPnl = position.netSpotQuantityBtc * frame.spot.close - position.spotEntryCostUsdt;
  const perpetualPnl = position.quantityBtc * (position.perpetualEntryFillPrice - frame.mark.close);
  return position.entryEquityUsdt + spotPnl + perpetualPnl + position.fundingPnlUsdt - position.perpetualEntryFeeUsdt;
}

function closePosition(position, frame, frameIndex, exit, execution) {
  const spotExitFillPrice = exit.spotReferencePrice * (1 - execution.spotSlippageRatePerLeg);
  const perpetualExitFillPrice = exit.perpetualReferencePrice * (1 + execution.perpetualSlippageRatePerLeg);
  const spotExitGrossProceeds = position.netSpotQuantityBtc * spotExitFillPrice;
  const spotExitFeeUsdt = spotExitGrossProceeds * execution.spotTakerFeeRate;
  const spotExitNetProceeds = spotExitGrossProceeds - spotExitFeeUsdt;
  const perpetualExitFeeUsdt = position.quantityBtc * perpetualExitFillPrice * execution.perpetualTakerFeeRate;
  const spotPnl = spotExitNetProceeds - position.spotEntryCostUsdt;
  const perpetualPnl = position.quantityBtc * (position.perpetualEntryFillPrice - perpetualExitFillPrice);
  const netPnlUsdt = spotPnl + perpetualPnl + position.fundingPnlUsdt -
    position.perpetualEntryFeeUsdt - perpetualExitFeeUsdt;
  const spotEntryFeeValue = (position.grossSpotQuantityBtc - position.netSpotQuantityBtc) *
    position.spotEntryReferencePrice;
  const feeCostUsdt = spotEntryFeeValue + spotExitFeeUsdt + position.perpetualEntryFeeUsdt + perpetualExitFeeUsdt;
  const rawPricePnl = position.netSpotQuantityBtc * exit.spotReferencePrice -
    position.grossSpotQuantityBtc * position.spotEntryReferencePrice +
    position.quantityBtc * (position.perpetualEntryReferencePrice - exit.perpetualReferencePrice);
  const slippageCostUsdt = Math.max(0, rawPricePnl + position.fundingPnlUsdt - feeCostUsdt - netPnlUsdt);
  return {
    candidateId: position.candidateId,
    entryTimestamp: instantString(position.entryTimestamp),
    exitTimestamp: instantString(frame.timestamp),
    entryFrameIndex: position.entryFrameIndex,
    exitFrameIndex: frameIndex,
    exitReason: exit.reason,
    entryEquityUsdt: round8(position.entryEquityUsdt),
    quantityBtc: round12(position.quantityBtc),
    grossSpotQuantityBtc: round12(position.grossSpotQuantityBtc),
    netSpotQuantityBtc: round12(position.netSpotQuantityBtc),
    netHedgeMismatchBtc: round12(position.netHedgeMismatchBtc),
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
    returnOnEntryEquityPct: round8(netPnlUsdt / position.entryEquityUsdt * 100),
  };
}

function repriceTrades(trades, frames, execution, options) {
  let netPnlUsdt = 0;
  let atomicityFailureCount = 0;
  for (const trade of trades) {
    const entryFrame = frames[trade.entryFrameIndex];
    const delayedEntryFrame = frames[Math.min(frames.length - 1, trade.entryFrameIndex + options.secondLegDelayBars)];
    const delayedExitFrame = frames[Math.min(frames.length - 1, trade.exitFrameIndex + options.secondLegDelayBars)];
    const spotEntryReference = Math.max(trade.spotEntryReferencePrice, delayedEntryFrame.spot.open);
    const perpetualExitReference = Math.max(trade.perpetualExitReferencePrice, delayedExitFrame.perpetual.open);
    const sizing = pairSizing(
      trade.quantityBtc,
      spotEntryReference,
      entryFrame.perpetual.open,
      execution,
      options.costMultiplier,
    );
    const maximumCommitted = trade.entryEquityUsdt * (1 - execution.minimumUncommittedEquityFraction);
    if (options.enforceAtomicity && (sizing.committedCapitalUsdt > maximumCommitted + 1e-9 ||
        sizing.netHedgeMismatchBtc > execution.maximumNetHedgeMismatchBtc + 1e-12)) {
      atomicityFailureCount += 1;
      const shortEntry = sizing.perpetualEntryFillPrice;
      const shortExit = delayedEntryFrame.perpetual.open *
        (1 + execution.perpetualSlippageRatePerLeg * options.costMultiplier);
      const entryFee = trade.quantityBtc * shortEntry * execution.perpetualTakerFeeRate * options.costMultiplier;
      const exitFee = trade.quantityBtc * shortExit * execution.perpetualTakerFeeRate * options.costMultiplier;
      netPnlUsdt += trade.quantityBtc * (shortEntry - shortExit) - entryFee - exitFee;
      continue;
    }
    const spotExitFill = trade.spotExitReferencePrice *
      (1 - execution.spotSlippageRatePerLeg * options.costMultiplier);
    const perpetualExitFill = perpetualExitReference *
      (1 + execution.perpetualSlippageRatePerLeg * options.costMultiplier);
    const spotExitGross = sizing.netSpotQuantityBtc * spotExitFill;
    const spotExitNet = spotExitGross * (1 - execution.spotTakerFeeRate * options.costMultiplier);
    const spotPnl = spotExitNet - sizing.spotOrderAmountUsdt;
    const perpetualEntryFee = sizing.perpetualEntryFeeUsdt;
    const perpetualExitFee = trade.quantityBtc * perpetualExitFill *
      execution.perpetualTakerFeeRate * options.costMultiplier;
    const perpetualPnl = trade.quantityBtc * (sizing.perpetualEntryFillPrice - perpetualExitFill);
    netPnlUsdt += spotPnl + perpetualPnl + trade.fundingPnlUsdt - perpetualEntryFee - perpetualExitFee;
  }
  return { netPnlUsdt, atomicityFailureCount };
}

function calculateBlockReturns(dailyEquity, blocks, initialEquity) {
  let previousEquity = initialEquity;
  return blocks.map((block) => {
    const endExclusiveDay = block.endAt.slice(0, 10);
    const rows = dailyEquity.filter((row) => row.day >= block.startAt.slice(0, 10) && row.day < endExclusiveDay);
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
  const margins = [
    metrics.tradeCount / gate.minimumClosedPositions - 1,
    metrics.activeCalendarDays / gate.minimumActiveCalendarDays - 1,
    metrics.capturedFundingSettlements / gate.minimumCapturedFundingSettlements - 1,
    metrics.positiveBlockCount - gate.minimumPositiveBlockCount,
    metrics.netReturnPct / 100,
    metrics.profitFactor / gate.minimumProfitFactor - 1,
    metrics.meanDailyReturnPct,
    metrics.bootstrapLowerMeanDailyReturnPct,
    (gate.maximumDrawdownPct - metrics.maximumDrawdownPct) / gate.maximumDrawdownPct,
    gate.maximumLiquidationCount - metrics.liquidationCount,
    gate.maximumPositivePositionProfitConcentration - metrics.positivePositionProfitConcentration,
    gate.maximumNetHedgeMismatchBtc - metrics.maximumNetHedgeMismatchBtc,
    metrics.costStressNetReturnPct / 100,
    metrics.secondLegDelayStressNetReturnPct / 100,
  ];
  return Math.min(...margins);
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
  return sorted.length % 2 === 0 ? (sorted[middle - 1] + sorted[middle]) / 2 : sorted[middle];
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
