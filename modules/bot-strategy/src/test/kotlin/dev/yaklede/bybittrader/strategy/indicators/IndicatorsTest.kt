package dev.yaklede.bybittrader.strategy.indicators

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

class IndicatorsTest :
    StringSpec({
        "calculates RSI from fixed closes" {
            val rsi = Indicators.rsi(listOf(44.0, 44.15, 43.9, 44.35, 44.8, 45.1, 44.7, 45.3), period = 3)

            rsi!! shouldBe (75.71 plusOrMinus 0.1)
        }

        "calculates Bollinger bands from fixed closes" {
            val bands = Indicators.bollingerBands(listOf(10.0, 12.0, 14.0, 16.0, 18.0), period = 5, standardDeviationMultiplier = 2.0)

            bands!!.middle shouldBe 14.0
            bands.upper shouldBe (19.65 plusOrMinus 0.1)
            bands.lower shouldBe (8.35 plusOrMinus 0.1)
        }
    })
