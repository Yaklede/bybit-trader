package dev.yaklede.bybittrader.domain

import java.math.BigDecimal
import java.time.Instant

@JvmInline
value class Symbol(
    val value: String,
) {
    init {
        require(value.matches(Regex("[A-Z0-9]{3,30}"))) {
            "Symbol must use uppercase exchange format."
        }
    }
}

enum class Timeframe {
    M1,
    M5,
    M15,
    H1,
}

enum class Side {
    BUY,
    SELL,
}

enum class OrderType {
    MARKET,
    LIMIT,
}

enum class OrderStatus {
    CREATED,
    SUBMITTED,
    PARTIALLY_FILLED,
    FILLED,
    CANCELLED,
    REJECTED,
}

data class Candle(
    val symbol: Symbol,
    val timeframe: Timeframe,
    val openedAt: Instant,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: BigDecimal,
) {
    init {
        require(high >= low) { "Candle high must be greater than or equal to low." }
        require(volume >= BigDecimal.ZERO) { "Candle volume must not be negative." }
    }
}
