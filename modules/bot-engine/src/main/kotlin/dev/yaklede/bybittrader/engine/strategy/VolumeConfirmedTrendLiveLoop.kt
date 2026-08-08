package dev.yaklede.bybittrader.engine.strategy

import dev.yaklede.bybittrader.domain.BotMode
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.control.BotStateStore
import dev.yaklede.bybittrader.engine.market.MarketTicker
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

data class VolumeConfirmedTrendLiveLoopConfig(
    val protocolId: String,
    val candidateId: String,
    val protocolSha256: String,
    val symbol: Symbol,
    val approvedShadowSessionId: String,
    val interval: Duration = Duration.ofSeconds(15),
    val maximumSignalAge: Duration = Duration.ofMinutes(20),
    val maximumTickerAge: Duration = Duration.ofSeconds(30),
    val maximumTickerFutureSkew: Duration = Duration.ofSeconds(5),
    val signalEventLimit: Int = 32,
) {
    init {
        require(protocolId.isNotBlank() && candidateId.isNotBlank() && approvedShadowSessionId.isNotBlank()) {
            "Trend live loop identities must not be blank."
        }
        require(protocolSha256.matches(Regex("[0-9a-f]{64}"))) {
            "Trend live loop protocol fingerprint must be a lowercase SHA-256."
        }
        require(symbol.value == "BTCUSDT") { "The frozen trend live loop supports BTCUSDT only." }
        require(!interval.isNegative && !interval.isZero) { "Trend live loop interval must be positive." }
        require(!maximumSignalAge.isNegative && !maximumSignalAge.isZero && maximumSignalAge < Duration.ofHours(4)) {
            "Trend live maximum signal age must be positive and shorter than one H4 period."
        }
        require(!maximumTickerAge.isNegative && !maximumTickerAge.isZero) {
            "Trend live maximum ticker age must be positive."
        }
        require(!maximumTickerFutureSkew.isNegative) {
            "Trend live maximum ticker future skew must not be negative."
        }
        require(signalEventLimit in 1..100) { "Trend live signal event limit must be between 1 and 100." }
    }
}

enum class VolumeConfirmedTrendLiveLoopStatus {
    PAUSED,
    NO_FRESH_SIGNAL,
    SIGNAL_EVALUATED,
    RECONCILED,
    HALTED,
}

enum class VolumeConfirmedTrendLiveRuntimeMode {
    DISABLED,
    MANAGEMENT_ONLY,
    SIGNAL_ENABLED,
}

data class VolumeConfirmedTrendLiveLoopResult(
    val status: VolumeConfirmedTrendLiveLoopStatus,
    val botMode: BotMode,
    val shadowSessionId: String?,
    val signal: VolumeConfirmedTrendExecutionSignal?,
    val evaluation: VolumeConfirmedTrendLiveEvaluationResult,
    val evaluatedAt: Instant,
)

data class VolumeConfirmedTrendLiveManagementLoopConfig(
    val interval: Duration = Duration.ofSeconds(15),
) {
    init {
        require(!interval.isNegative && !interval.isZero) {
            "Trend live management-loop interval must be positive."
        }
    }
}

class VolumeConfirmedTrendLiveManagementLoop(
    private val botStateStore: BotStateStore,
    private val liveExecutor: VolumeConfirmedTrendLiveExecutor,
    private val config: VolumeConfirmedTrendLiveManagementLoopConfig = VolumeConfirmedTrendLiveManagementLoopConfig(),
    private val clock: Clock = Clock.systemUTC(),
    private val onResult: suspend (VolumeConfirmedTrendLiveLoopResult) -> Unit = {},
    private val onFailure: suspend (Throwable) -> Unit = {},
) {
    private val logger = LoggerFactory.getLogger(VolumeConfirmedTrendLiveManagementLoop::class.java)

    suspend fun runOnce(): VolumeConfirmedTrendLiveLoopResult {
        val evaluatedAt = Instant.now(clock)
        val evaluation = liveExecutor.reconcile()
        val result =
            VolumeConfirmedTrendLiveLoopResult(
                status =
                    if (evaluation.status.blocksLiveLoop()) {
                        VolumeConfirmedTrendLiveLoopStatus.HALTED
                    } else {
                        VolumeConfirmedTrendLiveLoopStatus.RECONCILED
                    },
                botMode = botStateStore.current().mode,
                shadowSessionId = null,
                signal = null,
                evaluation = evaluation,
                evaluatedAt = evaluatedAt,
            )
        onResult(result)
        return result
    }

    fun start(scope: CoroutineScope): Job =
        scope.launch {
            while (isActive) {
                try {
                    runOnce()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    logger.warn("volume-confirmed trend live management loop failed", error)
                    notifyTradingLoopFailure(
                        logger = logger,
                        loopName = "volume-confirmed trend live management loop",
                        onFailure = onFailure,
                        error = error,
                    )
                }
                delay(config.interval.toMillis())
            }
        }
}

class VolumeConfirmedTrendLiveLoop(
    private val shadowStore: VolumeConfirmedTrendShadowStore,
    private val botStateStore: BotStateStore,
    private val liveExecutor: VolumeConfirmedTrendLiveExecutor,
    private val tickerProvider: suspend (Symbol) -> MarketTicker,
    private val config: VolumeConfirmedTrendLiveLoopConfig,
    private val clock: Clock = Clock.systemUTC(),
    private val onResult: suspend (VolumeConfirmedTrendLiveLoopResult) -> Unit = {},
    private val onFailure: suspend (Throwable) -> Unit = {},
) {
    private val logger = LoggerFactory.getLogger(VolumeConfirmedTrendLiveLoop::class.java)

    suspend fun runOnce(): VolumeConfirmedTrendLiveLoopResult {
        val now = Instant.now(clock)
        val botMode = botStateStore.current().mode
        val shadowSnapshot =
            shadowStore.trendShadowSnapshot(
                protocolId = config.protocolId,
                symbol = config.symbol,
                limit = config.signalEventLimit,
            )
        val shadow = shadowSnapshot.state
        val invalidShadowReason = shadow.invalidReason()
        if (invalidShadowReason != null) {
            return publish(
                status = VolumeConfirmedTrendLiveLoopStatus.HALTED,
                botMode = botMode,
                shadowSessionId = shadow?.sessionId,
                signal = null,
                evaluation = liveExecutor.haltForSafety(invalidShadowReason),
                evaluatedAt = now,
            )
        }
        val validShadow = requireNotNull(shadow)
        val latestEvent =
            shadowSnapshot.recentEvents
                .asSequence()
                .filter(VolumeConfirmedTrendShadowEvent::isConfirmedSideChange)
                .maxWithOrNull(
                    compareBy<VolumeConfirmedTrendShadowEvent>(VolumeConfirmedTrendShadowEvent::eventAt)
                        .thenBy(VolumeConfirmedTrendShadowEvent::observedAt)
                        .thenBy(VolumeConfirmedTrendShadowEvent::eventId),
                )
        val signal = latestEvent?.toExecutionSignal()
        if (signal != null && validShadow.indicatorState.targetSide != signal.side) {
            return publish(
                status = VolumeConfirmedTrendLiveLoopStatus.HALTED,
                botMode = botMode,
                shadowSessionId = validShadow.sessionId,
                signal = signal,
                evaluation = liveExecutor.haltForSafety("TREND_SHADOW_TARGET_SIGNAL_MISMATCH"),
                evaluatedAt = now,
            )
        }

        val signalAge = signal?.let { Duration.between(it.executionAt, now) }
        if (signalAge?.isNegative == true) {
            return publish(
                status = VolumeConfirmedTrendLiveLoopStatus.HALTED,
                botMode = botMode,
                shadowSessionId = validShadow.sessionId,
                signal = signal,
                evaluation = liveExecutor.haltForSafety("TREND_SIGNAL_FROM_FUTURE"),
                evaluatedAt = now,
            )
        }
        val freshSignal = signal != null && signalAge != null && signalAge <= config.maximumSignalAge
        if (!freshSignal || botMode != BotMode.RUNNING) {
            val reconciled = liveExecutor.reconcile()
            val mismatchedOpenPosition =
                reconciled.state.observedPositionSide != null &&
                    validShadow.indicatorState.targetSide != null &&
                    reconciled.state.observedPositionSide != validShadow.indicatorState.targetSide
            if (!freshSignal && mismatchedOpenPosition && !reconciled.status.blocksLiveLoop()) {
                return publish(
                    status = VolumeConfirmedTrendLiveLoopStatus.HALTED,
                    botMode = botMode,
                    shadowSessionId = validShadow.sessionId,
                    signal = signal,
                    evaluation = liveExecutor.haltForSafety("TREND_SIGNAL_EXPIRED_WITH_POSITION_MISMATCH"),
                    evaluatedAt = now,
                )
            }
            return publish(
                status =
                    when {
                        reconciled.status.blocksLiveLoop() ->
                            VolumeConfirmedTrendLiveLoopStatus.HALTED
                        botMode != BotMode.RUNNING -> VolumeConfirmedTrendLiveLoopStatus.PAUSED
                        else -> VolumeConfirmedTrendLiveLoopStatus.NO_FRESH_SIGNAL
                    },
                botMode = botMode,
                shadowSessionId = validShadow.sessionId,
                signal = signal,
                evaluation = reconciled,
                evaluatedAt = now,
            )
        }

        val ticker = tickerProvider(config.symbol)
        require(ticker.symbol == config.symbol) { "Trend live ticker symbol does not match its configuration." }
        require(ticker.lastPrice.signum() > 0) { "Trend live ticker price must be positive." }
        val tickerObservedAt = Instant.now(clock)
        if (ticker.capturedAt.isAfter(tickerObservedAt.plus(config.maximumTickerFutureSkew))) {
            return publish(
                status = VolumeConfirmedTrendLiveLoopStatus.HALTED,
                botMode = botMode,
                shadowSessionId = validShadow.sessionId,
                signal = signal,
                evaluation = liveExecutor.haltForSafety("TREND_TICKER_FROM_FUTURE"),
                evaluatedAt = tickerObservedAt,
            )
        }
        if (Duration.between(ticker.capturedAt, tickerObservedAt) > config.maximumTickerAge) {
            return publish(
                status = VolumeConfirmedTrendLiveLoopStatus.HALTED,
                botMode = botMode,
                shadowSessionId = validShadow.sessionId,
                signal = signal,
                evaluation = liveExecutor.haltForSafety("TREND_TICKER_STALE"),
                evaluatedAt = tickerObservedAt,
            )
        }
        val evaluation = liveExecutor.evaluate(requireNotNull(signal), ticker.lastPrice)
        return publish(
            status =
                if (evaluation.status.blocksLiveLoop()) {
                    VolumeConfirmedTrendLiveLoopStatus.HALTED
                } else {
                    VolumeConfirmedTrendLiveLoopStatus.SIGNAL_EVALUATED
                },
            botMode = botMode,
            shadowSessionId = validShadow.sessionId,
            signal = signal,
            evaluation = evaluation,
            evaluatedAt = now,
        )
    }

    fun start(scope: CoroutineScope): Job =
        scope.launch {
            while (isActive) {
                try {
                    runOnce()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    logger.warn("volume-confirmed trend live loop failed", error)
                    notifyTradingLoopFailure(
                        logger = logger,
                        loopName = "volume-confirmed trend live loop",
                        onFailure = onFailure,
                        error = error,
                    )
                }
                delay(config.interval.toMillis())
            }
        }

    private suspend fun publish(
        status: VolumeConfirmedTrendLiveLoopStatus,
        botMode: BotMode,
        shadowSessionId: String?,
        signal: VolumeConfirmedTrendExecutionSignal?,
        evaluation: VolumeConfirmedTrendLiveEvaluationResult,
        evaluatedAt: Instant,
    ): VolumeConfirmedTrendLiveLoopResult =
        VolumeConfirmedTrendLiveLoopResult(
            status = status,
            botMode = botMode,
            shadowSessionId = shadowSessionId,
            signal = signal,
            evaluation = evaluation,
            evaluatedAt = evaluatedAt,
        ).also { onResult(it) }

    private fun VolumeConfirmedTrendShadowState?.invalidReason(): String? =
        when {
            this == null -> "TREND_SHADOW_STATE_UNAVAILABLE"
            protocolId != config.protocolId || candidateId != config.candidateId || protocolSha256 != config.protocolSha256 ->
                "TREND_SHADOW_IDENTITY_MISMATCH"
            sessionId != config.approvedShadowSessionId -> "TREND_SHADOW_APPROVED_SESSION_CHANGED"
            status != VolumeConfirmedTrendShadowStatus.OBSERVING -> "TREND_SHADOW_NOT_OBSERVING"
            else -> null
        }
}

private fun VolumeConfirmedTrendShadowEvent.isConfirmedSideChange(): Boolean =
    type == VolumeConfirmedTrendShadowEventType.H4_EVALUATED &&
        reason == "CONFIRMED_SIDE_CHANGE" &&
        side != null

private fun VolumeConfirmedTrendShadowEvent.toExecutionSignal(): VolumeConfirmedTrendExecutionSignal =
    VolumeConfirmedTrendExecutionSignal(
        side = requireNotNull(side),
        decisionAt = eventAt,
        executionAt = eventAt,
    )

private fun VolumeConfirmedTrendLiveEvaluationStatus.blocksLiveLoop(): Boolean =
    this == VolumeConfirmedTrendLiveEvaluationStatus.HALTED ||
        this == VolumeConfirmedTrendLiveEvaluationStatus.APPROVAL_BLOCKED
