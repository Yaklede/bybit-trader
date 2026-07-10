package dev.yaklede.bybittrader.engine.market

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class MarketDataSyncServiceTest :
    StringSpec({
        "sync fetches each timeframe and stores candles" {
            val feed = RecordingMarketDataFeed()
            val store = RecordingMarketCandleStore()
            val service =
                MarketDataSyncService(
                    marketDataFeed = feed,
                    candleStore = store,
                    clock = Clock.fixed(Instant.parse("2026-06-30T00:00:00Z"), ZoneOffset.UTC),
                )

            val result =
                service.sync(
                    symbol = Symbol("BTCUSDT"),
                    timeframes = listOf(Timeframe.M1, Timeframe.M5, Timeframe.M15),
                    limit = 2,
                )

            feed.requests.shouldContainExactly(
                listOf(
                    MarketDataRequest(Symbol("BTCUSDT"), Timeframe.M1, 2),
                    MarketDataRequest(Symbol("BTCUSDT"), Timeframe.M5, 2),
                    MarketDataRequest(Symbol("BTCUSDT"), Timeframe.M15, 2),
                ),
            )
            store.saved.size shouldBe 3
            result.totalFetchedCandles shouldBe 3
            result.syncedAt shouldBe Instant.parse("2026-06-30T00:00:00Z")
        }

        "syncHistory pages backward through the requested time range" {
            val symbol = Symbol("BTCUSDT")
            val feed =
                RecordingMarketDataFeed(
                    historyCandles =
                        (0 until 5).map { index ->
                            sampleCandle(
                                symbol = symbol,
                                timeframe = Timeframe.M1,
                                openedAt = Instant.parse("2026-06-30T00:00:00Z").plusSeconds(index * 60L),
                            )
                        },
                )
            val store = RecordingMarketCandleStore()
            val service =
                MarketDataSyncService(
                    marketDataFeed = feed,
                    candleStore = store,
                    clock = Clock.fixed(Instant.parse("2026-06-30T00:05:00Z"), ZoneOffset.UTC),
                )

            val result =
                service.syncHistory(
                    symbol = symbol,
                    timeframes = listOf(Timeframe.M1),
                    startAt = Instant.parse("2026-06-30T00:00:00Z"),
                    endAt = Instant.parse("2026-06-30T00:05:00Z"),
                    daysBack = 1,
                    pageLimit = 2,
                    maxRequestsPerTimeframe = 10,
                )

            feed.historyRequests.map { it.startAt }.shouldContainExactly(
                listOf(
                    Instant.parse("2026-06-30T00:03:00Z"),
                    Instant.parse("2026-06-30T00:01:00Z"),
                    Instant.parse("2026-06-30T00:00:00Z"),
                ),
            )
            store.saved.map { candles -> candles.size }.shouldContainExactly(listOf(2, 2, 1))
            result.totalFetchedCandles shouldBe 5
            result.timeframeResults.single().earliestOpenedAt shouldBe Instant.parse("2026-06-30T00:00:00Z")
            result.timeframeResults.single().latestOpenedAt shouldBe Instant.parse("2026-06-30T00:04:00Z")
        }

        "ensureRecentHistory backfills when stored candles are below the required count" {
            val symbol = Symbol("BTCUSDT")
            val feed =
                RecordingMarketDataFeed(
                    historyCandles =
                        (0 until 5).map { index ->
                            sampleCandle(
                                symbol = symbol,
                                timeframe = Timeframe.M5,
                                openedAt = Instant.parse("2026-06-30T00:00:00Z").plusSeconds(index * 300L),
                            )
                        },
                )
            val store = RecordingMarketCandleStore()
            val service =
                MarketDataSyncService(
                    marketDataFeed = feed,
                    candleStore = store,
                    clock = Clock.fixed(Instant.parse("2026-06-30T00:25:00Z"), ZoneOffset.UTC),
                )

            val result =
                service.ensureRecentHistory(
                    symbol = symbol,
                    timeframe = Timeframe.M5,
                    requiredCandles = 5,
                    pageLimit = 1000,
                    maxRequestsPerTimeframe = 10,
                )

            result.historySynced shouldBe true
            result.existingCandles shouldBe 0
            result.finalCandles shouldBe 5
            feed.historyRequests.size shouldBe 1
        }

        "ensureRecentHistory skips backfill when enough candles are already stored" {
            val symbol = Symbol("BTCUSDT")
            val feed = RecordingMarketDataFeed()
            val store =
                RecordingMarketCandleStore(
                    initialCandles =
                        (0 until 5).map { index ->
                            sampleCandle(
                                symbol = symbol,
                                timeframe = Timeframe.M5,
                                openedAt = Instant.parse("2026-06-30T00:00:00Z").plusSeconds(index * 300L),
                            )
                        },
                )
            val service =
                MarketDataSyncService(
                    marketDataFeed = feed,
                    candleStore = store,
                    clock = Clock.fixed(Instant.parse("2026-06-30T00:25:00Z"), ZoneOffset.UTC),
                )

            val result =
                service.ensureRecentHistory(
                    symbol = symbol,
                    timeframe = Timeframe.M5,
                    requiredCandles = 5,
                )

            result.historySynced shouldBe false
            result.existingCandles shouldBe 5
            result.finalCandles shouldBe 5
            feed.historyRequests shouldBe emptyList()
        }

        "closed candle sync drops the still-open candle and records checkpoint" {
            val symbol = Symbol("BTCUSDT")
            val store = RecordingMarketCandleStore()
            val feed =
                RecordingMarketDataFeed(
                    recentCandles =
                        listOf(
                            sampleCandle(symbol, Timeframe.M5, Instant.parse("2026-06-30T00:00:00Z")),
                            sampleCandle(symbol, Timeframe.M5, Instant.parse("2026-06-30T00:05:00Z")),
                        ),
                )
            val service =
                MarketDataSyncService(
                    marketDataFeed = feed,
                    candleStore = store,
                    clock = Clock.fixed(Instant.parse("2026-06-30T00:06:00Z"), ZoneOffset.UTC),
                    retryDelay = {},
                )

            val result = service.syncClosedCandles(symbol, listOf(Timeframe.M5), limit = 2, maxRetries = 0)

            result.timeframes.single().storedCandles shouldBe 1
            result.timeframes.single().droppedOpenCandles shouldBe 1
            val savedCandle = store.saved.single().single()
            savedCandle.openedAt shouldBe Instant.parse("2026-06-30T00:00:00Z")
            store.checkpoints(symbol).single().latestClosedOpenedAt shouldBe Instant.parse("2026-06-30T00:00:00Z")
        }

        "closed candle sync retries rate-limit failures with a bounded retry count" {
            val feed = RateLimitThenSuccessFeed(failuresBeforeSuccess = 2)
            val store = RecordingMarketCandleStore()
            val service =
                MarketDataSyncService(
                    marketDataFeed = feed,
                    candleStore = store,
                    clock = Clock.fixed(Instant.parse("2026-06-30T00:06:00Z"), ZoneOffset.UTC),
                    retryDelay = {},
                )

            val result = service.syncClosedCandles(Symbol("BTCUSDT"), listOf(Timeframe.M5), limit = 1, maxRetries = 2)

            result.timeframes.single().retriesUsed shouldBe 2
            result.rateLimitTriggered shouldBe true
            feed.attempts shouldBe 3
        }
    })

private class RecordingMarketDataFeed(
    private val historyCandles: List<Candle> = emptyList(),
    private val recentCandles: List<Candle>? = null,
) : MarketDataFeed {
    val requests = mutableListOf<MarketDataRequest>()
    val historyRequests = mutableListOf<HistoryMarketDataRequest>()

    override suspend fun fetchRecentCandles(
        symbol: Symbol,
        timeframe: Timeframe,
        limit: Int,
    ): List<Candle> {
        requests += MarketDataRequest(symbol, timeframe, limit)
        return recentCandles ?: listOf(sampleCandle(symbol, timeframe))
    }

    override suspend fun fetchCandles(
        symbol: Symbol,
        timeframe: Timeframe,
        startAt: Instant,
        endAt: Instant,
        limit: Int,
    ): List<Candle> {
        historyRequests += HistoryMarketDataRequest(symbol, timeframe, startAt, endAt, limit)
        return historyCandles
            .filter { candle ->
                candle.symbol == symbol &&
                    candle.timeframe == timeframe &&
                    !candle.openedAt.isBefore(startAt) &&
                    !candle.openedAt.isAfter(endAt)
            }.take(limit)
    }
}

private class RecordingMarketCandleStore(
    initialCandles: List<Candle> = emptyList(),
) : MarketCandleStore,
    MarketSyncCheckpointStore {
    val saved = mutableListOf<List<Candle>>()
    private val storedCandles = initialCandles.toMutableList()
    private val checkpoints = mutableMapOf<Pair<Symbol, Timeframe>, MarketSyncCheckpoint>()

    override suspend fun upsert(candles: List<Candle>) {
        saved += candles
        storedCandles += candles
    }

    override suspend fun recentCandles(
        symbol: Symbol,
        timeframe: Timeframe,
        limit: Int,
    ): List<Candle> =
        storedCandles
            .filter { candle -> candle.symbol == symbol && candle.timeframe == timeframe }
            .sortedByDescending(Candle::openedAt)
            .take(limit)

    override suspend fun upsertCheckpoint(checkpoint: MarketSyncCheckpoint) {
        checkpoints[checkpoint.symbol to checkpoint.timeframe] = checkpoint
    }

    override suspend fun checkpoints(symbol: Symbol): List<MarketSyncCheckpoint> =
        checkpoints.values.filter { it.symbol == symbol }.sortedBy { it.timeframe.name }
}

private class RateLimitThenSuccessFeed(
    private val failuresBeforeSuccess: Int,
) : MarketDataFeed {
    var attempts = 0

    override suspend fun fetchRecentCandles(
        symbol: Symbol,
        timeframe: Timeframe,
        limit: Int,
    ): List<Candle> {
        attempts += 1
        if (attempts <= failuresBeforeSuccess) throw MarketDataException("Bybit kline request failed with code 10006 rate limit")
        return listOf(sampleCandle(symbol, timeframe, Instant.parse("2026-06-30T00:00:00Z")))
    }
}

private data class MarketDataRequest(
    val symbol: Symbol,
    val timeframe: Timeframe,
    val limit: Int,
)

private data class HistoryMarketDataRequest(
    val symbol: Symbol,
    val timeframe: Timeframe,
    val startAt: Instant,
    val endAt: Instant,
    val limit: Int,
)

private fun sampleCandle(
    symbol: Symbol,
    timeframe: Timeframe,
    openedAt: Instant = Instant.parse("2026-06-30T00:00:00Z"),
): Candle =
    Candle(
        symbol = symbol,
        timeframe = timeframe,
        openedAt = openedAt,
        open = BigDecimal("100"),
        high = BigDecimal("110"),
        low = BigDecimal("90"),
        close = BigDecimal("105"),
        volume = BigDecimal("12.5"),
    )
