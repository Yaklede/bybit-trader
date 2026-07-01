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
import kotlin.math.sqrt

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
        require(m5Candles.size > maxOf(config.volumeLookback + config.setupRangeLookback, config.m5VwapLookback)) {
            "Not enough M5 candles for volume setup detection."
        }
        require(m15Candles.size >= config.contextVwapLookback) {
            "Not enough M15 candles for context VWAP detection."
        }

        return runLoadedCandles(
            symbol = symbol,
            m1Candles = m1Candles,
            m5Candles = m5Candles,
            m15Candles = m15Candles,
            config = config,
        )
    }

    internal fun runLoadedCandles(
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
        val m1Timeline = CandleTimeline(m1Candles)
        val m5Timeline = CandleTimeline(m5Candles)
        val m15Timeline = CandleTimeline(m15Candles)
        val setupCandles =
            when (config.setupTimeframe) {
                Timeframe.M1 -> m1Candles
                Timeframe.M5 -> m5Candles
                else -> error("Unsupported setup timeframe.")
            }
        require(setupCandles.size > startIndex) {
            "Not enough setup candles for volume setup detection."
        }

        for (setupIndex in startIndex until setupCandles.size) {
            val setupCandle = setupCandles[setupIndex]
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

            val candidate = detectSetup(setupCandles, setupIndex, config, noTradeReasonCounts) ?: continue
            setupCount += 1
            val marketRegime = classifyMarketRegime(m15Timeline, setupCandle.openedAt, config)

            if (!config.allowedMarketRegimes.contains(marketRegime)) {
                rejectedSetupCount += 1
                incrementReason("MARKET_REGIME_REJECTED", noTradeReasonCounts)
                continue
            }

            if (config.requireRegimeSideAlignment && !marketRegime.allows(candidate.side)) {
                rejectedSetupCount += 1
                incrementReason("MARKET_REGIME_SIDE_MISMATCH", noTradeReasonCounts)
                continue
            }

            if (!config.sideMode.allows(candidate.side)) {
                rejectedSetupCount += 1
                incrementReason("SIDE_NOT_ALLOWED", noTradeReasonCounts)
                continue
            }

            if (!localContextAllows(m5Timeline, setupCandle.openedAt, candidate.side, config)) {
                rejectedSetupCount += 1
                incrementReason("M5_CONTEXT_REJECTED", noTradeReasonCounts)
                continue
            }

            if (!contextAllows(m15Timeline, setupCandle.openedAt, candidate.side, config)) {
                rejectedSetupCount += 1
                incrementReason("CONTEXT_REJECTED", noTradeReasonCounts)
                continue
            }

            val entry = findEntry(m1Timeline, setupCandle, candidate, config)
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
            val entryRiskPct = riskPerUnit / entry.entryPrice
            if (config.minEntryRiskPct != null && entryRiskPct < config.minEntryRiskPct) {
                rejectedSetupCount += 1
                incrementReason("ENTRY_RISK_TOO_SMALL", noTradeReasonCounts)
                continue
            }
            if (config.maxEntryRiskPct != null && entryRiskPct > config.maxEntryRiskPct) {
                rejectedSetupCount += 1
                incrementReason("ENTRY_RISK_TOO_LARGE", noTradeReasonCounts)
                continue
            }
            val estimatedFeeR = estimatedRoundTripFeeR(entry, config)
            if (estimatedFeeR > config.maxEstimatedFeeR) {
                rejectedSetupCount += 1
                incrementReason("ESTIMATED_FEE_R_TOO_HIGH", noTradeReasonCounts)
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
                    setupMode = candidate.setupMode,
                    setupAt = setupCandle.openedAt,
                    entryAt = entry.entryAt,
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
                    marketRegime = marketRegime,
                    keyLevelType = candidate.keyLevel.type,
                    keyLevelDistancePct = candidate.keyLevel.distancePct,
                    volumePattern = candidate.volumePattern,
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
        index: Int,
        config: VolumeFlowBacktestConfig,
        noTradeReasonCounts: MutableMap<String, Int>,
    ): SetupCandidate? {
        val relativeVolume = relativeVolumeAt(candles, index, config.volumeLookback)
        if (relativeVolume == null || relativeVolume < config.relativeVolumeThreshold) {
            incrementReason("RELATIVE_VOLUME_LOW", noTradeReasonCounts)
            return null
        }
        val volumeZScore = volumeZScoreAt(candles, index, config.volumeLookback)
        if (volumeZScore == null || volumeZScore < config.volumeZScoreThreshold) {
            incrementReason("VOLUME_ZSCORE_LOW", noTradeReasonCounts)
            return null
        }
        val setupRange = setupRangeAt(candles, index, config.setupRangeLookback)
        if (setupRange == null) {
            incrementReason("NO_SETUP_RANGE", noTradeReasonCounts)
            return null
        }
        val shape = VolumeFlowIndicators.candleShape(candles[index])
        val keyLevel = keyLevelContext(candles[index], setupRange, config)
        if (config.requireKeyLevelProximity && !keyLevel.isNearKeyLevel) {
            incrementReason("KEY_LEVEL_PROXIMITY_REJECTED", noTradeReasonCounts)
            return null
        }
        if (config.avoidRangeMiddle && keyLevel.type == VolumeFlowKeyLevelType.RANGE_MIDDLE) {
            incrementReason("RANGE_MIDDLE_REJECTED", noTradeReasonCounts)
            return null
        }
        return when (config.setupMode) {
            VolumeFlowSetupMode.BREAKOUT_CONTINUATION ->
                detectBreakoutContinuation(
                    candles[index],
                    setupRange,
                    shape,
                    keyLevel,
                    relativeVolume,
                    volumeZScore,
                    config,
                    noTradeReasonCounts,
                )
            VolumeFlowSetupMode.FAILED_BREAK_REVERSAL ->
                detectFailedBreakReversal(
                    candles[index],
                    setupRange,
                    shape,
                    keyLevel,
                    relativeVolume,
                    volumeZScore,
                    config,
                    noTradeReasonCounts,
                )
            VolumeFlowSetupMode.VOLUME_REJECTION_REVERSAL ->
                detectVolumeRejectionReversal(
                    candles[index],
                    shape,
                    keyLevel,
                    relativeVolume,
                    volumeZScore,
                    config,
                    noTradeReasonCounts,
                )
        }
    }

    private fun detectBreakoutContinuation(
        candle: Candle,
        setupRange: SetupRange,
        shape: CandleShape,
        keyLevel: KeyLevelContext,
        relativeVolume: Double,
        volumeZScore: Double,
        config: VolumeFlowBacktestConfig,
        noTradeReasonCounts: MutableMap<String, Int>,
    ): SetupCandidate? {
        val close = candle.close.toDouble()
        val side =
            when {
                close > setupRange.high -> Side.BUY
                close < setupRange.low -> Side.SELL
                else -> null
            }
        if (side == null) {
            incrementReason("NO_RANGE_BREAKOUT", noTradeReasonCounts)
            return null
        }
        if (shape.direction != side) {
            incrementReason("CANDLE_DIRECTION_MISMATCH", noTradeReasonCounts)
            return null
        }
        if (shape.bodyRatio < config.minBodyRatio) {
            incrementReason("BODY_TOO_SMALL", noTradeReasonCounts)
            return null
        }
        if (!shape.closesStronglyFor(side, config.minDirectionalCloseStrength)) {
            incrementReason("WEAK_CLOSE_LOCATION", noTradeReasonCounts)
            return null
        }
        return SetupCandidate(
            setupMode = VolumeFlowSetupMode.BREAKOUT_CONTINUATION,
            side = side,
            entryLevel =
                when (side) {
                    Side.BUY -> candle.high.toDouble()
                    Side.SELL -> candle.low.toDouble()
                },
            relativeVolume = relativeVolume,
            volumeZScore = volumeZScore,
            shape = shape,
            keyLevel = keyLevel,
            volumePattern = VolumeFlowVolumePattern.BREAKOUT_ACCEPTANCE,
        )
    }

    private fun detectFailedBreakReversal(
        candle: Candle,
        setupRange: SetupRange,
        shape: CandleShape,
        keyLevel: KeyLevelContext,
        relativeVolume: Double,
        volumeZScore: Double,
        config: VolumeFlowBacktestConfig,
        noTradeReasonCounts: MutableMap<String, Int>,
    ): SetupCandidate? {
        val high = candle.high.toDouble()
        val low = candle.low.toDouble()
        val close = candle.close.toDouble()
        val side =
            when {
                high > setupRange.high && close <= setupRange.high -> Side.SELL
                low < setupRange.low && close >= setupRange.low -> Side.BUY
                else -> null
            }
        if (side == null) {
            incrementReason("NO_FAILED_BREAK", noTradeReasonCounts)
            return null
        }
        if (shape.direction != side) {
            incrementReason("CANDLE_DIRECTION_MISMATCH", noTradeReasonCounts)
            return null
        }
        if (shape.bodyRatio < config.minBodyRatio) {
            incrementReason("BODY_TOO_SMALL", noTradeReasonCounts)
            return null
        }
        val rejectionWickRatio =
            when (side) {
                Side.BUY -> shape.lowerWickRatio
                Side.SELL -> shape.upperWickRatio
            }
        if (rejectionWickRatio < config.minRejectionWickRatio) {
            incrementReason("REJECTION_WICK_TOO_SMALL", noTradeReasonCounts)
            return null
        }
        if (!shape.closesStronglyFor(side, config.minDirectionalCloseStrength)) {
            incrementReason("WEAK_CLOSE_LOCATION", noTradeReasonCounts)
            return null
        }
        return SetupCandidate(
            setupMode = VolumeFlowSetupMode.FAILED_BREAK_REVERSAL,
            side = side,
            entryLevel =
                when (side) {
                    Side.BUY -> setupRange.low
                    Side.SELL -> setupRange.high
                },
            relativeVolume = relativeVolume,
            volumeZScore = volumeZScore,
            shape = shape,
            keyLevel = keyLevel,
            volumePattern = VolumeFlowVolumePattern.FAILED_BREAK,
        )
    }

    private fun detectVolumeRejectionReversal(
        candle: Candle,
        shape: CandleShape,
        keyLevel: KeyLevelContext,
        relativeVolume: Double,
        volumeZScore: Double,
        config: VolumeFlowBacktestConfig,
        noTradeReasonCounts: MutableMap<String, Int>,
    ): SetupCandidate? {
        val side =
            when {
                shape.upperWickRatio >= config.minRejectionWickRatio &&
                    shape.closeLocation <= 1.0 - config.minDirectionalCloseStrength -> Side.SELL
                shape.lowerWickRatio >= config.minRejectionWickRatio &&
                    shape.closeLocation >= config.minDirectionalCloseStrength -> Side.BUY
                else -> null
            }
        if (side == null) {
            incrementReason("NO_VOLUME_REJECTION", noTradeReasonCounts)
            return null
        }
        if (shape.direction != side) {
            incrementReason("CANDLE_DIRECTION_MISMATCH", noTradeReasonCounts)
            return null
        }
        if (shape.bodyRatio < config.minBodyRatio) {
            incrementReason("BODY_TOO_SMALL", noTradeReasonCounts)
            return null
        }
        return SetupCandidate(
            setupMode = VolumeFlowSetupMode.VOLUME_REJECTION_REVERSAL,
            side = side,
            entryLevel = candle.close.toDouble(),
            relativeVolume = relativeVolume,
            volumeZScore = volumeZScore,
            shape = shape,
            keyLevel = keyLevel,
            volumePattern = VolumeFlowVolumePattern.CLIMAX_REVERSAL,
        )
    }

    private fun relativeVolumeAt(
        candles: List<Candle>,
        index: Int,
        lookback: Int,
    ): Double? {
        if (index < lookback) return null
        var volumeSum = 0.0
        for (volumeIndex in index - lookback until index) {
            volumeSum += candles[volumeIndex].volume.toDouble()
        }
        val baseline = volumeSum / lookback
        if (baseline <= 0.0) return null
        return candles[index].volume.toDouble() / baseline
    }

    private fun volumeZScoreAt(
        candles: List<Candle>,
        index: Int,
        lookback: Int,
    ): Double? {
        if (index < lookback) return null
        var volumeSum = 0.0
        for (volumeIndex in index - lookback until index) {
            volumeSum += candles[volumeIndex].volume.toDouble()
        }
        val average = volumeSum / lookback
        var squaredDeltaSum = 0.0
        for (volumeIndex in index - lookback until index) {
            val delta = candles[volumeIndex].volume.toDouble() - average
            squaredDeltaSum += delta * delta
        }
        val variance = squaredDeltaSum / lookback
        val standardDeviation = sqrt(variance)
        if (standardDeviation <= 0.0) return null
        return (candles[index].volume.toDouble() - average) / standardDeviation
    }

    private fun setupRangeAt(
        candles: List<Candle>,
        index: Int,
        lookback: Int,
    ): SetupRange? {
        if (index < lookback) return null
        var high = Double.NEGATIVE_INFINITY
        var low = Double.POSITIVE_INFINITY
        for (rangeIndex in index - lookback until index) {
            high = maxOf(high, candles[rangeIndex].high.toDouble())
            low = minOf(low, candles[rangeIndex].low.toDouble())
        }
        return SetupRange(high = high, low = low)
    }

    private fun keyLevelContext(
        candle: Candle,
        setupRange: SetupRange,
        config: VolumeFlowBacktestConfig,
    ): KeyLevelContext {
        val close = candle.close.toDouble()
        val rangeWidth = setupRange.high - setupRange.low
        if (close <= 0.0 || rangeWidth <= 0.0) {
            return KeyLevelContext(
                type = VolumeFlowKeyLevelType.UNKNOWN,
                distancePct = Double.POSITIVE_INFINITY,
                isNearKeyLevel = false,
            )
        }

        val touchedHigh = candle.high.toDouble() >= setupRange.high
        val touchedLow = candle.low.toDouble() <= setupRange.low
        val highDistancePct = if (touchedHigh) 0.0 else abs(close - setupRange.high) / close
        val lowDistancePct = if (touchedLow) 0.0 else abs(close - setupRange.low) / close
        val nearHigh = touchedHigh || highDistancePct <= config.keyLevelTolerancePct
        val nearLow = touchedLow || lowDistancePct <= config.keyLevelTolerancePct
        val rangePosition = (close - setupRange.low) / rangeWidth

        val type =
            when {
                nearHigh && (!nearLow || highDistancePct <= lowDistancePct) -> VolumeFlowKeyLevelType.RANGE_HIGH
                nearLow -> VolumeFlowKeyLevelType.RANGE_LOW
                rangePosition in 0.35..0.65 -> VolumeFlowKeyLevelType.RANGE_MIDDLE
                else -> VolumeFlowKeyLevelType.RANGE_INTERIOR
            }

        return KeyLevelContext(
            type = type,
            distancePct = minOf(highDistancePct, lowDistancePct),
            isNearKeyLevel = nearHigh || nearLow,
        )
    }

    private fun localContextAllows(
        m5Timeline: CandleTimeline,
        setupAt: Instant,
        side: Side,
        config: VolumeFlowBacktestConfig,
    ): Boolean {
        if (!config.requireM5Vwap) return true
        return vwapSideAllows(
            timeline = m5Timeline,
            setupAt = setupAt,
            side = side,
            lookback = config.m5VwapLookback,
            requireTrend = false,
        )
    }

    private fun contextAllows(
        m15Timeline: CandleTimeline,
        setupAt: Instant,
        side: Side,
        config: VolumeFlowBacktestConfig,
    ): Boolean {
        if (!config.requireContextVwap && !config.requireContextTrend) return true
        return vwapSideAllows(
            timeline = m15Timeline,
            setupAt = setupAt,
            side = side,
            lookback = config.contextVwapLookback,
            requireVwap = config.requireContextVwap,
            requireTrend = config.requireContextTrend,
        )
    }

    private fun vwapSideAllows(
        timeline: CandleTimeline,
        setupAt: Instant,
        side: Side,
        lookback: Int,
        requireVwap: Boolean = true,
        requireTrend: Boolean,
    ): Boolean {
        val contextCandles = timeline.takeLastAtOrBefore(setupAt, lookback)
        if (contextCandles.size < lookback) return false
        val latest = contextCandles.last()
        if (requireVwap) {
            val vwap = VolumeFlowIndicators.vwap(contextCandles) ?: return false
            val vwapAllows =
                when (side) {
                    Side.BUY -> latest.close.toDouble() >= vwap
                    Side.SELL -> latest.close.toDouble() <= vwap
                }
            if (!vwapAllows) {
                return false
            }
        }
        if (!requireTrend) return true

        val first = contextCandles.first()
        return when (side) {
            Side.BUY -> latest.close > first.close
            Side.SELL -> latest.close < first.close
        }
    }

    private fun classifyMarketRegime(
        m15Timeline: CandleTimeline,
        setupAt: Instant,
        config: VolumeFlowBacktestConfig,
    ): VolumeFlowMarketRegime {
        val contextCandles = m15Timeline.takeLastAtOrBefore(setupAt, config.contextVwapLookback)
        if (contextCandles.size < 3) return VolumeFlowMarketRegime.UNKNOWN

        val firstClose = contextCandles.first().close.toDouble()
        val latestClose = contextCandles.last().close.toDouble()
        if (firstClose <= 0.0 || latestClose <= 0.0) return VolumeFlowMarketRegime.UNKNOWN

        val directionalMovePct = (latestClose - firstClose) / firstClose
        var totalMovePct = 0.0
        for (index in 1 until contextCandles.size) {
            val previousClose = contextCandles[index - 1].close.toDouble()
            val currentClose = contextCandles[index].close.toDouble()
            if (previousClose > 0.0) {
                totalMovePct += abs(currentClose - previousClose) / previousClose
            }
        }
        val trendEfficiency = if (totalMovePct <= 0.0) 0.0 else abs(directionalMovePct) / totalMovePct
        val averageRangePct =
            contextCandles
                .mapNotNull { candle ->
                    val close = candle.close.toDouble()
                    if (close <= 0.0) null else (candle.high.toDouble() - candle.low.toDouble()) / close
                }.average()

        if (
            averageRangePct.isFinite() &&
            averageRangePct >= config.highVolatilityRangePct &&
            trendEfficiency < config.minTrendEfficiency
        ) {
            return VolumeFlowMarketRegime.HIGH_VOLATILITY_CHOP
        }
        if (abs(directionalMovePct) < config.minTrendMovePct || trendEfficiency < config.minTrendEfficiency) {
            return VolumeFlowMarketRegime.RANGE
        }

        val vwap = VolumeFlowIndicators.vwap(contextCandles)
        return when {
            directionalMovePct > 0.0 && (vwap == null || latestClose >= vwap) -> VolumeFlowMarketRegime.TREND_UP
            directionalMovePct < 0.0 && (vwap == null || latestClose <= vwap) -> VolumeFlowMarketRegime.TREND_DOWN
            else -> VolumeFlowMarketRegime.RANGE
        }
    }

    private fun estimatedRoundTripFeeR(
        entry: EntryPlan,
        config: VolumeFlowBacktestConfig,
    ): Double {
        val riskPerUnit = abs(entry.entryPrice - entry.stopPrice)
        if (riskPerUnit <= 0.0) return Double.POSITIVE_INFINITY
        val estimatedExitPrice =
            when (entry.side) {
                Side.BUY -> maxOf(entry.entryPrice, entry.targetPrice)
                Side.SELL -> minOf(entry.entryPrice, entry.targetPrice)
            }
        return ((entry.entryPrice + estimatedExitPrice) * config.feeRate) / riskPerUnit
    }

    private fun findEntry(
        m1Timeline: CandleTimeline,
        setupCandle: Candle,
        candidate: SetupCandidate,
        config: VolumeFlowBacktestConfig,
    ): EntryPlan? {
        val m1Candles = m1Timeline.candles
        val setupClosedAt = setupCandle.openedAt.plusSeconds(config.setupTimeframe.seconds())
        val startIndex = m1Timeline.firstIndexAtOrAfter(setupClosedAt)
        if (startIndex < 0) return null
        if (config.entryMode == VolumeFlowEntryMode.SETUP_CLOSE_CONFIRMATION) {
            val entryIndex = startIndex - 1
            if (entryIndex < 0) return null
            val rawEntryPrice = setupCandle.close.toDouble()
            val entryPrice =
                when (candidate.side) {
                    Side.BUY -> rawEntryPrice * (1.0 + config.slippageRate)
                    Side.SELL -> rawEntryPrice * (1.0 - config.slippageRate)
                }
            val stopPrice =
                when (candidate.side) {
                    Side.BUY -> setupCandle.low.toDouble()
                    Side.SELL -> setupCandle.high.toDouble()
                }
            val riskPerUnit = abs(entryPrice - stopPrice)
            val targetPrice =
                when (candidate.side) {
                    Side.BUY -> entryPrice + (riskPerUnit * config.targetR)
                    Side.SELL -> entryPrice - (riskPerUnit * config.targetR)
                }
            return EntryPlan(
                side = candidate.side,
                entryAt = setupClosedAt,
                entryIndex = entryIndex,
                entryCandle = m1Candles[entryIndex],
                entryPrice = entryPrice,
                stopPrice = stopPrice,
                targetPrice = targetPrice,
            )
        }
        val endIndex = minOf(startIndex + config.entryLookaheadM1Candles, m1Candles.lastIndex)
        val latestEntryAt = setupClosedAt.plusSeconds(config.entryLookaheadM1Candles.toLong() * 60L)
        for (index in startIndex..endIndex) {
            val candle = m1Candles[index]
            if (candle.openedAt.isAfter(latestEntryAt)) return null
            val shape = VolumeFlowIndicators.candleShape(candle)
            if (
                shape.direction == candidate.side &&
                shape.closesStronglyFor(candidate.side, config.minDirectionalCloseStrength) &&
                entryTriggerAccepted(candle, candidate, config)
            ) {
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
                    entryAt = candle.openedAt,
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

    private fun entryTriggerAccepted(
        candle: Candle,
        candidate: SetupCandidate,
        config: VolumeFlowBacktestConfig,
    ): Boolean =
        when (config.entryMode) {
            VolumeFlowEntryMode.RETEST_CONFIRMATION -> candle.retestsAndConfirms(candidate, config)
            VolumeFlowEntryMode.CLOSE_CONFIRMATION -> candle.closesBeyond(candidate)
            VolumeFlowEntryMode.SETUP_CLOSE_CONFIRMATION -> false
        }

    private fun simulateExit(
        m1Candles: List<Candle>,
        entry: EntryPlan,
        config: VolumeFlowBacktestConfig,
    ): ExitPlan {
        val startIndex = minOf(entry.entryIndex + 1, m1Candles.lastIndex)
        val endIndex = minOf(entry.entryIndex + config.maxHoldM1Candles, m1Candles.lastIndex)
        val initialRiskPerUnit = abs(entry.entryPrice - entry.stopPrice)
        var stopPrice = entry.stopPrice
        var stopReason = VolumeFlowExitReason.STOP
        for (index in startIndex..endIndex) {
            val candle = m1Candles[index]
            val stopHit =
                when (entry.side) {
                    Side.BUY -> candle.low.toDouble() <= stopPrice
                    Side.SELL -> candle.high.toDouble() >= stopPrice
                }
            if (stopHit) {
                return ExitPlan(
                    exitCandle = candle,
                    exitPrice = stopPrice.withExitSlippage(entry.side, config),
                    reason = stopReason,
                )
            }
            val targetHit =
                when (entry.side) {
                    Side.BUY -> candle.high.toDouble() >= entry.targetPrice
                    Side.SELL -> candle.low.toDouble() <= entry.targetPrice
                }
            if (config.exitMode == VolumeFlowExitMode.FIXED_TARGET && targetHit) {
                return ExitPlan(
                    exitCandle = candle,
                    exitPrice = entry.targetPrice.withExitSlippage(entry.side, config),
                    reason = VolumeFlowExitReason.TARGET,
                )
            }
            if (config.exitMode == VolumeFlowExitMode.RUNNER && initialRiskPerUnit > 0.0) {
                val bestPrice =
                    when (entry.side) {
                        Side.BUY -> candle.high.toDouble()
                        Side.SELL -> candle.low.toDouble()
                    }
                val favorableMove =
                    when (entry.side) {
                        Side.BUY -> bestPrice - entry.entryPrice
                        Side.SELL -> entry.entryPrice - bestPrice
                    }
                if (favorableMove >= initialRiskPerUnit * config.runnerTrailActivationR) {
                    val trailStop =
                        when (entry.side) {
                            Side.BUY -> bestPrice - (initialRiskPerUnit * config.runnerTrailDistanceR)
                            Side.SELL -> bestPrice + (initialRiskPerUnit * config.runnerTrailDistanceR)
                        }
                    val improvedStop = stopPrice.improvedFor(entry.side, trailStop)
                    if (improvedStop != stopPrice) {
                        stopPrice = improvedStop
                        stopReason = VolumeFlowExitReason.TRAILING_STOP
                    }
                }
            }
            val breakevenTriggerR = config.breakevenTriggerR
            if (breakevenTriggerR != null && initialRiskPerUnit > 0.0) {
                val breakevenTouched =
                    when (entry.side) {
                        Side.BUY -> candle.high.toDouble() >= entry.entryPrice + (initialRiskPerUnit * breakevenTriggerR)
                        Side.SELL -> candle.low.toDouble() <= entry.entryPrice - (initialRiskPerUnit * breakevenTriggerR)
                    }
                if (breakevenTouched) {
                    stopPrice = stopPrice.improvedFor(entry.side, entry.entryPrice)
                }
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
        val winRatePct = if (trades.isEmpty()) 0.0 else (wins.toDouble() / trades.size) * 100.0
        val expectancyProfile = volumeFlowExpectancyProfile(trades.map { it.returnR }, winRatePct)
        val netPnl = finalEquity - config.initialEquity
        val observedDays = dailyStates.size
        val activeDays = dailyStates.values.count { it.tradeCount > 0 }
        val tradeFrequencyTargetDays =
            dailyStates.values.count { dailyState ->
                dailyState.tradeCount in config.minTradesPerDay..config.maxTradesPerDay
            }
        val belowMinTradeDays = dailyStates.values.count { it.tradeCount < config.minTradesPerDay }
        val aboveMaxTradeDays = dailyStates.values.count { it.tradeCount > config.maxTradesPerDay }
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
            winRatePct = winRatePct,
            profitFactor = if (grossLoss == 0.0) null else grossProfit / grossLoss,
            expectancyR = if (trades.isEmpty()) 0.0 else trades.map { it.returnR }.average(),
            averageWinR = expectancyProfile.averageWinR,
            averageLossR = expectancyProfile.averageLossR,
            payoffRatio = expectancyProfile.payoffRatio,
            breakevenWinRatePct = expectancyProfile.breakevenWinRatePct,
            winRateEdgePct = expectancyProfile.winRateEdgePct,
            maxConsecutiveLosses = trades.maxConsecutiveLosses(),
            observedDays = observedDays,
            activeDays = activeDays,
            activeDayCoveragePct = if (observedDays == 0) 0.0 else (activeDays.toDouble() / observedDays) * 100.0,
            averageTradesPerDay = if (observedDays == 0) 0.0 else trades.size.toDouble() / observedDays,
            averageTradesPerActiveDay = if (activeDays == 0) 0.0 else trades.size.toDouble() / activeDays,
            tradeFrequencyTargetDays = tradeFrequencyTargetDays,
            tradeFrequencyTargetPct =
                if (observedDays == 0) 0.0 else (tradeFrequencyTargetDays.toDouble() / observedDays) * 100.0,
            belowMinTradeDays = belowMinTradeDays,
            aboveMaxTradeDays = aboveMaxTradeDays,
            targetHitDays = dailyStates.values.count { it.lockReason == "DAILY_TARGET_HIT" },
            stopHitDays = dailyStates.values.count { it.lockReason == "DAILY_STOP_HIT" },
            setupCount = setupCount,
            rejectedSetupCount = rejectedSetupCount,
            noTradeReasonCounts = noTradeReasonCounts,
            performanceBySetupMode = trades.tagSummaries { it.setupMode.name },
            performanceByMarketRegime = trades.tagSummaries { it.marketRegime.name },
            performanceByVolumePattern = trades.tagSummaries { it.volumePattern.name },
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
    val setupMode: VolumeFlowSetupMode,
    val side: Side,
    val entryLevel: Double,
    val relativeVolume: Double,
    val volumeZScore: Double,
    val shape: CandleShape,
    val keyLevel: KeyLevelContext,
    val volumePattern: VolumeFlowVolumePattern,
)

private data class SetupRange(
    val high: Double,
    val low: Double,
)

private data class KeyLevelContext(
    val type: VolumeFlowKeyLevelType,
    val distancePct: Double,
    val isNearKeyLevel: Boolean,
)

private fun Candle.retestsAndConfirms(
    candidate: SetupCandidate,
    config: VolumeFlowBacktestConfig,
): Boolean =
    when (candidate.side) {
        Side.BUY ->
            low.toDouble() <= candidate.entryLevel * (1.0 + config.entryRetestTolerancePct) &&
                close.toDouble() > candidate.entryLevel
        Side.SELL ->
            high.toDouble() >= candidate.entryLevel * (1.0 - config.entryRetestTolerancePct) &&
                close.toDouble() < candidate.entryLevel
    }

private fun Candle.closesBeyond(candidate: SetupCandidate): Boolean =
    when (candidate.side) {
        Side.BUY -> close.toDouble() > candidate.entryLevel
        Side.SELL -> close.toDouble() < candidate.entryLevel
    }

private data class EntryPlan(
    val side: Side,
    val entryAt: Instant,
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

private class CandleTimeline(
    val candles: List<Candle>,
) {
    private val openedAt = candles.map { it.openedAt }

    fun firstIndexAtOrAfter(time: Instant): Int {
        val index = openedAt.binarySearch(time)
        val nextIndex = if (index >= 0) index else -index - 1
        return if (nextIndex <= candles.lastIndex) nextIndex else -1
    }

    fun takeLastAtOrBefore(
        time: Instant,
        count: Int,
    ): List<Candle> {
        val latestIndex = lastIndexAtOrBefore(time)
        if (latestIndex < 0) return emptyList()
        val startIndex = maxOf(0, latestIndex - count + 1)
        return candles.subList(startIndex, latestIndex + 1)
    }

    private fun lastIndexAtOrBefore(time: Instant): Int {
        val index = openedAt.binarySearch(time)
        return if (index >= 0) index else -index - 2
    }
}

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
                config.dailyTargetPct != null && dailyReturnPct >= config.dailyTargetPct -> "DAILY_TARGET_HIT"
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

private fun Double.improvedFor(
    side: Side,
    candidateStop: Double,
): Double =
    when (side) {
        Side.BUY -> maxOf(this, candidateStop)
        Side.SELL -> minOf(this, candidateStop)
    }

private fun Instant.utcDate(): LocalDate = atZone(ZoneOffset.UTC).toLocalDate()

private fun Timeframe.seconds(): Long =
    when (this) {
        Timeframe.M1 -> 60L
        Timeframe.M5 -> 300L
        Timeframe.M15 -> 900L
        Timeframe.H1 -> 3_600L
    }

private fun incrementReason(
    reason: String,
    counts: MutableMap<String, Int>,
) {
    counts[reason] = (counts[reason] ?: 0) + 1
}

private fun VolumeFlowSideMode.allows(side: Side): Boolean =
    when (this) {
        VolumeFlowSideMode.BOTH -> true
        VolumeFlowSideMode.LONG_ONLY -> side == Side.BUY
        VolumeFlowSideMode.SHORT_ONLY -> side == Side.SELL
    }

private fun VolumeFlowMarketRegime.allows(side: Side): Boolean =
    when (this) {
        VolumeFlowMarketRegime.TREND_UP -> side == Side.BUY
        VolumeFlowMarketRegime.TREND_DOWN -> side == Side.SELL
        VolumeFlowMarketRegime.RANGE -> true
        VolumeFlowMarketRegime.HIGH_VOLATILITY_CHOP -> false
        VolumeFlowMarketRegime.UNKNOWN -> false
    }

private fun List<VolumeFlowBacktestTrade>.tagSummaries(selector: (VolumeFlowBacktestTrade) -> String): List<VolumeFlowTagSummary> =
    groupBy(selector)
        .toSortedMap()
        .map { (tag, trades) ->
            val wins = trades.count { it.pnl > 0.0 }
            val grossProfit = trades.filter { it.pnl > 0.0 }.sumOf { it.pnl }
            val grossLoss = trades.filter { it.pnl < 0.0 }.sumOf { abs(it.pnl) }
            val winRatePct = if (trades.isEmpty()) 0.0 else (wins.toDouble() / trades.size) * 100.0
            val expectancyProfile = volumeFlowExpectancyProfile(trades.map { it.returnR }, winRatePct)
            VolumeFlowTagSummary(
                tag = tag,
                tradeCount = trades.size,
                netPnl = trades.sumOf { it.pnl },
                winRatePct = winRatePct,
                profitFactor = if (grossLoss == 0.0) null else grossProfit / grossLoss,
                expectancyR = if (trades.isEmpty()) 0.0 else trades.map { it.returnR }.average(),
                averageWinR = expectancyProfile.averageWinR,
                averageLossR = expectancyProfile.averageLossR,
                payoffRatio = expectancyProfile.payoffRatio,
                breakevenWinRatePct = expectancyProfile.breakevenWinRatePct,
                winRateEdgePct = expectancyProfile.winRateEdgePct,
            )
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
