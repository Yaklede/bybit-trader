package dev.yaklede.bybittrader.exchange.bybit

import dev.yaklede.bybittrader.domain.OrderStatus
import dev.yaklede.bybittrader.domain.OrderType
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.execution.ExchangeCancelRequest
import dev.yaklede.bybittrader.engine.execution.ExchangeOrderRequest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class BybitPrivateClientTest :
    StringSpec({
        "placeOrder signs and submits a Bybit market order with TP and SL" {
            val engine =
                MockEngine { request ->
                    request.url.encodedPath shouldBe "/v5/order/create"
                    request.headers["X-BAPI-API-KEY"] shouldBe "test-api-key"
                    request.headers["X-BAPI-TIMESTAMP"] shouldBe "1719705600000"
                    request.headers["X-BAPI-RECV-WINDOW"] shouldBe "5000"
                    request.headers["X-BAPI-SIGN"] shouldBe "9ec8bac2dfd05cf354a9cab25b25ff8939576f0b9e423047f49f3bfd7f1260f6"
                    request.bodyAsText() shouldBe
                        """
                        {"category":"linear","symbol":"BTCUSDT","side":"Buy","orderType":"Market","qty":"0.123","timeInForce":"IOC","orderLinkId":"bt-BTCUSDT-1719748800000-1-B","reduceOnly":false,"takeProfit":"72000","stopLoss":"68000","tpslMode":"Full","positionIdx":0}
                        """.trimIndent()

                    respond(
                        content =
                            """
                            {
                              "retCode": 0,
                              "retMsg": "OK",
                              "result": {
                                "orderId": "exchange-1",
                                "orderLinkId": "bt-BTCUSDT-1719748800000-1-B"
                              }
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = testPrivateClient(engine)

            val result =
                client.placeOrder(
                    ExchangeOrderRequest(
                        symbol = Symbol("BTCUSDT"),
                        side = Side.BUY,
                        orderType = OrderType.MARKET,
                        quantity = BigDecimal("0.123"),
                        clientOrderId = "bt-BTCUSDT-1719748800000-1-B",
                        takeProfit = BigDecimal("72000"),
                        stopLoss = BigDecimal("68000"),
                    ),
                )

            result.exchangeOrderId shouldBe "exchange-1"
            result.clientOrderId shouldBe "bt-BTCUSDT-1719748800000-1-B"
            result.status shouldBe OrderStatus.SUBMITTED
        }

        "cancelOrder submits orderLinkId when exchange order id is absent" {
            val engine =
                MockEngine { request ->
                    request.url.encodedPath shouldBe "/v5/order/cancel"
                    request.bodyAsText() shouldBe
                        """{"category":"linear","symbol":"BTCUSDT","orderLinkId":"client-1"}"""

                    respond(
                        content =
                            """
                            {
                              "retCode": 0,
                              "retMsg": "OK",
                              "result": {
                                "orderId": "exchange-1",
                                "orderLinkId": "client-1"
                              }
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = testPrivateClient(engine)

            val result =
                client.cancelOrder(
                    ExchangeCancelRequest(
                        symbol = Symbol("BTCUSDT"),
                        exchangeOrderId = null,
                        clientOrderId = "client-1",
                    ),
                )

            result.exchangeOrderId shouldBe "exchange-1"
            result.clientOrderId shouldBe "client-1"
        }

        "setLeverage submits the same buy and sell leverage" {
            val engine =
                MockEngine { request ->
                    request.url.encodedPath shouldBe "/v5/position/set-leverage"
                    request.bodyAsText() shouldBe
                        """{"category":"linear","symbol":"BTCUSDT","buyLeverage":"15","sellLeverage":"15"}"""

                    respond(
                        content = """{"retCode":0,"retMsg":"OK"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = testPrivateClient(engine)

            client.setLeverage(Symbol("BTCUSDT"), BigDecimal("15.0"))
        }

        "setLeverage treats unchanged leverage as successful" {
            val engine =
                MockEngine {
                    respond(
                        content = """{"retCode":110043,"retMsg":"leverage not modified"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = testPrivateClient(engine)

            client.setLeverage(Symbol("BTCUSDT"), BigDecimal("15"))
        }

        "reconcile methods map Bybit open orders positions and executions" {
            val requestedPaths = mutableListOf<String>()
            val engine =
                MockEngine { request ->
                    requestedPaths += request.url.encodedPath
                    request.url.parameters["category"] shouldBe "linear"
                    request.url.parameters["symbol"] shouldBe "BTCUSDT"
                    val content =
                        when (request.url.encodedPath) {
                            "/v5/order/realtime" ->
                                """
                                {
                                  "retCode": 0,
                                  "retMsg": "OK",
                                  "result": {
                                    "list": [
                                      {
                                        "orderId": "exchange-1",
                                        "orderLinkId": "client-1",
                                        "symbol": "BTCUSDT",
                                        "side": "Sell",
                                        "orderType": "Market",
                                        "orderStatus": "New",
                                        "qty": "0.2",
                                        "createdTime": "1719748800000"
                                      }
                                    ]
                                  }
                                }
                                """.trimIndent()
                            "/v5/position/list" ->
                                """
                                {
                                  "retCode": 0,
                                  "retMsg": "OK",
                                  "result": {
                                    "list": [
                                      {
                                        "symbol": "BTCUSDT",
                                        "side": "Sell",
                                        "size": "0.2",
                                        "avgPrice": "70000",
                                        "markPrice": "69900",
                                        "unrealisedPnl": "20",
                                        "openTime": 1719748500000,
                                        "updatedTime": "1719748800000"
                                      }
                                    ]
                                  }
                                }
                                """.trimIndent()
                            "/v5/execution/list" ->
                                """
                                {
                                  "retCode": 0,
                                  "retMsg": "OK",
                                  "result": {
                                    "list": [
                                      {
                                        "orderId": "exchange-1",
                                        "orderLinkId": "client-1",
                                        "symbol": "BTCUSDT",
                                        "side": "Sell",
                                        "execPrice": "70000",
                                        "execQty": "0.2",
                                        "execFee": "8.4",
                                        "execTime": "1719748800000"
                                      }
                                    ]
                                  }
                                }
                                """.trimIndent()
                            else -> error("unexpected path ${request.url.encodedPath}")
                        }
                    respond(
                        content = content,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = testPrivateClient(engine)

            val openOrders = client.openOrders(Symbol("BTCUSDT"))
            val positions = client.positions(Symbol("BTCUSDT"))
            val executions = client.executions(Symbol("BTCUSDT"))

            requestedPaths.shouldContainExactly(
                listOf(
                    "/v5/order/realtime",
                    "/v5/position/list",
                    "/v5/execution/list",
                ),
            )
            openOrders.single().status shouldBe OrderStatus.SUBMITTED
            openOrders.single().side shouldBe Side.SELL
            positions.single().unrealizedPnl shouldBe BigDecimal("20")
            positions.single().openedAt shouldBe Instant.ofEpochMilli(1719748500000)
            executions.single().fee shouldBe BigDecimal("8.4")
        }

        "accountBalance maps Bybit unified wallet balance" {
            val engine =
                MockEngine { request ->
                    request.url.encodedPath shouldBe "/v5/account/wallet-balance"
                    request.url.parameters["accountType"] shouldBe "UNIFIED"
                    request.url.parameters["coin"] shouldBe "USDT"

                    respond(
                        content =
                            """
                            {
                              "retCode": 0,
                              "retMsg": "OK",
                              "result": {
                                "list": [
                                  {
                                    "accountType": "UNIFIED",
                                    "totalEquity": "1200.5",
                                    "totalWalletBalance": "1000",
                                    "totalMarginBalance": "1100",
                                    "totalAvailableBalance": "900",
                                    "totalPerpUPL": "100.5",
                                    "totalInitialMargin": "50",
                                    "totalMaintenanceMargin": "20",
                                    "coin": [
                                      {
                                        "coin": "USDT",
                                        "equity": "1200.5",
                                        "usdValue": "1200.5",
                                        "walletBalance": "1000",
                                        "locked": "0",
                                        "unrealisedPnl": "100.5"
                                      }
                                    ]
                                  }
                                ]
                              }
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = testPrivateClient(engine)

            val balance = client.accountBalance("USDT")

            balance.accountType shouldBe "UNIFIED"
            balance.totalEquity shouldBe BigDecimal("1200.5")
            balance.totalPerpUnrealizedPnl shouldBe BigDecimal("100.5")
            balance.coins.single().walletBalance shouldBe BigDecimal("1000")
            balance.capturedAt shouldBe Instant.parse("2024-06-30T00:00:00Z")
        }

        "bybit error responses throw sanitized execution exception" {
            val engine =
                MockEngine {
                    respond(
                        content = """{"retCode":10001,"retMsg":"secret raw provider detail"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = testPrivateClient(engine)

            val error =
                shouldThrow<dev.yaklede.bybittrader.engine.execution.ExchangeExecutionException> {
                    client.openOrders(Symbol("BTCUSDT"))
                }
            error.message shouldContain "Bybit list open orders failed with code 10001"
            error.providerCode shouldBe "10001"
            error.providerMessage shouldBe "secret raw provider detail"
        }
    })

private fun testPrivateClient(engine: MockEngine): BybitPrivateClient =
    BybitPrivateClient(
        httpClient =
            HttpClient(engine) {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                        },
                    )
                }
            },
        config =
            BybitPrivateClientConfig(
                keyId = "test-api-key",
                signingCredential = "test-signing-credential",
                baseUrl = "https://api-testnet.bybit.test",
            ),
        clock = Clock.fixed(Instant.parse("2024-06-30T00:00:00Z"), ZoneOffset.UTC),
    )

private fun io.ktor.client.request.HttpRequestData.bodyAsText(): String =
    when (val content = body) {
        is TextContent -> content.text
        is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
        else -> content.toString()
    }
