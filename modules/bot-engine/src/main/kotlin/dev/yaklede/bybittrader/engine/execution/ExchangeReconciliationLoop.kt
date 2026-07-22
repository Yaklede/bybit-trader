package dev.yaklede.bybittrader.engine.execution

import dev.yaklede.bybittrader.domain.Symbol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

class ExchangeReconciliationLoop(
    private val executionService: ExchangeExecutionService,
    private val config: ExchangeReconciliationLoopConfig,
    private val clock: Clock = Clock.systemUTC(),
    private val onClosure: suspend (ExecutionTradeClosure) -> Boolean = { false },
    private val onLifecycleEvent: suspend (ExecutionLifecycleEvent) -> Unit = {},
    private val onFailure: suspend (Throwable) -> Unit = {},
) {
    private val logger = LoggerFactory.getLogger(ExchangeReconciliationLoop::class.java)

    suspend fun runOnce(): ExchangeReconciliationReport {
        val reconciliation =
            try {
                Result.success(executionService.persistExchangeState(config.symbol))
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                logger.warn("execution state reconciliation failed symbol={}", config.symbol.value, error)
                Result.failure(error)
            }
        val lifecycleFailure =
            try {
                reconciliation.getOrNull()?.lifecycleEvent?.let { event -> onLifecycleEvent(event) }
                null
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                logger.warn("execution lifecycle callback failed symbol={}", config.symbol.value, error)
                error
            }
        deliverPendingClosureAlerts()
        reconciliation.exceptionOrNull()?.let { error -> throw error }
        lifecycleFailure?.let { error -> throw error }
        return reconciliation.getOrThrow()
    }

    private suspend fun deliverPendingClosureAlerts() {
        val pendingAlerts =
            try {
                executionService.pendingClosureAlerts(config.symbol, config.alertBatchLimit)
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                logger.warn("execution pending closure alert query failed symbol={}", config.symbol.value, error)
                return
            }
        pendingAlerts.forEach { pending ->
            val attemptedAt = Instant.now(clock)
            val delivered =
                try {
                    onClosure(pending.closure)
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    logger.warn(
                        "execution closure alert callback failed closureId={} attempt={}",
                        pending.closure.id,
                        pending.attemptCount + 1,
                        error,
                    )
                    false
                }
            try {
                executionService.recordClosureAlertAttempt(
                    closureId = pending.closure.id,
                    attemptedAt = attemptedAt,
                    delivered = delivered,
                )
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                logger.warn(
                    "execution closure alert attempt persistence failed closureId={} delivered={}",
                    pending.closure.id,
                    delivered,
                    error,
                )
            }
            if (!delivered) {
                logger.warn(
                    "execution closure alert remains pending closureId={} attempt={}",
                    pending.closure.id,
                    pending.attemptCount + 1,
                )
            }
        }
    }

    fun start(scope: CoroutineScope): Job =
        scope.launch {
            while (isActive) {
                try {
                    runOnce()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    logger.warn("execution reconciliation loop failed", error)
                    onFailure(error)
                }
                delay(config.intervalSeconds * 1_000L)
            }
        }
}

data class ExchangeReconciliationLoopConfig(
    val symbol: Symbol,
    val alertBatchLimit: Int = 100,
    val intervalSeconds: Long = 60,
) {
    init {
        require(alertBatchLimit in 1..1000) { "Execution alert batch limit must be between 1 and 1000." }
        require(intervalSeconds in 10..86_400) {
            "Execution reconciliation interval seconds must be between 10 and 86400."
        }
    }
}
