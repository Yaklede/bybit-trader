package dev.yaklede.bybittrader.app

import dev.yaklede.bybittrader.alerts.AlertMessage
import dev.yaklede.bybittrader.alerts.AlertSeverity
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalGate
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalGateStatus
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalReport
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalStatus
import java.util.Locale
import kotlin.math.abs

class VolumeConfirmedTrendApprovalAlertPolicy {
    private var activeFingerprint: String? = null

    @Synchronized
    fun shouldAlert(report: VolumeConfirmedTrendApprovalReport): Boolean {
        val fingerprint =
            buildString {
                append(report.sessionId ?: "NO_SESSION")
                append('|')
                append(report.status.name)
                report.gates.forEach { gate ->
                    append('|')
                    append(gate.id)
                    append(':')
                    append(gate.status.name)
                }
            }
        if (fingerprint == activeFingerprint) return false
        activeFingerprint = fingerprint
        return true
    }
}

fun VolumeConfirmedTrendApprovalReport.toOperatorAlert(): AlertMessage {
    val incompleteGates = gates.filter { gate -> gate.status != VolumeConfirmedTrendApprovalGateStatus.PASS }
    val gateSummary =
        if (incompleteGates.isEmpty()) {
            "없음"
        } else {
            incompleteGates
                .take(5)
                .joinToString(", ") { gate -> "${gate.operatorLabel()} ${gate.actual} (기준 ${gate.required})" }
                .let { summary ->
                    if (incompleteGates.size > 5) "$summary 외 ${incompleteGates.size - 5}개" else summary
                }
        }
    val metrics =
        "세션: ${sessionId ?: "없음"}. " +
            "관측: ${observedCalendarDays.operatorDecimal()}일. " +
            "누적 수익률: ${sessionReturnPct?.operatorDecimal()?.let { "$it%" } ?: "계산 전"}. " +
            "종료 거래 손익비: ${closedTradeProfitFactor?.operatorDecimal() ?: "계산 전"}. " +
            "남은 조건: $gateSummary."
    val orderBoundary = " 자동 주문과 실거래 주문은 계속 차단돼 있어요."
    return when (status) {
        VolumeConfirmedTrendApprovalStatus.READY_FOR_HUMAN_REVIEW ->
            AlertMessage(
                severity = AlertSeverity.INFO,
                title = "4시간 전략 검토 준비 완료",
                body =
                    "$metrics 모든 정량 게이트를 통과했어요. 승인 증거를 내보낸 뒤 사람이 별도로 검토해야 해요." +
                        orderBoundary,
            )
        VolumeConfirmedTrendApprovalStatus.SHADOW_STALE ->
            AlertMessage(
                severity = AlertSeverity.WARNING,
                title = "4시간 전략 데이터가 늦고 있어요",
                body = "$metrics 최근 관측 시각과 데이터 수집 상태를 확인해 주세요.$orderBoundary",
            )
        VolumeConfirmedTrendApprovalStatus.SHADOW_SESSION_FAILED ->
            AlertMessage(
                severity = AlertSeverity.CRITICAL,
                title = "4시간 전략 검증을 중단했어요",
                body = "$metrics 통과하지 못한 위험·연속성 조건을 확인하고 현재 세션을 승인에 사용하지 마세요.$orderBoundary",
            )
        VolumeConfirmedTrendApprovalStatus.HISTORICAL_EVIDENCE_REJECTED,
        VolumeConfirmedTrendApprovalStatus.RUNTIME_PARITY_REQUIRED,
        ->
            AlertMessage(
                severity = AlertSeverity.CRITICAL,
                title = "4시간 전략을 승인할 수 없어요",
                body = "$metrics 고정된 역사 증거 또는 실행 패리티가 유효하지 않아요.$orderBoundary",
            )
        VolumeConfirmedTrendApprovalStatus.SHADOW_DISABLED,
        VolumeConfirmedTrendApprovalStatus.SHADOW_NOT_STARTED,
        VolumeConfirmedTrendApprovalStatus.SHADOW_BOOTSTRAPPING,
        ->
            AlertMessage(
                severity = AlertSeverity.WARNING,
                title = "4시간 전략 시작을 확인해 주세요",
                body = "$metrics 가상 검증 초기화와 첫 저장 기록을 확인해 주세요.$orderBoundary",
            )
        VolumeConfirmedTrendApprovalStatus.SHADOW_COLLECTING ->
            AlertMessage(
                severity = AlertSeverity.INFO,
                title = "4시간 전략을 검증하고 있어요",
                body = "$metrics 현재 세션을 유지하면서 남은 조건을 관측해요.$orderBoundary",
            )
    }
}

private fun VolumeConfirmedTrendApprovalGate.operatorLabel(): String =
    when (id) {
        "EXTERNAL_VENUE_HISTORY" -> "외부 거래소 이력"
        "KOTLIN_CORE_PARITY" -> "계산 코어 일치"
        "RUNTIME_REPLAY_PARITY" -> "실행 결과 일치"
        "FRESH_SHADOW_DAYS" -> "연속 관측 기간"
        "CLOSED_TRADES" -> "종료 거래 수"
        "EXECUTED_TRANSITIONS" -> "포지션 전환 수"
        "SESSION_RETURN_PCT" -> "세션 누적 수익률"
        "CLOSED_TRADE_PROFIT_FACTOR" -> "종료 거래 손익비"
        "MAXIMUM_DRAWDOWN_PCT" -> "최대 손실폭"
        "MAXIMUM_ENTRY_EXPOSURE_FRACTION" -> "진입 노출"
        "MAXIMUM_ADVERSE_EXPOSURE_FRACTION" -> "최대 불리 노출"
        "LIQUIDATION_COUNT" -> "청산 횟수"
        "OBSERVATION_STALENESS_SECONDS" -> "최근 관측 지연"
        "CURRENT_SESSION_CONTINUITY" -> "현재 세션 연속성"
        else -> "검증 조건"
    }

private fun Double.operatorDecimal(): String =
    when {
        !isFinite() -> toString()
        abs(this) >= 1_000_000 -> String.format(Locale.US, "%.4e", this)
        else -> String.format(Locale.US, "%.4f", this).trimEnd('0').trimEnd('.')
    }
