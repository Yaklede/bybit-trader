package dev.yaklede.bybittrader.exchange.bybit

import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.market.flow.AccountRatioPeriod
import dev.yaklede.bybittrader.engine.market.flow.OpenInterestInterval
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.Instant

class BybitMarketDataClientTest :
    StringSpec({
        "fetchRecentCandles maps Bybit klines into ascending domain candles" {
            val engine =
                MockEngine { request ->
                    request.url.encodedPath shouldBe "/v5/market/kline"
                    request.url.parameters["category"] shouldBe "linear"
                    request.url.parameters["symbol"] shouldBe "BTCUSDT"
                    request.url.parameters["interval"] shouldBe "15"
                    request.url.parameters["limit"] shouldBe "2"

                    respond(
                        content =
                            """
                            {
                              "retCode": 0,
                              "retMsg": "OK",
                              "result": {
                                "symbol": "BTCUSDT",
                                "category": "linear",
                                "list": [
                                  ["1719749700000", "101", "111", "91", "106", "20", "2120"],
                                  ["1719748800000", "100", "110", "90", "105", "10.5", "1050"]
                                ]
                              },
                              "time": 1719749900000
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = BybitMarketDataClient(jsonClient(engine), baseUrl = "https://api.bybit.test")

            val candles = client.fetchRecentCandles(Symbol("BTCUSDT"), Timeframe.M15, 2)

            candles.map { it.openedAt }.shouldContainExactly(
                listOf(
                    Instant.ofEpochMilli(1719748800000),
                    Instant.ofEpochMilli(1719749700000),
                ),
            )
            candles.first().open shouldBe BigDecimal("100")
            candles.first().close shouldBe BigDecimal("105")
            candles.first().volume shouldBe BigDecimal("10.5")
        }

        "fetchCandles sends Bybit start and end timestamps" {
            val engine =
                MockEngine { request ->
                    request.url.parameters["start"] shouldBe "1719748800000"
                    request.url.parameters["end"] shouldBe "1719749700000"
                    request.url.parameters["limit"] shouldBe "2"

                    respond(
                        content =
                            """
                            {
                              "retCode": 0,
                              "retMsg": "OK",
                              "result": {
                                "symbol": "BTCUSDT",
                                "category": "linear",
                                "list": [
                                  ["1719749700000", "101", "111", "91", "106", "20", "2120"],
                                  ["1719748800000", "100", "110", "90", "105", "10.5", "1050"]
                                ]
                              },
                              "time": 1719749900000
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = BybitMarketDataClient(jsonClient(engine), baseUrl = "https://api.bybit.test")

            val candles =
                client.fetchCandles(
                    symbol = Symbol("BTCUSDT"),
                    timeframe = Timeframe.M15,
                    startAt = Instant.ofEpochMilli(1719748800000),
                    endAt = Instant.ofEpochMilli(1719749700000),
                    limit = 2,
                )

            candles.map { it.openedAt }.shouldContainExactly(
                listOf(
                    Instant.ofEpochMilli(1719748800000),
                    Instant.ofEpochMilli(1719749700000),
                ),
            )
        }

        "fetchRecentCandles fails on Bybit error response" {
            val engine =
                MockEngine {
                    respond(
                        content = """{"retCode":10001,"retMsg":"invalid request"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = BybitMarketDataClient(jsonClient(engine), baseUrl = "https://api.bybit.test")

            shouldThrow<BybitMarketDataException> {
                client.fetchRecentCandles(Symbol("BTCUSDT"), Timeframe.M15, 2)
            }
        }

        "fetchCandles accepts empty result without symbol" {
            val engine =
                MockEngine {
                    respond(
                        content =
                            """
                            {
                              "retCode": 0,
                              "retMsg": "OK",
                              "result": {
                                "list": []
                              },
                              "time": 1719749900000
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = BybitMarketDataClient(jsonClient(engine), baseUrl = "https://api.bybit.test")

            val candles =
                client.fetchCandles(
                    symbol = Symbol("BTCUSDT"),
                    timeframe = Timeframe.M1,
                    startAt = Instant.ofEpochMilli(1719748800000),
                    endAt = Instant.ofEpochMilli(1719749700000),
                    limit = 2,
                )

            candles shouldBe emptyList()
        }

        "fetchRecentCandles maps low timeframe intervals" {
            val requestedIntervals = mutableListOf<String?>()
            val engine =
                MockEngine { request ->
                    requestedIntervals += request.url.parameters["interval"]
                    respond(
                        content =
                            """
                            {
                              "retCode": 0,
                              "retMsg": "OK",
                              "result": {
                                "symbol": "BTCUSDT",
                                "category": "linear",
                                "list": [
                                  ["1719748800000", "100", "110", "90", "105", "10.5", "1050"]
                                ]
                              },
                              "time": 1719749900000
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = BybitMarketDataClient(jsonClient(engine), baseUrl = "https://api.bybit.test")

            client.fetchRecentCandles(Symbol("BTCUSDT"), Timeframe.M1, 1)
            client.fetchRecentCandles(Symbol("BTCUSDT"), Timeframe.M5, 1)

            requestedIntervals.shouldContainExactly(listOf("1", "5"))
        }

        "fetchTicker maps Bybit linear ticker" {
            val engine =
                MockEngine { request ->
                    request.url.encodedPath shouldBe "/v5/market/tickers"
                    request.url.parameters["category"] shouldBe "linear"
                    request.url.parameters["symbol"] shouldBe "BTCUSDT"

                    respond(
                        content =
                            """
                            {
                              "retCode": 0,
                              "retMsg": "OK",
                              "result": {
                                "category": "linear",
                                "list": [
                                  {
                                    "symbol": "BTCUSDT",
                                    "lastPrice": "61234.5",
                                    "markPrice": "61230.1",
                                    "indexPrice": "61220.2",
                                    "price24hPcnt": "0.0123",
                                    "fundingRate": "0.0001",
                                    "nextFundingTime": "1719752400000"
                                  }
                                ]
                              },
                              "time": 1719749900000
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = BybitMarketDataClient(jsonClient(engine), baseUrl = "https://api.bybit.test")

            val ticker = client.fetchTicker(Symbol("BTCUSDT"))

            ticker.symbol shouldBe Symbol("BTCUSDT")
            ticker.lastPrice shouldBe BigDecimal("61234.5")
            ticker.markPrice shouldBe BigDecimal("61230.1")
            ticker.price24hPcnt shouldBe BigDecimal("0.0123")
            ticker.fundingRate shouldBe BigDecimal("0.0001")
            ticker.nextFundingTime shouldBe Instant.ofEpochMilli(1719752400000)
            ticker.capturedAt shouldBe Instant.ofEpochMilli(1719749900000)
        }

        "fetchOpenInterestSnapshots paginates and normalizes reversed provider results" {
            var requestCount = 0
            val engine =
                MockEngine { request ->
                    request.url.encodedPath shouldBe "/v5/market/open-interest"
                    request.url.parameters["category"] shouldBe "linear"
                    request.url.parameters["symbol"] shouldBe "BTCUSDT"
                    request.url.parameters["intervalTime"] shouldBe "5min"
                    request.url.parameters["limit"] shouldBe "2"
                    requestCount += 1
                    when (requestCount) {
                        1 -> {
                            request.url.parameters["cursor"] shouldBe null
                            respond(
                                content =
                                    """
                                    {
                                      "retCode": 0,
                                      "retMsg": "OK",
                                      "result": {
                                        "symbol": "BTCUSDT",
                                        "category": "linear",
                                        "list": [
                                          {"openInterest": "12", "timestamp": "1719749400000"},
                                          {"openInterest": "11", "timestamp": "1719749100000"}
                                        ],
                                        "nextPageCursor": "page-2"
                                      }
                                    }
                                    """.trimIndent(),
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                        else -> {
                            request.url.parameters["cursor"] shouldBe "page-2"
                            respond(
                                content =
                                    """
                                    {
                                      "retCode": 0,
                                      "retMsg": "OK",
                                      "result": {
                                        "symbol": "BTCUSDT",
                                        "category": "linear",
                                        "list": [
                                          {"openInterest": "10", "timestamp": "1719748800000"}
                                        ],
                                        "nextPageCursor": ""
                                      }
                                    }
                                    """.trimIndent(),
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                    }
                }
            val client = BybitMarketDataClient(jsonClient(engine), baseUrl = "https://api.bybit.test")

            val snapshots =
                client.fetchOpenInterestSnapshots(
                    symbol = Symbol("BTCUSDT"),
                    interval = OpenInterestInterval.M5,
                    startAt = Instant.ofEpochMilli(1719748800000),
                    endAt = Instant.ofEpochMilli(1719749400000),
                    limit = 2,
                )

            snapshots.map { it.timestamp }.shouldContainExactly(
                listOf(
                    Instant.ofEpochMilli(1719748800000),
                    Instant.ofEpochMilli(1719749100000),
                    Instant.ofEpochMilli(1719749400000),
                ),
            )
            snapshots.first().openInterest shouldBe BigDecimal("10")
            requestCount shouldBe 2
        }

        "fetchAccountRatioSnapshots paginates and normalizes reversed provider results" {
            var requestCount = 0
            val engine =
                MockEngine { request ->
                    request.url.encodedPath shouldBe "/v5/market/account-ratio"
                    request.url.parameters["category"] shouldBe "linear"
                    request.url.parameters["symbol"] shouldBe "BTCUSDT"
                    request.url.parameters["period"] shouldBe "5min"
                    request.url.parameters["limit"] shouldBe "2"
                    requestCount += 1
                    val result =
                        when (requestCount) {
                            1 -> {
                                request.url.parameters["cursor"] shouldBe null
                                """
                                {
                                  "symbol": "BTCUSDT",
                                  "category": "linear",
                                  "list": [
                                    {"symbol": "BTCUSDT", "buyRatio": "0.60", "sellRatio": "0.40", "timestamp": "1719749400000"},
                                    {"symbol": "BTCUSDT", "buyRatio": "0.55", "sellRatio": "0.45", "timestamp": "1719749100000"}
                                  ],
                                  "nextPageCursor": "page-2"
                                }
                                """.trimIndent()
                            }
                            else -> {
                                request.url.parameters["cursor"] shouldBe "page-2"
                                """
                                {
                                  "symbol": "BTCUSDT",
                                  "category": "linear",
                                  "list": [
                                    {"symbol": "BTCUSDT", "buyRatio": "0.45", "sellRatio": "0.55", "timestamp": "1719748800000"}
                                  ],
                                  "nextPageCursor": ""
                                }
                                """.trimIndent()
                            }
                        }
                    respond(
                        content = "{\"retCode\":0,\"retMsg\":\"OK\",\"result\":$result}",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = BybitMarketDataClient(jsonClient(engine), baseUrl = "https://api.bybit.test")

            val snapshots =
                client.fetchAccountRatioSnapshots(
                    symbol = Symbol("BTCUSDT"),
                    period = AccountRatioPeriod.M5,
                    startAt = Instant.ofEpochMilli(1719748800000),
                    endAt = Instant.ofEpochMilli(1719749400000),
                    limit = 2,
                )

            snapshots.map { it.timestamp }.shouldContainExactly(
                listOf(
                    Instant.ofEpochMilli(1719748800000),
                    Instant.ofEpochMilli(1719749100000),
                    Instant.ofEpochMilli(1719749400000),
                ),
            )
            snapshots.first().buyRatio shouldBe BigDecimal("0.45")
            snapshots.last().sellRatio shouldBe BigDecimal("0.40")
            requestCount shouldBe 2
        }

        "fetchPremiumIndexBars normalizes reverse-sorted Bybit rows" {
            val engine =
                MockEngine { request ->
                    request.url.encodedPath shouldBe "/v5/market/premium-index-price-kline"
                    request.url.parameters["interval"] shouldBe "15"
                    respond(
                        content =
                            """
                            {
                              "retCode": 0,
                              "retMsg": "OK",
                              "result": {
                                "symbol": "BTCUSDT",
                                "category": "linear",
                                "list": [
                                  ["1719749700000", "0.2", "0.3", "0.1", "0.25"],
                                  ["1719748800000", "0.1", "0.2", "0.0", "0.15"]
                                ]
                              }
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = BybitMarketDataClient(jsonClient(engine), baseUrl = "https://api.bybit.test")

            val bars =
                client.fetchPremiumIndexBars(
                    symbol = Symbol("BTCUSDT"),
                    timeframe = Timeframe.M15,
                    startAt = Instant.ofEpochMilli(1719748800000),
                    endAt = Instant.ofEpochMilli(1719749700000),
                    limit = 2,
                )

            bars.map { it.openedAt }.shouldContainExactly(
                listOf(
                    Instant.ofEpochMilli(1719748800000),
                    Instant.ofEpochMilli(1719749700000),
                ),
            )
            bars.first().close shouldBe BigDecimal("0.15")
        }

        "fetchOpenInterestSnapshots rejects a repeated pagination cursor" {
            val engine =
                MockEngine {
                    respond(
                        content =
                            """
                            {
                              "retCode": 0,
                              "retMsg": "OK",
                              "result": {
                                "symbol": "BTCUSDT",
                                "category": "linear",
                                "list": [{"openInterest": "12", "timestamp": "1719749400000"}],
                                "nextPageCursor": "stuck"
                              }
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = BybitMarketDataClient(jsonClient(engine), baseUrl = "https://api.bybit.test")

            shouldThrow<BybitMarketDataException> {
                client.fetchOpenInterestSnapshots(
                    symbol = Symbol("BTCUSDT"),
                    interval = OpenInterestInterval.M5,
                    startAt = Instant.ofEpochMilli(1719748800000),
                    endAt = Instant.ofEpochMilli(1719749400000),
                    limit = 1,
                )
            }
        }

        "fetchPremiumIndexBars continues after a short non-terminal page" {
            var requestCount = 0
            val engine =
                MockEngine {
                    requestCount += 1
                    val rows =
                        if (requestCount == 1) {
                            """
                            [
                              ["1719750600000", "0.2", "0.3", "0.1", "0.25"],
                              ["1719749700000", "0.1", "0.2", "0.0", "0.15"]
                            ]
                            """.trimIndent()
                        } else {
                            """[["1719748800000", "0.0", "0.1", "-0.1", "0.05"]]"""
                        }
                    respond(
                        content =
                            """
                            {
                              "retCode": 0,
                              "retMsg": "OK",
                              "result": {"symbol": "BTCUSDT", "category": "linear", "list": $rows}
                            }
                            """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = BybitMarketDataClient(jsonClient(engine), baseUrl = "https://api.bybit.test")

            val bars =
                client.fetchPremiumIndexBars(
                    symbol = Symbol("BTCUSDT"),
                    timeframe = Timeframe.M15,
                    startAt = Instant.ofEpochMilli(1719748800000),
                    endAt = Instant.ofEpochMilli(1719750600000),
                    limit = 3,
                )

            bars.size shouldBe 3
            requestCount shouldBe 2
        }

        "fetchFundingRateSnapshots fails on Bybit retCode error" {
            val engine =
                MockEngine {
                    respond(
                        content = """{"retCode":10006,"retMsg":"rate limit"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            val client = BybitMarketDataClient(jsonClient(engine), baseUrl = "https://api.bybit.test")

            shouldThrow<BybitMarketDataException> {
                client.fetchFundingRateSnapshots(
                    symbol = Symbol("BTCUSDT"),
                    startAt = Instant.ofEpochMilli(1719748800000),
                    endAt = Instant.ofEpochMilli(1719749700000),
                )
            }
        }

        "archive CSV parser aggregates taker side flow by minute boundary" {
            val bars =
                BybitTakerFlowArchiveParser.parse(
                    lines =
                        sequenceOf(
                            "timestamp,symbol,side,size,price",
                            "1719748859.999,BTCUSDT,Buy,0.10,60000",
                            "1719748860.000,BTCUSDT,Sell,0.20,60010",
                            "1719748861.250,BTCUSDT,Buy,0.05,60020",
                        ),
                    symbol = Symbol("BTCUSDT"),
                )

            bars.map { it.openedAt }.shouldContainExactly(
                listOf(
                    Instant.ofEpochMilli(1719748800000),
                    Instant.ofEpochMilli(1719748860000),
                ),
            )
            bars.first().takerBuyBase shouldBe BigDecimal("0.10")
            bars.first().buyTradeCount shouldBe 1
            bars.last().takerSellBase shouldBe BigDecimal("0.20")
            bars.last().takerSellNotional shouldBe BigDecimal("12002.00")
            bars.last().buyTradeCount shouldBe 1
        }

        "archive CSV parser rejects malformed rows by default and can skip them" {
            val lines =
                sequenceOf(
                    "timestamp,symbol,side,size,price",
                    "1719748800000,BTCUSDT,Buy,0.10,60000",
                    "bad-timestamp,BTCUSDT,Sell,0.20,60010",
                )

            shouldThrow<BybitMarketDataException> {
                BybitTakerFlowArchiveParser.parse(lines = lines, symbol = Symbol("BTCUSDT"))
            }

            val skipped =
                BybitTakerFlowArchiveParser.parse(
                    lines =
                        sequenceOf(
                            "timestamp,symbol,side,size,price",
                            "1719748800000,BTCUSDT,Buy,0.10,60000",
                            "bad-timestamp,BTCUSDT,Sell,0.20,60010",
                        ),
                    symbol = Symbol("BTCUSDT"),
                    malformedRowPolicy = MalformedArchiveRowPolicy.SKIP,
                )

            skipped.single().takerBuyNotional shouldBe BigDecimal("6000.00")
        }
    })

private fun jsonClient(engine: MockEngine): HttpClient =
    HttpClient(engine) {
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                },
            )
        }
    }
