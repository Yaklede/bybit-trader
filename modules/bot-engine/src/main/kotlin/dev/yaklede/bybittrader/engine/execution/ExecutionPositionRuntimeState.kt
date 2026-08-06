package dev.yaklede.bybittrader.engine.execution

import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.position.CausalPositionState
import java.time.Instant

data class ExecutionPositionRuntimeState(
    val mode: ExecutionRuntimeMode,
    val lifecycleId: String,
    val symbol: Symbol,
    val timeframe: Timeframe,
    val lastProcessedCandleAt: Instant?,
    val policyState: CausalPositionState,
    val updatedAt: Instant,
) {
    init {
        require(lifecycleId.isNotBlank()) { "Execution position lifecycle id must not be blank." }
        require(policyState.initialQuantity > 0.0) { "Execution position initial quantity must be positive." }
        require(policyState.remainingQuantity > 0.0) { "Execution position remaining quantity must be positive." }
        require(policyState.remainingQuantity <= policyState.initialQuantity) {
            "Execution position remaining quantity must not exceed its initial quantity."
        }
        require(lastProcessedCandleAt == null || !lastProcessedCandleAt.isBefore(policyState.entryAt)) {
            "Execution position checkpoint must not precede the entry."
        }
    }
}

interface ExecutionPositionRuntimeStateStore {
    suspend fun executionPositionRuntimeState(
        mode: ExecutionRuntimeMode,
        symbol: Symbol,
    ): ExecutionPositionRuntimeState?

    suspend fun upsertExecutionPositionRuntimeState(state: ExecutionPositionRuntimeState)

    suspend fun deleteExecutionPositionRuntimeState(
        mode: ExecutionRuntimeMode,
        symbol: Symbol,
    )
}
