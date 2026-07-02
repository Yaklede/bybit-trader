package dev.yaklede.bybittrader.exchange.bybit

import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
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
