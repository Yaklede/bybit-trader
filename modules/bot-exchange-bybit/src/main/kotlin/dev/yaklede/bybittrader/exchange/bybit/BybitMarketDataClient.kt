package dev.yaklede.bybittrader.exchange.bybit

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.market.MarketDataException
import dev.yaklede.bybittrader.engine.market.MarketDataFeed
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.time.Instant

class BybitMarketDataClient(
    private val httpClient: HttpClient,
    private val baseUrl: String = "https://api.bybit.com",
    private val category: BybitMarketCategory = BybitMarketCategory.LINEAR,
) : MarketDataFeed {
    init {
        require(baseUrl.isNotBlank()) { "Bybit public base URL must not be blank." }
    }

    override suspend fun fetchRecentCandles(
        symbol: Symbol,
        timeframe: Timeframe,
        limit: Int,
    ): List<Candle> {
        require(limit in 1..1000) { "Limit must be between 1 and 1000." }
        val response =
            httpClient
                .get("${baseUrl.trimEnd('/')}/v5/market/kline") {
                    parameter("category", category.apiValue)
                    parameter("symbol", symbol.value)
                    parameter("interval", timeframe.toBybitInterval())
                    parameter("limit", limit)
                }.body<BybitKlineResponse>()

        if (response.retCode != 0) {
            throw BybitMarketDataException(
                "Bybit kline request failed with code ${response.retCode}: ${response.retMsg}",
            )
        }

        val result = response.result ?: return emptyList()
        return result.list
            .map { row -> row.toCandle(symbol = symbol, timeframe = timeframe) }
            .sortedBy { it.openedAt }
    }
}

enum class BybitMarketCategory(
    val apiValue: String,
) {
    LINEAR("linear"),
}

class BybitMarketDataException(
    message: String,
) : MarketDataException(message)

private fun Timeframe.toBybitInterval(): String =
    when (this) {
        Timeframe.M1 -> "1"
        Timeframe.M5 -> "5"
        Timeframe.M15 -> "15"
        Timeframe.H1 -> "60"
    }

private fun List<String>.toCandle(
    symbol: Symbol,
    timeframe: Timeframe,
): Candle {
    require(size >= 6) { "Bybit kline row must contain at least 6 fields." }
    return Candle(
        symbol = symbol,
        timeframe = timeframe,
        openedAt = Instant.ofEpochMilli(this[0].toLong()),
        open = BigDecimal(this[1]),
        high = BigDecimal(this[2]),
        low = BigDecimal(this[3]),
        close = BigDecimal(this[4]),
        volume = BigDecimal(this[5]),
    )
}

@Serializable
private data class BybitKlineResponse(
    val retCode: Int,
    val retMsg: String,
    val result: BybitKlineResult? = null,
)

@Serializable
private data class BybitKlineResult(
    val symbol: String,
    val category: String? = null,
    val list: List<List<String>> = emptyList(),
)
