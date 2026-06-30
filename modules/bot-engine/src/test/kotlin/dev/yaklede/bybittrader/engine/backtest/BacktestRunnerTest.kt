package dev.yaklede.bybittrader.engine.backtest

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Price
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.SignalIntent
import dev.yaklede.bybittrader.domain.SignalScore
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.strategy.StrategyDecision
import dev.yaklede.bybittrader.strategy.TradingStrategy
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class BacktestRunnerTest :
    StringSpec({
        "runner calculates pnl and expected monthly return from completed trades" {
            val runner = BacktestRunner(AlwaysBuyStrategy())
            val result =
                runner.run(
                    candles = risingCandles(),
                    config =
                        BacktestConfig(
                            initialEquity = 10_000.0,
                            riskFraction = 0.01,
                            feeRate = 0.0,
                            slippageRate = 0.0,
                            maxHoldCandles = 3,
                        ),
                )

            result.trades.size shouldBe 2
            result.wins shouldBe 2
            result.netPnl.shouldBeGreaterThan(0.0)
            result.expectedMonthlyReturnPct!!.shouldBeGreaterThan(0.0)
            result.acceptedSignals shouldBe result.trades.size
            result.trades
                .first()
                .partialQuantity
                .shouldBeGreaterThan(0.0)
        }

        "runner subtracts fees and positive long funding from net pnl" {
            val runner = BacktestRunner(SlowBuyStrategy())
            val result =
                runner.run(
                    candles = flatCandles(),
                    config =
                        BacktestConfig(
                            initialEquity = 10_000.0,
                            riskFraction = 0.01,
                            feeRate = 0.001,
                            slippageRate = 0.0,
                            fundingRatePer8h = 0.001,
                            partialTakeProfitFraction = 0.0,
                            maxHoldCandles = 3,
                        ),
                )

            result.grossPnl shouldBe 0.0
            result.fees.shouldBeGreaterThan(0.0)
            result.fundingCost.shouldBeGreaterThan(0.0)
            result.netPnl.shouldBeLessThan(0.0)
        }

        "runner counts no trade reasons" {
            val runner = BacktestRunner(NoTradeStrategy())
            val result = runner.run(risingCandles())

            result.trades.size shouldBe 0
            result.skippedSignals shouldBe result.evaluatedWindows
            result.noTradeReasonCounts["TEST_NO_EDGE"] shouldBe result.evaluatedWindows
        }
    })

private class AlwaysBuyStrategy : TradingStrategy {
    override val name: String = "always-buy-test"
    override val warmupCandles: Int = 2

    override fun evaluate(candles: List<Candle>): StrategyDecision {
        val latest = candles.last()
        return StrategyDecision(
            intent =
                SignalIntent(
                    symbol = latest.symbol,
                    side = Side.BUY,
                    strategy = name,
                    score = SignalScore(80, listOf("TEST")),
                    invalidationPrice = Price(latest.close - BigDecimal("5")),
                    expectedR = BigDecimal("1.5"),
                ),
            reasonCodes = listOf("TEST"),
        )
    }
}

private class SlowBuyStrategy : TradingStrategy {
    override val name: String = "slow-buy-test"
    override val warmupCandles: Int = 2

    override fun evaluate(candles: List<Candle>): StrategyDecision {
        val latest = candles.last()
        return StrategyDecision(
            intent =
                SignalIntent(
                    symbol = latest.symbol,
                    side = Side.BUY,
                    strategy = name,
                    score = SignalScore(80, listOf("TEST")),
                    invalidationPrice = Price(latest.close - BigDecimal("5")),
                    expectedR = BigDecimal("100"),
                ),
            reasonCodes = listOf("TEST"),
        )
    }
}

private class NoTradeStrategy : TradingStrategy {
    override val name: String = "no-trade-test"
    override val warmupCandles: Int = 2

    override fun evaluate(candles: List<Candle>): StrategyDecision = StrategyDecision.noTrade("TEST_NO_EDGE")
}

private fun risingCandles(): List<Candle> =
    listOf(100, 100, 100, 101, 110, 112).mapIndexed { index, close ->
        Candle(
            symbol = Symbol("BTCUSDT"),
            timeframe = Timeframe.M15,
            openedAt = Instant.parse("2026-06-30T00:00:00Z").plusSeconds(index * 900L),
            open = BigDecimal(close),
            high = BigDecimal(close + 10),
            low = BigDecimal(close - 1),
            close = BigDecimal(close),
            volume = BigDecimal("10"),
        )
    }

private fun flatCandles(): List<Candle> =
    (0 until 10).map { index ->
        Candle(
            symbol = Symbol("BTCUSDT"),
            timeframe = Timeframe.M15,
            openedAt = Instant.parse("2026-06-30T00:00:00Z").plusSeconds(index * 900L),
            open = BigDecimal("100"),
            high = BigDecimal("101"),
            low = BigDecimal("99"),
            close = BigDecimal("100"),
            volume = BigDecimal("10"),
        )
    }
