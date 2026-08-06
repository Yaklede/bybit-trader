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
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
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

        "trailing stop updates after the current candle without a fixed target" {
            val runner = BacktestRunner(TimedBuyStrategy(Instant.parse("2026-06-30T00:10:00Z"), 90.0))
            val result =
                runner.run(
                    candles = trailingCandles(),
                    config =
                        BacktestConfig(
                            initialEquity = 10_000.0,
                            riskFraction = 0.01,
                            feeRate = 0.0,
                            slippageRate = 0.0,
                            exitSlippageRate = 0.0,
                            partialTakeProfitFraction = 0.0,
                            atrTrailingPeriod = 2,
                            atrTrailingMultiplier = 1.0,
                            fixedTargetEnabled = false,
                            maxHoldCandles = 2,
                        ),
                )

            result.trades.size shouldBe 1
            result.trades.single().signalAt shouldBe Instant.parse("2026-06-30T00:10:00Z")
            result.trades.single().entryAt shouldBe Instant.parse("2026-06-30T00:15:00Z")
            result.trades.single().exitAt shouldBe Instant.parse("2026-06-30T00:20:00Z")
            result.trades.single().targetPrice shouldBe null
            result.trades.single().exitReason shouldBe BacktestExitReason.TRAILING_STOP
            result.trades.single().exitTriggerPrice shouldBe (118.0 plusOrMinus 0.000001)
        }

        "runner enforces the UTC daily trade limit" {
            val runner = BacktestRunner(AlwaysBuyStrategy())
            val result =
                runner.run(
                    candles = risingCandles(),
                    config =
                        BacktestConfig(
                            feeRate = 0.0,
                            slippageRate = 0.0,
                            partialTakeProfitFraction = 0.0,
                            maxHoldCandles = 1,
                            maxTradesPerUtcDay = 1,
                        ),
                )

            result.trades.size shouldBe 1
            (result.noTradeReasonCounts["MAX_TRADES_PER_UTC_DAY"] ?: 0) shouldBeGreaterThan 0
        }

        "runner rejects entry risk below the configured floor" {
            val runner = BacktestRunner(TightStopStrategy())
            val result =
                runner.run(
                    candles = flatCandles(),
                    config =
                        BacktestConfig(
                            feeRate = 0.0,
                            slippageRate = 0.0,
                            partialTakeProfitFraction = 0.0,
                            minimumEntryRiskFraction = 0.002,
                        ),
                )

            result.trades.size shouldBe 0
            result.noTradeReasonCounts["ENTRY_RISK_BELOW_MINIMUM"] shouldBe result.evaluatedWindows
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

private class TimedBuyStrategy(
    private val signalAt: Instant,
    private val stopPrice: Double,
) : TradingStrategy {
    override val name: String = "timed-buy-test"
    override val warmupCandles: Int = 2

    override fun evaluate(candles: List<Candle>): StrategyDecision {
        val latest = candles.last()
        if (latest.openedAt != signalAt) return StrategyDecision.noTrade("NOT_SIGNAL_TIME")
        return StrategyDecision(
            intent =
                SignalIntent(
                    symbol = latest.symbol,
                    side = Side.BUY,
                    strategy = name,
                    score = SignalScore(80, listOf("TEST")),
                    invalidationPrice = Price(BigDecimal.valueOf(stopPrice)),
                    expectedR = BigDecimal.ONE,
                ),
            reasonCodes = listOf("TEST"),
        )
    }
}

private class TightStopStrategy : TradingStrategy {
    override val name: String = "tight-stop-test"
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
                    invalidationPrice = Price(latest.close - BigDecimal("0.1")),
                    expectedR = BigDecimal.ONE,
                ),
            reasonCodes = listOf("TEST"),
        )
    }
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

private fun trailingCandles(): List<Candle> =
    listOf(
        candleAt(0, 100.0, 101.0, 99.0, 100.0),
        candleAt(1, 100.0, 101.0, 99.0, 100.0),
        candleAt(2, 100.0, 101.0, 99.0, 100.0),
        candleAt(3, 100.0, 120.0, 95.0, 119.0),
        candleAt(4, 119.0, 119.0, 117.0, 118.0),
    )

private fun candleAt(
    index: Int,
    open: Double,
    high: Double,
    low: Double,
    close: Double,
): Candle =
    Candle(
        symbol = Symbol("BTCUSDT"),
        timeframe = Timeframe.M5,
        openedAt = Instant.parse("2026-06-30T00:00:00Z").plusSeconds(index * 300L),
        open = BigDecimal.valueOf(open),
        high = BigDecimal.valueOf(high),
        low = BigDecimal.valueOf(low),
        close = BigDecimal.valueOf(close),
        volume = BigDecimal.TEN,
    )
