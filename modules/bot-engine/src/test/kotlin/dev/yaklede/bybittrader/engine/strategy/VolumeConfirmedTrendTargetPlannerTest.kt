package dev.yaklede.bybittrader.engine.strategy

import dev.yaklede.bybittrader.domain.Side
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class VolumeConfirmedTrendTargetPlannerTest :
    StringSpec({
        "flat account opens the frozen target exposure with a bounded IOC price" {
            val plan = plan(equity = "660", referencePrice = "60000", side = Side.BUY)

            plan.action shouldBe VolumeConfirmedTrendTargetAction.OPEN
            plan.orderSide shouldBe Side.BUY
            plan.orderQuantity shouldBe BigDecimal("0.007")
            plan.limitPrice shouldBe BigDecimal("60012")
            plan.reduceOnly shouldBe false
            (requireNotNull(plan.clientOrderId).length <= 36) shouldBe true
        }

        "one hundred USDT account can use the minimum BTC quantity under its rounded ceiling" {
            val plan = plan(equity = "100", referencePrice = "60000", side = Side.SELL)

            plan.action shouldBe VolumeConfirmedTrendTargetAction.OPEN
            plan.orderQuantity shouldBe BigDecimal("0.001")
            plan.limitPrice shouldBe BigDecimal("59988")
        }

        "minimum quantity is never rounded up beyond the exposure ceiling" {
            val plan = plan(equity = "50", referencePrice = "100000", side = Side.BUY)

            plan.action shouldBe VolumeConfirmedTrendTargetAction.NO_TRADE
            plan.reasonCode shouldBe "MINIMUM_QUANTITY_EXCEEDS_EXPOSURE_LIMIT"
            plan.orderQuantity shouldBe null
            plan.clientOrderId shouldBe null
        }

        "same-side position remains unchanged until an opposite confirmed transition" {
            val plan =
                plan(
                    equity = "1000",
                    referencePrice = "60000",
                    side = Side.BUY,
                    position = VolumeConfirmedTrendObservedPosition(Side.BUY, BigDecimal("0.001")),
                )

            plan.action shouldBe VolumeConfirmedTrendTargetAction.NO_ACTION
            plan.reasonCode shouldBe "TARGET_SIDE_ALREADY_OPEN"
            plan.orderSide shouldBe null
        }

        "opposite position produces only a reduce-only close before re-entry" {
            val plan =
                plan(
                    equity = "660",
                    referencePrice = "60000",
                    side = Side.SELL,
                    position = VolumeConfirmedTrendObservedPosition(Side.BUY, BigDecimal("0.007")),
                )

            plan.action shouldBe VolumeConfirmedTrendTargetAction.CLOSE
            plan.targetSide shouldBe Side.SELL
            plan.orderSide shouldBe Side.SELL
            plan.orderQuantity shouldBe BigDecimal("0.007")
            plan.limitPrice shouldBe BigDecimal("59988")
            plan.reduceOnly shouldBe true
        }

        "client order identity is deterministic for a replayed H4 command" {
            val first = plan(equity = "660", referencePrice = "60000", side = Side.BUY)
            val replay = plan(equity = "660", referencePrice = "61000", side = Side.BUY)

            first.decisionKey shouldBe replay.decisionKey
            first.clientOrderId shouldBe replay.clientOrderId
            replay.limitPrice shouldBe BigDecimal("61012.2")
        }

        "approval revocation creates a deterministic reduce-only safety exit" {
            val position = VolumeConfirmedTrendObservedPosition(Side.BUY, BigDecimal("0.007"))
            val first =
                VolumeConfirmedTrendTargetPlanner.safetyExit(
                    protocolSha256 = "a".repeat(64),
                    observedAt = Instant.parse("2026-08-07T00:00:00Z"),
                    referencePrice = BigDecimal("60000"),
                    priceTick = BigDecimal("0.1"),
                    currentPosition = position,
                )
            val replay =
                VolumeConfirmedTrendTargetPlanner.safetyExit(
                    protocolSha256 = "a".repeat(64),
                    observedAt = Instant.parse("2026-08-07T00:00:00Z"),
                    referencePrice = BigDecimal("61000"),
                    priceTick = BigDecimal("0.1"),
                    currentPosition = position,
                )

            first.action shouldBe VolumeConfirmedTrendTargetAction.CLOSE
            first.orderSide shouldBe Side.SELL
            first.orderQuantity shouldBe BigDecimal("0.007")
            first.reduceOnly shouldBe true
            first.reasonCode shouldBe TREND_APPROVAL_REVOKED_EXIT_REASON_CODE
            first.clientOrderId shouldBe replay.clientOrderId
            replay.limitPrice shouldBe BigDecimal("60987.8")
        }
    })

private fun plan(
    equity: String,
    referencePrice: String,
    side: Side,
    position: VolumeConfirmedTrendObservedPosition? = null,
): VolumeConfirmedTrendTargetPlan =
    VolumeConfirmedTrendTargetPlanner.plan(
        protocolSha256 = "a".repeat(64),
        command =
            VolumeConfirmedTrendCommand(
                side = side,
                decisionAt = Instant.parse("2026-08-06T20:00:00Z"),
                executionAt = Instant.parse("2026-08-07T00:00:00Z"),
                decisionIndex = 600,
                executionIndex = 601,
                netVotes = if (side == Side.BUY) 3 else -3,
                decisionVolume = 100.0,
                priorVolumeMedian = 80.0,
            ),
        accountEquity = BigDecimal(equity),
        referencePrice = BigDecimal(referencePrice),
        priceTick = BigDecimal("0.1"),
        currentPosition = position,
    )
