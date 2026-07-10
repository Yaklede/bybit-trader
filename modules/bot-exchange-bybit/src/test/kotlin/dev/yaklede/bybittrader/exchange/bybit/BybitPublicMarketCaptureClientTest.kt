package dev.yaklede.bybittrader.exchange.bybit

import dev.yaklede.bybittrader.engine.market.capture.LiquidatedPositionSide
import dev.yaklede.bybittrader.engine.market.capture.LiquidationEvent
import dev.yaklede.bybittrader.engine.market.capture.OrderBookDepthSnapshot
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.math.BigDecimal
import java.time.Instant

class BybitPublicMarketCaptureClientTest :
    StringSpec({
        "parser maintains an order book across snapshot and delta messages" {
            val parser = BybitPublicMarketCaptureParser(orderBookDepth = 1)

            val snapshot =
                parser
                    .parse(
                        """
                        {
                          "topic":"orderbook.50.BTCUSDT",
                          "type":"snapshot",
                          "data":{"s":"BTCUSDT","b":[["100","2"],["99","3"]],"a":[["101","4"],["102","5"]],"cts":1719748800000}
                        }
                        """.trimIndent(),
                    ).single() as OrderBookDepthSnapshot
            snapshot.bidNotional shouldBe BigDecimal("200")
            snapshot.askNotional shouldBe BigDecimal("404")
            snapshot.capturedAt shouldBe Instant.ofEpochMilli(1719748800000)

            val delta =
                parser
                    .parse(
                        """
                        {
                          "topic":"orderbook.50.BTCUSDT",
                          "type":"delta",
                          "data":{"s":"BTCUSDT","b":[["100","0"]],"a":[["101","1"]],"cts":1719748800200}
                        }
                        """.trimIndent(),
                    ).single() as OrderBookDepthSnapshot
            delta.bidNotional shouldBe BigDecimal("297")
            delta.askNotional shouldBe BigDecimal("101")
        }

        "parser maps Bybit liquidation sides to liquidated positions" {
            val parser = BybitPublicMarketCaptureParser(orderBookDepth = 1)

            val events =
                parser
                    .parse(
                        """
                        {
                          "topic":"allLiquidation.BTCUSDT",
                          "data":[
                            {"T":1719748800000,"s":"BTCUSDT","S":"Buy","v":"2","p":"100"},
                            {"T":1719748800100,"s":"BTCUSDT","S":"Sell","v":"3","p":"101"}
                          ]
                        }
                        """.trimIndent(),
                    ).map { it as LiquidationEvent }

            events.map { it.liquidatedSide }.shouldContainExactly(
                LiquidatedPositionSide.LONG,
                LiquidatedPositionSide.SHORT,
            )
            events.map { it.notional }.shouldContainExactly(BigDecimal("200"), BigDecimal("303"))
        }
    })
