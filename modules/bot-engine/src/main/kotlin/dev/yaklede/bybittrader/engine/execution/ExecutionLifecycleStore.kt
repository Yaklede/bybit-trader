package dev.yaklede.bybittrader.engine.execution

import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import java.math.BigDecimal
import java.time.Instant

enum class ExecutionLifecycleState {
    ENTRY_SUBMITTED,
    PARTIALLY_FILLED,
    OPEN_UNPROTECTED,
    OPEN_PROTECTED,
    EXIT_SUBMITTED,
    CLOSED,
    ERROR,
}

data class ExecutionLifecycleEvent(
    val id: Long = 0,
    val mode: ExecutionRuntimeMode,
    val lifecycleId: String,
    val symbol: Symbol,
    val state: ExecutionLifecycleState,
    val side: Side,
    val requestedQuantity: BigDecimal,
    val filledQuantity: BigDecimal?,
    val fillVwap: BigDecimal?,
    val takeProfit: BigDecimal?,
    val stopLoss: BigDecimal?,
    val exchangeOrderId: String?,
    val clientOrderId: String?,
    val reasonCode: String,
    val occurredAt: Instant,
) {
    init {
        require(lifecycleId.isNotBlank()) { "Execution lifecycle id must not be blank." }
        require(requestedQuantity > BigDecimal.ZERO) { "Requested execution quantity must be positive." }
        require(filledQuantity == null || filledQuantity >= BigDecimal.ZERO) {
            "Filled execution quantity must not be negative."
        }
        require(fillVwap == null || fillVwap > BigDecimal.ZERO) { "Execution fill VWAP must be positive." }
        require(reasonCode.isNotBlank()) { "Execution lifecycle reason code must not be blank." }
    }
}

interface ExecutionLifecycleStore {
    suspend fun recordLifecycleEvent(event: ExecutionLifecycleEvent): Long?

    suspend fun latestLifecycleEvent(
        mode: ExecutionRuntimeMode,
        symbol: Symbol,
    ): ExecutionLifecycleEvent?

    suspend fun lifecycleEvents(
        mode: ExecutionRuntimeMode?,
        symbol: Symbol?,
        limit: Int,
    ): List<ExecutionLifecycleEvent>
}

fun ExecutionLifecycleState.canTransitionTo(next: ExecutionLifecycleState): Boolean =
    when (this) {
        ExecutionLifecycleState.ENTRY_SUBMITTED ->
            next in
                setOf(
                    ExecutionLifecycleState.ENTRY_SUBMITTED,
                    ExecutionLifecycleState.PARTIALLY_FILLED,
                    ExecutionLifecycleState.OPEN_UNPROTECTED,
                    ExecutionLifecycleState.OPEN_PROTECTED,
                    ExecutionLifecycleState.EXIT_SUBMITTED,
                    ExecutionLifecycleState.CLOSED,
                    ExecutionLifecycleState.ERROR,
                )

        ExecutionLifecycleState.PARTIALLY_FILLED ->
            next in
                setOf(
                    ExecutionLifecycleState.PARTIALLY_FILLED,
                    ExecutionLifecycleState.OPEN_UNPROTECTED,
                    ExecutionLifecycleState.OPEN_PROTECTED,
                    ExecutionLifecycleState.EXIT_SUBMITTED,
                    ExecutionLifecycleState.CLOSED,
                    ExecutionLifecycleState.ERROR,
                )

        ExecutionLifecycleState.OPEN_UNPROTECTED,
        ExecutionLifecycleState.OPEN_PROTECTED,
        ->
            next in
                setOf(
                    ExecutionLifecycleState.OPEN_UNPROTECTED,
                    ExecutionLifecycleState.OPEN_PROTECTED,
                    ExecutionLifecycleState.EXIT_SUBMITTED,
                    ExecutionLifecycleState.CLOSED,
                    ExecutionLifecycleState.ERROR,
                )

        ExecutionLifecycleState.EXIT_SUBMITTED ->
            next in
                setOf(
                    ExecutionLifecycleState.EXIT_SUBMITTED,
                    ExecutionLifecycleState.OPEN_UNPROTECTED,
                    ExecutionLifecycleState.OPEN_PROTECTED,
                    ExecutionLifecycleState.CLOSED,
                    ExecutionLifecycleState.ERROR,
                )

        ExecutionLifecycleState.ERROR ->
            next in
                setOf(
                    ExecutionLifecycleState.ERROR,
                    ExecutionLifecycleState.OPEN_UNPROTECTED,
                    ExecutionLifecycleState.OPEN_PROTECTED,
                    ExecutionLifecycleState.EXIT_SUBMITTED,
                    ExecutionLifecycleState.CLOSED,
                )

        ExecutionLifecycleState.CLOSED -> next == ExecutionLifecycleState.CLOSED
    }
