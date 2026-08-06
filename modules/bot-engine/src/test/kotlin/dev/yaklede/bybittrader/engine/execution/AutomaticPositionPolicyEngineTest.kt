package dev.yaklede.bybittrader.engine.execution

import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.position.CausalPositionExitReason
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class AutomaticPositionPolicyEngineTest :
    StringSpec({
        "actual fill opens state and one closed candle advances the shared trailing policy" {
            val engine = testEngine()
            val opened = engine.open(testLifecycle(), testPosition(), ENTRY_AT, testProtection(), RECONCILED_AT)

            opened.policyState.entryAt shouldBe ENTRY_AT
            opened.policyState.entryPrice shouldBe 100.0
            opened.policyState.fullTargetPrice shouldBe null

            val decision = engine.advance(opened, candlesThrough("2024-06-30T00:10:00Z"), Instant.parse("2024-06-30T00:15:00Z"))
            val update = decision as AutomaticPositionPolicyDecision.Update
            update.state.lastProcessedCandleAt shouldBe Instant.parse("2024-06-30T00:10:00Z")
            update.stopLoss shouldBe BigDecimal("108.0")

            val exitDecision =
                engine.advance(
                    update.state,
                    candlesThrough("2024-06-30T00:15:00Z", lastLow = "107"),
                    Instant.parse("2024-06-30T00:20:00Z"),
                )
            val exit = exitDecision as AutomaticPositionPolicyDecision.Exit
            exit.reason shouldBe CausalPositionExitReason.TRAILING_STOP
        }

        "missed closed candles fail instead of replaying historical protection updates" {
            val engine = testEngine()
            val opened = engine.open(testLifecycle(), testPosition(), ENTRY_AT, testProtection(), RECONCILED_AT)

            engine.advance(
                opened,
                candlesThrough("2024-06-30T00:15:00Z"),
                Instant.parse("2024-06-30T00:20:00Z"),
            ) shouldBe AutomaticPositionPolicyDecision.Failure("POSITION_POLICY_CANDLE_GAP")
        }

        "live policy rejects partial take profit until execution confirmation is implemented" {
            shouldThrow<IllegalArgumentException> {
                AutomaticPositionPolicyEngine(
                    policy = testPolicy().copy(partialTakeProfitFraction = 0.5),
                    feeRate = BigDecimal("0.0006"),
                    priceTick = BigDecimal("0.1"),
                )
            }
        }
    })

private val SYMBOL = Symbol("BTCUSDT")
private val ENTRY_AT = Instant.parse("2024-06-30T00:05:30Z")
private val RECONCILED_AT = Instant.parse("2024-06-30T00:06:00Z")

private fun testEngine(): AutomaticPositionPolicyEngine =
    AutomaticPositionPolicyEngine(
        policy = testPolicy(),
        feeRate = BigDecimal("0.0006"),
        priceTick = BigDecimal("0.1"),
    )

private fun testPolicy(): AutomaticPositionPolicy =
    AutomaticPositionPolicy(
        timeframe = Timeframe.M5,
        maxHoldCandles = 36,
        maxTradesPerUtcDay = 1,
        atrTrailingPeriod = 2,
        atrTrailingMultiplier = 1.0,
        fixedTargetEnabled = false,
    )

private fun testLifecycle(): ExecutionLifecycleEvent =
    ExecutionLifecycleEvent(
        mode = ExecutionRuntimeMode.TESTNET,
        lifecycleId = "auto-BTCUSDT-1",
        symbol = SYMBOL,
        state = ExecutionLifecycleState.OPEN_PROTECTED,
        side = Side.BUY,
        requestedQuantity = BigDecimal.ONE,
        filledQuantity = BigDecimal.ONE,
        fillVwap = BigDecimal("100"),
        takeProfit = null,
        stopLoss = BigDecimal("90"),
        exchangeOrderId = "exchange-1",
        clientOrderId = "auto-BTCUSDT-1",
        reasonCode = "ACTUAL_FILL_PROTECTION_VERIFIED",
        occurredAt = RECONCILED_AT,
        protectionRequired = true,
        plannedEntryPrice = BigDecimal("100"),
        structuralStopPrice = BigDecimal("90"),
        expectedR = BigDecimal("2"),
        protectionDeadlineAt = Instant.parse("2024-06-30T00:08:00Z"),
        fixedTargetEnabled = false,
        intendedRisk = BigDecimal("10"),
    )

private fun testPosition(): ExchangePosition =
    ExchangePosition(
        symbol = SYMBOL,
        side = Side.BUY,
        size = BigDecimal.ONE,
        openedAt = ENTRY_AT,
        entryPrice = BigDecimal("100"),
        markPrice = BigDecimal("105"),
        unrealizedPnl = BigDecimal("5"),
        updatedAt = RECONCILED_AT,
        takeProfit = null,
        stopLoss = BigDecimal("90"),
    )

private fun testProtection(): ExecutionProtectionPlan =
    ExecutionProtectionPlan(
        takeProfit = null,
        stopLoss = BigDecimal("90"),
        riskPerUnit = BigDecimal("10"),
    )

private fun candlesThrough(
    lastOpenedAt: String,
    lastLow: String = "99",
): List<Candle> {
    val last = Instant.parse(lastOpenedAt)
    return generateSequence(Instant.parse("2024-06-30T00:00:00Z")) { it.plusSeconds(300) }
        .takeWhile { !it.isAfter(last) }
        .map { openedAt ->
            val isLast = openedAt == last
            Candle(
                symbol = SYMBOL,
                timeframe = Timeframe.M5,
                openedAt = openedAt,
                open = BigDecimal("100"),
                high = if (isLast) BigDecimal("110") else BigDecimal("102"),
                low = if (isLast) BigDecimal(lastLow) else BigDecimal("100"),
                close = if (isLast) BigDecimal("109") else BigDecimal("101"),
                volume = BigDecimal("100"),
            )
        }.toList()
}
