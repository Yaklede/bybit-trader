package dev.yaklede.bybittrader.engine.backtest

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.strategy.TradingStrategy
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
        val trades = mutableListOf<BacktestTrade>()
        var index = strategy.warmupCandles

        while (index < sortedCandles.lastIndex) {
            val signal = strategy.evaluate(sortedCandles.subList(0, index + 1)).intent
            if (signal == null) {
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
            val stopPrice = signal.invalidationPrice.value.toDouble()
            val riskPerUnit = abs(entryPrice - stopPrice)
            if (riskPerUnit <= 0.0) {
                index += 1
                continue
            }

            val riskAmount = equity * config.riskFraction
            val quantity = riskAmount / riskPerUnit
            val targetPrice =
                when (signal.side) {
                    Side.BUY -> entryPrice + (riskPerUnit * signal.expectedR.toDouble())
                    Side.SELL -> entryPrice - (riskPerUnit * signal.expectedR.toDouble())
                }
            val plannedExitIndex = minOf(entryIndex + config.maxHoldCandles, sortedCandles.lastIndex)
            val exit = findExit(sortedCandles, signal.side, entryIndex, plannedExitIndex, stopPrice, targetPrice)
            val slippedExitPrice =
                when (signal.side) {
                    Side.BUY -> exit.price * (1.0 - config.slippageRate)
                    Side.SELL -> exit.price * (1.0 + config.slippageRate)
                }
            val grossPnl =
                when (signal.side) {
                    Side.BUY -> (slippedExitPrice - entryPrice) * quantity
                    Side.SELL -> (entryPrice - slippedExitPrice) * quantity
                }
            val fees = ((entryPrice * quantity) + (slippedExitPrice * quantity)) * config.feeRate
            val pnl = grossPnl - fees
            equity += pnl
            peakEquity = maxOf(peakEquity, equity)
            maxDrawdownPct = maxOf(maxDrawdownPct, ((peakEquity - equity) / peakEquity) * 100.0)
            trades +=
                BacktestTrade(
                    side = signal.side,
                    entryAt = entryCandle.openedAt,
                    exitAt = sortedCandles[exit.index].openedAt,
                    entryPrice = entryPrice,
                    exitPrice = slippedExitPrice,
                    quantity = quantity,
                    pnl = pnl,
                    returnR = pnl / riskAmount,
                    exitReason = exit.reason,
                )
            index = exit.index + 1
        }

        return resultFromTrades(sortedCandles, config, equity, maxDrawdownPct, trades)
    }

    private fun findExit(
        candles: List<Candle>,
        side: Side,
        entryIndex: Int,
        plannedExitIndex: Int,
        stopPrice: Double,
        targetPrice: Double,
    ): ExitCandidate {
        for (index in entryIndex..plannedExitIndex) {
            val candle = candles[index]
            val low = candle.low.toDouble()
            val high = candle.high.toDouble()
            when (side) {
                Side.BUY -> {
                    if (low <= stopPrice) return ExitCandidate(index, stopPrice, BacktestExitReason.STOP)
                    if (high >= targetPrice) return ExitCandidate(index, targetPrice, BacktestExitReason.TARGET)
                }

                Side.SELL -> {
                    if (high >= stopPrice) return ExitCandidate(index, stopPrice, BacktestExitReason.STOP)
                    if (low <= targetPrice) return ExitCandidate(index, targetPrice, BacktestExitReason.TARGET)
                }
            }
        }
        return ExitCandidate(
            index = plannedExitIndex,
            price = candles[plannedExitIndex].close.toDouble(),
            reason = BacktestExitReason.TIME,
        )
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
            netPnl = 0.0,
            netReturnPct = 0.0,
            expectedMonthlyReturnPct = null,
            maxDrawdownPct = 0.0,
            trades = emptyList(),
            wins = 0,
            losses = 0,
            winRatePct = 0.0,
            profitFactor = null,
            expectancyR = 0.0,
        )

    private fun resultFromTrades(
        candles: List<Candle>,
        config: BacktestConfig,
        finalEquity: Double,
        maxDrawdownPct: Double,
        trades: List<BacktestTrade>,
    ): BacktestResult {
        val wins = trades.count { it.pnl > 0.0 }
        val losses = trades.count { it.pnl < 0.0 }
        val grossProfit = trades.filter { it.pnl > 0.0 }.sumOf { it.pnl }
        val grossLoss = trades.filter { it.pnl < 0.0 }.sumOf { abs(it.pnl) }
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
            netPnl = netPnl,
            netReturnPct = netReturnPct,
            expectedMonthlyReturnPct = expectedMonthlyReturnPct,
            maxDrawdownPct = maxDrawdownPct,
            trades = trades,
            wins = wins,
            losses = losses,
            winRatePct = if (trades.isEmpty()) 0.0 else (wins.toDouble() / trades.size) * 100.0,
            profitFactor = if (grossLoss == 0.0) null else grossProfit / grossLoss,
            expectancyR = if (trades.isEmpty()) 0.0 else trades.map { it.returnR }.average(),
        )
    }
}

private data class ExitCandidate(
    val index: Int,
    val price: Double,
    val reason: BacktestExitReason,
)
