package dev.yaklede.bybittrader.engine.position

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Price
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.SignalIntent
import dev.yaklede.bybittrader.domain.SignalScore
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class CausalEntryPlannerTest :
    StringSpec({
        "entry anchored distance can widen a structural stop and sizes from current equity" {
            val planner = planner()

            val result =
                planner.plan(
                    request(
                        signal = signal(side = Side.BUY, structuralStop = 95.0, anchoredDistance = 10.0),
                        equity = 10_000.0,
                    ),
                )

            result.rejectionReason shouldBe null
            result.plan?.entryPrice shouldBe (100.0 plusOrMinus 0.000001)
            result.plan?.initialStopPrice shouldBe (90.0 plusOrMinus 0.000001)
            result.plan?.riskAmount shouldBe (100.0 plusOrMinus 0.000001)
            result.plan?.quantity shouldBe (10.0 plusOrMinus 0.000001)
        }

        "daily entry limit rejects before creating exposure" {
            val planner = planner(maxTradesPerUtcDay = 1)

            val result = planner.plan(request(entriesOnEntryUtcDay = 1))

            result.plan shouldBe null
            result.rejectionReason shouldBe "MAX_TRADES_PER_UTC_DAY"
        }

        "risk distance below the configured floor is rejected" {
            val planner = planner(minimumEntryRiskFraction = 0.002)

            val result =
                planner.plan(
                    request(signal = signal(side = Side.BUY, structuralStop = 99.9, anchoredDistance = null)),
                )

            result.plan shouldBe null
            result.rejectionReason shouldBe "ENTRY_RISK_BELOW_MINIMUM"
        }
    })

private fun planner(
    maxTradesPerUtcDay: Int? = 1,
    minimumEntryRiskFraction: Double? = null,
): CausalEntryPlanner =
    CausalEntryPlanner(
        CausalEntryPolicyConfig(
            riskFraction = 0.01,
            entrySlippageRate = 0.0,
            maxTradesPerUtcDay = maxTradesPerUtcDay,
            minimumEntryRiskFraction = minimumEntryRiskFraction,
            maximumEntryRiskFraction = null,
        ),
    )

private fun request(
    signal: SignalIntent = signal(Side.BUY, structuralStop = 95.0, anchoredDistance = null),
    equity: Double = 10_000.0,
    entriesOnEntryUtcDay: Int = 0,
): CausalEntryPlanRequest =
    CausalEntryPlanRequest(
        signal = signal,
        signalAt = Instant.parse("2026-06-30T00:00:00Z"),
        entryCandle =
            Candle(
                symbol = Symbol("BTCUSDT"),
                timeframe = Timeframe.M15,
                openedAt = Instant.parse("2026-06-30T00:15:00Z"),
                open = BigDecimal("100"),
                high = BigDecimal("105"),
                low = BigDecimal("95"),
                close = BigDecimal("102"),
                volume = BigDecimal.TEN,
            ),
        equity = equity,
        entriesOnEntryUtcDay = entriesOnEntryUtcDay,
    )

private fun signal(
    side: Side,
    structuralStop: Double,
    anchoredDistance: Double?,
): SignalIntent =
    SignalIntent(
        symbol = Symbol("BTCUSDT"),
        side = side,
        strategy = "entry-planner-test",
        score = SignalScore(80, listOf("TEST")),
        invalidationPrice = Price(BigDecimal.valueOf(structuralStop)),
        expectedR = BigDecimal("2"),
        entryAnchoredStopDistance = anchoredDistance?.let(BigDecimal::valueOf),
    )
