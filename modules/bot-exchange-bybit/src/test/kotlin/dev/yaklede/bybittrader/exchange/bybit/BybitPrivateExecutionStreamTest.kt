package dev.yaklede.bybittrader.exchange.bybit

import dev.yaklede.bybittrader.domain.Side
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.math.BigDecimal
import java.time.Instant

class BybitPrivateExecutionStreamTest :
    StringSpec({
        "parser maps private execution metadata and closure size" {
            val executions =
                BybitPrivateExecutionParser().parse(
                    """
                    {
                      "topic":"execution",
                      "id":"private-1",
                      "creationTime":"1719748800100",
                      "data":[
                        {
                          "execId":"exec-1",
                          "orderId":"order-1",
                          "orderLinkId":"close-BTCUSDT-1",
                          "symbol":"BTCUSDT",
                          "side":"Sell",
                          "execPrice":"63275.7",
                          "execQty":"0.001",
                          "execFee":"0.03480164",
                          "execTime":"1719748800000",
                          "execPnl":"-0.304",
                          "closedSize":"0.001",
                          "execType":"Trade",
                          "createType":"CreateByStopLoss",
                          "stopOrderType":"StopLoss"
                        }
                      ]
                    }
                    """.trimIndent(),
                )

            executions.size shouldBe 1
            val execution = executions.single()
            execution.executionId shouldBe "exec-1"
            execution.exchangeOrderId shouldBe "order-1"
            execution.clientOrderId shouldBe "close-BTCUSDT-1"
            execution.symbol.value shouldBe "BTCUSDT"
            execution.side shouldBe Side.SELL
            execution.price shouldBe BigDecimal("63275.7")
            execution.quantity shouldBe BigDecimal("0.001")
            execution.fee shouldBe BigDecimal("0.03480164")
            execution.executedAt shouldBe Instant.ofEpochMilli(1719748800000)
            execution.closedSize shouldBe BigDecimal("0.001")
            execution.executionPnl shouldBe BigDecimal("-0.304")
            execution.createType shouldBe "CreateByStopLoss"
            execution.stopOrderType shouldBe "StopLoss"
        }

        "parser ignores non execution messages and invalid rows" {
            val parser = BybitPrivateExecutionParser()

            parser.parse("""{"op":"pong"}""") shouldBe emptyList()
            parser.parse(
                """
                {"topic":"execution","data":[
                  {"symbol":"BTCUSDT","side":"Buy","execPrice":"0","execQty":"0","execTime":"1"}
                ]}
                """.trimIndent(),
            ) shouldBe emptyList()
        }

        "parser exposes authentication failures for reconnect handling" {
            val failure =
                BybitPrivateExecutionParser().controlFailure(
                    """{"op":"auth","success":false,"ret_msg":"invalid request"}""",
                )

            failure shouldContain "auth"
            failure shouldContain "invalid request"
        }

        "parser keeps multiple execution rows in exchange order" {
            val payload =
                """
                {"topic":"execution","data":[
                  {"execId":"1","symbol":"BTCUSDT","side":"Buy","execPrice":"100","execQty":"1","execTime":"1000"},
                  {"execId":"2","symbol":"BTCUSDT","side":"Sell","execPrice":"101","execQty":"1","execTime":"1001"}
                ]}
                """.trimIndent()

            BybitPrivateExecutionParser().parse(payload).map { it.side }.shouldContainExactly(Side.BUY, Side.SELL)
        }
    })
