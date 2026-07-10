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
import dev.yaklede.bybittrader.engine.execution.ExecutionRuntimeMode
import dev.yaklede.bybittrader.engine.execution.ExecutionTradeClosure
import dev.yaklede.bybittrader.engine.execution.LivePerformanceSnapshot
import dev.yaklede.bybittrader.engine.execution.LivePerformanceWindow
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.Instant

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

        get("/execution/closed-trades") {
            val request =
                ClosedTradesQuery(
                    symbol = call.request.queryParameters["symbol"],
                    limit = call.request.queryParameters["limit"],
                    cursor = call.request.queryParameters["cursor"],
                    mode = call.request.queryParameters["mode"],
                ).validated()
            val items =
                executionService.closedTrades(
                    symbol = request.symbol?.let(::Symbol),
                    mode = request.mode?.let(ExecutionRuntimeMode::valueOf),
                    limit = request.limit,
                    cursor = request.cursor,
                )
            call.respond(
                ClosedTradesResponse(
                    items = items.map(ExecutionTradeClosure::toResponse),
                    nextCursor = items.lastOrNull()?.id?.toString(),
                ),
            )
        }

        get("/performance/live/summary") {
            val request =
                LivePerformanceQuery(
                    mode = call.request.queryParameters["mode"],
                    window = call.request.queryParameters["window"],
                ).validated()
            val summary =
                executionService.livePerformanceSummary(
                    mode = request.mode?.let(ExecutionRuntimeMode::valueOf),
                    window = request.window,
                )
            call.respond(summary.toResponse(request))
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

private data class ClosedTradesQuery(
    val symbol: String?,
    val limit: String?,
    val cursor: String?,
    val mode: String?,
) {
    fun validated(): ClosedTradesQueryValues {
        val normalizedSymbol =
            symbol
                ?.trim()
                ?.uppercase()
                ?.takeIf { it.isNotBlank() }
                ?.also { Symbol(it) }
        val normalizedLimit = limit?.toIntOrNull() ?: 20
        require(normalizedLimit in 1..100) { "Limit must be between 1 and 100." }
        val normalizedCursor = cursor?.toLongOrNull()
        require(cursor == null || normalizedCursor != null) { "Cursor must be a positive number." }
        require(normalizedCursor == null || normalizedCursor > 0) { "Cursor must be a positive number." }
        val normalizedMode =
            mode
                ?.trim()
                ?.uppercase()
                ?.takeIf { it.isNotBlank() }
                ?.also { ExecutionRuntimeMode.valueOf(it) }
        return ClosedTradesQueryValues(normalizedSymbol, normalizedLimit, normalizedCursor, normalizedMode)
    }
}

private data class ClosedTradesQueryValues(
    val symbol: String?,
    val limit: Int,
    val cursor: Long?,
    val mode: String?,
)

private data class LivePerformanceQuery(
    val mode: String?,
    val window: String?,
) {
    fun validated(): LivePerformanceQueryValues {
        val normalizedMode =
            mode
                ?.trim()
                ?.uppercase()
                ?.takeIf { it.isNotBlank() }
                ?.also { ExecutionRuntimeMode.valueOf(it) }
        val normalizedWindow =
            when (window?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: "all") {
                "session" -> LivePerformanceWindow.SESSION
                "7d" -> LivePerformanceWindow.SEVEN_DAYS
                "30d" -> LivePerformanceWindow.THIRTY_DAYS
                "all" -> LivePerformanceWindow.ALL
                else -> throw IllegalArgumentException("Window must be session, 7d, 30d, or all.")
            }
        return LivePerformanceQueryValues(normalizedMode, normalizedWindow)
    }
}

private data class LivePerformanceQueryValues(
    val mode: String?,
    val window: LivePerformanceWindow,
)

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
data class ClosedTradesResponse(
    val items: List<ClosedTradeResponse>,
    val nextCursor: String?,
)

@Serializable
data class ClosedTradeResponse(
    val tradeId: Long,
    val mode: String,
    val symbol: String,
    val side: String,
    val openedAt: String,
    val closedAt: String,
    val entryPrice: String,
    val exitPrice: String,
    val quantity: String,
    val grossPnl: String,
    val fees: String,
    val netPnl: String,
    val exitReason: String,
    val exchangeOrderId: String?,
    val clientOrderId: String?,
)

@Serializable
data class LivePerformanceSummaryResponse(
    val mode: String,
    val window: String,
    val tradeCount: Int,
    val winRatePct: String,
    val grossProfit: String,
    val grossLoss: String,
    val fees: String,
    val netPnl: String,
    val profitFactor: String?,
    val expectancy: String?,
    val maxClosedTradeDrawdownPct: String,
    val lastClosedAt: String?,
    val capturedAt: String,
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

private fun ExecutionTradeClosure.toResponse(): ClosedTradeResponse =
    ClosedTradeResponse(
        tradeId = id,
        mode = mode.name,
        symbol = symbol.value,
        side = side.name,
        openedAt = openedAt.toString(),
        closedAt = closedAt.toString(),
        entryPrice = entryPrice.toPlainString(),
        exitPrice = exitPrice.toPlainString(),
        quantity = quantity.toPlainString(),
        grossPnl = grossPnl.toPlainString(),
        fees = fees.toPlainString(),
        netPnl = netPnl.toPlainString(),
        exitReason = exitReason,
        exchangeOrderId = exchangeOrderId,
        clientOrderId = clientOrderId,
    )

private fun LivePerformanceSnapshot?.toResponse(request: LivePerformanceQueryValues): LivePerformanceSummaryResponse =
    if (this == null) {
        LivePerformanceSummaryResponse(
            mode = request.mode ?: "LIVE",
            window = request.window.toContractValue(),
            tradeCount = 0,
            winRatePct = "0",
            grossProfit = "0",
            grossLoss = "0",
            fees = "0",
            netPnl = "0",
            profitFactor = null,
            expectancy = null,
            maxClosedTradeDrawdownPct = "0",
            lastClosedAt = null,
            capturedAt = Instant.EPOCH.toString(),
        )
    } else {
        LivePerformanceSummaryResponse(
            mode = mode.name,
            window = window.toContractValue(),
            tradeCount = tradeCount,
            winRatePct = winRatePct.toPlainString(),
            grossProfit = grossProfit.toPlainString(),
            grossLoss = grossLoss.toPlainString(),
            fees = fees.toPlainString(),
            netPnl = netPnl.toPlainString(),
            profitFactor = profitFactor?.toPlainString(),
            expectancy = expectancy?.toPlainString(),
            maxClosedTradeDrawdownPct = maxClosedTradeDrawdownPct.toPlainString(),
            lastClosedAt = lastClosedAt?.toString(),
            capturedAt = capturedAt.toString(),
        )
    }

private fun LivePerformanceWindow.toContractValue(): String =
    when (this) {
        LivePerformanceWindow.SESSION -> "session"
        LivePerformanceWindow.SEVEN_DAYS -> "7d"
        LivePerformanceWindow.THIRTY_DAYS -> "30d"
        LivePerformanceWindow.ALL -> "all"
    }

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
