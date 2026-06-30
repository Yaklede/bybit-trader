package dev.yaklede.bybittrader.engine.backtest

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.market.MarketCandleStore
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.abs

private const val WALK_FORWARD_WINDOW_COUNT = 4

class VolumeFlowCompositeBacktestService(
    private val candleStore: MarketCandleStore,
) {
    suspend fun run(
        symbol: Symbol,
        m1Limit: Int,
        m5Limit: Int,
        m15Limit: Int,
        config: VolumeFlowCompositeBacktestConfig,
    ): VolumeFlowCompositeBacktestReport {
        require(m1Limit in 60..600_000) { "M1 candle limit must be between 60 and 600000." }
        require(m5Limit in 30..200_000) { "M5 candle limit must be between 30 and 200000." }
        require(m15Limit in 30..50_000) { "M15 candle limit must be between 30 and 50000." }

        val m1Candles = candleStore.recentCandles(symbol, Timeframe.M1, m1Limit).sortedBy { it.openedAt }
        val m5Candles = candleStore.recentCandles(symbol, Timeframe.M5, m5Limit).sortedBy { it.openedAt }
        val m15Candles = candleStore.recentCandles(symbol, Timeframe.M15, m15Limit).sortedBy { it.openedAt }
        require(m1Candles.size >= 60) { "At least 60 M1 candles are required." }
        require(m5Candles.size >= 30) { "At least 30 M5 candles are required." }
        require(m15Candles.size >= 30) { "At least 30 M15 candles are required." }

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
        config: VolumeFlowCompositeBacktestConfig,
    ): VolumeFlowCompositeBacktestReport {
        val backtestService = VolumeFlowBacktestService(candleStore)
        val legReports =
            config.legs.map { leg ->
                val signalConfig =
                    leg.config.copy(
                        initialEquity = config.initialEquity,
                        dailyTargetPct = null,
                        dailyStopPct = 10.0,
                        minTradesPerDay = 1,
                        maxTradesPerDay = Int.MAX_VALUE,
                        maxConsecutiveLosses = Int.MAX_VALUE,
                    )
                LegSignalReport(
                    leg = leg.copy(config = signalConfig),
                    report = backtestService.runLoadedCandles(symbol, m1Candles, m5Candles, m15Candles, signalConfig),
                )
            }
        val signals =
            legReports
                .flatMap { legReport ->
                    legReport.report.trades.map { trade ->
                        CompositeSignal(
                            legId = legReport.leg.id,
                            riskFraction = legReport.leg.config.riskFraction,
                            trade = trade,
                        )
                    }
                }.sortedWith(compareBy<CompositeSignal> { it.trade.setupAt }.thenBy { it.trade.entryAt })

        val simulationResult =
            simulateCompositeSignals(
                config = config,
                signals = signals,
                legReports = legReports,
            )

        return buildCompositeReport(
            symbol = symbol,
            m1Candles = m1Candles,
            m5Candles = m5Candles,
            m15Candles = m15Candles,
            config = config,
            finalEquity = simulationResult.finalEquity,
            maxDrawdownPct = simulationResult.maxDrawdownPct,
            setupCount = legReports.sumOf { it.report.setupCount },
            rejectedSetupCount = legReports.sumOf { it.report.rejectedSetupCount },
            signalCount = signals.size,
            noTradeReasonCounts = simulationResult.noTradeReasonCounts.toSortedMap(),
            dailyStates = simulationResult.dailyStates,
            trades = simulationResult.trades,
        )
    }

    private fun buildCompositeReport(
        symbol: Symbol,
        m1Candles: List<Candle>,
        m5Candles: List<Candle>,
        m15Candles: List<Candle>,
        config: VolumeFlowCompositeBacktestConfig,
        finalEquity: Double,
        maxDrawdownPct: Double,
        setupCount: Int,
        rejectedSetupCount: Int,
        signalCount: Int,
        noTradeReasonCounts: Map<String, Int>,
        dailyStates: Map<LocalDate, CompositeDailyBacktestState>,
        trades: List<VolumeFlowCompositeBacktestTrade>,
    ): VolumeFlowCompositeBacktestReport {
        val startAt =
            listOfNotNull(
                m1Candles.firstOrNull()?.openedAt,
                m5Candles.firstOrNull()?.openedAt,
                m15Candles.firstOrNull()?.openedAt,
            ).minOrNull()
        val endAt =
            listOfNotNull(
                m1Candles.lastOrNull()?.openedAt,
                m5Candles.lastOrNull()?.openedAt,
                m15Candles.lastOrNull()?.openedAt,
            ).maxOrNull()
        val observedDays = observedDaysBetween(startAt, endAt)
        val activeDays = dailyStates.values.count { it.tradeCount > 0 }
        val tradeFrequencyTargetDays =
            dailyStates.values.count { dailyState ->
                dailyState.tradeCount in config.minTradesPerDay..config.maxTradesPerDay
            }
        val daysAtOrAboveMinTrades = dailyStates.values.count { it.tradeCount >= config.minTradesPerDay }
        val aboveMaxTradeDays = dailyStates.values.count { it.tradeCount > config.maxTradesPerDay }
        val wins = trades.count { it.pnl > 0.0 }
        val losses = trades.count { it.pnl < 0.0 }
        val grossProfit = trades.filter { it.pnl > 0.0 }.sumOf { it.pnl }
        val grossLoss = trades.filter { it.pnl < 0.0 }.sumOf { abs(it.pnl) }
        val netPnl = finalEquity - config.initialEquity
        return VolumeFlowCompositeBacktestReport(
            symbol = symbol,
            m1CandleCount = m1Candles.size,
            m5CandleCount = m5Candles.size,
            m15CandleCount = m15Candles.size,
            startAt = startAt,
            endAt = endAt,
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
            maxConsecutiveLosses = trades.maxCompositeConsecutiveLosses(),
            observedDays = observedDays,
            activeDays = activeDays,
            averageTradesPerDay = if (observedDays == 0) 0.0 else trades.size.toDouble() / observedDays,
            averageTradesPerActiveDay = if (activeDays == 0) 0.0 else trades.size.toDouble() / activeDays,
            tradeFrequencyTargetDays = tradeFrequencyTargetDays,
            tradeFrequencyTargetPct =
                if (observedDays == 0) 0.0 else (tradeFrequencyTargetDays.toDouble() / observedDays) * 100.0,
            belowMinTradeDays = observedDays - daysAtOrAboveMinTrades,
            aboveMaxTradeDays = aboveMaxTradeDays,
            targetHitDays = dailyStates.values.count { it.lockReason == "DAILY_TARGET_HIT" },
            stopHitDays = dailyStates.values.count { it.lockReason == "DAILY_STOP_HIT" },
            setupCount = setupCount,
            rejectedSetupCount = rejectedSetupCount,
            signalCount = signalCount,
            skippedSignalCount = signalCount - trades.size,
            noTradeReasonCounts = noTradeReasonCounts,
            performanceByLeg = trades.compositeTagSummaries { it.legId },
            performanceBySetupMode = trades.compositeTagSummaries { it.setupMode.name },
            performanceByMarketRegime = trades.compositeTagSummaries { it.marketRegime.name },
            performanceByVolumePattern = trades.compositeTagSummaries { it.volumePattern.name },
            monthlyPerformance = trades.monthlyPerformance(config.initialEquity),
            walkForwardPerformance =
                trades.walkForwardPerformance(
                    initialEquity = config.initialEquity,
                    startAt = startAt,
                    endAt = endAt,
                    windowCount = WALK_FORWARD_WINDOW_COUNT,
                ),
            trades = trades,
        )
    }
}

private fun simulateCompositeSignals(
    config: VolumeFlowCompositeBacktestConfig,
    signals: List<CompositeSignal>,
    legReports: List<LegSignalReport>,
): CompositeSimulationResult {
    val noTradeReasonCounts = mutableMapOf<String, Int>()
    legReports.forEach { legReport ->
        mergeReasonCounts(noTradeReasonCounts, legReport.report.noTradeReasonCounts)
    }

    return if (config.maxConcurrentPositions == 1) {
        simulateSinglePositionComposite(config, signals, noTradeReasonCounts)
    } else {
        simulateConcurrentComposite(config, signals, noTradeReasonCounts)
    }
}

private fun simulateSinglePositionComposite(
    config: VolumeFlowCompositeBacktestConfig,
    signals: List<CompositeSignal>,
    noTradeReasonCounts: MutableMap<String, Int>,
): CompositeSimulationResult {
    var equity = config.initialEquity
    var peakEquity = equity
    var maxDrawdownPct = 0.0
    var blockedUntil = Instant.MIN
    var consecutiveLosses = 0
    val trades = mutableListOf<VolumeFlowCompositeBacktestTrade>()
    val dailyStates = mutableMapOf<LocalDate, CompositeDailyBacktestState>()

    for (signal in signals) {
        val sourceTrade = signal.trade
        if (!sourceTrade.setupAt.isAfter(blockedUntil)) {
            incrementCompositeReason("OVERLAPPING_POSITION", noTradeReasonCounts)
            continue
        }
        val dayState =
            dailyStates.getOrPut(sourceTrade.setupAt.utcDate()) {
                CompositeDailyBacktestState(startingEquity = equity)
            }
        if (dayState.blocksNewEntries()) {
            incrementCompositeReason(dayState.lockReason ?: "DAILY_LOCK", noTradeReasonCounts)
            continue
        }

        val trade = signal.toCompositeTrade(equity, noTradeReasonCounts) ?: continue
        equity += trade.pnl
        peakEquity = maxOf(peakEquity, equity)
        maxDrawdownPct = maxOf(maxDrawdownPct, ((peakEquity - equity) / peakEquity) * 100.0)
        consecutiveLosses = if (trade.pnl < 0.0) consecutiveLosses + 1 else 0

        trades += trade
        dayState.recordTrade(trade.pnl, equity, config, consecutiveLosses)
        blockedUntil = sourceTrade.exitAt
    }

    return CompositeSimulationResult(
        finalEquity = equity,
        maxDrawdownPct = maxDrawdownPct,
        noTradeReasonCounts = noTradeReasonCounts,
        dailyStates = dailyStates,
        trades = trades,
    )
}

private fun simulateConcurrentComposite(
    config: VolumeFlowCompositeBacktestConfig,
    signals: List<CompositeSignal>,
    noTradeReasonCounts: MutableMap<String, Int>,
): CompositeSimulationResult {
    var equity = config.initialEquity
    var peakEquity = equity
    var maxDrawdownPct = 0.0
    var consecutiveLosses = 0
    val openTrades = mutableListOf<VolumeFlowCompositeBacktestTrade>()
    val closedTrades = mutableListOf<VolumeFlowCompositeBacktestTrade>()
    val dailyStates = mutableMapOf<LocalDate, CompositeDailyBacktestState>()
    val acceptedEntriesByDay = mutableMapOf<LocalDate, Int>()

    fun closeTrade(trade: VolumeFlowCompositeBacktestTrade) {
        equity += trade.pnl
        peakEquity = maxOf(peakEquity, equity)
        maxDrawdownPct = maxOf(maxDrawdownPct, ((peakEquity - equity) / peakEquity) * 100.0)
        consecutiveLosses = if (trade.pnl < 0.0) consecutiveLosses + 1 else 0
        closedTrades += trade
        dailyStates
            .getOrPut(trade.setupAt.utcDate()) {
                CompositeDailyBacktestState(startingEquity = equity)
            }.recordTrade(trade.pnl, equity, config, consecutiveLosses)
    }

    fun closeMaturedTrades(setupAt: Instant) {
        val maturedTrades =
            openTrades
                .filter { trade -> trade.exitAt.isBefore(setupAt) }
                .sortedBy { trade -> trade.exitAt }
        if (maturedTrades.isEmpty()) return

        openTrades.removeAll(maturedTrades.toSet())
        maturedTrades.forEach(::closeTrade)
    }

    for (signal in signals) {
        val sourceTrade = signal.trade
        closeMaturedTrades(sourceTrade.setupAt)

        if (openTrades.size >= config.maxConcurrentPositions) {
            incrementCompositeReason("OVERLAPPING_POSITION", noTradeReasonCounts)
            continue
        }

        val setupDay = sourceTrade.setupAt.utcDate()
        val dayState =
            dailyStates.getOrPut(setupDay) {
                CompositeDailyBacktestState(startingEquity = equity)
            }
        if (dayState.blocksNewEntries()) {
            incrementCompositeReason(dayState.lockReason ?: "DAILY_LOCK", noTradeReasonCounts)
            continue
        }
        if ((acceptedEntriesByDay[setupDay] ?: 0) >= config.maxTradesPerDay) {
            incrementCompositeReason("MAX_TRADES_PER_DAY", noTradeReasonCounts)
            continue
        }

        val trade = signal.toCompositeTrade(equity, noTradeReasonCounts) ?: continue
        acceptedEntriesByDay[setupDay] = (acceptedEntriesByDay[setupDay] ?: 0) + 1
        openTrades += trade
    }

    openTrades
        .sortedBy { trade -> trade.exitAt }
        .forEach(::closeTrade)

    return CompositeSimulationResult(
        finalEquity = equity,
        maxDrawdownPct = maxDrawdownPct,
        noTradeReasonCounts = noTradeReasonCounts,
        dailyStates = dailyStates,
        trades = closedTrades,
    )
}

private fun CompositeSignal.toCompositeTrade(
    equity: Double,
    noTradeReasonCounts: MutableMap<String, Int>,
): VolumeFlowCompositeBacktestTrade? {
    val sourceTrade = trade
    val riskPerUnit = abs(sourceTrade.entryPrice - sourceTrade.stopPrice)
    if (riskPerUnit <= 0.0) {
        incrementCompositeReason("INVALID_RISK_DISTANCE", noTradeReasonCounts)
        return null
    }

    val riskAmount = equity * riskFraction
    val quantity = riskAmount / riskPerUnit
    val quantityScale = if (sourceTrade.quantity <= 0.0) 0.0 else quantity / sourceTrade.quantity
    val grossPnl = sourceTrade.grossPnl * quantityScale
    val fees = sourceTrade.fees * quantityScale
    val pnl = grossPnl - fees

    return VolumeFlowCompositeBacktestTrade(
        legId = legId,
        side = sourceTrade.side,
        setupMode = sourceTrade.setupMode,
        setupAt = sourceTrade.setupAt,
        entryAt = sourceTrade.entryAt,
        exitAt = sourceTrade.exitAt,
        entryPrice = sourceTrade.entryPrice,
        stopPrice = sourceTrade.stopPrice,
        targetPrice = sourceTrade.targetPrice,
        exitPrice = sourceTrade.exitPrice,
        quantity = quantity,
        grossPnl = grossPnl,
        fees = fees,
        pnl = pnl,
        returnR = if (riskAmount <= 0.0) 0.0 else pnl / riskAmount,
        exitReason = sourceTrade.exitReason,
        marketRegime = sourceTrade.marketRegime,
        keyLevelType = sourceTrade.keyLevelType,
        keyLevelDistancePct = sourceTrade.keyLevelDistancePct,
        volumePattern = sourceTrade.volumePattern,
        relativeVolume = sourceTrade.relativeVolume,
        volumeZScore = sourceTrade.volumeZScore,
        setupBodyRatio = sourceTrade.setupBodyRatio,
        setupCloseLocation = sourceTrade.setupCloseLocation,
    )
}

private data class CompositeSimulationResult(
    val finalEquity: Double,
    val maxDrawdownPct: Double,
    val noTradeReasonCounts: Map<String, Int>,
    val dailyStates: Map<LocalDate, CompositeDailyBacktestState>,
    val trades: List<VolumeFlowCompositeBacktestTrade>,
)

private data class LegSignalReport(
    val leg: VolumeFlowCompositeBacktestLeg,
    val report: VolumeFlowBacktestReport,
)

private data class CompositeSignal(
    val legId: String,
    val riskFraction: Double,
    val trade: VolumeFlowBacktestTrade,
)

private class CompositeDailyBacktestState(
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
        config: VolumeFlowCompositeBacktestConfig,
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

private fun observedDaysBetween(
    startAt: Instant?,
    endAt: Instant?,
): Int {
    if (startAt == null || endAt == null) return 0
    val startDate = startAt.utcDate()
    val endDate = endAt.utcDate()
    return ChronoUnit.DAYS.between(startDate, endDate).toInt() + 1
}

private fun Instant.utcDate(): LocalDate = atZone(ZoneOffset.UTC).toLocalDate()

private fun Instant.utcYearMonth(): String = YearMonth.from(atZone(ZoneOffset.UTC)).toString()

private fun mergeReasonCounts(
    target: MutableMap<String, Int>,
    source: Map<String, Int>,
) {
    source.forEach { (reason, count) ->
        target[reason] = (target[reason] ?: 0) + count
    }
}

private fun incrementCompositeReason(
    reason: String,
    counts: MutableMap<String, Int>,
) {
    counts[reason] = (counts[reason] ?: 0) + 1
}

private fun List<VolumeFlowCompositeBacktestTrade>.compositeTagSummaries(
    selector: (VolumeFlowCompositeBacktestTrade) -> String,
): List<VolumeFlowTagSummary> =
    groupBy(selector)
        .toSortedMap()
        .map { (tag, trades) ->
            val wins = trades.count { it.pnl > 0.0 }
            val grossProfit = trades.filter { it.pnl > 0.0 }.sumOf { it.pnl }
            val grossLoss = trades.filter { it.pnl < 0.0 }.sumOf { abs(it.pnl) }
            VolumeFlowTagSummary(
                tag = tag,
                tradeCount = trades.size,
                netPnl = trades.sumOf { it.pnl },
                winRatePct = if (trades.isEmpty()) 0.0 else (wins.toDouble() / trades.size) * 100.0,
                profitFactor = if (grossLoss == 0.0) null else grossProfit / grossLoss,
                expectancyR = if (trades.isEmpty()) 0.0 else trades.map { it.returnR }.average(),
            )
        }

private fun List<VolumeFlowCompositeBacktestTrade>.maxCompositeConsecutiveLosses(): Int {
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

private fun List<VolumeFlowCompositeBacktestTrade>.monthlyPerformance(initialEquity: Double): List<VolumeFlowPeriodSummary> {
    var equity = initialEquity
    val states = linkedMapOf<String, CompositePeriodBacktestState>()
    forEach { trade ->
        val period = trade.exitAt.utcYearMonth()
        val state = states.getOrPut(period) { CompositePeriodBacktestState(period, equity) }
        equity += trade.pnl
        state.record(trade, equity)
    }
    return states.values.map(CompositePeriodBacktestState::toSummary)
}

private fun List<VolumeFlowCompositeBacktestTrade>.walkForwardPerformance(
    initialEquity: Double,
    startAt: Instant?,
    endAt: Instant?,
    windowCount: Int,
): List<VolumeFlowPeriodSummary> {
    if (startAt == null || endAt == null || !endAt.isAfter(startAt) || windowCount <= 0) return emptyList()

    var equity = initialEquity
    var tradeIndex = 0
    val sortedTrades = sortedBy { it.exitAt }
    val totalSeconds = Duration.between(startAt, endAt).seconds.coerceAtLeast(1L)
    return (0 until windowCount).map { windowIndex ->
        val windowStart = startAt.plusSeconds((totalSeconds * windowIndex) / windowCount)
        val windowEnd =
            if (windowIndex == windowCount - 1) {
                endAt.plusNanos(1)
            } else {
                startAt.plusSeconds((totalSeconds * (windowIndex + 1)) / windowCount)
            }
        val state =
            CompositePeriodBacktestState(
                period = "WF${windowIndex + 1}:${windowStart.utcDate()}..${windowEnd.minusNanos(1).utcDate()}",
                startingEquity = equity,
            )
        while (tradeIndex < sortedTrades.size && sortedTrades[tradeIndex].exitAt.isBefore(windowEnd)) {
            val trade = sortedTrades[tradeIndex]
            equity += trade.pnl
            state.record(trade, equity)
            tradeIndex += 1
        }
        state.toSummary()
    }
}

private class CompositePeriodBacktestState(
    private val period: String,
    private val startingEquity: Double,
) {
    private var endingEquity: Double = startingEquity
    private var peakEquity: Double = startingEquity
    private var maxDrawdownPct: Double = 0.0
    private val trades = mutableListOf<VolumeFlowCompositeBacktestTrade>()

    fun record(
        trade: VolumeFlowCompositeBacktestTrade,
        currentEquity: Double,
    ) {
        trades += trade
        endingEquity = currentEquity
        peakEquity = maxOf(peakEquity, endingEquity)
        maxDrawdownPct = maxOf(maxDrawdownPct, ((peakEquity - endingEquity) / peakEquity) * 100.0)
    }

    fun toSummary(): VolumeFlowPeriodSummary {
        val wins = trades.count { it.pnl > 0.0 }
        val losses = trades.count { it.pnl < 0.0 }
        val grossProfit = trades.filter { it.pnl > 0.0 }.sumOf { it.pnl }
        val grossLoss = trades.filter { it.pnl < 0.0 }.sumOf { abs(it.pnl) }
        val netPnl = endingEquity - startingEquity
        return VolumeFlowPeriodSummary(
            period = period,
            tradeCount = trades.size,
            wins = wins,
            losses = losses,
            startingEquity = startingEquity,
            endingEquity = endingEquity,
            netPnl = netPnl,
            returnPct = if (startingEquity <= 0.0) 0.0 else (netPnl / startingEquity) * 100.0,
            maxDrawdownPct = maxDrawdownPct,
            profitFactor = if (grossLoss == 0.0) null else grossProfit / grossLoss,
            expectancyR = if (trades.isEmpty()) 0.0 else trades.map { it.returnR }.average(),
        )
    }
}
