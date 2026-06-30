package dev.yaklede.bybittrader.engine.market

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe

interface MarketCandleStore {
    suspend fun upsert(candles: List<Candle>)

    suspend fun recentCandles(
        symbol: Symbol,
        timeframe: Timeframe,
        limit: Int,
    ): List<Candle>
}
