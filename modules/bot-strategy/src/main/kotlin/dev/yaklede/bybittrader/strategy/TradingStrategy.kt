package dev.yaklede.bybittrader.strategy

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.SignalIntent

interface TradingStrategy {
    val name: String
    val warmupCandles: Int

    fun evaluate(candles: List<Candle>): StrategyDecision
}

data class StrategyDecision(
    val intent: SignalIntent?,
    val reasonCodes: List<String>,
) {
    companion object {
        fun noTrade(vararg reasonCodes: String): StrategyDecision = StrategyDecision(intent = null, reasonCodes = reasonCodes.toList())
    }
}
