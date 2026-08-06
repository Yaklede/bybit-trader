package dev.yaklede.bybittrader.app

import dev.yaklede.bybittrader.alerts.AlertSeverity
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.execution.ExchangeEvaluationResult
import dev.yaklede.bybittrader.engine.execution.ExchangeEvaluationStatus
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Instant

class ExecutionRiskAlertPolicyTest :
    StringSpec({
        "emits one Korean warning for the same blocking reason" {
            val policy = ExecutionRiskAlertPolicy()
            val result = evaluationResult(listOf("RISK_NAV_BASELINE_PENDING"))

            val first = policy.messages(result)
            val repeated = policy.messages(result)

            first.single().severity shouldBe AlertSeverity.WARNING
            first.single().title shouldBe "신규 진입 자동 차단"
            first.single().body shouldContain "현금흐름 조정 NAV 기준점을 수집 중"
            repeated shouldBe emptyList()
        }

        "escalates when the blocking fingerprint changes to a confirmed mismatch" {
            val policy = ExecutionRiskAlertPolicy()
            policy.messages(evaluationResult(listOf("ACCOUNT_LEDGER_MISMATCH_PENDING")))

            val escalated = policy.messages(evaluationResult(listOf("ACCOUNT_LEDGER_MISMATCH_CONFIRMED")))

            escalated.single().severity shouldBe AlertSeverity.CRITICAL
            escalated.single().body shouldContain "반복 확인됨"
        }

        "emits one recovery message after risk reasons clear" {
            val policy = ExecutionRiskAlertPolicy()
            policy.messages(evaluationResult(listOf("DAILY_EQUITY_LOSS_LIMIT_REACHED")))

            val recovered = policy.messages(evaluationResult(listOf("NO_SIGNAL")))
            val repeated = policy.messages(evaluationResult(listOf("NO_SIGNAL")))

            recovered.single().severity shouldBe AlertSeverity.INFO
            recovered.single().title shouldBe "신규 진입 차단 해제"
            repeated shouldBe emptyList()
        }

        "ignores ordinary no-trade reasons" {
            ExecutionRiskAlertPolicy()
                .messages(evaluationResult(listOf("INSUFFICIENT_CLOSED_CANDLE_HISTORY"))) shouldBe emptyList()
        }
    })

private fun evaluationResult(reasonCodes: List<String>): ExchangeEvaluationResult =
    ExchangeEvaluationResult(
        symbol = Symbol("BTCUSDT"),
        timeframe = Timeframe.M5,
        mode = "RUNNING",
        status = ExchangeEvaluationStatus.NO_TRADE,
        evaluatedAt = Instant.parse("2026-08-06T00:00:00Z"),
        candleCount = 0,
        reasonCodes = reasonCodes,
        signalId = null,
        orderId = null,
        exchangeOrderId = null,
        clientOrderId = null,
        entryPrice = null,
        takeProfit = null,
        stopLoss = null,
        quantity = null,
        intendedRisk = null,
    )
