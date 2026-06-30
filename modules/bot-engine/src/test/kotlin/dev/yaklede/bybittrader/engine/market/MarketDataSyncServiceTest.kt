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
    })

private class RecordingMarketDataFeed(
    private val historyCandles: List<Candle> = emptyList(),
) : MarketDataFeed {
    val requests = mutableListOf<MarketDataRequest>()
    val historyRequests = mutableListOf<HistoryMarketDataRequest>()

    override suspend fun fetchRecentCandles(
        symbol: Symbol,
        timeframe: Timeframe,
        limit: Int,
    ): List<Candle> {
        requests += MarketDataRequest(symbol, timeframe, limit)
        return listOf(sampleCandle(symbol, timeframe))
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

private class RecordingMarketCandleStore : MarketCandleStore {
    val saved = mutableListOf<List<Candle>>()

    override suspend fun upsert(candles: List<Candle>) {
        saved += candles
    }

    override suspend fun recentCandles(
        symbol: Symbol,
        timeframe: Timeframe,
        limit: Int,
    ): List<Candle> = emptyList()
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
