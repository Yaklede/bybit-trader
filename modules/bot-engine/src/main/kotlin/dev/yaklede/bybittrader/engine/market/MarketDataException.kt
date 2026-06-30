package dev.yaklede.bybittrader.engine.market

open class MarketDataException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
