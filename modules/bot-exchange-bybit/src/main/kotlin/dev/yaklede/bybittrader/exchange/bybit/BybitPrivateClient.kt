package dev.yaklede.bybittrader.exchange.bybit

import dev.yaklede.bybittrader.domain.OrderStatus
import dev.yaklede.bybittrader.domain.OrderType
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.execution.ExchangeAccountBalance
import dev.yaklede.bybittrader.engine.execution.ExchangeAccountExecutionProfile
import dev.yaklede.bybittrader.engine.execution.ExchangeAccountMode
import dev.yaklede.bybittrader.engine.execution.ExchangeAccountTransaction
import dev.yaklede.bybittrader.engine.execution.ExchangeCancelRequest
import dev.yaklede.bybittrader.engine.execution.ExchangeCancelResult
import dev.yaklede.bybittrader.engine.execution.ExchangeClosedPnl
import dev.yaklede.bybittrader.engine.execution.ExchangeCoinBalance
import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionException
import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionFill
import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionGateway
import dev.yaklede.bybittrader.engine.execution.ExchangeInstrumentRules
import dev.yaklede.bybittrader.engine.execution.ExchangeMarginMode
import dev.yaklede.bybittrader.engine.execution.ExchangeOpenOrder
import dev.yaklede.bybittrader.engine.execution.ExchangeOrderRequest
import dev.yaklede.bybittrader.engine.execution.ExchangeOrderResult
import dev.yaklede.bybittrader.engine.execution.ExchangePosition
import dev.yaklede.bybittrader.engine.execution.ExchangePositionExecutionProfile
import dev.yaklede.bybittrader.engine.execution.ExchangePositionMode
import dev.yaklede.bybittrader.engine.execution.ExchangePositionProtectionRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant

class BybitPrivateClient(
    private val httpClient: HttpClient,
    private val config: BybitPrivateClientConfig,
    private val clock: Clock = Clock.systemUTC(),
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

    override suspend fun accountExecutionProfile(): ExchangeAccountExecutionProfile {
        val response =
            signedGet<BybitAccountInfoResponse>(
                path = "/v5/account/info",
                queryString = "",
            )
        response.requireSuccess("get account info")
        val result = response.result ?: throw ExchangeExecutionException("Bybit account info response had no result.")
        return ExchangeAccountExecutionProfile(
            accountType = config.accountType,
            accountMode = result.unifiedMarginStatus.toExchangeAccountMode(),
            unifiedMarginStatus = result.unifiedMarginStatus,
            marginMode = result.marginMode.toExchangeMarginMode(),
            updatedAt = result.updatedTime.toInstantFromMillisOrNull(),
        )
    }

    override suspend fun positionExecutionProfile(symbol: Symbol): ExchangePositionExecutionProfile {
        val response = positionResponse(symbol)
        val positions = response.result?.list.orEmpty()
        val indices = positions.mapNotNull(BybitPositionItem::positionIdx).toSet()
        val positionMode =
            when {
                indices == setOf(0) -> ExchangePositionMode.ONE_WAY
                indices.isNotEmpty() && indices.all { it == 1 || it == 2 } -> ExchangePositionMode.HEDGE
                else -> ExchangePositionMode.UNKNOWN
            }
        val oneWayLeverage = positions.firstOrNull { it.positionIdx == 0 }?.leverage.toBigDecimalOrNull()
        return ExchangePositionExecutionProfile(
            symbol = symbol,
            positionMode = positionMode,
            buyLeverage = positions.firstOrNull { it.positionIdx == 1 }?.leverage.toBigDecimalOrNull() ?: oneWayLeverage,
            sellLeverage = positions.firstOrNull { it.positionIdx == 2 }?.leverage.toBigDecimalOrNull() ?: oneWayLeverage,
            observedPositionIndices = indices,
            reduceOnlyRestricted = positions.any(BybitPositionItem::isReduceOnly),
        )
    }

    override suspend fun instrumentRules(symbol: Symbol): ExchangeInstrumentRules {
        val query =
            bybitQueryString(
                "category" to config.category.apiValue,
                "symbol" to symbol.value,
            )
        val response =
            publicGet<BybitInstrumentInfoResponse>(
                path = "/v5/market/instruments-info",
                queryString = query,
            )
        response.requireSuccess("get instrument info")
        val item =
            response.result
                ?.list
                .orEmpty()
                .singleOrNull { it.symbol == symbol.value }
                ?: throw ExchangeExecutionException("Bybit instrument info response had no exact symbol.")
        return item.toExchangeInstrumentRules(symbol)
    }

    override suspend fun setLeverage(
        symbol: Symbol,
        leverage: BigDecimal,
    ) {
        val leverageValue = leverage.stripTrailingZeros().toPlainString()
        val body =
            privateJson.encodeToString(
                BybitSetLeverageBody(
                    category = config.category.apiValue,
                    symbol = symbol.value,
                    buyLeverage = leverageValue,
                    sellLeverage = leverageValue,
                ),
            )
        val response =
            signedPost<BybitSetLeverageResponse>(
                path = "/v5/position/set-leverage",
                body = body,
            )
        response.requireSuccess("set leverage", toleratedCodes = setOf(110043))
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
                    price = request.price?.toPlainString(),
                    timeInForce = request.timeInForce.name,
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

    override suspend fun setPositionProtection(request: ExchangePositionProtectionRequest) {
        val body =
            privateJson.encodeToString(
                BybitSetTradingStopBody(
                    category = config.category.apiValue,
                    symbol = request.symbol.value,
                    takeProfit = request.takeProfit?.toPlainString() ?: "0",
                    stopLoss = request.stopLoss.toPlainString(),
                    tpslMode = "Full",
                    tpTriggerBy = request.takeProfit?.let { "LastPrice" },
                    slTriggerBy = "LastPrice",
                    positionIdx = config.positionIdx,
                ),
            )
        val response =
            signedPost<BybitSetTradingStopResponse>(
                path = "/v5/position/trading-stop",
                body = body,
            )
        response.requireSuccess("set position protection")
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
                "limit" to "50",
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

    override suspend fun order(
        symbol: Symbol,
        clientOrderId: String,
    ): ExchangeOpenOrder? {
        require(clientOrderId.isNotBlank()) { "Bybit client order ID must not be blank." }
        val query =
            bybitQueryString(
                "category" to config.category.apiValue,
                "orderLinkId" to clientOrderId,
                "limit" to "1",
            )
        val realtime =
            signedGet<BybitOpenOrdersResponse>(
                path = "/v5/order/realtime",
                queryString = query,
            )
        realtime.requireSuccess("get order by client ID")
        realtime.result
            ?.list
            .orEmpty()
            .firstOrNull { item -> item.orderLinkId == clientOrderId }
            ?.toExchangeOpenOrder(symbol)
            ?.let { return it }

        val history =
            signedGet<BybitOpenOrdersResponse>(
                path = "/v5/order/history",
                queryString = query,
            )
        history.requireSuccess("get order history by client ID")
        return history.result
            ?.list
            .orEmpty()
            .firstOrNull { item -> item.orderLinkId == clientOrderId }
            ?.toExchangeOpenOrder(symbol)
    }

    override suspend fun positions(symbol: Symbol): List<ExchangePosition> {
        val response = positionResponse(symbol)
        return response.result
            ?.list
            .orEmpty()
            .mapNotNull { item -> item.toExchangePosition(symbol) }
    }

    private suspend fun positionResponse(symbol: Symbol): BybitPositionsResponse {
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
        return response
    }

    override suspend fun executions(symbol: Symbol): List<ExchangeExecutionFill> {
        val executions = mutableListOf<ExchangeExecutionFill>()
        val observedCursors = mutableSetOf<String>()
        var cursor: String? = null
        var pageCount = 0
        do {
            requirePrivateHistoryPageBudget(++pageCount, "execution history")
            val query =
                bybitQueryString(
                    "category" to config.category.apiValue,
                    "symbol" to symbol.value,
                    "limit" to BYBIT_PRIVATE_HISTORY_PAGE_LIMIT.toString(),
                    "cursor" to cursor,
                )
            val response =
                signedGet<BybitExecutionsResponse>(
                    path = "/v5/execution/list",
                    queryString = query,
                )
            response.requireSuccess("list executions")
            val result = response.result ?: break
            executions += result.list.mapNotNull { item -> item.toExchangeExecution(symbol) }
            cursor = requireNextPrivateHistoryCursor(result.nextPageCursor, observedCursors, "execution history")
        } while (cursor != null)
        return executions
    }

    override suspend fun closedPnls(symbol: Symbol): List<ExchangeClosedPnl> {
        val closedPnls = mutableListOf<ExchangeClosedPnl>()
        val observedCursors = mutableSetOf<String>()
        var cursor: String? = null
        var pageCount = 0
        do {
            requirePrivateHistoryPageBudget(++pageCount, "closed PnL history")
            val query =
                bybitQueryString(
                    "category" to config.category.apiValue,
                    "symbol" to symbol.value,
                    "limit" to BYBIT_PRIVATE_HISTORY_PAGE_LIMIT.toString(),
                    "cursor" to cursor,
                )
            val response =
                signedGet<BybitClosedPnlResponse>(
                    path = "/v5/position/closed-pnl",
                    queryString = query,
                )
            response.requireSuccess("list closed pnl")
            val result = response.result ?: break
            closedPnls += result.list.mapNotNull { item -> item.toExchangeClosedPnl(symbol) }
            cursor = requireNextPrivateHistoryCursor(result.nextPageCursor, observedCursors, "closed PnL history")
        } while (cursor != null)
        return closedPnls
    }

    override suspend fun accountBalance(coin: String?): ExchangeAccountBalance {
        val query =
            bybitQueryString(
                "accountType" to config.accountType,
                "coin" to coin?.trim()?.uppercase()?.takeIf { it.isNotBlank() },
            )
        val response =
            signedGet<BybitWalletBalanceResponse>(
                path = "/v5/account/wallet-balance",
                queryString = query,
            )
        response.requireSuccess("get wallet balance")
        val account =
            response.result
                ?.list
                ?.firstOrNull()
                ?: throw ExchangeExecutionException("Bybit wallet balance response had no account.")
        return account.toExchangeAccountBalance(clock.instant())
    }

    override suspend fun accountTransactions(
        currency: String,
        startAt: Instant,
        endAt: Instant,
    ): List<ExchangeAccountTransaction> {
        require(!endAt.isBefore(startAt)) { "Transaction-log end time must not be before start time." }
        require(Duration.between(startAt, endAt) <= MAX_TRANSACTION_LOG_RANGE) {
            "Transaction-log range must not exceed seven days."
        }
        val normalizedCurrency = currency.trim().uppercase()
        require(normalizedCurrency.isNotBlank()) { "Transaction-log currency must not be blank." }

        val transactions = mutableListOf<ExchangeAccountTransaction>()
        val observedCursors = mutableSetOf<String>()
        var cursor: String? = null
        var pageCount = 0
        do {
            requirePrivateHistoryPageBudget(++pageCount, "transaction log")
            val query =
                bybitQueryString(
                    "accountType" to config.accountType,
                    "category" to config.category.apiValue,
                    "currency" to normalizedCurrency,
                    "startTime" to startAt.toEpochMilli().toString(),
                    "endTime" to endAt.toEpochMilli().toString(),
                    "limit" to "50",
                    "cursor" to cursor,
                )
            val response =
                signedGet<BybitTransactionLogResponse>(
                    path = "/v5/account/transaction-log",
                    queryString = query,
                )
            response.requireSuccess("get transaction log")
            val result = response.result ?: break
            transactions += result.list.mapNotNull(BybitTransactionLogItem::toExchangeAccountTransaction)
            cursor = requireNextPrivateHistoryCursor(result.nextPageCursor, observedCursors, "transaction log")
        } while (cursor != null)
        return transactions.distinctBy(ExchangeAccountTransaction::identityKey)
    }

    private suspend inline fun <reified T> signedGet(
        path: String,
        queryString: String,
    ): T {
        val headers = signer.signGet(queryString)
        return try {
            httpClient
                .get(config.requestUrl(path, queryString)) {
                    apply(headers)
                }.body()
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            throw ExchangeExecutionException("Bybit private request failed.", cause = cause)
        }
    }

    private suspend inline fun <reified T> publicGet(
        path: String,
        queryString: String,
    ): T =
        try {
            httpClient.get(config.requestUrl(path, queryString)).body()
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            throw ExchangeExecutionException("Bybit public instrument request failed.", cause = cause)
        }

    private suspend inline fun <reified T> signedPost(
        path: String,
        body: String,
    ): T {
        val headers = signer.signPost(body)
        return try {
            httpClient
                .post("${config.baseUrl.trimEnd('/')}$path") {
                    contentType(ContentType.Application.Json)
                    apply(headers)
                    setBody(body)
                }.body()
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: Throwable) {
            throw ExchangeExecutionException("Bybit private request failed.", cause = cause)
        }
    }
}

private fun requirePrivateHistoryPageBudget(
    pageCount: Int,
    source: String,
) {
    if (pageCount > MAX_PRIVATE_HISTORY_PAGES) {
        throw ExchangeExecutionException("Bybit $source pagination exceeded its page budget.")
    }
}

private fun requireNextPrivateHistoryCursor(
    nextPageCursor: String?,
    observedCursors: MutableSet<String>,
    source: String,
): String? {
    val cursor = nextPageCursor?.takeIf(String::isNotBlank) ?: return null
    if (!observedCursors.add(cursor)) {
        throw ExchangeExecutionException("Bybit $source pagination repeated a cursor.")
    }
    return cursor
}

private fun BybitPrivateClientConfig.requestUrl(
    path: String,
    queryString: String,
): String = "${baseUrl.trimEnd('/')}$path${queryString.takeIf(String::isNotBlank)?.let { "?$it" }.orEmpty()}"

data class BybitPrivateClientConfig(
    val keyId: String,
    val signingCredential: String,
    val baseUrl: String,
    val recvWindowMillis: Long = 5_000,
    val category: BybitTradingCategory = BybitTradingCategory.LINEAR,
    val positionIdx: Int = 0,
    val accountType: String = "UNIFIED",
) {
    init {
        require(keyId.isNotBlank()) { "Bybit API key must not be blank." }
        require(signingCredential.isNotBlank()) { "Bybit API secret must not be blank." }
        require(baseUrl.isNotBlank()) { "Bybit private base URL must not be blank." }
        require(recvWindowMillis in 1_000..60_000) { "Bybit recv window must be between 1000 and 60000 ms." }
        require(positionIdx in 0..2) { "Bybit position index must be 0, 1, or 2." }
        require(accountType in setOf("UNIFIED", "CONTRACT", "SPOT")) {
            "Bybit account type must be UNIFIED, CONTRACT, or SPOT."
        }
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

private fun BybitOrderResponse.requireSuccess(
    action: String,
    toleratedCodes: Set<Int> = emptySet(),
) {
    if (retCode != 0 && retCode !in toleratedCodes) {
        throw ExchangeExecutionException(
            message = "Bybit $action failed with code $retCode.",
            providerCode = retCode.toString(),
            providerMessage = retMsg.takeIf { it.isNotBlank() },
        )
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
        "PendingCancel",
        "Triggered",
        "Active",
        -> OrderStatus.SUBMITTED
        "PartiallyFilled" -> OrderStatus.PARTIALLY_FILLED
        "Filled" -> OrderStatus.FILLED
        "Cancelled",
        "Deactivated",
        "PartiallyFilledCanceled",
        "PartiallyFilledCancelled",
        -> OrderStatus.CANCELLED
        "Rejected" -> OrderStatus.REJECTED
        else -> OrderStatus.SUBMITTED
    }

private fun Int.toExchangeAccountMode(): ExchangeAccountMode =
    when (this) {
        1 -> ExchangeAccountMode.CLASSIC
        3, 4 -> ExchangeAccountMode.UNIFIED_1
        5, 6 -> ExchangeAccountMode.UNIFIED_2
        else -> ExchangeAccountMode.UNKNOWN
    }

private fun String.toExchangeMarginMode(): ExchangeMarginMode =
    when (this) {
        "REGULAR_MARGIN" -> ExchangeMarginMode.CROSS
        "ISOLATED_MARGIN" -> ExchangeMarginMode.ISOLATED
        "PORTFOLIO_MARGIN" -> ExchangeMarginMode.PORTFOLIO
        else -> ExchangeMarginMode.UNKNOWN
    }

private fun String?.toBigDecimalOrNull(): BigDecimal? = this?.takeIf { it.isNotBlank() }?.let(::BigDecimal)

private fun String?.toPositiveBigDecimalOrNull(): BigDecimal? = toBigDecimalOrNull()?.takeIf { value -> value > BigDecimal.ZERO }

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
        reduceOnly = reduceOnly,
        stopOrderType = stopOrderType,
        filledQuantity = cumExecQty.toBigDecimalOrNull(),
        updatedAt = updatedTime.toInstantFromMillisOrNull(),
        providerStatus = orderStatus,
        cancelType = cancelType,
        rejectReason = rejectReason,
    )
}

private fun BybitPositionItem.toExchangePosition(fallbackSymbol: Symbol): ExchangePosition? {
    val side = side.toSide() ?: return null
    val size = size.toBigDecimalOrNull() ?: BigDecimal.ZERO
    return ExchangePosition(
        symbol = symbol?.let(::Symbol) ?: fallbackSymbol,
        side = side,
        size = size,
        openedAt = openTime?.takeIf { it > 0L }?.let(Instant::ofEpochMilli),
        entryPrice = avgPrice.toBigDecimalOrNull(),
        markPrice = markPrice.toBigDecimalOrNull(),
        unrealizedPnl = unrealisedPnl.toBigDecimalOrNull(),
        updatedAt = updatedTime.toInstantFromMillisOrNull(),
        takeProfit = takeProfit.toPositiveBigDecimalOrNull(),
        stopLoss = stopLoss.toPositiveBigDecimalOrNull(),
    )
}

private fun BybitInstrumentInfoItem.toExchangeInstrumentRules(fallbackSymbol: Symbol): ExchangeInstrumentRules =
    ExchangeInstrumentRules(
        symbol = symbol?.let(::Symbol) ?: fallbackSymbol,
        status = requireNotNull(status) { "Bybit instrument status was absent." },
        contractType = requireNotNull(contractType) { "Bybit instrument contract type was absent." },
        baseCoin = requireNotNull(baseCoin) { "Bybit instrument base coin was absent." },
        quoteCoin = requireNotNull(quoteCoin) { "Bybit instrument quote coin was absent." },
        settleCoin = requireNotNull(settleCoin) { "Bybit instrument settle coin was absent." },
        unifiedMarginTrade = unifiedMarginTrade,
        minimumOrderQuantity = requireNotNull(lotSizeFilter?.minOrderQty.toBigDecimalOrNull()),
        quantityStep = requireNotNull(lotSizeFilter?.qtyStep.toBigDecimalOrNull()),
        minimumNotional = requireNotNull(lotSizeFilter?.minNotionalValue.toBigDecimalOrNull()),
        priceTick = requireNotNull(priceFilter?.tickSize.toBigDecimalOrNull()),
        minimumLeverage = requireNotNull(leverageFilter?.minLeverage.toBigDecimalOrNull()),
        maximumLeverage = requireNotNull(leverageFilter?.maxLeverage.toBigDecimalOrNull()),
        leverageStep = requireNotNull(leverageFilter?.leverageStep.toBigDecimalOrNull()),
    )

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
        executionId = execId,
        executionType = execType,
        createType = createType,
        stopOrderType = stopOrderType,
        closedSize = closedSize.toBigDecimalOrNull(),
        executionPnl = execPnl.toBigDecimalOrNull(),
    )
}

private fun BybitClosedPnlItem.toExchangeClosedPnl(fallbackSymbol: Symbol): ExchangeClosedPnl? {
    val side = side.toSide() ?: return null
    val createdAt = createdTime.toInstantFromMillisOrNull() ?: updatedTime.toInstantFromMillisOrNull() ?: return null
    val entryPrice = avgEntryPrice.toBigDecimalOrNull() ?: return null
    val exitPrice = avgExitPrice.toBigDecimalOrNull() ?: return null
    val quantity = qty.toBigDecimalOrNull() ?: return null
    val netPnl = closedPnl.toBigDecimalOrNull() ?: return null
    val openFee = openFee.toBigDecimalOrNull() ?: BigDecimal.ZERO
    val closeFee = closeFee.toBigDecimalOrNull() ?: BigDecimal.ZERO
    val fees = openFee + closeFee
    return ExchangeClosedPnl(
        exchangeOrderId = orderId,
        clientOrderId = orderLinkId,
        symbol = symbol?.let(::Symbol) ?: fallbackSymbol,
        side = side,
        openedAt = createdAt,
        closedAt = updatedTime.toInstantFromMillisOrNull() ?: createdAt,
        entryPrice = entryPrice,
        exitPrice = exitPrice,
        quantity = quantity,
        grossPnl = netPnl + fees,
        fees = fees,
        netPnl = netPnl,
        exitReason = "CLOSED_PNL",
    )
}

private fun BybitWalletBalanceAccount.toExchangeAccountBalance(capturedAt: Instant): ExchangeAccountBalance =
    ExchangeAccountBalance(
        accountType = accountType,
        totalEquity = totalEquity.toBigDecimalOrNull(),
        totalWalletBalance = totalWalletBalance.toBigDecimalOrNull(),
        totalMarginBalance = totalMarginBalance.toBigDecimalOrNull(),
        totalAvailableBalance = totalAvailableBalance.toBigDecimalOrNull(),
        totalPerpUnrealizedPnl = totalPerpUPL.toBigDecimalOrNull(),
        totalInitialMargin = totalInitialMargin.toBigDecimalOrNull(),
        totalMaintenanceMargin = totalMaintenanceMargin.toBigDecimalOrNull(),
        coins = coin.map(BybitWalletBalanceCoin::toExchangeCoinBalance),
        capturedAt = capturedAt,
    )

private fun BybitWalletBalanceCoin.toExchangeCoinBalance(): ExchangeCoinBalance =
    ExchangeCoinBalance(
        coin = coin,
        equity = equity.toBigDecimalOrNull(),
        usdValue = usdValue.toBigDecimalOrNull(),
        walletBalance = walletBalance.toBigDecimalOrNull(),
        locked = locked.toBigDecimalOrNull(),
        unrealizedPnl = unrealisedPnl.toBigDecimalOrNull(),
        cumulativeRealizedPnl = cumRealisedPnl.toBigDecimalOrNull(),
    )

private fun BybitTransactionLogItem.toExchangeAccountTransaction(): ExchangeAccountTransaction? {
    val normalizedId = id?.takeIf(String::isNotBlank) ?: return null
    val timestamp = transactionTime.toInstantFromMillisOrNull() ?: return null
    val normalizedCurrency = currency?.takeIf(String::isNotBlank) ?: return null
    val normalizedType = type?.takeIf(String::isNotBlank) ?: return null
    return ExchangeAccountTransaction(
        transactionId = normalizedId,
        symbol = symbol?.takeIf(String::isNotBlank)?.let(::Symbol),
        category = category.orEmpty(),
        side = side.toSide(),
        transactionAt = timestamp,
        type = normalizedType,
        subtype = transSubType?.takeIf(String::isNotBlank),
        quantity = qty.toBigDecimalOrNull(),
        size = size.toBigDecimalOrNull(),
        currency = normalizedCurrency,
        tradePrice = tradePrice.toBigDecimalOrNull(),
        funding = funding.toBigDecimalOrNull() ?: BigDecimal.ZERO,
        fee = fee.toBigDecimalOrNull() ?: BigDecimal.ZERO,
        cashFlow = cashFlow.toBigDecimalOrNull() ?: BigDecimal.ZERO,
        change = change.toBigDecimalOrNull() ?: BigDecimal.ZERO,
        cashBalance = cashBalance.toBigDecimalOrNull(),
        feeRate = feeRate.toBigDecimalOrNull(),
        tradeId = tradeId?.takeIf(String::isNotBlank),
        exchangeOrderId = orderId?.takeIf(String::isNotBlank),
        clientOrderId = orderLinkId?.takeIf(String::isNotBlank),
    )
}

private fun ExchangeAccountTransaction.identityKey(): String =
    listOf(
        transactionId,
        type,
        tradeId.orEmpty(),
        exchangeOrderId.orEmpty(),
        transactionAt.toString(),
        change.toPlainString(),
    ).joinToString("|")

private interface BybitOrderResponse {
    val retCode: Int
    val retMsg: String
}

@Serializable
private data class BybitSetLeverageBody(
    val category: String,
    val symbol: String,
    val buyLeverage: String,
    val sellLeverage: String,
)

@Serializable
private data class BybitSetTradingStopBody(
    val category: String,
    val symbol: String,
    val takeProfit: String,
    val stopLoss: String,
    val tpslMode: String,
    val tpTriggerBy: String? = null,
    val slTriggerBy: String,
    val positionIdx: Int,
)

@Serializable
private data class BybitPlaceOrderBody(
    val category: String,
    val symbol: String,
    val side: String,
    val orderType: String,
    val qty: String,
    val price: String? = null,
    val timeInForce: String,
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
private data class BybitSetLeverageResponse(
    override val retCode: Int,
    override val retMsg: String,
) : BybitOrderResponse

@Serializable
private data class BybitSetTradingStopResponse(
    override val retCode: Int,
    override val retMsg: String,
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
    val cumExecQty: String? = null,
    val createdTime: String? = null,
    val updatedTime: String? = null,
    val reduceOnly: Boolean = false,
    val stopOrderType: String? = null,
    val cancelType: String? = null,
    val rejectReason: String? = null,
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
    val openTime: Long? = null,
    val takeProfit: String? = null,
    val stopLoss: String? = null,
    val updatedTime: String? = null,
    val positionIdx: Int? = null,
    val leverage: String? = null,
    val isReduceOnly: Boolean = false,
)

@Serializable
private data class BybitAccountInfoResponse(
    override val retCode: Int,
    override val retMsg: String,
    val result: BybitAccountInfoResult? = null,
) : BybitOrderResponse

@Serializable
private data class BybitAccountInfoResult(
    val unifiedMarginStatus: Int,
    val marginMode: String,
    val updatedTime: String? = null,
)

@Serializable
private data class BybitInstrumentInfoResponse(
    override val retCode: Int,
    override val retMsg: String,
    val result: BybitInstrumentInfoResult? = null,
) : BybitOrderResponse

@Serializable
private data class BybitInstrumentInfoResult(
    val list: List<BybitInstrumentInfoItem> = emptyList(),
)

@Serializable
private data class BybitInstrumentInfoItem(
    val symbol: String? = null,
    val status: String? = null,
    val contractType: String? = null,
    val baseCoin: String? = null,
    val quoteCoin: String? = null,
    val settleCoin: String? = null,
    val unifiedMarginTrade: Boolean = false,
    val lotSizeFilter: BybitLotSizeFilter? = null,
    val priceFilter: BybitPriceFilter? = null,
    val leverageFilter: BybitLeverageFilter? = null,
)

@Serializable
private data class BybitLotSizeFilter(
    val minOrderQty: String? = null,
    val qtyStep: String? = null,
    val minNotionalValue: String? = null,
)

@Serializable
private data class BybitPriceFilter(
    val tickSize: String? = null,
)

@Serializable
private data class BybitLeverageFilter(
    val minLeverage: String? = null,
    val maxLeverage: String? = null,
    val leverageStep: String? = null,
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
    val nextPageCursor: String? = null,
)

@Serializable
private data class BybitExecutionItem(
    val execId: String? = null,
    val orderId: String? = null,
    val orderLinkId: String? = null,
    val symbol: String? = null,
    val side: String? = null,
    val execPrice: String? = null,
    val execQty: String? = null,
    val execFee: String? = null,
    val execTime: String? = null,
    val execPnl: String? = null,
    val closedSize: String? = null,
    val execType: String? = null,
    val createType: String? = null,
    val stopOrderType: String? = null,
)

@Serializable
private data class BybitClosedPnlResponse(
    override val retCode: Int,
    override val retMsg: String,
    val result: BybitClosedPnlResult? = null,
) : BybitOrderResponse

@Serializable
private data class BybitClosedPnlResult(
    val list: List<BybitClosedPnlItem> = emptyList(),
    val nextPageCursor: String? = null,
)

@Serializable
private data class BybitClosedPnlItem(
    val orderId: String? = null,
    val orderLinkId: String? = null,
    val symbol: String? = null,
    val side: String? = null,
    val qty: String? = null,
    val avgEntryPrice: String? = null,
    val avgExitPrice: String? = null,
    val closedPnl: String? = null,
    val openFee: String? = null,
    val closeFee: String? = null,
    val createdTime: String? = null,
    val updatedTime: String? = null,
)

@Serializable
private data class BybitWalletBalanceResponse(
    override val retCode: Int,
    override val retMsg: String,
    val result: BybitWalletBalanceResult? = null,
) : BybitOrderResponse

@Serializable
private data class BybitWalletBalanceResult(
    val list: List<BybitWalletBalanceAccount> = emptyList(),
)

@Serializable
private data class BybitWalletBalanceAccount(
    val accountType: String,
    val totalEquity: String? = null,
    val totalWalletBalance: String? = null,
    val totalMarginBalance: String? = null,
    val totalAvailableBalance: String? = null,
    val totalPerpUPL: String? = null,
    val totalInitialMargin: String? = null,
    val totalMaintenanceMargin: String? = null,
    val coin: List<BybitWalletBalanceCoin> = emptyList(),
)

@Serializable
private data class BybitWalletBalanceCoin(
    val coin: String,
    val equity: String? = null,
    val usdValue: String? = null,
    val walletBalance: String? = null,
    val locked: String? = null,
    @SerialName("unrealisedPnl")
    val unrealisedPnl: String? = null,
    val cumRealisedPnl: String? = null,
)

@Serializable
private data class BybitTransactionLogResponse(
    override val retCode: Int,
    override val retMsg: String,
    val result: BybitTransactionLogResult? = null,
) : BybitOrderResponse

@Serializable
private data class BybitTransactionLogResult(
    val list: List<BybitTransactionLogItem> = emptyList(),
    val nextPageCursor: String? = null,
)

@Serializable
private data class BybitTransactionLogItem(
    val id: String? = null,
    val symbol: String? = null,
    val category: String? = null,
    val side: String? = null,
    val transactionTime: String? = null,
    val type: String? = null,
    val transSubType: String? = null,
    val qty: String? = null,
    val size: String? = null,
    val currency: String? = null,
    val tradePrice: String? = null,
    val funding: String? = null,
    val fee: String? = null,
    val cashFlow: String? = null,
    val change: String? = null,
    val cashBalance: String? = null,
    val feeRate: String? = null,
    val tradeId: String? = null,
    val orderId: String? = null,
    val orderLinkId: String? = null,
)

private const val BYBIT_PRIVATE_HISTORY_PAGE_LIMIT = 100
private const val MAX_PRIVATE_HISTORY_PAGES = 1_000
private val MAX_TRANSACTION_LOG_RANGE: Duration = Duration.ofDays(7)
