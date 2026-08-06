package dev.yaklede.bybittrader.engine.backtest

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.position.CausalEntryPlanRequest
import dev.yaklede.bybittrader.engine.position.CausalEntryPlanner
import dev.yaklede.bybittrader.engine.position.CausalEntryPolicyConfig
import dev.yaklede.bybittrader.engine.position.CausalPositionExitReason
import dev.yaklede.bybittrader.engine.position.CausalPositionOpenRequest
import dev.yaklede.bybittrader.engine.position.CausalPositionPolicy
import dev.yaklede.bybittrader.engine.position.CausalPositionPolicyConfig
import dev.yaklede.bybittrader.strategy.TradingStrategy
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.pow

class BacktestRunner(
    private val strategy: TradingStrategy,
) {
    fun run(
        candles: List<Candle>,
        config: BacktestConfig = BacktestConfig(),
    ): BacktestResult {
        val sortedCandles = candles.sortedBy { it.openedAt }
        require(sortedCandles.map { it.symbol }.distinct().size <= 1) { "Backtest candles must use a single symbol." }
        require(sortedCandles.map { it.timeframe }.distinct().size <= 1) { "Backtest candles must use a single timeframe." }
        val replayCandles =
            sortedCandles.filter { candle ->
                (config.replayStartAt == null || !candle.openedAt.isBefore(config.replayStartAt)) &&
                    (config.replayEndAtExclusive == null || candle.openedAt.isBefore(config.replayEndAtExclusive))
            }

        if (sortedCandles.size < strategy.warmupCandles + 2 || replayCandles.isEmpty()) {
            return emptyResult(replayCandles, config)
        }

        var equity = config.initialEquity
        var peakEquity = equity
        var maxDrawdownPct = 0.0
        var evaluatedWindows = 0
        var skippedSignals = 0
        val noTradeReasonCounts = mutableMapOf<String, Int>()
        val trades = mutableListOf<BacktestTrade>()
        val tradesByUtcDay = mutableMapOf<java.time.LocalDate, Int>()
        val entryPlanner =
            CausalEntryPlanner(
                CausalEntryPolicyConfig(
                    riskFraction = config.riskFraction,
                    entrySlippageRate = config.slippageRate,
                    maxTradesPerUtcDay = config.maxTradesPerUtcDay,
                    minimumEntryRiskFraction = config.minimumEntryRiskFraction,
                    maximumEntryRiskFraction = config.maximumEntryRiskFraction,
                ),
            )
        val firstReplayIndex =
            config.replayStartAt?.let { replayStartAt ->
                sortedCandles.indexOfFirst { !it.openedAt.isBefore(replayStartAt) }
            } ?: 0
        val lastReplayIndex =
            config.replayEndAtExclusive?.let { replayEndAtExclusive ->
                sortedCandles.indexOfLast { it.openedAt.isBefore(replayEndAtExclusive) }
            } ?: sortedCandles.lastIndex
        var index = maxOf(strategy.warmupCandles, firstReplayIndex)

        while (index < lastReplayIndex) {
            if (config.requireFullHoldWindow && index + 1 + config.maxHoldCandles > lastReplayIndex) break
            evaluatedWindows += 1
            val decisionCandle = sortedCandles[index]
            val decisionCandles =
                CausalReplay.closedCandlesAt(
                    sortedCandles.subList(0, index + 1),
                    decisionCandle.openedAt.plus(decisionCandle.timeframe.replayDuration()),
                )
            val decision = strategy.evaluate(decisionCandles)
            val signal = decision.intent
            if (signal == null) {
                skippedSignals += 1
                decision.reasonCodes.incrementReasons(noTradeReasonCounts)
                index += 1
                continue
            }

            val entryIndex = index + 1
            val entryFill = CausalReplay.nextContiguousEntry(sortedCandles, index, signal.side, config.slippageRate)
            if (entryFill == null) {
                skippedSignals += 1
                listOf("NO_CONTIGUOUS_ENTRY_CANDLE").incrementReasons(noTradeReasonCounts)
                index += 1
                continue
            }
            val entryCandle = entryFill.candle
            val entryUtcDay = entryCandle.openedAt.atZone(ZoneOffset.UTC).toLocalDate()
            val dayTrades = tradesByUtcDay[entryUtcDay] ?: 0
            val entryResult =
                entryPlanner.plan(
                    CausalEntryPlanRequest(
                        signal = signal,
                        signalAt = decisionCandle.openedAt,
                        entryCandle = entryCandle,
                        equity = equity,
                        entriesOnEntryUtcDay = dayTrades,
                    ),
                )
            val entryPlan = entryResult.plan
            if (entryPlan == null) {
                skippedSignals += 1
                listOf(requireNotNull(entryResult.rejectionReason)).incrementReasons(noTradeReasonCounts)
                index += 1
                continue
            }
            val plannedExitIndex = minOf(entryIndex + config.maxHoldCandles, lastReplayIndex)
            val exit =
                simulateExit(
                    candles = sortedCandles,
                    side = signal.side,
                    entryIndex = entryIndex,
                    plannedExitIndex = plannedExitIndex,
                    entryPrice = entryPlan.entryPrice,
                    initialStopPrice = entryPlan.initialStopPrice,
                    riskPerUnit = entryPlan.riskPerUnit,
                    expectedR = entryPlan.expectedR,
                    quantity = entryPlan.quantity,
                    config = config,
                )
            val finalExitPrice = CausalReplay.applyExitSlippage(signal.side, exit.finalExitPrice, config.exitSlippageRate)
            val finalGrossPnl = grossPnl(signal.side, entryPlan.entryPrice, finalExitPrice, exit.remainingQuantity)
            val grossPnl = exit.partialGrossPnl + finalGrossPnl
            val entryFees = entryPlan.entryPrice * entryPlan.quantity * config.feeRate
            val finalFees = finalExitPrice * exit.remainingQuantity * config.feeRate
            val fees = entryFees + exit.partialFees + finalFees
            val fundingCost =
                fundingCost(
                    side = signal.side,
                    notional = entryPlan.entryPrice * entryPlan.quantity,
                    entryAt = entryCandle.openedAt,
                    exitAt = sortedCandles[exit.finalExitIndex].openedAt,
                    fundingRatePer8h = config.fundingRatePer8h,
                )
            val pnl = grossPnl - fees - fundingCost
            equity += pnl
            peakEquity = maxOf(peakEquity, equity)
            maxDrawdownPct = maxOf(maxDrawdownPct, ((peakEquity - equity) / peakEquity) * 100.0)
            trades +=
                BacktestTrade(
                    side = signal.side,
                    signalAt = decisionCandle.openedAt,
                    entryAt = entryCandle.openedAt,
                    exitAt = sortedCandles[exit.finalExitIndex].openedAt,
                    entryPrice = entryPlan.entryPrice,
                    initialStopPrice = entryPlan.initialStopPrice,
                    targetPrice = exit.targetPrice,
                    exitTriggerPrice = exit.finalExitPrice,
                    exitPrice = finalExitPrice,
                    quantity = entryPlan.quantity,
                    remainingQuantity = exit.remainingQuantity,
                    grossPnl = grossPnl,
                    fees = fees,
                    fundingCost = fundingCost,
                    pnl = pnl,
                    returnR = pnl / entryPlan.riskAmount,
                    equityAfter = equity,
                    exitReason = exit.finalExitReason,
                    partialTakeProfitAt = exit.partialTakeProfitAt,
                    partialExitPrice = exit.partialExitPrice,
                    partialQuantity = exit.partialQuantity,
                )
            tradesByUtcDay[entryUtcDay] = dayTrades + 1
            index = exit.finalExitIndex + 1
        }

        return resultFromTrades(
            candles = replayCandles,
            config = config,
            finalEquity = equity,
            maxDrawdownPct = maxDrawdownPct,
            trades = trades,
            evaluatedWindows = evaluatedWindows,
            skippedSignals = skippedSignals,
            noTradeReasonCounts = noTradeReasonCounts.toSortedMap(),
        )
    }

    private fun simulateExit(
        candles: List<Candle>,
        side: Side,
        entryIndex: Int,
        plannedExitIndex: Int,
        entryPrice: Double,
        initialStopPrice: Double,
        riskPerUnit: Double,
        expectedR: Double,
        quantity: Double,
        config: BacktestConfig,
    ): SimulatedExit {
        val policy =
            CausalPositionPolicy(
                CausalPositionPolicyConfig(
                    feeRate = config.feeRate,
                    partialTakeProfitR = config.partialTakeProfitR,
                    partialTakeProfitFraction = config.partialTakeProfitFraction,
                    breakevenAfterPartialTakeProfit = config.breakevenAfterPartialTakeProfit,
                    atrTrailingMultiplier = config.atrTrailingMultiplier,
                    fixedTargetEnabled = config.fixedTargetEnabled,
                    maxHoldCandles = config.maxHoldCandles,
                ),
            )
        var state =
            policy.open(
                CausalPositionOpenRequest(
                    side = side,
                    entryAt = candles[entryIndex].openedAt,
                    entryPrice = entryPrice,
                    initialStopPrice = initialStopPrice,
                    riskPerUnit = riskPerUnit,
                    expectedR = expectedR,
                    quantity = quantity,
                ),
            )
        for (index in entryIndex..plannedExitIndex) {
            val candle = candles[index]
            val step =
                policy.onCandle(
                    state = state,
                    candle = candle,
                    trailingAtr =
                        CausalPositionPolicy.trailingAtr(
                            candles = candles,
                            currentIndex = index,
                            period = config.atrTrailingPeriod,
                        ),
                )
            state = step.state
            step.exit?.let { exit ->
                return SimulatedExit(
                    finalExitIndex = index,
                    finalExitPrice = exit.triggerPrice,
                    finalExitReason = exit.reason.toBacktestExitReason(),
                    remainingQuantity = exit.remainingQuantity,
                    partialTakeProfitAt = state.partialTakeProfitAt,
                    partialExitPrice = state.partialExitPrice,
                    partialQuantity = state.partialQuantity,
                    partialGrossPnl = state.partialGrossPnl,
                    partialFees = state.partialFees,
                    targetPrice = state.fullTargetPrice,
                )
            }
        }

        return SimulatedExit(
            finalExitIndex = plannedExitIndex,
            finalExitPrice = candles[plannedExitIndex].close.toDouble(),
            finalExitReason = BacktestExitReason.TIME,
            remainingQuantity = state.remainingQuantity,
            partialTakeProfitAt = state.partialTakeProfitAt,
            partialExitPrice = state.partialExitPrice,
            partialQuantity = state.partialQuantity,
            partialGrossPnl = state.partialGrossPnl,
            partialFees = state.partialFees,
            targetPrice = state.fullTargetPrice,
        )
    }

    private fun grossPnl(
        side: Side,
        entryPrice: Double,
        exitPrice: Double,
        quantity: Double,
    ): Double =
        when (side) {
            Side.BUY -> (exitPrice - entryPrice) * quantity
            Side.SELL -> (entryPrice - exitPrice) * quantity
        }

    private fun fundingCost(
        side: Side,
        notional: Double,
        entryAt: Instant,
        exitAt: Instant,
        fundingRatePer8h: Double,
    ): Double {
        if (fundingRatePer8h == 0.0) return 0.0
        val heldHours = ChronoUnit.SECONDS.between(entryAt, exitAt) / 3_600.0
        val sideMultiplier =
            when (side) {
                Side.BUY -> 1.0
                Side.SELL -> -1.0
            }
        return notional * fundingRatePer8h * (heldHours / 8.0) * sideMultiplier
    }

    private fun List<String>.incrementReasons(counts: MutableMap<String, Int>) {
        val reasons = if (isEmpty()) listOf("NO_REASON") else this
        reasons.forEach { reason ->
            counts[reason] = (counts[reason] ?: 0) + 1
        }
    }

    private fun emptyResult(
        candles: List<Candle>,
        config: BacktestConfig,
    ): BacktestResult =
        BacktestResult(
            symbol = candles.firstOrNull()?.symbol ?: Symbol("BTCUSDT"),
            timeframe = candles.firstOrNull()?.timeframe ?: Timeframe.M15,
            candleCount = candles.size,
            startAt = candles.firstOrNull()?.openedAt,
            endAt = candles.lastOrNull()?.openedAt,
            initialEquity = config.initialEquity,
            finalEquity = config.initialEquity,
            grossPnl = 0.0,
            fees = 0.0,
            fundingCost = 0.0,
            netPnl = 0.0,
            netReturnPct = 0.0,
            expectedMonthlyReturnPct = null,
            maxDrawdownPct = 0.0,
            trades = emptyList(),
            wins = 0,
            losses = 0,
            maxConsecutiveLosses = 0,
            winRatePct = 0.0,
            profitFactor = null,
            expectancyR = 0.0,
            evaluatedWindows = 0,
            acceptedSignals = 0,
            skippedSignals = 0,
            noTradeReasonCounts = emptyMap(),
        )

    private fun resultFromTrades(
        candles: List<Candle>,
        config: BacktestConfig,
        finalEquity: Double,
        maxDrawdownPct: Double,
        trades: List<BacktestTrade>,
        evaluatedWindows: Int,
        skippedSignals: Int,
        noTradeReasonCounts: Map<String, Int>,
    ): BacktestResult {
        val wins = trades.count { it.pnl > 0.0 }
        val losses = trades.count { it.pnl < 0.0 }
        val grossProfit = trades.filter { it.pnl > 0.0 }.sumOf { it.pnl }
        val grossLoss = trades.filter { it.pnl < 0.0 }.sumOf { abs(it.pnl) }
        val grossPnl = trades.sumOf { it.grossPnl }
        val fees = trades.sumOf { it.fees }
        val fundingCost = trades.sumOf { it.fundingCost }
        val netPnl = finalEquity - config.initialEquity
        val netReturnPct = (netPnl / config.initialEquity) * 100.0
        val periodDays =
            ChronoUnit.SECONDS.between(candles.first().openedAt, candles.last().openedAt) / 86_400.0
        val expectedMonthlyReturnPct =
            if (periodDays > 0.0 && finalEquity > 0.0) {
                ((finalEquity / config.initialEquity).pow(30.0 / periodDays) - 1.0) * 100.0
            } else {
                null
            }

        return BacktestResult(
            symbol = candles.first().symbol,
            timeframe = candles.first().timeframe,
            candleCount = candles.size,
            startAt = candles.first().openedAt,
            endAt = candles.last().openedAt,
            initialEquity = config.initialEquity,
            finalEquity = finalEquity,
            grossPnl = grossPnl,
            fees = fees,
            fundingCost = fundingCost,
            netPnl = netPnl,
            netReturnPct = netReturnPct,
            expectedMonthlyReturnPct = expectedMonthlyReturnPct,
            maxDrawdownPct = maxDrawdownPct,
            trades = trades,
            wins = wins,
            losses = losses,
            maxConsecutiveLosses = trades.maxConsecutiveLosses(),
            winRatePct = if (trades.isEmpty()) 0.0 else (wins.toDouble() / trades.size) * 100.0,
            profitFactor = if (grossLoss == 0.0) null else grossProfit / grossLoss,
            expectancyR = if (trades.isEmpty()) 0.0 else trades.map { it.returnR }.average(),
            evaluatedWindows = evaluatedWindows,
            acceptedSignals = trades.size,
            skippedSignals = skippedSignals,
            noTradeReasonCounts = noTradeReasonCounts,
        )
    }

    private fun List<BacktestTrade>.maxConsecutiveLosses(): Int {
        var current = 0
        var max = 0
        forEach { trade ->
            if (trade.pnl < 0.0) {
                current += 1
                max = maxOf(max, current)
            } else {
                current = 0
            }
        }
        return max
    }
}

private fun CausalPositionExitReason.toBacktestExitReason(): BacktestExitReason =
    when (this) {
        CausalPositionExitReason.TARGET -> BacktestExitReason.TARGET
        CausalPositionExitReason.STOP -> BacktestExitReason.STOP
        CausalPositionExitReason.BREAKEVEN_STOP -> BacktestExitReason.BREAKEVEN_STOP
        CausalPositionExitReason.TRAILING_STOP -> BacktestExitReason.TRAILING_STOP
        CausalPositionExitReason.TIME -> BacktestExitReason.TIME
    }

private data class SimulatedExit(
    val finalExitIndex: Int,
    val finalExitPrice: Double,
    val finalExitReason: BacktestExitReason,
    val remainingQuantity: Double,
    val partialTakeProfitAt: Instant?,
    val partialExitPrice: Double?,
    val partialQuantity: Double,
    val partialGrossPnl: Double,
    val partialFees: Double,
    val targetPrice: Double?,
)
