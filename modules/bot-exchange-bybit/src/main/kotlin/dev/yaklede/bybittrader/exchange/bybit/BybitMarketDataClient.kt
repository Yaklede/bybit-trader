package dev.yaklede.bybittrader.exchange.bybit

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.market.MarketDataException
import dev.yaklede.bybittrader.engine.market.MarketDataFeed
import dev.yaklede.bybittrader.engine.market.MarketTicker
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
    ): List<Candle> = fetchKlines(symbol = symbol, timeframe = timeframe, limit = limit, startAt = null, endAt = null)

    override suspend fun fetchCandles(
        symbol: Symbol,
        timeframe: Timeframe,
        startAt: Instant,
        endAt: Instant,
        limit: Int,
    ): List<Candle> {
        require(!startAt.isAfter(endAt)) { "Start time must be before or equal to end time." }
        return fetchKlines(symbol = symbol, timeframe = timeframe, limit = limit, startAt = startAt, endAt = endAt)
    }

    override suspend fun fetchTicker(symbol: Symbol): MarketTicker {
        val response =
            httpClient
                .get("${baseUrl.trimEnd('/')}/v5/market/tickers") {
                    parameter("category", category.apiValue)
                    parameter("symbol", symbol.value)
                }.body<BybitTickerResponse>()

        if (response.retCode != 0) {
            throw BybitMarketDataException(
                response.failureMessage("ticker"),
            )
        }

        val ticker =
            response.result
                ?.list
                ?.firstOrNull { item -> item.symbol == symbol.value }
                ?: throw BybitMarketDataException("Bybit ticker response had no ticker for ${symbol.value}.")

        return ticker.toMarketTicker(capturedAt = Instant.ofEpochMilli(response.time ?: System.currentTimeMillis()))
    }

    private suspend fun fetchKlines(
        symbol: Symbol,
        timeframe: Timeframe,
        limit: Int,
        startAt: Instant?,
        endAt: Instant?,
    ): List<Candle> {
        require(limit in 1..1000) { "Limit must be between 1 and 1000." }
        val response =
            httpClient
                .get("${baseUrl.trimEnd('/')}/v5/market/kline") {
                    parameter("category", category.apiValue)
                    parameter("symbol", symbol.value)
                    parameter("interval", timeframe.toBybitInterval())
                    parameter("limit", limit)
                    startAt?.let { parameter("start", it.toEpochMilli()) }
                    endAt?.let { parameter("end", it.toEpochMilli()) }
                }.body<BybitKlineResponse>()

        if (response.retCode != 0) {
            throw BybitMarketDataException(
                response.failureMessage("kline"),
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

private fun BybitTickerItem.toMarketTicker(capturedAt: Instant): MarketTicker =
    MarketTicker(
        symbol = Symbol(symbol),
        lastPrice = BigDecimal(lastPrice),
        markPrice = markPrice?.toBigDecimalOrNull(),
        indexPrice = indexPrice?.toBigDecimalOrNull(),
        price24hPcnt = price24hPcnt?.toBigDecimalOrNull(),
        fundingRate = fundingRate?.toBigDecimalOrNull(),
        nextFundingTime = nextFundingTime?.toLongOrNull()?.let(Instant::ofEpochMilli),
        capturedAt = capturedAt,
    )

@Serializable
private data class BybitKlineResponse(
    val retCode: Int,
    val retMsg: String,
    val result: BybitKlineResult? = null,
)

private fun BybitKlineResponse.failureMessage(action: String): String =
    bybitFailureMessage(action = action, retCode = retCode, retMsg = retMsg)

private fun BybitTickerResponse.failureMessage(action: String): String =
    bybitFailureMessage(action = action, retCode = retCode, retMsg = retMsg)

private fun bybitFailureMessage(
    action: String,
    retCode: Int,
    retMsg: String,
): String {
    val rateLimitSuffix = if (retCode == 10006 || retMsg.contains("rate", ignoreCase = true)) " rate limit" else ""
    return "Bybit $action request failed with code $retCode$rateLimitSuffix: $retMsg"
}

@Serializable
private data class BybitKlineResult(
    val symbol: String? = null,
    val category: String? = null,
    val list: List<List<String>> = emptyList(),
)

@Serializable
private data class BybitTickerResponse(
    val retCode: Int,
    val retMsg: String,
    val result: BybitTickerResult? = null,
    val time: Long? = null,
)

@Serializable
private data class BybitTickerResult(
    val category: String? = null,
    val list: List<BybitTickerItem> = emptyList(),
)

@Serializable
private data class BybitTickerItem(
    val symbol: String,
    val lastPrice: String,
    val markPrice: String? = null,
    val indexPrice: String? = null,
    val price24hPcnt: String? = null,
    val fundingRate: String? = null,
    val nextFundingTime: String? = null,
)
