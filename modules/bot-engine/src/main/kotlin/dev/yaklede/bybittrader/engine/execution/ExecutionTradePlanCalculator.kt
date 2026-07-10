package dev.yaklede.bybittrader.engine.execution

import dev.yaklede.bybittrader.domain.Side
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

internal data class ExecutionSizingConstraints(
    val quantityStep: BigDecimal?,
    val minQuantity: BigDecimal?,
    val maxQuantity: BigDecimal?,
    val maxNotional: BigDecimal?,
    val leverage: BigDecimal?,
)

internal data class ExecutionSizing(
    val quantity: BigDecimal,
)

internal object ExecutionTradePlanCalculator {
    fun calculateSizing(
        entryPrice: BigDecimal,
        riskPerUnit: BigDecimal,
        intendedRisk: BigDecimal,
        accountEquity: BigDecimal,
        constraints: ExecutionSizingConstraints,
    ): ExecutionSizing? {
        if (entryPrice <= BigDecimal.ZERO || riskPerUnit <= BigDecimal.ZERO || intendedRisk <= BigDecimal.ZERO) return null
        var quantity = intendedRisk.divide(riskPerUnit, MathContext.DECIMAL64).normalizeToStep(constraints.quantityStep)
        constraints.maxQuantity?.let { maxQuantity ->
            if (quantity > maxQuantity) quantity = maxQuantity.normalizeToStep(constraints.quantityStep)
        }
        constraints.leverage?.let { leverage ->
            val maxNotionalByLeverage = accountEquity.multiply(leverage, MathContext.DECIMAL64)
            val maxQuantityByLeverage =
                maxNotionalByLeverage
                    .divide(entryPrice, MathContext.DECIMAL64)
                    .normalizeToStep(constraints.quantityStep)
            if (quantity > maxQuantityByLeverage) quantity = maxQuantityByLeverage
        }
        constraints.maxNotional?.let { maxNotional ->
            val maxQuantityByNotional =
                maxNotional
                    .divide(entryPrice, MathContext.DECIMAL64)
                    .normalizeToStep(constraints.quantityStep)
            if (quantity > maxQuantityByNotional) quantity = maxQuantityByNotional
        }
        return if (constraints.minQuantity == null || quantity >= constraints.minQuantity) ExecutionSizing(quantity) else null
    }

    fun calculateTakeProfit(
        side: Side,
        entryPrice: BigDecimal,
        riskPerUnit: BigDecimal,
        expectedR: BigDecimal,
    ): BigDecimal =
        when (side) {
            Side.BUY -> entryPrice.add(riskPerUnit.multiply(expectedR, MathContext.DECIMAL64))
            Side.SELL -> entryPrice.subtract(riskPerUnit.multiply(expectedR, MathContext.DECIMAL64))
        }

    fun targetStopRejection(
        side: Side,
        entryPrice: BigDecimal,
        takeProfit: BigDecimal,
        stopLoss: BigDecimal,
        feeRate: BigDecimal,
        slippageBufferRate: BigDecimal,
    ): String? {
        val grossTargetMove =
            when (side) {
                Side.BUY -> takeProfit.subtract(entryPrice)
                Side.SELL -> entryPrice.subtract(takeProfit)
            }
        val stopMove =
            when (side) {
                Side.BUY -> entryPrice.subtract(stopLoss)
                Side.SELL -> stopLoss.subtract(entryPrice)
            }
        if (grossTargetMove <= BigDecimal.ZERO || stopMove <= BigDecimal.ZERO) return "INVALID_TARGET_STOP_GEOMETRY"
        val roundTripCostRate = feeRate.multiply(BigDecimal("2")).add(slippageBufferRate)
        val roundTripCostMove = entryPrice.multiply(roundTripCostRate, MathContext.DECIMAL64)
        return if (grossTargetMove <= roundTripCostMove) "TARGET_DOES_NOT_COVER_ROUND_TRIP_FEES" else null
    }

    fun leverageStopRejection(
        side: Side,
        entryPrice: BigDecimal,
        stopLoss: BigDecimal,
        leverage: BigDecimal?,
        liquidationBufferPct: BigDecimal,
    ): String? {
        if (leverage == null) return null
        val liquidationDistanceRate =
            BigDecimal.ONE
                .divide(leverage, MathContext.DECIMAL64)
                .subtract(liquidationBufferPct.divide(BigDecimal("100"), MathContext.DECIMAL64))
        if (liquidationDistanceRate <= BigDecimal.ZERO) return "INVALID_LIQUIDATION_BUFFER"
        val stopDistanceRate =
            when (side) {
                Side.BUY -> entryPrice.subtract(stopLoss)
                Side.SELL -> stopLoss.subtract(entryPrice)
            }.divide(entryPrice, MathContext.DECIMAL64)
        return if (stopDistanceRate >= liquidationDistanceRate) "STOP_REACHES_ESTIMATED_LIQUIDATION" else null
    }
}

internal fun ExchangeExecutionConfig.sizingConstraints(): ExecutionSizingConstraints =
    ExecutionSizingConstraints(
        quantityStep = quantityStep,
        minQuantity = minQuantity,
        maxQuantity = maxQuantity,
        maxNotional = maxNotional,
        leverage = leverage,
    )

internal fun BigDecimal.floorToStep(step: BigDecimal): BigDecimal {
    val units = divide(step, 0, RoundingMode.DOWN)
    return units.multiply(step).stripTrailingZeros()
}

private fun BigDecimal.normalizeToStep(step: BigDecimal?): BigDecimal = if (step == null) this else floorToStep(step)
