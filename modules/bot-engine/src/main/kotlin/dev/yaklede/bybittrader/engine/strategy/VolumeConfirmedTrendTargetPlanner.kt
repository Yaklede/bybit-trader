package dev.yaklede.bybittrader.engine.strategy

import dev.yaklede.bybittrader.domain.Side
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest

enum class VolumeConfirmedTrendTargetAction {
    NO_ACTION,
    NO_TRADE,
    OPEN,
    CLOSE,
}

data class VolumeConfirmedTrendObservedPosition(
    val side: Side,
    val quantity: BigDecimal,
) {
    init {
        require(quantity > BigDecimal.ZERO) { "Observed trend position quantity must be positive." }
    }
}

data class VolumeConfirmedTrendTargetPlan(
    val action: VolumeConfirmedTrendTargetAction,
    val targetSide: Side,
    val orderSide: Side?,
    val orderQuantity: BigDecimal?,
    val reduceOnly: Boolean,
    val limitPrice: BigDecimal?,
    val decisionKey: String,
    val clientOrderId: String?,
    val reasonCode: String,
) {
    init {
        val submitsOrder = action == VolumeConfirmedTrendTargetAction.OPEN || action == VolumeConfirmedTrendTargetAction.CLOSE
        require(submitsOrder == (orderSide != null && orderQuantity != null && limitPrice != null && clientOrderId != null)) {
            "Trend target plan order fields must match whether it submits an order."
        }
        require(orderQuantity == null || orderQuantity > BigDecimal.ZERO) {
            "Trend target plan order quantity must be positive."
        }
        require(limitPrice == null || limitPrice > BigDecimal.ZERO) {
            "Trend target plan limit price must be positive."
        }
        require(action != VolumeConfirmedTrendTargetAction.OPEN || !reduceOnly) {
            "Trend target entry cannot be reduce-only."
        }
        require(action != VolumeConfirmedTrendTargetAction.CLOSE || reduceOnly) {
            "Trend target exit must be reduce-only."
        }
        require(decisionKey.isNotBlank() && reasonCode.isNotBlank()) {
            "Trend target plan identities must not be blank."
        }
    }
}

object VolumeConfirmedTrendTargetPlanner {
    fun plan(
        protocolSha256: String,
        command: VolumeConfirmedTrendCommand,
        accountEquity: BigDecimal,
        referencePrice: BigDecimal,
        priceTick: BigDecimal,
        currentPosition: VolumeConfirmedTrendObservedPosition?,
        contract: VolumeConfirmedTrendExecutionContract = VolumeConfirmedTrendExecutionContract(),
    ): VolumeConfirmedTrendTargetPlan {
        require(protocolSha256.matches(Regex("[0-9a-f]{64}"))) {
            "Trend target planner requires a lowercase protocol SHA-256."
        }
        require(accountEquity > BigDecimal.ZERO) { "Trend target planner account equity must be positive." }
        require(referencePrice > BigDecimal.ZERO) { "Trend target planner reference price must be positive." }
        require(priceTick > BigDecimal.ZERO) { "Trend target planner price tick must be positive." }

        val decisionKey = "$protocolSha256|${command.executionAt}|${command.side.name}"
        if (currentPosition?.side == command.side) {
            return noOrderPlan(
                action = VolumeConfirmedTrendTargetAction.NO_ACTION,
                targetSide = command.side,
                decisionKey = decisionKey,
                reasonCode = "TARGET_SIDE_ALREADY_OPEN",
            )
        }

        if (currentPosition != null) {
            val exitSide = currentPosition.side.opposite()
            return orderPlan(
                action = VolumeConfirmedTrendTargetAction.CLOSE,
                targetSide = command.side,
                orderSide = exitSide,
                orderQuantity = currentPosition.quantity,
                reduceOnly = true,
                limitPrice = boundedLimitPrice(referencePrice, exitSide, priceTick, contract),
                decisionKey = decisionKey,
                clientOrderId = clientOrderId(protocolSha256, command, phase = "x", side = exitSide),
                reasonCode = "OPPOSITE_POSITION_REQUIRES_CONFIRMED_EXIT",
            )
        }

        val entryLimitPrice = boundedLimitPrice(referencePrice, command.side, priceTick, contract)
        val quantity =
            VolumeConfirmedTrendEngine
                .quantity(
                    equity = accountEquity.toDouble(),
                    price = entryLimitPrice.toDouble(),
                    contract = contract,
                ).let(BigDecimal::valueOf)
        if (quantity <= BigDecimal.ZERO) {
            return noOrderPlan(
                action = VolumeConfirmedTrendTargetAction.NO_TRADE,
                targetSide = command.side,
                decisionKey = decisionKey,
                reasonCode = "MINIMUM_QUANTITY_EXCEEDS_EXPOSURE_LIMIT",
            )
        }

        return orderPlan(
            action = VolumeConfirmedTrendTargetAction.OPEN,
            targetSide = command.side,
            orderSide = command.side,
            orderQuantity = quantity,
            reduceOnly = false,
            limitPrice = entryLimitPrice,
            decisionKey = decisionKey,
            clientOrderId = clientOrderId(protocolSha256, command, phase = "e", side = command.side),
            reasonCode = "TARGET_POSITION_ENTRY_READY",
        )
    }

    private fun boundedLimitPrice(
        referencePrice: BigDecimal,
        orderSide: Side,
        priceTick: BigDecimal,
        contract: VolumeConfirmedTrendExecutionContract,
    ): BigDecimal {
        val multiplier =
            if (orderSide == Side.BUY) {
                BigDecimal.ONE + BigDecimal.valueOf(contract.oneWaySlippageRate)
            } else {
                BigDecimal.ONE - BigDecimal.valueOf(contract.oneWaySlippageRate)
            }
        val raw = referencePrice * multiplier
        val rounding = if (orderSide == Side.BUY) RoundingMode.CEILING else RoundingMode.FLOOR
        return raw.divide(priceTick, 0, rounding).multiply(priceTick).stripTrailingZeros()
    }

    private fun clientOrderId(
        protocolSha256: String,
        command: VolumeConfirmedTrendCommand,
        phase: String,
        side: Side,
    ): String {
        val sideCode = if (side == Side.BUY) "b" else "s"
        val digest = sha256("$protocolSha256|${command.executionAt}|${command.side.name}|$phase|${side.name}").take(8)
        return "vct-$phase-$sideCode-${command.executionAt.epochSecond}-$digest"
    }

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun noOrderPlan(
        action: VolumeConfirmedTrendTargetAction,
        targetSide: Side,
        decisionKey: String,
        reasonCode: String,
    ): VolumeConfirmedTrendTargetPlan =
        VolumeConfirmedTrendTargetPlan(
            action = action,
            targetSide = targetSide,
            orderSide = null,
            orderQuantity = null,
            reduceOnly = false,
            limitPrice = null,
            decisionKey = decisionKey,
            clientOrderId = null,
            reasonCode = reasonCode,
        )

    private fun orderPlan(
        action: VolumeConfirmedTrendTargetAction,
        targetSide: Side,
        orderSide: Side,
        orderQuantity: BigDecimal,
        reduceOnly: Boolean,
        limitPrice: BigDecimal,
        decisionKey: String,
        clientOrderId: String,
        reasonCode: String,
    ): VolumeConfirmedTrendTargetPlan =
        VolumeConfirmedTrendTargetPlan(
            action = action,
            targetSide = targetSide,
            orderSide = orderSide,
            orderQuantity = orderQuantity,
            reduceOnly = reduceOnly,
            limitPrice = limitPrice,
            decisionKey = decisionKey,
            clientOrderId = clientOrderId,
            reasonCode = reasonCode,
        )
}

private fun Side.opposite(): Side = if (this == Side.BUY) Side.SELL else Side.BUY
