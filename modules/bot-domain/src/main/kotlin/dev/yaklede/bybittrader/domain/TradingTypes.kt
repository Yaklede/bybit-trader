package dev.yaklede.bybittrader.domain

import java.math.BigDecimal
import java.time.Instant

@JvmInline
value class Quantity(
    val value: BigDecimal,
) {
    init {
        require(value > BigDecimal.ZERO) { "Quantity must be positive." }
    }
}

@JvmInline
value class Price(
    val value: BigDecimal,
) {
    init {
        require(value > BigDecimal.ZERO) { "Price must be positive." }
    }
}

data class SignalScore(
    val total: Int,
    val reasonCodes: List<String>,
) {
    init {
        require(total in 0..100) { "Signal score must be between 0 and 100." }
    }
}

data class SignalIntent(
    val symbol: Symbol,
    val side: Side,
    val strategy: String,
    val score: SignalScore,
    val invalidationPrice: Price,
    val expectedR: BigDecimal,
) {
    init {
        require(strategy.isNotBlank()) { "Strategy must not be blank." }
        require(expectedR > BigDecimal.ZERO) { "Expected R must be positive." }
    }
}

data class RiskDecision(
    val accepted: Boolean,
    val riskFraction: BigDecimal,
    val reasonCode: String,
) {
    init {
        require(riskFraction >= BigDecimal.ZERO) { "Risk fraction must not be negative." }
        require(reasonCode.isNotBlank()) { "Reason code must not be blank." }
    }
}

data class PositionSnapshot(
    val symbol: Symbol,
    val side: Side,
    val quantity: Quantity,
    val entryPrice: Price,
    val updatedAt: Instant,
)

data class OrderIntent(
    val symbol: Symbol,
    val side: Side,
    val type: OrderType,
    val quantity: Quantity,
    val linkedSignalId: String?,
    val manualReason: String?,
) {
    init {
        require(linkedSignalId != null || !manualReason.isNullOrBlank()) {
            "Order intent needs a linked signal or manual reason."
        }
    }
}

data class FillEvent(
    val orderId: String,
    val price: Price,
    val quantity: Quantity,
    val fee: BigDecimal,
    val filledAt: Instant,
) {
    init {
        require(orderId.isNotBlank()) { "Order id must not be blank." }
        require(fee >= BigDecimal.ZERO) { "Fee must not be negative." }
    }
}
