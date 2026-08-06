package dev.yaklede.bybittrader.engine.execution

import java.math.BigDecimal
import java.math.MathContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

data class ExecutionRiskState(
    val mode: ExecutionRuntimeMode,
    val peakEquity: BigDecimal,
    val utcDayStartedAt: Instant,
    val dayStartEquity: BigDecimal,
    val latestEquity: BigDecimal,
    val consecutiveLosses: Int,
    val lastClosureId: Long?,
    val updatedAt: Instant,
)

data class ExecutionRiskDecision(
    val reasonCodes: List<String>,
) {
    val allowsEntry: Boolean = reasonCodes.isEmpty()
}

internal object ExecutionRiskCircuitBreaker {
    fun update(
        previous: ExecutionRiskState?,
        snapshot: ExecutionAccountSnapshot,
        newClosures: List<ExecutionTradeClosure>,
    ): ExecutionRiskState? {
        val equity = snapshot.totalEquity ?: snapshot.totalWalletBalance ?: return null
        if (equity <= BigDecimal.ZERO) return null

        val dayStartedAt = snapshot.capturedAt.utcDayStartedAt()
        val sameUtcDay = previous?.utcDayStartedAt == dayStartedAt
        var consecutiveLosses = previous?.consecutiveLosses ?: 0
        var lastClosureId = previous?.lastClosureId
        newClosures
            .asSequence()
            .filter { closure -> closure.mode == snapshot.mode }
            .filter { closure -> lastClosureId?.let { closure.id > it } ?: true }
            .sortedBy(ExecutionTradeClosure::id)
            .forEach { closure ->
                consecutiveLosses =
                    when {
                        closure.netPnl < BigDecimal.ZERO -> consecutiveLosses + 1
                        closure.netPnl > BigDecimal.ZERO -> 0
                        else -> consecutiveLosses
                    }
                lastClosureId = maxOf(lastClosureId ?: closure.id, closure.id)
            }

        return ExecutionRiskState(
            mode = snapshot.mode,
            peakEquity = previous?.peakEquity?.max(equity) ?: equity,
            utcDayStartedAt = dayStartedAt,
            dayStartEquity = previous?.dayStartEquity?.takeIf { sameUtcDay } ?: equity,
            latestEquity = equity,
            consecutiveLosses = consecutiveLosses,
            lastClosureId = lastClosureId,
            updatedAt = snapshot.capturedAt,
        )
    }

    fun evaluate(
        state: ExecutionRiskState?,
        now: Instant,
        maximumAge: Duration,
        maximumDailyLossFraction: BigDecimal,
        maximumAccountDrawdownFraction: BigDecimal,
        maximumConsecutiveLosses: Int,
    ): ExecutionRiskDecision {
        if (state == null) return ExecutionRiskDecision(listOf("RISK_STATE_UNAVAILABLE"))
        if (state.updatedAt.isAfter(now.plus(CLOCK_SKEW_TOLERANCE))) {
            return ExecutionRiskDecision(listOf("RISK_STATE_CLOCK_SKEW"))
        }
        if (Duration.between(state.updatedAt, now) > maximumAge) {
            return ExecutionRiskDecision(listOf("RISK_STATE_STALE"))
        }

        val reasonCodes = mutableListOf<String>()
        if (lossFraction(state.dayStartEquity, state.latestEquity) >= maximumDailyLossFraction) {
            reasonCodes += "DAILY_EQUITY_LOSS_LIMIT_REACHED"
        }
        if (lossFraction(state.peakEquity, state.latestEquity) >= maximumAccountDrawdownFraction) {
            reasonCodes += "ACCOUNT_DRAWDOWN_LIMIT_REACHED"
        }
        if (state.consecutiveLosses >= maximumConsecutiveLosses) {
            reasonCodes += "CONSECUTIVE_LOSS_LIMIT_REACHED"
        }
        return ExecutionRiskDecision(reasonCodes)
    }

    private fun lossFraction(
        baseline: BigDecimal,
        current: BigDecimal,
    ): BigDecimal {
        if (baseline <= BigDecimal.ZERO || current >= baseline) return BigDecimal.ZERO
        return baseline.subtract(current).divide(baseline, MathContext.DECIMAL64)
    }
}

private fun Instant.utcDayStartedAt(): Instant =
    atZone(ZoneOffset.UTC)
        .toLocalDate()
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()

private val CLOCK_SKEW_TOLERANCE: Duration = Duration.ofSeconds(5)
