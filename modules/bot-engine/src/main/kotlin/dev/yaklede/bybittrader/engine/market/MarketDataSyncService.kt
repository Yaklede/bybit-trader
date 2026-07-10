package dev.yaklede.bybittrader.engine.market

import dev.yaklede.bybittrader.domain.ResearchCandleLimits
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

class MarketDataSyncService(
    private val marketDataFeed: MarketDataFeed,
    private val candleStore: MarketCandleStore,
    private val checkpointStore: MarketSyncCheckpointStore? = candleStore as? MarketSyncCheckpointStore,
    private val clock: Clock = Clock.systemUTC(),
    private val retryDelay: suspend (Long) -> Unit = { millis -> delay(millis) },
) {
    private val logger = LoggerFactory.getLogger(MarketDataSyncService::class.java)

    suspend fun sync(
        symbol: Symbol,
        timeframes: List<Timeframe>,
        limit: Int,
    ): MarketDataSyncResult {
        require(timeframes.isNotEmpty()) { "At least one timeframe is required." }
        require(limit in 1..1000) { "Limit must be between 1 and 1000." }

        logger.info(
            "market-data sync requested symbol={} timeframes={} limit={}",
            symbol.value,
            timeframes.joinToString(",") { it.name },
            limit,
        )
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

        val result =
            MarketDataSyncResult(
                symbol = symbol,
                timeframeResults = timeframeResults,
                totalFetchedCandles = timeframeResults.sumOf { it.fetchedCandles },
                syncedAt = Instant.now(clock),
            )
        logger.info(
            "market-data sync completed symbol={} totalFetchedCandles={}",
            symbol.value,
            result.totalFetchedCandles,
        )
        return result
    }

    suspend fun syncClosedCandles(
        symbol: Symbol,
        timeframes: List<Timeframe>,
        limit: Int,
        maxRetries: Int,
    ): ClosedCandleSyncResult {
        require(timeframes.isNotEmpty()) { "At least one timeframe is required." }
        require(limit in 1..1000) { "Limit must be between 1 and 1000." }
        require(maxRetries in 0..5) { "Max retries must be between 0 and 5." }

        val syncedAt = Instant.now(clock)
        val results =
            timeframes.distinct().map { timeframe ->
                syncClosedTimeframe(
                    symbol = symbol,
                    timeframe = timeframe,
                    limit = limit,
                    maxRetries = maxRetries,
                    syncedAt = syncedAt,
                )
            }
        return ClosedCandleSyncResult(
            symbol = symbol,
            syncedAt = syncedAt,
            rateLimitTriggered = results.any { it.retriesUsed > 0 || it.lastSyncStatus == MarketSyncStatus.RATE_LIMITED },
            timeframes = results,
        )
    }

    suspend fun closedCandleStatus(symbol: Symbol): ClosedCandleStatusResult =
        ClosedCandleStatusResult(
            symbol = symbol,
            checkpoints = checkpointStore?.checkpoints(symbol).orEmpty(),
        )

    suspend fun ensureRecentHistory(
        symbol: Symbol,
        timeframe: Timeframe,
        requiredCandles: Int,
        pageLimit: Int = 1000,
        maxRequestsPerTimeframe: Int = ResearchCandleLimits.MAX_HISTORY_REQUESTS_PER_TIMEFRAME,
    ): MarketDataWarmupResult {
        require(requiredCandles in 1..ResearchCandleLimits.MAX_M5_REPLAY_CANDLES) {
            "Required candles must be between 1 and ${ResearchCandleLimits.MAX_M5_REPLAY_CANDLES}."
        }
        require(pageLimit in 1..1000) { "Page limit must be between 1 and 1000." }
        require(maxRequestsPerTimeframe in 1..ResearchCandleLimits.MAX_HISTORY_REQUESTS_PER_TIMEFRAME) {
            "Max requests per timeframe must be between 1 and ${ResearchCandleLimits.MAX_HISTORY_REQUESTS_PER_TIMEFRAME}."
        }

        val existingCandles = candleStore.recentCandles(symbol, timeframe, requiredCandles).size
        if (existingCandles >= requiredCandles) {
            logger.info(
                "market-data warmup skipped symbol={} timeframe={} existingCandles={} requiredCandles={}",
                symbol.value,
                timeframe.name,
                existingCandles,
                requiredCandles,
            )
            return MarketDataWarmupResult(
                symbol = symbol,
                timeframe = timeframe,
                requiredCandles = requiredCandles,
                existingCandles = existingCandles,
                fetchedCandles = 0,
                finalCandles = existingCandles,
                historySynced = false,
                syncedAt = Instant.now(clock),
            )
        }

        val daysBack = warmupDaysBack(requiredCandles, timeframe)
        logger.info(
            "market-data warmup requested symbol={} timeframe={} existingCandles={} requiredCandles={} daysBack={}",
            symbol.value,
            timeframe.name,
            existingCandles,
            requiredCandles,
            daysBack,
        )
        val syncResult =
            syncHistory(
                symbol = symbol,
                timeframes = listOf(timeframe),
                startAt = null,
                endAt = null,
                daysBack = daysBack,
                pageLimit = pageLimit,
                maxRequestsPerTimeframe = maxRequestsPerTimeframe,
            )
        val finalCandles = candleStore.recentCandles(symbol, timeframe, requiredCandles).size
        logger.info(
            "market-data warmup completed symbol={} timeframe={} fetchedCandles={} finalCandles={} requiredCandles={}",
            symbol.value,
            timeframe.name,
            syncResult.totalFetchedCandles,
            finalCandles,
            requiredCandles,
        )
        return MarketDataWarmupResult(
            symbol = symbol,
            timeframe = timeframe,
            requiredCandles = requiredCandles,
            existingCandles = existingCandles,
            fetchedCandles = syncResult.totalFetchedCandles,
            finalCandles = finalCandles,
            historySynced = true,
            syncedAt = syncResult.syncedAt,
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

        logger.info(
            "market-data history sync requested symbol={} timeframes={} startAt={} endAt={} pageLimit={}",
            symbol.value,
            timeframes.joinToString(",") { it.name },
            resolvedStartAt,
            resolvedEndAt,
            pageLimit,
        )
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

        val result =
            MarketDataSyncResult(
                symbol = symbol,
                timeframeResults = timeframeResults,
                totalFetchedCandles = timeframeResults.sumOf { it.fetchedCandles },
                syncedAt = Instant.now(clock),
            )
        logger.info(
            "market-data history sync completed symbol={} totalFetchedCandles={}",
            symbol.value,
            result.totalFetchedCandles,
        )
        return result
    }

    suspend fun ticker(symbol: Symbol): MarketTicker {
        logger.info("market ticker requested symbol={}", symbol.value)
        val ticker = marketDataFeed.fetchTicker(symbol)
        logger.info("market ticker completed symbol={} lastPrice={}", symbol.value, ticker.lastPrice.toPlainString())
        return ticker
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

    private suspend fun syncClosedTimeframe(
        symbol: Symbol,
        timeframe: Timeframe,
        limit: Int,
        maxRetries: Int,
        syncedAt: Instant,
    ): ClosedTimeframeSyncResult {
        var attempt = 0
        while (true) {
            try {
                val fetched = marketDataFeed.fetchRecentCandles(symbol, timeframe, limit)
                val closedBefore = floorToTimeframe(syncedAt, timeframe)
                val closed = fetched.filter { candle -> candle.openedAt.isBefore(closedBefore) }.sortedBy { it.openedAt }
                val dropped = fetched.size - closed.size
                if (closed.isNotEmpty()) {
                    candleStore.upsert(closed)
                    checkpointStore?.upsertCheckpoint(
                        MarketSyncCheckpoint(
                            symbol = symbol,
                            timeframe = timeframe,
                            latestClosedOpenedAt = closed.last().openedAt,
                            lastSyncAt = syncedAt,
                            lastSyncStatus = MarketSyncStatus.SUCCESS,
                            consecutiveRateLimitCount = 0,
                        ),
                    )
                }
                return ClosedTimeframeSyncResult(
                    timeframe = timeframe,
                    requestedLimit = limit,
                    storedCandles = closed.size,
                    droppedOpenCandles = dropped,
                    retriesUsed = attempt,
                    latestClosedOpenedAt = closed.lastOrNull()?.openedAt,
                    lastSyncStatus = MarketSyncStatus.SUCCESS,
                )
            } catch (error: MarketDataException) {
                if (!error.isRateLimitLike() || attempt >= maxRetries) {
                    val previousCheckpoint =
                        checkpointStore
                            ?.checkpoints(symbol)
                            ?.firstOrNull { it.timeframe == timeframe }
                    previousCheckpoint?.let { previous ->
                        val rateLimited = error.isRateLimitLike()
                        val status = if (rateLimited) MarketSyncStatus.RATE_LIMITED else MarketSyncStatus.FAILED
                        val nextRateLimitCount = previous.consecutiveRateLimitCount + if (rateLimited) 1 else 0
                        checkpointStore.upsertCheckpoint(
                            previous.copy(
                                lastSyncAt = syncedAt,
                                lastSyncStatus = status,
                                consecutiveRateLimitCount = nextRateLimitCount,
                            ),
                        )
                    }
                    if (error.isRateLimitLike()) {
                        return ClosedTimeframeSyncResult(
                            timeframe = timeframe,
                            requestedLimit = limit,
                            storedCandles = 0,
                            droppedOpenCandles = 0,
                            retriesUsed = attempt,
                            latestClosedOpenedAt = null,
                            lastSyncStatus = MarketSyncStatus.RATE_LIMITED,
                        )
                    }
                    throw error
                }
                attempt += 1
                retryDelay(backoffMillis(attempt))
            }
        }
    }
}

data class MarketDataSyncResult(
    val symbol: Symbol,
    val timeframeResults: List<TimeframeSyncResult>,
    val totalFetchedCandles: Int,
    val syncedAt: Instant,
)

data class MarketDataWarmupResult(
    val symbol: Symbol,
    val timeframe: Timeframe,
    val requiredCandles: Int,
    val existingCandles: Int,
    val fetchedCandles: Int,
    val finalCandles: Int,
    val historySynced: Boolean,
    val syncedAt: Instant,
)

data class TimeframeSyncResult(
    val timeframe: Timeframe,
    val fetchedCandles: Int,
    val earliestOpenedAt: Instant?,
    val latestOpenedAt: Instant?,
)

data class ClosedCandleSyncResult(
    val symbol: Symbol,
    val syncedAt: Instant,
    val rateLimitTriggered: Boolean,
    val timeframes: List<ClosedTimeframeSyncResult>,
)

data class ClosedTimeframeSyncResult(
    val timeframe: Timeframe,
    val requestedLimit: Int,
    val storedCandles: Int,
    val droppedOpenCandles: Int,
    val retriesUsed: Int,
    val latestClosedOpenedAt: Instant?,
    val lastSyncStatus: MarketSyncStatus,
)

data class ClosedCandleStatusResult(
    val symbol: Symbol,
    val checkpoints: List<MarketSyncCheckpoint>,
)

private fun Timeframe.durationMillis(): Long =
    when (this) {
        Timeframe.M1 -> 60_000L
        Timeframe.M5 -> 300_000L
        Timeframe.M15 -> 900_000L
        Timeframe.H1 -> 3_600_000L
    }

private fun floorToTimeframe(
    instant: Instant,
    timeframe: Timeframe,
): Instant {
    val millis = timeframe.durationMillis()
    val floored = (instant.toEpochMilli() / millis) * millis
    return Instant.ofEpochMilli(floored)
}

private fun backoffMillis(attempt: Int): Long = (250L shl (attempt - 1)).coerceAtMost(4_000L)

private fun MarketDataException.isRateLimitLike(): Boolean =
    message.orEmpty().contains("10006") ||
        message.orEmpty().contains("429", ignoreCase = true) ||
        message.orEmpty().contains("rate limit", ignoreCase = true)

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

private fun warmupDaysBack(
    requiredCandles: Int,
    timeframe: Timeframe,
): Int {
    val requiredMillis = requiredCandles.toLong() * timeframe.durationMillis()
    val dayMillis = Duration.ofDays(1).toMillis()
    val baseDays = ((requiredMillis - 1) / dayMillis) + 1
    val bufferedDays = ((baseDays * 110) + 99) / 100
    return bufferedDays
        .coerceAtLeast(1)
        .coerceAtMost(ResearchCandleLimits.MAX_HISTORY_DAYS_BACK.toLong())
        .toInt()
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
