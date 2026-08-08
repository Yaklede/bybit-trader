package dev.yaklede.bybittrader.engine.strategy

import dev.yaklede.bybittrader.engine.execution.ExchangeAccountBalance
import dev.yaklede.bybittrader.engine.execution.ExchangeCoinBalance
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class VolumeConfirmedTrendAccountIsolationPolicyTest :
    StringSpec({
        "dedicated USDT account caps trading equity at available balance" {
            val assessment =
                VolumeConfirmedTrendAccountIsolationPolicy.assess(
                    balance = balance(settlementEquity = "660", availableBalance = "600"),
                    settleCoin = "USDT",
                )

            assessment.allowsEntry shouldBe true
            assessment.tradingEquity shouldBe BigDecimal("600")
            assessment.reasonCode shouldBe null
        }

        "missing liability metadata fails closed" {
            val assessment =
                VolumeConfirmedTrendAccountIsolationPolicy.assess(
                    balance =
                        balance().copy(
                            coins = listOf(coin("USDT", "660").copy(borrowAmount = null)),
                        ),
                    settleCoin = "USDT",
                )

            assessment.allowsEntry shouldBe false
            assessment.reasonCode shouldBe "TREND_ACCOUNT_BALANCE_FIELDS_UNAVAILABLE"
        }

        "locked spot funds fail before entry" {
            val assessment =
                VolumeConfirmedTrendAccountIsolationPolicy.assess(
                    balance =
                        balance().copy(
                            coins = listOf(coin("USDT", "660").copy(locked = BigDecimal.ONE)),
                        ),
                    settleCoin = "USDT",
                )

            assessment.reasonCode shouldBe "TREND_ACCOUNT_LOCKED_BALANCE_OBSERVED"
        }

        "residual account margin exposure fails before entry" {
            val assessment =
                VolumeConfirmedTrendAccountIsolationPolicy.assess(
                    balance = balance().copy(totalInitialMargin = BigDecimal("0.01")),
                    settleCoin = "USDT",
                )

            assessment.reasonCode shouldBe "TREND_ACCOUNT_MARGIN_EXPOSURE_OBSERVED"
        }

        "non-USDT collateral fails before entry" {
            val assessment =
                VolumeConfirmedTrendAccountIsolationPolicy.assess(
                    balance = balance().copy(coins = listOf(coin("USDT", "660"), coin("USDC", "10"))),
                    settleCoin = "USDT",
                )

            assessment.reasonCode shouldBe "TREND_FOREIGN_COLLATERAL_OBSERVED"
        }
    })

private fun balance(
    settlementEquity: String = "660",
    availableBalance: String = settlementEquity,
): ExchangeAccountBalance =
    ExchangeAccountBalance(
        accountType = "UNIFIED",
        totalEquity = BigDecimal(settlementEquity),
        totalWalletBalance = BigDecimal(settlementEquity),
        totalMarginBalance = BigDecimal(settlementEquity),
        totalAvailableBalance = BigDecimal(availableBalance),
        totalPerpUnrealizedPnl = BigDecimal.ZERO,
        totalInitialMargin = BigDecimal.ZERO,
        totalMaintenanceMargin = BigDecimal.ZERO,
        coins = listOf(coin("USDT", settlementEquity)),
        capturedAt = Instant.parse("2026-08-08T00:00:00Z"),
    )

private fun coin(
    name: String,
    equity: String,
): ExchangeCoinBalance =
    ExchangeCoinBalance(
        coin = name,
        equity = BigDecimal(equity),
        usdValue = BigDecimal(equity),
        walletBalance = BigDecimal(equity),
        locked = BigDecimal.ZERO,
        unrealizedPnl = BigDecimal.ZERO,
        cumulativeRealizedPnl = BigDecimal.ZERO,
        borrowAmount = BigDecimal.ZERO,
        spotBorrow = BigDecimal.ZERO,
        accruedInterest = BigDecimal.ZERO,
        spotHedgingQuantity = BigDecimal.ZERO,
        bonus = BigDecimal.ZERO,
    )
