package dev.yaklede.bybittrader.engine.market

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import java.time.Instant

interface MarketCandleStore {
    suspend fun upsert(candles: List<Candle>)

    suspend fun recentCandles(
        symbol: Symbol,
        timeframe: Timeframe,
        limit: Int,
    ): List<Candle>

    suspend fun candlesBetween(
        symbol: Symbol,
        timeframe: Timeframe,
        startAt: Instant,
        endAt: Instant,
        limit: Int,
    ): List<Candle> =
        recentCandles(symbol, timeframe, limit)
            .filter { candle -> !candle.openedAt.isBefore(startAt) && !candle.openedAt.isAfter(endAt) }
}
