package dev.yaklede.bybittrader.app

import dev.yaklede.bybittrader.alerts.AlertMessage
import dev.yaklede.bybittrader.alerts.AlertSeverity
import dev.yaklede.bybittrader.engine.execution.ExchangeEvaluationResult

internal class ExecutionRiskAlertPolicy {
    private var activeFingerprint: String? = null

    fun messages(result: ExchangeEvaluationResult): List<AlertMessage> {
        val reasons =
            result.reasonCodes
                .filter(EXECUTION_ENTRY_RISK_REASON_CODES::contains)
                .distinct()
                .sorted()
        if (reasons.isEmpty()) {
            if (activeFingerprint == null) return emptyList()
            activeFingerprint = null
            return listOf(
                AlertMessage(
                    severity = AlertSeverity.INFO,
                    title = "신규 진입 차단 해제",
                    body =
                        "${result.symbol.value} 계좌 위험 상태와 거래 원장 대사가 다시 정상 범위에 들어왔어요. " +
                            "다음 전략 신호부터 신규 진입을 다시 평가해요.",
                ),
            )
        }

        val fingerprint = reasons.joinToString("|")
        if (fingerprint == activeFingerprint) return emptyList()
        activeFingerprint = fingerprint
        val severity =
            if (reasons.any(CRITICAL_EXECUTION_ENTRY_RISK_REASON_CODES::contains)) {
                AlertSeverity.CRITICAL
            } else {
                AlertSeverity.WARNING
            }
        return listOf(
            AlertMessage(
                severity = severity,
                title = "신규 진입 자동 차단",
                body =
                    buildString {
                        append("${result.symbol.value} ${result.timeframe.name} 신규 진입을 중단했어요.\n")
                        reasons.forEach { reason -> append("- ${reason.toKoreanRiskReason()} ($reason)\n") }
                        append("기존 포지션의 보호·종료 관리는 계속 실행돼요. 대시보드와 거래소 원장을 확인해 주세요.")
                    },
            ),
        )
    }
}

private fun String.toKoreanRiskReason(): String =
    when (this) {
        "RISK_STATE_STORE_UNAVAILABLE" -> "위험 상태 저장소를 사용할 수 없음"
        "RISK_STATE_UNAVAILABLE" -> "계좌 위험 기준점이 없음"
        "RISK_STATE_STALE" -> "계좌 위험 상태가 오래됨"
        "RISK_STATE_CLOCK_SKEW" -> "계좌 위험 상태 시각이 서버 시각과 맞지 않음"
        "RISK_NAV_UNAVAILABLE" -> "현금흐름 조정 NAV를 아직 계산할 수 없음"
        "RISK_NAV_BASELINE_PENDING" -> "현금흐름 조정 NAV 기준점을 수집 중"
        "RISK_NAV_INVALID" -> "현금흐름 조정 NAV 계산이 유효하지 않음"
        "DAILY_EQUITY_LOSS_LIMIT_REACHED" -> "UTC 기준 당일 손실 한도 도달"
        "ACCOUNT_DRAWDOWN_LIMIT_REACHED" -> "계좌 최대 낙폭 한도 도달"
        "CONSECUTIVE_LOSS_LIMIT_REACHED" -> "연속 손실 한도 도달"
        "ACCOUNT_RECONCILIATION_UNAVAILABLE" -> "지갑-거래 원장 대사 상태가 없음"
        "ACCOUNT_RECONCILIATION_CLOCK_SKEW" -> "지갑-거래 원장 대사 시각이 맞지 않음"
        "ACCOUNT_RECONCILIATION_STALE" -> "지갑-거래 원장 대사 상태가 오래됨"
        "ACCOUNT_RECONCILIATION_BASELINE_PENDING" -> "지갑-거래 원장 기준점을 수집 중"
        "ACCOUNT_TRANSACTION_SYNC_UNAVAILABLE" -> "Bybit 거래내역 동기화 실패"
        "ACCOUNT_WALLET_DATA_UNAVAILABLE" -> "USDT 지갑 잔고 데이터가 없음"
        "ACCOUNT_LEDGER_MISMATCH_PENDING" -> "지갑 변화와 거래 원장이 일시적으로 불일치"
        "ACCOUNT_LEDGER_MISMATCH_CONFIRMED" -> "지갑 변화와 거래 원장 불일치가 반복 확인됨"
        else -> this
    }

private val EXECUTION_ENTRY_RISK_REASON_CODES =
    setOf(
        "RISK_STATE_STORE_UNAVAILABLE",
        "RISK_STATE_UNAVAILABLE",
        "RISK_STATE_STALE",
        "RISK_STATE_CLOCK_SKEW",
        "RISK_NAV_UNAVAILABLE",
        "RISK_NAV_BASELINE_PENDING",
        "RISK_NAV_INVALID",
        "DAILY_EQUITY_LOSS_LIMIT_REACHED",
        "ACCOUNT_DRAWDOWN_LIMIT_REACHED",
        "CONSECUTIVE_LOSS_LIMIT_REACHED",
        "ACCOUNT_RECONCILIATION_UNAVAILABLE",
        "ACCOUNT_RECONCILIATION_CLOCK_SKEW",
        "ACCOUNT_RECONCILIATION_STALE",
        "ACCOUNT_RECONCILIATION_BASELINE_PENDING",
        "ACCOUNT_TRANSACTION_SYNC_UNAVAILABLE",
        "ACCOUNT_WALLET_DATA_UNAVAILABLE",
        "ACCOUNT_LEDGER_MISMATCH_PENDING",
        "ACCOUNT_LEDGER_MISMATCH_CONFIRMED",
    )

private val CRITICAL_EXECUTION_ENTRY_RISK_REASON_CODES =
    setOf(
        "RISK_NAV_INVALID",
        "DAILY_EQUITY_LOSS_LIMIT_REACHED",
        "ACCOUNT_DRAWDOWN_LIMIT_REACHED",
        "CONSECUTIVE_LOSS_LIMIT_REACHED",
        "ACCOUNT_TRANSACTION_SYNC_UNAVAILABLE",
        "ACCOUNT_WALLET_DATA_UNAVAILABLE",
        "ACCOUNT_LEDGER_MISMATCH_CONFIRMED",
    )
