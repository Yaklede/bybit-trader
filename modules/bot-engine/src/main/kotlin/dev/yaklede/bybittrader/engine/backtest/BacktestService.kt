package dev.yaklede.bybittrader.engine.backtest

import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.market.MarketCandleStore

class BacktestService(
    private val candleStore: MarketCandleStore,
    private val runner: BacktestRunner,
) {
    suspend fun run(
        symbol: Symbol,
        timeframe: Timeframe,
        candleLimit: Int,
        config: BacktestConfig,
    ): BacktestResult {
        require(candleLimit in 30..1000) { "Candle limit must be between 30 and 1000." }
        val candles =
            candleStore
                .recentCandles(symbol, timeframe, candleLimit)
                .sortedBy { it.openedAt }
        return runner.run(candles, config)
    }
}
