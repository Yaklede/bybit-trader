package dev.yaklede.bybittrader.api.market

import dev.yaklede.bybittrader.domain.ResearchCandleLimits
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.market.ClosedCandleStatusResult
import dev.yaklede.bybittrader.engine.market.ClosedCandleSyncResult
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

        post("/market-data/closed-candle/sync") {
            val request = call.receive<ClosedCandleSyncRequest>().validated()
            val result =
                marketDataSyncService.syncClosedCandles(
                    symbol = Symbol(request.symbol),
                    timeframes = request.timeframes.map(Timeframe::valueOf),
                    limit = request.limit,
                    maxRetries = request.maxRetries,
                )
            call.respond(result.toResponse())
        }

        get("/market-data/closed-candle/status") {
            val request =
                MarketTickerRequest(
                    symbol = call.request.queryParameters["symbol"] ?: "BTCUSDT",
                ).validated()
            call.respond(marketDataSyncService.closedCandleStatus(Symbol(request.symbol)).toResponse())
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
data class ClosedCandleSyncRequest(
    val symbol: String,
    val timeframes: List<String>,
    val limit: Int = 300,
    val closedOnly: Boolean = true,
    val maxRetries: Int = 5,
) {
    fun validated(): ClosedCandleSyncRequest {
        val normalizedSymbol = symbol.trim().uppercase()
        Symbol(normalizedSymbol)
        require(timeframes.isNotEmpty()) { "At least one timeframe is required." }
        val normalizedTimeframes =
            timeframes.map { timeframe ->
                timeframe.trim().uppercase().also { Timeframe.valueOf(it) }
            }
        require(limit in 1..1000) { "Limit must be between 1 and 1000." }
        require(closedOnly) { "closedOnly must be true for closed-candle sync." }
        require(maxRetries in 0..5) { "Max retries must be between 0 and 5." }
        return copy(symbol = normalizedSymbol, timeframes = normalizedTimeframes)
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
data class ClosedCandleSyncResponse(
    val symbol: String,
    val syncedAt: String,
    val rateLimitTriggered: Boolean,
    val timeframes: List<ClosedTimeframeSyncResponse>,
)

@Serializable
data class ClosedTimeframeSyncResponse(
    val timeframe: String,
    val requestedLimit: Int,
    val storedCandles: Int,
    val droppedOpenCandles: Int,
    val retriesUsed: Int,
    val latestClosedOpenedAt: String?,
)

@Serializable
data class ClosedCandleStatusResponse(
    val symbol: String,
    val checkpoints: List<MarketSyncCheckpointResponse>,
)

@Serializable
data class MarketSyncCheckpointResponse(
    val timeframe: String,
    val latestClosedOpenedAt: String,
    val lastSyncAt: String,
    val lastSyncStatus: String,
    val consecutiveRateLimitCount: Int,
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

private fun ClosedCandleSyncResult.toResponse(): ClosedCandleSyncResponse =
    ClosedCandleSyncResponse(
        symbol = symbol.value,
        syncedAt = syncedAt.toString(),
        rateLimitTriggered = rateLimitTriggered,
        timeframes =
            timeframes.map { result ->
                ClosedTimeframeSyncResponse(
                    timeframe = result.timeframe.name,
                    requestedLimit = result.requestedLimit,
                    storedCandles = result.storedCandles,
                    droppedOpenCandles = result.droppedOpenCandles,
                    retriesUsed = result.retriesUsed,
                    latestClosedOpenedAt = result.latestClosedOpenedAt?.toString(),
                )
            },
    )

private fun ClosedCandleStatusResult.toResponse(): ClosedCandleStatusResponse =
    ClosedCandleStatusResponse(
        symbol = symbol.value,
        checkpoints =
            checkpoints.map { checkpoint ->
                MarketSyncCheckpointResponse(
                    timeframe = checkpoint.timeframe.name,
                    latestClosedOpenedAt = checkpoint.latestClosedOpenedAt.toString(),
                    lastSyncAt = checkpoint.lastSyncAt.toString(),
                    lastSyncStatus = checkpoint.lastSyncStatus.name,
                    consecutiveRateLimitCount = checkpoint.consecutiveRateLimitCount,
                )
            },
    )

private fun String.parseInstant(fieldName: String): Instant =
    try {
        Instant.parse(this)
    } catch (exception: DateTimeParseException) {
        throw IllegalArgumentException("$fieldName must be an ISO-8601 instant.", exception)
    }
