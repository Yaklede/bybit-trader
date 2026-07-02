package dev.yaklede.bybittrader.engine.backtest

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.ResearchCandleLimits
import dev.yaklede.bybittrader.domain.Side
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
        replayStartAt: Instant? = null,
        replayEndAt: Instant? = null,
    ): VolumeFlowCompositeBacktestReport {
        require(m1Limit in 60..ResearchCandleLimits.MAX_M1_REPLAY_CANDLES) {
            "M1 candle limit must be between 60 and ${ResearchCandleLimits.MAX_M1_REPLAY_CANDLES}."
        }
        require(m5Limit in 30..ResearchCandleLimits.MAX_M5_REPLAY_CANDLES) {
            "M5 candle limit must be between 30 and ${ResearchCandleLimits.MAX_M5_REPLAY_CANDLES}."
        }
        require(m15Limit in 30..ResearchCandleLimits.MAX_M15_REPLAY_CANDLES) {
            "M15 candle limit must be between 30 and ${ResearchCandleLimits.MAX_M15_REPLAY_CANDLES}."
        }
        require((replayStartAt == null) == (replayEndAt == null)) {
            "Replay start and end timestamps must both be set or both be omitted."
        }
        require(replayStartAt == null || replayEndAt == null || replayEndAt.isAfter(replayStartAt)) {
            "Replay end timestamp must be after replay start timestamp."
        }

        val m1Candles = loadReplayCandles(symbol, Timeframe.M1, m1Limit, replayStartAt, replayEndAt)
        val m5Candles = loadReplayCandles(symbol, Timeframe.M5, m5Limit, replayStartAt, replayEndAt)
        val m15Candles = loadReplayCandles(symbol, Timeframe.M15, m15Limit, replayStartAt, replayEndAt)
        require(m1Candles.size >= 60) { "At least 60 M1 candles are required." }
        require(m5Candles.size >= 30) { "At least 30 M5 candles are required." }
        require(m15Candles.size >= 30) { "At least 30 M15 candles are required." }

        return runLoadedCandles(
            symbol = symbol,
            m1Candles = m1Candles,
            m5Candles = m5Candles,
            m15Candles = m15Candles,
            config = config,
            replayRequest =
                VolumeFlowReplayRequest(
                    m1Limit = m1Limit,
                    m5Limit = m5Limit,
                    m15Limit = m15Limit,
                    startAt = replayStartAt,
                    endAt = replayEndAt,
                ),
        )
    }

    private suspend fun loadReplayCandles(
        symbol: Symbol,
        timeframe: Timeframe,
        limit: Int,
        replayStartAt: Instant?,
        replayEndAt: Instant?,
    ): List<Candle> =
        if (replayStartAt != null && replayEndAt != null) {
            candleStore.candlesBetween(symbol, timeframe, replayStartAt, replayEndAt, limit)
        } else {
            candleStore.recentCandles(symbol, timeframe, limit)
        }.sortedBy { it.openedAt }

    internal fun runLoadedCandles(
        symbol: Symbol,
        m1Candles: List<Candle>,
        m5Candles: List<Candle>,
        m15Candles: List<Candle>,
        config: VolumeFlowCompositeBacktestConfig,
        replayRequest: VolumeFlowReplayRequest =
            VolumeFlowReplayRequest(
                m1Limit = m1Candles.size,
                m5Limit = m5Candles.size,
                m15Limit = m15Candles.size,
                startAt = null,
                endAt = null,
            ),
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
            replayRequest = replayRequest,
            finalEquity = simulationResult.finalEquity,
            maxDrawdownPct = simulationResult.maxDrawdownPct,
            markToMarketMaxDrawdownPct = simulationResult.markToMarketMaxDrawdownPct,
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
        replayRequest: VolumeFlowReplayRequest,
        finalEquity: Double,
        maxDrawdownPct: Double,
        markToMarketMaxDrawdownPct: Double,
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
        val replayCoverage =
            listOf(
                m1Candles.toReplayCoverage(Timeframe.M1, replayRequest.m1Limit, replayRequest.startAt, replayRequest.endAt),
                m5Candles.toReplayCoverage(Timeframe.M5, replayRequest.m5Limit, replayRequest.startAt, replayRequest.endAt),
                m15Candles.toReplayCoverage(Timeframe.M15, replayRequest.m15Limit, replayRequest.startAt, replayRequest.endAt),
            )
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
        val winRatePct = if (trades.isEmpty()) 0.0 else (wins.toDouble() / trades.size) * 100.0
        val expectancyProfile = volumeFlowExpectancyProfile(trades.map { it.returnR }, winRatePct)
        val mfeCaptures = trades.mapNotNull { it.mfeCapturePct }.filter { it.isFinite() }
        val netPnl = finalEquity - config.initialEquity
        return VolumeFlowCompositeBacktestReport(
            symbol = symbol,
            m1CandleCount = m1Candles.size,
            m5CandleCount = m5Candles.size,
            m15CandleCount = m15Candles.size,
            startAt = startAt,
            endAt = endAt,
            replayCoverage = replayCoverage,
            commonReplayWindow = replayCoverage.commonReplayWindow(),
            initialEquity = config.initialEquity,
            finalEquity = finalEquity,
            netPnl = netPnl,
            netReturnPct = (netPnl / config.initialEquity) * 100.0,
            maxDrawdownPct = maxDrawdownPct,
            markToMarketMaxDrawdownPct = markToMarketMaxDrawdownPct,
            averageMaxFavorableExcursionR =
                if (trades.isEmpty()) 0.0 else trades.map { it.maxFavorableExcursionR }.average(),
            averageMaxAdverseExcursionR =
                if (trades.isEmpty()) 0.0 else trades.map { it.maxAdverseExcursionR }.average(),
            averageMfeCapturePct = if (mfeCaptures.isEmpty()) null else mfeCaptures.average(),
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
            maxConsecutiveLosses = trades.maxCompositeConsecutiveLosses(),
            observedDays = observedDays,
            activeDays = activeDays,
            activeDayCoveragePct = if (observedDays == 0) 0.0 else (activeDays.toDouble() / observedDays) * 100.0,
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
            performanceByLegExit = trades.compositeLegExitSummaries(),
            performanceBySetupMode = trades.compositeTagSummaries { it.setupMode.name },
            performanceBySide = trades.compositeTagSummaries { it.side.name },
            performanceByExitReason = trades.compositeTagSummaries { it.exitReason.name },
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
            equityCurve = trades.equityCurve(config.initialEquity),
            trades = trades,
        )
    }
}

data class VolumeFlowReplayRequest(
    val m1Limit: Int,
    val m5Limit: Int,
    val m15Limit: Int,
    val startAt: Instant?,
    val endAt: Instant?,
)

private fun List<Candle>.toReplayCoverage(
    timeframe: Timeframe,
    requestedLimit: Int,
    requestedStartAt: Instant?,
    requestedEndAt: Instant?,
): VolumeFlowReplayCoverage =
    VolumeFlowReplayCoverage(
        timeframe = timeframe,
        requestedLimit = requestedLimit,
        requestedStartAt = requestedStartAt,
        requestedEndAt = requestedEndAt,
        actualCount = size,
        startAt = firstOrNull()?.openedAt,
        endAt = lastOrNull()?.openedAt,
    )

private fun List<VolumeFlowReplayCoverage>.commonReplayWindow(): VolumeFlowCommonReplayWindow {
    val starts = mapNotNull { it.startAt }
    val ends = mapNotNull { it.endAt }
    if (starts.size != size || ends.size != size) {
        return VolumeFlowCommonReplayWindow(startAt = null, endAt = null)
    }

    val startAt = starts.maxOrNull()
    val endAt = ends.minOrNull()
    return if (startAt != null && endAt != null && !endAt.isBefore(startAt)) {
        VolumeFlowCommonReplayWindow(startAt = startAt, endAt = endAt)
    } else {
        VolumeFlowCommonReplayWindow(startAt = null, endAt = null)
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
    var markToMarketMaxDrawdownPct = 0.0
    var blockedUntil = Instant.MIN
    var portfolioBlockedUntil = Instant.MIN
    var consecutiveLosses = 0
    val trades = mutableListOf<VolumeFlowCompositeBacktestTrade>()
    val dailyStates = mutableMapOf<LocalDate, CompositeDailyBacktestState>()

    for (signal in signals) {
        val sourceTrade = signal.trade
        if (!sourceTrade.setupAt.isAfter(blockedUntil)) {
            incrementCompositeReason("OVERLAPPING_POSITION", noTradeReasonCounts)
            continue
        }
        if (!sourceTrade.setupAt.isAfter(portfolioBlockedUntil)) {
            incrementCompositeReason("PORTFOLIO_DRAWDOWN_COOLDOWN", noTradeReasonCounts)
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

        val riskMultiplier = config.portfolioDrawdownRiskMultiplier(equity, peakEquity)
        val trade = signal.toCompositeTrade(equity, riskMultiplier, noTradeReasonCounts) ?: continue
        val markToMarketLowEquity = equity * (1.0 - (trade.maxUnrealizedDrawdownPct / 100.0))
        markToMarketMaxDrawdownPct =
            maxOf(markToMarketMaxDrawdownPct, ((peakEquity - markToMarketLowEquity) / peakEquity) * 100.0)
        equity += trade.pnl
        peakEquity = maxOf(peakEquity, equity)
        maxDrawdownPct = maxOf(maxDrawdownPct, ((peakEquity - equity) / peakEquity) * 100.0)
        portfolioBlockedUntil = config.nextPortfolioBlockedUntil(equity, peakEquity, sourceTrade.exitAt, portfolioBlockedUntil)
        consecutiveLosses = nextCompositeLossStreak(consecutiveLosses, trade)

        trades += trade
        dayState.recordTrade(trade.pnl, equity, config, consecutiveLosses)
        blockedUntil = sourceTrade.exitAt
    }

    return CompositeSimulationResult(
        finalEquity = equity,
        maxDrawdownPct = maxDrawdownPct,
        markToMarketMaxDrawdownPct = markToMarketMaxDrawdownPct,
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
    var markToMarketMaxDrawdownPct = 0.0
    var portfolioBlockedUntil = Instant.MIN
    var consecutiveLosses = 0
    val openTrades = mutableListOf<VolumeFlowCompositeBacktestTrade>()
    val closedTrades = mutableListOf<VolumeFlowCompositeBacktestTrade>()
    val dailyStates = mutableMapOf<LocalDate, CompositeDailyBacktestState>()
    val acceptedEntriesByDay = mutableMapOf<LocalDate, Int>()
    val acceptedSetupKeys = mutableSetOf<CompositeSetupKey>()

    fun closeTrade(trade: VolumeFlowCompositeBacktestTrade) {
        equity += trade.pnl
        peakEquity = maxOf(peakEquity, equity)
        maxDrawdownPct = maxOf(maxDrawdownPct, ((peakEquity - equity) / peakEquity) * 100.0)
        portfolioBlockedUntil = config.nextPortfolioBlockedUntil(equity, peakEquity, trade.exitAt, portfolioBlockedUntil)
        consecutiveLosses = nextCompositeLossStreak(consecutiveLosses, trade)
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

        if (!sourceTrade.setupAt.isAfter(portfolioBlockedUntil)) {
            incrementCompositeReason("PORTFOLIO_DRAWDOWN_COOLDOWN", noTradeReasonCounts)
            continue
        }
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
        val setupKey = sourceTrade.toCompositeSetupKey()
        if (config.dedupeSameSetupSignals && setupKey in acceptedSetupKeys) {
            incrementCompositeReason("DUPLICATE_SETUP_SIGNAL", noTradeReasonCounts)
            continue
        }

        val riskMultiplier = config.portfolioDrawdownRiskMultiplier(equity, peakEquity)
        val trade = signal.toCompositeTrade(equity, riskMultiplier, noTradeReasonCounts) ?: continue
        val markToMarketLowEquity = equity * (1.0 - (trade.maxUnrealizedDrawdownPct / 100.0))
        markToMarketMaxDrawdownPct =
            maxOf(markToMarketMaxDrawdownPct, ((peakEquity - markToMarketLowEquity) / peakEquity) * 100.0)
        acceptedEntriesByDay[setupDay] = (acceptedEntriesByDay[setupDay] ?: 0) + 1
        acceptedSetupKeys += setupKey
        openTrades += trade
    }

    openTrades
        .sortedBy { trade -> trade.exitAt }
        .forEach(::closeTrade)

    return CompositeSimulationResult(
        finalEquity = equity,
        maxDrawdownPct = maxDrawdownPct,
        markToMarketMaxDrawdownPct = markToMarketMaxDrawdownPct,
        noTradeReasonCounts = noTradeReasonCounts,
        dailyStates = dailyStates,
        trades = closedTrades,
    )
}

private fun CompositeSignal.toCompositeTrade(
    equity: Double,
    riskMultiplier: Double,
    noTradeReasonCounts: MutableMap<String, Int>,
): VolumeFlowCompositeBacktestTrade? {
    val sourceTrade = trade
    val riskPerUnit = abs(sourceTrade.entryPrice - sourceTrade.stopPrice)
    if (riskPerUnit <= 0.0) {
        incrementCompositeReason("INVALID_RISK_DISTANCE", noTradeReasonCounts)
        return null
    }

    val effectiveRiskFraction = riskFraction * riskMultiplier
    val riskAmount = equity * effectiveRiskFraction
    val quantity = riskAmount / riskPerUnit
    val quantityScale = if (sourceTrade.quantity <= 0.0) 0.0 else quantity / sourceTrade.quantity
    val grossPnl = sourceTrade.grossPnl * quantityScale
    val fees = sourceTrade.fees * quantityScale
    val pnl = grossPnl - fees
    val returnR = if (riskAmount <= 0.0) 0.0 else pnl / riskAmount
    val mfeCapturePct =
        if (sourceTrade.maxFavorableExcursionR <= 0.0) {
            null
        } else {
            (returnR / sourceTrade.maxFavorableExcursionR) * 100.0
        }

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
        returnR = returnR,
        maxFavorableExcursionR = sourceTrade.maxFavorableExcursionR,
        maxAdverseExcursionR = sourceTrade.maxAdverseExcursionR,
        mfeCapturePct = mfeCapturePct,
        maxFavorablePrice = sourceTrade.maxFavorablePrice,
        maxAdversePrice = sourceTrade.maxAdversePrice,
        maxUnrealizedProfitPct = sourceTrade.maxFavorableExcursionR * effectiveRiskFraction * 100.0,
        maxUnrealizedDrawdownPct = sourceTrade.maxAdverseExcursionR * effectiveRiskFraction * 100.0,
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

private fun VolumeFlowCompositeBacktestConfig.portfolioDrawdownRiskMultiplier(
    equity: Double,
    peakEquity: Double,
): Double =
    if (portfolioDrawdownThrottlePct != null && portfolioDrawdownPct(equity, peakEquity) >= portfolioDrawdownThrottlePct) {
        portfolioDrawdownRiskMultiplier
    } else {
        1.0
    }

private fun VolumeFlowCompositeBacktestConfig.nextPortfolioBlockedUntil(
    equity: Double,
    peakEquity: Double,
    exitAt: Instant,
    currentBlockedUntil: Instant,
): Instant {
    val throttlePct = portfolioDrawdownThrottlePct ?: return currentBlockedUntil
    if (portfolioDrawdownCooldownDays <= 0) return currentBlockedUntil
    if (portfolioDrawdownPct(equity, peakEquity) < throttlePct) return currentBlockedUntil

    val nextBlockedUntil = exitAt.plus(Duration.ofDays(portfolioDrawdownCooldownDays.toLong()))
    return if (nextBlockedUntil.isAfter(currentBlockedUntil)) nextBlockedUntil else currentBlockedUntil
}

private fun portfolioDrawdownPct(
    equity: Double,
    peakEquity: Double,
): Double = if (peakEquity <= 0.0) 0.0 else ((peakEquity - equity) / peakEquity) * 100.0

private data class CompositeSimulationResult(
    val finalEquity: Double,
    val maxDrawdownPct: Double,
    val markToMarketMaxDrawdownPct: Double,
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

private data class CompositeSetupKey(
    val setupAt: Instant,
    val side: Side,
    val setupMode: VolumeFlowSetupMode,
    val marketRegime: VolumeFlowMarketRegime,
    val volumePattern: VolumeFlowVolumePattern,
)

private fun VolumeFlowBacktestTrade.toCompositeSetupKey(): CompositeSetupKey =
    CompositeSetupKey(
        setupAt = setupAt,
        side = side,
        setupMode = setupMode,
        marketRegime = marketRegime,
        volumePattern = volumePattern,
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
        .map { (tag, trades) -> trades.toCompositeTagSummary(tag) }

private fun List<VolumeFlowCompositeBacktestTrade>.compositeLegExitSummaries(): List<VolumeFlowLegExitSummary> =
    groupBy { trade -> trade.legId to trade.exitReason }
        .entries
        .sortedWith(compareBy({ it.key.first }, { it.key.second.name }))
        .map { (key, trades) ->
            val (legId, exitReason) = key
            VolumeFlowLegExitSummary(
                legId = legId,
                exitReason = exitReason,
                summary = trades.toCompositeTagSummary("$legId:${exitReason.name}"),
            )
        }

private fun List<VolumeFlowCompositeBacktestTrade>.toCompositeTagSummary(tag: String): VolumeFlowTagSummary {
    val wins = count { it.pnl > 0.0 }
    val grossProfit = filter { it.pnl > 0.0 }.sumOf { it.pnl }
    val grossLoss = filter { it.pnl < 0.0 }.sumOf { abs(it.pnl) }
    val winRatePct = if (isEmpty()) 0.0 else (wins.toDouble() / size) * 100.0
    val expectancyProfile = volumeFlowExpectancyProfile(map { it.returnR }, winRatePct)
    val mfeCaptures = mapNotNull { it.mfeCapturePct }.filter { it.isFinite() }
    return VolumeFlowTagSummary(
        tag = tag,
        tradeCount = size,
        netPnl = sumOf { it.pnl },
        winRatePct = winRatePct,
        profitFactor = if (grossLoss == 0.0) null else grossProfit / grossLoss,
        expectancyR = if (isEmpty()) 0.0 else map { it.returnR }.average(),
        averageWinR = expectancyProfile.averageWinR,
        averageLossR = expectancyProfile.averageLossR,
        payoffRatio = expectancyProfile.payoffRatio,
        breakevenWinRatePct = expectancyProfile.breakevenWinRatePct,
        winRateEdgePct = expectancyProfile.winRateEdgePct,
        averageMaxFavorableExcursionR = if (isEmpty()) 0.0 else map { it.maxFavorableExcursionR }.average(),
        averageMaxAdverseExcursionR = if (isEmpty()) 0.0 else map { it.maxAdverseExcursionR }.average(),
        averageMfeCapturePct = if (mfeCaptures.isEmpty()) null else mfeCaptures.average(),
    )
}

private fun List<VolumeFlowCompositeBacktestTrade>.maxCompositeConsecutiveLosses(): Int {
    var current = 0
    var max = 0
    forEach { trade ->
        current = nextCompositeLossStreak(current, trade)
        max = maxOf(max, current)
    }
    return max
}

private fun nextCompositeLossStreak(
    current: Int,
    trade: VolumeFlowCompositeBacktestTrade,
): Int =
    when {
        trade.pnl >= 0.0 -> 0
        trade.exitReason == VolumeFlowExitReason.BREAKEVEN_STOP -> current
        else -> current + 1
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

private fun List<VolumeFlowCompositeBacktestTrade>.equityCurve(initialEquity: Double): List<VolumeFlowEquityCurvePoint> {
    var equity = initialEquity
    var peakEquity = initialEquity
    return mapIndexed { index, trade ->
        val startingEquity = equity
        val markToMarketLowEquity = startingEquity * (1.0 - (trade.maxUnrealizedDrawdownPct / 100.0))
        val markToMarketDrawdownPct =
            if (peakEquity <= 0.0) {
                0.0
            } else {
                maxOf(0.0, ((peakEquity - markToMarketLowEquity) / peakEquity) * 100.0)
            }

        equity += trade.pnl
        peakEquity = maxOf(peakEquity, equity)
        val realizedDrawdownPct =
            if (peakEquity <= 0.0) {
                0.0
            } else {
                maxOf(0.0, ((peakEquity - equity) / peakEquity) * 100.0)
            }

        VolumeFlowEquityCurvePoint(
            sequence = index + 1,
            at = trade.exitAt,
            legId = trade.legId,
            side = trade.side,
            exitReason = trade.exitReason,
            startingEquity = startingEquity,
            endingEquity = equity,
            peakEquity = peakEquity,
            pnl = trade.pnl,
            returnR = trade.returnR,
            realizedDrawdownPct = realizedDrawdownPct,
            markToMarketLowEquity = markToMarketLowEquity,
            markToMarketDrawdownPct = markToMarketDrawdownPct,
            maxUnrealizedDrawdownPct = trade.maxUnrealizedDrawdownPct,
            maxAdverseExcursionR = trade.maxAdverseExcursionR,
        )
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
        val winRatePct = if (trades.isEmpty()) 0.0 else (wins.toDouble() / trades.size) * 100.0
        val expectancyProfile = volumeFlowExpectancyProfile(trades.map { it.returnR }, winRatePct)
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
            averageWinR = expectancyProfile.averageWinR,
            averageLossR = expectancyProfile.averageLossR,
            payoffRatio = expectancyProfile.payoffRatio,
            breakevenWinRatePct = expectancyProfile.breakevenWinRatePct,
            winRateEdgePct = expectancyProfile.winRateEdgePct,
        )
    }
}
