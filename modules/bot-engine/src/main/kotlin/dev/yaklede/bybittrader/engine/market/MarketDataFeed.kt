package dev.yaklede.bybittrader.engine.market

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import java.math.BigDecimal
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

    suspend fun fetchTicker(symbol: Symbol): MarketTicker = throw MarketDataException("Ticker provider is not available.")
}

data class MarketTicker(
    val symbol: Symbol,
    val lastPrice: BigDecimal,
    val markPrice: BigDecimal?,
    val indexPrice: BigDecimal?,
    val price24hPcnt: BigDecimal?,
    val fundingRate: BigDecimal?,
    val nextFundingTime: Instant?,
    val capturedAt: Instant,
)
