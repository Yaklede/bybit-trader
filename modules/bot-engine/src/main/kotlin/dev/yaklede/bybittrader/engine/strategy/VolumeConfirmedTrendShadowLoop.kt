package dev.yaklede.bybittrader.engine.strategy

import dev.yaklede.bybittrader.domain.ResearchCandleLimits
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.market.MarketDataSyncService
import dev.yaklede.bybittrader.engine.market.flow.FundingRateSyncService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

private const val LOOP_H4_SECONDS = 4L * 60L * 60L
private const val LOOP_M15_SECONDS = 15L * 60L

data class VolumeConfirmedTrendShadowLoopConfig(
    val symbol: Symbol,
    val recentSyncLimit: Int = 1000,
    val historyPageLimit: Int = 1000,
    val maximumHistoryRequests: Int = 1000,
    val boundaryDelay: Duration = Duration.ofSeconds(10),
    val failureRetryDelay: Duration = Duration.ofMinutes(1),
) {
    init {
        require(recentSyncLimit in 16..1000) { "Trend shadow recent sync limit must be between 16 and 1000." }
        require(historyPageLimit in 1..1000) { "Trend shadow history page limit must be between 1 and 1000." }
        require(maximumHistoryRequests in 1..ResearchCandleLimits.MAX_HISTORY_REQUESTS_PER_TIMEFRAME) {
            "Trend shadow maximum history requests are outside the research limit."
        }
        require(!boundaryDelay.isNegative && boundaryDelay < Duration.ofMinutes(5)) {
            "Trend shadow boundary delay must be between zero and five minutes."
        }
        require(!failureRetryDelay.isNegative && !failureRetryDelay.isZero) {
            "Trend shadow failure retry delay must be positive."
        }
    }
}

class VolumeConfirmedTrendShadowLoop(
    private val marketDataSyncService: MarketDataSyncService,
    private val fundingRateSyncService: FundingRateSyncService,
    private val shadowService: VolumeConfirmedTrendShadowService,
    private val config: VolumeConfirmedTrendShadowLoopConfig,
    private val clock: Clock = Clock.systemUTC(),
    private val onResult: suspend (VolumeConfirmedTrendShadowEvaluationResult) -> Unit = {},
    private val onFailure: suspend (Throwable) -> Unit = {},
) {
    private val logger = LoggerFactory.getLogger(VolumeConfirmedTrendShadowLoop::class.java)

    suspend fun runOnce(): VolumeConfirmedTrendShadowEvaluationResult {
        val now = Instant.now(clock)
        val nextRequiredM15 = shadowService.nextRequiredM15OpenedAt()
        if (nextRequiredM15.isBefore(now)) {
            val requiredM15 =
                ((Duration.between(nextRequiredM15, now).seconds + LOOP_M15_SECONDS - 1) / LOOP_M15_SECONDS)
                    .coerceAtLeast(1)
            if (requiredM15 > config.recentSyncLimit) {
                val missingHistorySeconds = Duration.between(nextRequiredM15, now).seconds
                val daySeconds = Duration.ofDays(1).seconds
                val daysBack =
                    ((missingHistorySeconds + daySeconds - 1) / daySeconds)
                        .coerceIn(1, ResearchCandleLimits.MAX_HISTORY_DAYS_BACK.toLong())
                        .toInt()
                marketDataSyncService.syncHistory(
                    symbol = config.symbol,
                    timeframes = listOf(Timeframe.M15),
                    startAt = nextRequiredM15,
                    endAt = now,
                    daysBack = daysBack,
                    pageLimit = config.historyPageLimit,
                    maxRequestsPerTimeframe = config.maximumHistoryRequests,
                )
            }
            marketDataSyncService.syncClosedCandles(
                symbol = config.symbol,
                timeframes = listOf(Timeframe.M15),
                limit = config.recentSyncLimit,
                maxRetries = 5,
            )
        }
        val fundingStart = shadowService.nextFundingSyncAt()
        if (!fundingStart.isAfter(now)) {
            fundingRateSyncService.sync(config.symbol, fundingStart, now)
        }
        val result = shadowService.evaluate(marketDataSyncService.ticker(config.symbol))
        onResult(result)
        return result
    }

    fun start(scope: CoroutineScope): Job =
        scope.launch {
            while (isActive) {
                try {
                    runOnce()
                    delay(millisUntilNextBoundary(Instant.now(clock), config.boundaryDelay))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    logger.warn("volume-confirmed trend shadow loop failed", error)
                    onFailure(error)
                    delay(config.failureRetryDelay.toMillis())
                }
            }
        }
}

private fun millisUntilNextBoundary(
    now: Instant,
    boundaryDelay: Duration,
): Long {
    val nextBoundarySeconds = ((now.epochSecond / LOOP_H4_SECONDS) + 1) * LOOP_H4_SECONDS
    val target = Instant.ofEpochSecond(nextBoundarySeconds).plus(boundaryDelay)
    return Duration.between(now, target).toMillis().coerceAtLeast(1_000L)
}
