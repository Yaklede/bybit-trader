package dev.yaklede.bybittrader.app

import dev.yaklede.bybittrader.alerts.AlertSeverity
import dev.yaklede.bybittrader.domain.BotMode
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.strategy.TREND_ACTIVE_ORDER_CANCEL_REQUESTED_REASON_CODE
import dev.yaklede.bybittrader.engine.strategy.TREND_SAFETY_HALT_EXIT_REASON_CODE_PREFIX
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveApprovalFailure
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveEvaluationResult
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveEvaluationStatus
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveLoopResult
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveLoopStatus
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveState
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveStatus
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendTargetAction
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendTargetPlan
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.math.BigDecimal
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

        "exact-order cancellation pending alerts once while ordinary recovery stays quiet" {
            val policy = VolumeConfirmedTrendLiveAlertPolicy()
            val ordinary = liveResult(VolumeConfirmedTrendLiveEvaluationStatus.RECOVERY_PENDING)
            val cancellation =
                liveResult(
                    status = VolumeConfirmedTrendLiveEvaluationStatus.RECOVERY_PENDING,
                    recoveryReasonCode = TREND_ACTIVE_ORDER_CANCEL_REQUESTED_REASON_CODE,
                    clientOrderId = "vct-entry-001",
                    exchangeOrderId = "exchange-entry-001",
                )

            policy.shouldAlert(ordinary) shouldBe false
            policy.shouldAlert(cancellation) shouldBe true
            policy.shouldAlert(cancellation) shouldBe false
        }

        "exact-order cancellation alert explains the hold and next action in Korean" {
            val result =
                liveResult(
                    status = VolumeConfirmedTrendLiveEvaluationStatus.RECOVERY_PENDING,
                    recoveryReasonCode = TREND_ACTIVE_ORDER_CANCEL_REQUESTED_REASON_CODE,
                    clientOrderId = "vct-exit-001",
                    exchangeOrderId = "exchange-exit-001",
                )

            val message = requireNotNull(result.toTrendLiveAlertMessage())

            message.severity shouldBe AlertSeverity.WARNING
            message.title shouldBe "H4 주문 취소 확인 중"
            message.body shouldContain "취소 완료를 확인할 때까지 새 주문을 보내지 않아요"
            message.body shouldContain "대시보드와 Bybit에서 주문 상태를 확인해 주세요"
            message.body shouldContain "클라이언트 주문 ID: vct-exit-001"
            message.body shouldContain "거래소 주문 ID: exchange-exit-001"
        }

        "execution contract halt explains the mismatch and required operator checks in Korean" {
            val result =
                liveResult(
                    status = VolumeConfirmedTrendLiveEvaluationStatus.HALTED,
                    haltedReasonCode = "TREND_ENTRY_EXECUTION_SIDE_MISMATCH",
                )

            val message = requireNotNull(result.toTrendLiveAlertMessage())

            message.severity shouldBe AlertSeverity.CRITICAL
            message.title shouldBe "H4 실거래 자동 중단"
            message.body shouldContain "체결 방향이 저장된 주문과 다르게 확인됐어요"
            message.body shouldContain "Bybit 주문 내역, 체결 내역, 현재 포지션 수량을 서로 비교해 주세요"
            message.body shouldContain "확인이 끝나기 전에는 실거래를 다시 켜지 마세요"
            message.body shouldContain "진단 코드: TREND_ENTRY_EXECUTION_SIDE_MISMATCH"
        }

        "cancellation acknowledgement mismatch explains that the cancellation identity is unsafe" {
            val reasonCode =
                "TREND_ENTRY_ORDER_REDUCE_ONLY_MISMATCH|" +
                    "TREND_ACTIVE_ORDER_CANCEL_ACK_EXCHANGE_ID_MISMATCH"
            val result =
                liveResult(
                    status = VolumeConfirmedTrendLiveEvaluationStatus.HALTED,
                    haltedReasonCode = reasonCode,
                )

            val message = requireNotNull(result.toTrendLiveAlertMessage())

            message.severity shouldBe AlertSeverity.CRITICAL
            message.body shouldContain "주문 취소 응답의 주문 ID가 취소 요청과 일치하지 않아요"
            message.body shouldContain "진단 코드: $reasonCode"
        }

        "approval blocking alerts again when preserved order evidence changes" {
            val policy = VolumeConfirmedTrendLiveAlertPolicy()
            val first = approvalBlockedResult("TREND_ENTRY_ORDER_STATE_UNKNOWN", "vct-entry-001")
            val changed = approvalBlockedResult("TREND_ENTRY_FILL_WITHOUT_POSITION", "vct-entry-001")

            policy.shouldAlert(first) shouldBe true
            policy.shouldAlert(first) shouldBe false
            policy.shouldAlert(changed) shouldBe true
        }

        "approval-blocked alert includes recovery evidence and a next action" {
            val result =
                approvalBlockedResult(
                    haltedReasonCode = "TREND_ENTRY_ORDER_STATE_UNKNOWN",
                    clientOrderId = "vct-entry-001",
                    exchangeOrderId = "exchange-entry-001",
                )

            val message = requireNotNull(result.toTrendLiveAlertMessage())

            message.severity shouldBe AlertSeverity.CRITICAL
            message.title shouldBe "H4 실거래 승인 무효"
            message.body shouldContain "기존 중단 사유: TREND_ENTRY_ORDER_STATE_UNKNOWN"
            message.body shouldContain "클라이언트 주문 ID: vct-entry-001"
            message.body shouldContain "거래소 주문 ID: exchange-entry-001"
            message.body shouldContain "대시보드와 Bybit에서 주문·포지션을 확인"
            message.body shouldContain "실거래를 다시 켜지 마세요"
        }

        "safety exit alert explains the cause in Korean" {
            val base = liveResult(VolumeConfirmedTrendLiveEvaluationStatus.ORDER_SUBMITTED)
            val plan =
                VolumeConfirmedTrendTargetPlan(
                    action = VolumeConfirmedTrendTargetAction.CLOSE,
                    targetSide = Side.SELL,
                    orderSide = Side.SELL,
                    orderQuantity = BigDecimal("0.007"),
                    reduceOnly = true,
                    limitPrice = BigDecimal("59988"),
                    decisionKey = "safety-decision-001",
                    clientOrderId = "vct-s-s-001",
                    reasonCode =
                        "$TREND_SAFETY_HALT_EXIT_REASON_CODE_PREFIX|TREND_SIGNAL_FROM_FUTURE",
                )
            val state =
                base.evaluation.state.copy(
                    status = VolumeConfirmedTrendLiveStatus.EXIT_SUBMITTED,
                    activeDecisionKey = plan.decisionKey,
                    pendingTargetSide = plan.targetSide,
                    clientOrderId = plan.clientOrderId,
                    exchangeOrderId = "exchange-safety-001",
                    observedPositionSide = Side.BUY,
                    observedPositionQuantity = BigDecimal("0.007"),
                )
            val result =
                base.copy(
                    status = VolumeConfirmedTrendLiveLoopStatus.HALTED,
                    evaluation = base.evaluation.copy(state = state, plan = plan),
                )

            val message = requireNotNull(result.toTrendLiveAlertMessage())

            message.severity shouldBe AlertSeverity.CRITICAL
            message.title shouldBe "H4 안전 포지션 정리"
            message.body shouldContain "안전 조건 불일치"
            message.body shouldContain "원인: 신호 시각이 서버 시각보다 미래임 (TREND_SIGNAL_FROM_FUTURE)"
            message.body shouldContain "수량: 0.007"
        }
    })

private fun liveResult(
    status: VolumeConfirmedTrendLiveEvaluationStatus,
    riskReasonCodes: List<String> = emptyList(),
    recoveryReasonCode: String? = null,
    clientOrderId: String? = null,
    exchangeOrderId: String? = null,
    haltedReasonCode: String = "TREND_TEST_HALT",
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
            clientOrderId = clientOrderId,
            exchangeOrderId = exchangeOrderId,
            observedPositionSide = null,
            observedPositionQuantity = null,
            lastExecutionId = null,
            haltedReasonCode = if (halted) haltedReasonCode else null,
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
                recoveryReasonCode = recoveryReasonCode,
            ),
        evaluatedAt = state.updatedAt,
    )
}

private fun approvalBlockedResult(
    haltedReasonCode: String,
    clientOrderId: String,
    exchangeOrderId: String? = null,
): VolumeConfirmedTrendLiveLoopResult {
    val base = liveResult(VolumeConfirmedTrendLiveEvaluationStatus.APPROVAL_BLOCKED)
    val halted =
        base.evaluation.state.copy(
            status = VolumeConfirmedTrendLiveStatus.HALTED,
            clientOrderId = clientOrderId,
            exchangeOrderId = exchangeOrderId,
            haltedReasonCode = haltedReasonCode,
        )
    return base.copy(
        status = VolumeConfirmedTrendLiveLoopStatus.HALTED,
        evaluation =
            base.evaluation.copy(
                state = halted,
                approvalFailures = listOf(VolumeConfirmedTrendLiveApprovalFailure.RECEIPT_NOT_APPROVED),
            ),
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
