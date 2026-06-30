package dev.yaklede.bybittrader.api.backtest

import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.backtest.BacktestConfig
import dev.yaklede.bybittrader.engine.backtest.BacktestResult
import dev.yaklede.bybittrader.engine.backtest.BacktestService
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import kotlin.math.round

fun Route.configureBacktestRoutes(backtestService: BacktestService) {
    authenticate("control") {
        post("/backtests/run") {
            val request = call.receive<BacktestRunRequest>().validated()
            val result =
                backtestService.run(
                    symbol = Symbol(request.symbol),
                    timeframe = Timeframe.valueOf(request.timeframe),
                    candleLimit = request.candleLimit,
                    config =
                        BacktestConfig(
                            initialEquity = request.initialEquity,
                            riskFraction = request.riskFraction,
                            feeRate = request.feeRate,
                            slippageRate = request.slippageRate,
                            maxHoldCandles = request.maxHoldCandles,
                        ),
                )
            call.respond(result.toResponse())
        }
    }
}

@Serializable
data class BacktestRunRequest(
    val symbol: String,
    val timeframe: String,
    val candleLimit: Int = 500,
    val initialEquity: Double = 10_000.0,
    val riskFraction: Double = 0.005,
    val feeRate: Double = 0.0006,
    val slippageRate: Double = 0.0002,
    val maxHoldCandles: Int = 16,
) {
    fun validated(): BacktestRunRequest {
        Symbol(symbol)
        Timeframe.valueOf(timeframe)
        require(candleLimit in 30..1000) { "Candle limit must be between 30 and 1000." }
        BacktestConfig(
            initialEquity = initialEquity,
            riskFraction = riskFraction,
            feeRate = feeRate,
            slippageRate = slippageRate,
            maxHoldCandles = maxHoldCandles,
        )
        return this
    }
}

@Serializable
data class BacktestRunResponse(
    val symbol: String,
    val timeframe: String,
    val candleCount: Int,
    val startAt: String?,
    val endAt: String?,
    val initialEquity: Double,
    val finalEquity: Double,
    val netPnl: Double,
    val netReturnPct: Double,
    val expectedMonthlyReturnPct: Double?,
    val maxDrawdownPct: Double,
    val tradeCount: Int,
    val wins: Int,
    val losses: Int,
    val winRatePct: Double,
    val profitFactor: Double?,
    val expectancyR: Double,
    val trades: List<BacktestTradeResponse>,
)

@Serializable
data class BacktestTradeResponse(
    val side: String,
    val entryAt: String,
    val exitAt: String,
    val entryPrice: Double,
    val exitPrice: Double,
    val pnl: Double,
    val returnR: Double,
    val exitReason: String,
)

private fun BacktestResult.toResponse(): BacktestRunResponse =
    BacktestRunResponse(
        symbol = symbol.value,
        timeframe = timeframe.name,
        candleCount = candleCount,
        startAt = startAt?.toString(),
        endAt = endAt?.toString(),
        initialEquity = initialEquity.roundForApi(),
        finalEquity = finalEquity.roundForApi(),
        netPnl = netPnl.roundForApi(),
        netReturnPct = netReturnPct.roundForApi(),
        expectedMonthlyReturnPct = expectedMonthlyReturnPct?.roundForApi(),
        maxDrawdownPct = maxDrawdownPct.roundForApi(),
        tradeCount = trades.size,
        wins = wins,
        losses = losses,
        winRatePct = winRatePct.roundForApi(),
        profitFactor = profitFactor?.roundForApi(),
        expectancyR = expectancyR.roundForApi(),
        trades =
            trades.takeLast(20).map { trade ->
                BacktestTradeResponse(
                    side = trade.side.name,
                    entryAt = trade.entryAt.toString(),
                    exitAt = trade.exitAt.toString(),
                    entryPrice = trade.entryPrice.roundForApi(),
                    exitPrice = trade.exitPrice.roundForApi(),
                    pnl = trade.pnl.roundForApi(),
                    returnR = trade.returnR.roundForApi(),
                    exitReason = trade.exitReason.name,
                )
            },
    )

private fun Double.roundForApi(): Double = round(this * 100_000.0) / 100_000.0
