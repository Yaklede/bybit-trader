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

internal data class ExecutionProtectionPlan(
    val takeProfit: BigDecimal?,
    val stopLoss: BigDecimal,
    val riskPerUnit: BigDecimal,
)

internal object ExecutionTradePlanCalculator {
    fun calculateProtection(
        side: Side,
        entryPrice: BigDecimal,
        structuralStopPrice: BigDecimal,
        entryAnchoredStopDistance: BigDecimal?,
        expectedR: BigDecimal,
        priceTick: BigDecimal,
        fixedTargetEnabled: Boolean = true,
    ): ExecutionProtectionPlan? {
        if (
            entryPrice <= BigDecimal.ZERO ||
            structuralStopPrice <= BigDecimal.ZERO ||
            expectedR <= BigDecimal.ZERO ||
            priceTick <= BigDecimal.ZERO
        ) {
            return null
        }
        val stopPrice =
            when (side) {
                Side.BUY ->
                    entryAnchoredStopDistance
                        ?.let { distance -> minOf(structuralStopPrice, entryPrice.subtract(distance)) }
                        ?: structuralStopPrice
                Side.SELL ->
                    entryAnchoredStopDistance
                        ?.let { distance -> maxOf(structuralStopPrice, entryPrice.add(distance)) }
                        ?: structuralStopPrice
            }.normalizeProtectionPrice(side = side, priceTick = priceTick, isStop = true)
        val directionalStopIsValid =
            when (side) {
                Side.BUY -> stopPrice > BigDecimal.ZERO && stopPrice < entryPrice
                Side.SELL -> stopPrice > entryPrice
            }
        if (!directionalStopIsValid) return null
        val riskPerUnit = entryPrice.subtract(stopPrice).abs()
        if (!fixedTargetEnabled) {
            return ExecutionProtectionPlan(takeProfit = null, stopLoss = stopPrice, riskPerUnit = riskPerUnit)
        }
        val takeProfit =
            calculateTakeProfit(side, entryPrice, riskPerUnit, expectedR)
                .normalizeProtectionPrice(side = side, priceTick = priceTick, isStop = false)
        val directionalTargetIsValid =
            when (side) {
                Side.BUY -> takeProfit > entryPrice
                Side.SELL -> takeProfit > BigDecimal.ZERO && takeProfit < entryPrice
            }
        return if (directionalTargetIsValid) {
            ExecutionProtectionPlan(takeProfit = takeProfit, stopLoss = stopPrice, riskPerUnit = riskPerUnit)
        } else {
            null
        }
    }

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
        return if (
            quantity > BigDecimal.ZERO &&
            (constraints.minQuantity == null || quantity >= constraints.minQuantity)
        ) {
            ExecutionSizing(quantity)
        } else {
            null
        }
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

    fun costAdjustedRiskPerUnit(
        entryPrice: BigDecimal,
        riskPerUnit: BigDecimal,
        feeRate: BigDecimal,
        slippageBufferRate: BigDecimal,
        exitSlippageRate: BigDecimal = slippageBufferRate,
    ): BigDecimal {
        val roundTripCostRate = roundTripCostRate(feeRate, slippageBufferRate, exitSlippageRate)
        return riskPerUnit.add(entryPrice.multiply(roundTripCostRate, MathContext.DECIMAL64))
    }

    fun targetStopRejection(
        side: Side,
        entryPrice: BigDecimal,
        takeProfit: BigDecimal,
        stopLoss: BigDecimal,
        feeRate: BigDecimal,
        slippageBufferRate: BigDecimal,
        minimumNetRiskReward: BigDecimal = BigDecimal.ONE,
        exitSlippageRate: BigDecimal = slippageBufferRate,
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
        val roundTripCostMove =
            entryPrice.multiply(
                roundTripCostRate(feeRate, slippageBufferRate, exitSlippageRate),
                MathContext.DECIMAL64,
            )
        if (grossTargetMove <= roundTripCostMove) return "TARGET_DOES_NOT_COVER_ROUND_TRIP_FEES"
        val netReward = grossTargetMove.subtract(roundTripCostMove)
        val netRisk = stopMove.add(roundTripCostMove)
        val netRiskReward = netReward.divide(netRisk, MathContext.DECIMAL64)
        return if (netRiskReward < minimumNetRiskReward) "NET_RISK_REWARD_BELOW_MINIMUM" else null
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

    private fun roundTripCostRate(
        feeRate: BigDecimal,
        entrySlippageRate: BigDecimal,
        exitSlippageRate: BigDecimal,
    ): BigDecimal = feeRate.multiply(BigDecimal("2")).add(entrySlippageRate).add(exitSlippageRate)
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

internal fun BigDecimal.ceilToStep(step: BigDecimal): BigDecimal {
    val units = divide(step, 0, RoundingMode.UP)
    return units.multiply(step).stripTrailingZeros()
}

private fun BigDecimal.normalizeProtectionPrice(
    side: Side,
    priceTick: BigDecimal,
    isStop: Boolean,
): BigDecimal {
    if (remainder(priceTick).compareTo(BigDecimal.ZERO) == 0) return this
    return when {
        side == Side.BUY && isStop -> floorToStep(priceTick)
        side == Side.BUY -> floorToStep(priceTick)
        side == Side.SELL && isStop -> ceilToStep(priceTick)
        else -> ceilToStep(priceTick)
    }
}

private fun BigDecimal.normalizeToStep(step: BigDecimal?): BigDecimal = if (step == null) this else floorToStep(step)
