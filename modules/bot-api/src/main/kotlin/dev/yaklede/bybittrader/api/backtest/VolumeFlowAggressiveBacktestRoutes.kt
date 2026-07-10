package dev.yaklede.bybittrader.api.backtest

import dev.yaklede.bybittrader.domain.ResearchCandleLimits
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowAggressiveBacktestConfig
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowAggressiveBacktestReport
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowAggressiveBacktestService
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowAggressiveBacktestTrade
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowAggressiveProfiles
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.time.Instant

fun Route.configureVolumeFlowAggressiveBacktestRoutes(aggressiveBacktestService: VolumeFlowAggressiveBacktestService) {
    authenticate("control") {
        post("/backtests/volume-flow/aggressive/current/run") {
            val request = call.receive<VolumeFlowAggressiveCurrentBacktestRequest>().validated()
            val report =
                aggressiveBacktestService.run(
                    symbol = Symbol(request.symbol),
                    m1Limit = request.m1Limit,
                    m5Limit = request.m5Limit,
                    config = request.toConfig(),
                    replayStartAt = request.replayStartInstant(),
                    replayEndAt = request.replayEndInstant(),
                )
            call.respond(report.toResponse(request.tradeLimit))
        }
    }
}

@Serializable
data class VolumeFlowAggressiveCurrentBacktestRequest(
    val symbol: String = "BTCUSDT",
    val m1Limit: Int = ResearchCandleLimits.MAX_M1_REPLAY_CANDLES,
    val m5Limit: Int = 315_648,
    val replayStartAt: String? = null,
    val replayEndAt: String? = null,
    val initialEquity: Double? = null,
    val riskFraction: Double? = null,
    val feeRate: Double? = null,
    val slippageRate: Double? = null,
    val quantityStep: Double? = null,
    val minQuantity: Double? = null,
    val maxQuantity: Double? = null,
    val maxNotional: Double? = null,
    val leverage: Double? = null,
    val liquidationBufferPct: Double? = null,
    val sessionHoursUtc: Set<Int>? = null,
    val tradeLimit: Int = 50,
) {
    fun validated(): VolumeFlowAggressiveCurrentBacktestRequest {
        Symbol(symbol)
        require(m1Limit in 1..ResearchCandleLimits.MAX_M1_REPLAY_CANDLES) {
            "M1 limit must be between 1 and ${ResearchCandleLimits.MAX_M1_REPLAY_CANDLES}."
        }
        require(m5Limit in 30..ResearchCandleLimits.MAX_M5_REPLAY_CANDLES) {
            "M5 limit must be between 30 and ${ResearchCandleLimits.MAX_M5_REPLAY_CANDLES}."
        }
        val parsedReplayStartAt = replayStartAt?.let(::parseAggressiveReplayInstant)
        val parsedReplayEndAt = replayEndAt?.let(::parseAggressiveReplayInstant)
        require((parsedReplayStartAt == null) == (parsedReplayEndAt == null)) {
            "Replay start and end timestamps must both be set or both be omitted."
        }
        require(parsedReplayStartAt == null || parsedReplayEndAt == null || parsedReplayEndAt.isAfter(parsedReplayStartAt)) {
            "Replay end timestamp must be after replay start timestamp."
        }
        require(tradeLimit in 0..10_000) { "Trade limit must be between 0 and 10000." }
        require(sessionHoursUtc == null || sessionHoursUtc.isNotEmpty()) { "Session hours must not be empty." }
        require(sessionHoursUtc == null || sessionHoursUtc.all { it in 0..23 }) {
            "Session hours must be between 0 and 23."
        }
        toConfig()
        return this
    }

    fun replayStartInstant(): Instant? = replayStartAt?.let(::parseAggressiveReplayInstant)

    fun replayEndInstant(): Instant? = replayEndAt?.let(::parseAggressiveReplayInstant)

    fun toConfig(): VolumeFlowAggressiveBacktestConfig {
        val base = VolumeFlowAggressiveProfiles.finalUsV1()
        return base.copy(
            initialEquity = initialEquity ?: base.initialEquity,
            riskFraction = riskFraction ?: base.riskFraction,
            feeRate = feeRate ?: base.feeRate,
            slippageRate = slippageRate ?: base.slippageRate,
            quantityStep = quantityStep ?: base.quantityStep,
            minQuantity = minQuantity ?: base.minQuantity,
            maxQuantity = maxQuantity ?: base.maxQuantity,
            maxNotional = maxNotional ?: base.maxNotional,
            leverage = leverage ?: base.leverage,
            liquidationBufferPct = liquidationBufferPct ?: base.liquidationBufferPct,
            sessionHoursUtc = sessionHoursUtc ?: base.sessionHoursUtc,
        )
    }
}

private fun parseAggressiveReplayInstant(value: String): Instant =
    runCatching { Instant.parse(value) }
        .getOrElse { throw IllegalArgumentException("Replay timestamps must be ISO-8601 instants.") }

@Serializable
data class VolumeFlowAggressiveBacktestResponse(
    val engineVersion: String,
    val fillModelVersion: String,
    val validationStatus: String,
    val liveExpansionAllowed: Boolean,
    val symbol: String,
    val profileId: String,
    val m5CandleCount: Int,
    val m1CandleCount: Int,
    val warmupCandleCount: Int,
    val executionPathMode: String,
    val startAt: String?,
    val endAt: String?,
    val initialEquity: Double,
    val finalEquity: Double,
    val netPnl: Double,
    val netReturnPct: Double,
    val compoundDailyReturnPct: Double,
    val maxDrawdownPct: Double,
    val tradeCount: Int,
    val activeDays: Int,
    val observedDays: Int,
    val activeDayCoveragePct: Double,
    val skippedSignalCount: Int,
    val skippedDataGapCount: Int,
    val wins: Int,
    val losses: Int,
    val winRatePct: Double,
    val profitFactor: Double?,
    val trades: List<VolumeFlowAggressiveTradeResponse>,
)

@Serializable
data class VolumeFlowAggressiveTradeResponse(
    val signalAt: String,
    val openedAt: String,
    val closedAt: String,
    val side: String,
    val exitReason: String,
    val entryPrice: Double,
    val stopPrice: Double,
    val targetPrice: Double,
    val exitPrice: Double,
    val riskPerUnit: Double,
    val riskFraction: Double,
    val quantity: Double,
    val notional: Double,
    val stopAtr: Double,
    val targetR: Double,
    val rMultipleGross: Double,
    val rMultipleNet: Double,
    val pnl: Double,
    val returnPct: Double,
    val equityAfter: Double,
    val drawdownPct: Double,
    val entryRelativeVolume: Double,
    val entryRangePct: Double,
    val entryBodyRatio: Double,
    val entryCloseLocation: Double,
)

private fun VolumeFlowAggressiveBacktestReport.toResponse(tradeLimit: Int): VolumeFlowAggressiveBacktestResponse =
    VolumeFlowAggressiveBacktestResponse(
        engineVersion = engineVersion,
        fillModelVersion = fillModelVersion,
        validationStatus = validationStatus.name,
        liveExpansionAllowed = validationStatus == dev.yaklede.bybittrader.engine.backtest.StrategyValidationStatus.VERIFIED,
        symbol = symbol.value,
        profileId = profileId,
        m5CandleCount = m5CandleCount,
        m1CandleCount = m1CandleCount,
        warmupCandleCount = warmupCandleCount,
        executionPathMode = executionPathMode.name,
        startAt = startAt?.toString(),
        endAt = endAt?.toString(),
        initialEquity = initialEquity,
        finalEquity = finalEquity,
        netPnl = netPnl,
        netReturnPct = netReturnPct,
        compoundDailyReturnPct = compoundDailyReturnPct,
        maxDrawdownPct = maxDrawdownPct,
        tradeCount = tradeCount,
        activeDays = activeDays,
        observedDays = observedDays,
        activeDayCoveragePct = activeDayCoveragePct,
        skippedSignalCount = skippedSignalCount,
        skippedDataGapCount = skippedDataGapCount,
        wins = wins,
        losses = losses,
        winRatePct = winRatePct,
        profitFactor = profitFactor,
        trades = trades.takeLast(tradeLimit).map(VolumeFlowAggressiveBacktestTrade::toResponse),
    )

private fun VolumeFlowAggressiveBacktestTrade.toResponse(): VolumeFlowAggressiveTradeResponse =
    VolumeFlowAggressiveTradeResponse(
        signalAt = signalAt.toString(),
        openedAt = openedAt.toString(),
        closedAt = closedAt.toString(),
        side = side.name,
        exitReason = exitReason.name,
        entryPrice = entryPrice,
        stopPrice = stopPrice,
        targetPrice = targetPrice,
        exitPrice = exitPrice,
        riskPerUnit = riskPerUnit,
        riskFraction = riskFraction,
        quantity = quantity,
        notional = notional,
        stopAtr = stopAtr,
        targetR = targetR,
        rMultipleGross = rMultipleGross,
        rMultipleNet = rMultipleNet,
        pnl = pnl,
        returnPct = returnPct,
        equityAfter = equityAfter,
        drawdownPct = drawdownPct,
        entryRelativeVolume = entryRelativeVolume,
        entryRangePct = entryRangePct,
        entryBodyRatio = entryBodyRatio,
        entryCloseLocation = entryCloseLocation,
    )
