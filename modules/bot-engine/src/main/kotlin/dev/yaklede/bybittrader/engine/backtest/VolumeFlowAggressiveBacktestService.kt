package dev.yaklede.bybittrader.engine.backtest

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.ResearchCandleLimits
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.market.MarketCandleStore
import dev.yaklede.bybittrader.strategy.volume.VolumeFlowIndicators
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt

private const val AGGRESSIVE_WARMUP_CANDLES = 60
private const val MIN_ENTRY_RISK_PCT = 0.002
private const val MAX_ENTRY_RISK_PCT = 0.035

class VolumeFlowAggressiveBacktestService(
    private val candleStore: MarketCandleStore,
) {
    suspend fun run(
        symbol: Symbol,
        m5Limit: Int,
        config: VolumeFlowAggressiveBacktestConfig = VolumeFlowAggressiveProfiles.finalUsV1(),
        replayStartAt: Instant? = null,
        replayEndAt: Instant? = null,
    ): VolumeFlowAggressiveBacktestReport {
        require(m5Limit in 30..ResearchCandleLimits.MAX_M5_REPLAY_CANDLES) {
            "M5 limit must be between 30 and ${ResearchCandleLimits.MAX_M5_REPLAY_CANDLES}."
        }
        require((replayStartAt == null) == (replayEndAt == null)) {
            "Replay start and end timestamps must both be set or both be omitted."
        }
        require(replayStartAt == null || replayEndAt == null || replayEndAt.isAfter(replayStartAt)) {
            "Replay end timestamp must be after replay start timestamp."
        }
        val candles =
            if (replayStartAt != null && replayEndAt != null) {
                candleStore.candlesBetween(symbol, Timeframe.M5, replayStartAt, replayEndAt, m5Limit)
            } else {
                candleStore.recentCandles(symbol, Timeframe.M5, m5Limit)
            }.sortedBy { it.openedAt }
        require(candles.size >= AGGRESSIVE_WARMUP_CANDLES + config.maxHoldCandles + 2) {
            "Not enough M5 candles for aggressive absorption replay."
        }
        return runLoadedCandles(symbol, candles, config, replayStartAt, replayEndAt)
    }

    internal fun runLoadedCandles(
        symbol: Symbol,
        candles: List<Candle>,
        config: VolumeFlowAggressiveBacktestConfig = VolumeFlowAggressiveProfiles.finalUsV1(),
        replayStartAt: Instant? = null,
        replayEndAt: Instant? = null,
    ): VolumeFlowAggressiveBacktestReport {
        val enriched = candles.enriched(config)
        var equity = config.initialEquity
        var peakEquity = equity
        var maxDrawdownPct = 0.0
        var wins = 0
        var losses = 0
        var grossProfit = 0.0
        var grossLoss = 0.0
        var skippedSignalCount = 0
        val activeDays = linkedSetOf<String>()
        val tradesByDay = mutableMapOf<String, Int>()
        val trades = mutableListOf<VolumeFlowAggressiveBacktestTrade>()

        var index = AGGRESSIVE_WARMUP_CANDLES
        val replayEndIndex = enriched.size
        while (index < replayEndIndex - config.maxHoldCandles - 2) {
            val setup = findSetup(enriched, index, replayEndIndex, config)
            if (setup == null) {
                index += 1
                continue
            }
            val dayTradeCount = tradesByDay[setup.entry.day] ?: 0
            if (dayTradeCount >= config.maxTradesPerDay) {
                index += 1
                continue
            }
            val riskAmount = equity * config.riskFraction
            val quantity = calculateQuantity(equity, setup, config)
            if (quantity == null) {
                skippedSignalCount += 1
                index += 1
                continue
            }
            val exit = simulateExit(enriched, setup, replayEndIndex, config)
            val grossPnl =
                when (setup.side) {
                    Side.BUY -> (exit.exitPrice - setup.entryPrice) * quantity
                    Side.SELL -> (setup.entryPrice - exit.exitPrice) * quantity
                }
            val fees = ((setup.entryPrice + exit.exitPrice) * quantity) * config.feeRate
            val pnl = grossPnl - fees
            val equityBefore = equity
            equity += pnl
            peakEquity = maxOf(peakEquity, equity)
            val drawdownPct = ((peakEquity - equity) / peakEquity) * 100.0
            maxDrawdownPct = maxOf(maxDrawdownPct, drawdownPct)
            if (pnl > 0.0) {
                wins += 1
                grossProfit += pnl
            } else {
                losses += 1
                grossLoss += abs(pnl)
            }
            activeDays += setup.entry.day
            tradesByDay[setup.entry.day] = dayTradeCount + 1
            trades +=
                VolumeFlowAggressiveBacktestTrade(
                    openedAt = setup.entry.candle.openedAt,
                    closedAt = enriched[exit.exitIndex].candle.openedAt,
                    side = setup.side,
                    exitReason = exit.reason,
                    entryPrice = setup.entryPrice,
                    stopPrice = setup.stopPrice,
                    targetPrice = setup.targetPrice,
                    exitPrice = exit.exitPrice,
                    riskPerUnit = setup.riskPerUnit,
                    riskFraction = config.riskFraction,
                    quantity = quantity,
                    notional = setup.entryPrice * quantity,
                    stopAtr = setup.stopAtr,
                    targetR = setup.targetR,
                    rMultipleGross = grossPnl / riskAmount,
                    rMultipleNet = pnl / riskAmount,
                    pnl = pnl,
                    returnPct = (pnl / equityBefore) * 100.0,
                    equityAfter = equity,
                    drawdownPct = drawdownPct,
                    entryRelativeVolume = setup.entry.relativeVolume ?: 0.0,
                    entryRangePct = setup.entry.rangePct,
                    entryBodyRatio = setup.entry.bodyRatio,
                    entryCloseLocation = setup.entry.closeLocation,
                )
            index = exit.exitIndex + 1
        }

        val startAt = replayStartAt ?: candles.firstOrNull()?.openedAt
        val endAt = replayEndAt ?: candles.lastOrNull()?.openedAt
        val observedDays = observedDaysBetween(startAt, endAt)
        val netPnl = equity - config.initialEquity
        val compoundDailyReturnPct =
            if (equity > 0.0 && observedDays > 0) {
                ((equity / config.initialEquity).pow(1.0 / observedDays) - 1.0) * 100.0
            } else {
                -100.0
            }
        return VolumeFlowAggressiveBacktestReport(
            symbol = symbol,
            profileId = config.profileId,
            m5CandleCount = candles.size,
            startAt = startAt,
            endAt = endAt,
            initialEquity = config.initialEquity,
            finalEquity = equity,
            netPnl = netPnl,
            netReturnPct = (netPnl / config.initialEquity) * 100.0,
            compoundDailyReturnPct = compoundDailyReturnPct,
            maxDrawdownPct = maxDrawdownPct,
            tradeCount = trades.size,
            activeDays = activeDays.size,
            observedDays = observedDays,
            activeDayCoveragePct = if (observedDays == 0) 0.0 else (activeDays.size.toDouble() / observedDays) * 100.0,
            skippedSignalCount = skippedSignalCount,
            wins = wins,
            losses = losses,
            winRatePct = if (trades.isEmpty()) 0.0 else (wins.toDouble() / trades.size) * 100.0,
            profitFactor =
                when {
                    grossLoss > 0.0 -> grossProfit / grossLoss
                    grossProfit > 0.0 -> 999.0
                    else -> null
                },
            trades = trades,
        )
    }

    private fun calculateQuantity(
        equity: Double,
        setup: AggressiveSetup,
        config: VolumeFlowAggressiveBacktestConfig,
    ): Double? {
        if (setup.riskPerUnit <= 0.0) return null
        var quantity = (equity * config.riskFraction) / setup.riskPerUnit
        config.maxQuantity?.let { maxQuantity ->
            quantity = minOf(quantity, maxQuantity)
        }
        val leverageMaxNotional = config.leverage?.let { equity * it }
        val effectiveMaxNotional =
            listOfNotNull(config.maxNotional, leverageMaxNotional)
                .minOrNull()
        effectiveMaxNotional?.let { maxNotional ->
            quantity = minOf(quantity, maxNotional / setup.entryPrice)
        }
        config.quantityStep?.let { quantityStep ->
            quantity = quantity.floorToStep(quantityStep)
        }
        val minQuantity = config.minQuantity
        return if (minQuantity == null || quantity >= minQuantity) quantity else null
    }

    private fun findSetup(
        candles: List<AggressiveCandle>,
        index: Int,
        replayEndIndex: Int,
        config: VolumeFlowAggressiveBacktestConfig,
    ): AggressiveSetup? {
        val candle = candles[index]
        if (!config.sessionHoursUtc.contains(candle.hourUtc)) return null
        val atr = candle.atr ?: return null
        if (atr <= 0.0) return null
        val relativeVolume = candle.relativeVolume ?: return null
        if (relativeVolume < config.relativeVolumeMin) return null
        val cluster = clusterRange(candles, index - config.clusterCandles + 1, index + 1) ?: return null
        val averageVolume = candle.avgVolume ?: return null
        if (averageVolume <= 0.0) return null
        val clusterVolumeRatio = cluster.volume / (averageVolume * config.clusterCandles)
        if (clusterVolumeRatio < config.clusterVolumeMin) return null
        val firstClusterOpen = candles[index - config.clusterCandles + 1].candle.open.toDouble()
        val displacementAtr = abs(candle.candle.close.toDouble() - firstClusterOpen) / atr
        val rangeAtr = (cluster.high - cluster.low) / atr
        if (displacementAtr > config.maxDisplacementAtr || rangeAtr > config.maxRangeAtr) return null

        val latestEntry = minOf(index + config.entryLookaheadCandles, replayEndIndex - 1)
        for (entryIndex in index + 1..latestEntry) {
            val entry = candles[entryIndex]
            if (!config.sessionHoursUtc.contains(entry.hourUtc)) continue
            if (config.sideMode != VolumeFlowSideMode.SHORT_ONLY && entry.candle.close.toDouble() > cluster.high) {
                return buildSetup(candles, Side.BUY, entryIndex, cluster, config)
            }
            if (config.sideMode != VolumeFlowSideMode.LONG_ONLY && entry.candle.close.toDouble() < cluster.low) {
                return buildSetup(candles, Side.SELL, entryIndex, cluster, config)
            }
        }
        return null
    }

    private fun buildSetup(
        candles: List<AggressiveCandle>,
        side: Side,
        entryIndex: Int,
        cluster: AggressiveCluster,
        config: VolumeFlowAggressiveBacktestConfig,
    ): AggressiveSetup? {
        if (!sideAllowedForRegime(candles, side, entryIndex, config)) return null
        val entry = candles[entryIndex]
        val entryOpen = entry.candle.open.toDouble()
        val entryPrice =
            when (side) {
                Side.BUY -> entryOpen * (1.0 + config.slippageRate)
                Side.SELL -> entryOpen * (1.0 - config.slippageRate)
            }
        val stopAtr = stopAtrFor(candles, entryIndex, config)
        val atr = entry.atr ?: return null
        val atrStop = atr * stopAtr
        val structuralStop =
            when (side) {
                Side.BUY -> cluster.low
                Side.SELL -> cluster.high
            }
        val stopPrice =
            when (side) {
                Side.BUY -> minOf(structuralStop, entryPrice - atrStop)
                Side.SELL -> maxOf(structuralStop, entryPrice + atrStop)
            }
        val riskPerUnit = abs(entryPrice - stopPrice)
        val entryRiskPct = riskPerUnit / entryPrice
        if (riskPerUnit <= 0.0 || entryRiskPct < MIN_ENTRY_RISK_PCT || entryRiskPct > MAX_ENTRY_RISK_PCT) return null
        val targetR = targetRFor(candles, entryIndex, config)
        val targetPrice =
            when (side) {
                Side.BUY -> entryPrice + (riskPerUnit * targetR)
                Side.SELL -> entryPrice - (riskPerUnit * targetR)
            }
        return AggressiveSetup(
            side = side,
            entryIndex = entryIndex,
            entry = entry,
            entryPrice = entryPrice,
            stopPrice = stopPrice,
            targetPrice = targetPrice,
            riskPerUnit = riskPerUnit,
            stopAtr = stopAtr,
            targetR = targetR,
        )
    }

    private fun sideAllowedForRegime(
        candles: List<AggressiveCandle>,
        side: Side,
        entryIndex: Int,
        config: VolumeFlowAggressiveBacktestConfig,
    ): Boolean {
        for (rule in config.sideRegimeBlocks) {
            if (rule.side != side) continue
            val stats = priorStatsFor(candles, entryIndex, rule.lookbackCandles) ?: continue
            if (rule.returnMinPct != null && stats.returnPct < rule.returnMinPct) continue
            if (rule.returnMaxPct != null && stats.returnPct >= rule.returnMaxPct) continue
            if (rule.avgVolumeMin != null && stats.avgVolume < rule.avgVolumeMin) continue
            if (rule.avgVolumeMax != null && stats.avgVolume >= rule.avgVolumeMax) continue
            if (rule.avgRangePctMin != null && stats.avgRangePct < rule.avgRangePctMin) continue
            if (rule.confirmLookbackCandles != null) {
                val confirmStats = priorStatsFor(candles, entryIndex, rule.confirmLookbackCandles) ?: continue
                if (rule.confirmReturnMinPct != null && confirmStats.returnPct < rule.confirmReturnMinPct) continue
                if (rule.confirmReturnMaxPct != null && confirmStats.returnPct >= rule.confirmReturnMaxPct) continue
                if (rule.confirmAvgVolumeMin != null && confirmStats.avgVolume < rule.confirmAvgVolumeMin) continue
                if (rule.confirmAvgVolumeMax != null && confirmStats.avgVolume >= rule.confirmAvgVolumeMax) continue
                if (rule.confirmAvgRangePctMin != null && confirmStats.avgRangePct < rule.confirmAvgRangePctMin) continue
            }
            return false
        }
        return true
    }

    private fun targetRFor(
        candles: List<AggressiveCandle>,
        entryIndex: Int,
        config: VolumeFlowAggressiveBacktestConfig,
    ): Double {
        val adaptive = config.adaptiveTarget ?: return config.targetR
        val stats = priorStatsFor(candles, entryIndex, adaptive.lookbackCandles) ?: return config.targetR
        if (stats.returnPct > adaptive.returnMaxPct) return config.targetR
        if (stats.avgVolume < adaptive.avgVolumeMin) return config.targetR
        if (stats.avgRangePct < adaptive.avgRangePctMin) return config.targetR
        return adaptive.targetR
    }

    private fun stopAtrFor(
        candles: List<AggressiveCandle>,
        entryIndex: Int,
        config: VolumeFlowAggressiveBacktestConfig,
    ): Double {
        val adaptive = config.adaptiveStop ?: return config.stopAtr
        val stats = priorStatsFor(candles, entryIndex, adaptive.lookbackCandles) ?: return config.stopAtr
        if (stats.returnPct < adaptive.returnMinPct) return config.stopAtr
        if (stats.avgVolume < adaptive.avgVolumeMin) return config.stopAtr
        if (stats.avgRangePct < adaptive.avgRangePctMin) return config.stopAtr
        return adaptive.stopAtr
    }

    private fun simulateExit(
        candles: List<AggressiveCandle>,
        setup: AggressiveSetup,
        replayEndIndex: Int,
        config: VolumeFlowAggressiveBacktestConfig,
    ): AggressiveExit {
        val end = minOf(setup.entryIndex + config.maxHoldCandles, replayEndIndex - 1)
        val liquidationPrice = approximateLiquidationPrice(setup, config)
        for (index in setup.entryIndex..end) {
            val candle = candles[index].candle
            if (setup.side == Side.BUY) {
                if (liquidationPrice != null && candle.low.toDouble() <= liquidationPrice) {
                    return AggressiveExit(index, liquidationPrice, VolumeFlowExitReason.LIQUIDATION)
                }
                if (candle.low.toDouble() <= setup.stopPrice) {
                    return AggressiveExit(index, setup.stopPrice, VolumeFlowExitReason.STOP)
                }
                if (candle.high.toDouble() >= setup.targetPrice) {
                    return AggressiveExit(index, setup.targetPrice, VolumeFlowExitReason.TARGET)
                }
            } else {
                if (liquidationPrice != null && candle.high.toDouble() >= liquidationPrice) {
                    return AggressiveExit(index, liquidationPrice, VolumeFlowExitReason.LIQUIDATION)
                }
                if (candle.high.toDouble() >= setup.stopPrice) {
                    return AggressiveExit(index, setup.stopPrice, VolumeFlowExitReason.STOP)
                }
                if (candle.low.toDouble() <= setup.targetPrice) {
                    return AggressiveExit(index, setup.targetPrice, VolumeFlowExitReason.TARGET)
                }
            }
        }
        return AggressiveExit(end, candles[end].candle.close.toDouble(), VolumeFlowExitReason.TIME)
    }

    private fun approximateLiquidationPrice(
        setup: AggressiveSetup,
        config: VolumeFlowAggressiveBacktestConfig,
    ): Double? {
        val leverage = config.leverage ?: return null
        val liquidationDistancePct = (100.0 / leverage) - config.liquidationBufferPct
        if (liquidationDistancePct <= 0.0) return setup.entryPrice
        val liquidationDistance = liquidationDistancePct / 100.0
        return when (setup.side) {
            Side.BUY -> setup.entryPrice * (1.0 - liquidationDistance)
            Side.SELL -> setup.entryPrice * (1.0 + liquidationDistance)
        }
    }
}

private fun List<Candle>.enriched(config: VolumeFlowAggressiveBacktestConfig): List<AggressiveCandle> {
    val result = ArrayList<AggressiveCandle>(size)
    val volumeQueue = ArrayDeque<Double>()
    val trueRangeQueue = ArrayDeque<Double>()
    var volumeSum = 0.0
    var trueRangeSum = 0.0
    var cumulativeVolume = 0.0
    var cumulativeRangePct = 0.0
    forEachIndexed { index, candle ->
        val previousClose = if (index > 0) this[index - 1].close.toDouble() else candle.close.toDouble()
        val trueRange =
            maxOf(
                candle.high.toDouble() - candle.low.toDouble(),
                abs(candle.high.toDouble() - previousClose),
                abs(candle.low.toDouble() - previousClose),
            )
        val avgVolume = if (volumeQueue.size == config.volumeLookback) volumeSum / config.volumeLookback else null
        val atr = if (trueRangeQueue.size == config.atrLookback) trueRangeSum / config.atrLookback else null
        val range = candle.high.toDouble() - candle.low.toDouble()
        val close = candle.close.toDouble()
        val rangePct = if (close > 0.0) (range / close) * 100.0 else 0.0
        cumulativeVolume += candle.volume.toDouble()
        cumulativeRangePct += rangePct
        val shape = VolumeFlowIndicators.candleShape(candle)
        result +=
            AggressiveCandle(
                candle = candle,
                index = index,
                hourUtc = candle.openedAt.atZone(ZoneOffset.UTC).hour,
                day =
                    candle
                        .openedAt
                        .atZone(ZoneOffset.UTC)
                        .toLocalDate()
                        .toString(),
                avgVolume = avgVolume,
                atr = atr,
                relativeVolume = if (avgVolume != null && avgVolume > 0.0) candle.volume.toDouble() / avgVolume else null,
                bodyRatio = shape.bodyRatio,
                closeLocation = shape.closeLocation,
                rangePct = rangePct,
                cumulativeVolume = cumulativeVolume,
                cumulativeRangePct = cumulativeRangePct,
            )
        volumeQueue += candle.volume.toDouble()
        volumeSum += candle.volume.toDouble()
        if (volumeQueue.size > config.volumeLookback) {
            volumeSum -= volumeQueue.removeFirst()
        }
        trueRangeQueue += trueRange
        trueRangeSum += trueRange
        if (trueRangeQueue.size > config.atrLookback) {
            trueRangeSum -= trueRangeQueue.removeFirst()
        }
    }
    return result
}

private fun clusterRange(
    candles: List<AggressiveCandle>,
    start: Int,
    end: Int,
): AggressiveCluster? {
    if (start < 0 || end > candles.size || start >= end) return null
    var high = Double.NEGATIVE_INFINITY
    var low = Double.POSITIVE_INFINITY
    var volume = 0.0
    for (index in start until end) {
        val candle = candles[index].candle
        high = maxOf(high, candle.high.toDouble())
        low = minOf(low, candle.low.toDouble())
        volume += candle.volume.toDouble()
    }
    return AggressiveCluster(high = high, low = low, volume = volume)
}

private fun priorStatsFor(
    candles: List<AggressiveCandle>,
    index: Int,
    lookbackCandles: Int,
): AggressivePriorStats? {
    val start = index - lookbackCandles
    if (start < 0 || start >= index) return null
    val first = candles[start]
    val last = candles[index - 1]
    val firstOpen = first.candle.open.toDouble()
    if (firstOpen <= 0.0) return null
    val previous = if (start > 0) candles[start - 1] else null
    val volume = last.cumulativeVolume - (previous?.cumulativeVolume ?: 0.0)
    val rangePct = last.cumulativeRangePct - (previous?.cumulativeRangePct ?: 0.0)
    return AggressivePriorStats(
        returnPct = ((last.candle.close.toDouble() / firstOpen) - 1.0) * 100.0,
        avgVolume = volume / lookbackCandles,
        avgRangePct = rangePct / lookbackCandles,
    )
}

private fun observedDaysBetween(
    startAt: Instant?,
    endAt: Instant?,
): Int {
    if (startAt == null || endAt == null) return 1
    val days = Duration.between(startAt, endAt).toMillis().toDouble() / 86_400_000.0
    return maxOf(1, days.roundToInt())
}

private data class AggressiveCandle(
    val candle: Candle,
    val index: Int,
    val hourUtc: Int,
    val day: String,
    val avgVolume: Double?,
    val atr: Double?,
    val relativeVolume: Double?,
    val bodyRatio: Double,
    val closeLocation: Double,
    val rangePct: Double,
    val cumulativeVolume: Double,
    val cumulativeRangePct: Double,
)

private data class AggressiveCluster(
    val high: Double,
    val low: Double,
    val volume: Double,
)

private data class AggressiveSetup(
    val side: Side,
    val entryIndex: Int,
    val entry: AggressiveCandle,
    val entryPrice: Double,
    val stopPrice: Double,
    val targetPrice: Double,
    val riskPerUnit: Double,
    val stopAtr: Double,
    val targetR: Double,
)

private data class AggressiveExit(
    val exitIndex: Int,
    val exitPrice: Double,
    val reason: VolumeFlowExitReason,
)

private fun Double.floorToStep(step: Double): Double {
    if (step <= 0.0) return this
    return floor((this / step) + 1e-12) * step
}

private data class AggressivePriorStats(
    val returnPct: Double,
    val avgVolume: Double,
    val avgRangePct: Double,
)
