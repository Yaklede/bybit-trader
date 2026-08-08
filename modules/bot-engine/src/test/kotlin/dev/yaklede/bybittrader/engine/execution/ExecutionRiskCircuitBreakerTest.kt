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

        "unitizes deposits and withdrawals without changing strategy nav" {
            val baselineAt = Instant.parse("2026-08-06T00:00:00Z")
            val baseline =
                ExecutionRiskCircuitBreaker.update(
                    previous = null,
                    snapshot = riskSnapshot("100", baselineAt.toString()),
                    newClosures = emptyList(),
                    accountTransactions = emptyList(),
                )!!
            val deposit =
                ExecutionRiskCircuitBreaker.update(
                    previous = baseline,
                    snapshot = riskSnapshot("200", baselineAt.plusSeconds(60).toString()),
                    newClosures = emptyList(),
                    accountTransactions =
                        listOf(
                            riskTransaction(
                                id = 1,
                                type = "TRANSFER_IN",
                                change = "100",
                                transactionAt = baselineAt.plusSeconds(30),
                            ),
                        ),
                )!!
            val withdrawal =
                ExecutionRiskCircuitBreaker.update(
                    previous = deposit,
                    snapshot = riskSnapshot("150", baselineAt.plusSeconds(120).toString()),
                    newClosures = emptyList(),
                    accountTransactions =
                        listOf(
                            riskTransaction(
                                id = 2,
                                type = "TRANSFER_OUT",
                                change = "-50",
                                transactionAt = baselineAt.plusSeconds(90),
                            ),
                        ),
                )!!

            baseline.navStatus shouldBe ExecutionRiskNavStatus.BASELINE
            deposit.navStatus shouldBe ExecutionRiskNavStatus.READY
            deposit.latestUnitizedNav shouldBe BigDecimal.ONE
            deposit.strategyUnits shouldBe BigDecimal("200")
            withdrawal.latestUnitizedNav.compareTo(BigDecimal.ONE) shouldBe 0
            withdrawal.cumulativeExternalCashFlow shouldBe BigDecimal("50")
            walletRiskDecision(withdrawal, baselineAt.plusSeconds(120)).allowsEntry shouldBe true
        }

        "detects a trading loss after a deposit from unitized nav" {
            val now = Instant.parse("2026-08-06T00:00:00Z")
            val baseline =
                ExecutionRiskCircuitBreaker.update(
                    previous = null,
                    snapshot = riskSnapshot("100", now.toString()),
                    newClosures = emptyList(),
                )!!
            val funded =
                ExecutionRiskCircuitBreaker.update(
                    previous = baseline,
                    snapshot = riskSnapshot("200", now.plusSeconds(60).toString()),
                    newClosures = emptyList(),
                    accountTransactions =
                        listOf(riskTransaction(1, "TRANSFER_IN", "100", now.plusSeconds(30))),
                )!!
            val loss =
                ExecutionRiskCircuitBreaker.update(
                    previous = funded,
                    snapshot = riskSnapshot("180", now.plusSeconds(120).toString()),
                    newClosures = emptyList(),
                    accountTransactions =
                        listOf(riskTransaction(2, "TRADE", "-20", now.plusSeconds(90))),
                )!!

            loss.latestUnitizedNav.compareTo(BigDecimal("0.9")) shouldBe 0
            loss.peakUnitizedNav shouldBe BigDecimal.ONE
            walletRiskDecision(loss, now.plusSeconds(120)).reasonCodes shouldBe
                listOf("DAILY_EQUITY_LOSS_LIMIT_REACHED")
        }

        "uses transaction ids as an external cash flow checkpoint" {
            val now = Instant.parse("2026-08-06T00:00:00Z")
            val baseline =
                ExecutionRiskCircuitBreaker.update(
                    previous = null,
                    snapshot = riskSnapshot("100", now.toString()),
                    newClosures = emptyList(),
                    accountTransactions =
                        listOf(riskTransaction(7, "TRANSFER_IN", "100", now.minusSeconds(30))),
                )!!
            val replayed =
                ExecutionRiskCircuitBreaker.update(
                    previous = baseline,
                    snapshot = riskSnapshot("100", now.plusSeconds(60).toString()),
                    newClosures = emptyList(),
                    accountTransactions =
                        listOf(riskTransaction(7, "TRANSFER_IN", "100", now.minusSeconds(30))),
                )!!

            baseline.lastAccountTransactionId shouldBe 7L
            replayed.cumulativeExternalCashFlow shouldBe BigDecimal.ZERO
            replayed.latestUnitizedNav shouldBe BigDecimal.ONE
        }

        "blocks baseline and invalid unitized nav states" {
            val now = Instant.parse("2026-08-06T12:00:00Z")
            val baseline = riskState(updatedAt = now).copy(navStatus = ExecutionRiskNavStatus.BASELINE)
            walletRiskDecision(baseline, now).reasonCodes shouldBe listOf("RISK_NAV_BASELINE_PENDING")
            walletRiskDecision(
                baseline.copy(navStatus = ExecutionRiskNavStatus.INVALID),
                now,
            ).reasonCodes shouldBe listOf("RISK_NAV_INVALID")
        }

        "frozen trend policy evaluates only account drawdown and fails closed before nav is ready" {
            val now = Instant.parse("2026-08-06T12:00:00Z")
            val baseline = riskState(updatedAt = now).copy(navStatus = ExecutionRiskNavStatus.BASELINE)
            val baselineDecision =
                ExecutionRiskCircuitBreaker
                    .evaluateAccountDrawdown(
                        state = baseline,
                        now = now,
                        maximumAge = Duration.ofMinutes(10),
                        maximumAccountDrawdownFraction = BigDecimal("0.35"),
                    )
            baselineDecision.reasonCodes shouldBe listOf("RISK_NAV_BASELINE_PENDING")

            val breached =
                baseline.copy(
                    navStatus = ExecutionRiskNavStatus.READY,
                    consecutiveLosses = 99,
                    latestUnitizedNav = BigDecimal("0.64"),
                    peakUnitizedNav = BigDecimal.ONE,
                    dayStartUnitizedNav = BigDecimal("0.64"),
                )
            val breachedDecision =
                ExecutionRiskCircuitBreaker
                    .evaluateAccountDrawdown(
                        state = breached,
                        now = now,
                        maximumAge = Duration.ofMinutes(10),
                        maximumAccountDrawdownFraction = BigDecimal("0.35"),
                    )
            breachedDecision.reasonCodes shouldBe listOf("ACCOUNT_DRAWDOWN_LIMIT_REACHED")
        }
    })

private fun walletRiskDecision(
    state: ExecutionRiskState,
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

private fun riskTransaction(
    id: Long,
    type: String,
    change: String,
    transactionAt: Instant,
): ExecutionAccountTransactionEvent =
    ExecutionAccountTransactionEvent(
        id = id,
        mode = ExecutionRuntimeMode.LIVE,
        transaction =
            ExchangeAccountTransaction(
                transactionId = "transaction-$id",
                symbol = Symbol("BTCUSDT"),
                category = "linear",
                side = Side.BUY,
                transactionAt = transactionAt,
                type = type,
                subtype = null,
                quantity = null,
                size = null,
                currency = "USDT",
                tradePrice = null,
                funding = BigDecimal.ZERO,
                fee = BigDecimal.ZERO,
                cashFlow = BigDecimal(change),
                change = BigDecimal(change),
                cashBalance = null,
                feeRate = null,
                tradeId = null,
                exchangeOrderId = null,
                clientOrderId = null,
            ),
        receivedAt = transactionAt,
    )
