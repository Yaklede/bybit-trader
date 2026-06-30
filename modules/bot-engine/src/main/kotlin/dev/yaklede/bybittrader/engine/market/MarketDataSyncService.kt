package dev.yaklede.bybittrader.engine.market

import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import java.time.Clock
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
    val latestOpenedAt: Instant?,
)
