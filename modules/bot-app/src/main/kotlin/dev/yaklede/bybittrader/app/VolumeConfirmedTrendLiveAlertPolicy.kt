package dev.yaklede.bybittrader.app

import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveEvaluationStatus
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveLoopResult
import java.time.Clock
import java.time.Duration
import java.time.Instant

class VolumeConfirmedTrendLiveAlertPolicy(
    private val repeatInterval: Duration = Duration.ofHours(1),
    private val clock: Clock = Clock.systemUTC(),
) {
    private var activeFingerprint: String? = null
    private var lastAlertAt: Instant? = null

    init {
        require(!repeatInterval.isNegative && !repeatInterval.isZero) {
            "Trend live alert repeat interval must be positive."
        }
    }

    @Synchronized
    fun shouldAlert(result: VolumeConfirmedTrendLiveLoopResult): Boolean {
        val fingerprint = result.alertFingerprint()
        if (fingerprint == null) {
            recordHealthy()
            return false
        }
        val now = Instant.now(clock)
        val changed = fingerprint != activeFingerprint
        val repeatDue = lastAlertAt?.let { Duration.between(it, now) >= repeatInterval } ?: true
        if (!changed && !repeatDue) return false
        activeFingerprint = fingerprint
        lastAlertAt = now
        return true
    }

    @Synchronized
    fun shouldAlert(error: Throwable): Boolean {
        val now = Instant.now(clock)
        val fingerprint = "FAILURE|${error::class.qualifiedName}|${error.message.orEmpty()}"
        val changed = fingerprint != activeFingerprint
        val repeatDue = lastAlertAt?.let { Duration.between(it, now) >= repeatInterval } ?: true
        if (!changed && !repeatDue) return false
        activeFingerprint = fingerprint
        lastAlertAt = now
        return true
    }

    @Synchronized
    fun recordHealthy() {
        activeFingerprint = null
        lastAlertAt = null
    }
}

private fun VolumeConfirmedTrendLiveLoopResult.alertFingerprint(): String? =
    when (evaluation.status) {
        VolumeConfirmedTrendLiveEvaluationStatus.ORDER_SUBMITTED ->
            "ORDER_SUBMITTED|${evaluation.state.clientOrderId}|${evaluation.state.exchangeOrderId}"
        VolumeConfirmedTrendLiveEvaluationStatus.ORDER_NOT_FILLED ->
            "ORDER_NOT_FILLED|${evaluation.state.clientOrderId}"
        VolumeConfirmedTrendLiveEvaluationStatus.RECOVERED ->
            "RECOVERED|${evaluation.state.lastExecutionId}|${evaluation.state.status.name}"
        VolumeConfirmedTrendLiveEvaluationStatus.HALTED ->
            "HALTED|${evaluation.state.haltedReasonCode}"
        VolumeConfirmedTrendLiveEvaluationStatus.APPROVAL_BLOCKED -> {
            val state = evaluation.state
            listOf(
                "APPROVAL_BLOCKED",
                evaluation.approvalFailures.joinToString(",") { it.name },
                state.status.name,
                state.haltedReasonCode.orEmpty(),
                state.clientOrderId.orEmpty(),
                state.exchangeOrderId.orEmpty(),
                state.observedPositionSide?.name.orEmpty(),
                state.observedPositionQuantity?.toPlainString().orEmpty(),
            ).joinToString("|")
        }
        VolumeConfirmedTrendLiveEvaluationStatus.RISK_BLOCKED ->
            "RISK_BLOCKED|${evaluation.riskReasonCodes.joinToString(",")}"
        else -> null
    }
