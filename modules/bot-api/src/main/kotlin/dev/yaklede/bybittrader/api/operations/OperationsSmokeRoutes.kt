package dev.yaklede.bybittrader.api.operations

import dev.yaklede.bybittrader.api.ErrorResponse
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.control.BotControlService
import dev.yaklede.bybittrader.engine.control.ControlResult
import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionService
import dev.yaklede.bybittrader.engine.execution.ExchangeReconciliationReport
import dev.yaklede.bybittrader.engine.execution.ExchangeSmokeOrderResult
import dev.yaklede.bybittrader.engine.market.MarketDataSyncService
import dev.yaklede.bybittrader.engine.market.MarketTicker
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.math.BigDecimal

fun Route.configureOperationsSmokeRoutes(
    controlService: BotControlService,
    marketDataSyncService: MarketDataSyncService,
    executionService: ExchangeExecutionService?,
    runtimeMode: String?,
    onControlResult: suspend (ControlResult) -> Unit = {},
    onSmokeAlert: (suspend (String) -> SmokeAlertDeliveryResponse)? = null,
) {
    val logger = LoggerFactory.getLogger("dev.yaklede.bybittrader.api.operations")
    authenticate("control") {
        route("/ops/smoke") {
            get("/capabilities") {
                call.respond(
                    SmokeCapabilitiesResponse(
                        runtimeMode = runtimeMode,
                        marketTicker = true,
                        exchangeRead = executionService != null,
                        discordAlert = onSmokeAlert != null,
                        controlCycle = true,
                        testnetMarketOrder = runtimeMode == TESTNET_MODE && executionService != null,
                    ),
                )
            }

            post("/discord") {
                val request = call.receive<SmokeDiscordRequest>().validated()
                if (onSmokeAlert == null) {
                    call.respond(
                        status = HttpStatusCode.ServiceUnavailable,
                        message = ErrorResponse(code = "SMOKE_ALERT_UNAVAILABLE", message = "Alert sink is not configured."),
                    )
                    return@post
                }
                logger.info("smoke discord requested")
                val startedAt = System.nanoTime()
                val delivery = onSmokeAlert(request.message ?: DEFAULT_SMOKE_ALERT_MESSAGE)
                val elapsed = elapsedMillis(startedAt)
                logger.info("smoke discord completed delivered={} sink={}", delivery.delivered, delivery.sinkName)
                call.respond(SmokeDiscordResponse(step = SmokeStepResponse.fromDelivery(delivery, elapsed), delivery = delivery))
            }

            post("/exchange-read") {
                val request = call.receive<SmokeExchangeReadRequest>().validated()
                val service =
                    executionService
                        ?: return@post call.respond(
                            status = HttpStatusCode.ServiceUnavailable,
                            message =
                                ErrorResponse(
                                    code = "SMOKE_EXCHANGE_UNAVAILABLE",
                                    message = "Private exchange execution is not configured.",
                                ),
                        )
                logger.info("smoke exchange-read requested symbol={} coin={}", request.symbol, request.coin)
                val startedAt = System.nanoTime()
                val ticker: MarketTicker = marketDataSyncService.ticker(Symbol(request.symbol))
                val balance = service.accountBalance(request.coin)
                val coinCount = balance.coins.size
                val report: ExchangeReconciliationReport = service.reconcile(Symbol(request.symbol))
                val elapsed = elapsedMillis(startedAt)
                logger.info(
                    "smoke exchange-read completed symbol={} openOrders={} positions={} executions={}",
                    request.symbol,
                    report.openOrders.size,
                    report.positions.size,
                    report.executions.size,
                )
                call.respond(
                    SmokeExchangeReadResponse(
                        step = SmokeStepResponse.passed("exchange-read", elapsed),
                        symbol = request.symbol,
                        lastPrice = ticker.lastPrice.toPlainString(),
                        coinCount = coinCount,
                        openOrderCount = report.openOrders.size,
                        positionCount = report.positions.size,
                        executionCount = report.executions.size,
                    ),
                )
            }

            post("/control-cycle") {
                val request = call.receive<SmokeControlCycleRequest>().validated()
                logger.warn("smoke control-cycle requested")
                val startedAt = System.nanoTime()
                val pauseResult = controlService.pauseAll(actor = SMOKE_ACTOR, reason = request.reason)
                notifyControlResult(pauseResult, onControlResult, logger)
                val resumeResult = controlService.resume(actor = SMOKE_ACTOR, reason = request.reason)
                notifyControlResult(resumeResult, onControlResult, logger)
                val elapsed = elapsedMillis(startedAt)
                logger.warn(
                    "smoke control-cycle completed pauseMode={} resumeMode={}",
                    pauseResult.newMode.name,
                    resumeResult.newMode.name,
                )
                call.respond(
                    SmokeControlCycleResponse(
                        step = SmokeStepResponse.passed("control-cycle", elapsed),
                        pauseMode = pauseResult.newMode.name,
                        resumeMode = resumeResult.newMode.name,
                    ),
                )
            }

            post("/control-pause") {
                val request = call.receive<SmokeControlRequest>().validated("테스트넷 정지 기능을 확인했어요.")
                logger.warn("smoke control-pause requested")
                val startedAt = System.nanoTime()
                val result = controlService.pauseAll(actor = SMOKE_ACTOR, reason = request.reason)
                notifyControlResult(result, onControlResult, logger)
                val elapsed = elapsedMillis(startedAt)
                logger.warn("smoke control-pause completed mode={}", result.newMode.name)
                call.respond(
                    SmokeControlActionResponse(
                        step = SmokeStepResponse.passed("control-pause", elapsed),
                        action = result.action.name,
                        previousMode = result.previousMode.name,
                        newMode = result.newMode.name,
                        changedAt = result.changedAt.toString(),
                    ),
                )
            }

            post("/control-resume") {
                val request = call.receive<SmokeControlRequest>().validated("테스트넷 재가동 기능을 확인했어요.")
                logger.warn("smoke control-resume requested")
                val startedAt = System.nanoTime()
                val result = controlService.resume(actor = SMOKE_ACTOR, reason = request.reason)
                notifyControlResult(result, onControlResult, logger)
                val elapsed = elapsedMillis(startedAt)
                logger.warn("smoke control-resume completed mode={}", result.newMode.name)
                call.respond(
                    SmokeControlActionResponse(
                        step = SmokeStepResponse.passed("control-resume", elapsed),
                        action = result.action.name,
                        previousMode = result.previousMode.name,
                        newMode = result.newMode.name,
                        changedAt = result.changedAt.toString(),
                    ),
                )
            }

            post("/testnet-market-order") {
                val request = call.receive<SmokeMarketOrderRequest>().validated(runtimeMode)
                val service =
                    executionService
                        ?: return@post call.respond(
                            status = HttpStatusCode.ServiceUnavailable,
                            message =
                                ErrorResponse(
                                    code = "SMOKE_ORDER_UNAVAILABLE",
                                    message = "Private exchange execution is not configured.",
                                ),
                        )
                logger.warn(
                    "smoke testnet market order requested symbol={} side={} quantity={}",
                    request.symbol,
                    request.side,
                    request.quantity.toPlainString(),
                )
                val startedAt = System.nanoTime()
                val order: ExchangeSmokeOrderResult =
                    service.submitSmokeMarketOrder(
                        symbol = Symbol(request.symbol),
                        side = Side.valueOf(request.side),
                        quantity = request.quantity,
                    )
                val report = service.reconcile(Symbol(request.symbol))
                val elapsed = elapsedMillis(startedAt)
                logger.warn(
                    "smoke testnet market order completed exchangeOrderId={} positions={} executions={}",
                    order.exchangeOrderId,
                    report.positions.size,
                    report.executions.size,
                )
                call.respond(
                    SmokeMarketOrderResponse(
                        step = SmokeStepResponse.passed("testnet-market-order", elapsed),
                        order =
                            SmokeOrderResponse(
                                symbol = order.symbol.value,
                                side = order.side.name,
                                quantity = order.quantity.toPlainString(),
                                exchangeOrderId = order.exchangeOrderId,
                                clientOrderId = order.clientOrderId,
                                orderId = order.orderId,
                                status = order.status,
                                submittedAt = order.submittedAt.toString(),
                            ),
                        positionCount = report.positions.size,
                        executionCount = report.executions.size,
                    ),
                )
            }
        }
    }
}

private fun elapsedMillis(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

private suspend fun notifyControlResult(
    result: ControlResult,
    onControlResult: suspend (ControlResult) -> Unit,
    logger: org.slf4j.Logger,
) {
    try {
        onControlResult(result)
    } catch (cause: Throwable) {
        logger.warn("smoke control alert failed action={} error={}", result.action.name, cause::class.simpleName)
    }
}

@Serializable
data class SmokeCapabilitiesResponse(
    val runtimeMode: String?,
    val marketTicker: Boolean,
    val exchangeRead: Boolean,
    val discordAlert: Boolean,
    val controlCycle: Boolean,
    val testnetMarketOrder: Boolean,
)

@Serializable
data class SmokeStepResponse(
    val name: String,
    val status: String,
    val durationMillis: Long,
    val message: String,
) {
    companion object {
        fun passed(
            name: String,
            durationMillis: Long,
        ): SmokeStepResponse = SmokeStepResponse(name = name, status = "PASS", durationMillis = durationMillis, message = "정상적으로 완료됐어요.")

        fun fromDelivery(
            delivery: SmokeAlertDeliveryResponse,
            durationMillis: Long,
        ): SmokeStepResponse =
            SmokeStepResponse(
                name = "discord",
                status = if (delivery.delivered) "PASS" else "FAIL",
                durationMillis = durationMillis,
                message = delivery.failureReason ?: "Discord 웹훅 전송이 완료됐어요.",
            )
    }
}

@Serializable
data class SmokeAlertDeliveryResponse(
    val delivered: Boolean,
    val sinkName: String,
    val failureReason: String?,
)

@Serializable
data class SmokeDiscordRequest(
    val message: String? = null,
) {
    fun validated(): SmokeDiscordRequest {
        require(message == null || message.length <= 240) { "Message must be 240 characters or shorter." }
        return copy(message = message?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_SMOKE_ALERT_MESSAGE)
    }
}

@Serializable
data class SmokeDiscordResponse(
    val step: SmokeStepResponse,
    val delivery: SmokeAlertDeliveryResponse,
)

@Serializable
data class SmokeExchangeReadRequest(
    val symbol: String = "BTCUSDT",
    val coin: String = "USDT",
) {
    fun validated(): SmokeExchangeReadRequest =
        copy(
            symbol = normalizeSymbol(symbol),
            coin = normalizeCoin(coin),
        )
}

@Serializable
data class SmokeExchangeReadResponse(
    val step: SmokeStepResponse,
    val symbol: String,
    val lastPrice: String,
    val coinCount: Int,
    val openOrderCount: Int,
    val positionCount: Int,
    val executionCount: Int,
)

@Serializable
data class SmokeControlCycleRequest(
    val reason: String? = null,
) {
    fun validated(): SmokeControlCycleRequest {
        require(reason == null || reason.length <= 240) { "Reason must be 240 characters or shorter." }
        return copy(reason = reason?.trim()?.takeIf { it.isNotEmpty() } ?: "테스트넷 정지와 재가동 기능을 확인했어요.")
    }
}

@Serializable
data class SmokeControlCycleResponse(
    val step: SmokeStepResponse,
    val pauseMode: String,
    val resumeMode: String,
)

@Serializable
data class SmokeControlRequest(
    val reason: String? = null,
) {
    fun validated(defaultReason: String): SmokeControlRequest {
        require(reason == null || reason.length <= 240) { "Reason must be 240 characters or shorter." }
        return copy(reason = reason?.trim()?.takeIf { it.isNotEmpty() } ?: defaultReason)
    }
}

@Serializable
data class SmokeControlActionResponse(
    val step: SmokeStepResponse,
    val action: String,
    val previousMode: String,
    val newMode: String,
    val changedAt: String,
)

@Serializable
data class SmokeMarketOrderRequest(
    val symbol: String = "BTCUSDT",
    val side: String = "BUY",
    val quantity: String,
    val acknowledgement: String,
) {
    fun validated(runtimeMode: String?): SmokeMarketOrderRequestValues {
        require(runtimeMode == TESTNET_MODE) { "Smoke market order is allowed only when runtimeMode is TESTNET." }
        require(acknowledgement == TESTNET_MARKET_ORDER_ACK) {
            "Acknowledgement must be $TESTNET_MARKET_ORDER_ACK."
        }
        val normalizedSide = side.trim().uppercase()
        Side.valueOf(normalizedSide)
        val parsedQuantity = quantity.toBigDecimalOrNull() ?: throw IllegalArgumentException("Quantity must be a decimal number.")
        require(parsedQuantity > BigDecimal.ZERO) { "Quantity must be greater than 0." }
        return SmokeMarketOrderRequestValues(
            symbol = normalizeSymbol(symbol),
            side = normalizedSide,
            quantity = parsedQuantity.stripTrailingZeros(),
        )
    }
}

data class SmokeMarketOrderRequestValues(
    val symbol: String,
    val side: String,
    val quantity: BigDecimal,
)

@Serializable
data class SmokeMarketOrderResponse(
    val step: SmokeStepResponse,
    val order: SmokeOrderResponse,
    val positionCount: Int,
    val executionCount: Int,
)

@Serializable
data class SmokeOrderResponse(
    val symbol: String,
    val side: String,
    val quantity: String,
    val exchangeOrderId: String?,
    val clientOrderId: String,
    val orderId: Long,
    val status: String,
    val submittedAt: String,
)

private fun normalizeSymbol(symbol: String): String {
    val normalizedSymbol = symbol.trim().uppercase()
    Symbol(normalizedSymbol)
    return normalizedSymbol
}

private fun normalizeCoin(coin: String): String {
    val normalizedCoin = coin.trim().uppercase()
    require(COIN_PATTERN.matches(normalizedCoin)) { "Coin must contain 2 to 20 uppercase letters or numbers." }
    return normalizedCoin
}

private val COIN_PATTERN = Regex("^[A-Z0-9]{2,20}$")
private const val TESTNET_MODE = "TESTNET"
private const val TESTNET_MARKET_ORDER_ACK = "TESTNET_MARKET_ORDER"
private const val DEFAULT_SMOKE_ALERT_MESSAGE = "Bybit Trader 테스트넷 알림 테스트예요."
private const val SMOKE_ACTOR = "smoke-test"
