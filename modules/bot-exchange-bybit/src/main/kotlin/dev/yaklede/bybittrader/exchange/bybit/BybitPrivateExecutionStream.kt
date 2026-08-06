package dev.yaklede.bybittrader.exchange.bybit

import dev.yaklede.bybittrader.domain.OrderStatus
import dev.yaklede.bybittrader.domain.OrderType
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionFill
import dev.yaklede.bybittrader.engine.execution.ExchangeOrderUpdate
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.LinkedHashMap
import java.util.UUID
import kotlin.math.min

class BybitPrivateExecutionStream(
    private val httpClient: HttpClient,
    private val config: BybitPrivateExecutionStreamConfig,
    private val clock: Clock = Clock.systemUTC(),
    private val onExecution: suspend (ExchangeExecutionFill) -> Unit = {},
    private val onOrder: suspend (ExchangeOrderUpdate) -> Unit = {},
) {
    private val logger = LoggerFactory.getLogger(BybitPrivateExecutionStream::class.java)
    private val parser = BybitPrivateExecutionParser()
    private val signer =
        BybitRequestSigner(
            keyId = config.keyId,
            signingCredential = config.signingCredential,
            clock = clock,
        )
    private val seenExecutionKeys = LinkedHashMap<String, Unit>()
    private val seenOrderKeys = LinkedHashMap<String, Unit>()
    private var streamJob: Job? = null

    fun start(scope: CoroutineScope): Job {
        check(streamJob?.isActive != true) { "Bybit private execution stream is already running." }
        return scope
            .launch {
                streamJob = coroutineContext[Job]
                runLoop()
            }.also { streamJob = it }
    }

    fun stop() {
        streamJob?.cancel()
    }

    private suspend fun runLoop() {
        var reconnectDelayMillis = config.minimumReconnectDelayMillis
        while (currentCoroutineContext().isActive) {
            try {
                runSession()
                reconnectDelayMillis = config.minimumReconnectDelayMillis
                logger.warn("Bybit private execution stream disconnected; reconnecting")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logger.warn(
                    "Bybit private execution stream failed; reconnecting delayMillis={}",
                    reconnectDelayMillis,
                    error,
                )
            }
            delay(reconnectDelayMillis)
            reconnectDelayMillis =
                min(
                    config.maximumReconnectDelayMillis,
                    reconnectDelayMillis * 2,
                )
        }
    }

    private suspend fun runSession() {
        val connectionId = UUID.randomUUID().toString()
        logger.info(
            "Bybit private execution stream connecting connectionId={} url={}",
            connectionId,
            config.webSocketUrl,
        )
        httpClient.webSocket(urlString = config.webSocketUrl) {
            val heartbeatJob =
                launch {
                    while (isActive) {
                        delay(config.heartbeatIntervalMillis)
                        send(PING_PAYLOAD)
                    }
                }
            try {
                val expiresMillis = clock.millis() + config.authValidityMillis
                send(authPayload(expiresMillis, signer.signWebSocket(expiresMillis)))
                send(SUBSCRIBE_PAYLOAD)
                logger.info("Bybit private execution stream auth and subscription sent connectionId={}", connectionId)
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    val payload = frame.data.decodeToString()
                    parser.controlFailure(payload)?.let { message ->
                        throw BybitPrivateExecutionStreamException(message)
                    }
                    parser.parse(payload).forEach { execution ->
                        dispatchExecution(connectionId, execution)
                    }
                    parser.parseOrders(payload).forEach { order ->
                        dispatchOrder(connectionId, order)
                    }
                }
            } finally {
                heartbeatJob.cancelAndJoin()
            }
        }
    }

    private suspend fun dispatchExecution(
        connectionId: String,
        execution: ExchangeExecutionFill,
    ) {
        val executionKey = execution.deduplicationKey()
        synchronized(seenExecutionKeys) {
            if (seenExecutionKeys.containsKey(executionKey)) return
            seenExecutionKeys[executionKey] = Unit
            while (seenExecutionKeys.size > config.maximumRememberedExecutions) {
                seenExecutionKeys.remove(seenExecutionKeys.entries.first().key)
            }
        }
        logger.info(
            "Bybit private execution observed connectionId={} symbol={} orderId={} executionId={} qty={} closedSize={} createType={} stopOrderType={}",
            connectionId,
            execution.symbol.value,
            execution.exchangeOrderId,
            execution.executionId,
            execution.quantity.toPlainString(),
            execution.closedSize?.toPlainString(),
            execution.createType,
            execution.stopOrderType,
        )
        try {
            onExecution(execution)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logger.warn(
                "Bybit private execution callback failed executionId={}",
                execution.executionId,
                error,
            )
        }
    }

    private suspend fun dispatchOrder(
        connectionId: String,
        order: ExchangeOrderUpdate,
    ) {
        val orderKey = order.deduplicationKey()
        synchronized(seenOrderKeys) {
            if (seenOrderKeys.containsKey(orderKey)) return
            seenOrderKeys[orderKey] = Unit
            while (seenOrderKeys.size > config.maximumRememberedExecutions) {
                seenOrderKeys.remove(seenOrderKeys.entries.first().key)
            }
        }
        logger.info(
            "Bybit private order observed connectionId={} symbol={} orderId={} clientOrderId={} status={} cumExecQty={} leavesQty={}",
            connectionId,
            order.symbol.value,
            order.exchangeOrderId,
            order.clientOrderId,
            order.status.name,
            order.cumulativeFilledQuantity.toPlainString(),
            order.leavesQuantity.toPlainString(),
        )
        try {
            onOrder(order)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logger.warn(
                "Bybit private order callback failed exchangeOrderId={} clientOrderId={}",
                order.exchangeOrderId,
                order.clientOrderId,
                error,
            )
        }
    }

    private fun ExchangeExecutionFill.deduplicationKey(): String =
        executionId
            ?: listOf(
                exchangeOrderId,
                clientOrderId,
                symbol.value,
                side.name,
                price.toPlainString(),
                quantity.toPlainString(),
                executedAt.toEpochMilli().toString(),
            ).joinToString("|")

    private fun ExchangeOrderUpdate.deduplicationKey(): String =
        listOf(
            exchangeOrderId.orEmpty(),
            clientOrderId.orEmpty(),
            status.name,
            cumulativeFilledQuantity.toPlainString(),
            leavesQuantity.toPlainString(),
            rejectReason.orEmpty(),
            cancelType.orEmpty(),
            updatedAt.toEpochMilli().toString(),
        ).joinToString("|")

    private fun authPayload(
        expiresMillis: Long,
        signature: String,
    ): String = """{"op":"auth","args":["${config.keyId}",$expiresMillis,"$signature"]}"""

    companion object {
        private const val PING_PAYLOAD = "{\"op\":\"ping\"}"
        private const val SUBSCRIBE_PAYLOAD = "{\"op\":\"subscribe\",\"args\":[\"execution\",\"order\"]}"
    }
}

data class BybitPrivateExecutionStreamConfig(
    val keyId: String,
    val signingCredential: String,
    val webSocketUrl: String,
    val heartbeatIntervalMillis: Long = 20_000,
    val authValidityMillis: Long = 10_000,
    val minimumReconnectDelayMillis: Long = 1_000,
    val maximumReconnectDelayMillis: Long = 30_000,
    val maximumRememberedExecutions: Int = 10_000,
) {
    init {
        require(keyId.isNotBlank()) { "Bybit private execution stream API key must not be blank." }
        require(signingCredential.isNotBlank()) { "Bybit private execution stream API secret must not be blank." }
        require(webSocketUrl.startsWith("ws://") || webSocketUrl.startsWith("wss://")) {
            "Bybit private execution stream URL must use ws or wss."
        }
        require(heartbeatIntervalMillis in 5_000..120_000) {
            "Bybit private execution stream heartbeat must be between 5000 and 120000 ms."
        }
        require(authValidityMillis in 1_000..60_000) {
            "Bybit private execution stream auth validity must be between 1000 and 60000 ms."
        }
        require(minimumReconnectDelayMillis in 250..60_000) {
            "Bybit private execution stream minimum reconnect delay must be between 250 and 60000 ms."
        }
        require(maximumReconnectDelayMillis >= minimumReconnectDelayMillis) {
            "Bybit private execution stream maximum reconnect delay must not be lower than minimum delay."
        }
        require(maximumRememberedExecutions in 100..100_000) {
            "Bybit private execution stream remembered executions must be between 100 and 100000."
        }
    }
}

class BybitPrivateExecutionStreamException(
    message: String,
) : RuntimeException(message)

class BybitPrivateExecutionParser(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun parse(payload: String): List<ExchangeExecutionFill> {
        val root = payload.toJsonObject() ?: return emptyList()
        if (root["topic"]?.jsonPrimitive?.contentOrNull != "execution") return emptyList()
        return root["data"]
            ?.jsonArray
            ?.mapNotNull { it.jsonObject.toExchangeExecution() }
            .orEmpty()
    }

    fun parseOrders(payload: String): List<ExchangeOrderUpdate> {
        val root = payload.toJsonObject() ?: return emptyList()
        if (root["topic"]?.jsonPrimitive?.contentOrNull != "order") return emptyList()
        val createdAt = root.string("creationTime")?.toLongOrNull()?.let(Instant::ofEpochMilli)
        return root["data"]
            ?.jsonArray
            ?.mapNotNull { item -> item.jsonObject.toExchangeOrderUpdate(createdAt) }
            .orEmpty()
    }

    fun controlFailure(payload: String): String? {
        val root = payload.toJsonObject() ?: return null
        val operation = root["op"]?.jsonPrimitive?.contentOrNull ?: return null
        val success = root["success"]?.jsonPrimitive?.booleanOrNull ?: return null
        if (success) return null
        val message =
            root["ret_msg"]?.jsonPrimitive?.contentOrNull
                ?: root["retCode"]?.jsonPrimitive?.contentOrNull
                ?: "unknown Bybit WebSocket error"
        return "Bybit private execution WebSocket $operation failed: $message"
    }

    private fun JsonObject.toExchangeExecution(): ExchangeExecutionFill? {
        val side =
            when (string("side")) {
                "Buy" -> Side.BUY
                "Sell" -> Side.SELL
                else -> return null
            }
        val symbol = string("symbol")?.let(::Symbol) ?: return null
        val price = string("execPrice")?.toBigDecimalOrNull() ?: return null
        val quantity = string("execQty")?.toBigDecimalOrNull() ?: return null
        val executedAt = string("execTime")?.toLongOrNull()?.let(Instant::ofEpochMilli) ?: return null
        if (price <= BigDecimal.ZERO || quantity <= BigDecimal.ZERO) return null
        return ExchangeExecutionFill(
            exchangeOrderId = string("orderId"),
            clientOrderId = string("orderLinkId"),
            symbol = symbol,
            side = side,
            price = price,
            quantity = quantity,
            fee = string("execFee")?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            executedAt = executedAt,
            executionId = string("execId"),
            executionType = string("execType"),
            createType = string("createType"),
            stopOrderType = string("stopOrderType"),
            closedSize = string("closedSize")?.toBigDecimalOrNull(),
            executionPnl = string("execPnl")?.toBigDecimalOrNull(),
        )
    }

    private fun JsonObject.toExchangeOrderUpdate(messageCreatedAt: Instant?): ExchangeOrderUpdate? {
        val side =
            when (string("side")) {
                "Buy" -> Side.BUY
                "Sell" -> Side.SELL
                else -> return null
            }
        val orderType =
            when (string("orderType")) {
                "Market" -> OrderType.MARKET
                "Limit" -> OrderType.LIMIT
                else -> return null
            }
        val status =
            when (string("orderStatus")) {
                "New",
                "Created",
                "Untriggered",
                "PendingCancel",
                -> OrderStatus.SUBMITTED

                "PartiallyFilled" -> OrderStatus.PARTIALLY_FILLED
                "Filled" -> OrderStatus.FILLED
                "Cancelled",
                "Deactivated",
                "PartiallyFilledCanceled",
                -> OrderStatus.CANCELLED

                "Rejected" -> OrderStatus.REJECTED
                else -> return null
            }
        val quantity = string("qty")?.toBigDecimalOrNull() ?: return null
        val cumulativeFilledQuantity = string("cumExecQty")?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        val leavesQuantity =
            string("leavesQty")?.toBigDecimalOrNull()
                ?: quantity.subtract(cumulativeFilledQuantity).max(BigDecimal.ZERO)
        val updatedAt =
            string("updatedTime")?.toLongOrNull()?.let(Instant::ofEpochMilli)
                ?: messageCreatedAt
                ?: return null
        return runCatching {
            ExchangeOrderUpdate(
                exchangeOrderId = string("orderId"),
                clientOrderId = string("orderLinkId"),
                parentClientOrderId = string("parentOrderLinkId"),
                symbol = string("symbol")?.let(::Symbol) ?: return null,
                side = side,
                orderType = orderType,
                status = status,
                quantity = quantity,
                cumulativeFilledQuantity = cumulativeFilledQuantity,
                leavesQuantity = leavesQuantity,
                averageFillPrice = string("avgPrice")?.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO },
                reduceOnly = this["reduceOnly"]?.jsonPrimitive?.booleanOrNull ?: false,
                rejectReason = string("rejectReason"),
                cancelType = string("cancelType"),
                updatedAt = updatedAt,
            )
        }.getOrNull()
    }

    private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)

    private fun String.toJsonObject(): JsonObject? = runCatching { json.parseToJsonElement(this).jsonObject }.getOrNull()
}
