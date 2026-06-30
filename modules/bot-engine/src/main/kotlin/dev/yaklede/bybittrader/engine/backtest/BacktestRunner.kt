package dev.yaklede.bybittrader.engine.backtest

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.strategy.TradingStrategy
import java.time.Instant
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

        if (sortedCandles.size < strategy.warmupCandles + 2) {
            return emptyResult(sortedCandles, config)
        }

        var equity = config.initialEquity
        var peakEquity = equity
        var maxDrawdownPct = 0.0
        var evaluatedWindows = 0
        var skippedSignals = 0
        val noTradeReasonCounts = mutableMapOf<String, Int>()
        val trades = mutableListOf<BacktestTrade>()
        var index = strategy.warmupCandles

        while (index < sortedCandles.lastIndex) {
            evaluatedWindows += 1
            val decision = strategy.evaluate(sortedCandles.subList(0, index + 1))
            val signal = decision.intent
            if (signal == null) {
                skippedSignals += 1
                decision.reasonCodes.incrementReasons(noTradeReasonCounts)
                index += 1
                continue
            }

            val entryIndex = index + 1
            val entryCandle = sortedCandles[entryIndex]
            val rawEntryPrice = entryCandle.open.toDouble()
            val entryPrice =
                when (signal.side) {
                    Side.BUY -> rawEntryPrice * (1.0 + config.slippageRate)
                    Side.SELL -> rawEntryPrice * (1.0 - config.slippageRate)
                }
            val initialStopPrice = signal.invalidationPrice.value.toDouble()
            val riskPerUnit = abs(entryPrice - initialStopPrice)
            if (riskPerUnit <= 0.0) {
                skippedSignals += 1
                listOf("INVALID_RISK_DISTANCE").incrementReasons(noTradeReasonCounts)
                index += 1
                continue
            }

            val riskAmount = equity * config.riskFraction
            val quantity = riskAmount / riskPerUnit
            val plannedExitIndex = minOf(entryIndex + config.maxHoldCandles, sortedCandles.lastIndex)
            val exit =
                simulateExit(
                    candles = sortedCandles,
                    side = signal.side,
                    entryIndex = entryIndex,
                    plannedExitIndex = plannedExitIndex,
                    entryPrice = entryPrice,
                    initialStopPrice = initialStopPrice,
                    riskPerUnit = riskPerUnit,
                    expectedR = signal.expectedR.toDouble(),
                    quantity = quantity,
                    config = config,
                )
            val finalExitPrice =
                when (signal.side) {
                    Side.BUY -> exit.finalExitPrice * (1.0 - config.slippageRate)
                    Side.SELL -> exit.finalExitPrice * (1.0 + config.slippageRate)
                }
            val finalGrossPnl = grossPnl(signal.side, entryPrice, finalExitPrice, exit.remainingQuantity)
            val grossPnl = exit.partialGrossPnl + finalGrossPnl
            val entryFees = entryPrice * quantity * config.feeRate
            val finalFees = finalExitPrice * exit.remainingQuantity * config.feeRate
            val fees = entryFees + exit.partialFees + finalFees
            val fundingCost =
                fundingCost(
                    side = signal.side,
                    notional = entryPrice * quantity,
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
                    entryAt = entryCandle.openedAt,
                    exitAt = sortedCandles[exit.finalExitIndex].openedAt,
                    entryPrice = entryPrice,
                    exitPrice = finalExitPrice,
                    quantity = quantity,
                    remainingQuantity = exit.remainingQuantity,
                    grossPnl = grossPnl,
                    fees = fees,
                    fundingCost = fundingCost,
                    pnl = pnl,
                    returnR = pnl / riskAmount,
                    exitReason = exit.finalExitReason,
                    partialTakeProfitAt = exit.partialTakeProfitAt,
                    partialExitPrice = exit.partialExitPrice,
                    partialQuantity = exit.partialQuantity,
                )
            index = exit.finalExitIndex + 1
        }

        return resultFromTrades(
            candles = sortedCandles,
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
        val fullTargetPrice = targetPrice(side, entryPrice, riskPerUnit, expectedR)
        val partialTargetPrice = targetPrice(side, entryPrice, riskPerUnit, config.partialTakeProfitR)
        var stopPrice = initialStopPrice
        var partialTaken = false
        var partialTakeProfitAt: Instant? = null
        var partialExitPrice: Double? = null
        var partialQuantity = 0.0
        var partialGrossPnl = 0.0
        var partialFees = 0.0
        var remainingQuantity = quantity

        for (index in entryIndex..plannedExitIndex) {
            val candle = candles[index]
            if (config.atrTrailingMultiplier > 0.0) {
                trailingStopPrice(candles, side, index, config)?.let { candidate ->
                    stopPrice =
                        when (side) {
                            Side.BUY -> maxOf(stopPrice, candidate)
                            Side.SELL -> minOf(stopPrice, candidate)
                        }
                }
            }

            val low = candle.low.toDouble()
            val high = candle.high.toDouble()
            val stopHit =
                when (side) {
                    Side.BUY -> low <= stopPrice
                    Side.SELL -> high >= stopPrice
                }
            if (stopHit) {
                return SimulatedExit(
                    finalExitIndex = index,
                    finalExitPrice = stopPrice,
                    finalExitReason =
                        when {
                            stopPrice.isCloseTo(initialStopPrice) -> BacktestExitReason.STOP
                            partialTaken && stopPrice.isCloseTo(entryPrice) -> BacktestExitReason.BREAKEVEN_STOP
                            else -> BacktestExitReason.TRAILING_STOP
                        },
                    remainingQuantity = remainingQuantity,
                    partialTakeProfitAt = partialTakeProfitAt,
                    partialExitPrice = partialExitPrice,
                    partialQuantity = partialQuantity,
                    partialGrossPnl = partialGrossPnl,
                    partialFees = partialFees,
                )
            }

            if (!partialTaken && config.partialTakeProfitFraction > 0.0 && candle.touches(side, partialTargetPrice)) {
                partialTaken = true
                partialTakeProfitAt = candle.openedAt
                partialExitPrice = partialTargetPrice
                partialQuantity = quantity * config.partialTakeProfitFraction
                remainingQuantity -= partialQuantity
                partialGrossPnl = grossPnl(side, entryPrice, partialTargetPrice, partialQuantity)
                partialFees = partialTargetPrice * partialQuantity * config.feeRate
                if (config.breakevenAfterPartialTakeProfit) {
                    stopPrice =
                        when (side) {
                            Side.BUY -> maxOf(stopPrice, entryPrice)
                            Side.SELL -> minOf(stopPrice, entryPrice)
                        }
                }
            }

            if (candle.touches(side, fullTargetPrice)) {
                return SimulatedExit(
                    finalExitIndex = index,
                    finalExitPrice = fullTargetPrice,
                    finalExitReason = BacktestExitReason.TARGET,
                    remainingQuantity = remainingQuantity,
                    partialTakeProfitAt = partialTakeProfitAt,
                    partialExitPrice = partialExitPrice,
                    partialQuantity = partialQuantity,
                    partialGrossPnl = partialGrossPnl,
                    partialFees = partialFees,
                )
            }
        }

        return SimulatedExit(
            finalExitIndex = plannedExitIndex,
            finalExitPrice = candles[plannedExitIndex].close.toDouble(),
            finalExitReason = BacktestExitReason.TIME,
            remainingQuantity = remainingQuantity,
            partialTakeProfitAt = partialTakeProfitAt,
            partialExitPrice = partialExitPrice,
            partialQuantity = partialQuantity,
            partialGrossPnl = partialGrossPnl,
            partialFees = partialFees,
        )
    }

    private fun targetPrice(
        side: Side,
        entryPrice: Double,
        riskPerUnit: Double,
        targetR: Double,
    ): Double =
        when (side) {
            Side.BUY -> entryPrice + (riskPerUnit * targetR)
            Side.SELL -> entryPrice - (riskPerUnit * targetR)
        }

    private fun Candle.touches(
        side: Side,
        price: Double,
    ): Boolean =
        when (side) {
            Side.BUY -> high.toDouble() >= price
            Side.SELL -> low.toDouble() <= price
        }

    private fun trailingStopPrice(
        candles: List<Candle>,
        side: Side,
        index: Int,
        config: BacktestConfig,
    ): Double? {
        if (index <= config.atrTrailingPeriod) return null
        val window = candles.subList(index - config.atrTrailingPeriod, index + 1)
        val trueRanges =
            window.zipWithNext().map { (previous, current) ->
                val high = current.high.toDouble()
                val low = current.low.toDouble()
                val previousClose = previous.close.toDouble()
                maxOf(
                    high - low,
                    abs(high - previousClose),
                    abs(low - previousClose),
                )
            }
        val atr = trueRanges.average()
        val close = candles[index].close.toDouble()
        return when (side) {
            Side.BUY -> close - (atr * config.atrTrailingMultiplier)
            Side.SELL -> close + (atr * config.atrTrailingMultiplier)
        }
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

    private fun Double.isCloseTo(other: Double): Boolean = abs(this - other) < 0.00000001
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
)
