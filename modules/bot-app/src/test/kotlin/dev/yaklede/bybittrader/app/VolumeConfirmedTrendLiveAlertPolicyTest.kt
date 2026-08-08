package dev.yaklede.bybittrader.app

import dev.yaklede.bybittrader.domain.BotMode
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveEvaluationResult
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveEvaluationStatus
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveLoopResult
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveLoopStatus
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveState
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveStatus
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class VolumeConfirmedTrendLiveAlertPolicyTest :
    StringSpec({
        "an unchanged halt alerts once until its repeat interval" {
            val clock = MutableClock(Instant.parse("2026-08-08T00:00:00Z"))
            val policy = VolumeConfirmedTrendLiveAlertPolicy(Duration.ofHours(1), clock)
            val halted = liveResult(VolumeConfirmedTrendLiveEvaluationStatus.HALTED)

            policy.shouldAlert(halted) shouldBe true
            policy.shouldAlert(halted) shouldBe false
            clock.now = clock.now.plus(Duration.ofHours(1))
            policy.shouldAlert(halted) shouldBe true
        }

        "a healthy reconciliation resets the active halt fingerprint" {
            val policy = VolumeConfirmedTrendLiveAlertPolicy()
            val halted = liveResult(VolumeConfirmedTrendLiveEvaluationStatus.HALTED)

            policy.shouldAlert(halted) shouldBe true
            policy.shouldAlert(liveResult(VolumeConfirmedTrendLiveEvaluationStatus.RECONCILED)) shouldBe false
            policy.shouldAlert(halted) shouldBe true
        }

        "risk blocking alerts once and alerts again when its reason changes" {
            val policy = VolumeConfirmedTrendLiveAlertPolicy()
            val drawdown =
                liveResult(
                    status = VolumeConfirmedTrendLiveEvaluationStatus.RISK_BLOCKED,
                    riskReasonCodes = listOf("ACCOUNT_DRAWDOWN_LIMIT_REACHED"),
                )
            val walletMismatch =
                liveResult(
                    status = VolumeConfirmedTrendLiveEvaluationStatus.RISK_BLOCKED,
                    riskReasonCodes = listOf("ACCOUNT_LEDGER_MISMATCH_CONFIRMED"),
                )

            policy.shouldAlert(drawdown) shouldBe true
            policy.shouldAlert(drawdown) shouldBe false
            policy.shouldAlert(walletMismatch) shouldBe true
        }
    })

private fun liveResult(
    status: VolumeConfirmedTrendLiveEvaluationStatus,
    riskReasonCodes: List<String> = emptyList(),
): VolumeConfirmedTrendLiveLoopResult {
    val halted = status == VolumeConfirmedTrendLiveEvaluationStatus.HALTED
    val state =
        VolumeConfirmedTrendLiveState(
            protocolId = "volume-confirmed-trend-ensemble-v1",
            candidateId = "vcte_4h_majority_001",
            protocolSha256 = "a".repeat(64),
            symbol = Symbol("BTCUSDT"),
            status = if (halted) VolumeConfirmedTrendLiveStatus.HALTED else VolumeConfirmedTrendLiveStatus.FLAT,
            approvalId = "approval-001",
            activeDecisionKey = null,
            pendingTargetSide = null,
            clientOrderId = null,
            exchangeOrderId = null,
            observedPositionSide = null,
            observedPositionQuantity = null,
            lastExecutionId = null,
            haltedReasonCode = if (halted) "TREND_TEST_HALT" else null,
            updatedAt = Instant.parse("2026-08-08T00:00:00Z"),
        )
    return VolumeConfirmedTrendLiveLoopResult(
        status = if (halted) VolumeConfirmedTrendLiveLoopStatus.HALTED else VolumeConfirmedTrendLiveLoopStatus.RECONCILED,
        botMode = BotMode.RUNNING,
        shadowSessionId = "shadow-session-001",
        signal = null,
        evaluation =
            VolumeConfirmedTrendLiveEvaluationResult(
                status = status,
                state = state,
                plan = null,
                riskReasonCodes = riskReasonCodes,
            ),
        evaluatedAt = state.updatedAt,
    )
}

private class MutableClock(
    var now: Instant,
    private val zone: ZoneId = ZoneOffset.UTC,
) : Clock() {
    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)

    override fun instant(): Instant = now
}
