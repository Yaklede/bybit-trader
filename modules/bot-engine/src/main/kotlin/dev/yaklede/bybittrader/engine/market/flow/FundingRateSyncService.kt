package dev.yaklede.bybittrader.engine.market.flow

import dev.yaklede.bybittrader.domain.Symbol
import org.slf4j.LoggerFactory
import java.time.Instant

data class FundingRateSyncResult(
    val symbol: Symbol,
    val startAt: Instant,
    val endAt: Instant,
    val fetchedSnapshots: Int,
)

class FundingRateSyncService(
    private val feed: FundingRateFeed,
    private val store: FlowMarketDataStore,
) {
    private val logger = LoggerFactory.getLogger(FundingRateSyncService::class.java)

    suspend fun sync(
        symbol: Symbol,
        startAt: Instant,
        endAt: Instant,
    ): FundingRateSyncResult {
        require(!endAt.isBefore(startAt)) { "Funding sync end must not precede its start." }
        logger.info("funding-rate sync requested symbol={} startAt={} endAt={}", symbol.value, startAt, endAt)
        val snapshots = feed.fetchFundingRateSnapshots(symbol, startAt, endAt)
        require(snapshots.all { it.symbol == symbol }) { "Funding feed returned a different symbol." }
        require(snapshots.zipWithNext().all { (previous, current) -> current.timestamp.isAfter(previous.timestamp) }) {
            "Funding feed must return strictly ordered snapshots."
        }
        store.upsertFundingRateSnapshots(snapshots)
        logger.info("funding-rate sync completed symbol={} fetchedSnapshots={}", symbol.value, snapshots.size)
        return FundingRateSyncResult(
            symbol = symbol,
            startAt = startAt,
            endAt = endAt,
            fetchedSnapshots = snapshots.size,
        )
    }
}
