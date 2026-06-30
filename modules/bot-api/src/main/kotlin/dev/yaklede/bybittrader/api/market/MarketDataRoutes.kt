package dev.yaklede.bybittrader.api.market

import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.market.MarketDataSyncResult
import dev.yaklede.bybittrader.engine.market.MarketDataSyncService
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.format.DateTimeParseException

fun Route.configureMarketDataRoutes(marketDataSyncService: MarketDataSyncService) {
    authenticate("control") {
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
        require(daysBack in 1..1095) { "Days back must be between 1 and 1095." }
        require(pageLimit in 1..1000) { "Page limit must be between 1 and 1000." }
        require(maxRequestsPerTimeframe in 1..5000) {
            "Max requests per timeframe must be between 1 and 5000."
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
