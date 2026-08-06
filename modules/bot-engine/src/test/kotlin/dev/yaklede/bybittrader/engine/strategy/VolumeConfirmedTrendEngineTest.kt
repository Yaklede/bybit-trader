package dev.yaklede.bybittrader.engine.strategy

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class VolumeConfirmedTrendEngineTest :
    StringSpec({
        "aggregates exactly sixteen contiguous M15 candles into H4" {
            val candles = m15Candles(Instant.parse("2026-01-01T00:00:00Z"), 16)

            val bar = VolumeConfirmedTrendEngine.aggregateM15(candles).single()

            bar.openedAt shouldBe Instant.parse("2026-01-01T00:00:00Z")
            bar.open shouldBeExactly 100.0
            bar.close shouldBeExactly 116.0
            bar.high shouldBeExactly 117.0
            bar.low shouldBeExactly 99.0
            bar.volume shouldBeExactly 32.0
        }

        "fails closed on an incomplete internal H4 bucket" {
            val candles = m15Candles(Instant.parse("2026-01-01T00:00:00Z"), 48).filterIndexed { index, _ -> index != 20 }

            shouldThrow<IllegalStateException> { VolumeConfirmedTrendEngine.aggregateM15(candles) }
                .message shouldBe "Incomplete internal H4 bucket at 2026-01-01T04:00:00Z: 15 bars."
        }

        "executes a volume-confirmed trend transition on the next H4 open" {
            val start = Instant.parse("2026-01-01T00:00:00Z")
            val bars =
                (0 until 8).map { index ->
                    h4Bar(
                        at = start.plusSeconds(index * 14_400L),
                        open = 100.0 + index,
                        close = 100.0 + index,
                        volume = if (index == 5) 20.0 else 10.0,
                    )
                }
            val parameters =
                VolumeConfirmedTrendParameters(
                    emaVotePairs = listOf(VolumeConfirmedTrendEmaPair(1, 2)),
                    minimumMajorityVotes = 1,
                    volumeMedianLookbackBars = 2,
                    warmupDecisionBars = 3,
                )

            val command = VolumeConfirmedTrendEngine.commands(bars, parameters).filterNotNull().first()

            command.executionIndex shouldBe command.decisionIndex + 1
            command.executionAt shouldBe bars[command.executionIndex].openedAt
            command.decisionAt shouldBe command.executionAt
            command.side shouldBe Side.BUY
        }

        "uses minimum BTC quantity only below the rounded exposure ceiling" {
            VolumeConfirmedTrendEngine.quantity(100.0, 64_000.0) shouldBeExactly 0.001
            VolumeConfirmedTrendEngine.quantity(70.0, 64_000.0) shouldBeExactly 0.0
        }

        "charges funding before a same-timestamp reversal" {
            val start = Instant.parse("2026-01-01T00:00:00Z")
            val bars =
                listOf(
                    h4Bar(start, 100.0, 110.0),
                    h4Bar(start.plusSeconds(14_400), 110.0, 100.0),
                    h4Bar(start.plusSeconds(28_800), 100.0, 100.0),
                )
            val commands =
                listOf(
                    command(Side.BUY, bars, 0),
                    command(Side.SELL, bars, 1),
                    null,
                )

            val result =
                VolumeConfirmedTrendSimulator.run(
                    bars = bars,
                    fundingRates = listOf(VolumeConfirmedTrendFundingRate(bars[1].openedAt, 0.01)),
                    commands = commands,
                    startingEquity = 100.0,
                    costMultiplier = 1.0,
                    contract = VolumeConfirmedTrendExecutionContract(oneWayFeeRate = 0.0, oneWaySlippageRate = 0.0),
                )

            (result.totalFundingPnl < 0.0) shouldBe true
            result.trades.size shouldBe 2
            result.liquidationCount shouldBe 0
            (result.maximumEntryExposureFraction <= 0.85) shouldBe true
            (result.maximumAdverseExposureFraction >= result.maximumEntryExposureFraction) shouldBe true
        }

        "can preserve the ending position for runtime replay parity" {
            val start = Instant.parse("2026-01-01T00:00:00Z")
            val bars =
                listOf(
                    h4Bar(start, 100.0, 110.0),
                    h4Bar(start.plusSeconds(14_400), 110.0, 120.0),
                )

            val result =
                VolumeConfirmedTrendSimulator.run(
                    bars = bars,
                    fundingRates = emptyList(),
                    commands = listOf(command(Side.BUY, bars, 0), null),
                    startingEquity = 100.0,
                    costMultiplier = 1.0,
                    contract = VolumeConfirmedTrendExecutionContract(oneWayFeeRate = 0.0, oneWaySlippageRate = 0.0),
                    closeAtEvidenceEnd = false,
                )

            result.trades shouldBe emptyList()
            result.endingOpenPosition?.side shouldBe Side.BUY
            result.endingCash shouldBeExactly 100.0
            result.endingEquity shouldBeExactly 113.0
        }

        "restores the streaming evaluator without changing future transitions" {
            val parameters =
                VolumeConfirmedTrendParameters(
                    emaVotePairs = listOf(VolumeConfirmedTrendEmaPair(1, 3)),
                    minimumMajorityVotes = 1,
                    volumeMedianLookbackBars = 2,
                    warmupDecisionBars = 3,
                )
            val start = Instant.parse("2026-01-01T00:00:00Z")
            val bars =
                listOf(100.0, 101.0, 102.0, 103.0, 90.0, 80.0, 95.0, 110.0).mapIndexed { index, close ->
                    h4Bar(
                        at = start.plusSeconds(index * 14_400L),
                        open = close,
                        close = close,
                        volume = if (index >= 4) 20.0 else 10.0,
                    )
                }
            val uninterrupted = VolumeConfirmedTrendEvaluator(parameters)
            val uninterruptedTransitions = bars.mapNotNull(uninterrupted::evaluate)

            val firstProcess = VolumeConfirmedTrendEvaluator(parameters)
            val beforeRestart = bars.take(5).mapNotNull(firstProcess::evaluate)
            val restored = VolumeConfirmedTrendEvaluator.restore(firstProcess.snapshot(), parameters)
            val afterRestart = bars.drop(5).mapNotNull(restored::evaluate)

            beforeRestart + afterRestart shouldBe uninterruptedTransitions
            restored.snapshot() shouldBe uninterrupted.snapshot()
        }

        "emits a pending transition for the latest closed H4 bar" {
            val parameters =
                VolumeConfirmedTrendParameters(
                    emaVotePairs = listOf(VolumeConfirmedTrendEmaPair(1, 2)),
                    minimumMajorityVotes = 1,
                    volumeMedianLookbackBars = 2,
                    warmupDecisionBars = 3,
                )
            val start = Instant.parse("2026-01-01T00:00:00Z")
            val evaluator = VolumeConfirmedTrendEvaluator(parameters)
            val transitions =
                (0 until 3).mapNotNull { index ->
                    evaluator.evaluate(
                        h4Bar(
                            at = start.plusSeconds(index * 14_400L),
                            open = 100.0 + index,
                            close = 100.0 + index,
                            volume = if (index == 2) 20.0 else 10.0,
                        ),
                    )
                }

            transitions.single().decisionAt shouldBe Instant.parse("2026-01-01T12:00:00Z")
            transitions.single().side shouldBe Side.BUY
        }
    })

private fun m15Candles(
    start: Instant,
    count: Int,
): List<Candle> =
    (0 until count).map { index ->
        Candle(
            symbol = Symbol("BTCUSDT"),
            timeframe = Timeframe.M15,
            openedAt = start.plusSeconds(index * 900L),
            open = BigDecimal(100 + index),
            high = BigDecimal(102 + index),
            low = BigDecimal(99 + index),
            close = BigDecimal(101 + index),
            volume = BigDecimal("2"),
        )
    }

private fun h4Bar(
    at: Instant,
    open: Double,
    close: Double,
    volume: Double = 10.0,
): VolumeConfirmedTrendBar =
    VolumeConfirmedTrendBar(
        openedAt = at,
        open = open,
        high = maxOf(open, close) + 1.0,
        low = minOf(open, close) - 1.0,
        close = close,
        volume = volume,
    )

private fun command(
    side: Side,
    bars: List<VolumeConfirmedTrendBar>,
    index: Int,
): VolumeConfirmedTrendCommand =
    VolumeConfirmedTrendCommand(
        side = side,
        decisionAt = bars[index].openedAt,
        executionAt = bars[index].openedAt,
        decisionIndex = index,
        executionIndex = index,
        netVotes = if (side == Side.BUY) 1 else -1,
        decisionVolume = 10.0,
        priorVolumeMedian = 10.0,
    )
