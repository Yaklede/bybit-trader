package dev.yaklede.bybittrader.api.backtest

import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.backtest.BacktestConfig
import dev.yaklede.bybittrader.engine.backtest.BacktestSummary
import dev.yaklede.bybittrader.engine.backtest.MeanReversionCandidate
import dev.yaklede.bybittrader.engine.backtest.MeanReversionSweepConfig
import dev.yaklede.bybittrader.engine.backtest.MeanReversionSweepReport
import dev.yaklede.bybittrader.engine.backtest.MeanReversionSweepService
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

fun Route.configureMeanReversionSweepRoutes(sweepService: MeanReversionSweepService) {
    authenticate("control") {
        post("/backtests/mean-reversion/sweep") {
            val request = call.receive<MeanReversionSweepRequest>().validated()
            val report =
                sweepService.run(
                    symbol = Symbol(request.symbol),
                    timeframe = Timeframe.valueOf(request.timeframe),
                    candleLimit = request.candleLimit,
                    backtestConfig = request.toBacktestConfig(),
                    sweepConfig = request.toSweepConfig(),
                )
            call.respond(report.toResponse(request.topResults))
        }
    }
}

@Serializable
data class MeanReversionSweepRequest(
    val symbol: String,
    val timeframe: String,
    val candleLimit: Int = 500,
    val initialEquity: Double = 10_000.0,
    val riskFraction: Double = 0.005,
    val feeRate: Double = 0.0006,
    val slippageRate: Double = 0.0002,
    val fundingRatePer8h: Double = 0.0,
    val partialTakeProfitR: Double = 1.0,
    val partialTakeProfitFraction: Double = 0.5,
    val breakevenAfterPartialTakeProfit: Boolean = true,
    val atrTrailingPeriod: Int = 14,
    val atrTrailingMultiplier: Double = 0.0,
    val maxHoldCandles: Int = 16,
    val trainRatio: Double = 0.6,
    val oversoldRsiValues: List<Double> = listOf(25.0, 30.0, 35.0),
    val overboughtRsiValues: List<Double> = listOf(65.0, 70.0, 75.0),
    val bollingerStdDevValues: List<Double> = listOf(1.8, 2.0, 2.2),
    val atrStopMultiplierValues: List<Double> = listOf(1.0, 1.2, 1.5),
    val topResults: Int = 10,
) {
    fun validated(): MeanReversionSweepRequest {
        Symbol(symbol)
        Timeframe.valueOf(timeframe)
        require(candleLimit in 60..1000) { "Candle limit must be between 60 and 1000." }
        require(topResults in 1..50) { "Top results must be between 1 and 50." }
        toBacktestConfig()
        toSweepConfig()
        return this
    }

    fun toBacktestConfig(): BacktestConfig =
        BacktestConfig(
            initialEquity = initialEquity,
            riskFraction = riskFraction,
            feeRate = feeRate,
            slippageRate = slippageRate,
            fundingRatePer8h = fundingRatePer8h,
            partialTakeProfitR = partialTakeProfitR,
            partialTakeProfitFraction = partialTakeProfitFraction,
            breakevenAfterPartialTakeProfit = breakevenAfterPartialTakeProfit,
            atrTrailingPeriod = atrTrailingPeriod,
            atrTrailingMultiplier = atrTrailingMultiplier,
            maxHoldCandles = maxHoldCandles,
        )

    fun toSweepConfig(): MeanReversionSweepConfig =
        MeanReversionSweepConfig(
            oversoldRsiValues = oversoldRsiValues,
            overboughtRsiValues = overboughtRsiValues,
            bollingerStdDevValues = bollingerStdDevValues,
            atrStopMultiplierValues = atrStopMultiplierValues,
            trainRatio = trainRatio,
        )
}

@Serializable
data class MeanReversionSweepResponse(
    val symbol: String,
    val timeframe: String,
    val candleCount: Int,
    val trainCandleCount: Int,
    val testCandleCount: Int,
    val resultCount: Int,
    val results: List<MeanReversionSweepResultResponse>,
)

@Serializable
data class MeanReversionSweepResultResponse(
    val candidate: MeanReversionCandidateResponse,
    val train: BacktestSummaryResponse,
    val test: BacktestSummaryResponse,
)

@Serializable
data class MeanReversionCandidateResponse(
    val oversoldRsi: Double,
    val overboughtRsi: Double,
    val bollingerStdDev: Double,
    val atrStopMultiplier: Double,
)

@Serializable
data class BacktestSummaryResponse(
    val tradeCount: Int,
    val netPnl: Double,
    val netReturnPct: Double,
    val expectedMonthlyReturnPct: Double?,
    val maxDrawdownPct: Double,
    val profitFactor: Double?,
    val expectancyR: Double,
    val maxConsecutiveLosses: Int,
    val noTradeReasonCounts: Map<String, Int>,
)

private fun MeanReversionSweepReport.toResponse(topResults: Int): MeanReversionSweepResponse =
    MeanReversionSweepResponse(
        symbol = symbol.value,
        timeframe = timeframe.name,
        candleCount = candleCount,
        trainCandleCount = trainCandleCount,
        testCandleCount = testCandleCount,
        resultCount = results.size,
        results =
            results.take(topResults).map { result ->
                MeanReversionSweepResultResponse(
                    candidate = result.candidate.toResponse(),
                    train = result.train.toResponse(),
                    test = result.test.toResponse(),
                )
            },
    )

private fun MeanReversionCandidate.toResponse(): MeanReversionCandidateResponse =
    MeanReversionCandidateResponse(
        oversoldRsi = oversoldRsi,
        overboughtRsi = overboughtRsi,
        bollingerStdDev = bollingerStdDev,
        atrStopMultiplier = atrStopMultiplier,
    )

private fun BacktestSummary.toResponse(): BacktestSummaryResponse =
    BacktestSummaryResponse(
        tradeCount = tradeCount,
        netPnl = netPnl.roundForApi(),
        netReturnPct = netReturnPct.roundForApi(),
        expectedMonthlyReturnPct = expectedMonthlyReturnPct?.roundForApi(),
        maxDrawdownPct = maxDrawdownPct.roundForApi(),
        profitFactor = profitFactor?.roundForApi(),
        expectancyR = expectancyR.roundForApi(),
        maxConsecutiveLosses = maxConsecutiveLosses,
        noTradeReasonCounts = noTradeReasonCounts,
    )
