package dev.yaklede.bybittrader.engine.backtest

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.market.MarketCandleStore
import dev.yaklede.bybittrader.strategy.volume.CandleShape
import dev.yaklede.bybittrader.strategy.volume.VolumeFlowIndicators
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs

class VolumeFlowBacktestService(
    private val candleStore: MarketCandleStore,
) {
    suspend fun run(
        symbol: Symbol,
        m1Limit: Int,
        m5Limit: Int,
        m15Limit: Int,
        config: VolumeFlowBacktestConfig,
    ): VolumeFlowBacktestReport {
        require(m1Limit in 60..600_000) { "M1 candle limit must be between 60 and 600000." }
        require(m5Limit in 30..200_000) { "M5 candle limit must be between 30 and 200000." }
        require(m15Limit in 30..50_000) { "M15 candle limit must be between 30 and 50000." }

        val m1Candles = candleStore.recentCandles(symbol, Timeframe.M1, m1Limit).sortedBy { it.openedAt }
        val m5Candles = candleStore.recentCandles(symbol, Timeframe.M5, m5Limit).sortedBy { it.openedAt }
        val m15Candles = candleStore.recentCandles(symbol, Timeframe.M15, m15Limit).sortedBy { it.openedAt }
        require(m1Candles.size >= 60) { "At least 60 M1 candles are required." }
        require(m5Candles.size > config.volumeLookback + config.setupRangeLookback) {
            "Not enough M5 candles for volume setup detection."
        }
        require(m15Candles.size >= config.contextVwapLookback) {
            "Not enough M15 candles for context VWAP detection."
        }

        return runBacktest(
            symbol = symbol,
            m1Candles = m1Candles,
            m5Candles = m5Candles,
            m15Candles = m15Candles,
            config = config,
        )
    }

    private fun runBacktest(
        symbol: Symbol,
        m1Candles: List<Candle>,
        m5Candles: List<Candle>,
        m15Candles: List<Candle>,
        config: VolumeFlowBacktestConfig,
    ): VolumeFlowBacktestReport {
        var equity = config.initialEquity
        var peakEquity = equity
        var maxDrawdownPct = 0.0
        var blockedUntil = Instant.MIN
        var consecutiveLosses = 0
        var setupCount = 0
        var rejectedSetupCount = 0
        val noTradeReasonCounts = mutableMapOf<String, Int>()
        val trades = mutableListOf<VolumeFlowBacktestTrade>()
        val dailyStates = mutableMapOf<LocalDate, DailyBacktestState>()
        val startIndex = maxOf(config.volumeLookback, config.setupRangeLookback)

        for (setupIndex in startIndex until m5Candles.size) {
            val setupCandle = m5Candles[setupIndex]
            if (!setupCandle.openedAt.isAfter(blockedUntil)) {
                continue
            }
            val dayState =
                dailyStates.getOrPut(setupCandle.openedAt.utcDate()) {
                    DailyBacktestState(startingEquity = equity)
                }
            if (dayState.blocksNewEntries()) {
                incrementReason(dayState.lockReason ?: "DAILY_LOCK", noTradeReasonCounts)
                continue
            }

            val setupWindow = m5Candles.subList(0, setupIndex + 1)
            val candidate = detectSetup(setupWindow, config, noTradeReasonCounts) ?: continue
            setupCount += 1

            if (!contextAllows(m15Candles, setupCandle.openedAt, candidate.side, config)) {
                rejectedSetupCount += 1
                incrementReason("CONTEXT_REJECTED", noTradeReasonCounts)
                continue
            }

            val entry = findEntry(m1Candles, setupCandle, candidate, config)
            if (entry == null) {
                rejectedSetupCount += 1
                incrementReason("NO_M1_RETEST_TRIGGER", noTradeReasonCounts)
                continue
            }

            val riskPerUnit = abs(entry.entryPrice - entry.stopPrice)
            if (riskPerUnit <= 0.0) {
                rejectedSetupCount += 1
                incrementReason("INVALID_RISK_DISTANCE", noTradeReasonCounts)
                continue
            }

            val riskAmount = equity * config.riskFraction
            val quantity = riskAmount / riskPerUnit
            val exit = simulateExit(m1Candles, entry, config)
            val grossPnl = grossPnl(candidate.side, entry.entryPrice, exit.exitPrice, quantity)
            val fees = ((entry.entryPrice * quantity) + (exit.exitPrice * quantity)) * config.feeRate
            val pnl = grossPnl - fees
            equity += pnl
            peakEquity = maxOf(peakEquity, equity)
            maxDrawdownPct = maxOf(maxDrawdownPct, ((peakEquity - equity) / peakEquity) * 100.0)
            consecutiveLosses = if (pnl < 0.0) consecutiveLosses + 1 else 0

            val trade =
                VolumeFlowBacktestTrade(
                    side = candidate.side,
                    setupAt = setupCandle.openedAt,
                    entryAt = entry.entryCandle.openedAt,
                    exitAt = exit.exitCandle.openedAt,
                    entryPrice = entry.entryPrice,
                    stopPrice = entry.stopPrice,
                    targetPrice = entry.targetPrice,
                    exitPrice = exit.exitPrice,
                    quantity = quantity,
                    grossPnl = grossPnl,
                    fees = fees,
                    pnl = pnl,
                    returnR = pnl / riskAmount,
                    exitReason = exit.reason,
                    relativeVolume = candidate.relativeVolume,
                    volumeZScore = candidate.volumeZScore,
                    setupBodyRatio = candidate.shape.bodyRatio,
                    setupCloseLocation = candidate.shape.closeLocation,
                )
            trades += trade
            dayState.recordTrade(pnl, equity, config, consecutiveLosses)
            blockedUntil = exit.exitCandle.openedAt
        }

        return buildReport(
            symbol = symbol,
            m1Candles = m1Candles,
            m5Candles = m5Candles,
            m15Candles = m15Candles,
            config = config,
            finalEquity = equity,
            maxDrawdownPct = maxDrawdownPct,
            setupCount = setupCount,
            rejectedSetupCount = rejectedSetupCount,
            noTradeReasonCounts = noTradeReasonCounts.toSortedMap(),
            dailyStates = dailyStates,
            trades = trades,
        )
    }

    private fun detectSetup(
        candles: List<Candle>,
        config: VolumeFlowBacktestConfig,
        noTradeReasonCounts: MutableMap<String, Int>,
    ): SetupCandidate? {
        val relativeVolume = VolumeFlowIndicators.relativeVolume(candles, config.volumeLookback)
        if (relativeVolume == null || relativeVolume < config.relativeVolumeThreshold) {
            incrementReason("RELATIVE_VOLUME_LOW", noTradeReasonCounts)
            return null
        }
        val volumeZScore = VolumeFlowIndicators.volumeZScore(candles, config.volumeLookback)
        if (volumeZScore == null || volumeZScore < config.volumeZScoreThreshold) {
            incrementReason("VOLUME_ZSCORE_LOW", noTradeReasonCounts)
            return null
        }
        val side = VolumeFlowIndicators.breakoutSide(candles, config.setupRangeLookback)
        if (side == null) {
            incrementReason("NO_RANGE_BREAKOUT", noTradeReasonCounts)
            return null
        }
        val shape = VolumeFlowIndicators.candleShape(candles.last())
        if (shape.direction != side) {
            incrementReason("CANDLE_DIRECTION_MISMATCH", noTradeReasonCounts)
            return null
        }
        if (shape.bodyRatio < config.minBodyRatio) {
            incrementReason("BODY_TOO_SMALL", noTradeReasonCounts)
            return null
        }
        if (!shape.closesStronglyFor(side)) {
            incrementReason("WEAK_CLOSE_LOCATION", noTradeReasonCounts)
            return null
        }
        return SetupCandidate(
            side = side,
            relativeVolume = relativeVolume,
            volumeZScore = volumeZScore,
            shape = shape,
        )
    }

    private fun contextAllows(
        m15Candles: List<Candle>,
        setupAt: Instant,
        side: Side,
        config: VolumeFlowBacktestConfig,
    ): Boolean {
        val contextCandles =
            m15Candles
                .filter { !it.openedAt.isAfter(setupAt) }
                .takeLast(config.contextVwapLookback)
        if (contextCandles.size < config.contextVwapLookback) return false
        val vwap = VolumeFlowIndicators.vwap(contextCandles) ?: return false
        val latest = contextCandles.last()
        return when (side) {
            Side.BUY -> latest.close.toDouble() >= vwap
            Side.SELL -> latest.close.toDouble() <= vwap
        }
    }

    private fun findEntry(
        m1Candles: List<Candle>,
        setupCandle: Candle,
        candidate: SetupCandidate,
        config: VolumeFlowBacktestConfig,
    ): EntryPlan? {
        val startIndex = m1Candles.indexOfFirst { it.openedAt.isAfter(setupCandle.openedAt) }
        if (startIndex < 0) return null
        val breakoutLevel =
            when (candidate.side) {
                Side.BUY -> setupCandle.high.toDouble()
                Side.SELL -> setupCandle.low.toDouble()
            }
        val endIndex = minOf(startIndex + config.entryLookaheadM1Candles, m1Candles.lastIndex)
        val latestEntryAt = setupCandle.openedAt.plusSeconds(config.entryLookaheadM1Candles.toLong() * 60L)
        for (index in startIndex..endIndex) {
            val candle = m1Candles[index]
            if (candle.openedAt.isAfter(latestEntryAt)) return null
            val shape = VolumeFlowIndicators.candleShape(candle)
            val retestAccepted =
                when (candidate.side) {
                    Side.BUY ->
                        candle.low.toDouble() <= breakoutLevel * (1.0 + config.entryRetestTolerancePct) &&
                            candle.close.toDouble() > breakoutLevel
                    Side.SELL ->
                        candle.high.toDouble() >= breakoutLevel * (1.0 - config.entryRetestTolerancePct) &&
                            candle.close.toDouble() < breakoutLevel
                }
            if (shape.direction == candidate.side && shape.closesStronglyFor(candidate.side) && retestAccepted) {
                val rawEntryPrice = candle.close.toDouble()
                val entryPrice =
                    when (candidate.side) {
                        Side.BUY -> rawEntryPrice * (1.0 + config.slippageRate)
                        Side.SELL -> rawEntryPrice * (1.0 - config.slippageRate)
                    }
                val stopPrice =
                    when (candidate.side) {
                        Side.BUY -> minOf(setupCandle.low.toDouble(), candle.low.toDouble())
                        Side.SELL -> maxOf(setupCandle.high.toDouble(), candle.high.toDouble())
                    }
                val riskPerUnit = abs(entryPrice - stopPrice)
                val targetPrice =
                    when (candidate.side) {
                        Side.BUY -> entryPrice + (riskPerUnit * config.targetR)
                        Side.SELL -> entryPrice - (riskPerUnit * config.targetR)
                    }
                return EntryPlan(
                    side = candidate.side,
                    entryIndex = index,
                    entryCandle = candle,
                    entryPrice = entryPrice,
                    stopPrice = stopPrice,
                    targetPrice = targetPrice,
                )
            }
        }
        return null
    }

    private fun simulateExit(
        m1Candles: List<Candle>,
        entry: EntryPlan,
        config: VolumeFlowBacktestConfig,
    ): ExitPlan {
        val startIndex = minOf(entry.entryIndex + 1, m1Candles.lastIndex)
        val endIndex = minOf(entry.entryIndex + config.maxHoldM1Candles, m1Candles.lastIndex)
        for (index in startIndex..endIndex) {
            val candle = m1Candles[index]
            val stopHit =
                when (entry.side) {
                    Side.BUY -> candle.low.toDouble() <= entry.stopPrice
                    Side.SELL -> candle.high.toDouble() >= entry.stopPrice
                }
            if (stopHit) {
                return ExitPlan(
                    exitCandle = candle,
                    exitPrice = entry.stopPrice.withExitSlippage(entry.side, config),
                    reason = VolumeFlowExitReason.STOP,
                )
            }
            val targetHit =
                when (entry.side) {
                    Side.BUY -> candle.high.toDouble() >= entry.targetPrice
                    Side.SELL -> candle.low.toDouble() <= entry.targetPrice
                }
            if (targetHit) {
                return ExitPlan(
                    exitCandle = candle,
                    exitPrice = entry.targetPrice.withExitSlippage(entry.side, config),
                    reason = VolumeFlowExitReason.TARGET,
                )
            }
        }
        val exitCandle = m1Candles[endIndex]
        return ExitPlan(
            exitCandle = exitCandle,
            exitPrice = exitCandle.close.toDouble().withExitSlippage(entry.side, config),
            reason = VolumeFlowExitReason.TIME,
        )
    }

    private fun buildReport(
        symbol: Symbol,
        m1Candles: List<Candle>,
        m5Candles: List<Candle>,
        m15Candles: List<Candle>,
        config: VolumeFlowBacktestConfig,
        finalEquity: Double,
        maxDrawdownPct: Double,
        setupCount: Int,
        rejectedSetupCount: Int,
        noTradeReasonCounts: Map<String, Int>,
        dailyStates: Map<LocalDate, DailyBacktestState>,
        trades: List<VolumeFlowBacktestTrade>,
    ): VolumeFlowBacktestReport {
        val wins = trades.count { it.pnl > 0.0 }
        val losses = trades.count { it.pnl < 0.0 }
        val grossProfit = trades.filter { it.pnl > 0.0 }.sumOf { it.pnl }
        val grossLoss = trades.filter { it.pnl < 0.0 }.sumOf { abs(it.pnl) }
        val netPnl = finalEquity - config.initialEquity
        val activeDays = dailyStates.values.count { it.tradeCount > 0 }
        return VolumeFlowBacktestReport(
            symbol = symbol,
            m1CandleCount = m1Candles.size,
            m5CandleCount = m5Candles.size,
            m15CandleCount = m15Candles.size,
            startAt =
                listOfNotNull(
                    m1Candles.firstOrNull()?.openedAt,
                    m5Candles.firstOrNull()?.openedAt,
                    m15Candles.firstOrNull()?.openedAt,
                ).minOrNull(),
            endAt =
                listOfNotNull(
                    m1Candles.lastOrNull()?.openedAt,
                    m5Candles.lastOrNull()?.openedAt,
                    m15Candles.lastOrNull()?.openedAt,
                ).maxOrNull(),
            initialEquity = config.initialEquity,
            finalEquity = finalEquity,
            netPnl = netPnl,
            netReturnPct = (netPnl / config.initialEquity) * 100.0,
            maxDrawdownPct = maxDrawdownPct,
            tradeCount = trades.size,
            wins = wins,
            losses = losses,
            winRatePct = if (trades.isEmpty()) 0.0 else (wins.toDouble() / trades.size) * 100.0,
            profitFactor = if (grossLoss == 0.0) null else grossProfit / grossLoss,
            expectancyR = if (trades.isEmpty()) 0.0 else trades.map { it.returnR }.average(),
            maxConsecutiveLosses = trades.maxConsecutiveLosses(),
            activeDays = activeDays,
            targetHitDays = dailyStates.values.count { it.lockReason == "DAILY_TARGET_HIT" },
            stopHitDays = dailyStates.values.count { it.lockReason == "DAILY_STOP_HIT" },
            setupCount = setupCount,
            rejectedSetupCount = rejectedSetupCount,
            noTradeReasonCounts = noTradeReasonCounts,
            trades = trades,
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
}

private data class SetupCandidate(
    val side: Side,
    val relativeVolume: Double,
    val volumeZScore: Double,
    val shape: CandleShape,
)

private data class EntryPlan(
    val side: Side,
    val entryIndex: Int,
    val entryCandle: Candle,
    val entryPrice: Double,
    val stopPrice: Double,
    val targetPrice: Double,
)

private data class ExitPlan(
    val exitCandle: Candle,
    val exitPrice: Double,
    val reason: VolumeFlowExitReason,
)

private class DailyBacktestState(
    val startingEquity: Double,
) {
    var tradeCount: Int = 0
        private set
    var netPnl: Double = 0.0
        private set
    var lockReason: String? = null
        private set

    fun blocksNewEntries(): Boolean = lockReason != null

    fun recordTrade(
        pnl: Double,
        currentEquity: Double,
        config: VolumeFlowBacktestConfig,
        consecutiveLosses: Int,
    ) {
        tradeCount += 1
        netPnl = currentEquity - startingEquity
        val dailyReturnPct = (netPnl / startingEquity) * 100.0
        lockReason =
            when {
                dailyReturnPct >= config.dailyTargetPct -> "DAILY_TARGET_HIT"
                dailyReturnPct <= -config.dailyStopPct -> "DAILY_STOP_HIT"
                tradeCount >= config.maxTradesPerDay -> "MAX_TRADES_PER_DAY"
                consecutiveLosses >= config.maxConsecutiveLosses -> "MAX_CONSECUTIVE_LOSSES"
                pnl.isNaN() -> "INVALID_PNL"
                else -> null
            }
    }
}

private fun Double.withExitSlippage(
    side: Side,
    config: VolumeFlowBacktestConfig,
): Double =
    when (side) {
        Side.BUY -> this * (1.0 - config.slippageRate)
        Side.SELL -> this * (1.0 + config.slippageRate)
    }

private fun Instant.utcDate(): LocalDate = atZone(ZoneOffset.UTC).toLocalDate()

private fun incrementReason(
    reason: String,
    counts: MutableMap<String, Int>,
) {
    counts[reason] = (counts[reason] ?: 0) + 1
}

private fun List<VolumeFlowBacktestTrade>.maxConsecutiveLosses(): Int {
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
