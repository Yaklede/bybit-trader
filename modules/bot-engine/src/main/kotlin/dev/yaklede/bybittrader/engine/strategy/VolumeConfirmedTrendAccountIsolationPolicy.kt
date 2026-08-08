package dev.yaklede.bybittrader.engine.strategy

import dev.yaklede.bybittrader.engine.execution.ExchangeAccountBalance
import dev.yaklede.bybittrader.engine.execution.ExchangeCoinBalance
import java.math.BigDecimal

data class VolumeConfirmedTrendAccountIsolationAssessment(
    val tradingEquity: BigDecimal?,
    val reasonCode: String?,
) {
    val allowsEntry: Boolean = tradingEquity != null && reasonCode == null
}

object VolumeConfirmedTrendAccountIsolationPolicy {
    fun assess(
        balance: ExchangeAccountBalance,
        settleCoin: String,
    ): VolumeConfirmedTrendAccountIsolationAssessment {
        require(settleCoin.matches(Regex("[A-Z0-9]{2,16}"))) {
            "Trend account isolation settle coin must be an uppercase asset code."
        }
        if (balance.accountTotalsUnavailable()) {
            return rejected("TREND_ACCOUNT_TOTALS_UNAVAILABLE")
        }
        if (balance.coins.any(ExchangeCoinBalance::inventoryFieldsUnavailable)) {
            return rejected("TREND_ACCOUNT_BALANCE_FIELDS_UNAVAILABLE")
        }

        val settlementBalances = balance.coins.filter { coin -> coin.coin == settleCoin }
        if (settlementBalances.size != 1) {
            return rejected("TREND_SETTLEMENT_BALANCE_UNAVAILABLE")
        }
        if (balance.coins.any(ExchangeCoinBalance::hasLiability)) {
            return rejected("TREND_ACCOUNT_LIABILITY_OBSERVED")
        }
        if (balance.coins.any { coin -> coin.locked.isNonZero() }) {
            return rejected("TREND_ACCOUNT_LOCKED_BALANCE_OBSERVED")
        }
        if (balance.coins.any { coin -> coin.spotHedgingQuantity.isNonZero() }) {
            return rejected("TREND_ACCOUNT_SPOT_HEDGE_OBSERVED")
        }
        if (balance.coins.any { coin -> coin.bonus.isNonZero() }) {
            return rejected("TREND_ACCOUNT_BONUS_OBSERVED")
        }
        if (
            balance.totalInitialMargin.isNonZero() ||
            balance.totalMaintenanceMargin.isNonZero() ||
            balance.totalPerpUnrealizedPnl.isNonZero()
        ) {
            return rejected("TREND_ACCOUNT_MARGIN_EXPOSURE_OBSERVED")
        }
        if (balance.coins.any { coin -> coin.coin != settleCoin && coin.hasCurrentExposure() }) {
            return rejected("TREND_FOREIGN_COLLATERAL_OBSERVED")
        }

        val settlementEquity = settlementBalances.single().equity
        val availableBalance = balance.totalAvailableBalance
        if (settlementEquity == null || availableBalance == null) {
            return rejected("TREND_SETTLEMENT_EQUITY_UNAVAILABLE")
        }
        val tradingEquity = minOf(settlementEquity, availableBalance)
        if (tradingEquity <= BigDecimal.ZERO) {
            return rejected("TREND_SETTLEMENT_EQUITY_UNAVAILABLE")
        }
        return VolumeConfirmedTrendAccountIsolationAssessment(
            tradingEquity = tradingEquity,
            reasonCode = null,
        )
    }

    private fun rejected(reasonCode: String): VolumeConfirmedTrendAccountIsolationAssessment =
        VolumeConfirmedTrendAccountIsolationAssessment(
            tradingEquity = null,
            reasonCode = reasonCode,
        )
}

private fun ExchangeAccountBalance.accountTotalsUnavailable(): Boolean =
    listOf(
        totalEquity,
        totalWalletBalance,
        totalMarginBalance,
        totalAvailableBalance,
        totalPerpUnrealizedPnl,
        totalInitialMargin,
        totalMaintenanceMargin,
    ).any { value -> value == null }

private fun ExchangeCoinBalance.inventoryFieldsUnavailable(): Boolean =
    coin.isBlank() ||
        listOf(
            equity,
            usdValue,
            walletBalance,
            locked,
            unrealizedPnl,
            borrowAmount,
            spotBorrow,
            accruedInterest,
            spotHedgingQuantity,
            bonus,
        ).any { value -> value == null }

private fun ExchangeCoinBalance.hasLiability(): Boolean = borrowAmount.isNonZero() || spotBorrow.isNonZero() || accruedInterest.isNonZero()

private fun ExchangeCoinBalance.hasCurrentExposure(): Boolean =
    listOf(
        equity,
        usdValue,
        walletBalance,
        locked,
        unrealizedPnl,
        borrowAmount,
        spotBorrow,
        accruedInterest,
        spotHedgingQuantity,
        bonus,
    ).any { value -> value.isNonZero() }

private fun BigDecimal?.isNonZero(): Boolean = this != null && compareTo(BigDecimal.ZERO) != 0
