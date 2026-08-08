package dev.yaklede.bybittrader.engine.strategy

import dev.yaklede.bybittrader.domain.BotMode
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.control.BotRuntimeStatus
import dev.yaklede.bybittrader.engine.control.BotStateStore
import dev.yaklede.bybittrader.engine.market.MarketTicker
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class VolumeConfirmedTrendLiveLoopTest :
    StringSpec({
        "fresh persisted side change evaluates exactly one live signal" {
            val fixture = fixture(eventAt = TEST_NOW.minusSeconds(10))

            val result = fixture.loop.runOnce()

            result.status shouldBe VolumeConfirmedTrendLiveLoopStatus.SIGNAL_EVALUATED
            fixture.executor.evaluatedSignals.size shouldBe 1
            fixture.executor.reconcileCount shouldBe 0
            fixture.tickerRequestCount() shouldBe 1
        }

        "causally latest side change does not depend on Shadow store ordering" {
            val latestEventAt = TEST_NOW.minusSeconds(10)
            val fixture =
                fixture(
                    eventAt = latestEventAt,
                    targetSide = Side.BUY,
                    events =
                        listOf(
                            confirmedEvent(APPROVED_SESSION_ID, Side.BUY, latestEventAt),
                            confirmedEvent(APPROVED_SESSION_ID, Side.SELL, latestEventAt.minus(Duration.ofHours(4))),
                        ),
                )

            val result = fixture.loop.runOnce()

            result.status shouldBe VolumeConfirmedTrendLiveLoopStatus.SIGNAL_EVALUATED
            result.signal?.side shouldBe Side.BUY
            fixture.executor.evaluatedSignals.map(VolumeConfirmedTrendExecutionSignal::side) shouldBe listOf(Side.BUY)
            fixture.executor.haltReasons shouldBe emptyList()
        }

        "stale signal reconciles without opening a delayed position" {
            val fixture = fixture(eventAt = TEST_NOW.minus(Duration.ofMinutes(21)))

            val result = fixture.loop.runOnce()

            result.status shouldBe VolumeConfirmedTrendLiveLoopStatus.NO_FRESH_SIGNAL
            fixture.executor.evaluatedSignals.size shouldBe 0
            fixture.executor.reconcileCount shouldBe 1
            fixture.tickerRequestCount() shouldBe 0
        }

        "paused bot reconciles but does not submit a fresh signal" {
            val fixture = fixture(eventAt = TEST_NOW.minusSeconds(10), botMode = BotMode.PAUSE_NEW_ENTRIES)

            val result = fixture.loop.runOnce()

            result.status shouldBe VolumeConfirmedTrendLiveLoopStatus.PAUSED
            fixture.executor.evaluatedSignals.size shouldBe 0
            fixture.executor.reconcileCount shouldBe 1
            fixture.tickerRequestCount() shouldBe 0
        }

        "stale ticker halts before evaluating a fresh entry signal" {
            val fixture =
                fixture(
                    eventAt = TEST_NOW.minusSeconds(10),
                    tickerCapturedAt = TEST_NOW.minusSeconds(31),
                )

            val result = fixture.loop.runOnce()

            result.status shouldBe VolumeConfirmedTrendLiveLoopStatus.HALTED
            fixture.executor.haltReasons shouldBe listOf("TREND_TICKER_STALE")
            fixture.executor.evaluatedSignals shouldBe emptyList()
            fixture.tickerRequestCount() shouldBe 1
        }

        "ticker beyond the allowed future skew halts before evaluating a fresh entry signal" {
            val fixture =
                fixture(
                    eventAt = TEST_NOW.minusSeconds(10),
                    tickerCapturedAt = TEST_NOW.plusSeconds(6),
                )

            val result = fixture.loop.runOnce()

            result.status shouldBe VolumeConfirmedTrendLiveLoopStatus.HALTED
            fixture.executor.haltReasons shouldBe listOf("TREND_TICKER_FROM_FUTURE")
            fixture.executor.evaluatedSignals shouldBe emptyList()
            fixture.tickerRequestCount() shouldBe 1
        }

        "changed Shadow session halts before any private reconciliation" {
            val fixture = fixture(eventAt = TEST_NOW.minusSeconds(10), shadowSessionId = "replacement-session")

            val result = fixture.loop.runOnce()

            result.status shouldBe VolumeConfirmedTrendLiveLoopStatus.HALTED
            fixture.executor.haltReasons shouldBe listOf("TREND_SHADOW_APPROVED_SESSION_CHANGED")
            fixture.executor.reconcileCount shouldBe 0
            fixture.executor.evaluatedSignals.size shouldBe 0
        }

        "expired opposite signal halts an unmatched open position" {
            val fixture =
                fixture(
                    eventAt = TEST_NOW.minus(Duration.ofMinutes(21)),
                    targetSide = Side.SELL,
                    reconciledState = liveState(status = VolumeConfirmedTrendLiveStatus.OPEN, positionSide = Side.BUY),
                )

            val result = fixture.loop.runOnce()

            result.status shouldBe VolumeConfirmedTrendLiveLoopStatus.HALTED
            fixture.executor.haltReasons shouldBe listOf("TREND_SIGNAL_EXPIRED_WITH_POSITION_MISMATCH")
            fixture.executor.evaluatedSignals.size shouldBe 0
        }

        "management-only loop reconciles while the bot is paused without reading Shadow or ticker data" {
            val executor = FakeTrendLiveExecutor(liveState())
            val loop =
                VolumeConfirmedTrendLiveManagementLoop(
                    botStateStore = FakeBotStateStore(BotMode.PAUSE_NEW_ENTRIES),
                    liveExecutor = executor,
                    clock = Clock.fixed(TEST_NOW, ZoneOffset.UTC),
                )

            val result = loop.runOnce()

            result.status shouldBe VolumeConfirmedTrendLiveLoopStatus.RECONCILED
            result.botMode shouldBe BotMode.PAUSE_NEW_ENTRIES
            result.shadowSessionId shouldBe null
            result.signal shouldBe null
            executor.reconcileCount shouldBe 1
            executor.evaluatedSignals.size shouldBe 0
        }

        "management-only loop reports an approval block as halted" {
            val executor =
                FakeTrendLiveExecutor(
                    reconciledState = liveState(),
                    reconciledStatus = VolumeConfirmedTrendLiveEvaluationStatus.APPROVAL_BLOCKED,
                )
            val loop =
                VolumeConfirmedTrendLiveManagementLoop(
                    botStateStore = FakeBotStateStore(BotMode.RUNNING),
                    liveExecutor = executor,
                    clock = Clock.fixed(TEST_NOW, ZoneOffset.UTC),
                )

            val result = loop.runOnce()

            result.status shouldBe VolumeConfirmedTrendLiveLoopStatus.HALTED
            executor.reconcileCount shouldBe 1
        }

        "management-only loop survives a failing failure callback" {
            val executor = FakeTrendLiveExecutor(liveState()).apply { reconcileFailuresRemaining = 1 }
            val recovered = CompletableDeferred<Unit>()
            var failureCallbacks = 0
            val loop =
                VolumeConfirmedTrendLiveManagementLoop(
                    botStateStore = FakeBotStateStore(BotMode.RUNNING),
                    liveExecutor = executor,
                    config = VolumeConfirmedTrendLiveManagementLoopConfig(interval = Duration.ofMillis(1)),
                    clock = Clock.fixed(TEST_NOW, ZoneOffset.UTC),
                    onResult = { recovered.complete(Unit) },
                    onFailure = {
                        failureCallbacks += 1
                        error("injected management failure callback outage")
                    },
                )
            val job = loop.start(CoroutineScope(SupervisorJob() + Dispatchers.Default))

            try {
                withTimeout(1_000) { recovered.await() }
            } finally {
                job.cancelAndJoin()
            }

            (executor.reconcileCount >= 2) shouldBe true
            failureCallbacks shouldBe 1
        }

        "signal loop survives a failing failure callback" {
            val shadowState = shadowState(APPROVED_SESSION_ID, Side.BUY)
            val event = confirmedEvent(APPROVED_SESSION_ID, Side.BUY, TEST_NOW.minusSeconds(10))
            val executor = FakeTrendLiveExecutor(liveState())
            val recovered = CompletableDeferred<Unit>()
            var tickerRequests = 0
            var failureCallbacks = 0
            val loop =
                VolumeConfirmedTrendLiveLoop(
                    shadowStore = FakeTrendShadowStore(shadowState, listOf(event)),
                    botStateStore = FakeBotStateStore(BotMode.RUNNING),
                    liveExecutor = executor,
                    tickerProvider = { symbol ->
                        tickerRequests += 1
                        if (tickerRequests == 1) error("injected ticker outage")
                        MarketTicker(
                            symbol = symbol,
                            lastPrice = BigDecimal("60000"),
                            markPrice = null,
                            indexPrice = null,
                            price24hPcnt = null,
                            fundingRate = null,
                            nextFundingTime = null,
                            capturedAt = TEST_NOW,
                        )
                    },
                    config =
                        VolumeConfirmedTrendLiveLoopConfig(
                            protocolId = PROTOCOL_ID,
                            candidateId = CANDIDATE_ID,
                            protocolSha256 = PROTOCOL_SHA,
                            symbol = SYMBOL,
                            approvedShadowSessionId = APPROVED_SESSION_ID,
                            interval = Duration.ofMillis(1),
                        ),
                    clock = Clock.fixed(TEST_NOW, ZoneOffset.UTC),
                    onResult = { recovered.complete(Unit) },
                    onFailure = {
                        failureCallbacks += 1
                        error("injected signal failure callback outage")
                    },
                )
            val job = loop.start(CoroutineScope(SupervisorJob() + Dispatchers.Default))

            try {
                withTimeout(1_000) { recovered.await() }
            } finally {
                job.cancelAndJoin()
            }

            (tickerRequests >= 2) shouldBe true
            (executor.evaluatedSignals.size >= 1) shouldBe true
            failureCallbacks shouldBe 1
        }
    })

private data class LiveLoopFixture(
    val loop: VolumeConfirmedTrendLiveLoop,
    val executor: FakeTrendLiveExecutor,
    val tickerRequestCount: () -> Int,
)

private fun fixture(
    eventAt: Instant,
    botMode: BotMode = BotMode.RUNNING,
    shadowSessionId: String = APPROVED_SESSION_ID,
    targetSide: Side = Side.BUY,
    reconciledState: VolumeConfirmedTrendLiveState = liveState(),
    events: List<VolumeConfirmedTrendShadowEvent>? = null,
    tickerCapturedAt: Instant = TEST_NOW,
): LiveLoopFixture {
    val shadowState = shadowState(sessionId = shadowSessionId, targetSide = targetSide)
    val event = confirmedEvent(sessionId = shadowSessionId, side = targetSide, eventAt = eventAt)
    val shadowStore = FakeTrendShadowStore(shadowState, events ?: listOf(event))
    val executor = FakeTrendLiveExecutor(reconciledState)
    var tickerRequests = 0
    val loop =
        VolumeConfirmedTrendLiveLoop(
            shadowStore = shadowStore,
            botStateStore = FakeBotStateStore(botMode),
            liveExecutor = executor,
            tickerProvider = { symbol ->
                tickerRequests += 1
                MarketTicker(
                    symbol = symbol,
                    lastPrice = BigDecimal("60000"),
                    markPrice = null,
                    indexPrice = null,
                    price24hPcnt = null,
                    fundingRate = null,
                    nextFundingTime = null,
                    capturedAt = tickerCapturedAt,
                )
            },
            config =
                VolumeConfirmedTrendLiveLoopConfig(
                    protocolId = PROTOCOL_ID,
                    candidateId = CANDIDATE_ID,
                    protocolSha256 = PROTOCOL_SHA,
                    symbol = SYMBOL,
                    approvedShadowSessionId = APPROVED_SESSION_ID,
                    interval = Duration.ofSeconds(15),
                    maximumSignalAge = Duration.ofMinutes(20),
                ),
            clock = Clock.fixed(TEST_NOW, ZoneOffset.UTC),
        )
    return LiveLoopFixture(loop, executor) { tickerRequests }
}

private class FakeTrendShadowStore(
    private val state: VolumeConfirmedTrendShadowState?,
    private val events: List<VolumeConfirmedTrendShadowEvent>,
) : VolumeConfirmedTrendShadowStore {
    override suspend fun trendShadowState(
        protocolId: String,
        symbol: Symbol,
    ): VolumeConfirmedTrendShadowState? = error("Live loop must read an atomic Shadow snapshot.")

    override suspend fun commitTrendShadow(
        state: VolumeConfirmedTrendShadowState,
        events: List<VolumeConfirmedTrendShadowEvent>,
    ) = error("not used")

    override suspend fun trendShadowEvents(
        sessionId: String,
        limit: Int,
    ): List<VolumeConfirmedTrendShadowEvent> = error("Live loop must read an atomic Shadow snapshot.")

    override suspend fun trendShadowSnapshot(
        protocolId: String,
        symbol: Symbol,
        limit: Int,
    ): VolumeConfirmedTrendShadowSnapshot =
        VolumeConfirmedTrendShadowSnapshot(
            state = state,
            recentEvents =
                state
                    ?.let { persisted ->
                        events.filter { it.sessionId == persisted.sessionId }.takeLast(limit)
                    }.orEmpty(),
        )
}

private class FakeBotStateStore(
    mode: BotMode,
) : BotStateStore {
    private var status = BotRuntimeStatus(mode, TEST_NOW, null)

    override suspend fun current(): BotRuntimeStatus = status

    override suspend fun update(status: BotRuntimeStatus) {
        this.status = status
    }
}

private class FakeTrendLiveExecutor(
    private val reconciledState: VolumeConfirmedTrendLiveState,
    private val reconciledStatus: VolumeConfirmedTrendLiveEvaluationStatus =
        VolumeConfirmedTrendLiveEvaluationStatus.RECONCILED,
) : VolumeConfirmedTrendLiveExecutor {
    val evaluatedSignals = mutableListOf<VolumeConfirmedTrendExecutionSignal>()
    val haltReasons = mutableListOf<String>()
    var reconcileCount = 0
    var reconcileFailuresRemaining = 0

    override suspend fun evaluate(
        signal: VolumeConfirmedTrendExecutionSignal,
        referencePrice: BigDecimal,
    ): VolumeConfirmedTrendLiveEvaluationResult {
        evaluatedSignals += signal
        return result(reconciledState, VolumeConfirmedTrendLiveEvaluationStatus.NO_ACTION)
    }

    override suspend fun reconcile(): VolumeConfirmedTrendLiveEvaluationResult {
        reconcileCount += 1
        if (reconcileFailuresRemaining > 0) {
            reconcileFailuresRemaining -= 1
            error("injected reconciliation outage")
        }
        return result(reconciledState, reconciledStatus)
    }

    override suspend fun haltForSafety(reasonCode: String): VolumeConfirmedTrendLiveEvaluationResult {
        haltReasons += reasonCode
        return result(
            reconciledState.copy(
                status = VolumeConfirmedTrendLiveStatus.HALTED,
                haltedReasonCode = reasonCode,
            ),
            VolumeConfirmedTrendLiveEvaluationStatus.HALTED,
        )
    }

    private fun result(
        state: VolumeConfirmedTrendLiveState,
        status: VolumeConfirmedTrendLiveEvaluationStatus,
    ): VolumeConfirmedTrendLiveEvaluationResult = VolumeConfirmedTrendLiveEvaluationResult(status, state, null)
}

private fun shadowState(
    sessionId: String,
    targetSide: Side,
): VolumeConfirmedTrendShadowState =
    VolumeConfirmedTrendShadowState(
        protocolId = PROTOCOL_ID,
        candidateId = CANDIDATE_ID,
        protocolSha256 = PROTOCOL_SHA,
        symbol = SYMBOL,
        sessionId = sessionId,
        status = VolumeConfirmedTrendShadowStatus.OBSERVING,
        sessionStartedAt = TEST_NOW.minus(Duration.ofDays(90)),
        indicatorState =
            VolumeConfirmedTrendIndicatorState(
                processedBars = 600,
                lastBarOpenedAt = TEST_NOW.minus(Duration.ofHours(4)),
                emaStates = List(5) { VolumeConfirmedTrendEmaState(60_000.0, 59_000.0) },
                targetSide = targetSide,
                recentVolumes = List(42) { 100.0 },
            ),
        lastAppliedFundingAt = TEST_NOW.minus(Duration.ofHours(8)),
        lastObservedAt = TEST_NOW,
        position = null,
        sessionStartingEquity = 660.0,
        cash = 700.0,
        equity = 700.0,
        peakEquity = 710.0,
        maximumDrawdownPct = 5.0,
        totalFees = 1.0,
        totalSlippage = 0.5,
        totalFundingPnl = 0.1,
        closedTrades = 5,
        executedTransitions = 6,
        invalidatedSessionCount = 0,
        updatedAt = TEST_NOW,
    )

private fun confirmedEvent(
    sessionId: String,
    side: Side,
    eventAt: Instant,
): VolumeConfirmedTrendShadowEvent =
    VolumeConfirmedTrendShadowEvent(
        eventId = "event-${eventAt.epochSecond}",
        sessionId = sessionId,
        protocolId = PROTOCOL_ID,
        protocolSha256 = PROTOCOL_SHA,
        symbol = SYMBOL,
        type = VolumeConfirmedTrendShadowEventType.H4_EVALUATED,
        eventAt = eventAt,
        observedAt = eventAt.plusSeconds(10),
        h4OpenedAt = eventAt.minus(Duration.ofHours(4)),
        side = side,
        referencePrice = 60_000.0,
        fillPrice = null,
        quantity = null,
        fee = 0.0,
        slippage = 0.0,
        fundingPnl = 0.0,
        grossPnl = 0.0,
        netPnl = 0.0,
        cash = 700.0,
        equity = 700.0,
        reason = "CONFIRMED_SIDE_CHANGE",
    )

private fun liveState(
    status: VolumeConfirmedTrendLiveStatus = VolumeConfirmedTrendLiveStatus.FLAT,
    positionSide: Side? = null,
): VolumeConfirmedTrendLiveState =
    VolumeConfirmedTrendLiveState(
        protocolId = PROTOCOL_ID,
        candidateId = CANDIDATE_ID,
        protocolSha256 = PROTOCOL_SHA,
        symbol = SYMBOL,
        status = status,
        approvalId = APPROVAL_ID,
        activeDecisionKey = null,
        pendingTargetSide = positionSide,
        clientOrderId = null,
        exchangeOrderId = null,
        observedPositionSide = positionSide,
        observedPositionQuantity = positionSide?.let { BigDecimal("0.001") },
        lastExecutionId = null,
        haltedReasonCode = null,
        updatedAt = TEST_NOW,
    )

private const val PROTOCOL_ID = "volume-confirmed-trend-ensemble-v1"
private const val CANDIDATE_ID = "vcte_4h_majority_001"
private const val APPROVED_SESSION_ID = "trend-shadow-approved-session"
private const val APPROVAL_ID = "approval-001"
private val PROTOCOL_SHA = "a".repeat(64)
private val SYMBOL = Symbol("BTCUSDT")
private val TEST_NOW = Instant.parse("2026-11-07T00:00:10Z")
