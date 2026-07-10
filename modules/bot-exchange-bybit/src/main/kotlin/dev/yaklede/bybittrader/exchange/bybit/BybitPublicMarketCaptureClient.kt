package dev.yaklede.bybittrader.exchange.bybit

import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.market.capture.ForwardMarketCaptureEvent
import dev.yaklede.bybittrader.engine.market.capture.ForwardMarketCaptureFeed
import dev.yaklede.bybittrader.engine.market.capture.LiquidatedPositionSide
import dev.yaklede.bybittrader.engine.market.capture.LiquidationEvent
import dev.yaklede.bybittrader.engine.market.capture.OrderBookDepthSnapshot
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
import java.time.Instant
import java.util.TreeMap

private val DECIMAL_CONTEXT: MathContext = MathContext.DECIMAL64
private val BPS_MULTIPLIER: BigDecimal = BigDecimal("10000")

class BybitPublicMarketCaptureClient(
    private val httpClient: HttpClient,
    private val baseUrl: String = DEFAULT_BYBIT_PUBLIC_STREAM_URL,
    orderBookDepth: Int = DEFAULT_ORDER_BOOK_DEPTH,
) : ForwardMarketCaptureFeed {
    private val parser = BybitPublicMarketCaptureParser(orderBookDepth)

    init {
        require(baseUrl.startsWith("ws://") || baseUrl.startsWith("wss://")) {
            "Bybit public stream URL must use ws or wss."
        }
    }

    override suspend fun collect(
        symbol: Symbol,
        onEvent: suspend (ForwardMarketCaptureEvent) -> Unit,
    ) {
        httpClient.webSocket(urlString = baseUrl) {
            send(
                Frame.Text(
                    """{"op":"subscribe","args":["orderbook.${parser.orderBookDepth}.${symbol.value}","allLiquidation.${symbol.value}"]}""",
                ),
            )
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                parser.parse(frame.data.decodeToString()).forEach { event -> onEvent(event) }
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

    init {
        require(orderBookDepth in 1..50) { "Order book depth must be between 1 and 50." }
    }

    fun parse(payload: String): List<ForwardMarketCaptureEvent> {
        val root = json.parseToJsonElement(payload).jsonObject
        val topic = root["topic"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        return when {
            topic.startsWith("orderbook.") -> parseOrderBook(root)
            topic.startsWith("allLiquidation.") -> parseLiquidations(root)
            else -> emptyList()
        }
    }

    private fun parseOrderBook(root: JsonObject): List<ForwardMarketCaptureEvent> {
        val data = root["data"]?.jsonObject ?: return emptyList()
        when (root["type"]?.jsonPrimitive?.contentOrNull) {
            "snapshot" -> {
                bids.clear()
                asks.clear()
            }
            "delta" -> Unit
            else -> return emptyList()
        }
        data["b"]?.jsonArray?.applyLevels(bids)
        data["a"]?.jsonArray?.applyLevels(asks)
        if (bids.isEmpty() || asks.isEmpty()) return emptyList()

        val symbol = data["s"]?.jsonPrimitive?.contentOrNull?.let(::Symbol) ?: return emptyList()
        val capturedAt = data.epochMillis("cts") ?: root.epochMillis("ts") ?: return emptyList()
        val bestBid = bids.firstKey()
        val bestAsk = asks.firstKey()
        val midpoint = (bestBid + bestAsk).divide(BigDecimal("2"), DECIMAL_CONTEXT)
        if (midpoint <= BigDecimal.ZERO) return emptyList()
        val spreadBps = (bestAsk - bestBid).divide(midpoint, DECIMAL_CONTEXT).multiply(BPS_MULTIPLIER)
        return listOf(
            OrderBookDepthSnapshot(
                symbol = symbol,
                capturedAt = capturedAt,
                bidNotional = bids.entries.take(orderBookDepth).sumNotional(),
                askNotional = asks.entries.take(orderBookDepth).sumNotional(),
                spreadBps = spreadBps,
            ),
        )
    }

    private fun parseLiquidations(root: JsonObject): List<ForwardMarketCaptureEvent> =
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

private fun JsonObject.epochMillis(key: String): Instant? =
    this[key]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.toLongOrNull()
        ?.let(Instant::ofEpochMilli)

private const val DEFAULT_ORDER_BOOK_DEPTH = 50
private const val DEFAULT_BYBIT_PUBLIC_STREAM_URL = "wss://stream.bybit.com/v5/public/linear"
