package dev.yaklede.bybittrader.engine.backtest

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.market.MarketCandleStore
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class MeanReversionSweepServiceTest :
    StringSpec({
        "runs train and test backtests for bounded parameter candidates" {
            val service = MeanReversionSweepService(InMemoryCandleStore(sweepCandles()))
            val report =
                service.run(
                    symbol = Symbol("BTCUSDT"),
                    timeframe = Timeframe.M15,
                    candleLimit = 120,
                    backtestConfig = BacktestConfig(partialTakeProfitFraction = 0.0),
                    sweepConfig =
                        MeanReversionSweepConfig(
                            oversoldRsiValues = listOf(30.0, 35.0),
                            overboughtRsiValues = listOf(65.0),
                            bollingerStdDevValues = listOf(1.8),
                            atrStopMultiplierValues = listOf(1.0),
                        ),
                )

            report.candleCount shouldBe 120
            report.trainCandleCount shouldBe 72
            report.testCandleCount shouldBe 48
            report.results.size shouldBe 2
        }

        "rejects too many candidates" {
            shouldThrow<IllegalArgumentException> {
                MeanReversionSweepConfig(
                    oversoldRsiValues = (1..5).map { it.toDouble() },
                    overboughtRsiValues = (60..64).map { it.toDouble() },
                    bollingerStdDevValues = listOf(1.8, 2.0, 2.2),
                    atrStopMultiplierValues = listOf(1.0, 1.2),
                    maxCandidates = 100,
                )
            }
        }
    })

private class InMemoryCandleStore(
    private val candles: List<Candle>,
) : MarketCandleStore {
    override suspend fun upsert(candles: List<Candle>) = Unit

    override suspend fun recentCandles(
        symbol: Symbol,
        timeframe: Timeframe,
        limit: Int,
    ): List<Candle> =
        candles
            .filter { it.symbol == symbol && it.timeframe == timeframe }
            .sortedByDescending { it.openedAt }
            .take(limit)
}

private fun sweepCandles(): List<Candle> =
    (0 until 140).map { index ->
        val base = 100 + ((index % 24) - 12)
        Candle(
            symbol = Symbol("BTCUSDT"),
            timeframe = Timeframe.M15,
            openedAt = Instant.parse("2026-06-30T00:00:00Z").plusSeconds(index * 900L),
            open = BigDecimal(base),
            high = BigDecimal(base + 3),
            low = BigDecimal(base - 3),
            close = BigDecimal(base + (index % 3) - 1),
            volume = BigDecimal("10"),
        )
    }
