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
                    latestOpenedAt = result.latestOpenedAt?.toString(),
                )
            },
    )
