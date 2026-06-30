package dev.yaklede.bybittrader.engine.market

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import java.time.Instant

interface MarketDataFeed {
    suspend fun fetchRecentCandles(
        symbol: Symbol,
        timeframe: Timeframe,
        limit: Int,
    ): List<Candle>

    suspend fun fetchCandles(
        symbol: Symbol,
        timeframe: Timeframe,
        startAt: Instant,
        endAt: Instant,
        limit: Int,
    ): List<Candle> =
        fetchRecentCandles(symbol, timeframe, limit)
            .filter { candle -> !candle.openedAt.isBefore(startAt) && !candle.openedAt.isAfter(endAt) }
}
