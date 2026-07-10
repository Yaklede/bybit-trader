package dev.yaklede.bybittrader.engine.market.capture

import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.market.flow.TakerFlowBar
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ForwardMarketCaptureServiceTest :
    StringSpec({
        "aggregates order book, liquidation, and taker trade events into closed minute bars" {
            val store = InMemoryForwardMarketCaptureStore()
            val service = ForwardMarketCaptureService(store)
            val symbol = Symbol("BTCUSDT")

            service.record(orderBookSnapshot(symbol, "2026-07-10T00:00:05Z", "100", "60", "1.2"))
            service.record(orderBookSnapshot(symbol, "2026-07-10T00:00:45Z", "80", "120", "2.8"))
            service.record(liquidationEvent(symbol, "2026-07-10T00:00:25Z", LiquidatedPositionSide.LONG, "150"))
            service.record(liquidationEvent(symbol, "2026-07-10T00:00:50Z", LiquidatedPositionSide.SHORT, "80"))
            service.record(takerTradeEvent(symbol, "2026-07-10T00:00:10Z", Side.BUY, "2", "100"))
            service.record(takerTradeEvent(symbol, "2026-07-10T00:00:20Z", Side.BUY, "3", "101"))
            service.record(takerTradeEvent(symbol, "2026-07-10T00:00:30Z", Side.SELL, "4", "99"))

            val result = service.flushClosedBars(Instant.parse("2026-07-10T00:01:00Z"))

            result.orderBookBars shouldBe 1
            result.liquidationBars shouldBe 1
            result.takerFlowBars shouldBe 1
            store.orderBookBars.single().meanBidNotional shouldBe BigDecimal("90")
            store.orderBookBars.single().meanAskNotional shouldBe BigDecimal("90")
            store.orderBookBars.single().meanImbalance shouldBe BigDecimal("0.025")
            store.orderBookBars.single().meanSpreadBps shouldBe BigDecimal("2.0")
            store.orderBookBars.single().maxSpreadBps shouldBe BigDecimal("2.8")
            store.liquidationBars.single().longLiquidationNotional shouldBe BigDecimal("150")
            store.liquidationBars.single().shortLiquidationNotional shouldBe BigDecimal("80")
            store.liquidationBars.single().longLiquidationCount shouldBe 1
            store.liquidationBars.single().shortLiquidationCount shouldBe 1
            store.takerFlowBars.single().takerBuyBase shouldBe BigDecimal("5")
            store.takerFlowBars.single().takerBuyNotional shouldBe BigDecimal("503")
            store.takerFlowBars.single().takerSellBase shouldBe BigDecimal("4")
            store.takerFlowBars.single().takerSellNotional shouldBe BigDecimal("396")
            store.takerFlowBars.single().buyTradeCount shouldBe 2
            store.takerFlowBars.single().sellTradeCount shouldBe 1
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

        "reports a fresh order book bar separately from optional liquidation activity" {
            val store = InMemoryForwardMarketCaptureStore()
            val symbol = Symbol("BTCUSDT")
            store.orderBookBars +=
                OrderBookImbalanceBar(
                    symbol = symbol,
                    openedAt = Instant.parse("2026-07-10T00:03:00Z"),
                    sampleCount = 2,
                    meanBidNotional = BigDecimal("100"),
                    meanAskNotional = BigDecimal("80"),
                    meanImbalance = BigDecimal("0.111"),
                    meanSpreadBps = BigDecimal("1"),
                    maxSpreadBps = BigDecimal("1"),
                )
            store.takerFlowBars +=
                TakerFlowBar(
                    symbol = symbol,
                    openedAt = Instant.parse("2026-07-10T00:03:00Z"),
                    takerBuyBase = BigDecimal("2"),
                    takerBuyNotional = BigDecimal("200"),
                    takerSellBase = BigDecimal("1"),
                    takerSellNotional = BigDecimal("100"),
                    buyTradeCount = 2,
                    sellTradeCount = 1,
                )
            val status =
                ForwardMarketCaptureStatusService(
                    store = store,
                    clock = Clock.fixed(Instant.parse("2026-07-10T00:05:00Z"), ZoneOffset.UTC),
                ).status(symbol = symbol, enabled = true)

            status.enabled shouldBe true
            status.orderBookFresh shouldBe true
            status.latestOrderBookBarAt shouldBe Instant.parse("2026-07-10T00:03:00Z")
            status.latestLiquidationBarAt shouldBe null
            status.latestTakerFlowBarAt shouldBe Instant.parse("2026-07-10T00:03:00Z")
            status.takerFlowFresh shouldBe true
        }

        "reports disabled capture without exposing old bars" {
            val status =
                ForwardMarketCaptureStatusService(InMemoryForwardMarketCaptureStore()).status(
                    symbol = Symbol("BTCUSDT"),
                    enabled = false,
                )

            status shouldBe ForwardMarketCaptureStatus.DISABLED
        }
    })

private class InMemoryForwardMarketCaptureStore : ForwardMarketCaptureStore {
    val orderBookBars = mutableListOf<OrderBookImbalanceBar>()
    val liquidationBars = mutableListOf<LiquidationFlowBar>()
    val takerFlowBars = mutableListOf<TakerFlowBar>()

    override suspend fun upsertOrderBookImbalanceBars(bars: List<OrderBookImbalanceBar>) {
        orderBookBars += bars
    }

    override suspend fun orderBookImbalanceBarsBetween(
        symbol: Symbol,
        startAt: Instant,
        endAt: Instant,
        limit: Int,
    ): List<OrderBookImbalanceBar> =
        orderBookBars
            .filter { it.symbol == symbol && !it.openedAt.isBefore(startAt) && !it.openedAt.isAfter(endAt) }
            .sortedBy(OrderBookImbalanceBar::openedAt)
            .take(limit)

    override suspend fun upsertLiquidationFlowBars(bars: List<LiquidationFlowBar>) {
        liquidationBars += bars
    }

    override suspend fun liquidationFlowBarsBetween(
        symbol: Symbol,
        startAt: Instant,
        endAt: Instant,
        limit: Int,
    ): List<LiquidationFlowBar> =
        liquidationBars
            .filter { it.symbol == symbol && !it.openedAt.isBefore(startAt) && !it.openedAt.isAfter(endAt) }
            .sortedBy(LiquidationFlowBar::openedAt)
            .take(limit)

    override suspend fun upsertTakerFlowBars(bars: List<TakerFlowBar>) {
        takerFlowBars += bars
    }

    override suspend fun takerFlowBarsBetween(
        symbol: Symbol,
        startAt: Instant,
        endAt: Instant,
        limit: Int,
    ): List<TakerFlowBar> =
        takerFlowBars
            .filter { it.symbol == symbol && !it.openedAt.isBefore(startAt) && !it.openedAt.isAfter(endAt) }
            .sortedBy(TakerFlowBar::openedAt)
            .take(limit)
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

private fun takerTradeEvent(
    symbol: Symbol,
    timestamp: String,
    side: Side,
    quantity: String,
    price: String,
): TakerTradeEvent =
    TakerTradeEvent(
        symbol = symbol,
        capturedAt = Instant.parse(timestamp),
        takerSide = side,
        quantity = BigDecimal(quantity),
        price = BigDecimal(price),
    )
