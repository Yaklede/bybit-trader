package dev.yaklede.bybittrader.engine.backtest

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.ResearchCandleLimits
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.execution.ExecutionSizingConstraints
import dev.yaklede.bybittrader.engine.execution.ExecutionTradePlanCalculator
import dev.yaklede.bybittrader.engine.market.MarketCandleStore
import dev.yaklede.bybittrader.engine.market.flow.FlowMarketDataStore
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.pow

private const val WALK_FORWARD_WINDOW_COUNT = 4

class VolumeFlowCompositeBacktestService(
    private val candleStore: MarketCandleStore,
    private val flowMarketDataStore: FlowMarketDataStore? = candleStore as? FlowMarketDataStore,
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

        val m1Candles =
            loadReplayCandles(
                symbol,
                Timeframe.M1,
                m1Limit,
                replayStartAt,
                replayEndAt,
                config.requiredWarmupCandles(Timeframe.M1),
            )
        val m5Candles =
            loadReplayCandles(
                symbol,
                Timeframe.M5,
                m5Limit,
                replayStartAt,
                replayEndAt,
                config.requiredWarmupCandles(Timeframe.M5),
            )
        val m15Candles =
            loadReplayCandles(
                symbol,
                Timeframe.M15,
                m15Limit,
                replayStartAt,
                replayEndAt,
                config.requiredWarmupCandles(Timeframe.M15),
            )
        require(m1Candles.isNotEmpty()) { "M1 replay candles are required." }
        require(m5Candles.isNotEmpty()) { "M5 replay candles are required." }
        require(m15Candles.isNotEmpty()) { "M15 replay candles are required." }

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
        warmupLimit: Int,
    ): List<Candle> =
        if (replayStartAt != null && replayEndAt != null) {
            val warmupBeforeAt = replayStartAt.minusSeconds(timeframe.seconds()).plusNanos(1)
            val warmup = candleStore.candlesBefore(symbol, timeframe, warmupBeforeAt, warmupLimit).sortedBy { it.openedAt }
            require(warmup.size >= warmupLimit) {
                "Not enough ${timeframe.name} warmup candles before the composite replay start."
            }
            val replay = candleStore.candlesBetween(symbol, timeframe, replayStartAt, replayEndAt, limit)
            (warmup + replay).distinctBy { it.openedAt }
        } else {
            candleStore.recentCandles(symbol, timeframe, limit)
        }.sortedBy { it.openedAt }

    internal suspend fun runLoadedCandles(
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
        val backtestService = VolumeFlowBacktestService(candleStore, flowMarketDataStore)
        val sharedFlowContext =
            compositeFlowLoadConfig(config)?.let { flowConfig ->
                backtestService.loadFlowContextIfEnabled(
                    symbol = symbol,
                    setupCandles = m5Candles,
                    config = flowConfig,
                    replayStartAt = replayRequest.startAt,
                    replayEndAt = replayRequest.endAt,
                )
            }
        val legReports =
            config.legs.map { leg ->
                val signalConfig =
                    leg.config.copy(
                        initialEquity = config.initialEquity,
                        quantityStep = config.quantityStep,
                        minQuantity = config.minQuantity,
                        maxQuantity = config.maxQuantity,
                        maxNotional = config.maxNotional,
                        leverage = config.leverage,
                        liquidationBufferPct = config.liquidationBufferPct,
                        dailyTargetPct = null,
                        dailyStopPct = 10.0,
                        minTradesPerDay = 1,
                        maxTradesPerDay = Int.MAX_VALUE,
                        maxConsecutiveLosses = Int.MAX_VALUE,
                    )
                LegSignalReport(
                    leg = leg.copy(config = signalConfig),
                    report =
                        backtestService.runLoadedCandles(
                            symbol = symbol,
                            m1Candles = m1Candles,
                            m5Candles = m5Candles,
                            m15Candles = m15Candles,
                            config = signalConfig,
                            replayStartAt = replayRequest.startAt,
                            replayEndAt = replayRequest.endAt,
                            flowContext = sharedFlowContext,
                        ),
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
        val replayCoverage =
            listOf(
                m1Candles.toReplayCoverage(Timeframe.M1, replayRequest.m1Limit, replayRequest.startAt, replayRequest.endAt),
                m5Candles.toReplayCoverage(Timeframe.M5, replayRequest.m5Limit, replayRequest.startAt, replayRequest.endAt),
                m15Candles.toReplayCoverage(Timeframe.M15, replayRequest.m15Limit, replayRequest.startAt, replayRequest.endAt),
            )
        val commonReplayWindow = replayCoverage.commonReplayWindow()
        val startAt = commonReplayWindow.startAt
        val endAt = commonReplayWindow.endAt
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
            m1CandleCount = replayCoverage.single { it.timeframe == Timeframe.M1 }.actualCount,
            m5CandleCount = replayCoverage.single { it.timeframe == Timeframe.M5 }.actualCount,
            m15CandleCount = replayCoverage.single { it.timeframe == Timeframe.M15 }.actualCount,
            startAt = startAt,
            endAt = endAt,
            replayCoverage = replayCoverage,
            commonReplayWindow = commonReplayWindow,
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
            liquidationCount = trades.count { it.exitReason == VolumeFlowExitReason.LIQUIDATION },
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
            robustnessSummary =
                trades.robustnessSummary(
                    initialEquity = config.initialEquity,
                    startAt = startAt,
                    endAt = endAt,
                    config = config,
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

private fun VolumeFlowCompositeBacktestConfig.requiredWarmupCandles(timeframe: Timeframe): Int =
    when (timeframe) {
        Timeframe.M1 ->
            legs.maxOf { leg -> maxOf(leg.config.volumeLookback, leg.config.setupRangeLookback) } + 1
        Timeframe.M5 ->
            legs.maxOf { leg ->
                maxOf(
                    leg.config.m5VwapLookback,
                    if (leg.config.setupTimeframe == Timeframe.M5) {
                        maxOf(leg.config.volumeLookback, leg.config.setupRangeLookback) + 1
                    } else {
                        0
                    },
                )
            }
        Timeframe.M15 ->
            legs.maxOf { leg ->
                val config = leg.config
                val macroLookback = if (config.usesMacroTrendContext()) config.macroTrendLookbackM15Candles else 0
                maxOf(config.contextVwapLookback, macroLookback)
            }
        Timeframe.H1 -> error("H1 is not used by the composite volume-flow replay.")
    }

private fun VolumeFlowBacktestConfig.usesMacroTrendContext(): Boolean =
    requireMacroTrendAlignment ||
        minMacroTrendMovePct > 0.0 ||
        minMacroTrendEfficiency != null ||
        macroTrendEfficiencyRelativeVolumeMin != null ||
        macroTrendEfficiencyRelativeVolumeMax != null ||
        macroTrendMismatchRiskMultiplier < 1.0 ||
        highContextRangeRelativeVolumeMacroBypassMovePct != null ||
        highContextRangeRelativeVolumeMacroBypassEfficiency != null

private fun compositeFlowLoadConfig(config: VolumeFlowCompositeBacktestConfig): VolumeFlowBacktestConfig? {
    val enabledConfigs = config.legs.map { it.config }.filter { it.flowFiltersEnabled() }
    if (enabledConfigs.isEmpty()) return null
    val first = enabledConfigs.first()
    return first.copy(
        flowLookbackM1Candles = enabledConfigs.maxOf { it.flowLookbackM1Candles },
        openInterestLookbackSnapshots = enabledConfigs.maxOf { it.openInterestLookbackSnapshots },
        maxFundingDataStalenessMinutes = enabledConfigs.maxOf { it.maxFundingDataStalenessMinutes },
    )
}

private fun Timeframe.seconds(): Long =
    when (this) {
        Timeframe.M1 -> 60L
        Timeframe.M5 -> 300L
        Timeframe.M15 -> 900L
        Timeframe.H1 -> 3_600L
    }

private fun List<Candle>.toReplayCoverage(
    timeframe: Timeframe,
    requestedLimit: Int,
    requestedStartAt: Instant?,
    requestedEndAt: Instant?,
): VolumeFlowReplayCoverage =
    if (requestedStartAt != null && requestedEndAt != null) {
        val replayCandles = filter { candle -> !candle.openedAt.isBefore(requestedStartAt) && !candle.openedAt.isAfter(requestedEndAt) }
        VolumeFlowReplayCoverage(
            timeframe = timeframe,
            requestedLimit = requestedLimit,
            requestedStartAt = requestedStartAt,
            requestedEndAt = requestedEndAt,
            actualCount = replayCandles.size,
            warmupCount = size - replayCandles.size,
            startAt = replayCandles.firstOrNull()?.openedAt,
            endAt = replayCandles.lastOrNull()?.openedAt,
        )
    } else {
        VolumeFlowReplayCoverage(
            timeframe = timeframe,
            requestedLimit = requestedLimit,
            requestedStartAt = null,
            requestedEndAt = null,
            actualCount = size,
            warmupCount = 0,
            startAt = firstOrNull()?.openedAt,
            endAt = lastOrNull()?.openedAt,
        )
    }

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
    val monthlyStates = mutableMapOf<String, CompositeMonthlyBacktestState>()
    val legDrawdownStates = mutableMapOf<String, CompositeLegDrawdownState>()

    fun legDrawdownState(legId: String): CompositeLegDrawdownState =
        legDrawdownStates.getOrPut(legId) { CompositeLegDrawdownState(config.initialEquity) }

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
        val monthState =
            monthlyStates.getOrPut(sourceTrade.setupAt.utcYearMonth()) {
                CompositeMonthlyBacktestState(startingEquity = equity)
            }
        if (monthState.blocksNewEntries()) {
            incrementCompositeReason(monthState.lockReason ?: "MONTHLY_LOCK", noTradeReasonCounts)
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

        val riskMultiplier =
            config.portfolioDrawdownRiskMultiplier(equity, peakEquity) *
                config.legDrawdownRiskMultiplier(signal.legId, legDrawdownState(signal.legId).riskDrawdownPct)
        val trade = signal.toCompositeTrade(equity, riskMultiplier, config, noTradeReasonCounts) ?: continue
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
        monthState.recordTrade(equity, config)
        legDrawdownState(trade.legId).recordTrade(trade)
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
    val monthlyStates = mutableMapOf<String, CompositeMonthlyBacktestState>()
    val acceptedEntriesByDay = mutableMapOf<LocalDate, Int>()
    val acceptedSetupKeys = mutableSetOf<CompositeSetupKey>()
    val legDrawdownStates = mutableMapOf<String, CompositeLegDrawdownState>()

    fun legDrawdownState(legId: String): CompositeLegDrawdownState =
        legDrawdownStates.getOrPut(legId) { CompositeLegDrawdownState(config.initialEquity) }

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
        monthlyStates
            .getOrPut(trade.setupAt.utcYearMonth()) {
                CompositeMonthlyBacktestState(startingEquity = equity - trade.pnl)
            }.recordTrade(equity, config)
        legDrawdownState(trade.legId).recordTrade(trade)
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
        val setupMonth = sourceTrade.setupAt.utcYearMonth()
        val monthState =
            monthlyStates.getOrPut(setupMonth) {
                CompositeMonthlyBacktestState(startingEquity = equity)
            }
        if (monthState.blocksNewEntries()) {
            incrementCompositeReason(monthState.lockReason ?: "MONTHLY_LOCK", noTradeReasonCounts)
            continue
        }
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

        val riskMultiplier =
            config.portfolioDrawdownRiskMultiplier(equity, peakEquity) *
                config.legDrawdownRiskMultiplier(signal.legId, legDrawdownState(signal.legId).riskDrawdownPct)
        val availableLeverageNotional =
            config.leverage?.let { leverage ->
                val openNotional = openTrades.sumOf { trade -> trade.entryPrice * trade.quantity }
                (equity * leverage - openNotional).coerceAtLeast(0.0)
            }
        val trade =
            signal.toCompositeTrade(
                equity = equity,
                riskMultiplier = riskMultiplier,
                config = config,
                noTradeReasonCounts = noTradeReasonCounts,
                availableLeverageNotional = availableLeverageNotional,
            ) ?: continue
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
    config: VolumeFlowCompositeBacktestConfig,
    noTradeReasonCounts: MutableMap<String, Int>,
    availableLeverageNotional: Double? = null,
): VolumeFlowCompositeBacktestTrade? {
    val sourceTrade = trade
    val riskPerUnit = abs(sourceTrade.entryPrice - sourceTrade.stopPrice)
    if (riskPerUnit <= 0.0) {
        incrementCompositeReason("INVALID_RISK_DISTANCE", noTradeReasonCounts)
        return null
    }

    val effectiveRiskFraction = riskFraction * sourceTrade.riskMultiplier * riskMultiplier
    val intendedRiskAmount = equity * effectiveRiskFraction
    val sizing =
        ExecutionTradePlanCalculator.calculateSizing(
            entryPrice = sourceTrade.entryPrice.toBigDecimal(),
            riskPerUnit = riskPerUnit.toBigDecimal(),
            intendedRisk = intendedRiskAmount.toBigDecimal(),
            accountEquity = equity.toBigDecimal(),
            constraints = config.sizingConstraints(availableLeverageNotional),
        )
    if (sizing == null) {
        incrementCompositeReason("EXECUTION_SIZE_UNAVAILABLE", noTradeReasonCounts)
        return null
    }
    val quantity = sizing.quantity.toDouble()
    val riskAmount = quantity * riskPerUnit
    val actualRiskFraction = if (equity <= 0.0) 0.0 else riskAmount / equity
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
        riskMultiplier = sourceTrade.riskMultiplier * riskMultiplier,
        macroTrendMovePct = sourceTrade.macroTrendMovePct,
        macroTrendEfficiency = sourceTrade.macroTrendEfficiency,
        contextTrendMovePct = sourceTrade.contextTrendMovePct,
        contextTrendEfficiency = sourceTrade.contextTrendEfficiency,
        contextRangePct = sourceTrade.contextRangePct,
        contextQuoteVolume = sourceTrade.contextQuoteVolume,
        entryDelayM1Candles = sourceTrade.entryDelayM1Candles,
        entryBodyRatio = sourceTrade.entryBodyRatio,
        entryCloseLocation = sourceTrade.entryCloseLocation,
        entryRelativeVolume = sourceTrade.entryRelativeVolume,
        entryVolumeZScore = sourceTrade.entryVolumeZScore,
        entryRiskPct = sourceTrade.entryRiskPct,
        maxFavorableExcursionR = sourceTrade.maxFavorableExcursionR,
        maxAdverseExcursionR = sourceTrade.maxAdverseExcursionR,
        mfeCapturePct = mfeCapturePct,
        maxFavorablePrice = sourceTrade.maxFavorablePrice,
        maxAdversePrice = sourceTrade.maxAdversePrice,
        maxUnrealizedProfitPct = sourceTrade.maxFavorableExcursionR * actualRiskFraction * 100.0,
        maxUnrealizedDrawdownPct = sourceTrade.maxAdverseExcursionR * actualRiskFraction * 100.0,
        exitReason = sourceTrade.exitReason,
        marketRegime = sourceTrade.marketRegime,
        keyLevelType = sourceTrade.keyLevelType,
        keyLevelDistancePct = sourceTrade.keyLevelDistancePct,
        volumePattern = sourceTrade.volumePattern,
        relativeVolume = sourceTrade.relativeVolume,
        volumeZScore = sourceTrade.volumeZScore,
        setupBodyRatio = sourceTrade.setupBodyRatio,
        setupCloseLocation = sourceTrade.setupCloseLocation,
        flowMetrics = sourceTrade.flowMetrics,
    )
}

private fun VolumeFlowCompositeBacktestConfig.sizingConstraints(availableLeverageNotional: Double? = null): ExecutionSizingConstraints =
    ExecutionSizingConstraints(
        quantityStep = quantityStep?.toBigDecimal(),
        minQuantity = minQuantity?.toBigDecimal(),
        maxQuantity = maxQuantity?.toBigDecimal(),
        maxNotional = listOfNotNull(maxNotional, availableLeverageNotional).minOrNull()?.toBigDecimal(),
        leverage = leverage?.toBigDecimal(),
    )

private fun VolumeFlowCompositeBacktestConfig.portfolioDrawdownRiskMultiplier(
    equity: Double,
    peakEquity: Double,
): Double =
    if (portfolioDrawdownThrottlePct != null && portfolioDrawdownPct(equity, peakEquity) >= portfolioDrawdownThrottlePct) {
        portfolioDrawdownRiskMultiplier
    } else {
        1.0
    }

private fun VolumeFlowCompositeBacktestConfig.legDrawdownRiskMultiplier(
    legId: String,
    drawdownPct: Double,
): Double {
    val rule = legDrawdownRiskRules.firstOrNull { it.legId == legId } ?: return 1.0
    return if (drawdownPct >= rule.drawdownThresholdPct) rule.riskMultiplier else 1.0
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

private class CompositeLegDrawdownState(
    private val initialEquity: Double,
) {
    var riskDrawdownPct: Double = 0.0
        private set

    private var equity: Double = initialEquity
    private var peakEquity: Double = initialEquity

    fun recordTrade(trade: VolumeFlowCompositeBacktestTrade) {
        val markToMarketLowEquity = equity * (1.0 - (trade.maxUnrealizedDrawdownPct / 100.0))
        val markToMarketDrawdownPct = portfolioDrawdownPct(markToMarketLowEquity, peakEquity)

        equity += trade.pnl
        peakEquity = maxOf(peakEquity, equity)
        val realizedDrawdownPct = portfolioDrawdownPct(equity, peakEquity)
        riskDrawdownPct = maxOf(markToMarketDrawdownPct, realizedDrawdownPct)
    }
}

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

private class CompositeMonthlyBacktestState(
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
        currentEquity: Double,
        config: VolumeFlowCompositeBacktestConfig,
    ) {
        tradeCount += 1
        netPnl = currentEquity - startingEquity
        val monthlyReturnPct = (netPnl / startingEquity) * 100.0
        lockReason =
            when {
                config.monthlyStopPct != null && monthlyReturnPct <= -config.monthlyStopPct -> "MONTHLY_STOP_HIT"
                currentEquity.isNaN() -> "INVALID_EQUITY"
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

private fun List<VolumeFlowCompositeBacktestTrade>.robustnessSummary(
    initialEquity: Double,
    startAt: Instant?,
    endAt: Instant?,
    config: VolumeFlowCompositeBacktestConfig,
): VolumeFlowRobustnessSummary {
    if (startAt == null || endAt == null || !endAt.isAfter(startAt)) {
        return emptyRobustnessSummary(config)
    }

    val replayStartAt = requireNotNull(startAt)
    val replayEndAt = requireNotNull(endAt)
    val windowDuration = Duration.ofDays(config.robustnessWindowDays.toLong())
    val stepDuration = Duration.ofDays(config.robustnessStepDays.toLong())
    val sortedPoints =
        sortedBy { it.exitAt }
            .zip(equityCurve(initialEquity))
            .sortedBy { it.first.exitAt }
    val windows = mutableListOf<VolumeFlowRobustnessWindowSummary>()
    var windowStart = replayStartAt
    while (!windowStart.plus(windowDuration).isAfter(replayEndAt)) {
        val windowEnd = windowStart.plus(windowDuration)
        val points =
            sortedPoints.filter { (trade, _) ->
                !trade.exitAt.isBefore(windowStart) && trade.exitAt.isBefore(windowEnd)
            }
        windows +=
            points.toRobustnessWindowSummary(
                initialEquity = initialEquity,
                windowStart = windowStart,
                windowEnd = windowEnd,
                config = config,
            )
        windowStart = windowStart.plus(stepDuration)
    }

    val passedCount = windows.count { it.passed }
    return VolumeFlowRobustnessSummary(
        windowDays = config.robustnessWindowDays,
        stepDays = config.robustnessStepDays,
        minReturnPct = config.robustnessMinReturnPct,
        maxDrawdownPct = config.robustnessMaxDrawdownPct,
        minTrades = config.robustnessMinTrades,
        windowCount = windows.size,
        passedWindowCount = passedCount,
        failedWindowCount = windows.size - passedCount,
        passRatePct = if (windows.isEmpty()) 0.0 else (passedCount.toDouble() / windows.size) * 100.0,
        worstReturnWindow = windows.minByOrNull { it.netReturnPct },
        worstDrawdownWindow = windows.maxByOrNull { it.markToMarketMaxDrawdownPct },
        windows = windows,
    )
}

private fun emptyRobustnessSummary(config: VolumeFlowCompositeBacktestConfig): VolumeFlowRobustnessSummary =
    VolumeFlowRobustnessSummary(
        windowDays = config.robustnessWindowDays,
        stepDays = config.robustnessStepDays,
        minReturnPct = config.robustnessMinReturnPct,
        maxDrawdownPct = config.robustnessMaxDrawdownPct,
        minTrades = config.robustnessMinTrades,
        windowCount = 0,
        passedWindowCount = 0,
        failedWindowCount = 0,
        passRatePct = 0.0,
        worstReturnWindow = null,
        worstDrawdownWindow = null,
        windows = emptyList(),
    )

private fun List<Pair<VolumeFlowCompositeBacktestTrade, VolumeFlowEquityCurvePoint>>.toRobustnessWindowSummary(
    initialEquity: Double,
    windowStart: Instant,
    windowEnd: Instant,
    config: VolumeFlowCompositeBacktestConfig,
): VolumeFlowRobustnessWindowSummary {
    var equity = initialEquity
    var peakEquity = initialEquity
    var maxDrawdownPct = 0.0
    var markToMarketMaxDrawdownPct = 0.0
    var maxConsecutiveLosses = 0
    var currentLosses = 0
    val syntheticPnls = mutableListOf<Double>()
    val returnRs = mutableListOf<Double>()
    val legStates = mutableMapOf<String, RobustnessLegState>()
    val activeDays = mutableSetOf<LocalDate>()

    forEach { (trade, point) ->
        val tradeReturnPct = if (point.startingEquity <= 0.0) 0.0 else point.pnl / point.startingEquity
        val syntheticPnl = equity * tradeReturnPct
        val markToMarketLowEquity = equity * (1.0 - (point.maxUnrealizedDrawdownPct / 100.0))
        markToMarketMaxDrawdownPct =
            maxOf(markToMarketMaxDrawdownPct, ((peakEquity - markToMarketLowEquity) / peakEquity) * 100.0)
        equity += syntheticPnl
        peakEquity = maxOf(peakEquity, equity)
        maxDrawdownPct = maxOf(maxDrawdownPct, ((peakEquity - equity) / peakEquity) * 100.0)
        currentLosses = nextRobustnessLossStreak(currentLosses, syntheticPnl, trade.exitReason)
        maxConsecutiveLosses = maxOf(maxConsecutiveLosses, currentLosses)
        syntheticPnls += syntheticPnl
        returnRs += point.returnR
        activeDays += trade.exitAt.utcDate()
        legStates.getOrPut(trade.legId) { RobustnessLegState() }.record(syntheticPnl)
    }

    val wins = syntheticPnls.count { it > 0.0 }
    val losses = syntheticPnls.count { it < 0.0 }
    val grossProfit = syntheticPnls.filter { it > 0.0 }.sum()
    val grossLoss = syntheticPnls.filter { it < 0.0 }.sumOf { abs(it) }
    val netPnl = equity - initialEquity
    val netReturnPct = if (initialEquity <= 0.0) 0.0 else (netPnl / initialEquity) * 100.0
    val observedDays = observedDaysBetween(windowStart, windowEnd.minusNanos(1))
    val worstLeg = legStates.minByOrNull { it.value.netPnl }
    val failReasons =
        buildList {
            if (syntheticPnls.size < config.robustnessMinTrades) add("TOO_FEW_TRADES")
            if (netReturnPct < config.robustnessMinReturnPct) add("RETURN_BELOW_MIN")
            if (markToMarketMaxDrawdownPct > config.robustnessMaxDrawdownPct) add("DRAWDOWN_ABOVE_MAX")
        }

    return VolumeFlowRobustnessWindowSummary(
        period = "${windowStart.utcDate()}..${windowEnd.minusNanos(1).utcDate()}",
        startAt = windowStart,
        endAt = windowEnd,
        observedDays = observedDays,
        tradeCount = syntheticPnls.size,
        wins = wins,
        losses = losses,
        activeDays = activeDays.size,
        activeDayCoveragePct = if (observedDays == 0) 0.0 else (activeDays.size.toDouble() / observedDays) * 100.0,
        startingEquity = initialEquity,
        endingEquity = equity,
        netPnl = netPnl,
        netReturnPct = netReturnPct,
        compoundDailyReturnPct = compoundDailyReturnPct(initialEquity, equity, observedDays),
        maxDrawdownPct = maxDrawdownPct,
        markToMarketMaxDrawdownPct = markToMarketMaxDrawdownPct,
        profitFactor = if (grossLoss == 0.0) null else grossProfit / grossLoss,
        expectancyR = if (returnRs.isEmpty()) 0.0 else returnRs.average(),
        winRatePct = if (syntheticPnls.isEmpty()) 0.0 else (wins.toDouble() / syntheticPnls.size) * 100.0,
        maxConsecutiveLosses = maxConsecutiveLosses,
        worstLegId = worstLeg?.key,
        worstLegNetPnl = worstLeg?.value?.netPnl,
        worstLegTradeCount = worstLeg?.value?.tradeCount ?: 0,
        passed = failReasons.isEmpty(),
        failReasons = failReasons,
    )
}

private fun nextRobustnessLossStreak(
    current: Int,
    pnl: Double,
    exitReason: VolumeFlowExitReason,
): Int =
    when {
        pnl >= 0.0 -> 0
        exitReason == VolumeFlowExitReason.BREAKEVEN_STOP -> current
        else -> current + 1
    }

private fun compoundDailyReturnPct(
    startingEquity: Double,
    endingEquity: Double,
    observedDays: Int,
): Double =
    if (startingEquity <= 0.0 || endingEquity <= 0.0 || observedDays <= 0) {
        0.0
    } else {
        ((endingEquity / startingEquity).pow(1.0 / observedDays.toDouble()) - 1.0) * 100.0
    }

private class RobustnessLegState {
    var tradeCount: Int = 0
        private set
    var netPnl: Double = 0.0
        private set

    fun record(pnl: Double) {
        tradeCount += 1
        netPnl += pnl
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
