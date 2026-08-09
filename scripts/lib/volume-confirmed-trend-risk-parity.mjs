const DAY_MILLIS = 24 * 60 * 60 * 1_000;

export function auditFrozenTrendRiskPolicy({
  run,
  maximumDailyLossFraction,
  maximumAccountDrawdownFraction,
  maximumConsecutiveLosses,
}) {
  validateInputs(
    run,
    maximumDailyLossFraction,
    maximumAccountDrawdownFraction,
    maximumConsecutiveLosses,
  );

  const dayStartEquityByTimestamp = maximumDailyLossFraction == null
    ? new Map()
    : new Map(
        run.equityCurve
          .filter((point) => point.at % DAY_MILLIS === 0)
          .map((point) => [point.at, point.equity]),
      );
  let closedEquity = run.startingEquityUsdt;
  let consecutiveLosses = 0;
  let maximumObservedConsecutiveLosses = 0;
  let firstDailyLossBreach = null;
  let firstConsecutiveLossBreach = null;

  run.trades.forEach((trade, index) => {
    closedEquity += trade.netPnl;
    consecutiveLosses = trade.netPnl < 0
      ? consecutiveLosses + 1
      : trade.netPnl > 0 ? 0 : consecutiveLosses;
    maximumObservedConsecutiveLosses = Math.max(maximumObservedConsecutiveLosses, consecutiveLosses);

    if (maximumDailyLossFraction != null) {
      const exitAtMillis = Date.parse(trade.exitAt);
      const utcDayStartedAtMillis = Math.floor(exitAtMillis / DAY_MILLIS) * DAY_MILLIS;
      const dayStartEquity = dayStartEquityByTimestamp.get(utcDayStartedAtMillis);
      if (!Number.isFinite(dayStartEquity) || dayStartEquity <= 0) {
        throw new Error(`Trend risk audit has no positive UTC day-start equity for ${trade.exitAt}.`);
      }
      const dailyLossFraction = Math.max(0, (dayStartEquity - closedEquity) / dayStartEquity);
      if (firstDailyLossBreach == null && dailyLossFraction >= maximumDailyLossFraction) {
        firstDailyLossBreach = {
          tradeIndex: index + 1,
          exitAt: trade.exitAt,
          utcDayStartedAt: new Date(utcDayStartedAtMillis).toISOString(),
          dayStartEquityUsdt: round8(dayStartEquity),
          closedEquityUsdt: round8(closedEquity),
          dailyLossFraction: round12(dailyLossFraction),
          dailyLossPct: round8(dailyLossFraction * 100),
        };
      }
    }
    if (maximumConsecutiveLosses != null &&
        firstConsecutiveLossBreach == null &&
        consecutiveLosses >= maximumConsecutiveLosses) {
      firstConsecutiveLossBreach = {
        tradeIndex: index + 1,
        exitAt: trade.exitAt,
        lossStreak: consecutiveLosses,
        prefixEndingEquityUsdt: round8(closedEquity),
        prefixNetReturnPct: round8(((closedEquity / run.startingEquityUsdt) - 1) * 100),
        remainingFrozenTrades: run.trades.length - index - 1,
      };
    }
  });

  let observedPeakEquity = run.startingEquityUsdt;
  let maximumObservedAccountDrawdownFraction = 0;
  let firstAccountDrawdownBreach = null;
  for (const point of run.equityCurve) {
    observedPeakEquity = Math.max(observedPeakEquity, point.equity);
    const accountDrawdownFraction = Number.isFinite(point.accountDrawdownFraction)
      ? point.accountDrawdownFraction
      : Math.max(0, (observedPeakEquity - point.equity) / observedPeakEquity);
    maximumObservedAccountDrawdownFraction = Math.max(
      maximumObservedAccountDrawdownFraction,
      accountDrawdownFraction,
    );
    if (firstAccountDrawdownBreach == null &&
        accountDrawdownFraction >= maximumAccountDrawdownFraction) {
      firstAccountDrawdownBreach = {
        observedAt: new Date(point.at).toISOString(),
        accountDrawdownFraction: round12(accountDrawdownFraction),
        accountDrawdownPct: round8(accountDrawdownFraction * 100),
      };
    }
  }

  const reasonCodes = [];
  if (firstDailyLossBreach != null) reasonCodes.push("FROZEN_PATH_BREACHES_RUNTIME_DAILY_LOSS_LIMIT");
  if (firstAccountDrawdownBreach != null) {
    reasonCodes.push("FROZEN_PATH_BREACHES_RUNTIME_ACCOUNT_DRAWDOWN_LIMIT");
  }
  if (firstConsecutiveLossBreach != null) {
    reasonCodes.push("FROZEN_PATH_BREACHES_RUNTIME_CONSECUTIVE_LOSS_LIMIT");
  }
  return {
    projectionKind: "FROZEN_CLOSURE_PATH_COUNTERFACTUAL",
    livePathSimulation: false,
    dailyLossMeasurementKind: maximumDailyLossFraction == null
      ? "DISABLED"
      : "CLOSED_EQUITY_VS_H4_UTC_DAY_START_PROXY",
    baselineTradeCount: run.trades.length,
    maximumObservedConsecutiveLosses,
    maximumObservedAccountDrawdownPct: round8(maximumObservedAccountDrawdownFraction * 100),
    firstDailyLossBreach,
    firstAccountDrawdownBreach,
    firstConsecutiveLossBreach,
    frozenPathReproducible: reasonCodes.length === 0,
    reasonCodes,
    limitation:
      "The audit checks the frozen closure path and conservative intrabar account drawdown. The policy replay applies entry thresholds at causal H4 boundaries; Live wallet snapshots can observe additional states and exchange fills.",
  };
}

function validateInputs(
  run,
  maximumDailyLossFraction,
  maximumAccountDrawdownFraction,
  maximumConsecutiveLosses,
) {
  if (!run || !Array.isArray(run.trades) || run.trades.length === 0 || !Array.isArray(run.equityCurve)) {
    throw new Error("Trend risk audit requires a non-empty simulated run.");
  }
  if (!Number.isFinite(run.startingEquityUsdt) || run.startingEquityUsdt <= 0) {
    throw new Error("Trend risk audit starting equity must be positive.");
  }
  if (maximumDailyLossFraction != null &&
      (!Number.isFinite(maximumDailyLossFraction) ||
       maximumDailyLossFraction <= 0 || maximumDailyLossFraction > 1)) {
    throw new Error("Trend risk audit daily loss fraction must be disabled or in (0, 1].");
  }
  if (!Number.isFinite(maximumAccountDrawdownFraction) ||
      maximumAccountDrawdownFraction <= 0 || maximumAccountDrawdownFraction > 1) {
    throw new Error("Trend risk audit account drawdown fraction must be in (0, 1].");
  }
  if (maximumConsecutiveLosses != null &&
      (!Number.isInteger(maximumConsecutiveLosses) || maximumConsecutiveLosses < 1)) {
    throw new Error("Trend risk audit consecutive loss limit must be disabled or a positive integer.");
  }
  for (const trade of run.trades) {
    if (!Number.isFinite(trade.netPnl) || !Number.isFinite(Date.parse(trade.exitAt))) {
      throw new Error("Trend risk audit trade evidence is invalid.");
    }
  }
  for (const point of run.equityCurve) {
    if (!Number.isFinite(point.at) || !Number.isFinite(point.equity)) {
      throw new Error("Trend risk audit equity evidence is invalid.");
    }
  }
}

function round8(value) {
  return Number(value.toFixed(8));
}

function round12(value) {
  return Number(value.toFixed(12));
}
