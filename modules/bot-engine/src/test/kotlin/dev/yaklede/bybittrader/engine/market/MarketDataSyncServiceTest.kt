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
                    timeframes = listOf(Timeframe.M15, Timeframe.H1),
                    limit = 2,
                )

            feed.requests.shouldContainExactly(
                listOf(
                    MarketDataRequest(Symbol("BTCUSDT"), Timeframe.M15, 2),
                    MarketDataRequest(Symbol("BTCUSDT"), Timeframe.H1, 2),
                ),
            )
            store.saved.size shouldBe 2
            result.totalFetchedCandles shouldBe 2
            result.syncedAt shouldBe Instant.parse("2026-06-30T00:00:00Z")
        }
    })

private class RecordingMarketDataFeed : MarketDataFeed {
    val requests = mutableListOf<MarketDataRequest>()

    override suspend fun fetchRecentCandles(
        symbol: Symbol,
        timeframe: Timeframe,
        limit: Int,
    ): List<Candle> {
        requests += MarketDataRequest(symbol, timeframe, limit)
        return listOf(sampleCandle(symbol, timeframe))
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

private fun sampleCandle(
    symbol: Symbol,
    timeframe: Timeframe,
): Candle =
    Candle(
        symbol = symbol,
        timeframe = timeframe,
        openedAt = Instant.parse("2026-06-30T00:00:00Z"),
        open = BigDecimal("100"),
        high = BigDecimal("110"),
        low = BigDecimal("90"),
        close = BigDecimal("105"),
        volume = BigDecimal("12.5"),
    )
