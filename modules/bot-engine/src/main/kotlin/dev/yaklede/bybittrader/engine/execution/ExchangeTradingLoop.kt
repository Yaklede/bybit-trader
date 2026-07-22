package dev.yaklede.bybittrader.engine.execution

import dev.yaklede.bybittrader.engine.market.MarketDataSyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

class ExchangeTradingLoop(
    private val marketDataSyncService: MarketDataSyncService,
    private val executionService: ExchangeExecutionService,
    private val config: ExchangeTradingLoopConfig,
    private val clock: Clock = Clock.systemUTC(),
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
        marketDataSyncService.syncClosedCandles(
            symbol = config.symbol,
            timeframes = listOf(config.timeframe),
            limit = minOf(config.syncLimit, config.candleLimit),
            maxRetries = 5,
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
                delay(millisUntilNextClosedCandle(Instant.now(clock), config.timeframe.durationMillis()))
            }
        }
}

private fun millisUntilNextClosedCandle(
    now: Instant,
    timeframeMillis: Long,
): Long {
    val nextBoundary = ((now.toEpochMilli() / timeframeMillis) + 1) * timeframeMillis
    return (nextBoundary - now.toEpochMilli()).coerceAtLeast(1_000L)
}

private fun dev.yaklede.bybittrader.domain.Timeframe.durationMillis(): Long =
    when (this) {
        dev.yaklede.bybittrader.domain.Timeframe.M1 -> 60_000L
        dev.yaklede.bybittrader.domain.Timeframe.M5 -> 300_000L
        dev.yaklede.bybittrader.domain.Timeframe.M15 -> 900_000L
        dev.yaklede.bybittrader.domain.Timeframe.H1 -> 3_600_000L
    }
