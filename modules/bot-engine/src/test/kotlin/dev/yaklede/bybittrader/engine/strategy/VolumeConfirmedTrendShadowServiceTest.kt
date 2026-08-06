package dev.yaklede.bybittrader.engine.strategy

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.market.MarketCandleStore
import dev.yaklede.bybittrader.engine.market.MarketTicker
import dev.yaklede.bybittrader.engine.market.flow.AccountRatioPeriod
import dev.yaklede.bybittrader.engine.market.flow.AccountRatioSnapshot
import dev.yaklede.bybittrader.engine.market.flow.FlowMarketDataStore
import dev.yaklede.bybittrader.engine.market.flow.FundingRateSnapshot
import dev.yaklede.bybittrader.engine.market.flow.OpenInterestInterval
import dev.yaklede.bybittrader.engine.market.flow.OpenInterestSnapshot
import dev.yaklede.bybittrader.engine.market.flow.PremiumIndexBar
import dev.yaklede.bybittrader.engine.market.flow.TakerFlowBar
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class VolumeConfirmedTrendShadowServiceTest :
    StringSpec({
        "bootstraps without chasing the current trend and opens only on the next transition" {
            val fixture = ShadowFixture()

            val bootstrapped = fixture.service().evaluate(fixture.ticker("2026-01-01T12:01:00Z", "110"))

            bootstrapped.status shouldBe VolumeConfirmedTrendShadowEvaluationStatus.BOOTSTRAPPED
            bootstrapped.state.position shouldBe null
            bootstrapped.state.indicatorState.targetSide shouldBe Side.BUY
            fixture.events.map { it.type } shouldBe listOf(VolumeConfirmedTrendShadowEventType.SESSION_STARTED)

            fixture.candles += h4Candles("2026-01-01T12:00:00Z", "110", "50", "30")
            val evaluated = fixture.service().evaluate(fixture.ticker("2026-01-01T16:01:00Z", "50"))

            evaluated.status shouldBe VolumeConfirmedTrendShadowEvaluationStatus.EVALUATED
            evaluated.state.position?.side shouldBe Side.SELL
            evaluated.state.position?.quantity shouldBe 1.3
            fixture.events.map { it.type } shouldContain VolumeConfirmedTrendShadowEventType.POSITION_OPENED

            val eventCount = fixture.events.size
            val restarted = fixture.service()
            val duplicate = restarted.evaluate(fixture.ticker("2026-01-01T16:01:00Z", "50"))

            duplicate.status shouldBe VolumeConfirmedTrendShadowEvaluationStatus.NO_NEW_H4
            duplicate.state.position shouldBe evaluated.state.position
            fixture.events.size shouldBe eventCount
        }

        "does not commit a gap reset while required settled funding is missing" {
            val fixture = ShadowFixture()
            fixture.service().evaluate(fixture.ticker("2026-01-01T12:01:00Z", "110"))
            fixture.candles += h4Candles("2026-01-01T12:00:00Z", "110", "50", "30")
            fixture.service().evaluate(fixture.ticker("2026-01-01T16:01:00Z", "50"))
            val beforeGap = requireNotNull(fixture.store.state)
            val beforeEvents = fixture.events.toList()
            fixture.candles += h4Candles("2026-01-01T16:00:00Z", "50", "45", "10")
            fixture.candles += h4Candles("2026-01-01T20:00:00Z", "45", "40", "10")

            shouldThrow<VolumeConfirmedTrendShadowDataException> {
                fixture.service().evaluate(fixture.ticker("2026-01-02T00:01:00Z", "40"))
            }.message shouldBe "Funding rate is missing at 2026-01-02T00:00:00Z while a shadow position is open."
            fixture.store.state shouldBe beforeGap
            fixture.events shouldBe beforeEvents

            fixture.flow.funding +=
                FundingRateSnapshot(
                    symbol = fixture.symbol,
                    timestamp = Instant.parse("2026-01-02T00:00:00Z"),
                    fundingRate = BigDecimal("0.0001"),
                )
            val reset = fixture.service().evaluate(fixture.ticker("2026-01-02T00:01:00Z", "40"))

            reset.status shouldBe VolumeConfirmedTrendShadowEvaluationStatus.SESSION_RESET
            reset.state.position shouldBe null
            reset.state.invalidatedSessionCount shouldBe 1
            reset.state.sessionId shouldBe "session-2"
            reset.state.sessionStartingEquity shouldBe reset.state.cash
            fixture.events.map { it.type } shouldContain VolumeConfirmedTrendShadowEventType.FUNDING_APPLIED
            fixture.events.map { it.type } shouldContain VolumeConfirmedTrendShadowEventType.SESSION_INVALIDATED
        }

        "fails closed when a required H4 bucket is incomplete" {
            val fixture = ShadowFixture()
            fixture.candles.removeAt(fixture.candles.lastIndex)

            shouldThrow<VolumeConfirmedTrendShadowDataException> {
                fixture.service().evaluate(fixture.ticker("2026-01-01T12:01:00Z", "110"))
            }
            fixture.store.state shouldBe null
            fixture.events shouldBe emptyList()
        }
    })

private class ShadowFixture {
    val symbol = Symbol("BTCUSDT")
    val candles = mutableListOf<Candle>()
    val flow = InMemoryFlowStore()
    val store = InMemoryTrendShadowStore()
    val events: List<VolumeConfirmedTrendShadowEvent>
        get() = store.events
    private var sessionCounter = 0
    private val parameters =
        VolumeConfirmedTrendParameters(
            emaVotePairs = listOf(VolumeConfirmedTrendEmaPair(1, 2)),
            minimumMajorityVotes = 1,
            volumeMedianLookbackBars = 2,
            warmupDecisionBars = 3,
        )
    private val bootstrap =
        run {
            val bars =
                listOf(
                    h4Bar("2026-01-01T00:00:00Z", 100.0, 10.0),
                    h4Bar("2026-01-01T04:00:00Z", 100.0, 10.0),
                )
            val evaluator = VolumeConfirmedTrendEvaluator(parameters)
            bars.forEach(evaluator::evaluate)
            VolumeConfirmedTrendBootstrap(
                protocolId = "trend-test",
                candidateId = "trend-test-candidate",
                protocolSha256 = "a".repeat(64),
                sourceFeatureSha256 = "b".repeat(64),
                sourceH4BarCount = bars.size,
                indicatorState = evaluator.snapshot(),
            )
        }

    init {
        candles += h4Candles("2026-01-01T08:00:00Z", "100", "110", "20")
    }

    fun service(): VolumeConfirmedTrendShadowService =
        VolumeConfirmedTrendShadowService(
            candleStore = InMemoryCandleStore(candles),
            flowStore = flow,
            shadowStore = store,
            config =
                VolumeConfirmedTrendShadowConfig(
                    symbol = symbol,
                    bootstrap = bootstrap,
                    initialEquity = 100.0,
                    parameters = parameters,
                    executionContract = VolumeConfirmedTrendExecutionContract(),
                ),
            sessionIdFactory = {
                sessionCounter += 1
                "session-$sessionCounter"
            },
        )

    fun ticker(
        at: String,
        price: String,
    ): MarketTicker =
        MarketTicker(
            symbol = symbol,
            lastPrice = BigDecimal(price),
            markPrice = BigDecimal(price),
            indexPrice = BigDecimal(price),
            price24hPcnt = null,
            fundingRate = null,
            nextFundingTime = null,
            capturedAt = Instant.parse(at),
        )
}

private class InMemoryTrendShadowStore : VolumeConfirmedTrendShadowStore {
    var state: VolumeConfirmedTrendShadowState? = null
    val events = mutableListOf<VolumeConfirmedTrendShadowEvent>()

    override suspend fun trendShadowState(
        protocolId: String,
        symbol: Symbol,
    ): VolumeConfirmedTrendShadowState? = state?.takeIf { it.protocolId == protocolId && it.symbol == symbol }

    override suspend fun commitTrendShadow(
        state: VolumeConfirmedTrendShadowState,
        events: List<VolumeConfirmedTrendShadowEvent>,
    ) {
        this.events += events.filter { incoming -> this.events.none { it.eventId == incoming.eventId } }
        this.state = state
    }

    override suspend fun trendShadowEvents(
        sessionId: String,
        limit: Int,
    ): List<VolumeConfirmedTrendShadowEvent> = events.filter { it.sessionId == sessionId }.takeLast(limit)
}

private class InMemoryCandleStore(
    private val candles: List<Candle>,
) : MarketCandleStore {
    override suspend fun upsert(candles: List<Candle>) = Unit

    override suspend fun recentCandles(
        symbol: Symbol,
        timeframe: Timeframe,
        limit: Int,
    ): List<Candle> = candles.filter { it.symbol == symbol && it.timeframe == timeframe }.takeLast(limit)

    override suspend fun candlesBetween(
        symbol: Symbol,
        timeframe: Timeframe,
        startAt: Instant,
        endAt: Instant,
        limit: Int,
    ): List<Candle> =
        candles
            .filter { it.symbol == symbol && it.timeframe == timeframe }
            .filter { !it.openedAt.isBefore(startAt) && !it.openedAt.isAfter(endAt) }
            .take(limit)
}

private class InMemoryFlowStore : FlowMarketDataStore {
    val funding = mutableListOf<FundingRateSnapshot>()

    override suspend fun upsertFundingRateSnapshots(snapshots: List<FundingRateSnapshot>) {
        funding += snapshots
    }

    override suspend fun fundingRateSnapshotsBetween(
        symbol: Symbol,
        startAt: Instant,
        endAt: Instant,
        limit: Int,
    ): List<FundingRateSnapshot> =
        funding
            .filter { it.symbol == symbol && !it.timestamp.isBefore(startAt) && !it.timestamp.isAfter(endAt) }
            .sortedBy { it.timestamp }
            .take(limit)

    override suspend fun fundingRateSnapshotsBefore(
        symbol: Symbol,
        beforeAt: Instant,
        limit: Int,
    ): List<FundingRateSnapshot> = funding.filter { it.symbol == symbol && it.timestamp.isBefore(beforeAt) }.takeLast(limit)

    override suspend fun upsertTakerFlowBars(bars: List<TakerFlowBar>) = Unit

    override suspend fun takerFlowBarsBetween(
        symbol: Symbol,
        startAt: Instant,
        endAt: Instant,
        limit: Int,
    ): List<TakerFlowBar> = emptyList()

    override suspend fun takerFlowBarsBefore(
        symbol: Symbol,
        beforeAt: Instant,
        limit: Int,
    ): List<TakerFlowBar> = emptyList()

    override suspend fun upsertOpenInterestSnapshots(snapshots: List<OpenInterestSnapshot>) = Unit

    override suspend fun openInterestSnapshotsBetween(
        symbol: Symbol,
        interval: OpenInterestInterval,
        startAt: Instant,
        endAt: Instant,
        limit: Int,
    ): List<OpenInterestSnapshot> = emptyList()

    override suspend fun openInterestSnapshotsBefore(
        symbol: Symbol,
        interval: OpenInterestInterval,
        beforeAt: Instant,
        limit: Int,
    ): List<OpenInterestSnapshot> = emptyList()

    override suspend fun upsertAccountRatioSnapshots(snapshots: List<AccountRatioSnapshot>) = Unit

    override suspend fun accountRatioSnapshotsBetween(
        symbol: Symbol,
        period: AccountRatioPeriod,
        startAt: Instant,
        endAt: Instant,
        limit: Int,
    ): List<AccountRatioSnapshot> = emptyList()

    override suspend fun accountRatioSnapshotsBefore(
        symbol: Symbol,
        period: AccountRatioPeriod,
        beforeAt: Instant,
        limit: Int,
    ): List<AccountRatioSnapshot> = emptyList()

    override suspend fun upsertPremiumIndexBars(bars: List<PremiumIndexBar>) = Unit

    override suspend fun premiumIndexBarsBetween(
        symbol: Symbol,
        timeframe: Timeframe,
        startAt: Instant,
        endAt: Instant,
        limit: Int,
    ): List<PremiumIndexBar> = emptyList()

    override suspend fun premiumIndexBarsBefore(
        symbol: Symbol,
        timeframe: Timeframe,
        beforeAt: Instant,
        limit: Int,
    ): List<PremiumIndexBar> = emptyList()
}

private fun h4Candles(
    at: String,
    open: String,
    close: String,
    totalVolume: String,
): List<Candle> {
    val openedAt = Instant.parse(at)
    val openValue = BigDecimal(open)
    val closeValue = BigDecimal(close)
    val volume = BigDecimal(totalVolume).divide(BigDecimal(16))
    return (0 until 16).map { index ->
        val candleOpen = if (index == 0) openValue else closeValue
        Candle(
            symbol = Symbol("BTCUSDT"),
            timeframe = Timeframe.M15,
            openedAt = openedAt.plusSeconds(index * 900L),
            open = candleOpen,
            high = maxOf(candleOpen, closeValue).add(BigDecimal.ONE),
            low = minOf(candleOpen, closeValue).subtract(BigDecimal.ONE),
            close = closeValue,
            volume = volume,
        )
    }
}

private fun h4Bar(
    at: String,
    close: Double,
    volume: Double,
): VolumeConfirmedTrendBar =
    VolumeConfirmedTrendBar(
        openedAt = Instant.parse(at),
        open = close,
        high = close + 1.0,
        low = close - 1.0,
        close = close,
        volume = volume,
    )
