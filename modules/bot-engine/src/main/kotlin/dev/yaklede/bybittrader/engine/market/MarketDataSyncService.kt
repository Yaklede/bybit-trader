package dev.yaklede.bybittrader.engine.market

import dev.yaklede.bybittrader.domain.ResearchCandleLimits
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import java.time.Clock
import java.time.Duration
import java.time.Instant

class MarketDataSyncService(
    private val marketDataFeed: MarketDataFeed,
    private val candleStore: MarketCandleStore,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun sync(
        symbol: Symbol,
        timeframes: List<Timeframe>,
        limit: Int,
    ): MarketDataSyncResult {
        require(timeframes.isNotEmpty()) { "At least one timeframe is required." }
        require(limit in 1..1000) { "Limit must be between 1 and 1000." }

        val timeframeResults =
            timeframes.distinct().map { timeframe ->
                val candles = marketDataFeed.fetchRecentCandles(symbol, timeframe, limit)
                candleStore.upsert(candles)
                TimeframeSyncResult(
                    timeframe = timeframe,
                    fetchedCandles = candles.size,
                    earliestOpenedAt = candles.minOfOrNull { it.openedAt },
                    latestOpenedAt = candles.maxOfOrNull { it.openedAt },
                )
            }

        return MarketDataSyncResult(
            symbol = symbol,
            timeframeResults = timeframeResults,
            totalFetchedCandles = timeframeResults.sumOf { it.fetchedCandles },
            syncedAt = Instant.now(clock),
        )
    }

    suspend fun syncHistory(
        symbol: Symbol,
        timeframes: List<Timeframe>,
        startAt: Instant?,
        endAt: Instant?,
        daysBack: Int,
        pageLimit: Int,
        maxRequestsPerTimeframe: Int,
    ): MarketDataSyncResult {
        require(timeframes.isNotEmpty()) { "At least one timeframe is required." }
        require(daysBack in 1..ResearchCandleLimits.MAX_HISTORY_DAYS_BACK) {
            "Days back must be between 1 and ${ResearchCandleLimits.MAX_HISTORY_DAYS_BACK}."
        }
        require(pageLimit in 1..1000) { "Page limit must be between 1 and 1000." }
        require(maxRequestsPerTimeframe in 1..ResearchCandleLimits.MAX_HISTORY_REQUESTS_PER_TIMEFRAME) {
            "Max requests per timeframe must be between 1 and ${ResearchCandleLimits.MAX_HISTORY_REQUESTS_PER_TIMEFRAME}."
        }

        val resolvedEndAt = endAt ?: Instant.now(clock)
        val resolvedStartAt = startAt ?: resolvedEndAt.minus(Duration.ofDays(daysBack.toLong()))
        require(resolvedStartAt.isBefore(resolvedEndAt)) { "Start time must be before end time." }

        val timeframeResults =
            timeframes.distinct().map { timeframe ->
                val requiredRequests = requiredRequests(resolvedStartAt, resolvedEndAt, timeframe, pageLimit)
                require(requiredRequests <= maxRequestsPerTimeframe) {
                    "History sync for ${timeframe.name} requires $requiredRequests requests; increase maxRequestsPerTimeframe."
                }
                syncHistoryTimeframe(
                    symbol = symbol,
                    timeframe = timeframe,
                    startAt = resolvedStartAt,
                    endAt = resolvedEndAt,
                    pageLimit = pageLimit,
                    requiredRequests = requiredRequests,
                )
            }

        return MarketDataSyncResult(
            symbol = symbol,
            timeframeResults = timeframeResults,
            totalFetchedCandles = timeframeResults.sumOf { it.fetchedCandles },
            syncedAt = Instant.now(clock),
        )
    }

    private suspend fun syncHistoryTimeframe(
        symbol: Symbol,
        timeframe: Timeframe,
        startAt: Instant,
        endAt: Instant,
        pageLimit: Int,
        requiredRequests: Int,
    ): TimeframeSyncResult {
        var cursorEndExclusive = endAt
        var fetchedCandles = 0
        var earliestOpenedAt: Instant? = null
        var latestOpenedAt: Instant? = null
        var remainingRequests = requiredRequests

        while (remainingRequests > 0 && cursorEndExclusive.isAfter(startAt)) {
            remainingRequests -= 1
            val windowStart = maxInstant(startAt, cursorEndExclusive.minusMillis(timeframe.durationMillis() * pageLimit))
            val windowEndInclusive = cursorEndExclusive.minusMillis(1)
            if (windowEndInclusive.isBefore(windowStart)) break

            val candles =
                marketDataFeed
                    .fetchCandles(
                        symbol = symbol,
                        timeframe = timeframe,
                        startAt = windowStart,
                        endAt = windowEndInclusive,
                        limit = pageLimit,
                    ).filter { candle ->
                        !candle.openedAt.isBefore(startAt) && candle.openedAt.isBefore(endAt)
                    }.sortedBy { it.openedAt }

            if (candles.isEmpty()) {
                cursorEndExclusive = windowStart
            } else {
                candleStore.upsert(candles)
                fetchedCandles += candles.size
                earliestOpenedAt = minInstant(earliestOpenedAt, candles.first().openedAt)
                latestOpenedAt = maxNullableInstant(latestOpenedAt, candles.last().openedAt)
                cursorEndExclusive = candles.first().openedAt
            }
        }

        return TimeframeSyncResult(
            timeframe = timeframe,
            fetchedCandles = fetchedCandles,
            earliestOpenedAt = earliestOpenedAt,
            latestOpenedAt = latestOpenedAt,
        )
    }
}

data class MarketDataSyncResult(
    val symbol: Symbol,
    val timeframeResults: List<TimeframeSyncResult>,
    val totalFetchedCandles: Int,
    val syncedAt: Instant,
)

data class TimeframeSyncResult(
    val timeframe: Timeframe,
    val fetchedCandles: Int,
    val earliestOpenedAt: Instant?,
    val latestOpenedAt: Instant?,
)

private fun Timeframe.durationMillis(): Long =
    when (this) {
        Timeframe.M1 -> 60_000L
        Timeframe.M5 -> 300_000L
        Timeframe.M15 -> 900_000L
        Timeframe.H1 -> 3_600_000L
    }

private fun requiredRequests(
    startAt: Instant,
    endAt: Instant,
    timeframe: Timeframe,
    pageLimit: Int,
): Int {
    val durationMillis = Duration.between(startAt, endAt).toMillis()
    val candleCount = ((durationMillis - 1) / timeframe.durationMillis()) + 1
    return (((candleCount - 1) / pageLimit) + 1).toInt()
}

private fun minInstant(
    current: Instant?,
    candidate: Instant,
): Instant = if (current == null || candidate.isBefore(current)) candidate else current

private fun maxInstant(
    first: Instant,
    second: Instant,
): Instant = if (first.isAfter(second)) first else second

private fun maxNullableInstant(
    current: Instant?,
    candidate: Instant,
): Instant = if (current == null || candidate.isAfter(current)) candidate else current
