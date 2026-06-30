package dev.yaklede.bybittrader.api.backtest

import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowBacktestSummary
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowEntryMode
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowMarketRegime
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowSetupMode
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowSideMode
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowSweepCandidate
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowSweepConfig
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowSweepReport
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowSweepResult
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowSweepService
import dev.yaklede.bybittrader.engine.backtest.defaultVolumeFlowMarketRegimes
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

fun Route.configureVolumeFlowSweepRoutes(sweepService: VolumeFlowSweepService) {
    authenticate("control") {
        post("/backtests/volume-flow/sweep") {
            val request = call.receive<VolumeFlowSweepRequest>().validated()
            val report =
                sweepService.run(
                    symbol = Symbol(request.symbol),
                    m1Limit = request.m1Limit,
                    m5Limit = request.m5Limit,
                    m15Limit = request.m15Limit,
                    sweepConfig = request.toSweepConfig(),
                )
            call.respond(report.toResponse(request.topResults))
        }
    }
}

@Serializable
data class VolumeFlowSweepRequest(
    val symbol: String,
    val m1Limit: Int = 3_000,
    val m5Limit: Int = 1_000,
    val m15Limit: Int = 500,
    val initialEquity: Double = 10_000.0,
    val riskFractionValues: List<Double> = listOf(0.0025, 0.005, 0.0075),
    val feeRate: Double = 0.0006,
    val slippageRate: Double = 0.0002,
    val setupModes: List<String> = listOf("BREAKOUT_CONTINUATION"),
    val entryModes: List<String> = listOf("RETEST_CONFIRMATION"),
    val sideModes: List<String> = listOf("BOTH"),
    val setupTimeframes: List<String> = listOf("M5"),
    val volumeLookback: Int = 20,
    val relativeVolumeThresholdValues: List<Double> = listOf(5.0, 7.0),
    val volumeZScoreThresholdValues: List<Double> = listOf(1.5),
    val setupRangeLookbackValues: List<Int> = listOf(12),
    val requireM5VwapValues: List<Boolean> = listOf(false, true),
    val m5VwapLookback: Int = 12,
    val contextVwapLookback: Int = 32,
    val requireContextVwapValues: List<Boolean> = listOf(true),
    val requireContextTrendValues: List<Boolean> = listOf(true, false),
    val allowedMarketRegimeValues: List<List<String>>? = null,
    val requireRegimeSideAlignmentValues: List<Boolean> = listOf(false),
    val minTrendMovePct: Double = 0.003,
    val minTrendEfficiency: Double = 0.35,
    val highVolatilityRangePct: Double = 0.006,
    val requireKeyLevelProximityValues: List<Boolean> = listOf(false),
    val keyLevelTolerancePctValues: List<Double> = listOf(0.0025),
    val avoidRangeMiddleValues: List<Boolean> = listOf(false),
    val minBodyRatioValues: List<Double> = listOf(0.45),
    val minRejectionWickRatioValues: List<Double> = listOf(0.25),
    val entryLookaheadM1CandlesValues: List<Int> = listOf(5),
    val entryRetestTolerancePct: Double = 0.0015,
    val maxEstimatedFeeRValues: List<Double> = listOf(0.2),
    val targetRValues: List<Double> = listOf(1.0, 1.2),
    val breakevenTriggerRValues: List<Double?> = listOf(null),
    val maxHoldM1CandlesValues: List<Int> = listOf(15, 30),
    val dailyTargetPct: Double = 1.0,
    val dailyStopPct: Double = 1.0,
    val minTradesPerDay: Int = 1,
    val maxTradesPerDay: Int = 5,
    val maxConsecutiveLosses: Int = 3,
    val trainRatio: Double = 0.6,
    val maxCandidates: Int = 200,
    val topResults: Int = 10,
) {
    fun validated(): VolumeFlowSweepRequest {
        Symbol(symbol)
        require(m1Limit in 120..600_000) { "M1 limit must be between 120 and 600000." }
        require(m5Limit in 60..200_000) { "M5 limit must be between 60 and 200000." }
        require(m15Limit in 60..50_000) { "M15 limit must be between 60 and 50000." }
        require(topResults in 1..50) { "Top results must be between 1 and 50." }
        allowedMarketRegimeValues?.flatten()?.forEach(VolumeFlowMarketRegime::valueOf)
        toSweepConfig()
        return this
    }

    fun toSweepConfig(): VolumeFlowSweepConfig =
        VolumeFlowSweepConfig(
            initialEquity = initialEquity,
            riskFractionValues = riskFractionValues,
            feeRate = feeRate,
            slippageRate = slippageRate,
            setupModes = setupModes.map(VolumeFlowSetupMode::valueOf),
            entryModes = entryModes.map(VolumeFlowEntryMode::valueOf),
            sideModes = sideModes.map(VolumeFlowSideMode::valueOf),
            setupTimeframes = setupTimeframes.map(Timeframe::valueOf),
            volumeLookback = volumeLookback,
            relativeVolumeThresholdValues = relativeVolumeThresholdValues,
            volumeZScoreThresholdValues = volumeZScoreThresholdValues,
            setupRangeLookbackValues = setupRangeLookbackValues,
            requireM5VwapValues = requireM5VwapValues,
            m5VwapLookback = m5VwapLookback,
            contextVwapLookback = contextVwapLookback,
            requireContextVwapValues = requireContextVwapValues,
            requireContextTrendValues = requireContextTrendValues,
            allowedMarketRegimeValues =
                allowedMarketRegimeValues
                    ?.map { regimes -> regimes.map(VolumeFlowMarketRegime::valueOf).toSet() }
                    ?: listOf(defaultVolumeFlowMarketRegimes),
            requireRegimeSideAlignmentValues = requireRegimeSideAlignmentValues,
            minTrendMovePct = minTrendMovePct,
            minTrendEfficiency = minTrendEfficiency,
            highVolatilityRangePct = highVolatilityRangePct,
            requireKeyLevelProximityValues = requireKeyLevelProximityValues,
            keyLevelTolerancePctValues = keyLevelTolerancePctValues,
            avoidRangeMiddleValues = avoidRangeMiddleValues,
            minBodyRatioValues = minBodyRatioValues,
            minRejectionWickRatioValues = minRejectionWickRatioValues,
            entryLookaheadM1CandlesValues = entryLookaheadM1CandlesValues,
            entryRetestTolerancePct = entryRetestTolerancePct,
            maxEstimatedFeeRValues = maxEstimatedFeeRValues,
            targetRValues = targetRValues,
            breakevenTriggerRValues = breakevenTriggerRValues,
            maxHoldM1CandlesValues = maxHoldM1CandlesValues,
            dailyTargetPct = dailyTargetPct,
            dailyStopPct = dailyStopPct,
            minTradesPerDay = minTradesPerDay,
            maxTradesPerDay = maxTradesPerDay,
            maxConsecutiveLosses = maxConsecutiveLosses,
            trainRatio = trainRatio,
            maxCandidates = maxCandidates,
        )
}

@Serializable
data class VolumeFlowSweepResponse(
    val symbol: String,
    val candidateCount: Int,
    val m1CandleCount: Int,
    val m5CandleCount: Int,
    val m15CandleCount: Int,
    val trainM1CandleCount: Int,
    val trainM5CandleCount: Int,
    val trainM15CandleCount: Int,
    val testM1CandleCount: Int,
    val testM5CandleCount: Int,
    val testM15CandleCount: Int,
    val resultCount: Int,
    val results: List<VolumeFlowSweepResultResponse>,
)

@Serializable
data class VolumeFlowSweepResultResponse(
    val candidate: VolumeFlowSweepCandidateResponse,
    val train: VolumeFlowBacktestSummaryResponse,
    val test: VolumeFlowBacktestSummaryResponse,
    val testPassesProfitabilityGate: Boolean,
    val testPassesFrequencyGate: Boolean,
    val score: Double,
)

@Serializable
data class VolumeFlowSweepCandidateResponse(
    val riskFraction: Double,
    val setupMode: String,
    val entryMode: String,
    val sideMode: String,
    val setupTimeframe: String,
    val relativeVolumeThreshold: Double,
    val volumeZScoreThreshold: Double,
    val setupRangeLookback: Int,
    val requireM5Vwap: Boolean,
    val requireContextVwap: Boolean,
    val requireContextTrend: Boolean,
    val allowedMarketRegimes: List<String>,
    val requireRegimeSideAlignment: Boolean,
    val requireKeyLevelProximity: Boolean,
    val keyLevelTolerancePct: Double,
    val avoidRangeMiddle: Boolean,
    val minBodyRatio: Double,
    val minRejectionWickRatio: Double,
    val entryLookaheadM1Candles: Int,
    val maxEstimatedFeeR: Double,
    val targetR: Double,
    val breakevenTriggerR: Double?,
    val maxHoldM1Candles: Int,
)

@Serializable
data class VolumeFlowBacktestSummaryResponse(
    val tradeCount: Int,
    val netPnl: Double,
    val netReturnPct: Double,
    val maxDrawdownPct: Double,
    val profitFactor: Double?,
    val expectancyR: Double,
    val winRatePct: Double,
    val maxConsecutiveLosses: Int,
    val observedDays: Int,
    val activeDays: Int,
    val averageTradesPerDay: Double,
    val averageTradesPerActiveDay: Double,
    val tradeFrequencyTargetPct: Double,
    val belowMinTradeDays: Int,
    val aboveMaxTradeDays: Int,
    val setupCount: Int,
    val rejectedSetupCount: Int,
    val noTradeReasonCounts: Map<String, Int>,
)

private fun VolumeFlowSweepReport.toResponse(topResults: Int): VolumeFlowSweepResponse =
    VolumeFlowSweepResponse(
        symbol = symbol.value,
        candidateCount = candidateCount,
        m1CandleCount = m1CandleCount,
        m5CandleCount = m5CandleCount,
        m15CandleCount = m15CandleCount,
        trainM1CandleCount = trainM1CandleCount,
        trainM5CandleCount = trainM5CandleCount,
        trainM15CandleCount = trainM15CandleCount,
        testM1CandleCount = testM1CandleCount,
        testM5CandleCount = testM5CandleCount,
        testM15CandleCount = testM15CandleCount,
        resultCount = results.size,
        results = results.take(topResults).map { it.toResponse() },
    )

private fun VolumeFlowSweepResult.toResponse(): VolumeFlowSweepResultResponse =
    VolumeFlowSweepResultResponse(
        candidate = candidate.toResponse(),
        train = train.toResponse(),
        test = test.toResponse(),
        testPassesProfitabilityGate = testPassesProfitabilityGate,
        testPassesFrequencyGate = testPassesFrequencyGate,
        score = score.roundForApi(),
    )

private fun VolumeFlowSweepCandidate.toResponse(): VolumeFlowSweepCandidateResponse =
    VolumeFlowSweepCandidateResponse(
        riskFraction = riskFraction.roundForApi(),
        setupMode = setupMode.name,
        entryMode = entryMode.name,
        sideMode = sideMode.name,
        setupTimeframe = setupTimeframe.name,
        relativeVolumeThreshold = relativeVolumeThreshold.roundForApi(),
        volumeZScoreThreshold = volumeZScoreThreshold.roundForApi(),
        setupRangeLookback = setupRangeLookback,
        requireM5Vwap = requireM5Vwap,
        requireContextVwap = requireContextVwap,
        requireContextTrend = requireContextTrend,
        allowedMarketRegimes = allowedMarketRegimes.map { it.name },
        requireRegimeSideAlignment = requireRegimeSideAlignment,
        requireKeyLevelProximity = requireKeyLevelProximity,
        keyLevelTolerancePct = keyLevelTolerancePct.roundForApi(),
        avoidRangeMiddle = avoidRangeMiddle,
        minBodyRatio = minBodyRatio.roundForApi(),
        minRejectionWickRatio = minRejectionWickRatio.roundForApi(),
        entryLookaheadM1Candles = entryLookaheadM1Candles,
        maxEstimatedFeeR = maxEstimatedFeeR.roundForApi(),
        targetR = targetR.roundForApi(),
        breakevenTriggerR = breakevenTriggerR?.roundForApi(),
        maxHoldM1Candles = maxHoldM1Candles,
    )

private fun VolumeFlowBacktestSummary.toResponse(): VolumeFlowBacktestSummaryResponse =
    VolumeFlowBacktestSummaryResponse(
        tradeCount = tradeCount,
        netPnl = netPnl.roundForApi(),
        netReturnPct = netReturnPct.roundForApi(),
        maxDrawdownPct = maxDrawdownPct.roundForApi(),
        profitFactor = profitFactor?.roundForApi(),
        expectancyR = expectancyR.roundForApi(),
        winRatePct = winRatePct.roundForApi(),
        maxConsecutiveLosses = maxConsecutiveLosses,
        observedDays = observedDays,
        activeDays = activeDays,
        averageTradesPerDay = averageTradesPerDay.roundForApi(),
        averageTradesPerActiveDay = averageTradesPerActiveDay.roundForApi(),
        tradeFrequencyTargetPct = tradeFrequencyTargetPct.roundForApi(),
        belowMinTradeDays = belowMinTradeDays,
        aboveMaxTradeDays = aboveMaxTradeDays,
        setupCount = setupCount,
        rejectedSetupCount = rejectedSetupCount,
        noTradeReasonCounts = noTradeReasonCounts,
    )
