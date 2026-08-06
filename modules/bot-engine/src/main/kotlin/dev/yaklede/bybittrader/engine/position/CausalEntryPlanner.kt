package dev.yaklede.bybittrader.engine.position

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.SignalIntent
import java.time.Instant
import kotlin.math.abs

class CausalEntryPlanner(
    private val config: CausalEntryPolicyConfig,
) {
    fun plan(request: CausalEntryPlanRequest): CausalEntryPlanResult {
        if (config.maxTradesPerUtcDay != null && request.entriesOnEntryUtcDay >= config.maxTradesPerUtcDay) {
            return CausalEntryPlanResult.rejected("MAX_TRADES_PER_UTC_DAY")
        }

        val rawEntryPrice = request.entryCandle.open.toDouble()
        val entryPrice =
            when (request.signal.side) {
                Side.BUY -> rawEntryPrice * (1.0 + config.entrySlippageRate)
                Side.SELL -> rawEntryPrice * (1.0 - config.entrySlippageRate)
            }
        val structuralStopPrice =
            request.signal.invalidationPrice.value
                .toDouble()
        val initialStopPrice =
            request.signal.entryAnchoredStopDistance?.toDouble()?.let { stopDistance ->
                when (request.signal.side) {
                    Side.BUY -> minOf(structuralStopPrice, entryPrice - stopDistance)
                    Side.SELL -> maxOf(structuralStopPrice, entryPrice + stopDistance)
                }
            } ?: structuralStopPrice
        val directionalStopIsValid =
            when (request.signal.side) {
                Side.BUY -> initialStopPrice > 0.0 && initialStopPrice < entryPrice
                Side.SELL -> initialStopPrice > entryPrice
            }
        if (!directionalStopIsValid) return CausalEntryPlanResult.rejected("INVALID_STOP_DIRECTION")

        val riskPerUnit = abs(entryPrice - initialStopPrice)
        if (riskPerUnit <= 0.0) return CausalEntryPlanResult.rejected("INVALID_RISK_DISTANCE")
        val entryRiskFraction = riskPerUnit / entryPrice
        if (config.minimumEntryRiskFraction != null && entryRiskFraction < config.minimumEntryRiskFraction) {
            return CausalEntryPlanResult.rejected("ENTRY_RISK_BELOW_MINIMUM")
        }
        if (config.maximumEntryRiskFraction != null && entryRiskFraction > config.maximumEntryRiskFraction) {
            return CausalEntryPlanResult.rejected("ENTRY_RISK_ABOVE_MAXIMUM")
        }

        val riskAmount = request.equity * config.riskFraction
        val quantity = riskAmount / riskPerUnit
        return CausalEntryPlanResult(
            plan =
                CausalEntryPlan(
                    signalAt = request.signalAt,
                    entryAt = request.entryCandle.openedAt,
                    side = request.signal.side,
                    rawEntryPrice = rawEntryPrice,
                    entryPrice = entryPrice,
                    structuralStopPrice = structuralStopPrice,
                    initialStopPrice = initialStopPrice,
                    riskPerUnit = riskPerUnit,
                    entryRiskFraction = entryRiskFraction,
                    riskAmount = riskAmount,
                    quantity = quantity,
                    expectedR = request.signal.expectedR.toDouble(),
                ),
            rejectionReason = null,
        )
    }
}

data class CausalEntryPolicyConfig(
    val riskFraction: Double,
    val entrySlippageRate: Double,
    val maxTradesPerUtcDay: Int?,
    val minimumEntryRiskFraction: Double?,
    val maximumEntryRiskFraction: Double?,
) {
    init {
        require(riskFraction > 0.0 && riskFraction <= 0.05) { "Risk fraction must be between 0 and 0.05." }
        require(entrySlippageRate in 0.0..0.01) { "Entry slippage must be between 0 and 0.01." }
        require(maxTradesPerUtcDay == null || maxTradesPerUtcDay > 0) {
            "Maximum daily trades must be positive when configured."
        }
        require(minimumEntryRiskFraction == null || minimumEntryRiskFraction > 0.0) {
            "Minimum entry risk must be positive when configured."
        }
        require(maximumEntryRiskFraction == null || maximumEntryRiskFraction > 0.0) {
            "Maximum entry risk must be positive when configured."
        }
        require(
            minimumEntryRiskFraction == null ||
                maximumEntryRiskFraction == null ||
                minimumEntryRiskFraction <= maximumEntryRiskFraction,
        ) { "Minimum entry risk must not exceed maximum entry risk." }
    }
}

data class CausalEntryPlanRequest(
    val signal: SignalIntent,
    val signalAt: Instant,
    val entryCandle: Candle,
    val equity: Double,
    val entriesOnEntryUtcDay: Int,
) {
    init {
        require(equity > 0.0) { "Entry equity must be positive." }
        require(entriesOnEntryUtcDay >= 0) { "Daily entry count must not be negative." }
        require(entryCandle.symbol == signal.symbol) { "Entry candle and signal symbols must match." }
    }
}

data class CausalEntryPlanResult(
    val plan: CausalEntryPlan?,
    val rejectionReason: String?,
) {
    init {
        require((plan == null) != (rejectionReason == null)) {
            "Entry planning must return either a plan or a rejection reason."
        }
    }

    companion object {
        fun rejected(reason: String): CausalEntryPlanResult {
            require(reason.isNotBlank()) { "Entry rejection reason must not be blank." }
            return CausalEntryPlanResult(plan = null, rejectionReason = reason)
        }
    }
}

data class CausalEntryPlan(
    val signalAt: Instant,
    val entryAt: Instant,
    val side: Side,
    val rawEntryPrice: Double,
    val entryPrice: Double,
    val structuralStopPrice: Double,
    val initialStopPrice: Double,
    val riskPerUnit: Double,
    val entryRiskFraction: Double,
    val riskAmount: Double,
    val quantity: Double,
    val expectedR: Double,
)
