package dev.yaklede.bybittrader.engine.market.maker

import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.market.capture.ForwardMarketCaptureBatch
import dev.yaklede.bybittrader.engine.market.capture.ForwardMarketDataQuality
import dev.yaklede.bybittrader.engine.market.capture.ForwardMarketEventKind
import dev.yaklede.bybittrader.engine.market.capture.ForwardMarketMessageType
import dev.yaklede.bybittrader.engine.market.capture.ForwardMarketRawEvent
import dev.yaklede.bybittrader.engine.market.capture.OrderBookDepthSnapshot
import dev.yaklede.bybittrader.engine.market.capture.TakerTradeEvent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

class MakerShadowEngineTest :
    StringSpec({
        "book touch and displayed quantity reduction do not create fills" {
            val ledger = RecordingMakerShadowLedger()
            val engine = MakerShadowEngine(config(), ledger)

            engine.onBatch(book(sequence = 100, quality = ForwardMarketDataQuality.SNAPSHOT_RESET))
            engine.onBatch(
                book(
                    sequence = 101,
                    quality = ForwardMarketDataQuality.VALID,
                    bidQuantity = "0.5",
                    askQuantity = "1",
                    second = 1,
                ),
            )

            val snapshot = engine.snapshot()
            snapshot.state shouldBe MakerShadowState.QUOTING
            snapshot.inventoryQuantity shouldBe BigDecimal.ZERO
            snapshot.activeQuotes.map { it.side }.shouldContainExactly(Side.BUY, Side.SELL)
            snapshot.activeQuotes.first { it.side == Side.BUY }.queueAhead shouldBe BigDecimal("2")
            ledger.events.none { it.type.isFill() } shouldBe true
        }

        "only later exact-price opposite taker volume can consume queue and partially fill" {
            val ledger = RecordingMakerShadowLedger()
            val engine = MakerShadowEngine(config(), ledger)
            engine.onBatch(book(sequence = 100, quality = ForwardMarketDataQuality.SNAPSHOT_RESET))

            engine.onBatch(trade(id = "same-sequence", sequence = 100, side = Side.SELL, price = "100", quantity = "10"))
            engine.onBatch(trade(id = "sell-1", sequence = 101, side = Side.SELL, price = "100", quantity = "1"))
            engine.onBatch(trade(id = "wrong-side", sequence = 102, side = Side.BUY, price = "100", quantity = "10"))
            engine.onBatch(trade(id = "wrong-price", sequence = 103, side = Side.SELL, price = "99", quantity = "10"))
            engine.onBatch(trade(id = "sell-2", sequence = 104, side = Side.SELL, price = "100", quantity = "1"))

            engine.snapshot().inventoryQuantity shouldBe BigDecimal.ZERO
            ledger.events.none { it.type.isFill() } shouldBe true

            val partial = trade(id = "sell-3", sequence = 105, side = Side.SELL, price = "100", quantity = "0.2")
            engine.onBatch(partial)
            engine.onBatch(partial)

            val snapshot = engine.snapshot()
            snapshot.inventoryQuantity shouldBe BigDecimal("0.2")
            snapshot.cash shouldBe BigDecimal("79.9800")
            snapshot.equity shouldBe BigDecimal("100.0800")
            snapshot.totalMakerFees shouldBe BigDecimal("0.0200")
            snapshot.activeQuotes shouldBe emptyList()
            snapshot.processedTradeIds shouldBe 6
            ledger.events.count { it.type.isFill() } shouldBe 1
            ledger.events.single { it.type == MakerShadowLedgerEventType.PARTIAL_FILL }.tradeId shouldBe "sell-3"

            engine.onBatch(book(sequence = 106, quality = ForwardMarketDataQuality.VALID, second = 2))
            engine.snapshot().activeQuotes.single().let { quote ->
                quote.side shouldBe Side.SELL
                quote.originalQuantity shouldBe BigDecimal("0.2")
                quote.queueAhead shouldBe BigDecimal("4")
            }
        }

        "flat inventory never opens only one side when the notional cap blocks the other" {
            val ledger = RecordingMakerShadowLedger()
            val cappedConfig = config().copy(maxNotional = BigDecimal("50"))
            val engine = MakerShadowEngine(cappedConfig, ledger)

            engine.onBatch(book(sequence = 100, quality = ForwardMarketDataQuality.SNAPSHOT_RESET))

            engine.snapshot().activeQuotes shouldBe emptyList()
            ledger.events.none { it.type == MakerShadowLedgerEventType.QUOTE_OPENED } shouldBe true
        }

        "open inventory receives a reducing quote even when spread is below the entry threshold" {
            val ledger = RecordingMakerShadowLedger()
            val engine = MakerShadowEngine(config(), ledger)
            engine.onBatch(
                book(
                    sequence = 100,
                    quality = ForwardMarketDataQuality.SNAPSHOT_RESET,
                    bidQuantity = "1",
                ),
            )
            engine.onBatch(trade(id = "entry", sequence = 101, side = Side.SELL, price = "100", quantity = "1.2"))

            engine.onBatch(
                book(
                    sequence = 102,
                    quality = ForwardMarketDataQuality.VALID,
                    bidPrice = "100",
                    askPrice = "100.001",
                    second = 2,
                ),
            )

            engine
                .snapshot()
                .activeQuotes
                .single()
                .side shouldBe Side.SELL
        }

        "gap invalidates quotes and a new snapshot is required before quoting resumes" {
            val ledger = RecordingMakerShadowLedger()
            val engine = MakerShadowEngine(config(), ledger)
            engine.onBatch(book(sequence = 100, quality = ForwardMarketDataQuality.SNAPSHOT_RESET))

            engine.onBatch(gap(sequence = 100, quality = ForwardMarketDataQuality.NON_MONOTONIC_UPDATE_ID, second = 1))
            engine.snapshot().state shouldBe MakerShadowState.HALTED_DATA_QUALITY
            engine.snapshot().activeQuotes shouldBe emptyList()

            engine.onBatch(book(sequence = 101, quality = ForwardMarketDataQuality.VALID, second = 2))
            engine.snapshot().state shouldBe MakerShadowState.HALTED_DATA_QUALITY

            engine.onBatch(
                book(
                    sequence = 200,
                    quality = ForwardMarketDataQuality.SNAPSHOT_RESET,
                    epoch = 2,
                    second = 3,
                ),
            )
            engine.snapshot().state shouldBe MakerShadowState.QUOTING
            engine.snapshot().activeQuotes.size shouldBe 2
            ledger.events.count { it.type == MakerShadowLedgerEventType.SHADOW_HALTED } shouldBe 1
        }

        "maximum holding duration closes inventory with conservative taker costs and records mark-outs" {
            val ledger = RecordingMakerShadowLedger()
            val engine =
                MakerShadowEngine(
                    config(
                        makerFeeRate = "0.001",
                        takerFeeRate = "0.002",
                        takerExitSlippageBps = "10",
                        maxHoldingDuration = Duration.ofSeconds(5),
                    ),
                    ledger,
                )
            engine.onBatch(
                book(
                    sequence = 100,
                    quality = ForwardMarketDataQuality.SNAPSHOT_RESET,
                    bidQuantity = "1",
                    askQuantity = "1",
                ),
            )
            engine.onBatch(trade(id = "entry", sequence = 101, side = Side.SELL, price = "100", quantity = "1.5"))

            engine.onBatch(
                book(
                    sequence = 102,
                    quality = ForwardMarketDataQuality.VALID,
                    bidPrice = "101",
                    askPrice = "102",
                    second = 2,
                ),
            )
            engine.onBatch(
                book(
                    sequence = 103,
                    quality = ForwardMarketDataQuality.VALID,
                    bidPrice = "98",
                    askPrice = "99",
                    second = 7,
                ),
            )

            val snapshot = engine.snapshot()
            snapshot.inventoryQuantity shouldBe BigDecimal.ZERO
            snapshot.cash shouldBe BigDecimal("98.8030980")
            snapshot.equity shouldBe BigDecimal("98.8030980")
            snapshot.totalMakerFees shouldBe BigDecimal("0.0500")
            snapshot.totalTakerFees shouldBe BigDecimal("0.0979020")
            snapshot.state shouldBe MakerShadowState.WAITING_FOR_BOOK
            ledger.events.count { it.type == MakerShadowLedgerEventType.FORCED_TAKER_EXIT } shouldBe 1
            ledger.events.count { it.type == MakerShadowLedgerEventType.MARK_OUT_1S } shouldBe 1
            ledger.events.count { it.type == MakerShadowLedgerEventType.MARK_OUT_5S } shouldBe 1
        }

        "the same ordered evidence produces an identical ledger" {
            suspend fun replay(): List<MakerShadowLedgerEvent> {
                val ledger = RecordingMakerShadowLedger()
                val engine = MakerShadowEngine(config(), ledger)
                engine.onBatch(book(sequence = 100, quality = ForwardMarketDataQuality.SNAPSHOT_RESET))
                engine.onBatch(trade(id = "queue", sequence = 101, side = Side.SELL, price = "100", quantity = "2"))
                engine.onBatch(trade(id = "fill", sequence = 102, side = Side.SELL, price = "100", quantity = "0.5"))
                engine.onBatch(book(sequence = 103, quality = ForwardMarketDataQuality.VALID, second = 2))
                return ledger.events
            }

            replay() shouldBe replay()
        }
    })

private class RecordingMakerShadowLedger : MakerShadowLedger {
    val events = mutableListOf<MakerShadowLedgerEvent>()

    override suspend fun append(events: List<MakerShadowLedgerEvent>) {
        this.events += events
    }
}

private fun config(
    makerFeeRate: String = "0.001",
    takerFeeRate: String = "0.002",
    takerExitSlippageBps: String = "10",
    maxHoldingDuration: Duration = Duration.ofSeconds(30),
): MakerShadowConfig =
    MakerShadowConfig(
        sessionId = "shadow-test",
        symbol = SYMBOL,
        initialEquity = BigDecimal("100"),
        orderQuantity = BigDecimal("0.5"),
        maxNotional = BigDecimal("100"),
        queueMultiplier = BigDecimal.ONE,
        queueBufferQuantity = BigDecimal.ZERO,
        minSpreadBps = BigDecimal.ONE,
        makerFeeRate = BigDecimal(makerFeeRate),
        takerFeeRate = BigDecimal(takerFeeRate),
        takerExitSlippageBps = BigDecimal(takerExitSlippageBps),
        maxQuoteAge = Duration.ofSeconds(10),
        maxHoldingDuration = maxHoldingDuration,
        maxEventDelay = Duration.ofSeconds(1),
    )

private fun book(
    sequence: Long,
    quality: ForwardMarketDataQuality,
    bidPrice: String = "100",
    bidQuantity: String = "2",
    askPrice: String = "101",
    askQuantity: String = "4",
    epoch: Long = 1,
    second: Long = 0,
): ForwardMarketCaptureBatch {
    val eventAt = BASE_TIME.plusSeconds(second)
    val receivedAt = eventAt.plusMillis(10)
    val bid = BigDecimal(bidPrice)
    val ask = BigDecimal(askPrice)
    val midpoint = (bid + ask).divide(BigDecimal("2"))
    val spreadBps = (ask - bid).divide(midpoint, java.math.MathContext.DECIMAL64).multiply(BigDecimal("10000"))
    return ForwardMarketCaptureBatch(
        rawEvent =
            rawEvent(
                kind = ForwardMarketEventKind.ORDER_BOOK,
                quality = quality,
                eventAt = eventAt,
                receivedAt = receivedAt,
                sequence = sequence,
                epoch = epoch,
            ),
        normalizedEvents =
            listOf(
                OrderBookDepthSnapshot(
                    symbol = SYMBOL,
                    capturedAt = eventAt,
                    bidNotional = bid.multiply(BigDecimal(bidQuantity)),
                    askNotional = ask.multiply(BigDecimal(askQuantity)),
                    spreadBps = spreadBps,
                    bestBidPrice = bid,
                    bestBidQuantity = BigDecimal(bidQuantity),
                    bestAskPrice = ask,
                    bestAskQuantity = BigDecimal(askQuantity),
                    updateId = sequence,
                    crossSequence = sequence,
                    bookEpoch = epoch,
                    matchingEngineTimestamp = eventAt,
                    receivedAt = receivedAt,
                    quality = quality,
                ),
            ),
    )
}

private fun trade(
    id: String,
    sequence: Long,
    side: Side,
    price: String,
    quantity: String,
    second: Long = 1,
): ForwardMarketCaptureBatch {
    val eventAt = BASE_TIME.plusSeconds(second)
    val receivedAt = eventAt.plusMillis(10)
    return ForwardMarketCaptureBatch(
        rawEvent =
            rawEvent(
                kind = ForwardMarketEventKind.PUBLIC_TRADE,
                quality = ForwardMarketDataQuality.VALID,
                eventAt = eventAt,
                receivedAt = receivedAt,
                sequence = sequence,
            ),
        normalizedEvents =
            listOf(
                TakerTradeEvent(
                    symbol = SYMBOL,
                    capturedAt = eventAt,
                    takerSide = side,
                    quantity = BigDecimal(quantity),
                    price = BigDecimal(price),
                    tradeId = id,
                    crossSequence = sequence,
                    matchingEngineTimestamp = eventAt,
                    receivedAt = receivedAt,
                ),
            ),
    )
}

private fun gap(
    sequence: Long,
    quality: ForwardMarketDataQuality,
    second: Long,
): ForwardMarketCaptureBatch {
    val eventAt = BASE_TIME.plusSeconds(second)
    return ForwardMarketCaptureBatch(
        rawEvent =
            rawEvent(
                kind = ForwardMarketEventKind.ORDER_BOOK,
                quality = quality,
                eventAt = eventAt,
                receivedAt = eventAt.plusMillis(10),
                sequence = sequence,
                epoch = 1,
            ),
        normalizedEvents = emptyList(),
    )
}

private fun rawEvent(
    kind: ForwardMarketEventKind,
    quality: ForwardMarketDataQuality,
    eventAt: Instant,
    receivedAt: Instant,
    sequence: Long,
    epoch: Long? = null,
): ForwardMarketRawEvent =
    ForwardMarketRawEvent(
        localConnectionId = "shadow-connection",
        topic =
            when (kind) {
                ForwardMarketEventKind.ORDER_BOOK -> "orderbook.50.${SYMBOL.value}"
                ForwardMarketEventKind.PUBLIC_TRADE -> "publicTrade.${SYMBOL.value}"
                ForwardMarketEventKind.LIQUIDATION -> "allLiquidation.${SYMBOL.value}"
            },
        symbol = SYMBOL,
        eventKind = kind,
        messageType = ForwardMarketMessageType.SNAPSHOT,
        exchangeTimestamp = eventAt,
        matchingEngineTimestamp = eventAt,
        receivedAt = receivedAt,
        sequenceStart = sequence,
        sequenceEnd = sequence,
        updateId = sequence.takeIf { kind == ForwardMarketEventKind.ORDER_BOOK },
        bookEpoch = epoch,
        quality = quality,
        rawPayload = "{\"sequence\":$sequence}",
    )

private fun MakerShadowLedgerEventType.isFill(): Boolean =
    this == MakerShadowLedgerEventType.FILL || this == MakerShadowLedgerEventType.PARTIAL_FILL

private val SYMBOL = Symbol("BTCUSDT")
private val BASE_TIME = Instant.parse("2026-08-06T00:00:00Z")
