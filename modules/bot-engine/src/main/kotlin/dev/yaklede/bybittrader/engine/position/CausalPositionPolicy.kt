package dev.yaklede.bybittrader.engine.position

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Side
import java.time.Instant
import kotlin.math.abs

/**
 * Incremental position policy shared by historical and forward execution adapters.
 * A candle can only update the stop after all exits for that candle have been resolved.
 */
class CausalPositionPolicy(
    private val config: CausalPositionPolicyConfig,
) {
    fun open(request: CausalPositionOpenRequest): CausalPositionState {
        require(request.entryPrice > 0.0) { "Entry price must be positive." }
        require(request.riskPerUnit > 0.0) { "Risk per unit must be positive." }
        require(request.quantity > 0.0) { "Position quantity must be positive." }
        require(request.expectedR > 0.0) { "Expected R must be positive." }
        require(request.initialStopPrice > 0.0) { "Initial stop price must be positive." }
        require(
            when (request.side) {
                Side.BUY -> request.initialStopPrice < request.entryPrice
                Side.SELL -> request.initialStopPrice > request.entryPrice
            },
        ) { "Initial stop must be on the loss side of the entry." }

        return CausalPositionState(
            side = request.side,
            entryAt = request.entryAt,
            entryPrice = request.entryPrice,
            initialStopPrice = request.initialStopPrice,
            currentStopPrice = request.initialStopPrice,
            riskPerUnit = request.riskPerUnit,
            expectedR = request.expectedR,
            initialQuantity = request.quantity,
            remainingQuantity = request.quantity,
            fullTargetPrice =
                if (config.fixedTargetEnabled) {
                    targetPrice(request.side, request.entryPrice, request.riskPerUnit, request.expectedR)
                } else {
                    null
                },
            partialTargetPrice =
                targetPrice(
                    request.side,
                    request.entryPrice,
                    request.riskPerUnit,
                    config.partialTakeProfitR,
                ),
            bestHigh = request.entryPrice,
            bestLow = request.entryPrice,
        )
    }

    fun onCandle(
        state: CausalPositionState,
        candle: Candle,
        trailingAtr: Double?,
    ): CausalPositionStep {
        require(!candle.openedAt.isBefore(state.entryAt)) { "Position candle must not precede the entry." }

        val stopTouched = candle.touchesStop(state.side, state.currentStopPrice)
        val targetTouched = state.fullTargetPrice?.let { candle.touchesTarget(state.side, it) } == true
        if (stopTouched) {
            return CausalPositionStep(
                state = state,
                exit =
                    CausalPositionExit(
                        exitedAt = candle.openedAt,
                        triggerPrice = state.currentStopPrice,
                        reason = state.stopExitReason(),
                        remainingQuantity = state.remainingQuantity,
                    ),
            )
        }

        var updated = state
        var partialFill: CausalPartialExit? = null
        if (
            !state.partialTaken &&
            config.partialTakeProfitFraction > 0.0 &&
            candle.touchesTarget(state.side, state.partialTargetPrice)
        ) {
            val partialQuantity = state.initialQuantity * config.partialTakeProfitFraction
            val partialGrossPnl =
                grossPnl(
                    side = state.side,
                    entryPrice = state.entryPrice,
                    exitPrice = state.partialTargetPrice,
                    quantity = partialQuantity,
                )
            val partialFee = state.partialTargetPrice * partialQuantity * config.feeRate
            val breakevenStop =
                if (config.breakevenAfterPartialTakeProfit) {
                    when (state.side) {
                        Side.BUY -> maxOf(state.currentStopPrice, state.entryPrice)
                        Side.SELL -> minOf(state.currentStopPrice, state.entryPrice)
                    }
                } else {
                    state.currentStopPrice
                }
            partialFill =
                CausalPartialExit(
                    exitedAt = candle.openedAt,
                    price = state.partialTargetPrice,
                    quantity = partialQuantity,
                    grossPnl = partialGrossPnl,
                    fee = partialFee,
                )
            updated =
                state.copy(
                    currentStopPrice = breakevenStop,
                    remainingQuantity = state.remainingQuantity - partialQuantity,
                    partialTaken = true,
                    partialTakeProfitAt = candle.openedAt,
                    partialExitPrice = state.partialTargetPrice,
                    partialQuantity = partialQuantity,
                    partialGrossPnl = partialGrossPnl,
                    partialFees = partialFee,
                )
        }

        if (targetTouched) {
            return CausalPositionStep(
                state = updated,
                partialExit = partialFill,
                exit =
                    CausalPositionExit(
                        exitedAt = candle.openedAt,
                        triggerPrice = requireNotNull(updated.fullTargetPrice),
                        reason = CausalPositionExitReason.TARGET,
                        remainingQuantity = updated.remainingQuantity,
                    ),
            )
        }

        updated =
            updated.copy(
                bestHigh = maxOf(updated.bestHigh, candle.high.toDouble()),
                bestLow = minOf(updated.bestLow, candle.low.toDouble()),
            )
        if (config.atrTrailingMultiplier > 0.0 && trailingAtr != null) {
            require(trailingAtr >= 0.0) { "Trailing ATR must not be negative." }
            val trailingDistance = trailingAtr * config.atrTrailingMultiplier
            val candidate =
                when (updated.side) {
                    Side.BUY -> updated.bestHigh - trailingDistance
                    Side.SELL -> updated.bestLow.plus(trailingDistance)
                }
            updated =
                updated.copy(
                    currentStopPrice =
                        when (updated.side) {
                            Side.BUY -> maxOf(updated.currentStopPrice, candidate)
                            Side.SELL -> minOf(updated.currentStopPrice, candidate)
                        },
                )
        }

        val elapsedCandles = updated.processedCandles
        updated = updated.copy(processedCandles = elapsedCandles.inc())
        val timeExit =
            if (elapsedCandles >= config.maxHoldCandles) {
                CausalPositionExit(
                    exitedAt = candle.openedAt,
                    triggerPrice = candle.close.toDouble(),
                    reason = CausalPositionExitReason.TIME,
                    remainingQuantity = updated.remainingQuantity,
                )
            } else {
                null
            }
        return CausalPositionStep(
            state = updated,
            partialExit = partialFill,
            exit = timeExit,
        )
    }

    companion object {
        fun trailingAtr(
            candles: List<Candle>,
            currentIndex: Int,
            period: Int,
        ): Double? {
            require(period > 1) { "ATR period must be greater than one." }
            if (currentIndex < period) return null
            return (currentIndex - period until currentIndex)
                .map { index ->
                    val current = candles[index]
                    val high = current.high.toDouble()
                    val low = current.low.toDouble()
                    val previousClose =
                        if (index == 0) {
                            current.close.toDouble()
                        } else {
                            candles[index - 1].close.toDouble()
                        }
                    maxOf(
                        high - low,
                        abs(high - previousClose),
                        abs(low - previousClose),
                    )
                }.average()
        }

        private fun targetPrice(
            side: Side,
            entryPrice: Double,
            riskPerUnit: Double,
            targetR: Double,
        ): Double =
            when (side) {
                Side.BUY -> entryPrice + (riskPerUnit * targetR)
                Side.SELL -> entryPrice - (riskPerUnit * targetR)
            }

        private fun grossPnl(
            side: Side,
            entryPrice: Double,
            exitPrice: Double,
            quantity: Double,
        ): Double =
            when (side) {
                Side.BUY -> (exitPrice - entryPrice) * quantity
                Side.SELL -> (entryPrice - exitPrice) * quantity
            }
    }
}

data class CausalPositionPolicyConfig(
    val feeRate: Double,
    val partialTakeProfitR: Double,
    val partialTakeProfitFraction: Double,
    val breakevenAfterPartialTakeProfit: Boolean,
    val atrTrailingMultiplier: Double,
    val fixedTargetEnabled: Boolean,
    val maxHoldCandles: Int,
) {
    init {
        require(feeRate in 0.0..0.01) { "Fee rate must be between 0 and 0.01." }
        require(partialTakeProfitR > 0.0) { "Partial target R must be positive." }
        require(partialTakeProfitFraction >= 0.0 && partialTakeProfitFraction < 1.0) {
            "Partial take-profit fraction must be between 0 inclusive and 1 exclusive."
        }
        require(atrTrailingMultiplier >= 0.0) { "ATR trailing multiplier must not be negative." }
        require(maxHoldCandles > 0) { "Maximum hold candles must be positive." }
    }
}

data class CausalPositionOpenRequest(
    val side: Side,
    val entryAt: Instant,
    val entryPrice: Double,
    val initialStopPrice: Double,
    val riskPerUnit: Double,
    val expectedR: Double,
    val quantity: Double,
)

data class CausalPositionState(
    val side: Side,
    val entryAt: Instant,
    val entryPrice: Double,
    val initialStopPrice: Double,
    val currentStopPrice: Double,
    val riskPerUnit: Double,
    val expectedR: Double,
    val initialQuantity: Double,
    val remainingQuantity: Double,
    val fullTargetPrice: Double?,
    val partialTargetPrice: Double,
    val bestHigh: Double,
    val bestLow: Double,
    val processedCandles: Int = 0,
    val partialTaken: Boolean = false,
    val partialTakeProfitAt: Instant? = null,
    val partialExitPrice: Double? = null,
    val partialQuantity: Double = 0.0,
    val partialGrossPnl: Double = 0.0,
    val partialFees: Double = 0.0,
)

data class CausalPositionStep(
    val state: CausalPositionState,
    val partialExit: CausalPartialExit? = null,
    val exit: CausalPositionExit? = null,
)

data class CausalPartialExit(
    val exitedAt: Instant,
    val price: Double,
    val quantity: Double,
    val grossPnl: Double,
    val fee: Double,
)

data class CausalPositionExit(
    val exitedAt: Instant,
    val triggerPrice: Double,
    val reason: CausalPositionExitReason,
    val remainingQuantity: Double,
)

enum class CausalPositionExitReason {
    TARGET,
    STOP,
    BREAKEVEN_STOP,
    TRAILING_STOP,
    TIME,
}

private fun Candle.touchesStop(
    side: Side,
    price: Double,
): Boolean =
    when (side) {
        Side.BUY -> low.toDouble() <= price
        Side.SELL -> high.toDouble() >= price
    }

private fun Candle.touchesTarget(
    side: Side,
    price: Double,
): Boolean =
    when (side) {
        Side.BUY -> high.toDouble() >= price
        Side.SELL -> low.toDouble() <= price
    }

private fun CausalPositionState.stopExitReason(): CausalPositionExitReason =
    when {
        currentStopPrice.isCloseTo(initialStopPrice) -> CausalPositionExitReason.STOP
        partialTaken && currentStopPrice.isCloseTo(entryPrice) -> CausalPositionExitReason.BREAKEVEN_STOP
        else -> CausalPositionExitReason.TRAILING_STOP
    }

private fun Double.isCloseTo(other: Double): Boolean = abs(this - other) < 0.00000001
