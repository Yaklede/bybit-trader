package dev.yaklede.bybittrader.engine.strategy

import dev.yaklede.bybittrader.engine.execution.ExchangeAccountBalance
import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionFill
import dev.yaklede.bybittrader.engine.execution.ExecutionAccountSnapshot
import dev.yaklede.bybittrader.engine.execution.ExecutionFillEvent
import dev.yaklede.bybittrader.engine.execution.ExecutionProjectionStore
import dev.yaklede.bybittrader.engine.execution.ExecutionRuntimeMode
import java.time.Duration
import java.time.Instant

interface VolumeConfirmedTrendLiveProjectionSink {
    suspend fun accountSnapshotDue(now: Instant): Boolean

    suspend fun recordAccountBalance(balance: ExchangeAccountBalance)

    suspend fun recordExecutionFills(
        fills: List<ExchangeExecutionFill>,
        receivedAt: Instant,
    )
}

object NoopVolumeConfirmedTrendLiveProjectionSink : VolumeConfirmedTrendLiveProjectionSink {
    override suspend fun accountSnapshotDue(now: Instant): Boolean = false

    override suspend fun recordAccountBalance(balance: ExchangeAccountBalance) = Unit

    override suspend fun recordExecutionFills(
        fills: List<ExchangeExecutionFill>,
        receivedAt: Instant,
    ) = Unit
}

class LedgerVolumeConfirmedTrendLiveProjectionSink(
    private val store: ExecutionProjectionStore,
    private val runtimeMode: ExecutionRuntimeMode,
    private val accountCoin: String = "USDT",
    private val accountSnapshotInterval: Duration = Duration.ofMinutes(1),
) : VolumeConfirmedTrendLiveProjectionSink {
    init {
        require(accountCoin.isNotBlank()) { "Trend live projection account coin must not be blank." }
        require(!accountSnapshotInterval.isNegative && !accountSnapshotInterval.isZero) {
            "Trend live account snapshot interval must be positive."
        }
    }

    override suspend fun accountSnapshotDue(now: Instant): Boolean {
        val latest = store.latestAccountSnapshot(runtimeMode, now) ?: return true
        val age = Duration.between(latest.capturedAt, now)
        return age.isNegative || age >= accountSnapshotInterval
    }

    override suspend fun recordAccountBalance(balance: ExchangeAccountBalance) {
        val coin = balance.coins.singleOrNull { it.coin == accountCoin }
        store.recordAccountSnapshot(
            ExecutionAccountSnapshot(
                mode = runtimeMode,
                accountType = balance.accountType,
                totalEquity = balance.totalEquity,
                totalWalletBalance = balance.totalWalletBalance,
                totalMarginBalance = balance.totalMarginBalance,
                totalAvailableBalance = balance.totalAvailableBalance,
                totalPerpUnrealizedPnl = balance.totalPerpUnrealizedPnl,
                totalInitialMargin = balance.totalInitialMargin,
                totalMaintenanceMargin = balance.totalMaintenanceMargin,
                trackedCoin = coin?.coin,
                trackedCoinEquity = coin?.equity,
                trackedCoinWalletBalance = coin?.walletBalance,
                trackedCoinUnrealizedPnl = coin?.unrealizedPnl,
                trackedCoinCumulativeRealizedPnl = coin?.cumulativeRealizedPnl,
                capturedAt = balance.capturedAt,
            ),
        )
    }

    override suspend fun recordExecutionFills(
        fills: List<ExchangeExecutionFill>,
        receivedAt: Instant,
    ) {
        fills.forEach { fill ->
            store.recordExecutionFill(
                ExecutionFillEvent(
                    mode = runtimeMode,
                    fill = fill,
                    receivedAt = receivedAt,
                ),
            )
        }
    }
}
