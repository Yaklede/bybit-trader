package dev.yaklede.bybittrader.engine.paper

import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.position.CausalPositionState
import java.time.Instant
import java.time.LocalDate

data class PaperRuntimeState(
    val strategy: String,
    val symbol: Symbol,
    val timeframe: Timeframe,
    val phase: PaperRuntimePhase,
    val lastProcessedCandleAt: Instant?,
    val equity: Double,
    val peakEquity: Double,
    val maxDrawdownPct: Double,
    val grossProfit: Double,
    val grossLoss: Double,
    val sumReturnR: Double,
    val closedTrades: Int,
    val entryCountDate: LocalDate?,
    val entryCount: Int,
    val pendingEntry: PaperPendingEntry?,
    val openPosition: PaperOpenPosition?,
    val updatedAt: Instant,
) {
    init {
        require(strategy.isNotBlank()) { "Paper runtime strategy must not be blank." }
        require(equity > 0.0) { "Paper runtime equity must be positive." }
        require(peakEquity >= equity) { "Paper runtime peak equity must not be below current equity." }
        require(maxDrawdownPct >= 0.0) { "Paper runtime drawdown must not be negative." }
        require(grossProfit >= 0.0) { "Paper runtime gross profit must not be negative." }
        require(grossLoss >= 0.0) { "Paper runtime gross loss must not be negative." }
        require(closedTrades >= 0) { "Paper runtime closed trade count must not be negative." }
        require(entryCount >= 0) { "Paper runtime daily entry count must not be negative." }
        require((entryCountDate == null) == (entryCount == 0)) {
            "Paper runtime daily entry count requires a date."
        }
        when (phase) {
            PaperRuntimePhase.FLAT -> require(pendingEntry == null && openPosition == null)
            PaperRuntimePhase.ENTRY_PENDING -> require(pendingEntry != null && openPosition == null)
            PaperRuntimePhase.OPEN -> require(pendingEntry == null && openPosition != null)
        }
    }

    companion object {
        fun initial(
            strategy: String,
            symbol: Symbol,
            timeframe: Timeframe,
            initialEquity: Double,
            updatedAt: Instant,
        ): PaperRuntimeState =
            PaperRuntimeState(
                strategy = strategy,
                symbol = symbol,
                timeframe = timeframe,
                phase = PaperRuntimePhase.FLAT,
                lastProcessedCandleAt = null,
                equity = initialEquity,
                peakEquity = initialEquity,
                maxDrawdownPct = 0.0,
                grossProfit = 0.0,
                grossLoss = 0.0,
                sumReturnR = 0.0,
                closedTrades = 0,
                entryCountDate = null,
                entryCount = 0,
                pendingEntry = null,
                openPosition = null,
                updatedAt = updatedAt,
            )
    }
}

enum class PaperRuntimePhase {
    FLAT,
    ENTRY_PENDING,
    OPEN,
}

data class PaperPendingEntry(
    val signalId: Long,
    val signalAt: Instant,
    val side: Side,
    val structuralStopPrice: Double,
    val entryAnchoredStopDistance: Double?,
    val expectedR: Double,
) {
    init {
        require(signalId > 0) { "Pending paper signal ID must be positive." }
        require(structuralStopPrice > 0.0) { "Pending paper structural stop must be positive." }
        require(entryAnchoredStopDistance == null || entryAnchoredStopDistance > 0.0) {
            "Pending paper anchored stop distance must be positive when configured."
        }
        require(expectedR > 0.0) { "Pending paper expected R must be positive." }
    }
}

data class PaperOpenPosition(
    val signalId: Long,
    val signalAt: Instant,
    val entryOrderId: Long,
    val entryFee: Double,
    val riskAmount: Double,
    val policyState: CausalPositionState,
) {
    init {
        require(signalId > 0) { "Open paper signal ID must be positive." }
        require(entryOrderId > 0) { "Open paper entry order ID must be positive." }
        require(entryFee >= 0.0) { "Open paper entry fee must not be negative." }
        require(riskAmount > 0.0) { "Open paper risk amount must be positive." }
    }
}

interface PaperRuntimeStateStore {
    suspend fun paperRuntimeState(
        strategy: String,
        symbol: Symbol,
        timeframe: Timeframe,
    ): PaperRuntimeState?

    suspend fun upsertPaperRuntimeState(state: PaperRuntimeState)
}
