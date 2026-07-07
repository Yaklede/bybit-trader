package dev.yaklede.bybittrader.engine.execution

import dev.yaklede.bybittrader.engine.market.MarketDataSyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

class ExchangeTradingLoop(
    private val marketDataSyncService: MarketDataSyncService,
    private val executionService: ExchangeExecutionService,
    private val config: ExchangeTradingLoopConfig,
    private val onResult: suspend (ExchangeEvaluationResult) -> Unit = {},
    private val onFailure: suspend (Throwable) -> Unit = {},
) {
    private val logger = LoggerFactory.getLogger(ExchangeTradingLoop::class.java)

    suspend fun runOnce(): ExchangeEvaluationResult {
        marketDataSyncService.ensureRecentHistory(
            symbol = config.symbol,
            timeframe = config.timeframe,
            requiredCandles = config.candleLimit,
        )
        marketDataSyncService.sync(
            symbol = config.symbol,
            timeframes = listOf(config.timeframe),
            limit = minOf(config.syncLimit, config.candleLimit),
        )
        val result =
            executionService.evaluateAndSubmit(
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
                    logger.warn("execution trading loop failed", error)
                    onFailure(error)
                }
                delay(config.intervalSeconds * 1000)
            }
        }
}
