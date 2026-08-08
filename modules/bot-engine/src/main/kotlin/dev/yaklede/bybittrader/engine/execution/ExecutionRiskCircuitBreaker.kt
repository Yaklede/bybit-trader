package dev.yaklede.bybittrader.engine.execution

import java.math.BigDecimal
import java.math.MathContext
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

enum class ExecutionRiskNavStatus {
    UNAVAILABLE,
    BASELINE,
    READY,
    INVALID,
}

data class ExecutionRiskState(
    val mode: ExecutionRuntimeMode,
    val peakEquity: BigDecimal,
    val utcDayStartedAt: Instant,
    val dayStartEquity: BigDecimal,
    val latestEquity: BigDecimal,
    val consecutiveLosses: Int,
    val lastClosureId: Long?,
    val updatedAt: Instant,
    val navStatus: ExecutionRiskNavStatus = ExecutionRiskNavStatus.READY,
    val strategyUnits: BigDecimal = latestEquity,
    val latestUnitizedNav: BigDecimal = latestEquity,
    val peakUnitizedNav: BigDecimal = peakEquity,
    val dayStartUnitizedNav: BigDecimal = dayStartEquity,
    val cumulativeExternalCashFlow: BigDecimal = BigDecimal.ZERO,
    val lastAccountTransactionId: Long? = null,
)

data class ExecutionRiskDecision(
    val reasonCodes: List<String>,
) {
    val allowsEntry: Boolean = reasonCodes.isEmpty()
}

data class ExecutionRiskReadiness(
    val runtimeMode: ExecutionRuntimeMode,
    val botMode: String,
    val executionEnabled: Boolean,
    val evaluatedAt: Instant,
    val allowsEntry: Boolean,
    val reasonCodes: List<String>,
    val riskState: ExecutionRiskState?,
    val walletReconciliationEnabled: Boolean,
    val walletReconciliationState: ExecutionWalletReconciliationState?,
    val currentDailyLossFraction: BigDecimal?,
    val currentAccountDrawdownFraction: BigDecimal?,
    val maximumDailyLossFraction: BigDecimal,
    val maximumAccountDrawdownFraction: BigDecimal,
    val maximumConsecutiveLosses: Int,
)

internal object ExecutionRiskCircuitBreaker {
    fun update(
        previous: ExecutionRiskState?,
        snapshot: ExecutionAccountSnapshot,
        newClosures: List<ExecutionTradeClosure>,
        accountTransactions: List<ExecutionAccountTransactionEvent> = emptyList(),
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

        val eligibleTransactions =
            accountTransactions
                .asSequence()
                .filter { event -> event.mode == snapshot.mode }
                .filter { event -> previous?.lastAccountTransactionId?.let { event.id > it } ?: true }
                .filter { event -> !event.transaction.transactionAt.isAfter(snapshot.capturedAt) }
                .sortedBy(ExecutionAccountTransactionEvent::id)
                .toList()
        val latestTransactionId =
            eligibleTransactions.maxOfOrNull(ExecutionAccountTransactionEvent::id)
                ?: previous?.lastAccountTransactionId
        val requiresNavBaseline =
            previous == null || previous.navStatus == ExecutionRiskNavStatus.UNAVAILABLE
        val navUpdate =
            if (requiresNavBaseline) {
                UnitizedNavUpdate(
                    status = ExecutionRiskNavStatus.BASELINE,
                    strategyUnits = equity,
                    latestNav = BigDecimal.ONE,
                    peakNav = BigDecimal.ONE,
                    dayStartNav = BigDecimal.ONE,
                    cumulativeExternalCashFlow = BigDecimal.ZERO,
                )
            } else {
                updateUnitizedNav(
                    previous = previous,
                    currentEquity = equity,
                    externalCashFlow = eligibleTransactions.externalCashFlow(),
                    sameUtcDay = sameUtcDay,
                )
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
            navStatus = navUpdate.status,
            strategyUnits = navUpdate.strategyUnits,
            latestUnitizedNav = navUpdate.latestNav,
            peakUnitizedNav = navUpdate.peakNav,
            dayStartUnitizedNav = navUpdate.dayStartNav,
            cumulativeExternalCashFlow = navUpdate.cumulativeExternalCashFlow,
            lastAccountTransactionId = latestTransactionId,
        )
    }

    fun evaluate(
        state: ExecutionRiskState?,
        now: Instant,
        maximumAge: Duration,
        maximumDailyLossFraction: BigDecimal,
        maximumAccountDrawdownFraction: BigDecimal,
        maximumConsecutiveLosses: Int,
        useUnitizedNav: Boolean = true,
    ): ExecutionRiskDecision {
        if (state == null) return ExecutionRiskDecision(listOf("RISK_STATE_UNAVAILABLE"))
        if (state.updatedAt.isAfter(now.plus(CLOCK_SKEW_TOLERANCE))) {
            return ExecutionRiskDecision(listOf("RISK_STATE_CLOCK_SKEW"))
        }
        if (Duration.between(state.updatedAt, now) > maximumAge) {
            return ExecutionRiskDecision(listOf("RISK_STATE_STALE"))
        }
        if (useUnitizedNav) {
            when (state.navStatus) {
                ExecutionRiskNavStatus.UNAVAILABLE ->
                    return ExecutionRiskDecision(listOf("RISK_NAV_UNAVAILABLE"))
                ExecutionRiskNavStatus.BASELINE ->
                    return ExecutionRiskDecision(listOf("RISK_NAV_BASELINE_PENDING"))
                ExecutionRiskNavStatus.INVALID ->
                    return ExecutionRiskDecision(listOf("RISK_NAV_INVALID"))
                ExecutionRiskNavStatus.READY -> Unit
            }
        }

        val reasonCodes = mutableListOf<String>()
        val dayStart = state.dayStartUnitizedNav.takeIf { useUnitizedNav } ?: state.dayStartEquity
        val peak = state.peakUnitizedNav.takeIf { useUnitizedNav } ?: state.peakEquity
        val latest = state.latestUnitizedNav.takeIf { useUnitizedNav } ?: state.latestEquity
        if (lossFraction(dayStart, latest) >= maximumDailyLossFraction) {
            reasonCodes += "DAILY_EQUITY_LOSS_LIMIT_REACHED"
        }
        if (lossFraction(peak, latest) >= maximumAccountDrawdownFraction) {
            reasonCodes += "ACCOUNT_DRAWDOWN_LIMIT_REACHED"
        }
        if (state.consecutiveLosses >= maximumConsecutiveLosses) {
            reasonCodes += "CONSECUTIVE_LOSS_LIMIT_REACHED"
        }
        return ExecutionRiskDecision(reasonCodes)
    }

    fun evaluateAccountDrawdown(
        state: ExecutionRiskState?,
        now: Instant,
        maximumAge: Duration,
        maximumAccountDrawdownFraction: BigDecimal,
        useUnitizedNav: Boolean = true,
    ): ExecutionRiskDecision {
        require(!maximumAge.isNegative && !maximumAge.isZero) { "Risk state maximum age must be positive." }
        require(maximumAccountDrawdownFraction > BigDecimal.ZERO && maximumAccountDrawdownFraction <= BigDecimal.ONE) {
            "Maximum account drawdown fraction must be in (0, 1]."
        }
        if (state == null) return ExecutionRiskDecision(listOf("RISK_STATE_UNAVAILABLE"))
        if (state.updatedAt.isAfter(now.plus(CLOCK_SKEW_TOLERANCE))) {
            return ExecutionRiskDecision(listOf("RISK_STATE_CLOCK_SKEW"))
        }
        if (Duration.between(state.updatedAt, now) > maximumAge) {
            return ExecutionRiskDecision(listOf("RISK_STATE_STALE"))
        }
        if (useUnitizedNav) {
            when (state.navStatus) {
                ExecutionRiskNavStatus.UNAVAILABLE ->
                    return ExecutionRiskDecision(listOf("RISK_NAV_UNAVAILABLE"))
                ExecutionRiskNavStatus.BASELINE ->
                    return ExecutionRiskDecision(listOf("RISK_NAV_BASELINE_PENDING"))
                ExecutionRiskNavStatus.INVALID ->
                    return ExecutionRiskDecision(listOf("RISK_NAV_INVALID"))
                ExecutionRiskNavStatus.READY -> Unit
            }
        }

        val peak = state.peakUnitizedNav.takeIf { useUnitizedNav } ?: state.peakEquity
        val latest = state.latestUnitizedNav.takeIf { useUnitizedNav } ?: state.latestEquity
        val reasons =
            if (lossFraction(peak, latest) >= maximumAccountDrawdownFraction) {
                listOf("ACCOUNT_DRAWDOWN_LIMIT_REACHED")
            } else {
                emptyList()
            }
        return ExecutionRiskDecision(reasons)
    }

    private fun updateUnitizedNav(
        previous: ExecutionRiskState,
        currentEquity: BigDecimal,
        externalCashFlow: BigDecimal,
        sameUtcDay: Boolean,
    ): UnitizedNavUpdate {
        if (previous.navStatus == ExecutionRiskNavStatus.INVALID) {
            return previous.invalidNavUpdate()
        }
        val preFlowEquity = currentEquity - externalCashFlow
        if (
            previous.latestEquity <= BigDecimal.ZERO ||
            previous.latestUnitizedNav <= BigDecimal.ZERO ||
            previous.strategyUnits <= BigDecimal.ZERO ||
            preFlowEquity <= BigDecimal.ZERO
        ) {
            return previous.invalidNavUpdate()
        }
        val periodReturnFactor = preFlowEquity.divide(previous.latestEquity, MathContext.DECIMAL128)
        val latestNav = previous.latestUnitizedNav.multiply(periodReturnFactor, MathContext.DECIMAL128)
        if (latestNav <= BigDecimal.ZERO) return previous.invalidNavUpdate()
        val strategyUnits =
            previous.strategyUnits.add(
                externalCashFlow.divide(latestNav, MathContext.DECIMAL128),
                MathContext.DECIMAL128,
            )
        if (strategyUnits <= BigDecimal.ZERO) return previous.invalidNavUpdate()
        return UnitizedNavUpdate(
            status = ExecutionRiskNavStatus.READY,
            strategyUnits = strategyUnits,
            latestNav = latestNav,
            peakNav = previous.peakUnitizedNav.max(latestNav),
            dayStartNav = previous.dayStartUnitizedNav.takeIf { sameUtcDay } ?: latestNav,
            cumulativeExternalCashFlow = previous.cumulativeExternalCashFlow + externalCashFlow,
        )
    }

    private fun lossFraction(
        baseline: BigDecimal,
        current: BigDecimal,
    ): BigDecimal {
        if (baseline <= BigDecimal.ZERO || current >= baseline) return BigDecimal.ZERO
        return baseline.subtract(current).divide(baseline, MathContext.DECIMAL64)
    }
}

private data class UnitizedNavUpdate(
    val status: ExecutionRiskNavStatus,
    val strategyUnits: BigDecimal,
    val latestNav: BigDecimal,
    val peakNav: BigDecimal,
    val dayStartNav: BigDecimal,
    val cumulativeExternalCashFlow: BigDecimal,
)

private fun ExecutionRiskState.invalidNavUpdate(): UnitizedNavUpdate =
    UnitizedNavUpdate(
        status = ExecutionRiskNavStatus.INVALID,
        strategyUnits = strategyUnits,
        latestNav = latestUnitizedNav,
        peakNav = peakUnitizedNav,
        dayStartNav = dayStartUnitizedNav,
        cumulativeExternalCashFlow = cumulativeExternalCashFlow,
    )

private fun List<ExecutionAccountTransactionEvent>.externalCashFlow(): BigDecimal =
    asSequence()
        .filterNot { event -> event.transaction.type.uppercase() in STRATEGY_PERFORMANCE_TRANSACTION_TYPES }
        .fold(BigDecimal.ZERO) { total, event -> total + event.transaction.change }

private fun Instant.utcDayStartedAt(): Instant =
    atZone(ZoneOffset.UTC)
        .toLocalDate()
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()

private val CLOCK_SKEW_TOLERANCE: Duration = Duration.ofSeconds(5)

private val STRATEGY_PERFORMANCE_TRANSACTION_TYPES =
    setOf(
        "TRADE",
        "SETTLEMENT",
        "DELIVERY",
        "LIQUIDATION",
        "ADL",
        "FEE_REFUND",
        "INTEREST",
    )
