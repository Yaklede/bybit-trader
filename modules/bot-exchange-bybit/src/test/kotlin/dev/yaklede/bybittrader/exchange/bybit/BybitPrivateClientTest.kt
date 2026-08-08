package dev.yaklede.bybittrader.exchange.bybit

import dev.yaklede.bybittrader.domain.OrderStatus
import dev.yaklede.bybittrader.domain.OrderType
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.execution.ExchangeAccountMode
import dev.yaklede.bybittrader.engine.execution.ExchangeCancelRequest
import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionException
import dev.yaklede.bybittrader.engine.execution.ExchangeMarginMode
import dev.yaklede.bybittrader.engine.execution.ExchangeOrderRequest
import dev.yaklede.bybittrader.engine.execution.ExchangePositionMode
import dev.yaklede.bybittrader.engine.execution.ExchangePositionProtectionRequest
import dev.yaklede.bybittrader.engine.execution.ExchangeTimeInForce
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

        "placeOrder submits a marketable IOC limit with bounded price and protection" {
            val engine =
                MockEngine { request ->
                    request.url.encodedPath shouldBe "/v5/order/create"
                    request.bodyAsText() shouldBe
                        """{"category":"linear","symbol":"BTCUSDT","side":"Buy","orderType":"Limit","qty":"0.123","price":"70014","timeInForce":"IOC","orderLinkId":"bt-BTCUSDT-1719748800000-2-B","reduceOnly":false,"takeProfit":"72000","stopLoss":"68000","tpslMode":"Full","positionIdx":0}"""
                    respond(
                        content =
                            """{"retCode":0,"retMsg":"OK","result":{"orderId":"exchange-2","orderLinkId":"bt-BTCUSDT-1719748800000-2-B"}}""",
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
                        orderType = OrderType.LIMIT,
                        quantity = BigDecimal("0.123"),
                        clientOrderId = "bt-BTCUSDT-1719748800000-2-B",
                        takeProfit = BigDecimal("72000"),
                        stopLoss = BigDecimal("68000"),
                        price = BigDecimal("70014"),
                        timeInForce = ExchangeTimeInForce.IOC,
                    ),
                )

            result.exchangeOrderId shouldBe "exchange-2"
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

        "setPositionProtection amends full-position TP and SL" {
            val engine =
                MockEngine { request ->
                    request.url.encodedPath shouldBe "/v5/position/trading-stop"
                    request.bodyAsText() shouldBe
                        """{"category":"linear","symbol":"BTCUSDT","takeProfit":"72000","stopLoss":"68000","tpslMode":"Full","tpTriggerBy":"LastPrice","slTriggerBy":"LastPrice","positionIdx":0}"""
                    respond(
                        content = """{"retCode":0,"retMsg":"OK"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = testPrivateClient(engine)

            client.setPositionProtection(
                ExchangePositionProtectionRequest(
                    symbol = Symbol("BTCUSDT"),
                    takeProfit = BigDecimal("72000"),
                    stopLoss = BigDecimal("68000"),
                ),
            )
        }

        "setPositionProtection clears TP for a stop-only position" {
            val engine =
                MockEngine { request ->
                    request.url.encodedPath shouldBe "/v5/position/trading-stop"
                    request.bodyAsText() shouldBe
                        """{"category":"linear","symbol":"BTCUSDT","takeProfit":"0","stopLoss":"68000","tpslMode":"Full","slTriggerBy":"LastPrice","positionIdx":0}"""
                    respond(
                        content = """{"retCode":0,"retMsg":"OK"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = testPrivateClient(engine)

            client.setPositionProtection(
                ExchangePositionProtectionRequest(
                    symbol = Symbol("BTCUSDT"),
                    takeProfit = null,
                    stopLoss = BigDecimal("68000"),
                ),
            )
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
                                        "createdTime": "1719748800000",
                                        "reduceOnly": true,
                                        "stopOrderType": "UNKNOWN"
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
                                        "takeProfit": "68000",
                                        "stopLoss": "71000",
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
                                        "execTime": "1719748800000",
                                        "execId": "exec-1",
                                        "execPnl": "20",
                                        "closedSize": "0.2",
                                        "execType": "Trade",
                                        "createType": "CreateByStopLoss",
                                        "stopOrderType": "StopLoss"
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
            openOrders.single().reduceOnly shouldBe true
            openOrders.single().stopOrderType shouldBe "UNKNOWN"
            positions.single().unrealizedPnl shouldBe BigDecimal("20")
            positions.single().openedAt shouldBe Instant.ofEpochMilli(1719748500000)
            positions.single().takeProfit shouldBe BigDecimal("68000")
            positions.single().stopLoss shouldBe BigDecimal("71000")
            executions.single().fee shouldBe BigDecimal("8.4")
            executions.single().executionId shouldBe "exec-1"
            executions.single().executionPnl shouldBe BigDecimal("20")
            executions.single().closedSize shouldBe BigDecimal("0.2")
            executions.single().createType shouldBe "CreateByStopLoss"
            executions.single().stopOrderType shouldBe "StopLoss"
        }

        "executions retrieves every cursor page" {
            var requestCount = 0
            val engine =
                MockEngine { request ->
                    requestCount += 1
                    request.url.encodedPath shouldBe "/v5/execution/list"
                    request.url.parameters["limit"] shouldBe "100"
                    request.url.parameters["cursor"] shouldBe if (requestCount == 1) null else "execution-next"
                    respond(
                        content =
                            executionHistoryResponse(
                                nextCursor = if (requestCount == 1) "execution-next" else "",
                                executionId = "exec-$requestCount",
                                executedAt = 1719748800000L + requestCount,
                            ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = testPrivateClient(engine)

            val executions = client.executions(Symbol("BTCUSDT"))

            requestCount shouldBe 2
            executions.mapNotNull { it.executionId }.shouldContainExactly("exec-1", "exec-2")
        }

        "executions rejects a repeated cursor" {
            var requestCount = 0
            val engine =
                MockEngine { request ->
                    requestCount += 1
                    request.url.parameters["cursor"] shouldBe if (requestCount == 1) null else "stuck"
                    respond(
                        content = executionHistoryResponse(nextCursor = "stuck"),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = testPrivateClient(engine)

            val error = shouldThrow<ExchangeExecutionException> { client.executions(Symbol("BTCUSDT")) }

            error.message shouldBe "Bybit execution history pagination repeated a cursor."
            requestCount shouldBe 2
        }

        "closedPnls retrieves every cursor page" {
            var requestCount = 0
            val engine =
                MockEngine { request ->
                    requestCount += 1
                    request.url.encodedPath shouldBe "/v5/position/closed-pnl"
                    request.url.parameters["limit"] shouldBe "100"
                    request.url.parameters["cursor"] shouldBe if (requestCount == 1) null else "closed-next"
                    respond(
                        content =
                            closedPnlHistoryResponse(
                                nextCursor = if (requestCount == 1) "closed-next" else "",
                                orderId = "closed-$requestCount",
                                closedAt = 1719748800000L + requestCount,
                            ),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = testPrivateClient(engine)

            val closedPnls = client.closedPnls(Symbol("BTCUSDT"))

            requestCount shouldBe 2
            closedPnls.mapNotNull { it.exchangeOrderId }.shouldContainExactly("closed-1", "closed-2")
        }

        "closedPnls rejects a repeated cursor" {
            var requestCount = 0
            val engine =
                MockEngine { request ->
                    requestCount += 1
                    request.url.parameters["cursor"] shouldBe if (requestCount == 1) null else "stuck"
                    respond(
                        content = closedPnlHistoryResponse(nextCursor = "stuck"),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = testPrivateClient(engine)

            val error = shouldThrow<ExchangeExecutionException> { client.closedPnls(Symbol("BTCUSDT")) }

            error.message shouldBe "Bybit closed PnL history pagination repeated a cursor."
            requestCount shouldBe 2
        }

        "order lookup falls back to history for a completed IOC" {
            val requestedPaths = mutableListOf<String>()
            val engine =
                MockEngine { request ->
                    requestedPaths += request.url.encodedPath
                    request.url.parameters["category"] shouldBe "linear"
                    request.url.parameters["orderLinkId"] shouldBe "vct-e-b-1786060800-abcd1234"
                    val list =
                        if (request.url.encodedPath == "/v5/order/realtime") {
                            "[]"
                        } else {
                            """
                            [{
                              "orderId":"exchange-history-1",
                              "orderLinkId":"vct-e-b-1786060800-abcd1234",
                              "symbol":"BTCUSDT",
                              "side":"Buy",
                              "orderType":"Limit",
                              "orderStatus":"Cancelled",
                              "qty":"0.007",
                              "cumExecQty":"0",
                              "createdTime":"1786060800000",
                              "updatedTime":"1786060800100",
                              "reduceOnly":false,
                              "cancelType":"CancelByNoImmediateQtyToFill",
                              "rejectReason":"EC_NoError"
                            }]
                            """.trimIndent()
                        }
                    respond(
                        content = """{"retCode":0,"retMsg":"OK","result":{"list":$list}}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = testPrivateClient(engine)

            val order = client.order(Symbol("BTCUSDT"), "vct-e-b-1786060800-abcd1234")

            requestedPaths.shouldContainExactly(listOf("/v5/order/realtime", "/v5/order/history"))
            order?.status shouldBe OrderStatus.CANCELLED
            order?.filledQuantity shouldBe BigDecimal.ZERO
            order?.providerStatus shouldBe "Cancelled"
            order?.cancelType shouldBe "CancelByNoImmediateQtyToFill"
            order?.rejectReason shouldBe "EC_NoError"
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

        "execution profiles map account position mode leverage and instrument rules" {
            val requestedPaths = mutableListOf<String>()
            val engine =
                MockEngine { request ->
                    requestedPaths += request.url.encodedPath
                    val content =
                        when (request.url.encodedPath) {
                            "/v5/account/info" -> {
                                request.headers["X-BAPI-API-KEY"] shouldBe "test-api-key"
                                """{"retCode":0,"retMsg":"OK","result":{"unifiedMarginStatus":5,"marginMode":"REGULAR_MARGIN","updatedTime":"1719705600000"}}"""
                            }
                            "/v5/position/list" -> {
                                request.headers["X-BAPI-API-KEY"] shouldBe "test-api-key"
                                """{"retCode":0,"retMsg":"OK","result":{"list":[{"symbol":"BTCUSDT","side":"","size":"0","positionIdx":0,"leverage":"1","isReduceOnly":false}]}}"""
                            }
                            "/v5/market/instruments-info" -> {
                                request.headers["X-BAPI-API-KEY"] shouldBe null
                                """
                                {
                                  "retCode": 0,
                                  "retMsg": "OK",
                                  "result": {
                                    "list": [{
                                      "symbol": "BTCUSDT",
                                      "status": "Trading",
                                      "contractType": "LinearPerpetual",
                                      "baseCoin": "BTC",
                                      "quoteCoin": "USDT",
                                      "settleCoin": "USDT",
                                      "unifiedMarginTrade": true,
                                      "lotSizeFilter": {"minOrderQty":"0.001","qtyStep":"0.001","minNotionalValue":"5"},
                                      "priceFilter": {"tickSize":"0.1"},
                                      "leverageFilter": {"minLeverage":"1","maxLeverage":"100","leverageStep":"0.01"}
                                    }]
                                  }
                                }
                                """.trimIndent()
                            }
                            else -> error("unexpected path ${request.url.encodedPath}")
                        }
                    respond(
                        content = content,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = testPrivateClient(engine)

            val account = client.accountExecutionProfile()
            val position = client.positionExecutionProfile(Symbol("BTCUSDT"))
            val instrument = client.instrumentRules(Symbol("BTCUSDT"))

            requestedPaths.shouldContainExactly(
                "/v5/account/info",
                "/v5/position/list",
                "/v5/market/instruments-info",
            )
            account.accountMode shouldBe ExchangeAccountMode.UNIFIED_2
            account.marginMode shouldBe ExchangeMarginMode.CROSS
            position.positionMode shouldBe ExchangePositionMode.ONE_WAY
            position.buyLeverage shouldBe BigDecimal.ONE
            position.sellLeverage shouldBe BigDecimal.ONE
            instrument.minimumOrderQuantity shouldBe BigDecimal("0.001")
            instrument.quantityStep shouldBe BigDecimal("0.001")
            instrument.minimumNotional shouldBe BigDecimal("5")
            instrument.priceTick shouldBe BigDecimal("0.1")
            instrument.unifiedMarginTrade shouldBe true
        }

        "accountTransactions paginates and maps balance-changing events" {
            var requestCount = 0
            val engine =
                MockEngine { request ->
                    requestCount += 1
                    request.url.encodedPath shouldBe "/v5/account/transaction-log"
                    request.url.parameters["accountType"] shouldBe "UNIFIED"
                    request.url.parameters["category"] shouldBe "linear"
                    request.url.parameters["currency"] shouldBe "USDT"
                    request.url.parameters["startTime"] shouldBe "1719705600000"
                    request.url.parameters["endTime"] shouldBe "1719792000000"
                    request.url.parameters["limit"] shouldBe "50"
                    val repeatedTrade =
                        """
                        {
                          "id": "transaction-1",
                          "symbol": "BTCUSDT",
                          "category": "linear",
                          "side": "Buy",
                          "transactionTime": "1719748800000",
                          "type": "TRADE",
                          "transSubType": "",
                          "qty": "0.2",
                          "size": "0.2",
                          "currency": "USDT",
                          "tradePrice": "70000",
                          "funding": "0",
                          "fee": "8.4",
                          "cashFlow": "20",
                          "change": "11.6",
                          "cashBalance": "1011.6",
                          "feeRate": "0.0006",
                          "tradeId": "trade-1",
                          "orderId": "exchange-1",
                          "orderLinkId": "client-1"
                        }
                        """.trimIndent()
                    val content =
                        if (requestCount == 1) {
                            request.url.parameters["cursor"] shouldBe null
                            """
                            {"retCode":0,"retMsg":"OK","result":{"nextPageCursor":"cursor-2","list":[$repeatedTrade]}}
                            """.trimIndent()
                        } else {
                            request.url.parameters["cursor"] shouldBe "cursor-2"
                            """
                            {
                              "retCode": 0,
                              "retMsg": "OK",
                              "result": {
                                "nextPageCursor": "",
                                "list": [
                                  $repeatedTrade,
                                  {
                                    "id": "transaction-2",
                                    "symbol": "BTCUSDT",
                                    "category": "linear",
                                    "side": "None",
                                    "transactionTime": "1719752400000",
                                    "type": "SETTLEMENT",
                                    "currency": "USDT",
                                    "funding": "-0.5",
                                    "fee": "0",
                                    "cashFlow": "0",
                                    "change": "-0.5",
                                    "cashBalance": "1011.1"
                                  }
                                ]
                              }
                            }
                            """.trimIndent()
                        }
                    respond(
                        content = content,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = testPrivateClient(engine)

            val transactions =
                client.accountTransactions(
                    currency = "usdt",
                    startAt = Instant.parse("2024-06-30T00:00:00Z"),
                    endAt = Instant.parse("2024-07-01T00:00:00Z"),
                )

            requestCount shouldBe 2
            transactions.map { it.transactionId }.shouldContainExactly("transaction-1", "transaction-2")
            transactions.first().change shouldBe BigDecimal("11.6")
            transactions.first().fee shouldBe BigDecimal("8.4")
            transactions.first().side shouldBe Side.BUY
            transactions.last().funding shouldBe BigDecimal("-0.5")
            transactions.last().side shouldBe null
        }

        "accountTransactions rejects a repeated cursor" {
            var requestCount = 0
            val engine =
                MockEngine { request ->
                    requestCount += 1
                    request.url.parameters["cursor"] shouldBe if (requestCount == 1) null else "stuck"
                    respond(
                        content =
                            """
                            {
                              "retCode": 0,
                              "retMsg": "OK",
                              "result": {
                                "nextPageCursor": "stuck",
                                "list": []
                              }
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = testPrivateClient(engine)

            val error =
                shouldThrow<ExchangeExecutionException> {
                    client.accountTransactions(
                        currency = "USDT",
                        startAt = Instant.parse("2024-06-30T00:00:00Z"),
                        endAt = Instant.parse("2024-07-01T00:00:00Z"),
                    )
                }

            error.message shouldBe "Bybit transaction log pagination repeated a cursor."
            requestCount shouldBe 2
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

private fun executionHistoryResponse(
    nextCursor: String,
    executionId: String? = null,
    executedAt: Long = 1719748800000L,
): String {
    val list =
        executionId?.let {
            """
            [{
              "orderId": "exchange-$it",
              "orderLinkId": "client-$it",
              "symbol": "BTCUSDT",
              "side": "Buy",
              "execPrice": "70000",
              "execQty": "0.001",
              "execFee": "0.042",
              "execTime": "$executedAt",
              "execId": "$it",
              "execType": "Trade"
            }]
            """.trimIndent()
        } ?: "[]"
    return """
        {"retCode":0,"retMsg":"OK","result":{"nextPageCursor":"$nextCursor","list":$list}}
        """.trimIndent()
}

private fun closedPnlHistoryResponse(
    nextCursor: String,
    orderId: String? = null,
    closedAt: Long = 1719748800000L,
): String {
    val list =
        orderId?.let {
            """
            [{
              "orderId": "$it",
              "orderLinkId": "client-$it",
              "symbol": "BTCUSDT",
              "side": "Buy",
              "qty": "0.001",
              "avgEntryPrice": "70000",
              "avgExitPrice": "71000",
              "closedPnl": "0.9",
              "openFee": "0.04",
              "closeFee": "0.04",
              "createdTime": "1719745200000",
              "updatedTime": "$closedAt"
            }]
            """.trimIndent()
        } ?: "[]"
    return """
        {"retCode":0,"retMsg":"OK","result":{"nextPageCursor":"$nextCursor","list":$list}}
        """.trimIndent()
}

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
