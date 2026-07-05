package dev.yaklede.bybittrader.api.paper

import dev.yaklede.bybittrader.domain.ResearchCandleLimits
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.paper.PaperEvaluationResult
import dev.yaklede.bybittrader.engine.paper.PaperTradingService
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

fun Route.configurePaperTradingRoutes(paperTradingService: PaperTradingService) {
    authenticate("control") {
        post("/paper/evaluate") {
            val request = call.receive<PaperEvaluationRequest>().validated()
            val result =
                paperTradingService.evaluateOnce(
                    symbol = Symbol(request.symbol),
                    timeframe = Timeframe.valueOf(request.timeframe),
                    candleLimit = request.candleLimit,
                )
            call.respond(result.toResponse())
        }
    }
}

@Serializable
data class PaperEvaluationRequest(
    val symbol: String,
    val timeframe: String,
    val candleLimit: Int = 18_000,
) {
    fun validated(): PaperEvaluationRequest {
        val normalizedSymbol = symbol.trim().uppercase()
        Symbol(normalizedSymbol)
        Timeframe.valueOf(timeframe.trim().uppercase())
        require(candleLimit in 20..ResearchCandleLimits.MAX_M5_REPLAY_CANDLES) {
            "Candle limit must be between 20 and ${ResearchCandleLimits.MAX_M5_REPLAY_CANDLES}."
        }
        return copy(
            symbol = normalizedSymbol,
            timeframe = timeframe.trim().uppercase(),
        )
    }
}

@Serializable
data class PaperEvaluationResponse(
    val symbol: String,
    val timeframe: String,
    val mode: String,
    val status: String,
    val evaluatedAt: String,
    val candleCount: Int,
    val reasonCodes: List<String>,
    val signalId: Long?,
    val orderId: Long?,
    val fillPrice: String?,
    val quantity: String?,
    val fee: String?,
)

private fun PaperEvaluationResult.toResponse(): PaperEvaluationResponse =
    PaperEvaluationResponse(
        symbol = symbol.value,
        timeframe = timeframe.name,
        mode = mode,
        status = status.name,
        evaluatedAt = evaluatedAt.toString(),
        candleCount = candleCount,
        reasonCodes = reasonCodes,
        signalId = signalId,
        orderId = orderId,
        fillPrice = fillPrice?.toPlainString(),
        quantity = quantity?.toPlainString(),
        fee = fee?.toPlainString(),
    )
