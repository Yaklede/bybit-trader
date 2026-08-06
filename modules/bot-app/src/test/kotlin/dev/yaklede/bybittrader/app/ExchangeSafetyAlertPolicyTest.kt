package dev.yaklede.bybittrader.app

import dev.yaklede.bybittrader.alerts.AlertSeverity
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.execution.ExchangeSafetyAction
import dev.yaklede.bybittrader.engine.execution.ExchangeSafetyResult
import dev.yaklede.bybittrader.engine.execution.ExchangeSafetyStatus
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Instant

class ExchangeSafetyAlertPolicyTest :
    StringSpec({
        "reports a confirmed safe stop with exchange counts" {
            val message = safetyResult().toSafetyAlertMessage()

            message.severity shouldBe AlertSeverity.INFO
            message.title shouldBe "안전 정지 거래소 확인 완료"
            message.body shouldContain "신규 진입 주문 취소: 2건"
            message.body shouldContain "남은 포지션: 1건"
            message.body shouldContain "문제: 없음"
        }

        "reports a failed flatten with actionable issue details" {
            val message =
                safetyResult(
                    action = ExchangeSafetyAction.FLATTEN,
                    status = ExchangeSafetyStatus.FAILED,
                    remainingOpenOrderCount = null,
                    remainingPositionCount = null,
                    issueCodes = listOf("SAFETY_POSITION_CLOSE_FAILED", "SAFETY_VERIFICATION_UNAVAILABLE"),
                ).toSafetyAlertMessage()

            message.severity shouldBe AlertSeverity.CRITICAL
            message.title shouldBe "전량 종료 거래소 확인 실패"
            message.body shouldContain "포지션 종료 주문 제출 실패 (SAFETY_POSITION_CLOSE_FAILED)"
            message.body shouldContain "남은 활성 주문: 확인 불가"
            message.body shouldContain "수동으로 정리"
        }
    })

private fun safetyResult(
    action: ExchangeSafetyAction = ExchangeSafetyAction.SAFE_STOP,
    status: ExchangeSafetyStatus = ExchangeSafetyStatus.CONFIRMED,
    remainingOpenOrderCount: Int? = 0,
    remainingPositionCount: Int? = 1,
    issueCodes: List<String> = emptyList(),
): ExchangeSafetyResult =
    ExchangeSafetyResult(
        action = action,
        status = status,
        mode = "PAUSE_ALL",
        symbol = Symbol("BTCUSDT"),
        requestedAt = Instant.parse("2026-08-06T00:00:00Z"),
        verifiedAt = Instant.parse("2026-08-06T00:00:01Z"),
        cancelledEntryOrderCount = 2,
        submittedCloseOrderCount = 0,
        protectedPositionCount = 1,
        remainingOpenOrderCount = remainingOpenOrderCount,
        remainingPositionCount = remainingPositionCount,
        issueCodes = issueCodes,
    )
