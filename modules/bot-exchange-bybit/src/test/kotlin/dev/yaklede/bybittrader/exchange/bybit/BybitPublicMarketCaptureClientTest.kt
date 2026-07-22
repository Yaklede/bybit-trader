package dev.yaklede.bybittrader.exchange.bybit

import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.engine.market.capture.ForwardMarketDataQuality
import dev.yaklede.bybittrader.engine.market.capture.LiquidatedPositionSide
import dev.yaklede.bybittrader.engine.market.capture.LiquidationEvent
import dev.yaklede.bybittrader.engine.market.capture.OrderBookDepthSnapshot
import dev.yaklede.bybittrader.engine.market.capture.TakerTradeEvent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class BybitPublicMarketCaptureClientTest :
    StringSpec({
        "parser maintains an order book across sequential snapshot and delta messages" {
            val parser = BybitPublicMarketCaptureParser(orderBookDepth = 1)
            parser.beginConnection("connection-1")

            val snapshot =
                parser
                    .parse(
                        """
                        {
                          "topic":"orderbook.50.BTCUSDT",
                          "type":"snapshot",
                          "ts":1719748800002,
                          "data":{"s":"BTCUSDT","b":[["100","2"],["99","3"]],"a":[["101","4"],["102","5"]],"u":100,"seq":1000},
                          "cts":1719748800000
                        }
                        """.trimIndent(),
                        Instant.parse("2026-07-10T00:00:00.010Z"),
                    )!!
            val snapshotEvent = snapshot.normalizedEvents.single() as OrderBookDepthSnapshot
            snapshotEvent.bidNotional shouldBe BigDecimal("200")
            snapshotEvent.askNotional shouldBe BigDecimal("404")
            snapshotEvent.capturedAt shouldBe Instant.ofEpochMilli(1719748800000)
            snapshot.rawEvent.quality shouldBe ForwardMarketDataQuality.SNAPSHOT_RESET
            snapshot.rawEvent.updateId shouldBe 100L
            snapshot.rawEvent.sequenceStart shouldBe 1000L
            snapshot.rawEvent.bookEpoch shouldBe 1L
            snapshot.rawEvent.matchingEngineTimestamp shouldBe Instant.ofEpochMilli(1719748800000)

            val delta =
                parser
                    .parse(
                        """
                        {
                          "topic":"orderbook.50.BTCUSDT",
                          "type":"delta",
                          "ts":1719748800202,
                          "data":{"s":"BTCUSDT","b":[["100","0"]],"a":[["101","1"]],"u":101,"seq":1001},
                          "cts":1719748800200
                        }
                        """.trimIndent(),
                        Instant.parse("2026-07-10T00:00:00.210Z"),
                    )!!
            val deltaEvent = delta.normalizedEvents.single() as OrderBookDepthSnapshot
            deltaEvent.bidNotional shouldBe BigDecimal("297")
            deltaEvent.askNotional shouldBe BigDecimal("101")
            delta.rawEvent.quality shouldBe ForwardMarketDataQuality.VALID
        }

        "parser invalidates the local book when an update ID is non-monotonic" {
            val parser = BybitPublicMarketCaptureParser(orderBookDepth = 1)
            parser.beginConnection("connection-gap")
            parser.parse(orderBookPayload(type = "snapshot", updateId = 40, sequence = 900), RECEIVED_AT)

            val gap = parser.parse(orderBookPayload(type = "delta", updateId = 40, sequence = 902), RECEIVED_AT)!!
            gap.rawEvent.quality shouldBe ForwardMarketDataQuality.NON_MONOTONIC_UPDATE_ID
            gap.rawEvent.gapDetected shouldBe true
            gap.normalizedEvents shouldBe emptyList()

            val afterGap = parser.parse(orderBookPayload(type = "delta", updateId = 41, sequence = 903), RECEIVED_AT)!!
            afterGap.rawEvent.quality shouldBe ForwardMarketDataQuality.DELTA_BEFORE_SNAPSHOT
            afterGap.normalizedEvents shouldBe emptyList()
        }

        "parser requires a fresh snapshot after each connection" {
            val parser = BybitPublicMarketCaptureParser(orderBookDepth = 1)
            parser.beginConnection("connection-old")
            parser.parse(orderBookPayload(type = "snapshot", updateId = 10, sequence = 100), RECEIVED_AT)

            parser.beginConnection("connection-new")
            val batch = parser.parse(orderBookPayload(type = "delta", updateId = 11, sequence = 101), RECEIVED_AT)!!

            batch.rawEvent.localConnectionId shouldBe "connection-new"
            batch.rawEvent.quality shouldBe ForwardMarketDataQuality.DELTA_BEFORE_SNAPSHOT
            batch.normalizedEvents shouldBe emptyList()
        }

        "parser treats update ID one as an order book reset" {
            val parser = BybitPublicMarketCaptureParser(orderBookDepth = 1)
            parser.beginConnection("connection-reset")
            parser.parse(orderBookPayload(type = "snapshot", updateId = 50, sequence = 1000), RECEIVED_AT)

            val reset = parser.parse(orderBookPayload(type = "delta", updateId = 1, sequence = 2000), RECEIVED_AT)!!

            reset.rawEvent.quality shouldBe ForwardMarketDataQuality.SNAPSHOT_RESET
            reset.rawEvent.bookEpoch shouldBe 2L
            reset.normalizedEvents.size shouldBe 1
        }

        "parser maps Bybit liquidation sides to liquidated positions" {
            val parser = BybitPublicMarketCaptureParser(orderBookDepth = 1)
            parser.beginConnection("connection-liquidation")

            val batch =
                parser.parse(
                    """
                    {
                      "topic":"allLiquidation.BTCUSDT",
                      "type":"snapshot",
                      "ts":1719748800200,
                      "data":[
                        {"T":1719748800000,"s":"BTCUSDT","S":"Buy","v":"2","p":"100"},
                        {"T":1719748800100,"s":"BTCUSDT","S":"Sell","v":"3","p":"101"}
                      ]
                    }
                    """.trimIndent(),
                    RECEIVED_AT,
                )!!
            val events = batch.normalizedEvents.map { it as LiquidationEvent }

            events.map { it.liquidatedSide }.shouldContainExactly(
                LiquidatedPositionSide.LONG,
                LiquidatedPositionSide.SHORT,
            )
            events.map { it.notional }.shouldContainExactly(BigDecimal("200"), BigDecimal("303"))
            batch.rawEvent.sequenceStart shouldBe null
            batch.rawEvent.quality shouldBe ForwardMarketDataQuality.VALID
        }

        "parser preserves the sequence range for batched public trades" {
            val parser = BybitPublicMarketCaptureParser(orderBookDepth = 1)
            parser.beginConnection("connection-trade")

            val batch =
                parser.parse(
                    """
                    {
                      "topic":"publicTrade.BTCUSDT",
                      "type":"snapshot",
                      "ts":1719748800200,
                      "data":[
                        {"T":1719748800000,"s":"BTCUSDT","S":"Buy","v":"2","p":"100","seq":700},
                        {"T":1719748800100,"s":"BTCUSDT","S":"Sell","v":"3","p":"101","seq":701}
                      ]
                    }
                    """.trimIndent(),
                    RECEIVED_AT,
                )!!
            val events = batch.normalizedEvents.map { it as TakerTradeEvent }

            events.map { it.takerSide }.shouldContainExactly(Side.BUY, Side.SELL)
            events.map { it.quantity }.shouldContainExactly(BigDecimal("2"), BigDecimal("3"))
            events.map { it.price }.shouldContainExactly(BigDecimal("100"), BigDecimal("101"))
            batch.rawEvent.sequenceStart shouldBe 700L
            batch.rawEvent.sequenceEnd shouldBe 701L
        }
    })

private fun orderBookPayload(
    type: String,
    updateId: Long,
    sequence: Long,
): String =
    """
    {
      "topic":"orderbook.50.BTCUSDT",
      "type":"$type",
      "ts":1719748800002,
      "data":{"s":"BTCUSDT","b":[["100","2"]],"a":[["101","4"]],"u":$updateId,"seq":$sequence},
      "cts":1719748800000
    }
    """.trimIndent()

private val RECEIVED_AT = Instant.parse("2026-07-10T00:00:00.010Z")
