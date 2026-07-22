package dev.yaklede.bybittrader.exchange.bybit

import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.market.capture.ForwardMarketCaptureBatch
import dev.yaklede.bybittrader.engine.market.capture.ForwardMarketCaptureEvent
import dev.yaklede.bybittrader.engine.market.capture.ForwardMarketCaptureFeed
import dev.yaklede.bybittrader.engine.market.capture.ForwardMarketDataQuality
import dev.yaklede.bybittrader.engine.market.capture.ForwardMarketEventKind
import dev.yaklede.bybittrader.engine.market.capture.ForwardMarketMessageType
import dev.yaklede.bybittrader.engine.market.capture.ForwardMarketRawEvent
import dev.yaklede.bybittrader.engine.market.capture.LiquidatedPositionSide
import dev.yaklede.bybittrader.engine.market.capture.LiquidationEvent
import dev.yaklede.bybittrader.engine.market.capture.OrderBookDepthSnapshot
import dev.yaklede.bybittrader.engine.market.capture.TakerTradeEvent
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.send
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.math.MathContext
import java.time.Clock
import java.time.Instant
import java.util.TreeMap
import java.util.UUID

private val DECIMAL_CONTEXT: MathContext = MathContext.DECIMAL64
private val BPS_MULTIPLIER: BigDecimal = BigDecimal("10000")

class BybitPublicMarketCaptureClient(
    private val httpClient: HttpClient,
    private val baseUrl: String = DEFAULT_BYBIT_PUBLIC_STREAM_URL,
    orderBookDepth: Int = DEFAULT_ORDER_BOOK_DEPTH,
    private val clock: Clock = Clock.systemUTC(),
) : ForwardMarketCaptureFeed {
    private val parser = BybitPublicMarketCaptureParser(orderBookDepth)

    init {
        require(baseUrl.startsWith("ws://") || baseUrl.startsWith("wss://")) {
            "Bybit public stream URL must use ws or wss."
        }
    }

    override suspend fun collect(
        symbol: Symbol,
        onBatch: suspend (ForwardMarketCaptureBatch) -> Unit,
    ) {
        val localConnectionId = UUID.randomUUID().toString()
        parser.beginConnection(localConnectionId)
        httpClient.webSocket(urlString = baseUrl) {
            send(
                Frame.Text(
                    """{"op":"subscribe","args":["orderbook.${parser.orderBookDepth}.${symbol.value}","allLiquidation.${symbol.value}","publicTrade.${symbol.value}"]}""",
                ),
            )
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val batch = parser.parse(frame.data.decodeToString(), Instant.now(clock)) ?: continue
                onBatch(batch)
                if (batch.rawEvent.quality.requiresReconnect) {
                    throw BybitPublicMarketCaptureGapException(
                        topic = batch.rawEvent.topic,
                        quality = batch.rawEvent.quality,
                        updateId = batch.rawEvent.updateId,
                    )
                }
            }
        }
    }
}

class BybitPublicMarketCaptureParser(
    val orderBookDepth: Int,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val bids = TreeMap<BigDecimal, BigDecimal>(compareByDescending { it })
    private val asks = TreeMap<BigDecimal, BigDecimal>()
    private var localConnectionId: String? = null
    private var bookEpoch: Long = 0
    private var bookReady: Boolean = false
    private var lastUpdateId: Long? = null

    init {
        require(orderBookDepth in 1..50) { "Order book depth must be between 1 and 50." }
    }

    fun beginConnection(localConnectionId: String) {
        require(localConnectionId.isNotBlank()) { "Bybit public stream local connection ID must not be blank." }
        this.localConnectionId = localConnectionId
        invalidateOrderBook()
    }

    fun parse(
        payload: String,
        receivedAt: Instant,
    ): ForwardMarketCaptureBatch? {
        val root = json.parseToJsonElement(payload).jsonObject
        val topic = root["topic"]?.jsonPrimitive?.contentOrNull ?: return null
        val connectionId = requireNotNull(localConnectionId) { "beginConnection must be called before parsing market data." }
        val eventKind = topic.eventKind() ?: return null
        val messageType = root.messageType() ?: return null
        val symbol = root.symbol(topic, eventKind) ?: return null
        val parsed =
            when (eventKind) {
                ForwardMarketEventKind.ORDER_BOOK -> parseOrderBook(root, symbol)
                ForwardMarketEventKind.LIQUIDATION ->
                    ParsedMarketMessage(parseLiquidations(root), ForwardMarketDataQuality.VALID)
                ForwardMarketEventKind.PUBLIC_TRADE ->
                    ParsedMarketMessage(parseTakerTrades(root), ForwardMarketDataQuality.VALID)
            }
        val sequences = root.sequences(eventKind)
        val objectData = root["data"] as? JsonObject
        return ForwardMarketCaptureBatch(
            rawEvent =
                ForwardMarketRawEvent(
                    localConnectionId = connectionId,
                    topic = topic,
                    symbol = symbol,
                    eventKind = eventKind,
                    messageType = messageType,
                    exchangeTimestamp = root.epochMillis("ts"),
                    matchingEngineTimestamp = root.epochMillis("cts") ?: objectData?.epochMillis("cts"),
                    receivedAt = receivedAt,
                    sequenceStart = sequences.minOrNull(),
                    sequenceEnd = sequences.maxOrNull(),
                    updateId = objectData?.long("u"),
                    bookEpoch = bookEpoch.takeIf { eventKind == ForwardMarketEventKind.ORDER_BOOK && it > 0 },
                    quality = parsed.quality,
                    rawPayload = payload,
                ),
            normalizedEvents = parsed.events,
        )
    }

    private fun parseOrderBook(
        root: JsonObject,
        symbol: Symbol,
    ): ParsedMarketMessage {
        val data = root["data"]?.jsonObject ?: return gap(ForwardMarketDataQuality.EMPTY_ORDER_BOOK)
        val messageType = root.messageType() ?: return gap(ForwardMarketDataQuality.EMPTY_ORDER_BOOK)
        val updateId = data.long("u")
        val reset = messageType == ForwardMarketMessageType.SNAPSHOT || updateId == 1L
        if (reset) {
            bookEpoch += 1
            bids.clear()
            asks.clear()
            if (updateId == null) return gap(ForwardMarketDataQuality.MISSING_UPDATE_ID)
            data["b"]?.jsonArray?.applyLevels(bids)
            data["a"]?.jsonArray?.applyLevels(asks)
            lastUpdateId = updateId
            bookReady = true
            return orderBookSnapshot(root, data, symbol, ForwardMarketDataQuality.SNAPSHOT_RESET)
        }
        if (!bookReady) return gap(ForwardMarketDataQuality.DELTA_BEFORE_SNAPSHOT)
        if (updateId == null) return gap(ForwardMarketDataQuality.MISSING_UPDATE_ID)
        if (lastUpdateId?.let { updateId <= it } == true) {
            return gap(ForwardMarketDataQuality.NON_MONOTONIC_UPDATE_ID)
        }
        data["b"]?.jsonArray?.applyLevels(bids)
        data["a"]?.jsonArray?.applyLevels(asks)
        lastUpdateId = updateId
        return orderBookSnapshot(root, data, symbol, ForwardMarketDataQuality.VALID)
    }

    private fun orderBookSnapshot(
        root: JsonObject,
        data: JsonObject,
        symbol: Symbol,
        quality: ForwardMarketDataQuality,
    ): ParsedMarketMessage {
        if (bids.isEmpty() || asks.isEmpty()) return gap(ForwardMarketDataQuality.EMPTY_ORDER_BOOK)
        val bestBid = bids.firstKey()
        val bestAsk = asks.firstKey()
        if (bestAsk < bestBid) return gap(ForwardMarketDataQuality.CROSSED_ORDER_BOOK)
        val midpoint = (bestBid + bestAsk).divide(BigDecimal("2"), DECIMAL_CONTEXT)
        if (midpoint <= BigDecimal.ZERO) return gap(ForwardMarketDataQuality.CROSSED_ORDER_BOOK)
        val capturedAt = root.epochMillis("cts") ?: data.epochMillis("cts") ?: root.epochMillis("ts")
        val events =
            capturedAt
                ?.let {
                    val spreadBps = (bestAsk - bestBid).divide(midpoint, DECIMAL_CONTEXT).multiply(BPS_MULTIPLIER)
                    listOf(
                        OrderBookDepthSnapshot(
                            symbol = symbol,
                            capturedAt = it,
                            bidNotional = bids.entries.take(orderBookDepth).sumNotional(),
                            askNotional = asks.entries.take(orderBookDepth).sumNotional(),
                            spreadBps = spreadBps,
                        ),
                    )
                }.orEmpty()
        return ParsedMarketMessage(events = events, quality = quality)
    }

    private fun gap(quality: ForwardMarketDataQuality): ParsedMarketMessage {
        invalidateOrderBook()
        return ParsedMarketMessage(events = emptyList(), quality = quality)
    }

    private fun invalidateOrderBook() {
        bids.clear()
        asks.clear()
        bookReady = false
        lastUpdateId = null
    }

    private fun parseLiquidations(root: JsonObject): List<LiquidationEvent> =
        root["data"]
            ?.jsonArray
            ?.mapNotNull { item ->
                val data = item.jsonObject
                val symbol = data["s"]?.jsonPrimitive?.contentOrNull?.let(::Symbol) ?: return@mapNotNull null
                val capturedAt = data.epochMillis("T") ?: return@mapNotNull null
                val side =
                    when (data["S"]?.jsonPrimitive?.contentOrNull) {
                        "Buy" -> LiquidatedPositionSide.LONG
                        "Sell" -> LiquidatedPositionSide.SHORT
                        else -> return@mapNotNull null
                    }
                val quantity = data["v"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: return@mapNotNull null
                val price = data["p"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: return@mapNotNull null
                if (quantity <= BigDecimal.ZERO || price <= BigDecimal.ZERO) return@mapNotNull null
                LiquidationEvent(
                    symbol = symbol,
                    capturedAt = capturedAt,
                    liquidatedSide = side,
                    notional = quantity.multiply(price),
                )
            }.orEmpty()

    private fun parseTakerTrades(root: JsonObject): List<TakerTradeEvent> =
        root["data"]
            ?.jsonArray
            ?.mapNotNull { item ->
                val data = item.jsonObject
                val symbol = data["s"]?.jsonPrimitive?.contentOrNull?.let(::Symbol) ?: return@mapNotNull null
                val capturedAt = data.epochMillis("T") ?: return@mapNotNull null
                val side =
                    when (data["S"]?.jsonPrimitive?.contentOrNull) {
                        "Buy" -> Side.BUY
                        "Sell" -> Side.SELL
                        else -> return@mapNotNull null
                    }
                val quantity = data["v"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: return@mapNotNull null
                val price = data["p"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: return@mapNotNull null
                if (quantity <= BigDecimal.ZERO || price <= BigDecimal.ZERO) return@mapNotNull null
                TakerTradeEvent(
                    symbol = symbol,
                    capturedAt = capturedAt,
                    takerSide = side,
                    quantity = quantity,
                    price = price,
                )
            }.orEmpty()
}

class BybitPublicMarketCaptureGapException(
    topic: String,
    quality: ForwardMarketDataQuality,
    updateId: Long?,
) : IllegalStateException(
        "Bybit public market data gap detected topic=%s quality=%s updateId=%s."
            .format(topic, quality.name, updateId ?: "missing"),
    )

private data class ParsedMarketMessage(
    val events: List<ForwardMarketCaptureEvent>,
    val quality: ForwardMarketDataQuality,
)

private fun String.eventKind(): ForwardMarketEventKind? =
    when {
        startsWith("orderbook.") -> ForwardMarketEventKind.ORDER_BOOK
        startsWith("allLiquidation.") -> ForwardMarketEventKind.LIQUIDATION
        startsWith("publicTrade.") -> ForwardMarketEventKind.PUBLIC_TRADE
        else -> null
    }

private fun JsonObject.messageType(): ForwardMarketMessageType? =
    when (this["type"]?.jsonPrimitive?.contentOrNull) {
        "snapshot" -> ForwardMarketMessageType.SNAPSHOT
        "delta" -> ForwardMarketMessageType.DELTA
        else -> null
    }

private fun JsonObject.symbol(
    topic: String,
    eventKind: ForwardMarketEventKind,
): Symbol? {
    val fromPayload =
        when (eventKind) {
            ForwardMarketEventKind.ORDER_BOOK -> (this["data"] as? JsonObject)?.get("s")?.jsonPrimitive?.contentOrNull
            ForwardMarketEventKind.PUBLIC_TRADE,
            ForwardMarketEventKind.LIQUIDATION,
            ->
                (this["data"] as? JsonArray)
                    ?.firstOrNull()
                    ?.jsonObject
                    ?.get("s")
                    ?.jsonPrimitive
                    ?.contentOrNull
        }
    return (fromPayload ?: topic.substringAfterLast('.').takeIf(String::isNotBlank))?.let(::Symbol)
}

private fun JsonObject.sequences(eventKind: ForwardMarketEventKind): List<Long> =
    when (eventKind) {
        ForwardMarketEventKind.ORDER_BOOK -> listOfNotNull((this["data"] as? JsonObject)?.long("seq"))
        ForwardMarketEventKind.PUBLIC_TRADE ->
            (this["data"] as? JsonArray)
                ?.mapNotNull { item -> item.jsonObject.long("seq") }
                .orEmpty()
        ForwardMarketEventKind.LIQUIDATION -> emptyList()
    }

private fun JsonArray.applyLevels(levels: MutableMap<BigDecimal, BigDecimal>) {
    forEach { row ->
        val fields = row.jsonArray
        if (fields.size < 2) return@forEach
        val price = fields[0].jsonPrimitive.contentOrNull?.toBigDecimalOrNull() ?: return@forEach
        val quantity = fields[1].jsonPrimitive.contentOrNull?.toBigDecimalOrNull() ?: return@forEach
        if (quantity <= BigDecimal.ZERO) {
            levels.remove(price)
        } else {
            levels[price] = quantity
        }
    }
}

private fun Iterable<Map.Entry<BigDecimal, BigDecimal>>.sumNotional(): BigDecimal =
    fold(BigDecimal.ZERO) { sum, entry -> sum + entry.key.multiply(entry.value) }

private fun JsonObject.epochMillis(key: String): Instant? = long(key)?.let(Instant::ofEpochMilli)

private fun JsonObject.long(key: String): Long? =
    this[key]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.toLongOrNull()

private const val DEFAULT_ORDER_BOOK_DEPTH = 50
private const val DEFAULT_BYBIT_PUBLIC_STREAM_URL = "wss://stream.bybit.com/v5/public/linear"
