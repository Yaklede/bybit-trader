package dev.yaklede.bybittrader.api.backtest

import dev.yaklede.bybittrader.domain.ResearchCandleLimits
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowBacktestConfig
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowCompositeBacktestConfig
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowCompositeBacktestLeg
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowCompositeBacktestReport
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowCompositeBacktestService
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowCompositeBacktestTrade
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowEntryMode
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowEquityCurvePoint
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowExitMode
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowLegExitSummary
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowMarketRegime
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowPeriodSummary
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowReplayCoverage
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowSetupMode
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowSideMode
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowTagSummary
import dev.yaklede.bybittrader.engine.backtest.defaultVolumeFlowMarketRegimes
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

fun Route.configureVolumeFlowCompositeBacktestRoutes(
    compositeBacktestService: VolumeFlowCompositeBacktestService,
    currentConfigProvider: VolumeFlowCompositeCurrentConfigProvider? = null,
) {
    authenticate("control") {
        post("/backtests/volume-flow/composite/run") {
            val request = call.receive<VolumeFlowCompositeBacktestRequest>().validated()
            call.respond(compositeBacktestService.run(request).toResponse(request))
        }
        if (currentConfigProvider != null) {
            post("/backtests/volume-flow/composite/current/run") {
                val overrideRequest = call.receive<VolumeFlowCompositeCurrentBacktestRequest>()
                val request = overrideRequest.toRunRequest(currentConfigProvider.load().validated())
                call.respond(compositeBacktestService.run(request).toResponse(request))
            }
        }
    }
}

fun interface VolumeFlowCompositeCurrentConfigProvider {
    suspend fun load(): VolumeFlowCompositeBacktestRequest
}

class FileVolumeFlowCompositeCurrentConfigProvider(
    private val path: Path,
    private val json: Json = Json { ignoreUnknownKeys = false },
) : VolumeFlowCompositeCurrentConfigProvider {
    override suspend fun load(): VolumeFlowCompositeBacktestRequest =
        withContext(Dispatchers.IO) {
            require(Files.exists(path)) {
                "Current volume-flow composite config file does not exist: ${path.toAbsolutePath()}"
            }
            json.decodeFromString<VolumeFlowCompositeBacktestRequest>(Files.readString(path)).validated()
        }
}

@Serializable
data class VolumeFlowCompositeCurrentBacktestRequest(
    val symbol: String? = null,
    val m1Limit: Int? = null,
    val m5Limit: Int? = null,
    val m15Limit: Int? = null,
    val replayStartAt: String? = null,
    val replayEndAt: String? = null,
    val tradeLimit: Int? = null,
    val equityCurveLimit: Int? = null,
    val drawdownEventLimit: Int? = null,
) {
    fun toRunRequest(base: VolumeFlowCompositeBacktestRequest): VolumeFlowCompositeBacktestRequest =
        base
            .copy(
                symbol = symbol ?: base.symbol,
                m1Limit = m1Limit ?: base.m1Limit,
                m5Limit = m5Limit ?: base.m5Limit,
                m15Limit = m15Limit ?: base.m15Limit,
                replayStartAt = replayStartAt ?: base.replayStartAt,
                replayEndAt = replayEndAt ?: base.replayEndAt,
                tradeLimit = tradeLimit ?: base.tradeLimit,
                equityCurveLimit = equityCurveLimit ?: base.equityCurveLimit,
                drawdownEventLimit = drawdownEventLimit ?: base.drawdownEventLimit,
            ).validated()
}

@Serializable
data class VolumeFlowCompositeBacktestRequest(
    val symbol: String,
    val m1Limit: Int = 3_000,
    val m5Limit: Int = 1_000,
    val m15Limit: Int = 500,
    val replayStartAt: String? = null,
    val replayEndAt: String? = null,
    val initialEquity: Double = 10_000.0,
    val dailyTargetPct: Double? = null,
    val dailyStopPct: Double = 1.0,
    val minTradesPerDay: Int = 1,
    val maxTradesPerDay: Int = 5,
    val maxConsecutiveLosses: Int = 3,
    val maxConcurrentPositions: Int = 1,
    val dedupeSameSetupSignals: Boolean = false,
    val portfolioDrawdownThrottlePct: Double? = null,
    val portfolioDrawdownRiskMultiplier: Double = 1.0,
    val portfolioDrawdownCooldownDays: Int = 0,
    val tradeLimit: Int = 50,
    val equityCurveLimit: Int = 500,
    val drawdownEventLimit: Int = 20,
    val legs: List<VolumeFlowCompositeLegRequest>,
) {
    fun validated(): VolumeFlowCompositeBacktestRequest {
        Symbol(symbol)
        require(m1Limit in 60..ResearchCandleLimits.MAX_M1_REPLAY_CANDLES) {
            "M1 limit must be between 60 and ${ResearchCandleLimits.MAX_M1_REPLAY_CANDLES}."
        }
        require(m5Limit in 30..ResearchCandleLimits.MAX_M5_REPLAY_CANDLES) {
            "M5 limit must be between 30 and ${ResearchCandleLimits.MAX_M5_REPLAY_CANDLES}."
        }
        require(m15Limit in 30..ResearchCandleLimits.MAX_M15_REPLAY_CANDLES) {
            "M15 limit must be between 30 and ${ResearchCandleLimits.MAX_M15_REPLAY_CANDLES}."
        }
        val parsedReplayStartAt = replayStartAt?.let(::parseReplayInstant)
        val parsedReplayEndAt = replayEndAt?.let(::parseReplayInstant)
        require((parsedReplayStartAt == null) == (parsedReplayEndAt == null)) {
            "Replay start and end timestamps must both be set or both be omitted."
        }
        require(parsedReplayStartAt == null || parsedReplayEndAt == null || parsedReplayEndAt.isAfter(parsedReplayStartAt)) {
            "Replay end timestamp must be after replay start timestamp."
        }
        require(tradeLimit in 0..10_000) { "Trade limit must be between 0 and 10000." }
        require(equityCurveLimit in 0..10_000) { "Equity curve limit must be between 0 and 10000." }
        require(drawdownEventLimit in 0..1_000) { "Drawdown event limit must be between 0 and 1000." }
        toConfig()
        return this
    }

    fun replayStartInstant(): Instant? = replayStartAt?.let(::parseReplayInstant)

    fun replayEndInstant(): Instant? = replayEndAt?.let(::parseReplayInstant)

    fun toConfig(): VolumeFlowCompositeBacktestConfig =
        VolumeFlowCompositeBacktestConfig(
            initialEquity = initialEquity,
            dailyTargetPct = dailyTargetPct,
            dailyStopPct = dailyStopPct,
            minTradesPerDay = minTradesPerDay,
            maxTradesPerDay = maxTradesPerDay,
            maxConsecutiveLosses = maxConsecutiveLosses,
            maxConcurrentPositions = maxConcurrentPositions,
            dedupeSameSetupSignals = dedupeSameSetupSignals,
            portfolioDrawdownThrottlePct = portfolioDrawdownThrottlePct,
            portfolioDrawdownRiskMultiplier = portfolioDrawdownRiskMultiplier,
            portfolioDrawdownCooldownDays = portfolioDrawdownCooldownDays,
            legs = legs.map { it.toLeg(initialEquity) },
        )
}

private fun parseReplayInstant(value: String): Instant =
    runCatching { Instant.parse(value) }
        .getOrElse { throw IllegalArgumentException("Replay timestamps must be ISO-8601 instants.") }

@Serializable
data class VolumeFlowCompositeLegRequest(
    val id: String,
    val riskFraction: Double = 0.0075,
    val feeRate: Double = 0.0006,
    val slippageRate: Double = 0.0002,
    val setupMode: String = "BREAKOUT_CONTINUATION",
    val entryMode: String = "RETEST_CONFIRMATION",
    val sideMode: String = "BOTH",
    val setupTimeframe: String = "M5",
    val volumeLookback: Int = 20,
    val relativeVolumeThreshold: Double = 5.0,
    val maxRelativeVolumeThreshold: Double? = null,
    val volumeZScoreThreshold: Double = 1.5,
    val setupRangeLookback: Int = 12,
    val requireM5Vwap: Boolean = false,
    val m5VwapLookback: Int = 12,
    val contextVwapLookback: Int = 32,
    val requireContextVwap: Boolean = true,
    val requireContextTrend: Boolean = true,
    val allowedMarketRegimes: List<String>? = null,
    val requireRegimeSideAlignment: Boolean = false,
    val minTrendMovePct: Double = 0.003,
    val minTrendEfficiency: Double = 0.35,
    val highVolatilityRangePct: Double = 0.006,
    val requireKeyLevelProximity: Boolean = false,
    val keyLevelTolerancePct: Double = 0.0025,
    val avoidRangeMiddle: Boolean = false,
    val minBodyRatio: Double = 0.45,
    val minDirectionalCloseStrength: Double = 0.70,
    val minRejectionWickRatio: Double = 0.25,
    val entryLookaheadM1Candles: Int = 5,
    val entryRetestTolerancePct: Double = 0.0015,
    val minEntryRiskPct: Double? = null,
    val maxEntryRiskPct: Double? = null,
    val maxEstimatedFeeR: Double = 0.2,
    val targetR: Double = 1.2,
    val exitMode: String = "FIXED_TARGET",
    val runnerTrailActivationR: Double = 1.0,
    val runnerTrailDistanceR: Double = 0.5,
    val trendBreakLookbackM1Candles: Int = 5,
    val breakevenTriggerR: Double? = null,
    val followThroughCheckM1Candles: Int? = null,
    val minFollowThroughR: Double? = null,
    val adverseExitCheckM1Candles: Int? = null,
    val maxAdverseRBeforeExit: Double? = null,
    val minFavorableRBeforeAdverseExit: Double? = null,
    val profitProtectActivationR: Double? = null,
    val profitProtectFloorR: Double? = null,
    val maxHoldM1Candles: Int = 30,
) {
    fun toLeg(initialEquity: Double): VolumeFlowCompositeBacktestLeg {
        val parsedSetupTimeframe = Timeframe.valueOf(setupTimeframe)
        require(parsedSetupTimeframe == Timeframe.M1 || parsedSetupTimeframe == Timeframe.M5) {
            "Setup timeframe must be M1 or M5."
        }
        allowedMarketRegimes?.forEach(VolumeFlowMarketRegime::valueOf)
        return VolumeFlowCompositeBacktestLeg(
            id = id,
            config =
                VolumeFlowBacktestConfig(
                    initialEquity = initialEquity,
                    riskFraction = riskFraction,
                    feeRate = feeRate,
                    slippageRate = slippageRate,
                    setupMode = VolumeFlowSetupMode.valueOf(setupMode),
                    entryMode = VolumeFlowEntryMode.valueOf(entryMode),
                    sideMode = VolumeFlowSideMode.valueOf(sideMode),
                    setupTimeframe = parsedSetupTimeframe,
                    volumeLookback = volumeLookback,
                    relativeVolumeThreshold = relativeVolumeThreshold,
                    maxRelativeVolumeThreshold = maxRelativeVolumeThreshold,
                    volumeZScoreThreshold = volumeZScoreThreshold,
                    setupRangeLookback = setupRangeLookback,
                    requireM5Vwap = requireM5Vwap,
                    m5VwapLookback = m5VwapLookback,
                    contextVwapLookback = contextVwapLookback,
                    requireContextVwap = requireContextVwap,
                    requireContextTrend = requireContextTrend,
                    allowedMarketRegimes =
                        allowedMarketRegimes?.map(VolumeFlowMarketRegime::valueOf)?.toSet()
                            ?: defaultVolumeFlowMarketRegimes,
                    requireRegimeSideAlignment = requireRegimeSideAlignment,
                    minTrendMovePct = minTrendMovePct,
                    minTrendEfficiency = minTrendEfficiency,
                    highVolatilityRangePct = highVolatilityRangePct,
                    requireKeyLevelProximity = requireKeyLevelProximity,
                    keyLevelTolerancePct = keyLevelTolerancePct,
                    avoidRangeMiddle = avoidRangeMiddle,
                    minBodyRatio = minBodyRatio,
                    minDirectionalCloseStrength = minDirectionalCloseStrength,
                    minRejectionWickRatio = minRejectionWickRatio,
                    entryLookaheadM1Candles = entryLookaheadM1Candles,
                    entryRetestTolerancePct = entryRetestTolerancePct,
                    minEntryRiskPct = minEntryRiskPct,
                    maxEntryRiskPct = maxEntryRiskPct,
                    maxEstimatedFeeR = maxEstimatedFeeR,
                    targetR = targetR,
                    exitMode = VolumeFlowExitMode.valueOf(exitMode),
                    runnerTrailActivationR = runnerTrailActivationR,
                    runnerTrailDistanceR = runnerTrailDistanceR,
                    trendBreakLookbackM1Candles = trendBreakLookbackM1Candles,
                    breakevenTriggerR = breakevenTriggerR,
                    followThroughCheckM1Candles = followThroughCheckM1Candles,
                    minFollowThroughR = minFollowThroughR,
                    adverseExitCheckM1Candles = adverseExitCheckM1Candles,
                    maxAdverseRBeforeExit = maxAdverseRBeforeExit,
                    minFavorableRBeforeAdverseExit = minFavorableRBeforeAdverseExit,
                    profitProtectActivationR = profitProtectActivationR,
                    profitProtectFloorR = profitProtectFloorR,
                    maxHoldM1Candles = maxHoldM1Candles,
                ),
        )
    }
}

@Serializable
data class VolumeFlowCompositeBacktestResponse(
    val symbol: String,
    val m1CandleCount: Int,
    val m5CandleCount: Int,
    val m15CandleCount: Int,
    val startAt: String?,
    val endAt: String?,
    val requestedCoverage: List<VolumeFlowRequestedReplayCoverageResponse>,
    val effectiveCoverage: List<VolumeFlowEffectiveReplayCoverageResponse>,
    val commonReplayWindow: VolumeFlowReplayWindowResponse,
    val initialEquity: Double,
    val finalEquity: Double,
    val netPnl: Double,
    val netReturnPct: Double,
    val compoundDailyReturnPct: Double,
    val maxDrawdownPct: Double,
    val markToMarketMaxDrawdownPct: Double,
    val averageMaxFavorableExcursionR: Double,
    val averageMaxAdverseExcursionR: Double,
    val averageMfeCapturePct: Double?,
    val tradeCount: Int,
    val wins: Int,
    val losses: Int,
    val winRatePct: Double,
    val profitFactor: Double?,
    val expectancyR: Double,
    val averageWinR: Double,
    val averageLossR: Double,
    val payoffRatio: Double?,
    val breakevenWinRatePct: Double?,
    val winRateEdgePct: Double?,
    val maxConsecutiveLosses: Int,
    val observedDays: Int,
    val activeDays: Int,
    val activeDayCoveragePct: Double,
    val averageTradesPerDay: Double,
    val averageTradesPerActiveDay: Double,
    val tradeFrequencyTargetDays: Int,
    val tradeFrequencyTargetPct: Double,
    val belowMinTradeDays: Int,
    val aboveMaxTradeDays: Int,
    val targetHitDays: Int,
    val stopHitDays: Int,
    val setupCount: Int,
    val rejectedSetupCount: Int,
    val signalCount: Int,
    val skippedSignalCount: Int,
    val noTradeReasonCounts: Map<String, Int>,
    val performanceByLeg: List<VolumeFlowTagSummaryResponse>,
    val performanceByLegExit: List<VolumeFlowLegExitSummaryResponse>,
    val performanceBySetupMode: List<VolumeFlowTagSummaryResponse>,
    val performanceBySide: List<VolumeFlowTagSummaryResponse>,
    val performanceByExitReason: List<VolumeFlowTagSummaryResponse>,
    val performanceByMarketRegime: List<VolumeFlowTagSummaryResponse>,
    val performanceByVolumePattern: List<VolumeFlowTagSummaryResponse>,
    val monthlyPerformance: List<VolumeFlowPeriodSummaryResponse>,
    val walkForwardPerformance: List<VolumeFlowPeriodSummaryResponse>,
    val equityCurve: List<VolumeFlowEquityCurvePointResponse>,
    val drawdownEvents: List<VolumeFlowEquityCurvePointResponse>,
    val trades: List<VolumeFlowCompositeTradeResponse>,
)

@Serializable
data class VolumeFlowRequestedReplayCoverageResponse(
    val timeframe: String,
    val requestedLimit: Int,
    val requestedStartAt: String?,
    val requestedEndAt: String?,
)

@Serializable
data class VolumeFlowEffectiveReplayCoverageResponse(
    val timeframe: String,
    val actualCount: Int,
    val startAt: String?,
    val endAt: String?,
)

@Serializable
data class VolumeFlowReplayWindowResponse(
    val startAt: String?,
    val endAt: String?,
)

@Serializable
data class VolumeFlowEquityCurvePointResponse(
    val sequence: Int,
    val at: String,
    val legId: String,
    val side: String,
    val exitReason: String,
    val startingEquity: Double,
    val endingEquity: Double,
    val peakEquity: Double,
    val pnl: Double,
    val returnR: Double,
    val realizedDrawdownPct: Double,
    val markToMarketLowEquity: Double,
    val markToMarketDrawdownPct: Double,
    val maxUnrealizedDrawdownPct: Double,
    val maxAdverseExcursionR: Double,
)

@Serializable
data class VolumeFlowLegExitSummaryResponse(
    val legId: String,
    val exitReason: String,
    val tradeCount: Int,
    val netPnl: Double,
    val winRatePct: Double,
    val profitFactor: Double?,
    val expectancyR: Double,
    val averageWinR: Double,
    val averageLossR: Double,
    val payoffRatio: Double?,
    val breakevenWinRatePct: Double?,
    val winRateEdgePct: Double?,
    val averageMaxFavorableExcursionR: Double,
    val averageMaxAdverseExcursionR: Double,
    val averageMfeCapturePct: Double?,
)

@Serializable
data class VolumeFlowPeriodSummaryResponse(
    val period: String,
    val tradeCount: Int,
    val wins: Int,
    val losses: Int,
    val startingEquity: Double,
    val endingEquity: Double,
    val netPnl: Double,
    val returnPct: Double,
    val maxDrawdownPct: Double,
    val profitFactor: Double?,
    val expectancyR: Double,
    val averageWinR: Double,
    val averageLossR: Double,
    val payoffRatio: Double?,
    val breakevenWinRatePct: Double?,
    val winRateEdgePct: Double?,
)

@Serializable
data class VolumeFlowCompositeTradeResponse(
    val legId: String,
    val side: String,
    val setupMode: String,
    val setupAt: String,
    val entryAt: String,
    val exitAt: String,
    val entryPrice: Double,
    val stopPrice: Double,
    val targetPrice: Double,
    val exitPrice: Double,
    val quantity: Double,
    val grossPnl: Double,
    val fees: Double,
    val pnl: Double,
    val returnR: Double,
    val maxFavorableExcursionR: Double,
    val maxAdverseExcursionR: Double,
    val mfeCapturePct: Double?,
    val maxFavorablePrice: Double,
    val maxAdversePrice: Double,
    val maxUnrealizedProfitPct: Double,
    val maxUnrealizedDrawdownPct: Double,
    val exitReason: String,
    val marketRegime: String,
    val keyLevelType: String,
    val keyLevelDistancePct: Double,
    val volumePattern: String,
    val relativeVolume: Double,
    val volumeZScore: Double,
    val setupBodyRatio: Double,
    val setupCloseLocation: Double,
)

private fun VolumeFlowCompositeBacktestReport.toResponse(
    tradeLimit: Int,
    equityCurveLimit: Int,
    drawdownEventLimit: Int,
): VolumeFlowCompositeBacktestResponse =
    VolumeFlowCompositeBacktestResponse(
        symbol = symbol.value,
        m1CandleCount = m1CandleCount,
        m5CandleCount = m5CandleCount,
        m15CandleCount = m15CandleCount,
        startAt = startAt?.toString(),
        endAt = endAt?.toString(),
        requestedCoverage = replayCoverage.map(VolumeFlowReplayCoverage::toRequestedResponse),
        effectiveCoverage = replayCoverage.map(VolumeFlowReplayCoverage::toEffectiveResponse),
        commonReplayWindow =
            VolumeFlowReplayWindowResponse(
                startAt = commonReplayWindow.startAt?.toString(),
                endAt = commonReplayWindow.endAt?.toString(),
            ),
        initialEquity = initialEquity.roundForApi(),
        finalEquity = finalEquity.roundForApi(),
        netPnl = netPnl.roundForApi(),
        netReturnPct = netReturnPct.roundForApi(),
        compoundDailyReturnPct = compoundDailyReturnPct(netReturnPct, observedDays).roundForApi(),
        maxDrawdownPct = maxDrawdownPct.roundForApi(),
        markToMarketMaxDrawdownPct = markToMarketMaxDrawdownPct.roundForApi(),
        averageMaxFavorableExcursionR = averageMaxFavorableExcursionR.roundForApi(),
        averageMaxAdverseExcursionR = averageMaxAdverseExcursionR.roundForApi(),
        averageMfeCapturePct = averageMfeCapturePct?.roundForApi(),
        tradeCount = tradeCount,
        wins = wins,
        losses = losses,
        winRatePct = winRatePct.roundForApi(),
        profitFactor = profitFactor?.roundForApi(),
        expectancyR = expectancyR.roundForApi(),
        averageWinR = averageWinR.roundForApi(),
        averageLossR = averageLossR.roundForApi(),
        payoffRatio = payoffRatio?.roundForApi(),
        breakevenWinRatePct = breakevenWinRatePct?.roundForApi(),
        winRateEdgePct = winRateEdgePct?.roundForApi(),
        maxConsecutiveLosses = maxConsecutiveLosses,
        observedDays = observedDays,
        activeDays = activeDays,
        activeDayCoveragePct = activeDayCoveragePct.roundForApi(),
        averageTradesPerDay = averageTradesPerDay.roundForApi(),
        averageTradesPerActiveDay = averageTradesPerActiveDay.roundForApi(),
        tradeFrequencyTargetDays = tradeFrequencyTargetDays,
        tradeFrequencyTargetPct = tradeFrequencyTargetPct.roundForApi(),
        belowMinTradeDays = belowMinTradeDays,
        aboveMaxTradeDays = aboveMaxTradeDays,
        targetHitDays = targetHitDays,
        stopHitDays = stopHitDays,
        setupCount = setupCount,
        rejectedSetupCount = rejectedSetupCount,
        signalCount = signalCount,
        skippedSignalCount = skippedSignalCount,
        noTradeReasonCounts = noTradeReasonCounts,
        performanceByLeg = performanceByLeg.map(VolumeFlowTagSummary::toCompositeResponse),
        performanceByLegExit = performanceByLegExit.map(VolumeFlowLegExitSummary::toResponse),
        performanceBySetupMode = performanceBySetupMode.map(VolumeFlowTagSummary::toCompositeResponse),
        performanceBySide = performanceBySide.map(VolumeFlowTagSummary::toCompositeResponse),
        performanceByExitReason = performanceByExitReason.map(VolumeFlowTagSummary::toCompositeResponse),
        performanceByMarketRegime = performanceByMarketRegime.map(VolumeFlowTagSummary::toCompositeResponse),
        performanceByVolumePattern = performanceByVolumePattern.map(VolumeFlowTagSummary::toCompositeResponse),
        monthlyPerformance = monthlyPerformance.map(VolumeFlowPeriodSummary::toResponse),
        walkForwardPerformance = walkForwardPerformance.map(VolumeFlowPeriodSummary::toResponse),
        equityCurve = equityCurve.takeLast(equityCurveLimit).map(VolumeFlowEquityCurvePoint::toResponse),
        drawdownEvents =
            equityCurve
                .sortedWith(compareByDescending<VolumeFlowEquityCurvePoint> { it.markToMarketDrawdownPct }.thenBy { it.sequence })
                .take(drawdownEventLimit)
                .map(VolumeFlowEquityCurvePoint::toResponse),
        trades = trades.takeLast(tradeLimit).map(VolumeFlowCompositeBacktestTrade::toResponse),
    )

private suspend fun VolumeFlowCompositeBacktestService.run(
    request: VolumeFlowCompositeBacktestRequest,
): VolumeFlowCompositeBacktestReport =
    run(
        symbol = Symbol(request.symbol),
        m1Limit = request.m1Limit,
        m5Limit = request.m5Limit,
        m15Limit = request.m15Limit,
        config = request.toConfig(),
        replayStartAt = request.replayStartInstant(),
        replayEndAt = request.replayEndInstant(),
    )

private fun VolumeFlowCompositeBacktestReport.toResponse(request: VolumeFlowCompositeBacktestRequest): VolumeFlowCompositeBacktestResponse =
    toResponse(
        tradeLimit = request.tradeLimit,
        equityCurveLimit = request.equityCurveLimit,
        drawdownEventLimit = request.drawdownEventLimit,
    )

private fun VolumeFlowReplayCoverage.toRequestedResponse(): VolumeFlowRequestedReplayCoverageResponse =
    VolumeFlowRequestedReplayCoverageResponse(
        timeframe = timeframe.name,
        requestedLimit = requestedLimit,
        requestedStartAt = requestedStartAt?.toString(),
        requestedEndAt = requestedEndAt?.toString(),
    )

private fun VolumeFlowReplayCoverage.toEffectiveResponse(): VolumeFlowEffectiveReplayCoverageResponse =
    VolumeFlowEffectiveReplayCoverageResponse(
        timeframe = timeframe.name,
        actualCount = actualCount,
        startAt = startAt?.toString(),
        endAt = endAt?.toString(),
    )

private fun VolumeFlowEquityCurvePoint.toResponse(): VolumeFlowEquityCurvePointResponse =
    VolumeFlowEquityCurvePointResponse(
        sequence = sequence,
        at = at.toString(),
        legId = legId,
        side = side.name,
        exitReason = exitReason.name,
        startingEquity = startingEquity.roundForApi(),
        endingEquity = endingEquity.roundForApi(),
        peakEquity = peakEquity.roundForApi(),
        pnl = pnl.roundForApi(),
        returnR = returnR.roundForApi(),
        realizedDrawdownPct = realizedDrawdownPct.roundForApi(),
        markToMarketLowEquity = markToMarketLowEquity.roundForApi(),
        markToMarketDrawdownPct = markToMarketDrawdownPct.roundForApi(),
        maxUnrealizedDrawdownPct = maxUnrealizedDrawdownPct.roundForApi(),
        maxAdverseExcursionR = maxAdverseExcursionR.roundForApi(),
    )

private fun VolumeFlowPeriodSummary.toResponse(): VolumeFlowPeriodSummaryResponse =
    VolumeFlowPeriodSummaryResponse(
        period = period,
        tradeCount = tradeCount,
        wins = wins,
        losses = losses,
        startingEquity = startingEquity.roundForApi(),
        endingEquity = endingEquity.roundForApi(),
        netPnl = netPnl.roundForApi(),
        returnPct = returnPct.roundForApi(),
        maxDrawdownPct = maxDrawdownPct.roundForApi(),
        profitFactor = profitFactor?.roundForApi(),
        expectancyR = expectancyR.roundForApi(),
        averageWinR = averageWinR.roundForApi(),
        averageLossR = averageLossR.roundForApi(),
        payoffRatio = payoffRatio?.roundForApi(),
        breakevenWinRatePct = breakevenWinRatePct?.roundForApi(),
        winRateEdgePct = winRateEdgePct?.roundForApi(),
    )

private fun VolumeFlowTagSummary.toCompositeResponse(): VolumeFlowTagSummaryResponse =
    VolumeFlowTagSummaryResponse(
        tag = tag,
        tradeCount = tradeCount,
        netPnl = netPnl.roundForApi(),
        winRatePct = winRatePct.roundForApi(),
        profitFactor = profitFactor?.roundForApi(),
        expectancyR = expectancyR.roundForApi(),
        averageWinR = averageWinR.roundForApi(),
        averageLossR = averageLossR.roundForApi(),
        payoffRatio = payoffRatio?.roundForApi(),
        breakevenWinRatePct = breakevenWinRatePct?.roundForApi(),
        winRateEdgePct = winRateEdgePct?.roundForApi(),
        averageMaxFavorableExcursionR = averageMaxFavorableExcursionR.roundForApi(),
        averageMaxAdverseExcursionR = averageMaxAdverseExcursionR.roundForApi(),
        averageMfeCapturePct = averageMfeCapturePct?.roundForApi(),
    )

private fun VolumeFlowLegExitSummary.toResponse(): VolumeFlowLegExitSummaryResponse =
    VolumeFlowLegExitSummaryResponse(
        legId = legId,
        exitReason = exitReason.name,
        tradeCount = summary.tradeCount,
        netPnl = summary.netPnl.roundForApi(),
        winRatePct = summary.winRatePct.roundForApi(),
        profitFactor = summary.profitFactor?.roundForApi(),
        expectancyR = summary.expectancyR.roundForApi(),
        averageWinR = summary.averageWinR.roundForApi(),
        averageLossR = summary.averageLossR.roundForApi(),
        payoffRatio = summary.payoffRatio?.roundForApi(),
        breakevenWinRatePct = summary.breakevenWinRatePct?.roundForApi(),
        winRateEdgePct = summary.winRateEdgePct?.roundForApi(),
        averageMaxFavorableExcursionR = summary.averageMaxFavorableExcursionR.roundForApi(),
        averageMaxAdverseExcursionR = summary.averageMaxAdverseExcursionR.roundForApi(),
        averageMfeCapturePct = summary.averageMfeCapturePct?.roundForApi(),
    )

private fun VolumeFlowCompositeBacktestTrade.toResponse(): VolumeFlowCompositeTradeResponse =
    VolumeFlowCompositeTradeResponse(
        legId = legId,
        side = side.name,
        setupMode = setupMode.name,
        setupAt = setupAt.toString(),
        entryAt = entryAt.toString(),
        exitAt = exitAt.toString(),
        entryPrice = entryPrice.roundForApi(),
        stopPrice = stopPrice.roundForApi(),
        targetPrice = targetPrice.roundForApi(),
        exitPrice = exitPrice.roundForApi(),
        quantity = quantity.roundForApi(),
        grossPnl = grossPnl.roundForApi(),
        fees = fees.roundForApi(),
        pnl = pnl.roundForApi(),
        returnR = returnR.roundForApi(),
        maxFavorableExcursionR = maxFavorableExcursionR.roundForApi(),
        maxAdverseExcursionR = maxAdverseExcursionR.roundForApi(),
        mfeCapturePct = mfeCapturePct?.roundForApi(),
        maxFavorablePrice = maxFavorablePrice.roundForApi(),
        maxAdversePrice = maxAdversePrice.roundForApi(),
        maxUnrealizedProfitPct = maxUnrealizedProfitPct.roundForApi(),
        maxUnrealizedDrawdownPct = maxUnrealizedDrawdownPct.roundForApi(),
        exitReason = exitReason.name,
        marketRegime = marketRegime.name,
        keyLevelType = keyLevelType.name,
        keyLevelDistancePct = keyLevelDistancePct.roundForApi(),
        volumePattern = volumePattern.name,
        relativeVolume = relativeVolume.roundForApi(),
        volumeZScore = volumeZScore.roundForApi(),
        setupBodyRatio = setupBodyRatio.roundForApi(),
        setupCloseLocation = setupCloseLocation.roundForApi(),
    )
