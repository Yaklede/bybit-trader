package dev.yaklede.bybittrader.app

import dev.yaklede.bybittrader.alerts.AlertMessage
import dev.yaklede.bybittrader.alerts.AlertSeverity
import dev.yaklede.bybittrader.alerts.AlertingService
import dev.yaklede.bybittrader.engine.execution.ExchangeSafetyAction
import dev.yaklede.bybittrader.engine.execution.ExchangeSafetyResult
import dev.yaklede.bybittrader.engine.execution.ExchangeSafetyStatus

internal suspend fun AlertingService.sendExchangeSafetyResult(result: ExchangeSafetyResult) {
    send(result.toSafetyAlertMessage())
}

internal fun ExchangeSafetyResult.toSafetyAlertMessage(): AlertMessage {
    val actionLabel =
        when (action) {
            ExchangeSafetyAction.SAFE_STOP -> "안전 정지"
            ExchangeSafetyAction.FLATTEN -> "전량 종료"
        }
    val statusLabel =
        when (status) {
            ExchangeSafetyStatus.CONFIRMED -> "거래소 확인 완료"
            ExchangeSafetyStatus.PENDING -> "거래소 확인 대기"
            ExchangeSafetyStatus.FAILED -> "거래소 확인 실패"
        }
    val issueSummary =
        if (issueCodes.isEmpty()) {
            "없음"
        } else {
            issueCodes.joinToString("; ") { code -> "${code.toSafetyIssueLabel()} ($code)" }
        }
    val nextAction =
        when (status) {
            ExchangeSafetyStatus.CONFIRMED -> "추가 조치는 필요하지 않아요."
            ExchangeSafetyStatus.PENDING -> "자동 재확인이 진행됩니다. Bybit에서 주문과 포지션이 정리됐는지 확인해 주세요."
            ExchangeSafetyStatus.FAILED -> "Bybit에서 미체결 주문과 포지션을 즉시 확인하고 필요하면 수동으로 정리해 주세요."
        }
    return AlertMessage(
        severity =
            when (status) {
                ExchangeSafetyStatus.CONFIRMED ->
                    if (action == ExchangeSafetyAction.FLATTEN) AlertSeverity.WARNING else AlertSeverity.INFO

                ExchangeSafetyStatus.PENDING -> AlertSeverity.WARNING
                ExchangeSafetyStatus.FAILED -> AlertSeverity.CRITICAL
            },
        title = "$actionLabel $statusLabel",
        body =
            "${symbol.value} $actionLabel 결과입니다. " +
                "신규 진입 주문 취소: ${cancelledEntryOrderCount}건, " +
                "포지션 종료 주문 제출: ${submittedCloseOrderCount}건, " +
                "보호된 포지션: ${protectedPositionCount}건, " +
                "남은 활성 주문: ${remainingOpenOrderCount.toCountLabel()}, " +
                "남은 포지션: ${remainingPositionCount.toCountLabel()}. " +
                "문제: $issueSummary. $nextAction",
    )
}

private fun Int?.toCountLabel(): String = this?.let { "${it}건" } ?: "확인 불가"

private fun String.toSafetyIssueLabel(): String =
    when (this) {
        "SAFETY_SNAPSHOT_UNAVAILABLE" -> "조치 전 거래소 주문·포지션 조회 실패"
        "SAFETY_VERIFICATION_UNAVAILABLE" -> "조치 후 거래소 상태 재조회 실패"
        "SAFETY_ORDER_CANCEL_FAILED" -> "미체결 진입 주문 취소 실패"
        "SAFETY_POSITION_CLOSE_FAILED" -> "포지션 종료 주문 제출 실패"
        "SAFETY_MULTIPLE_ACTIVE_POSITIONS_UNSUPPORTED" -> "여러 활성 포지션을 자동 정리할 수 없음"
        "SAFETY_VERIFICATION_PENDING" -> "거래소의 주문·포지션 정리 확인 대기"
        else -> "분류되지 않은 안전 조치 오류"
    }
