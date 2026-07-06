package dev.yaklede.bybittrader.api.market

import dev.yaklede.bybittrader.domain.ResearchCandleLimits
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.market.MarketDataSyncResult
import dev.yaklede.bybittrader.engine.market.MarketDataSyncService
import dev.yaklede.bybittrader.engine.market.MarketTicker
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.format.DateTimeParseException

fun Route.configureMarketDataRoutes(marketDataSyncService: MarketDataSyncService) {
    authenticate("control") {
        get("/market-data/ticker") {
            val request =
                MarketTickerRequest(
                    symbol = call.request.queryParameters["symbol"] ?: "BTCUSDT",
                ).validated()
            call.respond(marketDataSyncService.ticker(Symbol(request.symbol)).toResponse())
        }

        post("/market-data/sync") {
            val request = call.receive<MarketDataSyncRequest>().validated()
            val result =
                marketDataSyncService.sync(
                    symbol = Symbol(request.symbol),
                    timeframes = request.timeframes.map(Timeframe::valueOf),
                    limit = request.limit,
                )
            call.respond(result.toResponse())
        }

        post("/market-data/history/sync") {
            val request = call.receive<MarketDataHistorySyncRequest>().validated()
            val result =
                marketDataSyncService.syncHistory(
                    symbol = Symbol(request.symbol),
                    timeframes = request.timeframes.map(Timeframe::valueOf),
                    startAt = request.parsedStartAt(),
                    endAt = request.parsedEndAt(),
                    daysBack = request.daysBack,
                    pageLimit = request.pageLimit,
                    maxRequestsPerTimeframe = request.maxRequestsPerTimeframe,
                )
            call.respond(result.toResponse())
        }
    }
}

@Serializable
data class MarketTickerRequest(
    val symbol: String,
) {
    fun validated(): MarketTickerRequest {
        val normalizedSymbol = symbol.trim().uppercase()
        Symbol(normalizedSymbol)
        return copy(symbol = normalizedSymbol)
    }
}

@Serializable
data class MarketDataSyncRequest(
    val symbol: String,
    val timeframes: List<String>,
    val limit: Int = 200,
) {
    fun validated(): MarketDataSyncRequest {
        Symbol(symbol)
        require(timeframes.isNotEmpty()) { "At least one timeframe is required." }
        timeframes.forEach { Timeframe.valueOf(it) }
        require(limit in 1..1000) { "Limit must be between 1 and 1000." }
        return this
    }
}

@Serializable
data class MarketDataHistorySyncRequest(
    val symbol: String,
    val timeframes: List<String> = listOf("M1", "M5", "M15"),
    val startAt: String? = null,
    val endAt: String? = null,
    val daysBack: Int = 365,
    val pageLimit: Int = 1000,
    val maxRequestsPerTimeframe: Int = 1000,
) {
    fun validated(): MarketDataHistorySyncRequest {
        Symbol(symbol)
        require(timeframes.isNotEmpty()) { "At least one timeframe is required." }
        timeframes.forEach { Timeframe.valueOf(it) }
        parsedStartAt()
        parsedEndAt()
        require(daysBack in 1..ResearchCandleLimits.MAX_HISTORY_DAYS_BACK) {
            "Days back must be between 1 and ${ResearchCandleLimits.MAX_HISTORY_DAYS_BACK}."
        }
        require(pageLimit in 1..1000) { "Page limit must be between 1 and 1000." }
        require(maxRequestsPerTimeframe in 1..ResearchCandleLimits.MAX_HISTORY_REQUESTS_PER_TIMEFRAME) {
            "Max requests per timeframe must be between 1 and ${ResearchCandleLimits.MAX_HISTORY_REQUESTS_PER_TIMEFRAME}."
        }
        return this
    }

    fun parsedStartAt(): Instant? = startAt?.parseInstant("startAt")

    fun parsedEndAt(): Instant? = endAt?.parseInstant("endAt")
}

@Serializable
data class MarketDataSyncResponse(
    val symbol: String,
    val totalFetchedCandles: Int,
    val syncedAt: String,
    val timeframes: List<TimeframeSyncResponse>,
)

@Serializable
data class TimeframeSyncResponse(
    val timeframe: String,
    val fetchedCandles: Int,
    val earliestOpenedAt: String?,
    val latestOpenedAt: String?,
)

@Serializable
data class MarketTickerResponse(
    val symbol: String,
    val lastPrice: String,
    val markPrice: String?,
    val indexPrice: String?,
    val price24hPcnt: String?,
    val fundingRate: String?,
    val nextFundingTime: String?,
    val capturedAt: String,
)

fun MarketTicker.toResponse(): MarketTickerResponse =
    MarketTickerResponse(
        symbol = symbol.value,
        lastPrice = lastPrice.toPlainString(),
        markPrice = markPrice?.toPlainString(),
        indexPrice = indexPrice?.toPlainString(),
        price24hPcnt = price24hPcnt?.toPlainString(),
        fundingRate = fundingRate?.toPlainString(),
        nextFundingTime = nextFundingTime?.toString(),
        capturedAt = capturedAt.toString(),
    )

private fun MarketDataSyncResult.toResponse(): MarketDataSyncResponse =
    MarketDataSyncResponse(
        symbol = symbol.value,
        totalFetchedCandles = totalFetchedCandles,
        syncedAt = syncedAt.toString(),
        timeframes =
            timeframeResults.map { result ->
                TimeframeSyncResponse(
                    timeframe = result.timeframe.name,
                    fetchedCandles = result.fetchedCandles,
                    earliestOpenedAt = result.earliestOpenedAt?.toString(),
                    latestOpenedAt = result.latestOpenedAt?.toString(),
                )
            },
    )

private fun String.parseInstant(fieldName: String): Instant =
    try {
        Instant.parse(this)
    } catch (exception: DateTimeParseException) {
        throw IllegalArgumentException("$fieldName must be an ISO-8601 instant.", exception)
    }
