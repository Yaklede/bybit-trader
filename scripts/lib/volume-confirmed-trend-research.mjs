import { createHash } from "node:crypto";

const M15_MILLIS = 15 * 60 * 1_000;
const H4_MILLIS = 4 * 60 * 60 * 1_000;
const DAY_MILLIS = 24 * 60 * 60 * 1_000;
const YEAR_MILLIS = 365.25 * DAY_MILLIS;

export function validateTrendProtocol(protocol) {
  if (protocol?.schemaVersion !== 1 || !protocol.protocolId || !protocol.candidateId) {
    throw new Error("Trend protocol identity is invalid.");
  }
  if (protocol.automaticExecutionAllowed !== false || protocol.liveExecutionAllowed !== false) {
    throw new Error("Research protocol cannot enable automatic or live execution.");
  }
  const market = protocol.market;
  if (market?.symbol !== "BTCUSDT" || market.decisionTimeframe !== "H4" || market.sourceTimeframe !== "M15" ||
      market.requiredSourceBarsPerDecisionBar !== 16 || market.warmupDecisionBars < 1) {
    throw new Error("Trend market contract is invalid.");
  }
  const strategy = protocol.strategy;
  if (!Array.isArray(strategy?.emaVotePairs) || strategy.emaVotePairs.length % 2 !== 1 ||
      strategy.minimumMajorityVotes <= strategy.emaVotePairs.length / 2 ||
      strategy.volumeMedianLookbackBars < 1 || strategy.executionDelayBars !== 1 ||
      strategy.volumeMedianExcludesDecisionBar !== true ||
      strategy.changeSideOnlyWhenVolumeAtOrAboveMedian !== true || strategy.holdUntilOppositeConfirmed !== true) {
    throw new Error("Trend strategy contract is invalid.");
  }
  for (const pair of strategy.emaVotePairs) {
    if (!Number.isInteger(pair.fast) || !Number.isInteger(pair.slow) || pair.fast < 1 || pair.fast >= pair.slow) {
      throw new Error("Trend EMA pair is invalid.");
    }
  }
  const capital = protocol.capital;
  if (!Array.isArray(capital?.startingEquitiesUsdt) || capital.startingEquitiesUsdt.length === 0 ||
      decimal(capital.targetExposureFraction) <= 0 || decimal(capital.maximumRoundedExposureFraction) <= 0 ||
      decimal(capital.targetExposureFraction) > decimal(capital.maximumRoundedExposureFraction) ||
      decimal(capital.maximumRoundedExposureFraction) >= 1 || decimal(capital.quantityStepBtc) <= 0 ||
      decimal(capital.minimumQuantityBtc) <= 0) {
    throw new Error("Trend capital contract is invalid.");
  }
  const costs = protocol.costs;
  if (decimal(costs?.oneWayFeeRate) < 0 || decimal(costs?.oneWaySlippageRate) < 0 ||
      !Array.isArray(costs?.stressMultipliers) || costs.stressMultipliers.length === 0 ||
      costs.stressMultipliers.some((value) => decimal(value) < 1) || costs.applyActualFunding !== true) {
    throw new Error("Trend cost contract is invalid.");
  }
  return protocol;
}

export function canonicalInstantString(value) {
  return new Date(instantMillis(value)).toISOString();
}

export function aggregateM15ToH4(rows, requiredBars = 16) {
  if (!Array.isArray(rows) || rows.length === 0) throw new Error("M15 evidence is empty.");
  const groups = new Map();
  let previousAt = null;
  for (const source of rows) {
    const openedAt = instantMillis(source.openedAt ?? source.opened_at);
    if (previousAt != null && openedAt <= previousAt) throw new Error("M15 evidence must be strictly ordered.");
    previousAt = openedAt;
    const bucketAt = Math.floor(openedAt / H4_MILLIS) * H4_MILLIS;
    const group = groups.get(bucketAt) ?? [];
    group.push({
      openedAt,
      open: decimal(source.open),
      high: decimal(source.high),
      low: decimal(source.low),
      close: decimal(source.close),
      volume: decimal(source.volume),
    });
    groups.set(bucketAt, group);
  }
  const ordered = [...groups.entries()].sort((left, right) => left[0] - right[0]);
  const bars = [];
  ordered.forEach(([bucketAt, group], groupIndex) => {
    const boundaryPartial = groupIndex === 0 || groupIndex === ordered.length - 1;
    if (group.length !== requiredBars) {
      if (boundaryPartial) return;
      throw new Error(`Incomplete internal H4 bucket at ${new Date(bucketAt).toISOString()}: ${group.length} bars.`);
    }
    group.forEach((bar, index) => {
      const expected = bucketAt + index * M15_MILLIS;
      if (bar.openedAt !== expected) {
        throw new Error(`Non-contiguous M15 evidence at ${new Date(expected).toISOString()}.`);
      }
    });
    bars.push({
      openedAt: bucketAt,
      open: group[0].open,
      high: Math.max(...group.map((bar) => bar.high)),
      low: Math.min(...group.map((bar) => bar.low)),
      close: group.at(-1).close,
      volume: sum(group.map((bar) => bar.volume)),
      sourceBarCount: group.length,
    });
  });
  if (bars.length === 0) throw new Error("M15 evidence contains no complete H4 bars.");
  for (let index = 1; index < bars.length; index += 1) {
    if (bars[index].openedAt - bars[index - 1].openedAt !== H4_MILLIS) {
      throw new Error(`H4 evidence gap before ${new Date(bars[index].openedAt).toISOString()}.`);
    }
  }
  return bars;
}

export function normalizeH4Evidence(rows) {
  if (!Array.isArray(rows) || rows.length === 0) throw new Error("H4 evidence is empty.");
  const bars = rows.map((source) => {
    const openedAt = instantMillis(source.openedAt ?? source.opened_at);
    if (openedAt % H4_MILLIS !== 0) {
      throw new Error(`H4 evidence is off boundary at ${new Date(openedAt).toISOString()}.`);
    }
    const bar = {
      openedAt,
      open: decimal(source.open),
      high: decimal(source.high),
      low: decimal(source.low),
      close: decimal(source.close),
      volume: decimal(source.volume),
      sourceBarCount: 1,
    };
    if (bar.low > Math.min(bar.open, bar.close) || bar.high < Math.max(bar.open, bar.close) ||
        bar.low > bar.high || bar.volume < 0) {
      throw new Error(`H4 evidence OHLCV is invalid at ${new Date(openedAt).toISOString()}.`);
    }
    return bar;
  });
  for (let index = 1; index < bars.length; index += 1) {
    if (bars[index].openedAt - bars[index - 1].openedAt !== H4_MILLIS) {
      throw new Error(`H4 evidence gap before ${new Date(bars[index].openedAt).toISOString()}.`);
    }
  }
  return bars;
}

export function buildTrendCommands(bars, strategy, warmupBars) {
  if (bars.length < warmupBars + strategy.executionDelayBars) {
    throw new Error("Trend evidence is shorter than the configured warmup.");
  }
  const emaStates = strategy.emaVotePairs.map(() => ({ fast: null, slow: null }));
  const commands = Array(bars.length).fill(null);
  let targetSide = 0;
  for (let index = 0; index < bars.length; index += 1) {
    const close = bars[index].close;
    const votes = strategy.emaVotePairs.map((pair, pairIndex) => {
      const state = emaStates[pairIndex];
      state.fast = nextEma(state.fast, close, pair.fast);
      state.slow = nextEma(state.slow, close, pair.slow);
      return Math.sign(state.fast - state.slow);
    });
    if (index + 1 < warmupBars) continue;
    const positiveVotes = votes.filter((vote) => vote > 0).length;
    const negativeVotes = votes.filter((vote) => vote < 0).length;
    const desiredSide = positiveVotes >= strategy.minimumMajorityVotes
      ? 1
      : negativeVotes >= strategy.minimumMajorityVotes ? -1 : targetSide;
    if (desiredSide === 0 || desiredSide === targetSide) continue;
    const volumeStart = index - strategy.volumeMedianLookbackBars;
    if (volumeStart < 0) continue;
    const priorVolumeMedian = median(bars.slice(volumeStart, index).map((bar) => bar.volume));
    if (bars[index].volume < priorVolumeMedian) continue;
    const executionIndex = index + strategy.executionDelayBars;
    if (executionIndex >= bars.length) continue;
    targetSide = desiredSide;
    if (commands[executionIndex] != null) throw new Error("Trend command collision detected.");
    commands[executionIndex] = {
      side: desiredSide,
      decisionAt: bars[index].openedAt + H4_MILLIS,
      decisionIndex: index,
      executionIndex,
      votes: positiveVotes - negativeVotes,
      decisionVolume: bars[index].volume,
      priorVolumeMedian,
    };
  }
  return commands;
}

export function calculateTrendQuantity({
  equity,
  price,
  targetExposureFraction,
  maximumRoundedExposureFraction,
  quantityStep,
  minimumQuantity,
  maximumNotional,
}) {
  if (![equity, price, targetExposureFraction, maximumRoundedExposureFraction, quantityStep, minimumQuantity]
      .every(Number.isFinite) || equity <= 0 || price <= 0 || targetExposureFraction <= 0 ||
      maximumRoundedExposureFraction <= 0 || quantityStep <= 0 || minimumQuantity <= 0) {
    throw new Error("Trend quantity inputs are invalid.");
  }
  const targetNotional = Math.min(equity * targetExposureFraction, maximumNotional ?? Number.POSITIVE_INFINITY);
  const maximumNotionalForEquity = Math.min(
    equity * maximumRoundedExposureFraction,
    maximumNotional ?? Number.POSITIVE_INFINITY,
  );
  let quantity = floorStep(targetNotional / price, quantityStep);
  if (quantity + 1e-12 < minimumQuantity && minimumQuantity * price <= maximumNotionalForEquity + 1e-9) {
    quantity = minimumQuantity;
  }
  if (quantity + 1e-12 < minimumQuantity || quantity * price > maximumNotionalForEquity + 1e-9) return 0;
  return round12(quantity);
}

export function simulateTrendRun({
  bars,
  fundingRates,
  commands,
  protocol,
  startingEquity,
  costMultiplier,
  riskPolicy = null,
}) {
  const capital = protocol.capital;
  const feeRate = decimal(protocol.costs.oneWayFeeRate) * costMultiplier;
  const slippageRate = decimal(protocol.costs.oneWaySlippageRate) * costMultiplier;
  const targetExposureFraction = decimal(capital.targetExposureFraction);
  const maximumRoundedExposureFraction = decimal(capital.maximumRoundedExposureFraction);
  const quantityStep = decimal(capital.quantityStepBtc);
  const minimumQuantity = decimal(capital.minimumQuantityBtc);
  const maximumNotional = capital.absoluteMaximumNotionalUsdt == null
    ? null
    : decimal(capital.absoluteMaximumNotionalUsdt);
  const fundingByTimestamp = new Map(fundingRates.map((row) => [instantMillis(row.timestamp), decimal(row.rate)]));
  if (riskPolicy != null) validateTrendSimulationRiskPolicy(riskPolicy);
  let cash = startingEquity;
  let position = null;
  let peakEquity = startingEquity;
  let maximumCloseDrawdownPct = 0;
  let maximumConservativeIntrabarDrawdownPct = 0;
  let maximumEntryExposureFraction = 0;
  let maximumAdverseExposureFraction = 0;
  let totalFees = 0;
  let totalSlippage = 0;
  let totalFundingPnl = 0;
  let skippedMinimumQuantity = 0;
  let liquidationCount = 0;
  let orderLegCount = 0;
  let sideChangeCount = 0;
  let firstActiveAt = null;
  let riskDayStartedAt = null;
  let riskDayStartEquity = startingEquity;
  let riskPeakEquity = startingEquity;
  let consecutiveLosses = 0;
  let maximumObservedConsecutiveLosses = 0;
  const equityCurve = [];
  const trades = [];
  const blockedEntries = [];

  const markEquity = (price) => cash + (position == null ? 0 : position.side * position.quantity * (price - position.entryPrice));
  const closePosition = (referencePrice, at, reason) => {
    if (position == null) return;
    const exitPrice = referencePrice * (1 - position.side * slippageRate);
    const grossPnl = position.side * position.quantity * (exitPrice - position.entryPrice);
    const fee = position.quantity * exitPrice * feeRate;
    const slippage = position.quantity * Math.abs(exitPrice - referencePrice);
    cash += grossPnl - fee;
    totalFees += fee;
    totalSlippage += slippage;
    orderLegCount += 1;
    const trade = {
      side: position.side,
      quantity: position.quantity,
      entryAt: new Date(position.entryAt).toISOString(),
      exitAt: new Date(at).toISOString(),
      entryPrice: round8(position.entryPrice),
      exitPrice: round8(exitPrice),
      grossPnl: round8(grossPnl),
      fundingPnl: round8(position.fundingPnl),
      fees: round8(position.entryFee + fee),
      netPnl: round8(grossPnl + position.fundingPnl - position.entryFee - fee),
      reason,
    };
    trades.push(trade);
    consecutiveLosses = trade.netPnl < 0
      ? consecutiveLosses + 1
      : trade.netPnl > 0 ? 0 : consecutiveLosses;
    maximumObservedConsecutiveLosses = Math.max(maximumObservedConsecutiveLosses, consecutiveLosses);
    position = null;
  };

  bars.forEach((bar, index) => {
    const fundingRate = fundingByTimestamp.get(bar.openedAt) ?? 0;
    if (position != null && fundingRate !== 0) {
      const fundingPnl = -position.side * position.quantity * bar.open * fundingRate;
      cash += fundingPnl;
      position.fundingPnl += fundingPnl;
      totalFundingPnl += fundingPnl;
    }

    const riskOpenEquity = markEquity(bar.open);
    const currentUtcDay = Math.floor(bar.openedAt / DAY_MILLIS) * DAY_MILLIS;
    if (riskDayStartedAt !== currentUtcDay) {
      riskDayStartedAt = currentUtcDay;
      riskDayStartEquity = riskOpenEquity;
    }
    riskPeakEquity = Math.max(riskPeakEquity, riskOpenEquity);
    const command = commands[index];
    if (command != null && command.side !== position?.side) {
      const previousSide = position?.side ?? 0;
      closePosition(bar.open, bar.openedAt, "OPPOSITE_VOLUME_CONFIRMED_TREND");
      const equityBeforeEntry = cash;
      const riskReasons = riskPolicy == null
        ? []
        : trendSimulationRiskReasonCodes({
            riskPolicy,
            dayStartEquity: riskDayStartEquity,
            peakEquity: riskPeakEquity,
            latestEquity: equityBeforeEntry,
            consecutiveLosses,
          });
      if (riskReasons.length > 0) {
        blockedEntries.push({
          executionAt: new Date(bar.openedAt).toISOString(),
          side: command.side,
          equityUsdt: round8(equityBeforeEntry),
          dayStartEquityUsdt: round8(riskDayStartEquity),
          peakEquityUsdt: round8(riskPeakEquity),
          consecutiveLosses,
          reasonCodes: riskReasons,
        });
      } else {
        const entryPrice = bar.open * (1 + command.side * slippageRate);
        const quantity = calculateTrendQuantity({
          equity: equityBeforeEntry,
          price: entryPrice,
          targetExposureFraction,
          maximumRoundedExposureFraction,
          quantityStep,
          minimumQuantity,
          maximumNotional,
        });
        if (quantity === 0) {
          skippedMinimumQuantity += 1;
        } else {
          maximumEntryExposureFraction = Math.max(
            maximumEntryExposureFraction,
            quantity * entryPrice / equityBeforeEntry,
          );
          const fee = quantity * entryPrice * feeRate;
          const slippage = quantity * Math.abs(entryPrice - bar.open);
          cash -= fee;
          totalFees += fee;
          totalSlippage += slippage;
          orderLegCount += 1;
          if (previousSide !== 0 && previousSide !== command.side) sideChangeCount += 1;
          position = {
            side: command.side,
            quantity,
            entryPrice,
            entryAt: bar.openedAt,
            entryFee: fee,
            fundingPnl: 0,
          };
          firstActiveAt ??= bar.openedAt;
        }
      }
    }

    const openEquity = markEquity(bar.open);
    let conservativeAdverseEquity = openEquity;
    if (position != null) {
      const favorablePrice = position.side > 0 ? bar.high : bar.low;
      const adversePrice = position.side > 0 ? bar.low : bar.high;
      const favorableEquity = markEquity(favorablePrice);
      conservativeAdverseEquity = markEquity(adversePrice);
      peakEquity = Math.max(peakEquity, openEquity, favorableEquity);
      riskPeakEquity = Math.max(riskPeakEquity, favorableEquity);
      if (conservativeAdverseEquity <= 0) liquidationCount += 1;
      const exposure = position.quantity * bar.open / Math.max(conservativeAdverseEquity, 1e-12);
      maximumAdverseExposureFraction = Math.max(maximumAdverseExposureFraction, exposure);
      maximumConservativeIntrabarDrawdownPct = Math.max(
        maximumConservativeIntrabarDrawdownPct,
        peakEquity <= 0 ? 100 : ((peakEquity - conservativeAdverseEquity) / peakEquity) * 100,
      );
    }
    const closeEquity = markEquity(bar.close);
    peakEquity = Math.max(peakEquity, closeEquity);
    riskPeakEquity = Math.max(riskPeakEquity, closeEquity);
    maximumCloseDrawdownPct = Math.max(
      maximumCloseDrawdownPct,
      peakEquity <= 0 ? 100 : ((peakEquity - closeEquity) / peakEquity) * 100,
    );
    equityCurve.push({
      at: bar.openedAt + H4_MILLIS,
      equity: closeEquity,
      conservativeAdverseEquity,
    });
  });

  const finalBar = bars.at(-1);
  closePosition(finalBar.close, finalBar.openedAt + H4_MILLIS, "EVIDENCE_END");
  if (equityCurve.length > 0) equityCurve.at(-1).equity = cash;
  const evaluationStartAt = firstActiveAt ?? bars[0].openedAt;
  const evaluationEndAt = finalBar.openedAt + H4_MILLIS;
  const years = (evaluationEndAt - evaluationStartAt) / YEAR_MILLIS;
  const days = (evaluationEndAt - evaluationStartAt) / DAY_MILLIS;
  const profitableTrades = trades.filter((trade) => trade.netPnl > 0);
  const losingTrades = trades.filter((trade) => trade.netPnl < 0);
  const grossProfit = sum(profitableTrades.map((trade) => trade.netPnl));
  const grossLoss = Math.abs(sum(losingTrades.map((trade) => trade.netPnl)));
  const result = {
    startingEquityUsdt: round8(startingEquity),
    endingEquityUsdt: round8(cash),
    netPnlUsdt: round8(cash - startingEquity),
    netReturnPct: round8(((cash / startingEquity) - 1) * 100),
    cagrPct: round8(years <= 0 || cash <= 0 ? -100 : (Math.pow(cash / startingEquity, 1 / years) - 1) * 100),
    compoundDailyReturnPct: round8(days <= 0 || cash <= 0 ? -100 : (Math.pow(cash / startingEquity, 1 / days) - 1) * 100),
    maximumCloseDrawdownPct: round8(maximumCloseDrawdownPct),
    maximumConservativeIntrabarDrawdownPct: round8(maximumConservativeIntrabarDrawdownPct),
    maximumEntryExposureFraction: round8(maximumEntryExposureFraction),
    maximumAdverseExposureFraction: round8(maximumAdverseExposureFraction),
    totalFeesUsdt: round8(totalFees),
    totalSlippageUsdt: round8(totalSlippage),
    totalFundingPnlUsdt: round8(totalFundingPnl),
    orderLegCount,
    sideChangeCount,
    annualizedSideChangeCount: round8(years <= 0 ? 0 : sideChangeCount / years),
    closedTradeCount: trades.length,
    profitableTradeCount: profitableTrades.length,
    losingTradeCount: losingTrades.length,
    winRatePct: round8(trades.length === 0 ? 0 : profitableTrades.length / trades.length * 100),
    profitFactor: grossLoss === 0 ? null : round8(grossProfit / grossLoss),
    skippedMinimumQuantity,
    liquidationCount,
    evaluationStartAt: new Date(evaluationStartAt).toISOString(),
    evaluationEndAt: new Date(evaluationEndAt).toISOString(),
    equityCurve,
    trades,
  };
  if (riskPolicy != null) {
    result.riskPolicyReplay = {
      policy: {
        maximumDailyLossFraction: riskPolicy.maximumDailyLossFraction,
        maximumAccountDrawdownFraction: riskPolicy.maximumAccountDrawdownFraction,
        maximumConsecutiveLosses: riskPolicy.maximumConsecutiveLosses,
      },
      blockedEntryCount: blockedEntries.length,
      blockedEntryReasonCounts: countBlockedEntryReasons(blockedEntries),
      firstBlockedEntry: blockedEntries[0] ?? null,
      maximumObservedConsecutiveLosses,
      finalConsecutiveLosses: consecutiveLosses,
    };
  }
  return result;
}

function validateTrendSimulationRiskPolicy(riskPolicy) {
  if (!Number.isFinite(riskPolicy.maximumDailyLossFraction) ||
      riskPolicy.maximumDailyLossFraction <= 0 || riskPolicy.maximumDailyLossFraction > 1) {
    throw new Error("Trend simulation daily loss fraction must be in (0, 1].");
  }
  if (!Number.isFinite(riskPolicy.maximumAccountDrawdownFraction) ||
      riskPolicy.maximumAccountDrawdownFraction <= 0 || riskPolicy.maximumAccountDrawdownFraction > 1) {
    throw new Error("Trend simulation account drawdown fraction must be in (0, 1].");
  }
  if (!Number.isInteger(riskPolicy.maximumConsecutiveLosses) || riskPolicy.maximumConsecutiveLosses < 1) {
    throw new Error("Trend simulation consecutive loss limit must be a positive integer.");
  }
}

function trendSimulationRiskReasonCodes({
  riskPolicy,
  dayStartEquity,
  peakEquity,
  latestEquity,
  consecutiveLosses,
}) {
  const reasons = [];
  if (lossFraction(dayStartEquity, latestEquity) >= riskPolicy.maximumDailyLossFraction) {
    reasons.push("DAILY_EQUITY_LOSS_LIMIT_REACHED");
  }
  if (lossFraction(peakEquity, latestEquity) >= riskPolicy.maximumAccountDrawdownFraction) {
    reasons.push("ACCOUNT_DRAWDOWN_LIMIT_REACHED");
  }
  if (consecutiveLosses >= riskPolicy.maximumConsecutiveLosses) {
    reasons.push("CONSECUTIVE_LOSS_LIMIT_REACHED");
  }
  return reasons;
}

function lossFraction(baseline, current) {
  return baseline <= 0 || current >= baseline ? 0 : (baseline - current) / baseline;
}

function countBlockedEntryReasons(blockedEntries) {
  return Object.fromEntries(
    [...new Set(blockedEntries.flatMap((entry) => entry.reasonCodes))]
      .sort()
      .map((reason) => [reason, blockedEntries.filter((entry) => entry.reasonCodes.includes(reason)).length]),
  );
}

export function summarizeTrendRun(run, protocol) {
  const daily = dailyEquity(run.equityCurve, run.evaluationStartAt);
  const annual = annualReturns(daily);
  const completeYears = annual.filter((row) => row.complete);
  const rollingTwelveMonth = rollingReturns(daily, 365);
  const randomWindows = deterministicRandomWindows(daily, protocol.approvalGates);
  return {
    ...withoutInternalSeries(run),
    annualReturns: annual,
    completeYearCount: completeYears.length,
    positiveCompleteYearCount: completeYears.filter((row) => row.returnPct > 0).length,
    positiveCompleteYearFraction: round8(
      completeYears.length === 0 ? 0 : completeYears.filter((row) => row.returnPct > 0).length / completeYears.length,
    ),
    rollingTwelveMonth: {
      sampleCount: rollingTwelveMonth.length,
      positiveCount: rollingTwelveMonth.filter((row) => row.returnPct > 0).length,
      positiveFraction: round8(
        rollingTwelveMonth.length === 0
          ? 0
          : rollingTwelveMonth.filter((row) => row.returnPct > 0).length / rollingTwelveMonth.length,
      ),
      worstReturnPct: round8(Math.min(...rollingTwelveMonth.map((row) => row.returnPct))),
      medianReturnPct: round8(median(rollingTwelveMonth.map((row) => row.returnPct))),
    },
    randomWindows: {
      seed: protocol.approvalGates.randomWindowSeed,
      count: randomWindows.length,
      positiveCount: randomWindows.filter((row) => row.returnPct > 0).length,
      positiveFraction: round8(
        randomWindows.length === 0 ? 0 : randomWindows.filter((row) => row.returnPct > 0).length / randomWindows.length,
      ),
      medianReturnPct: round8(median(randomWindows.map((row) => row.returnPct))),
      medianCompoundDailyReturnPct: round8(median(randomWindows.map((row) => row.compoundDailyReturnPct))),
      windows: randomWindows,
    },
  };
}

export function evaluateTrendDevelopment(protocol, bars, fundingRates) {
  return evaluateTrendEvidence(protocol, bars, fundingRates, "DEVELOPMENT");
}

export function evaluateTrendExternal(protocol, bars, fundingRates) {
  return evaluateTrendEvidence(protocol, bars, fundingRates, "EXTERNAL");
}

function evaluateTrendEvidence(protocol, bars, fundingRates, phase) {
  validateTrendProtocol(protocol);
  const commands = buildTrendCommands(bars, protocol.strategy, protocol.market.warmupDecisionBars);
  const runs = [];
  for (const startingEquityValue of protocol.capital.startingEquitiesUsdt) {
    for (const multiplierValue of protocol.costs.stressMultipliers) {
      const startingEquity = decimal(startingEquityValue);
      const costMultiplier = decimal(multiplierValue);
      runs.push({
        startingEquityUsdt: startingEquityValue,
        costMultiplier: multiplierValue,
        metrics: summarizeTrendRun(
          simulateTrendRun({ bars, fundingRates, commands, protocol, startingEquity, costMultiplier }),
          protocol,
        ),
      });
    }
  }
  const canonicalEquity = protocol.capital.startingEquitiesUsdt.includes("660")
    ? "660"
    : protocol.capital.startingEquitiesUsdt[0];
  const baseline = requireRun(runs, canonicalEquity, "1");
  const doubleCost = requireRun(runs, canonicalEquity, "2");
  const gate = protocol.approvalGates;
  const gates = {
    minimumBaselineCagr: baseline.metrics.cagrPct >= decimal(gate.minimumBaselineCagrPct),
    minimumDoubleCostCagr: doubleCost.metrics.cagrPct >= decimal(gate.minimumDoubleCostCagrPct),
    maximumBaselineDrawdown:
      baseline.metrics.maximumConservativeIntrabarDrawdownPct <= decimal(gate.maximumBaselineDrawdownPct),
    maximumDoubleCostDrawdown:
      doubleCost.metrics.maximumConservativeIntrabarDrawdownPct <= decimal(gate.maximumDoubleCostDrawdownPct),
    minimumPositiveCompleteYearFraction:
      baseline.metrics.positiveCompleteYearFraction >= decimal(gate.minimumPositiveCompleteYearFraction),
    maximumDirectionChangesPerYear:
      baseline.metrics.annualizedSideChangeCount <= decimal(gate.maximumDirectionChangesPerYear),
    minimumPositiveRandomWindowFraction:
      baseline.metrics.randomWindows.positiveFraction >= decimal(gate.minimumPositiveRandomWindowFraction),
    minimumPositiveRollingTwelveMonthFraction:
      baseline.metrics.rollingTwelveMonth.positiveFraction >= decimal(gate.minimumPositiveRollingTwelveMonthFraction),
    minimumWorstRollingTwelveMonthReturn:
      baseline.metrics.rollingTwelveMonth.worstReturnPct >= decimal(gate.minimumWorstRollingTwelveMonthReturnPct),
    everyStartingEquityPositiveAtBaseline:
      runs.filter((run) => decimal(run.costMultiplier) === 1).every((run) => run.metrics.netReturnPct > 0),
    everyStartingEquityPositiveAtDoubleCost:
      runs.filter((run) => decimal(run.costMultiplier) === 2).every((run) => run.metrics.netReturnPct > 0),
    maximumEntryExposure:
      runs.every((run) =>
        run.metrics.maximumEntryExposureFraction <= decimal(protocol.capital.maximumRoundedExposureFraction) + 1e-8),
    zeroLiquidations: runs.every((run) => run.metrics.liquidationCount === 0),
    externalVenuePass: false,
    freshShadowPass: false,
    paperReplayParity: false,
  };
  const evidencePassed = Object.entries(gates)
    .filter(([name]) => !["externalVenuePass", "freshShadowPass", "paperReplayParity"].includes(name))
    .every(([, passed]) => passed);
  if (phase === "EXTERNAL") gates.externalVenuePass = evidencePassed;
  return {
    status: phase === "EXTERNAL"
      ? evidencePassed ? "HISTORICALLY_VALIDATED_SHADOW_REQUIRED" : "REJECTED_EXTERNAL_GATES"
      : evidencePassed ? "DEVELOPMENT_PASS_EXTERNAL_REQUIRED" : "REJECTED_DEVELOPMENT_GATES",
    automaticExecutionAllowed: false,
    liveExecutionAllowed: false,
    evidence: {
      h4BarCount: bars.length,
      firstH4OpenedAt: new Date(bars[0].openedAt).toISOString(),
      lastH4OpenedAt: new Date(bars.at(-1).openedAt).toISOString(),
      fundingRateCount: fundingRates.length,
      commandCount: commands.filter(Boolean).length,
      sourceFeatureSha256: sourceFeatureHash(bars, fundingRates),
    },
    canonicalStartingEquityUsdt: canonicalEquity,
    gates,
    runs,
  };
}

export function sourceFeatureHash(bars, fundingRates) {
  const digest = createHash("sha256");
  bars.forEach((bar) => digest.update([
    bar.openedAt, bar.open, bar.high, bar.low, bar.close, bar.volume, bar.sourceBarCount,
  ].join("|") + "\n"));
  fundingRates.forEach((row) => digest.update(`F|${instantMillis(row.timestamp)}|${decimal(row.rate)}\n`));
  return digest.digest("hex");
}

function dailyEquity(curve, evaluationStartAt) {
  const start = instantMillis(evaluationStartAt);
  const byDay = new Map();
  curve.filter((point) => point.at >= start).forEach((point) => {
    const dayAt = Math.floor(point.at / DAY_MILLIS) * DAY_MILLIS;
    byDay.set(dayAt, point.equity);
  });
  return [...byDay.entries()].map(([at, equity]) => ({ at, equity })).sort((left, right) => left.at - right.at);
}

function annualReturns(daily) {
  if (daily.length === 0) return [];
  const firstAt = daily[0].at;
  const lastAt = daily.at(-1).at;
  const byYear = new Map();
  daily.forEach((point) => {
    const year = new Date(point.at).getUTCFullYear();
    const points = byYear.get(year) ?? [];
    points.push(point);
    byYear.set(year, points);
  });
  let priorEquity = daily[0].equity;
  return [...byYear.entries()].map(([year, points]) => {
    const endingEquity = points.at(-1).equity;
    const complete = firstAt <= Date.UTC(year, 0, 1) && lastAt >= Date.UTC(year + 1, 0, 1) - DAY_MILLIS;
    const result = {
      year,
      complete,
      startingEquity: round8(priorEquity),
      endingEquity: round8(endingEquity),
      returnPct: round8(((endingEquity / priorEquity) - 1) * 100),
    };
    priorEquity = endingEquity;
    return result;
  });
}

function rollingReturns(daily, days) {
  const equityByDay = new Map(daily.map((point) => [point.at, point.equity]));
  return daily.flatMap((point) => {
    const start = equityByDay.get(point.at - days * DAY_MILLIS);
    if (start == null || start <= 0) return [];
    return [{
      startAt: new Date(point.at - days * DAY_MILLIS).toISOString(),
      endAt: new Date(point.at).toISOString(),
      returnPct: ((point.equity / start) - 1) * 100,
    }];
  });
}

function deterministicRandomWindows(daily, gate) {
  if (daily.length === 0) return [];
  const random = mulberry32(gate.randomWindowSeed);
  const result = [];
  for (let index = 0; index < gate.randomWindowCount; index += 1) {
    const months = randomInteger(random, gate.randomWindowMinimumMonths, gate.randomWindowMaximumMonths);
    const eligibleStarts = daily.filter((point) => addUtcMonths(point.at, months) <= daily.at(-1).at);
    if (eligibleStarts.length === 0) throw new Error(`No evidence supports a ${months}-month random window.`);
    const start = eligibleStarts[Math.floor(random() * eligibleStarts.length)];
    const requestedEndAt = addUtcMonths(start.at, months);
    const end = daily.find((point) => point.at >= requestedEndAt) ?? daily.at(-1);
    const days = Math.max(1, (end.at - start.at) / DAY_MILLIS);
    const returnRatio = end.equity / start.equity;
    result.push({
      id: `R${String(index + 1).padStart(2, "0")}`,
      months,
      startAt: new Date(start.at).toISOString(),
      endAt: new Date(end.at).toISOString(),
      returnPct: round8((returnRatio - 1) * 100),
      compoundDailyReturnPct: round8((Math.pow(returnRatio, 1 / days) - 1) * 100),
    });
  }
  return result;
}

function withoutInternalSeries(run) {
  const { equityCurve: _equityCurve, trades: _trades, ...summary } = run;
  return summary;
}

function requireRun(runs, startingEquity, costMultiplier) {
  const run = runs.find((candidate) =>
    decimal(candidate.startingEquityUsdt) === decimal(startingEquity) &&
    decimal(candidate.costMultiplier) === decimal(costMultiplier));
  if (run == null) throw new Error(`Missing trend run equity=${startingEquity} cost=${costMultiplier}.`);
  return run;
}

function nextEma(previous, value, period) {
  if (previous == null) return value;
  const alpha = 2 / (period + 1);
  return alpha * value + (1 - alpha) * previous;
}

function floorStep(value, step) {
  return Math.floor((value + 1e-12) / step) * step;
}

function median(values) {
  if (values.length === 0) throw new Error("Median requires at least one value.");
  const sorted = [...values].sort((left, right) => left - right);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
}

function sum(values) {
  return values.reduce((total, value) => total + value, 0);
}

function decimal(value) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) throw new Error(`Invalid decimal value: ${value}`);
  return parsed;
}

function instantMillis(value) {
  const parsed = typeof value === "number" ? value : Date.parse(value);
  if (!Number.isFinite(parsed)) throw new Error(`Invalid instant value: ${value}`);
  return parsed;
}

function round8(value) {
  return Math.round((value + Number.EPSILON) * 1e8) / 1e8;
}

function round12(value) {
  return Math.round((value + Number.EPSILON) * 1e12) / 1e12;
}

function mulberry32(seed) {
  let value = seed >>> 0;
  return () => {
    value += 0x6D2B79F5;
    let output = value;
    output = Math.imul(output ^ output >>> 15, output | 1);
    output ^= output + Math.imul(output ^ output >>> 7, output | 61);
    return ((output ^ output >>> 14) >>> 0) / 4294967296;
  };
}

function randomInteger(random, minimum, maximum) {
  return minimum + Math.floor(random() * (maximum - minimum + 1));
}

function addUtcMonths(timestamp, months) {
  const value = new Date(timestamp);
  const day = value.getUTCDate();
  value.setUTCDate(1);
  value.setUTCMonth(value.getUTCMonth() + months);
  const lastDay = new Date(Date.UTC(value.getUTCFullYear(), value.getUTCMonth() + 1, 0)).getUTCDate();
  value.setUTCDate(Math.min(day, lastDay));
  return value.getTime();
}
