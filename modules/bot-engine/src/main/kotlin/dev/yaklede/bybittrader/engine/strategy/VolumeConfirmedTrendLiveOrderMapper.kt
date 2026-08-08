package dev.yaklede.bybittrader.engine.strategy

import dev.yaklede.bybittrader.domain.OrderType
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.execution.ExchangeOrderRequest
import dev.yaklede.bybittrader.engine.execution.ExchangeOrderResult
import dev.yaklede.bybittrader.engine.execution.ExchangePosition
import dev.yaklede.bybittrader.engine.execution.ExchangeTimeInForce
import java.security.MessageDigest
import java.time.Instant

internal fun ExchangePosition.toObservedPosition(): VolumeConfirmedTrendObservedPosition =
    VolumeConfirmedTrendObservedPosition(side = side, quantity = size)

internal fun VolumeConfirmedTrendTargetPlan.toExchangeOrderRequest(symbol: Symbol): ExchangeOrderRequest =
    ExchangeOrderRequest(
        symbol = symbol,
        side = requireNotNull(orderSide),
        orderType = OrderType.LIMIT,
        quantity = requireNotNull(orderQuantity),
        clientOrderId = requireNotNull(clientOrderId),
        takeProfit = null,
        stopLoss = null,
        reduceOnly = reduceOnly,
        price = requireNotNull(limitPrice),
        timeInForce = ExchangeTimeInForce.IOC,
    )

internal fun VolumeConfirmedTrendTargetPlan.toIntentEvent(
    protocolId: String,
    protocolSha256: String,
    symbol: Symbol,
    type: VolumeConfirmedTrendLiveEventType,
    now: Instant,
    reasonCode: String = this.reasonCode,
): VolumeConfirmedTrendLiveEvent =
    VolumeConfirmedTrendLiveEvent(
        eventId = trendLiveEventId(decisionKey, type, clientOrderId, null),
        protocolId = protocolId,
        protocolSha256 = protocolSha256,
        symbol = symbol,
        decisionKey = decisionKey,
        type = type,
        targetSide = targetSide,
        orderSide = orderSide,
        orderQuantity = orderQuantity,
        referencePrice = null,
        limitPrice = limitPrice,
        clientOrderId = clientOrderId,
        exchangeOrderId = null,
        executionId = null,
        reasonCode = reasonCode,
        occurredAt = now,
    )

internal fun VolumeConfirmedTrendTargetPlan.toSubmittedEvent(
    protocolId: String,
    protocolSha256: String,
    symbol: Symbol,
    type: VolumeConfirmedTrendLiveEventType,
    result: ExchangeOrderResult,
    now: Instant,
): VolumeConfirmedTrendLiveEvent =
    toIntentEvent(protocolId, protocolSha256, symbol, type, now).copy(
        eventId = trendLiveEventId(decisionKey, type, clientOrderId, result.exchangeOrderId),
        exchangeOrderId = result.exchangeOrderId,
        reasonCode = "TREND_ORDER_SUBMITTED",
    )

internal fun lifecycleEvent(
    state: VolumeConfirmedTrendLiveState,
    type: VolumeConfirmedTrendLiveEventType,
    reasonCode: String,
    now: Instant,
): VolumeConfirmedTrendLiveEvent =
    VolumeConfirmedTrendLiveEvent(
        eventId =
            trendLiveEventId(
                decisionKey = state.activeDecisionKey ?: state.protocolSha256,
                type = type,
                clientOrderId = state.clientOrderId,
                exchangeOrderId = state.exchangeOrderId,
                discriminator =
                    listOf(
                        reasonCode,
                        state.observedPositionSide?.name.orEmpty(),
                        state.observedPositionQuantity?.toPlainString().orEmpty(),
                        state.lastExecutionId.orEmpty(),
                    ).joinToString("|"),
            ),
        protocolId = state.protocolId,
        protocolSha256 = state.protocolSha256,
        symbol = state.symbol,
        decisionKey = state.activeDecisionKey,
        type = type,
        targetSide = state.pendingTargetSide,
        orderSide = null,
        orderQuantity = state.observedPositionQuantity,
        referencePrice = null,
        limitPrice = null,
        clientOrderId = state.clientOrderId,
        exchangeOrderId = state.exchangeOrderId,
        executionId = state.lastExecutionId,
        reasonCode = reasonCode,
        occurredAt = now,
    )

private fun trendLiveEventId(
    decisionKey: String,
    type: VolumeConfirmedTrendLiveEventType,
    clientOrderId: String?,
    exchangeOrderId: String?,
    discriminator: String? = null,
): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(
            buildString {
                append("$decisionKey|${type.name}|${clientOrderId.orEmpty()}|${exchangeOrderId.orEmpty()}")
                discriminator?.let { append("|$it") }
            }.toByteArray(),
        ).joinToString("") { byte -> "%02x".format(byte) }
