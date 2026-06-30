package dev.yaklede.bybittrader.strategy

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Price
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.SignalIntent
import dev.yaklede.bybittrader.domain.SignalScore
import dev.yaklede.bybittrader.strategy.indicators.Indicators
import java.math.BigDecimal
import kotlin.math.abs
import kotlin.math.roundToInt

class MeanReversionStrategy(
    private val parameters: MeanReversionParameters = MeanReversionParameters(),
) : TradingStrategy {
    override val name: String = "mean-reversion-v1"
    override val warmupCandles: Int = parameters.minimumCandles

    override fun evaluate(candles: List<Candle>): StrategyDecision {
        if (candles.size < parameters.minimumCandles) {
            return StrategyDecision.noTrade("INSUFFICIENT_HISTORY")
        }
        val closes = candles.map { it.close.toDouble() }
        val latest = candles.last()
        val latestClose = latest.close.toDouble()
        val bands =
            Indicators.bollingerBands(
                values = closes,
                period = parameters.bollingerPeriod,
                standardDeviationMultiplier = parameters.bollingerStdDev,
            ) ?: return StrategyDecision.noTrade("NO_BOLLINGER")
        val rsi =
            Indicators.rsi(closes, parameters.rsiPeriod)
                ?: return StrategyDecision.noTrade("NO_RSI")
        val atr =
            Indicators.atr(candles, parameters.atrPeriod)
                ?: return StrategyDecision.noTrade("NO_ATR")

        return when {
            latestClose <= bands.lower && rsi <= parameters.oversoldRsi ->
                createDecision(
                    latest = latest,
                    side = Side.BUY,
                    close = latestClose,
                    bandMiddle = bands.middle,
                    bandWidth = bands.upper - bands.lower,
                    atr = atr,
                    reasonCodes = listOf("LOWER_BAND_REVERSION", "RSI_OVERSOLD"),
                )

            latestClose >= bands.upper && rsi >= parameters.overboughtRsi ->
                createDecision(
                    latest = latest,
                    side = Side.SELL,
                    close = latestClose,
                    bandMiddle = bands.middle,
                    bandWidth = bands.upper - bands.lower,
                    atr = atr,
                    reasonCodes = listOf("UPPER_BAND_REVERSION", "RSI_OVERBOUGHT"),
                )

            else -> StrategyDecision.noTrade("NO_EDGE")
        }
    }

    private fun createDecision(
        latest: Candle,
        side: Side,
        close: Double,
        bandMiddle: Double,
        bandWidth: Double,
        atr: Double,
        reasonCodes: List<String>,
    ): StrategyDecision {
        val distanceScore =
            if (bandWidth <= 0.0) {
                0
            } else {
                ((abs(close - bandMiddle) / bandWidth) * 30).roundToInt()
            }
        val score = (70 + distanceScore).coerceAtMost(95)
        val invalidationPrice =
            when (side) {
                Side.BUY -> (close - (atr * parameters.atrStopMultiplier)).coerceAtLeast(0.00000001)
                Side.SELL -> close + (atr * parameters.atrStopMultiplier)
            }

        return StrategyDecision(
            intent =
                SignalIntent(
                    symbol = latest.symbol,
                    side = side,
                    strategy = name,
                    score = SignalScore(total = score, reasonCodes = reasonCodes),
                    invalidationPrice = Price(BigDecimal.valueOf(invalidationPrice)),
                    expectedR = BigDecimal.valueOf(parameters.expectedR),
                ),
            reasonCodes = reasonCodes,
        )
    }
}

data class MeanReversionParameters(
    val bollingerPeriod: Int = 20,
    val bollingerStdDev: Double = 2.0,
    val rsiPeriod: Int = 14,
    val atrPeriod: Int = 14,
    val oversoldRsi: Double = 30.0,
    val overboughtRsi: Double = 70.0,
    val atrStopMultiplier: Double = 1.2,
    val expectedR: Double = 1.5,
) {
    val minimumCandles: Int = maxOf(bollingerPeriod, rsiPeriod + 1, atrPeriod + 1)

    init {
        require(bollingerPeriod > 1) { "Bollinger period must be greater than 1." }
        require(bollingerStdDev > 0.0) { "Bollinger standard deviation must be positive." }
        require(rsiPeriod > 1) { "RSI period must be greater than 1." }
        require(atrPeriod > 1) { "ATR period must be greater than 1." }
        require(oversoldRsi in 0.0..100.0) { "Oversold RSI must be between 0 and 100." }
        require(overboughtRsi in 0.0..100.0) { "Overbought RSI must be between 0 and 100." }
        require(oversoldRsi < overboughtRsi) { "Oversold RSI must be lower than overbought RSI." }
        require(atrStopMultiplier > 0.0) { "ATR stop multiplier must be positive." }
        require(expectedR > 0.0) { "Expected R must be positive." }
    }
}
