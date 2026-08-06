package dev.yaklede.bybittrader.engine.execution

import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

class ExecutionRiskCircuitBreakerTest :
    StringSpec({
        "initializes equity baselines and advances consecutive losses once per closure" {
            val first =
                ExecutionRiskCircuitBreaker.update(
                    previous = null,
                    snapshot = riskSnapshot("100", "2026-08-06T00:05:00Z"),
                    newClosures = listOf(riskClosure(1, "-2"), riskClosure(2, "-1")),
                )

            first?.peakEquity shouldBe BigDecimal("100")
            first?.dayStartEquity shouldBe BigDecimal("100")
            first?.latestEquity shouldBe BigDecimal("100")
            first?.consecutiveLosses shouldBe 2
            first?.lastClosureId shouldBe 2L

            val repeated =
                ExecutionRiskCircuitBreaker.update(
                    previous = first,
                    snapshot = riskSnapshot("99", "2026-08-06T00:06:00Z"),
                    newClosures = listOf(riskClosure(1, "-2"), riskClosure(2, "-1")),
                )

            repeated?.consecutiveLosses shouldBe 2
            repeated?.lastClosureId shouldBe 2L
        }

        "resets the daily baseline on a UTC day boundary and a win resets consecutive losses" {
            val previous =
                ExecutionRiskState(
                    mode = ExecutionRuntimeMode.LIVE,
                    peakEquity = BigDecimal("120"),
                    utcDayStartedAt = Instant.parse("2026-08-05T00:00:00Z"),
                    dayStartEquity = BigDecimal("110"),
                    latestEquity = BigDecimal("100"),
                    consecutiveLosses = 3,
                    lastClosureId = 3,
                    updatedAt = Instant.parse("2026-08-05T23:59:00Z"),
                )

            val updated =
                ExecutionRiskCircuitBreaker.update(
                    previous = previous,
                    snapshot = riskSnapshot("101", "2026-08-06T00:01:00Z"),
                    newClosures = listOf(riskClosure(4, "1")),
                )

            updated?.peakEquity shouldBe BigDecimal("120")
            updated?.utcDayStartedAt shouldBe Instant.parse("2026-08-06T00:00:00Z")
            updated?.dayStartEquity shouldBe BigDecimal("101")
            updated?.consecutiveLosses shouldBe 0
        }

        "blocks stale state and every breached account limit" {
            val now = Instant.parse("2026-08-06T12:00:00Z")
            val stale =
                riskDecision(
                    state = riskState(updatedAt = now.minusSeconds(121)),
                    now = now,
                )
            stale.reasonCodes shouldBe listOf("RISK_STATE_STALE")

            val breached =
                riskDecision(
                    state =
                        riskState(
                            peakEquity = "100",
                            dayStartEquity = "90",
                            latestEquity = "75",
                            consecutiveLosses = 3,
                            updatedAt = now,
                        ),
                    now = now,
                )
            breached.reasonCodes shouldBe
                listOf(
                    "DAILY_EQUITY_LOSS_LIMIT_REACHED",
                    "ACCOUNT_DRAWDOWN_LIMIT_REACHED",
                    "CONSECUTIVE_LOSS_LIMIT_REACHED",
                )
        }

        "allows a fresh state below every threshold" {
            val now = Instant.parse("2026-08-06T12:00:00Z")
            riskDecision(
                state =
                    riskState(
                        peakEquity = "100",
                        dayStartEquity = "99",
                        latestEquity = "98",
                        consecutiveLosses = 2,
                        updatedAt = now,
                    ),
                now = now,
            ).allowsEntry shouldBe true
        }
    })

private fun riskDecision(
    state: ExecutionRiskState?,
    now: Instant,
): ExecutionRiskDecision =
    ExecutionRiskCircuitBreaker.evaluate(
        state = state,
        now = now,
        maximumAge = Duration.ofSeconds(120),
        maximumDailyLossFraction = BigDecimal("0.03"),
        maximumAccountDrawdownFraction = BigDecimal("0.20"),
        maximumConsecutiveLosses = 3,
    )

private fun riskState(
    peakEquity: String = "100",
    dayStartEquity: String = "100",
    latestEquity: String = "100",
    consecutiveLosses: Int = 0,
    updatedAt: Instant,
): ExecutionRiskState =
    ExecutionRiskState(
        mode = ExecutionRuntimeMode.LIVE,
        peakEquity = BigDecimal(peakEquity),
        utcDayStartedAt = Instant.parse("2026-08-06T00:00:00Z"),
        dayStartEquity = BigDecimal(dayStartEquity),
        latestEquity = BigDecimal(latestEquity),
        consecutiveLosses = consecutiveLosses,
        lastClosureId = null,
        updatedAt = updatedAt,
    )

private fun riskSnapshot(
    equity: String,
    capturedAt: String,
): ExecutionAccountSnapshot =
    ExecutionAccountSnapshot(
        mode = ExecutionRuntimeMode.LIVE,
        accountType = "UNIFIED",
        totalEquity = BigDecimal(equity),
        totalWalletBalance = BigDecimal(equity),
        totalMarginBalance = BigDecimal(equity),
        totalAvailableBalance = BigDecimal(equity),
        totalPerpUnrealizedPnl = BigDecimal.ZERO,
        capturedAt = Instant.parse(capturedAt),
    )

private fun riskClosure(
    id: Long,
    netPnl: String,
): ExecutionTradeClosure =
    ExecutionTradeClosure(
        id = id,
        mode = ExecutionRuntimeMode.LIVE,
        symbol = Symbol("BTCUSDT"),
        side = Side.BUY,
        openedAt = Instant.parse("2026-08-06T00:00:00Z"),
        closedAt = Instant.parse("2026-08-06T00:05:00Z").plusSeconds(id),
        entryPrice = BigDecimal("100"),
        exitPrice = BigDecimal("99"),
        quantity = BigDecimal.ONE,
        grossPnl = BigDecimal(netPnl),
        fees = BigDecimal.ZERO,
        netPnl = BigDecimal(netPnl),
        exitReason = "STOP_LOSS",
        exchangeOrderId = "exchange-$id",
        clientOrderId = "client-$id",
    )
