package dev.yaklede.bybittrader.api.execution

import dev.yaklede.bybittrader.domain.ResearchCandleLimits
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.execution.ExchangeCancelRequest
import dev.yaklede.bybittrader.engine.execution.ExchangeCancelResult
import dev.yaklede.bybittrader.engine.execution.ExchangeEvaluationResult
import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionFill
import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionService
import dev.yaklede.bybittrader.engine.execution.ExchangeManualOrderResult
import dev.yaklede.bybittrader.engine.execution.ExchangeOpenOrder
import dev.yaklede.bybittrader.engine.execution.ExchangePosition
import dev.yaklede.bybittrader.engine.execution.ExchangeReconciliationReport
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.math.BigDecimal

fun Route.configureExecutionRoutes(
    executionService: ExchangeExecutionService,
    runtimeMode: String?,
) {
    authenticate("control") {
        post("/execution/evaluate-and-submit") {
            val request = call.receive<ExecutionEvaluationRequest>().validated()
            val result =
                executionService.evaluateAndSubmit(
                    symbol = Symbol(request.symbol),
                    timeframe = Timeframe.valueOf(request.timeframe),
                    candleLimit = request.candleLimit,
                )
            call.respond(result.toResponse())
        }

        post("/execution/manual/market-order") {
            val request = call.receive<ExecutionManualMarketOrderRequest>().validated(runtimeMode)
            val result =
                executionService.submitManualMarketOrder(
                    symbol = Symbol(request.symbol),
                    side = Side.valueOf(request.side),
                    quantity = request.quantity,
                )
            call.respond(result.toResponse())
        }

        post("/execution/manual/close-position") {
            val request = call.receive<ExecutionManualClosePositionRequest>().validated(runtimeMode)
            val result =
                executionService.submitReduceOnlyCloseOrder(
                    symbol = Symbol(request.symbol),
                    positionSide = Side.valueOf(request.positionSide),
                    quantity = request.quantity,
                )
            call.respond(result.toResponse())
        }

        post("/execution/reconcile") {
            val request = call.receive<ExecutionReconcileRequest>().validated()
            call.respond(executionService.reconcile(Symbol(request.symbol)).toResponse())
        }

        post("/execution/orders/cancel") {
            val request = call.receive<ExecutionCancelRequest>().validated()
            val result =
                executionService.cancelOrder(
                    ExchangeCancelRequest(
                        symbol = Symbol(request.symbol),
                        exchangeOrderId = request.exchangeOrderId,
                        clientOrderId = request.clientOrderId,
                    ),
                )
            call.respond(result.toResponse())
        }
    }
}

@Serializable
data class ExecutionEvaluationRequest(
    val symbol: String,
    val timeframe: String = "M5",
    val candleLimit: Int = 18_000,
) {
    fun validated(): ExecutionEvaluationRequest {
        val normalizedSymbol = symbol.trim().uppercase()
        val normalizedTimeframe = timeframe.trim().uppercase()
        Symbol(normalizedSymbol)
        Timeframe.valueOf(normalizedTimeframe)
        require(candleLimit in 20..ResearchCandleLimits.MAX_M5_REPLAY_CANDLES) {
            "Candle limit must be between 20 and ${ResearchCandleLimits.MAX_M5_REPLAY_CANDLES}."
        }
        return copy(symbol = normalizedSymbol, timeframe = normalizedTimeframe)
    }
}

@Serializable
data class ExecutionManualMarketOrderRequest(
    val symbol: String,
    val side: String,
    val quantity: String,
    val acknowledgement: String,
) {
    fun validated(runtimeMode: String?): ExecutionManualMarketOrderRequestValues {
        requireManualAcknowledgement(runtimeMode, acknowledgement, action = "MARKET_ORDER")
        val normalizedSide = side.trim().uppercase()
        Side.valueOf(normalizedSide)
        return ExecutionManualMarketOrderRequestValues(
            symbol = normalizeSymbol(symbol),
            side = normalizedSide,
            quantity = parsePositiveQuantity(quantity),
        )
    }
}

data class ExecutionManualMarketOrderRequestValues(
    val symbol: String,
    val side: String,
    val quantity: BigDecimal,
)

@Serializable
data class ExecutionManualClosePositionRequest(
    val symbol: String,
    val positionSide: String,
    val quantity: String,
    val acknowledgement: String,
) {
    fun validated(runtimeMode: String?): ExecutionManualClosePositionRequestValues {
        requireManualAcknowledgement(runtimeMode, acknowledgement, action = "CLOSE_POSITION")
        val normalizedPositionSide = positionSide.trim().uppercase()
        Side.valueOf(normalizedPositionSide)
        return ExecutionManualClosePositionRequestValues(
            symbol = normalizeSymbol(symbol),
            positionSide = normalizedPositionSide,
            quantity = parsePositiveQuantity(quantity),
        )
    }
}

data class ExecutionManualClosePositionRequestValues(
    val symbol: String,
    val positionSide: String,
    val quantity: BigDecimal,
)

@Serializable
data class ExecutionReconcileRequest(
    val symbol: String,
) {
    fun validated(): ExecutionReconcileRequest {
        val normalizedSymbol = symbol.trim().uppercase()
        Symbol(normalizedSymbol)
        return copy(symbol = normalizedSymbol)
    }
}

@Serializable
data class ExecutionCancelRequest(
    val symbol: String,
    val exchangeOrderId: String? = null,
    val clientOrderId: String? = null,
) {
    fun validated(): ExecutionCancelRequest {
        val normalizedSymbol = symbol.trim().uppercase()
        Symbol(normalizedSymbol)
        val normalizedExchangeOrderId = exchangeOrderId?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedClientOrderId = clientOrderId?.trim()?.takeIf { it.isNotEmpty() }
        require(normalizedExchangeOrderId != null || normalizedClientOrderId != null) {
            "Cancel request needs exchangeOrderId or clientOrderId."
        }
        return copy(
            symbol = normalizedSymbol,
            exchangeOrderId = normalizedExchangeOrderId,
            clientOrderId = normalizedClientOrderId,
        )
    }
}

@Serializable
data class ExecutionEvaluationResponse(
    val symbol: String,
    val timeframe: String,
    val mode: String,
    val status: String,
    val evaluatedAt: String,
    val candleCount: Int,
    val reasonCodes: List<String>,
    val signalId: Long?,
    val orderId: Long?,
    val exchangeOrderId: String?,
    val clientOrderId: String?,
    val entryPrice: String?,
    val takeProfit: String?,
    val stopLoss: String?,
    val quantity: String?,
    val intendedRisk: String?,
)

@Serializable
data class ExecutionReconciliationResponse(
    val symbol: String,
    val reconciledAt: String,
    val openOrders: List<ExecutionOpenOrderResponse>,
    val positions: List<ExecutionPositionResponse>,
    val executions: List<ExecutionFillResponse>,
)

@Serializable
data class ExecutionOpenOrderResponse(
    val exchangeOrderId: String?,
    val clientOrderId: String?,
    val symbol: String,
    val side: String,
    val orderType: String,
    val status: String,
    val quantity: String?,
    val createdAt: String?,
)

@Serializable
data class ExecutionPositionResponse(
    val symbol: String,
    val side: String,
    val size: String,
    val entryPrice: String?,
    val markPrice: String?,
    val unrealizedPnl: String?,
    val updatedAt: String?,
)

@Serializable
data class ExecutionFillResponse(
    val exchangeOrderId: String?,
    val clientOrderId: String?,
    val symbol: String,
    val side: String,
    val price: String,
    val quantity: String,
    val fee: String,
    val executedAt: String,
)

@Serializable
data class ExecutionCancelResponse(
    val exchangeOrderId: String?,
    val clientOrderId: String?,
)

@Serializable
data class ExecutionManualOrderResponse(
    val order: ExecutionManualOrderDetailResponse,
)

@Serializable
data class ExecutionManualOrderDetailResponse(
    val symbol: String,
    val side: String,
    val quantity: String,
    val reduceOnly: Boolean,
    val exchangeOrderId: String?,
    val clientOrderId: String,
    val orderId: Long,
    val status: String,
    val submittedAt: String,
)

private fun ExchangeEvaluationResult.toResponse(): ExecutionEvaluationResponse =
    ExecutionEvaluationResponse(
        symbol = symbol.value,
        timeframe = timeframe.name,
        mode = mode,
        status = status.name,
        evaluatedAt = evaluatedAt.toString(),
        candleCount = candleCount,
        reasonCodes = reasonCodes,
        signalId = signalId,
        orderId = orderId,
        exchangeOrderId = exchangeOrderId,
        clientOrderId = clientOrderId,
        entryPrice = entryPrice?.toPlainString(),
        takeProfit = takeProfit?.toPlainString(),
        stopLoss = stopLoss?.toPlainString(),
        quantity = quantity?.toPlainString(),
        intendedRisk = intendedRisk?.toPlainString(),
    )

private fun ExchangeReconciliationReport.toResponse(): ExecutionReconciliationResponse =
    ExecutionReconciliationResponse(
        symbol = symbol.value,
        reconciledAt = reconciledAt.toString(),
        openOrders = openOrders.map(ExchangeOpenOrder::toResponse),
        positions = positions.map(ExchangePosition::toResponse),
        executions = executions.map(ExchangeExecutionFill::toResponse),
    )

private fun ExchangeOpenOrder.toResponse(): ExecutionOpenOrderResponse =
    ExecutionOpenOrderResponse(
        exchangeOrderId = exchangeOrderId,
        clientOrderId = clientOrderId,
        symbol = symbol.value,
        side = side.name,
        orderType = orderType.name,
        status = status.name,
        quantity = quantity?.toPlainString(),
        createdAt = createdAt?.toString(),
    )

private fun ExchangePosition.toResponse(): ExecutionPositionResponse =
    ExecutionPositionResponse(
        symbol = symbol.value,
        side = side.name,
        size = size.toPlainString(),
        entryPrice = entryPrice?.toPlainString(),
        markPrice = markPrice?.toPlainString(),
        unrealizedPnl = unrealizedPnl?.toPlainString(),
        updatedAt = updatedAt?.toString(),
    )

private fun ExchangeExecutionFill.toResponse(): ExecutionFillResponse =
    ExecutionFillResponse(
        exchangeOrderId = exchangeOrderId,
        clientOrderId = clientOrderId,
        symbol = symbol.value,
        side = side.name,
        price = price.toPlainString(),
        quantity = quantity.toPlainString(),
        fee = fee.toPlainString(),
        executedAt = executedAt.toString(),
    )

private fun ExchangeCancelResult.toResponse(): ExecutionCancelResponse =
    ExecutionCancelResponse(
        exchangeOrderId = exchangeOrderId,
        clientOrderId = clientOrderId,
    )

private fun ExchangeManualOrderResult.toResponse(): ExecutionManualOrderResponse =
    ExecutionManualOrderResponse(
        order =
            ExecutionManualOrderDetailResponse(
                symbol = symbol.value,
                side = side.name,
                quantity = quantity.toPlainString(),
                reduceOnly = reduceOnly,
                exchangeOrderId = exchangeOrderId,
                clientOrderId = clientOrderId,
                orderId = orderId,
                status = status,
                submittedAt = submittedAt.toString(),
            ),
    )

private fun normalizeSymbol(symbol: String): String {
    val normalizedSymbol = symbol.trim().uppercase()
    Symbol(normalizedSymbol)
    return normalizedSymbol
}

private fun parsePositiveQuantity(quantity: String): BigDecimal {
    val parsedQuantity = quantity.toBigDecimalOrNull() ?: throw IllegalArgumentException("수량은 숫자로 입력해 주세요.")
    require(parsedQuantity > BigDecimal.ZERO) { "수량은 0보다 커야 해요." }
    return parsedQuantity.stripTrailingZeros()
}

private fun requireManualAcknowledgement(
    runtimeMode: String?,
    acknowledgement: String,
    action: String,
) {
    val normalizedMode = runtimeMode?.trim()?.uppercase()
    require(normalizedMode == "TESTNET" || normalizedMode == "LIVE") {
        "수동 거래 테스트는 테스트넷 또는 실거래 모드에서만 사용할 수 있어요."
    }
    val expectedAcknowledgement = "${normalizedMode}_$action"
    require(acknowledgement == expectedAcknowledgement) {
        "확인 문구는 $expectedAcknowledgement 이어야 해요."
    }
}
