package dev.yaklede.bybittrader.engine.strategy

import dev.yaklede.bybittrader.domain.Side
import java.time.Instant
import kotlin.math.abs
import kotlin.math.max

data class VolumeConfirmedTrendOpenPosition(
    val side: Side,
    val quantity: Double,
    val entryAt: Instant,
    val entryPrice: Double,
    val entryFee: Double,
    val fundingPnl: Double,
)

data class VolumeConfirmedTrendEntryExecution(
    val position: VolumeConfirmedTrendOpenPosition,
    val referencePrice: Double,
    val fillPrice: Double,
    val quantity: Double,
    val fee: Double,
    val slippage: Double,
    val cashAfter: Double,
    val equityAfter: Double,
    val exposureFraction: Double,
)

data class VolumeConfirmedTrendExitExecution(
    val position: VolumeConfirmedTrendOpenPosition,
    val referencePrice: Double,
    val fillPrice: Double,
    val fee: Double,
    val slippage: Double,
    val grossPnl: Double,
    val netPnl: Double,
    val cashAfter: Double,
)

data class VolumeConfirmedTrendFundingExecution(
    val position: VolumeConfirmedTrendOpenPosition,
    val fundingPnl: Double,
    val cashAfter: Double,
    val equityAfter: Double,
)

data class VolumeConfirmedTrendIntrabarRisk(
    val openEquity: Double,
    val favorableEquity: Double,
    val adverseEquity: Double,
    val peakEquity: Double,
    val drawdownPct: Double,
    val adverseExposureFraction: Double,
    val liquidationObserved: Boolean,
)

object VolumeConfirmedTrendExecutionModel {
    fun open(
        cash: Double,
        side: Side,
        referencePrice: Double,
        at: Instant,
        contract: VolumeConfirmedTrendExecutionContract,
        costMultiplier: Double = 1.0,
    ): VolumeConfirmedTrendEntryExecution? {
        require(cash > 0.0 && cash.isFinite()) { "Trend execution cash must be positive and finite." }
        require(referencePrice > 0.0 && referencePrice.isFinite()) {
            "Trend execution reference price must be positive and finite."
        }
        require(costMultiplier >= 1.0 && costMultiplier.isFinite()) {
            "Trend execution cost multiplier must be finite and at least one."
        }
        val slippageRate = contract.oneWaySlippageRate * costMultiplier
        val feeRate = contract.oneWayFeeRate * costMultiplier
        val fillPrice = referencePrice * (1.0 + side.sign * slippageRate)
        val quantity = VolumeConfirmedTrendEngine.quantity(cash, fillPrice, contract)
        if (quantity <= 0.0) return null
        val fee = quantity * fillPrice * feeRate
        val slippage = quantity * abs(fillPrice - referencePrice)
        val cashAfter = cash - fee
        val position =
            VolumeConfirmedTrendOpenPosition(
                side = side,
                quantity = quantity,
                entryAt = at,
                entryPrice = fillPrice,
                entryFee = fee,
                fundingPnl = 0.0,
            )
        return VolumeConfirmedTrendEntryExecution(
            position = position,
            referencePrice = referencePrice,
            fillPrice = fillPrice,
            quantity = quantity,
            fee = fee,
            slippage = slippage,
            cashAfter = cashAfter,
            equityAfter = markEquity(cashAfter, position, referencePrice),
            exposureFraction = quantity * fillPrice / cash,
        )
    }

    fun close(
        cash: Double,
        position: VolumeConfirmedTrendOpenPosition,
        referencePrice: Double,
        contract: VolumeConfirmedTrendExecutionContract,
        costMultiplier: Double = 1.0,
    ): VolumeConfirmedTrendExitExecution {
        require(cash.isFinite()) { "Trend execution cash must be finite." }
        require(referencePrice > 0.0 && referencePrice.isFinite()) {
            "Trend execution reference price must be positive and finite."
        }
        require(costMultiplier >= 1.0 && costMultiplier.isFinite()) {
            "Trend execution cost multiplier must be finite and at least one."
        }
        val slippageRate = contract.oneWaySlippageRate * costMultiplier
        val feeRate = contract.oneWayFeeRate * costMultiplier
        val fillPrice = referencePrice * (1.0 - position.side.sign * slippageRate)
        val grossPnl = position.side.sign * position.quantity * (fillPrice - position.entryPrice)
        val fee = position.quantity * fillPrice * feeRate
        val slippage = position.quantity * abs(fillPrice - referencePrice)
        return VolumeConfirmedTrendExitExecution(
            position = position,
            referencePrice = referencePrice,
            fillPrice = fillPrice,
            fee = fee,
            slippage = slippage,
            grossPnl = grossPnl,
            netPnl = grossPnl + position.fundingPnl - position.entryFee - fee,
            cashAfter = cash + grossPnl - fee,
        )
    }

    fun applyFunding(
        cash: Double,
        position: VolumeConfirmedTrendOpenPosition,
        settlementPrice: Double,
        fundingRate: Double,
    ): VolumeConfirmedTrendFundingExecution {
        require(cash.isFinite()) { "Trend execution cash must be finite." }
        require(settlementPrice > 0.0 && settlementPrice.isFinite()) {
            "Trend funding settlement price must be positive and finite."
        }
        require(fundingRate.isFinite()) { "Trend funding rate must be finite." }
        val fundingPnl = -position.side.sign * position.quantity * settlementPrice * fundingRate
        val fundedPosition = position.copy(fundingPnl = position.fundingPnl + fundingPnl)
        val cashAfter = cash + fundingPnl
        return VolumeConfirmedTrendFundingExecution(
            position = fundedPosition,
            fundingPnl = fundingPnl,
            cashAfter = cashAfter,
            equityAfter = markEquity(cashAfter, fundedPosition, settlementPrice),
        )
    }

    fun markEquity(
        cash: Double,
        position: VolumeConfirmedTrendOpenPosition?,
        price: Double,
    ): Double {
        require(cash.isFinite()) { "Trend execution cash must be finite." }
        require(price > 0.0 && price.isFinite()) { "Trend mark price must be positive and finite." }
        return cash + (position?.let { it.side.sign * it.quantity * (price - it.entryPrice) } ?: 0.0)
    }

    fun observeIntrabar(
        cash: Double,
        position: VolumeConfirmedTrendOpenPosition,
        bar: VolumeConfirmedTrendBar,
        peakEquity: Double,
    ): VolumeConfirmedTrendIntrabarRisk {
        require(peakEquity > 0.0 && peakEquity.isFinite()) { "Trend peak equity must be positive and finite." }
        val openEquity = markEquity(cash, position, bar.open)
        val favorablePrice = if (position.side == Side.BUY) bar.high else bar.low
        val adversePrice = if (position.side == Side.BUY) bar.low else bar.high
        val favorableEquity = markEquity(cash, position, favorablePrice)
        val adverseEquity = markEquity(cash, position, adversePrice)
        val nextPeak = max(peakEquity, max(openEquity, favorableEquity))
        return VolumeConfirmedTrendIntrabarRisk(
            openEquity = openEquity,
            favorableEquity = favorableEquity,
            adverseEquity = adverseEquity,
            peakEquity = nextPeak,
            drawdownPct = drawdownPct(nextPeak, adverseEquity),
            adverseExposureFraction = position.quantity * bar.open / max(adverseEquity, 1e-12),
            liquidationObserved = adverseEquity <= 0.0,
        )
    }
}

internal val Side.trendSign: Int
    get() = if (this == Side.BUY) 1 else -1

private val Side.sign: Int
    get() = trendSign

internal fun trendDrawdownPct(
    peak: Double,
    equity: Double,
): Double = drawdownPct(peak, equity)

private fun drawdownPct(
    peak: Double,
    equity: Double,
): Double = if (peak <= 0.0) 100.0 else ((peak - equity) / peak * 100.0).coerceAtLeast(0.0)
