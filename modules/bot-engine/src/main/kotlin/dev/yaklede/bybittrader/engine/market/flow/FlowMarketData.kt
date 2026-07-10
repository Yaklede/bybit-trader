package dev.yaklede.bybittrader.engine.market.flow

import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

private val ONE_MINUTE: Duration = Duration.ofMinutes(1)
private val FIVE_MINUTES: Duration = Duration.ofMinutes(5)
private val FIFTEEN_MINUTES: Duration = Duration.ofMinutes(15)

data class TakerFlowBar(
    val symbol: Symbol,
    val openedAt: Instant,
    val takerBuyBase: BigDecimal,
    val takerBuyNotional: BigDecimal,
    val takerSellBase: BigDecimal,
    val takerSellNotional: BigDecimal,
    val buyTradeCount: Int,
    val sellTradeCount: Int,
) {
    init {
        require(takerBuyBase >= BigDecimal.ZERO) { "Taker buy base quantity must not be negative." }
        require(takerBuyNotional >= BigDecimal.ZERO) { "Taker buy notional must not be negative." }
        require(takerSellBase >= BigDecimal.ZERO) { "Taker sell base quantity must not be negative." }
        require(takerSellNotional >= BigDecimal.ZERO) { "Taker sell notional must not be negative." }
        require(buyTradeCount >= 0) { "Buy trade count must not be negative." }
        require(sellTradeCount >= 0) { "Sell trade count must not be negative." }
    }

    val availableAt: Instant
        get() = openedAt.plus(ONE_MINUTE)
}

enum class OpenInterestInterval(
    val bybitValue: String,
    val duration: Duration,
) {
    M5("5min", FIVE_MINUTES),
}

data class OpenInterestSnapshot(
    val symbol: Symbol,
    val interval: OpenInterestInterval,
    val timestamp: Instant,
    val openInterest: BigDecimal,
) {
    init {
        require(openInterest >= BigDecimal.ZERO) { "Open interest must not be negative." }
    }

    val availableAt: Instant
        get() = timestamp
}

enum class AccountRatioPeriod(
    val bybitValue: String,
    val duration: Duration,
) {
    M5("5min", FIVE_MINUTES),
}

data class AccountRatioSnapshot(
    val symbol: Symbol,
    val period: AccountRatioPeriod,
    val timestamp: Instant,
    val buyRatio: BigDecimal,
    val sellRatio: BigDecimal,
) {
    init {
        require(buyRatio >= BigDecimal.ZERO && buyRatio <= BigDecimal.ONE) {
            "Account buy ratio must be between zero and one."
        }
        require(sellRatio >= BigDecimal.ZERO && sellRatio <= BigDecimal.ONE) {
            "Account sell ratio must be between zero and one."
        }
    }

    val availableAt: Instant
        get() = timestamp
}

data class PremiumIndexBar(
    val symbol: Symbol,
    val timeframe: Timeframe,
    val openedAt: Instant,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
) {
    init {
        require(timeframe == Timeframe.M15) { "Milestone 1 premium index bars must use 15m timeframe." }
        require(high >= low) { "Premium index high must be greater than or equal to low." }
    }

    val availableAt: Instant
        get() = openedAt.plus(FIFTEEN_MINUTES)
}

data class FundingRateSnapshot(
    val symbol: Symbol,
    val timestamp: Instant,
    val fundingRate: BigDecimal,
) {
    val availableAt: Instant
        get() = timestamp
}

interface FlowMarketDataStore {
    suspend fun upsertTakerFlowBars(bars: List<TakerFlowBar>)

    suspend fun takerFlowBarsBetween(
        symbol: Symbol,
        startAt: Instant,
        endAt: Instant,
        limit: Int,
    ): List<TakerFlowBar>

    suspend fun takerFlowBarsBefore(
        symbol: Symbol,
        beforeAt: Instant,
        limit: Int,
    ): List<TakerFlowBar>

    suspend fun upsertOpenInterestSnapshots(snapshots: List<OpenInterestSnapshot>)

    suspend fun openInterestSnapshotsBetween(
        symbol: Symbol,
        interval: OpenInterestInterval,
        startAt: Instant,
        endAt: Instant,
        limit: Int,
    ): List<OpenInterestSnapshot>

    suspend fun openInterestSnapshotsBefore(
        symbol: Symbol,
        interval: OpenInterestInterval,
        beforeAt: Instant,
        limit: Int,
    ): List<OpenInterestSnapshot>

    suspend fun upsertAccountRatioSnapshots(snapshots: List<AccountRatioSnapshot>)

    suspend fun accountRatioSnapshotsBetween(
        symbol: Symbol,
        period: AccountRatioPeriod,
        startAt: Instant,
        endAt: Instant,
        limit: Int,
    ): List<AccountRatioSnapshot>

    suspend fun accountRatioSnapshotsBefore(
        symbol: Symbol,
        period: AccountRatioPeriod,
        beforeAt: Instant,
        limit: Int,
    ): List<AccountRatioSnapshot>

    suspend fun upsertPremiumIndexBars(bars: List<PremiumIndexBar>)

    suspend fun premiumIndexBarsBetween(
        symbol: Symbol,
        timeframe: Timeframe,
        startAt: Instant,
        endAt: Instant,
        limit: Int,
    ): List<PremiumIndexBar>

    suspend fun premiumIndexBarsBefore(
        symbol: Symbol,
        timeframe: Timeframe,
        beforeAt: Instant,
        limit: Int,
    ): List<PremiumIndexBar>

    suspend fun upsertFundingRateSnapshots(snapshots: List<FundingRateSnapshot>)

    suspend fun fundingRateSnapshotsBetween(
        symbol: Symbol,
        startAt: Instant,
        endAt: Instant,
        limit: Int,
    ): List<FundingRateSnapshot>

    suspend fun fundingRateSnapshotsBefore(
        symbol: Symbol,
        beforeAt: Instant,
        limit: Int,
    ): List<FundingRateSnapshot>
}
