package dev.yaklede.bybittrader.exchange.bybit

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.market.MarketDataException
import dev.yaklede.bybittrader.engine.market.MarketDataFeed
import dev.yaklede.bybittrader.engine.market.MarketTicker
import dev.yaklede.bybittrader.engine.market.flow.AccountRatioPeriod
import dev.yaklede.bybittrader.engine.market.flow.AccountRatioSnapshot
import dev.yaklede.bybittrader.engine.market.flow.FundingRateSnapshot
import dev.yaklede.bybittrader.engine.market.flow.OpenInterestInterval
import dev.yaklede.bybittrader.engine.market.flow.OpenInterestSnapshot
import dev.yaklede.bybittrader.engine.market.flow.PremiumIndexBar
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

    suspend fun fetchOpenInterestSnapshots(
        symbol: Symbol,
        interval: OpenInterestInterval,
        startAt: Instant,
        endAt: Instant,
        limit: Int = BYBIT_OPEN_INTEREST_MAX_LIMIT,
    ): List<OpenInterestSnapshot> {
        require(!startAt.isAfter(endAt)) { "Start time must be before or equal to end time." }
        require(limit in 1..BYBIT_OPEN_INTEREST_MAX_LIMIT) {
            "Open interest limit must be between 1 and $BYBIT_OPEN_INTEREST_MAX_LIMIT."
        }

        val snapshots = mutableListOf<OpenInterestSnapshot>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val response =
                httpClient
                    .get("${baseUrl.trimEnd('/')}/v5/market/open-interest") {
                        parameter("category", category.apiValue)
                        parameter("symbol", symbol.value)
                        parameter("intervalTime", interval.bybitValue)
                        parameter("startTime", startAt.toEpochMilli())
                        parameter("endTime", endAt.toEpochMilli())
                        parameter("limit", limit)
                        cursor?.takeIf(String::isNotBlank)?.let { parameter("cursor", it) }
                    }.body<BybitOpenInterestResponse>()

            if (response.retCode != 0) {
                throw BybitMarketDataException(response.failureMessage("open interest"))
            }

            val result = response.result ?: break
            result.validateSymbolAndCategory(symbol = symbol, category = category)
            snapshots += result.list.map { item -> item.toOpenInterestSnapshot(symbol = symbol, interval = interval) }
            cursor = result.nextPageCursor?.takeIf(String::isNotBlank)
            if (cursor != null && !seenCursors.add(cursor)) {
                throw BybitMarketDataException("Bybit open interest pagination repeated cursor $cursor.")
            }
        } while (cursor != null)

        return snapshots
            .filter { snapshot -> !snapshot.timestamp.isBefore(startAt) && !snapshot.timestamp.isAfter(endAt) }
            .distinctBy { snapshot -> snapshot.timestamp }
            .sortedBy { snapshot -> snapshot.timestamp }
    }

    suspend fun fetchAccountRatioSnapshots(
        symbol: Symbol,
        period: AccountRatioPeriod,
        startAt: Instant,
        endAt: Instant,
        limit: Int = BYBIT_ACCOUNT_RATIO_MAX_LIMIT,
    ): List<AccountRatioSnapshot> {
        require(!startAt.isAfter(endAt)) { "Start time must be before or equal to end time." }
        require(limit in 1..BYBIT_ACCOUNT_RATIO_MAX_LIMIT) {
            "Account ratio limit must be between 1 and $BYBIT_ACCOUNT_RATIO_MAX_LIMIT."
        }

        val snapshots = mutableListOf<AccountRatioSnapshot>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val response =
                httpClient
                    .get("${baseUrl.trimEnd('/')}/v5/market/account-ratio") {
                        parameter("category", category.apiValue)
                        parameter("symbol", symbol.value)
                        parameter("period", period.bybitValue)
                        parameter("startTime", startAt.toEpochMilli())
                        parameter("endTime", endAt.toEpochMilli())
                        parameter("limit", limit)
                        cursor?.takeIf(String::isNotBlank)?.let { parameter("cursor", it) }
                    }.body<BybitAccountRatioResponse>()

            if (response.retCode != 0) {
                throw BybitMarketDataException(response.failureMessage("account ratio"))
            }

            val result = response.result ?: break
            result.validateSymbolAndCategory(symbol = symbol, category = category)
            val page = result.list.map { item -> item.toAccountRatioSnapshot(period = period) }
            page.forEach { snapshot ->
                if (snapshot.symbol != symbol) {
                    throw BybitMarketDataException(
                        "Bybit account ratio response had symbol ${snapshot.symbol.value}, expected ${symbol.value}.",
                    )
                }
            }
            snapshots += page
            cursor = result.nextPageCursor?.takeIf(String::isNotBlank)
            if (cursor != null && !seenCursors.add(cursor)) {
                throw BybitMarketDataException("Bybit account ratio pagination repeated cursor $cursor.")
            }
        } while (cursor != null)

        return snapshots
            .filter { snapshot -> !snapshot.timestamp.isBefore(startAt) && !snapshot.timestamp.isAfter(endAt) }
            .distinctBy { snapshot -> snapshot.timestamp }
            .sortedBy { snapshot -> snapshot.timestamp }
    }

    suspend fun fetchPremiumIndexBars(
        symbol: Symbol,
        timeframe: Timeframe,
        startAt: Instant,
        endAt: Instant,
        limit: Int = BYBIT_KLINE_MAX_LIMIT,
    ): List<PremiumIndexBar> {
        require(timeframe == Timeframe.M15) { "Milestone 1 premium index fetches must use 15m timeframe." }
        require(!startAt.isAfter(endAt)) { "Start time must be before or equal to end time." }
        require(limit in 1..BYBIT_KLINE_MAX_LIMIT) { "Premium index limit must be between 1 and $BYBIT_KLINE_MAX_LIMIT." }

        val bars = mutableListOf<PremiumIndexBar>()
        var pageEndAt = endAt
        do {
            val response =
                httpClient
                    .get("${baseUrl.trimEnd('/')}/v5/market/premium-index-price-kline") {
                        parameter("category", category.apiValue)
                        parameter("symbol", symbol.value)
                        parameter("interval", timeframe.toBybitInterval())
                        parameter("start", startAt.toEpochMilli())
                        parameter("end", pageEndAt.toEpochMilli())
                        parameter("limit", limit)
                    }.body<BybitPremiumIndexKlineResponse>()

            if (response.retCode != 0) {
                throw BybitMarketDataException(response.failureMessage("premium index kline"))
            }

            val result = response.result ?: break
            result.validateSymbolAndCategory(symbol = symbol, category = category)
            val page = result.list.map { row -> row.toPremiumIndexBar(symbol = symbol, timeframe = timeframe) }
            bars += page
            val earliest = page.minOfOrNull { bar -> bar.openedAt } ?: break
            pageEndAt = earliest.minusMillis(1)
        } while (!pageEndAt.isBefore(startAt))

        return bars
            .filter { bar -> !bar.openedAt.isBefore(startAt) && !bar.openedAt.isAfter(endAt) }
            .distinctBy { bar -> bar.openedAt }
            .sortedBy { bar -> bar.openedAt }
    }

    suspend fun fetchFundingRateSnapshots(
        symbol: Symbol,
        startAt: Instant,
        endAt: Instant,
        limit: Int = BYBIT_FUNDING_MAX_LIMIT,
    ): List<FundingRateSnapshot> {
        require(!startAt.isAfter(endAt)) { "Start time must be before or equal to end time." }
        require(limit in 1..BYBIT_FUNDING_MAX_LIMIT) { "Funding rate limit must be between 1 and $BYBIT_FUNDING_MAX_LIMIT." }

        val snapshots = mutableListOf<FundingRateSnapshot>()
        var pageEndAt = endAt
        do {
            val response =
                httpClient
                    .get("${baseUrl.trimEnd('/')}/v5/market/funding/history") {
                        parameter("category", category.apiValue)
                        parameter("symbol", symbol.value)
                        parameter("startTime", startAt.toEpochMilli())
                        parameter("endTime", pageEndAt.toEpochMilli())
                        parameter("limit", limit)
                    }.body<BybitFundingRateResponse>()

            if (response.retCode != 0) {
                throw BybitMarketDataException(response.failureMessage("funding history"))
            }

            val result = response.result ?: break
            result.validateCategory(category)
            val page = result.list.map { item -> item.toFundingRateSnapshot(symbol = symbol) }
            page.forEach { snapshot ->
                if (snapshot.symbol != symbol) {
                    throw BybitMarketDataException(
                        "Bybit funding history response had symbol ${snapshot.symbol.value}, expected ${symbol.value}.",
                    )
                }
            }
            snapshots += page
            val earliest = page.minOfOrNull { snapshot -> snapshot.timestamp } ?: break
            pageEndAt = earliest.minusMillis(1)
        } while (!pageEndAt.isBefore(startAt))

        return snapshots
            .filter { snapshot -> !snapshot.timestamp.isBefore(startAt) && !snapshot.timestamp.isAfter(endAt) }
            .distinctBy { snapshot -> snapshot.timestamp }
            .sortedBy { snapshot -> snapshot.timestamp }
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

private fun BybitOpenInterestResponse.failureMessage(action: String): String =
    bybitFailureMessage(action = action, retCode = retCode, retMsg = retMsg)

private fun BybitAccountRatioResponse.failureMessage(action: String): String =
    bybitFailureMessage(action = action, retCode = retCode, retMsg = retMsg)

private fun BybitPremiumIndexKlineResponse.failureMessage(action: String): String =
    bybitFailureMessage(action = action, retCode = retCode, retMsg = retMsg)

private fun BybitFundingRateResponse.failureMessage(action: String): String =
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

private fun BybitSymbolCategoryResult.validateSymbolAndCategory(
    symbol: Symbol,
    category: BybitMarketCategory,
) {
    if (this.symbol != null && this.symbol != symbol.value) {
        throw BybitMarketDataException("Bybit response had symbol ${this.symbol}, expected ${symbol.value}.")
    }
    validateCategory(category)
}

private fun BybitCategoryResult.validateCategory(category: BybitMarketCategory) {
    if (this.category != null && this.category != category.apiValue) {
        throw BybitMarketDataException("Bybit response had category ${this.category}, expected ${category.apiValue}.")
    }
}

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

@Serializable
private data class BybitOpenInterestResponse(
    val retCode: Int,
    val retMsg: String,
    val result: BybitOpenInterestResult? = null,
)

@Serializable
private data class BybitOpenInterestResult(
    override val symbol: String? = null,
    override val category: String? = null,
    val list: List<BybitOpenInterestItem> = emptyList(),
    val nextPageCursor: String? = null,
) : BybitSymbolCategoryResult

@Serializable
private data class BybitOpenInterestItem(
    val openInterest: String,
    val timestamp: String,
)

private fun BybitOpenInterestItem.toOpenInterestSnapshot(
    symbol: Symbol,
    interval: OpenInterestInterval,
): OpenInterestSnapshot =
    OpenInterestSnapshot(
        symbol = symbol,
        interval = interval,
        timestamp = Instant.ofEpochMilli(timestamp.toLong()),
        openInterest = BigDecimal(openInterest),
    )

@Serializable
private data class BybitAccountRatioResponse(
    val retCode: Int,
    val retMsg: String,
    val result: BybitAccountRatioResult? = null,
)

@Serializable
private data class BybitAccountRatioResult(
    override val symbol: String? = null,
    override val category: String? = null,
    val list: List<BybitAccountRatioItem> = emptyList(),
    val nextPageCursor: String? = null,
) : BybitSymbolCategoryResult

@Serializable
private data class BybitAccountRatioItem(
    val symbol: String,
    val buyRatio: String,
    val sellRatio: String,
    val timestamp: String,
)

private fun BybitAccountRatioItem.toAccountRatioSnapshot(period: AccountRatioPeriod): AccountRatioSnapshot =
    AccountRatioSnapshot(
        symbol = Symbol(symbol),
        period = period,
        timestamp = Instant.ofEpochMilli(timestamp.toLong()),
        buyRatio = BigDecimal(buyRatio),
        sellRatio = BigDecimal(sellRatio),
    )

@Serializable
private data class BybitPremiumIndexKlineResponse(
    val retCode: Int,
    val retMsg: String,
    val result: BybitPremiumIndexKlineResult? = null,
)

@Serializable
private data class BybitPremiumIndexKlineResult(
    override val symbol: String? = null,
    override val category: String? = null,
    val list: List<List<String>> = emptyList(),
) : BybitSymbolCategoryResult

private fun List<String>.toPremiumIndexBar(
    symbol: Symbol,
    timeframe: Timeframe,
): PremiumIndexBar {
    require(size >= 5) { "Bybit premium index row must contain at least 5 fields." }
    return PremiumIndexBar(
        symbol = symbol,
        timeframe = timeframe,
        openedAt = Instant.ofEpochMilli(this[0].toLong()),
        open = BigDecimal(this[1]),
        high = BigDecimal(this[2]),
        low = BigDecimal(this[3]),
        close = BigDecimal(this[4]),
    )
}

@Serializable
private data class BybitFundingRateResponse(
    val retCode: Int,
    val retMsg: String,
    val result: BybitFundingRateResult? = null,
)

@Serializable
private data class BybitFundingRateResult(
    override val category: String? = null,
    val list: List<BybitFundingRateItem> = emptyList(),
) : BybitCategoryResult

@Serializable
private data class BybitFundingRateItem(
    val symbol: String,
    val fundingRate: String,
    val fundingRateTimestamp: String,
)

private fun BybitFundingRateItem.toFundingRateSnapshot(symbol: Symbol): FundingRateSnapshot =
    FundingRateSnapshot(
        symbol = Symbol(this.symbol),
        timestamp = Instant.ofEpochMilli(fundingRateTimestamp.toLong()),
        fundingRate = BigDecimal(fundingRate),
    )

private interface BybitCategoryResult {
    val category: String?
}

private interface BybitSymbolCategoryResult : BybitCategoryResult {
    val symbol: String?
}

private const val BYBIT_OPEN_INTEREST_MAX_LIMIT = 200
private const val BYBIT_ACCOUNT_RATIO_MAX_LIMIT = 500
private const val BYBIT_FUNDING_MAX_LIMIT = 200
private const val BYBIT_KLINE_MAX_LIMIT = 1000
