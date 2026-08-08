package dev.yaklede.bybittrader.engine.strategy

import java.time.Duration
import java.time.Instant
import kotlin.math.abs

data class VolumeConfirmedTrendHistoricalEvidence(
    val protocolId: String,
    val candidateId: String,
    val protocolSha256: String,
    val externalResultSha256: String,
    val kotlinCoreParityResultSha256: String,
    val runtimeReplayParityResultSha256: String,
    val liveRiskPolicyParityResultSha256: String,
    val externalVenuePassed: Boolean,
    val kotlinCoreParityPassed: Boolean,
    val runtimeReplayParityPassed: Boolean,
    val liveRiskPolicyParityPassed: Boolean,
)

data class VolumeConfirmedTrendForwardPolicy(
    val policyId: String,
    val policySha256: String,
    val minimumCalendarDays: Int,
    val minimumClosedTrades: Int,
    val minimumExecutedTransitions: Int,
    val minimumSessionReturnPct: Double,
    val minimumClosedTradeProfitFactor: Double,
    val maximumDrawdownPct: Double,
    val maximumEntryExposureFraction: Double,
    val maximumAdverseExposureFraction: Double,
    val maximumLiquidationCount: Int,
    val maximumObservationStaleness: Duration,
) {
    init {
        require(minimumCalendarDays > 0 && minimumClosedTrades > 0 && minimumExecutedTransitions > 0)
        require(minimumClosedTradeProfitFactor >= 0.0 && maximumDrawdownPct > 0.0)
        require(maximumEntryExposureFraction > 0.0 && maximumAdverseExposureFraction > 0.0)
        require(maximumLiquidationCount >= 0 && !maximumObservationStaleness.isNegative)
    }
}

enum class VolumeConfirmedTrendApprovalStatus {
    HISTORICAL_EVIDENCE_REJECTED,
    RUNTIME_PARITY_REQUIRED,
    SHADOW_DISABLED,
    SHADOW_NOT_STARTED,
    SHADOW_BOOTSTRAPPING,
    SHADOW_COLLECTING,
    SHADOW_STALE,
    SHADOW_SESSION_FAILED,
    READY_FOR_HUMAN_REVIEW,
}

enum class VolumeConfirmedTrendApprovalGateStatus {
    PASS,
    PENDING,
    FAIL,
}

data class VolumeConfirmedTrendApprovalGate(
    val id: String,
    val status: VolumeConfirmedTrendApprovalGateStatus,
    val actual: String,
    val required: String,
    val reason: String,
)

data class VolumeConfirmedTrendApprovalReport(
    val status: VolumeConfirmedTrendApprovalStatus,
    val protocolId: String,
    val candidateId: String,
    val protocolSha256: String,
    val policyId: String,
    val policySha256: String,
    val evaluatedAt: Instant,
    val sessionId: String?,
    val observedCalendarDays: Double,
    val sessionReturnPct: Double?,
    val closedTradeProfitFactor: Double?,
    val gates: List<VolumeConfirmedTrendApprovalGate>,
    val readyForHumanReview: Boolean,
    val automaticExecutionAllowed: Boolean = false,
    val liveExecutionAllowed: Boolean = false,
)

data class VolumeConfirmedTrendApprovalSnapshot(
    val shadowReport: VolumeConfirmedTrendShadowReport?,
    val approvalReport: VolumeConfirmedTrendApprovalReport,
)

object VolumeConfirmedTrendApprovalGateContract {
    val requiredIds: Set<String> =
        linkedSetOf(
            "EXTERNAL_VENUE_HISTORY",
            "KOTLIN_CORE_PARITY",
            "RUNTIME_REPLAY_PARITY",
            "LIVE_RISK_POLICY_PARITY",
            "FRESH_SHADOW_DAYS",
            "CLOSED_TRADES",
            "EXECUTED_TRANSITIONS",
            "SESSION_RETURN_PCT",
            "CLOSED_TRADE_PROFIT_FACTOR",
            "MAXIMUM_DRAWDOWN_PCT",
            "MAXIMUM_ENTRY_EXPOSURE_FRACTION",
            "MAXIMUM_ADVERSE_EXPOSURE_FRACTION",
            "LIQUIDATION_COUNT",
            "OBSERVATION_STALENESS_SECONDS",
            "CURRENT_SESSION_START",
            "CURRENT_SESSION_CONTINUITY",
        )

    fun isSatisfiedBy(report: VolumeConfirmedTrendApprovalReport): Boolean {
        val gateIds = report.gates.map(VolumeConfirmedTrendApprovalGate::id)
        return gateIds.size == requiredIds.size &&
            gateIds.toSet() == requiredIds &&
            report.gates.all { gate -> gate.status == VolumeConfirmedTrendApprovalGateStatus.PASS } &&
            !report.automaticExecutionAllowed &&
            !report.liveExecutionAllowed
    }
}

class VolumeConfirmedTrendApprovalService(
    private val historicalEvidence: VolumeConfirmedTrendHistoricalEvidence,
    private val forwardPolicy: VolumeConfirmedTrendForwardPolicy,
    private val shadowReportProvider: suspend () -> VolumeConfirmedTrendShadowReport?,
    private val clock: () -> Instant = Instant::now,
) {
    suspend fun evaluate(): VolumeConfirmedTrendApprovalReport = snapshot().approvalReport

    suspend fun snapshot(): VolumeConfirmedTrendApprovalSnapshot {
        val shadowReport = shadowReportProvider()
        val evaluatedAt = clock()
        return VolumeConfirmedTrendApprovalSnapshot(
            shadowReport = shadowReport,
            approvalReport = evaluateSnapshot(shadowReport, evaluatedAt),
        )
    }

    private fun evaluateSnapshot(
        shadowReport: VolumeConfirmedTrendShadowReport?,
        evaluatedAt: Instant,
    ): VolumeConfirmedTrendApprovalReport {
        val state = shadowReport?.state
        val gates = mutableListOf<VolumeConfirmedTrendApprovalGate>()
        gates +=
            booleanGate(
                id = "EXTERNAL_VENUE_HISTORY",
                passed = historicalEvidence.externalVenuePassed,
                reason = "Frozen external venue economic and risk gates must pass.",
            )
        gates +=
            booleanGate(
                id = "KOTLIN_CORE_PARITY",
                passed = historicalEvidence.kotlinCoreParityPassed,
                reason = "Node and Kotlin historical traces must match.",
            )
        gates +=
            booleanGate(
                id = "RUNTIME_REPLAY_PARITY",
                passed = historicalEvidence.runtimeReplayParityPassed,
                reason = "Historical and persisted Shadow execution traces must match.",
            )
        gates +=
            booleanGate(
                id = "LIVE_RISK_POLICY_PARITY",
                passed = historicalEvidence.liveRiskPolicyParityPassed,
                reason = "Frozen validation and Live entry-risk state transitions must match.",
            )

        if (shadowReport == null) {
            gates += pendingShadowGates("Shadow runtime is disabled.")
            return report(
                status = baseFailureStatus() ?: VolumeConfirmedTrendApprovalStatus.SHADOW_DISABLED,
                evaluatedAt = evaluatedAt,
                state = null,
                observedDays = 0.0,
                sessionReturnPct = null,
                profitFactor = null,
                gates = gates,
            )
        }
        require(shadowReport.protocolId == historicalEvidence.protocolId) { "Shadow approval protocol ID mismatch." }
        require(shadowReport.candidateId == historicalEvidence.candidateId) { "Shadow approval candidate ID mismatch." }
        require(shadowReport.protocolSha256 == historicalEvidence.protocolSha256) {
            "Shadow approval protocol fingerprint mismatch."
        }
        if (state == null) {
            gates += pendingShadowGates("Shadow runtime has not persisted its first session.")
            return report(
                status = baseFailureStatus() ?: VolumeConfirmedTrendApprovalStatus.SHADOW_NOT_STARTED,
                evaluatedAt = evaluatedAt,
                state = null,
                observedDays = 0.0,
                sessionReturnPct = null,
                profitFactor = null,
                gates = gates,
            )
        }
        if (state.status == VolumeConfirmedTrendShadowStatus.BOOTSTRAPPING || state.sessionStartedAt == null) {
            gates += pendingShadowGates("Shadow runtime is restoring its causal indicator state.")
            return report(
                status = baseFailureStatus() ?: VolumeConfirmedTrendApprovalStatus.SHADOW_BOOTSTRAPPING,
                evaluatedAt = evaluatedAt,
                state = state,
                observedDays = 0.0,
                sessionReturnPct = null,
                profitFactor = null,
                gates = gates,
            )
        }

        val sessionStartedAt = requireNotNull(state.sessionStartedAt)
        val lastObservedAt = state.lastObservedAt ?: sessionStartedAt
        require(!lastObservedAt.isBefore(sessionStartedAt)) { "Shadow observation predates its current session." }
        val observedDays = Duration.between(sessionStartedAt, lastObservedAt).seconds / 86_400.0
        val staleness = Duration.between(lastObservedAt, evaluatedAt)
        require(!staleness.isNegative) { "Shadow approval clock predates the latest observation." }
        val sessionReturnPct = ((state.equity / state.sessionStartingEquity) - 1.0) * 100.0
        val currentSessionEvents = shadowReport.recentEvents.filter { event -> event.sessionId == state.sessionId }
        val closedNetPnls =
            currentSessionEvents
                .filter { event -> event.type == VolumeConfirmedTrendShadowEventType.POSITION_CLOSED }
                .map(VolumeConfirmedTrendShadowEvent::netPnl)
        val profitFactor = profitFactor(closedNetPnls)
        val sessionStartEvents =
            currentSessionEvents.filter { event -> event.type == VolumeConfirmedTrendShadowEventType.SESSION_STARTED }
        val hasValidSessionStart =
            sessionStartEvents.size == 1 &&
                sessionStartEvents.single().eventAt == sessionStartedAt &&
                sessionStartEvents.single().observedAt == sessionStartedAt
        val continuous =
            currentSessionEvents.none { event -> event.type == VolumeConfirmedTrendShadowEventType.SESSION_INVALIDATED } &&
                currentSessionEvents.matchesPersistedSessionCounters(
                    state = state,
                    sessionStartedAt = sessionStartedAt,
                    lastObservedAt = lastObservedAt,
                )

        gates += lowerBoundGate("FRESH_SHADOW_DAYS", observedDays, forwardPolicy.minimumCalendarDays.toDouble())
        gates += lowerBoundGate("CLOSED_TRADES", state.closedTrades.toDouble(), forwardPolicy.minimumClosedTrades.toDouble())
        gates +=
            lowerBoundGate(
                "EXECUTED_TRANSITIONS",
                state.executedTransitions.toDouble(),
                forwardPolicy.minimumExecutedTransitions.toDouble(),
            )
        gates += lowerBoundGate("SESSION_RETURN_PCT", sessionReturnPct, forwardPolicy.minimumSessionReturnPct)
        gates += nullableLowerBoundGate("CLOSED_TRADE_PROFIT_FACTOR", profitFactor, forwardPolicy.minimumClosedTradeProfitFactor)
        gates += upperBoundGate("MAXIMUM_DRAWDOWN_PCT", state.maximumDrawdownPct, forwardPolicy.maximumDrawdownPct)
        gates +=
            upperBoundGate(
                "MAXIMUM_ENTRY_EXPOSURE_FRACTION",
                state.maximumEntryExposureFraction,
                forwardPolicy.maximumEntryExposureFraction,
            )
        gates +=
            upperBoundGate(
                "MAXIMUM_ADVERSE_EXPOSURE_FRACTION",
                state.maximumAdverseExposureFraction,
                forwardPolicy.maximumAdverseExposureFraction,
            )
        gates +=
            upperBoundGate(
                "LIQUIDATION_COUNT",
                state.liquidationCount.toDouble(),
                forwardPolicy.maximumLiquidationCount.toDouble(),
            )
        gates +=
            upperBoundGate(
                "OBSERVATION_STALENESS_SECONDS",
                staleness.seconds.toDouble(),
                forwardPolicy.maximumObservationStaleness.seconds.toDouble(),
            )
        gates +=
            booleanGate(
                id = "CURRENT_SESSION_START",
                passed = hasValidSessionStart,
                reason = "The current Shadow session must contain exactly one matching start event.",
            )
        gates +=
            booleanGate(
                id = "CURRENT_SESSION_CONTINUITY",
                passed = continuous,
                reason = "The current Shadow session must contain no continuity invalidation.",
            )

        val baseFailure = baseFailureStatus()
        val hardFailureIds =
            setOf(
                "MAXIMUM_DRAWDOWN_PCT",
                "MAXIMUM_ENTRY_EXPOSURE_FRACTION",
                "MAXIMUM_ADVERSE_EXPOSURE_FRACTION",
                "LIQUIDATION_COUNT",
                "CURRENT_SESSION_START",
                "CURRENT_SESSION_CONTINUITY",
            )
        val hardFailure =
            gates.any { gate ->
                gate.id in hardFailureIds && gate.status == VolumeConfirmedTrendApprovalGateStatus.FAIL
            }
        val stale =
            gates.any { gate ->
                gate.id == "OBSERVATION_STALENESS_SECONDS" &&
                    gate.status == VolumeConfirmedTrendApprovalGateStatus.FAIL
            }
        val allPassed = gates.all { gate -> gate.status == VolumeConfirmedTrendApprovalGateStatus.PASS }
        val status =
            when {
                baseFailure != null -> baseFailure
                hardFailure -> VolumeConfirmedTrendApprovalStatus.SHADOW_SESSION_FAILED
                stale -> VolumeConfirmedTrendApprovalStatus.SHADOW_STALE
                allPassed -> VolumeConfirmedTrendApprovalStatus.READY_FOR_HUMAN_REVIEW
                else -> VolumeConfirmedTrendApprovalStatus.SHADOW_COLLECTING
            }
        return report(
            status = status,
            evaluatedAt = evaluatedAt,
            state = state,
            observedDays = observedDays,
            sessionReturnPct = sessionReturnPct,
            profitFactor = profitFactor,
            gates = gates,
        )
    }

    private fun baseFailureStatus(): VolumeConfirmedTrendApprovalStatus? =
        when {
            !historicalEvidence.externalVenuePassed || !historicalEvidence.kotlinCoreParityPassed ->
                VolumeConfirmedTrendApprovalStatus.HISTORICAL_EVIDENCE_REJECTED
            !historicalEvidence.runtimeReplayParityPassed -> VolumeConfirmedTrendApprovalStatus.RUNTIME_PARITY_REQUIRED
            !historicalEvidence.liveRiskPolicyParityPassed -> VolumeConfirmedTrendApprovalStatus.RUNTIME_PARITY_REQUIRED
            else -> null
        }

    private fun report(
        status: VolumeConfirmedTrendApprovalStatus,
        evaluatedAt: Instant,
        state: VolumeConfirmedTrendShadowState?,
        observedDays: Double,
        sessionReturnPct: Double?,
        profitFactor: Double?,
        gates: List<VolumeConfirmedTrendApprovalGate>,
    ): VolumeConfirmedTrendApprovalReport =
        VolumeConfirmedTrendApprovalReport(
            status = status,
            protocolId = historicalEvidence.protocolId,
            candidateId = historicalEvidence.candidateId,
            protocolSha256 = historicalEvidence.protocolSha256,
            policyId = forwardPolicy.policyId,
            policySha256 = forwardPolicy.policySha256,
            evaluatedAt = evaluatedAt,
            sessionId = state?.sessionId,
            observedCalendarDays = observedDays,
            sessionReturnPct = sessionReturnPct,
            closedTradeProfitFactor = profitFactor,
            gates = gates,
            readyForHumanReview = status == VolumeConfirmedTrendApprovalStatus.READY_FOR_HUMAN_REVIEW,
        )

    private fun pendingShadowGates(reason: String): List<VolumeConfirmedTrendApprovalGate> =
        listOf(
            "FRESH_SHADOW_DAYS",
            "CLOSED_TRADES",
            "EXECUTED_TRANSITIONS",
            "SESSION_RETURN_PCT",
            "CLOSED_TRADE_PROFIT_FACTOR",
            "MAXIMUM_DRAWDOWN_PCT",
            "MAXIMUM_ENTRY_EXPOSURE_FRACTION",
            "MAXIMUM_ADVERSE_EXPOSURE_FRACTION",
            "LIQUIDATION_COUNT",
            "OBSERVATION_STALENESS_SECONDS",
            "CURRENT_SESSION_START",
            "CURRENT_SESSION_CONTINUITY",
        ).map { id ->
            VolumeConfirmedTrendApprovalGate(
                id = id,
                status = VolumeConfirmedTrendApprovalGateStatus.PENDING,
                actual = "UNAVAILABLE",
                required = "FROZEN_FORWARD_POLICY",
                reason = reason,
            )
        }
}

private fun booleanGate(
    id: String,
    passed: Boolean,
    reason: String,
): VolumeConfirmedTrendApprovalGate =
    VolumeConfirmedTrendApprovalGate(
        id = id,
        status = if (passed) VolumeConfirmedTrendApprovalGateStatus.PASS else VolumeConfirmedTrendApprovalGateStatus.FAIL,
        actual = passed.toString(),
        required = "true",
        reason = reason,
    )

private fun lowerBoundGate(
    id: String,
    actual: Double,
    required: Double,
): VolumeConfirmedTrendApprovalGate =
    VolumeConfirmedTrendApprovalGate(
        id = id,
        status = if (actual >= required) VolumeConfirmedTrendApprovalGateStatus.PASS else VolumeConfirmedTrendApprovalGateStatus.PENDING,
        actual = actual.toString(),
        required = ">=$required",
        reason = "$id must reach its frozen minimum.",
    )

private fun nullableLowerBoundGate(
    id: String,
    actual: Double?,
    required: Double,
): VolumeConfirmedTrendApprovalGate =
    if (actual == null) {
        VolumeConfirmedTrendApprovalGate(
            id = id,
            status = VolumeConfirmedTrendApprovalGateStatus.PENDING,
            actual = "UNAVAILABLE",
            required = ">=$required",
            reason = "At least one closed winning or losing trade is required.",
        )
    } else {
        lowerBoundGate(id, actual, required)
    }

private fun upperBoundGate(
    id: String,
    actual: Double,
    required: Double,
): VolumeConfirmedTrendApprovalGate =
    VolumeConfirmedTrendApprovalGate(
        id = id,
        status = if (actual <= required) VolumeConfirmedTrendApprovalGateStatus.PASS else VolumeConfirmedTrendApprovalGateStatus.FAIL,
        actual = actual.toString(),
        required = "<=$required",
        reason = "$id must remain within its frozen maximum.",
    )

private fun profitFactor(netPnls: List<Double>): Double? {
    if (netPnls.isEmpty()) return null
    val grossProfit = netPnls.filter { it > 0.0 }.sum()
    val grossLoss = abs(netPnls.filter { it < 0.0 }.sum())
    return when {
        grossLoss > 0.0 -> grossProfit / grossLoss
        grossProfit > 0.0 -> Double.MAX_VALUE
        else -> 0.0
    }
}

private fun List<VolumeConfirmedTrendShadowEvent>.matchesPersistedSessionCounters(
    state: VolumeConfirmedTrendShadowState,
    sessionStartedAt: Instant,
    lastObservedAt: Instant,
): Boolean {
    val eventIds = map(VolumeConfirmedTrendShadowEvent::eventId)
    val eventsAreBounded =
        all { event ->
            !event.eventAt.isBefore(sessionStartedAt) &&
                !event.eventAt.isAfter(event.observedAt) &&
                !event.observedAt.isBefore(sessionStartedAt) &&
                !event.observedAt.isAfter(lastObservedAt)
        }
    val closedTrades = count { event -> event.type == VolumeConfirmedTrendShadowEventType.POSITION_CLOSED }
    val transitionEvidence =
        count { event ->
            event.type == VolumeConfirmedTrendShadowEventType.POSITION_OPENED ||
                event.type == VolumeConfirmedTrendShadowEventType.MINIMUM_QUANTITY_SKIPPED
        }
    return eventIds.distinct().size == eventIds.size &&
        eventsAreBounded &&
        closedTrades == state.closedTrades &&
        transitionEvidence == state.executedTransitions
}
