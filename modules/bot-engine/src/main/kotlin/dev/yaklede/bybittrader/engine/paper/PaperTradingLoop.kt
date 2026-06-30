package dev.yaklede.bybittrader.engine.paper

import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.market.MarketDataSyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException

class PaperTradingLoop(
    private val marketDataSyncService: MarketDataSyncService,
    private val paperTradingService: PaperTradingService,
    private val config: PaperTradingLoopConfig,
    private val onResult: suspend (PaperEvaluationResult) -> Unit = {},
    private val onFailure: suspend (Throwable) -> Unit = {},
) {
    suspend fun runOnce(): PaperEvaluationResult {
        marketDataSyncService.sync(
            symbol = config.symbol,
            timeframes = listOf(config.timeframe),
            limit = config.candleLimit,
        )
        val result =
            paperTradingService.evaluateOnce(
                symbol = config.symbol,
                timeframe = config.timeframe,
                candleLimit = config.candleLimit,
            )
        onResult(result)
        return result
    }

    fun start(scope: CoroutineScope): Job =
        scope.launch {
            while (isActive) {
                try {
                    runOnce()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    onFailure(error)
                }
                delay(config.interval.toMillis())
            }
        }
}

data class PaperTradingLoopConfig(
    val symbol: Symbol,
    val timeframe: Timeframe,
    val candleLimit: Int = 200,
    val interval: Duration = Duration.ofMinutes(15),
) {
    init {
        require(candleLimit in 20..1000) { "Paper loop candle limit must be between 20 and 1000." }
        require(!interval.isNegative && !interval.isZero) { "Paper loop interval must be positive." }
    }
}
