package dev.yaklede.bybittrader.api.backtest

import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowBacktestConfig
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowCompositeBacktestConfig
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowCompositeBacktestLeg
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowCompositeBacktestReport
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowCompositeBacktestService
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowCompositeBacktestTrade
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowEntryMode
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowExitMode
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowMarketRegime
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowPeriodSummary
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowSetupMode
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowSideMode
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowTagSummary
import dev.yaklede.bybittrader.engine.backtest.defaultVolumeFlowMarketRegimes
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

fun Route.configureVolumeFlowCompositeBacktestRoutes(compositeBacktestService: VolumeFlowCompositeBacktestService) {
    authenticate("control") {
        post("/backtests/volume-flow/composite/run") {
            val request = call.receive<VolumeFlowCompositeBacktestRequest>().validated()
            val result =
                compositeBacktestService.run(
                    symbol = Symbol(request.symbol),
                    m1Limit = request.m1Limit,
                    m5Limit = request.m5Limit,
                    m15Limit = request.m15Limit,
                    config = request.toConfig(),
                )
            call.respond(result.toResponse(request.tradeLimit))
        }
    }
}

@Serializable
data class VolumeFlowCompositeBacktestRequest(
    val symbol: String,
    val m1Limit: Int = 3_000,
    val m5Limit: Int = 1_000,
    val m15Limit: Int = 500,
    val initialEquity: Double = 10_000.0,
    val dailyTargetPct: Double? = null,
    val dailyStopPct: Double = 1.0,
    val minTradesPerDay: Int = 1,
    val maxTradesPerDay: Int = 5,
    val maxConsecutiveLosses: Int = 3,
    val maxConcurrentPositions: Int = 1,
    val tradeLimit: Int = 50,
    val legs: List<VolumeFlowCompositeLegRequest>,
) {
    fun validated(): VolumeFlowCompositeBacktestRequest {
        Symbol(symbol)
        require(m1Limit in 60..600_000) { "M1 limit must be between 60 and 600000." }
        require(m5Limit in 30..200_000) { "M5 limit must be between 30 and 200000." }
        require(m15Limit in 30..50_000) { "M15 limit must be between 30 and 50000." }
        require(tradeLimit in 0..10_000) { "Trade limit must be between 0 and 10000." }
        toConfig()
        return this
    }

    fun toConfig(): VolumeFlowCompositeBacktestConfig =
        VolumeFlowCompositeBacktestConfig(
            initialEquity = initialEquity,
            dailyTargetPct = dailyTargetPct,
            dailyStopPct = dailyStopPct,
            minTradesPerDay = minTradesPerDay,
            maxTradesPerDay = maxTradesPerDay,
            maxConsecutiveLosses = maxConsecutiveLosses,
            maxConcurrentPositions = maxConcurrentPositions,
            legs = legs.map { it.toLeg(initialEquity) },
        )
}

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
    val breakevenTriggerR: Double? = null,
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
                    breakevenTriggerR = breakevenTriggerR,
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
    val initialEquity: Double,
    val finalEquity: Double,
    val netPnl: Double,
    val netReturnPct: Double,
    val compoundDailyReturnPct: Double,
    val maxDrawdownPct: Double,
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
    val performanceBySetupMode: List<VolumeFlowTagSummaryResponse>,
    val performanceByMarketRegime: List<VolumeFlowTagSummaryResponse>,
    val performanceByVolumePattern: List<VolumeFlowTagSummaryResponse>,
    val monthlyPerformance: List<VolumeFlowPeriodSummaryResponse>,
    val walkForwardPerformance: List<VolumeFlowPeriodSummaryResponse>,
    val trades: List<VolumeFlowCompositeTradeResponse>,
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

private fun VolumeFlowCompositeBacktestReport.toResponse(tradeLimit: Int): VolumeFlowCompositeBacktestResponse =
    VolumeFlowCompositeBacktestResponse(
        symbol = symbol.value,
        m1CandleCount = m1CandleCount,
        m5CandleCount = m5CandleCount,
        m15CandleCount = m15CandleCount,
        startAt = startAt?.toString(),
        endAt = endAt?.toString(),
        initialEquity = initialEquity.roundForApi(),
        finalEquity = finalEquity.roundForApi(),
        netPnl = netPnl.roundForApi(),
        netReturnPct = netReturnPct.roundForApi(),
        compoundDailyReturnPct = compoundDailyReturnPct(netReturnPct, observedDays).roundForApi(),
        maxDrawdownPct = maxDrawdownPct.roundForApi(),
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
        performanceBySetupMode = performanceBySetupMode.map(VolumeFlowTagSummary::toCompositeResponse),
        performanceByMarketRegime = performanceByMarketRegime.map(VolumeFlowTagSummary::toCompositeResponse),
        performanceByVolumePattern = performanceByVolumePattern.map(VolumeFlowTagSummary::toCompositeResponse),
        monthlyPerformance = monthlyPerformance.map(VolumeFlowPeriodSummary::toResponse),
        walkForwardPerformance = walkForwardPerformance.map(VolumeFlowPeriodSummary::toResponse),
        trades = trades.takeLast(tradeLimit).map(VolumeFlowCompositeBacktestTrade::toResponse),
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
