package dev.yaklede.bybittrader.exchange.bybit

import dev.yaklede.bybittrader.domain.OrderStatus
import dev.yaklede.bybittrader.domain.OrderType
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.execution.ExchangeCancelRequest
import dev.yaklede.bybittrader.engine.execution.ExchangeCancelResult
import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionException
import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionFill
import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionGateway
import dev.yaklede.bybittrader.engine.execution.ExchangeOpenOrder
import dev.yaklede.bybittrader.engine.execution.ExchangeOrderRequest
import dev.yaklede.bybittrader.engine.execution.ExchangeOrderResult
import dev.yaklede.bybittrader.engine.execution.ExchangePosition
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant

class BybitPrivateClient(
    private val httpClient: HttpClient,
    private val config: BybitPrivateClientConfig,
    clock: Clock = Clock.systemUTC(),
) : ExchangeExecutionGateway {
    private val signer =
        BybitRequestSigner(
            keyId = config.keyId,
            signingCredential = config.signingCredential,
            recvWindowMillis = config.recvWindowMillis,
            clock = clock,
        )

    init {
        require(config.baseUrl.isNotBlank()) { "Bybit private base URL must not be blank." }
    }

    override suspend fun placeOrder(request: ExchangeOrderRequest): ExchangeOrderResult {
        val body =
            privateJson.encodeToString(
                BybitPlaceOrderBody(
                    category = config.category.apiValue,
                    symbol = request.symbol.value,
                    side = request.side.toBybitSide(),
                    orderType = request.orderType.toBybitOrderType(),
                    qty = request.quantity.toPlainString(),
                    orderLinkId = request.clientOrderId,
                    reduceOnly = request.reduceOnly,
                    takeProfit = request.takeProfit?.toPlainString(),
                    stopLoss = request.stopLoss?.toPlainString(),
                    tpslMode = if (request.takeProfit != null || request.stopLoss != null) "Full" else null,
                    positionIdx = config.positionIdx,
                ),
            )
        val response =
            signedPost<BybitPlaceOrderResponse>(
                path = "/v5/order/create",
                body = body,
            )
        response.requireSuccess("place order")
        val result = response.result ?: throw ExchangeExecutionException("Bybit place order response had no result.")
        return ExchangeOrderResult(
            exchangeOrderId = result.orderId,
            clientOrderId = result.orderLinkId ?: request.clientOrderId,
            status = OrderStatus.SUBMITTED,
        )
    }

    override suspend fun cancelOrder(request: ExchangeCancelRequest): ExchangeCancelResult {
        val body =
            privateJson.encodeToString(
                BybitCancelOrderBody(
                    category = config.category.apiValue,
                    symbol = request.symbol.value,
                    orderId = request.exchangeOrderId,
                    orderLinkId = request.clientOrderId,
                ),
            )
        val response =
            signedPost<BybitCancelOrderResponse>(
                path = "/v5/order/cancel",
                body = body,
            )
        response.requireSuccess("cancel order")
        val result = response.result ?: throw ExchangeExecutionException("Bybit cancel order response had no result.")
        return ExchangeCancelResult(
            exchangeOrderId = result.orderId,
            clientOrderId = result.orderLinkId,
        )
    }

    override suspend fun openOrders(symbol: Symbol): List<ExchangeOpenOrder> {
        val query =
            bybitQueryString(
                "category" to config.category.apiValue,
                "symbol" to symbol.value,
            )
        val response =
            signedGet<BybitOpenOrdersResponse>(
                path = "/v5/order/realtime",
                queryString = query,
            )
        response.requireSuccess("list open orders")
        return response.result
            ?.list
            .orEmpty()
            .mapNotNull { item -> item.toExchangeOpenOrder(symbol) }
    }

    override suspend fun positions(symbol: Symbol): List<ExchangePosition> {
        val query =
            bybitQueryString(
                "category" to config.category.apiValue,
                "symbol" to symbol.value,
            )
        val response =
            signedGet<BybitPositionsResponse>(
                path = "/v5/position/list",
                queryString = query,
            )
        response.requireSuccess("list positions")
        return response.result
            ?.list
            .orEmpty()
            .mapNotNull { item -> item.toExchangePosition(symbol) }
    }

    override suspend fun executions(symbol: Symbol): List<ExchangeExecutionFill> {
        val query =
            bybitQueryString(
                "category" to config.category.apiValue,
                "symbol" to symbol.value,
            )
        val response =
            signedGet<BybitExecutionsResponse>(
                path = "/v5/execution/list",
                queryString = query,
            )
        response.requireSuccess("list executions")
        return response.result
            ?.list
            .orEmpty()
            .mapNotNull { item -> item.toExchangeExecution(symbol) }
    }

    private suspend inline fun <reified T> signedGet(
        path: String,
        queryString: String,
    ): T {
        val headers = signer.signGet(queryString)
        return httpClient
            .get("${config.baseUrl.trimEnd('/')}$path?$queryString") {
                apply(headers)
            }.body()
    }

    private suspend inline fun <reified T> signedPost(
        path: String,
        body: String,
    ): T {
        val headers = signer.signPost(body)
        return httpClient
            .post("${config.baseUrl.trimEnd('/')}$path") {
                contentType(ContentType.Application.Json)
                apply(headers)
                setBody(body)
            }.body()
    }
}

data class BybitPrivateClientConfig(
    val keyId: String,
    val signingCredential: String,
    val baseUrl: String,
    val recvWindowMillis: Long = 5_000,
    val category: BybitTradingCategory = BybitTradingCategory.LINEAR,
    val positionIdx: Int = 0,
) {
    init {
        require(keyId.isNotBlank()) { "Bybit API key must not be blank." }
        require(signingCredential.isNotBlank()) { "Bybit API secret must not be blank." }
        require(baseUrl.isNotBlank()) { "Bybit private base URL must not be blank." }
        require(recvWindowMillis in 1_000..60_000) { "Bybit recv window must be between 1000 and 60000 ms." }
        require(positionIdx in 0..2) { "Bybit position index must be 0, 1, or 2." }
    }
}

enum class BybitTradingCategory(
    val apiValue: String,
) {
    LINEAR("linear"),
}

private val privateJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

private fun BybitAuthHeaders.applyTo(builder: io.ktor.client.request.HttpRequestBuilder) {
    builder.header("X-BAPI-API-KEY", keyId)
    builder.header("X-BAPI-TIMESTAMP", timestampMillis)
    builder.header("X-BAPI-SIGN", signature)
    builder.header("X-BAPI-RECV-WINDOW", recvWindowMillis)
}

private fun io.ktor.client.request.HttpRequestBuilder.apply(headers: BybitAuthHeaders) {
    headers.applyTo(this)
}

private fun bybitQueryString(vararg params: Pair<String, String?>): String =
    params
        .filter { (_, value) -> !value.isNullOrBlank() }
        .joinToString("&") { (key, value) -> "$key=$value" }

private fun BybitOrderResponse.requireSuccess(action: String) {
    if (retCode != 0) {
        throw ExchangeExecutionException("Bybit $action failed with code $retCode.")
    }
}

private fun Side.toBybitSide(): String =
    when (this) {
        Side.BUY -> "Buy"
        Side.SELL -> "Sell"
    }

private fun OrderType.toBybitOrderType(): String =
    when (this) {
        OrderType.MARKET -> "Market"
        OrderType.LIMIT -> "Limit"
    }

private fun String?.toSide(): Side? =
    when (this) {
        "Buy" -> Side.BUY
        "Sell" -> Side.SELL
        else -> null
    }

private fun String?.toOrderType(): OrderType? =
    when (this) {
        "Market" -> OrderType.MARKET
        "Limit" -> OrderType.LIMIT
        else -> null
    }

private fun String?.toOrderStatus(): OrderStatus =
    when (this) {
        "New",
        "Created",
        "Untriggered",
        -> OrderStatus.SUBMITTED
        "PartiallyFilled" -> OrderStatus.PARTIALLY_FILLED
        "Filled" -> OrderStatus.FILLED
        "Cancelled",
        "Deactivated",
        -> OrderStatus.CANCELLED
        "Rejected" -> OrderStatus.REJECTED
        else -> OrderStatus.SUBMITTED
    }

private fun String?.toBigDecimalOrNull(): BigDecimal? = this?.takeIf { it.isNotBlank() }?.let(::BigDecimal)

private fun String?.toInstantFromMillisOrNull(): Instant? =
    this
        ?.takeIf { it.isNotBlank() }
        ?.toLongOrNull()
        ?.let(Instant::ofEpochMilli)

private fun BybitOpenOrderItem.toExchangeOpenOrder(fallbackSymbol: Symbol): ExchangeOpenOrder? {
    val side = side.toSide() ?: return null
    val orderType = orderType.toOrderType() ?: return null
    return ExchangeOpenOrder(
        exchangeOrderId = orderId,
        clientOrderId = orderLinkId,
        symbol = symbol?.let(::Symbol) ?: fallbackSymbol,
        side = side,
        orderType = orderType,
        status = orderStatus.toOrderStatus(),
        quantity = qty.toBigDecimalOrNull(),
        createdAt = createdTime.toInstantFromMillisOrNull(),
    )
}

private fun BybitPositionItem.toExchangePosition(fallbackSymbol: Symbol): ExchangePosition? {
    val side = side.toSide() ?: return null
    val size = size.toBigDecimalOrNull() ?: BigDecimal.ZERO
    return ExchangePosition(
        symbol = symbol?.let(::Symbol) ?: fallbackSymbol,
        side = side,
        size = size,
        entryPrice = avgPrice.toBigDecimalOrNull(),
        markPrice = markPrice.toBigDecimalOrNull(),
        unrealizedPnl = unrealisedPnl.toBigDecimalOrNull(),
        updatedAt = updatedTime.toInstantFromMillisOrNull(),
    )
}

private fun BybitExecutionItem.toExchangeExecution(fallbackSymbol: Symbol): ExchangeExecutionFill? {
    val side = side.toSide() ?: return null
    val price = execPrice.toBigDecimalOrNull() ?: return null
    val quantity = execQty.toBigDecimalOrNull() ?: return null
    val executedAt = execTime.toInstantFromMillisOrNull() ?: return null
    return ExchangeExecutionFill(
        exchangeOrderId = orderId,
        clientOrderId = orderLinkId,
        symbol = symbol?.let(::Symbol) ?: fallbackSymbol,
        side = side,
        price = price,
        quantity = quantity,
        fee = execFee.toBigDecimalOrNull() ?: BigDecimal.ZERO,
        executedAt = executedAt,
    )
}

private interface BybitOrderResponse {
    val retCode: Int
    val retMsg: String
}

@Serializable
private data class BybitPlaceOrderBody(
    val category: String,
    val symbol: String,
    val side: String,
    val orderType: String,
    val qty: String,
    val orderLinkId: String,
    val reduceOnly: Boolean,
    val takeProfit: String? = null,
    val stopLoss: String? = null,
    val tpslMode: String? = null,
    val positionIdx: Int? = null,
)

@Serializable
private data class BybitCancelOrderBody(
    val category: String,
    val symbol: String,
    val orderId: String? = null,
    val orderLinkId: String? = null,
)

@Serializable
private data class BybitPlaceOrderResponse(
    override val retCode: Int,
    override val retMsg: String,
    val result: BybitOrderIdResult? = null,
) : BybitOrderResponse

@Serializable
private data class BybitCancelOrderResponse(
    override val retCode: Int,
    override val retMsg: String,
    val result: BybitOrderIdResult? = null,
) : BybitOrderResponse

@Serializable
private data class BybitOrderIdResult(
    val orderId: String? = null,
    val orderLinkId: String? = null,
)

@Serializable
private data class BybitOpenOrdersResponse(
    override val retCode: Int,
    override val retMsg: String,
    val result: BybitOpenOrdersResult? = null,
) : BybitOrderResponse

@Serializable
private data class BybitOpenOrdersResult(
    val list: List<BybitOpenOrderItem> = emptyList(),
)

@Serializable
private data class BybitOpenOrderItem(
    val orderId: String? = null,
    val orderLinkId: String? = null,
    val symbol: String? = null,
    val side: String? = null,
    val orderType: String? = null,
    val orderStatus: String? = null,
    val qty: String? = null,
    val createdTime: String? = null,
)

@Serializable
private data class BybitPositionsResponse(
    override val retCode: Int,
    override val retMsg: String,
    val result: BybitPositionsResult? = null,
) : BybitOrderResponse

@Serializable
private data class BybitPositionsResult(
    val list: List<BybitPositionItem> = emptyList(),
)

@Serializable
private data class BybitPositionItem(
    val symbol: String? = null,
    val side: String? = null,
    val size: String? = null,
    val avgPrice: String? = null,
    val markPrice: String? = null,
    @SerialName("unrealisedPnl")
    val unrealisedPnl: String? = null,
    val updatedTime: String? = null,
)

@Serializable
private data class BybitExecutionsResponse(
    override val retCode: Int,
    override val retMsg: String,
    val result: BybitExecutionsResult? = null,
) : BybitOrderResponse

@Serializable
private data class BybitExecutionsResult(
    val list: List<BybitExecutionItem> = emptyList(),
)

@Serializable
private data class BybitExecutionItem(
    val orderId: String? = null,
    val orderLinkId: String? = null,
    val symbol: String? = null,
    val side: String? = null,
    val execPrice: String? = null,
    val execQty: String? = null,
    val execFee: String? = null,
    val execTime: String? = null,
)
