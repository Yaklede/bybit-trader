package dev.yaklede.bybittrader.api.backtest

import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowBacktestConfig
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowBacktestReport
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowBacktestService
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowBacktestTrade
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

fun Route.configureVolumeFlowBacktestRoutes(volumeFlowBacktestService: VolumeFlowBacktestService) {
    authenticate("control") {
        post("/backtests/volume-flow/run") {
            val request = call.receive<VolumeFlowBacktestRequest>().validated()
            val result =
                volumeFlowBacktestService.run(
                    symbol = Symbol(request.symbol),
                    m1Limit = request.m1Limit,
                    m5Limit = request.m5Limit,
                    m15Limit = request.m15Limit,
                    config = request.toConfig(),
                )
            call.respond(result.toResponse())
        }
    }
}

@Serializable
data class VolumeFlowBacktestRequest(
    val symbol: String,
    val m1Limit: Int = 3_000,
    val m5Limit: Int = 1_000,
    val m15Limit: Int = 500,
    val initialEquity: Double = 10_000.0,
    val riskFraction: Double = 0.0025,
    val feeRate: Double = 0.0006,
    val slippageRate: Double = 0.0002,
    val volumeLookback: Int = 20,
    val relativeVolumeThreshold: Double = 7.0,
    val volumeZScoreThreshold: Double = 1.5,
    val setupRangeLookback: Int = 12,
    val contextVwapLookback: Int = 32,
    val requireContextTrend: Boolean = true,
    val minBodyRatio: Double = 0.45,
    val entryLookaheadM1Candles: Int = 5,
    val entryRetestTolerancePct: Double = 0.0015,
    val maxEstimatedFeeR: Double = 0.2,
    val targetR: Double = 1.2,
    val maxHoldM1Candles: Int = 15,
    val dailyTargetPct: Double = 1.0,
    val dailyStopPct: Double = 1.0,
    val maxTradesPerDay: Int = 5,
    val maxConsecutiveLosses: Int = 3,
) {
    fun validated(): VolumeFlowBacktestRequest {
        Symbol(symbol)
        require(m1Limit in 60..600_000) { "M1 limit must be between 60 and 600000." }
        require(m5Limit in 30..200_000) { "M5 limit must be between 30 and 200000." }
        require(m15Limit in 30..50_000) { "M15 limit must be between 30 and 50000." }
        toConfig()
        return this
    }

    fun toConfig(): VolumeFlowBacktestConfig =
        VolumeFlowBacktestConfig(
            initialEquity = initialEquity,
            riskFraction = riskFraction,
            feeRate = feeRate,
            slippageRate = slippageRate,
            volumeLookback = volumeLookback,
            relativeVolumeThreshold = relativeVolumeThreshold,
            volumeZScoreThreshold = volumeZScoreThreshold,
            setupRangeLookback = setupRangeLookback,
            contextVwapLookback = contextVwapLookback,
            requireContextTrend = requireContextTrend,
            minBodyRatio = minBodyRatio,
            entryLookaheadM1Candles = entryLookaheadM1Candles,
            entryRetestTolerancePct = entryRetestTolerancePct,
            maxEstimatedFeeR = maxEstimatedFeeR,
            targetR = targetR,
            maxHoldM1Candles = maxHoldM1Candles,
            dailyTargetPct = dailyTargetPct,
            dailyStopPct = dailyStopPct,
            maxTradesPerDay = maxTradesPerDay,
            maxConsecutiveLosses = maxConsecutiveLosses,
        )
}

@Serializable
data class VolumeFlowBacktestResponse(
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
    val maxDrawdownPct: Double,
    val tradeCount: Int,
    val wins: Int,
    val losses: Int,
    val winRatePct: Double,
    val profitFactor: Double?,
    val expectancyR: Double,
    val maxConsecutiveLosses: Int,
    val activeDays: Int,
    val targetHitDays: Int,
    val stopHitDays: Int,
    val setupCount: Int,
    val rejectedSetupCount: Int,
    val noTradeReasonCounts: Map<String, Int>,
    val trades: List<VolumeFlowTradeResponse>,
)

@Serializable
data class VolumeFlowTradeResponse(
    val side: String,
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
    val relativeVolume: Double,
    val volumeZScore: Double,
    val setupBodyRatio: Double,
    val setupCloseLocation: Double,
)

private fun VolumeFlowBacktestReport.toResponse(): VolumeFlowBacktestResponse =
    VolumeFlowBacktestResponse(
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
        maxDrawdownPct = maxDrawdownPct.roundForApi(),
        tradeCount = tradeCount,
        wins = wins,
        losses = losses,
        winRatePct = winRatePct.roundForApi(),
        profitFactor = profitFactor?.roundForApi(),
        expectancyR = expectancyR.roundForApi(),
        maxConsecutiveLosses = maxConsecutiveLosses,
        activeDays = activeDays,
        targetHitDays = targetHitDays,
        stopHitDays = stopHitDays,
        setupCount = setupCount,
        rejectedSetupCount = rejectedSetupCount,
        noTradeReasonCounts = noTradeReasonCounts,
        trades = trades.takeLast(20).map(VolumeFlowBacktestTrade::toResponse),
    )

private fun VolumeFlowBacktestTrade.toResponse(): VolumeFlowTradeResponse =
    VolumeFlowTradeResponse(
        side = side.name,
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
        relativeVolume = relativeVolume.roundForApi(),
        volumeZScore = volumeZScore.roundForApi(),
        setupBodyRatio = setupBodyRatio.roundForApi(),
        setupCloseLocation = setupCloseLocation.roundForApi(),
    )
