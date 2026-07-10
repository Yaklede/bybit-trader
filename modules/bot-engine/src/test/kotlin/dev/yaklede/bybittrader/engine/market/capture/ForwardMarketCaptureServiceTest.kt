package dev.yaklede.bybittrader.engine.market.capture

import dev.yaklede.bybittrader.domain.Symbol
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class ForwardMarketCaptureServiceTest :
    StringSpec({
        "aggregates order book and liquidation events into closed minute bars" {
            val store = InMemoryForwardMarketCaptureStore()
            val service = ForwardMarketCaptureService(store)
            val symbol = Symbol("BTCUSDT")

            service.record(orderBookSnapshot(symbol, "2026-07-10T00:00:05Z", "100", "60", "1.2"))
            service.record(orderBookSnapshot(symbol, "2026-07-10T00:00:45Z", "80", "120", "2.8"))
            service.record(liquidationEvent(symbol, "2026-07-10T00:00:25Z", LiquidatedPositionSide.LONG, "150"))
            service.record(liquidationEvent(symbol, "2026-07-10T00:00:50Z", LiquidatedPositionSide.SHORT, "80"))

            val result = service.flushClosedBars(Instant.parse("2026-07-10T00:01:00Z"))

            result.orderBookBars shouldBe 1
            result.liquidationBars shouldBe 1
            store.orderBookBars.single().meanBidNotional shouldBe BigDecimal("90")
            store.orderBookBars.single().meanAskNotional shouldBe BigDecimal("90")
            store.orderBookBars.single().meanImbalance shouldBe BigDecimal("0.025")
            store.orderBookBars.single().meanSpreadBps shouldBe BigDecimal("2.0")
            store.orderBookBars.single().maxSpreadBps shouldBe BigDecimal("2.8")
            store.liquidationBars.single().longLiquidationNotional shouldBe BigDecimal("150")
            store.liquidationBars.single().shortLiquidationNotional shouldBe BigDecimal("80")
            store.liquidationBars.single().longLiquidationCount shouldBe 1
            store.liquidationBars.single().shortLiquidationCount shouldBe 1
        }

        "keeps the current minute open until it closes" {
            val store = InMemoryForwardMarketCaptureStore()
            val service = ForwardMarketCaptureService(store)
            val symbol = Symbol("BTCUSDT")
            service.record(orderBookSnapshot(symbol, "2026-07-10T00:01:05Z", "100", "100", "1"))

            service.flushClosedBars(Instant.parse("2026-07-10T00:01:50Z")) shouldBe ForwardMarketCaptureFlushResult.EMPTY
            store.orderBookBars shouldBe emptyList()

            service.flushClosedBars(Instant.parse("2026-07-10T00:02:00Z")).orderBookBars shouldBe 1
        }
    })

private class InMemoryForwardMarketCaptureStore : ForwardMarketCaptureStore {
    val orderBookBars = mutableListOf<OrderBookImbalanceBar>()
    val liquidationBars = mutableListOf<LiquidationFlowBar>()

    override suspend fun upsertOrderBookImbalanceBars(bars: List<OrderBookImbalanceBar>) {
        orderBookBars += bars
    }

    override suspend fun orderBookImbalanceBarsBetween(
        symbol: Symbol,
        startAt: Instant,
        endAt: Instant,
        limit: Int,
    ): List<OrderBookImbalanceBar> = emptyList()

    override suspend fun upsertLiquidationFlowBars(bars: List<LiquidationFlowBar>) {
        liquidationBars += bars
    }

    override suspend fun liquidationFlowBarsBetween(
        symbol: Symbol,
        startAt: Instant,
        endAt: Instant,
        limit: Int,
    ): List<LiquidationFlowBar> = emptyList()
}

private fun orderBookSnapshot(
    symbol: Symbol,
    timestamp: String,
    bidNotional: String,
    askNotional: String,
    spreadBps: String,
): OrderBookDepthSnapshot =
    OrderBookDepthSnapshot(
        symbol = symbol,
        capturedAt = Instant.parse(timestamp),
        bidNotional = BigDecimal(bidNotional),
        askNotional = BigDecimal(askNotional),
        spreadBps = BigDecimal(spreadBps),
    )

private fun liquidationEvent(
    symbol: Symbol,
    timestamp: String,
    side: LiquidatedPositionSide,
    notional: String,
): LiquidationEvent =
    LiquidationEvent(
        symbol = symbol,
        capturedAt = Instant.parse(timestamp),
        liquidatedSide = side,
        notional = BigDecimal(notional),
    )
