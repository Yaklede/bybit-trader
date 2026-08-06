package dev.yaklede.bybittrader.engine.execution

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.engine.position.CausalPositionExitReason
import dev.yaklede.bybittrader.engine.position.CausalPositionOpenRequest
import dev.yaklede.bybittrader.engine.position.CausalPositionPolicy
import java.math.BigDecimal
import java.time.Instant

internal class AutomaticPositionPolicyEngine(
    private val policy: AutomaticPositionPolicy,
    feeRate: BigDecimal,
    private val priceTick: BigDecimal,
) {
    private val causalPolicy = CausalPositionPolicy(policy.causalConfig(feeRate))

    init {
        require(policy.partialTakeProfitFraction == 0.0) {
            "Automatic live position policy does not support partial take-profit execution yet."
        }
        require(priceTick > BigDecimal.ZERO) { "Automatic position price tick must be positive." }
    }

    fun open(
        lifecycle: ExecutionLifecycleEvent,
        position: ExchangePosition,
        entryAt: Instant,
        protection: ExecutionProtectionPlan,
        updatedAt: Instant,
    ): ExecutionPositionRuntimeState {
        val entryPrice = requireNotNull(position.entryPrice) { "Automatic position entry price is required." }
        val expectedR = requireNotNull(lifecycle.expectedR) { "Automatic position expected R is required." }
        val riskPerUnit = entryPrice.subtract(protection.stopLoss).abs()
        val opened =
            causalPolicy
                .open(
                    CausalPositionOpenRequest(
                        side = position.side,
                        entryAt = entryAt,
                        entryPrice = entryPrice.toDouble(),
                        initialStopPrice = protection.stopLoss.toDouble(),
                        riskPerUnit = riskPerUnit.toDouble(),
                        expectedR = expectedR.toDouble(),
                        quantity = position.size.toDouble(),
                    ),
                ).copy(
                    fullTargetPrice = protection.takeProfit?.toDouble(),
                )
        return ExecutionPositionRuntimeState(
            mode = lifecycle.mode,
            lifecycleId = lifecycle.lifecycleId,
            symbol = position.symbol,
            timeframe = policy.timeframe,
            lastProcessedCandleAt = null,
            policyState = opened,
            updatedAt = updatedAt,
        )
    }

    fun advance(
        runtime: ExecutionPositionRuntimeState,
        closedCandles: List<Candle>,
        closedBefore: Instant,
    ): AutomaticPositionPolicyDecision {
        require(runtime.timeframe == policy.timeframe) { "Automatic position timeframe does not match its policy." }
        val candles = closedCandles.sortedBy(Candle::openedAt)
        val expectedOpenedAt = runtime.nextExpectedCandleAt()
        if (!expectedOpenedAt.isBefore(closedBefore)) {
            return AutomaticPositionPolicyDecision.Waiting("POSITION_POLICY_AWAITING_CLOSED_CANDLE")
        }
        val newCandles = candles.filter { candle -> !candle.openedAt.isBefore(expectedOpenedAt) }
        val candle =
            newCandles.firstOrNull()
                ?: return AutomaticPositionPolicyDecision.Failure("POSITION_POLICY_CANDLE_GAP")
        if (candle.openedAt != expectedOpenedAt || newCandles.size != 1) {
            return AutomaticPositionPolicyDecision.Failure("POSITION_POLICY_CANDLE_GAP")
        }
        val currentIndex = candles.indexOfFirst { it.openedAt == candle.openedAt }
        val trailingAtr =
            if (policy.atrTrailingMultiplier > 0.0) {
                if (!candles.hasContiguousHistory(currentIndex, policy.atrTrailingPeriod, policy.candleDuration)) {
                    return AutomaticPositionPolicyDecision.Failure("POSITION_POLICY_ATR_HISTORY_GAP")
                }
                CausalPositionPolicy.trailingAtr(candles, currentIndex, policy.atrTrailingPeriod)
                    ?: return AutomaticPositionPolicyDecision.Failure("POSITION_POLICY_ATR_HISTORY_GAP")
            } else {
                null
            }
        val step = causalPolicy.onCandle(runtime.policyState, candle, trailingAtr)
        if (step.partialExit != null) {
            return AutomaticPositionPolicyDecision.Failure("POSITION_POLICY_PARTIAL_EXIT_UNSUPPORTED")
        }
        val normalizedState =
            step.state.copy(
                currentStopPrice = normalizeStop(step.state.side, BigDecimal.valueOf(step.state.currentStopPrice)).toDouble(),
            )
        val updated =
            runtime.copy(
                lastProcessedCandleAt = candle.openedAt,
                policyState = normalizedState,
                updatedAt = closedBefore,
            )
        return step.exit?.let { exit ->
            AutomaticPositionPolicyDecision.Exit(updated, exit.reason)
        } ?: AutomaticPositionPolicyDecision.Update(
            state = updated,
            takeProfit = normalizedState.fullTargetPrice?.let(BigDecimal::valueOf),
            stopLoss = BigDecimal.valueOf(normalizedState.currentStopPrice),
        )
    }

    private fun normalizeStop(
        side: Side,
        stopLoss: BigDecimal,
    ): BigDecimal =
        when (side) {
            Side.BUY -> stopLoss.floorToStep(priceTick)
            Side.SELL -> stopLoss.ceilToStep(priceTick)
        }
}

internal sealed interface AutomaticPositionPolicyDecision {
    data class Waiting(
        val reasonCode: String,
    ) : AutomaticPositionPolicyDecision

    data class Update(
        val state: ExecutionPositionRuntimeState,
        val takeProfit: BigDecimal?,
        val stopLoss: BigDecimal,
    ) : AutomaticPositionPolicyDecision

    data class Exit(
        val state: ExecutionPositionRuntimeState,
        val reason: CausalPositionExitReason,
    ) : AutomaticPositionPolicyDecision

    data class Failure(
        val reasonCode: String,
    ) : AutomaticPositionPolicyDecision
}

private fun ExecutionPositionRuntimeState.nextExpectedCandleAt(): Instant {
    lastProcessedCandleAt?.let { return it.plus(timeframe.executionDuration) }
    val durationMillis = timeframe.executionDuration.toMillis()
    val entryMillis = policyState.entryAt.toEpochMilli()
    val alignedMillis =
        if (entryMillis % durationMillis == 0L) {
            entryMillis
        } else {
            ((entryMillis / durationMillis) + 1L) * durationMillis
        }
    return Instant.ofEpochMilli(alignedMillis)
}

private fun List<Candle>.hasContiguousHistory(
    currentIndex: Int,
    period: Int,
    candleDuration: java.time.Duration,
): Boolean {
    if (currentIndex < period) return false
    return (currentIndex - period + 1..currentIndex).all { index ->
        this[index].openedAt == this[index - 1].openedAt.plus(candleDuration)
    }
}
