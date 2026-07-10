package dev.yaklede.bybittrader.engine.market

import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import java.time.Instant

data class MarketSyncCheckpoint(
    val symbol: Symbol,
    val timeframe: Timeframe,
    val latestClosedOpenedAt: Instant,
    val lastSyncAt: Instant,
    val lastSyncStatus: MarketSyncStatus,
    val consecutiveRateLimitCount: Int,
)

enum class MarketSyncStatus {
    SUCCESS,
    RATE_LIMITED,
    FAILED,
}

interface MarketSyncCheckpointStore {
    suspend fun upsertCheckpoint(checkpoint: MarketSyncCheckpoint)

    suspend fun checkpoints(symbol: Symbol): List<MarketSyncCheckpoint>
}
