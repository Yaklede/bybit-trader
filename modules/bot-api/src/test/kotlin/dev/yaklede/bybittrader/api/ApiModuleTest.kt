package dev.yaklede.bybittrader.api

import dev.yaklede.bybittrader.api.backtest.VolumeFlowCompositeBacktestRequest
import dev.yaklede.bybittrader.api.backtest.VolumeFlowCompositeCurrentConfigProvider
import dev.yaklede.bybittrader.api.backtest.VolumeFlowCompositeLegRequest
import dev.yaklede.bybittrader.domain.BotMode
import dev.yaklede.bybittrader.domain.Candle
import dev.yaklede.bybittrader.domain.Price
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.SignalIntent
import dev.yaklede.bybittrader.domain.SignalScore
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.domain.Timeframe
import dev.yaklede.bybittrader.engine.backtest.BacktestRunner
import dev.yaklede.bybittrader.engine.backtest.BacktestService
import dev.yaklede.bybittrader.engine.backtest.MeanReversionSweepService
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowBacktestService
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowCompositeBacktestService
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowSweepService
import dev.yaklede.bybittrader.engine.control.BotControlService
import dev.yaklede.bybittrader.engine.control.BotRuntimeStatus
import dev.yaklede.bybittrader.engine.control.BotStateStore
import dev.yaklede.bybittrader.engine.control.ControlEvent
import dev.yaklede.bybittrader.engine.control.ControlEventRecorder
import dev.yaklede.bybittrader.engine.market.MarketCandleStore
import dev.yaklede.bybittrader.engine.market.MarketDataException
import dev.yaklede.bybittrader.engine.market.MarketDataFeed
import dev.yaklede.bybittrader.engine.market.MarketDataSyncService
import dev.yaklede.bybittrader.engine.paper.PaperFillRecord
import dev.yaklede.bybittrader.engine.paper.PaperOrderRecord
import dev.yaklede.bybittrader.engine.paper.PaperPerformanceSnapshot
import dev.yaklede.bybittrader.engine.paper.PaperPositionRecord
import dev.yaklede.bybittrader.engine.paper.PaperSignalRecord
import dev.yaklede.bybittrader.engine.paper.PaperTradeRecord
import dev.yaklede.bybittrader.engine.paper.PaperTradingService
import dev.yaklede.bybittrader.engine.paper.PaperTradingStore
import dev.yaklede.bybittrader.strategy.StrategyDecision
import dev.yaklede.bybittrader.strategy.TradingStrategy
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ApiModuleTest :
    StringSpec({
        "health endpoint is public" {
            testApplication {
                application {
                    val stateStore = InMemoryStateStore()
                    configureApi(
                        stateStore = stateStore,
                        controlService = BotControlService(stateStore, InMemoryControlEventRecorder()),
                        marketDataSyncService = testMarketDataSyncService(),
                        backtestService = testBacktestService(),
                        meanReversionSweepService = testMeanReversionSweepService(),
                        volumeFlowBacktestService = testVolumeFlowBacktestService(),
                        controlCredential = "test-control-credential",
                    )
                }

                client.get("/health").status shouldBe HttpStatusCode.OK
            }
        }

        "control endpoints require bearer guard" {
            testApplication {
                application {
                    val stateStore = InMemoryStateStore()
                    configureApi(
                        stateStore = stateStore,
                        controlService = BotControlService(stateStore, InMemoryControlEventRecorder()),
                        marketDataSyncService = testMarketDataSyncService(),
                        backtestService = testBacktestService(),
                        meanReversionSweepService = testMeanReversionSweepService(),
                        volumeFlowBacktestService = testVolumeFlowBacktestService(),
                        controlCredential = "test-control-credential",
                    )
                }

                client
                    .post("/control/pause-all") {
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody("""{"reason":"manual"}""")
                    }.status shouldBe HttpStatusCode.Unauthorized
            }
        }

        "authorized pause all request changes bot mode" {
            testApplication {
                val stateStore = InMemoryStateStore()
                application {
                    configureApi(
                        stateStore = stateStore,
                        controlService = BotControlService(stateStore, InMemoryControlEventRecorder()),
                        marketDataSyncService = testMarketDataSyncService(),
                        backtestService = testBacktestService(),
                        meanReversionSweepService = testMeanReversionSweepService(),
                        volumeFlowBacktestService = testVolumeFlowBacktestService(),
                        controlCredential = "test-control-credential",
                    )
                }

                client
                    .post("/control/pause-all") {
                        bearerAuth("test-control-credential")
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody("""{"reason":"manual"}""")
                    }.status shouldBe HttpStatusCode.OK

                stateStore.current().mode shouldBe BotMode.PAUSE_ALL
            }
        }

        "authorized market data sync request stores fetched candles" {
            testApplication {
                val stateStore = InMemoryStateStore()
                val store = InMemoryMarketCandleStore()
                application {
                    configureApi(
                        stateStore = stateStore,
                        controlService = BotControlService(stateStore, InMemoryControlEventRecorder()),
                        marketDataSyncService = testMarketDataSyncService(store),
                        backtestService = testBacktestService(),
                        meanReversionSweepService = testMeanReversionSweepService(),
                        volumeFlowBacktestService = testVolumeFlowBacktestService(),
                        controlCredential = "test-control-credential",
                    )
                }

                val status =
                    client
                        .post("/market-data/sync") {
                            bearerAuth("test-control-credential")
                            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                            setBody("""{"symbol":"BTCUSDT","timeframes":["M15"],"limit":2}""")
                        }.status

                status shouldBe HttpStatusCode.OK
                store.saved.size shouldBe 1
                store.saved
                    .first()
                    .single()
                    .symbol shouldBe Symbol("BTCUSDT")
            }
        }

        "authorized market data history sync request stores fetched candles" {
            testApplication {
                val stateStore = InMemoryStateStore()
                val store = InMemoryMarketCandleStore()
                application {
                    configureApi(
                        stateStore = stateStore,
                        controlService = BotControlService(stateStore, InMemoryControlEventRecorder()),
                        marketDataSyncService = testMarketDataSyncService(store),
                        backtestService = testBacktestService(),
                        meanReversionSweepService = testMeanReversionSweepService(),
                        volumeFlowBacktestService = testVolumeFlowBacktestService(),
                        controlCredential = "test-control-credential",
                    )
                }

                val status =
                    client
                        .post("/market-data/history/sync") {
                            bearerAuth("test-control-credential")
                            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                            setBody(
                                """
                                {
                                  "symbol":"BTCUSDT",
                                  "timeframes":["M15"],
                                  "startAt":"2026-06-30T00:00:00Z",
                                  "endAt":"2026-06-30T00:15:00Z",
                                  "daysBack":3650,
                                  "pageLimit":10,
                                  "maxRequestsPerTimeframe":10000
                                }
                                """.trimIndent(),
                            )
                        }.status

                status shouldBe HttpStatusCode.OK
                store.saved.size shouldBe 1
                store.saved
                    .first()
                    .single()
                    .timeframe shouldBe Timeframe.M15
            }
        }

        "market data provider errors return sanitized bad gateway response" {
            testApplication {
                val stateStore = InMemoryStateStore()
                application {
                    configureApi(
                        stateStore = stateStore,
                        controlService = BotControlService(stateStore, InMemoryControlEventRecorder()),
                        marketDataSyncService =
                            MarketDataSyncService(
                                marketDataFeed = FailingMarketDataFeed(),
                                candleStore = InMemoryMarketCandleStore(),
                            ),
                        backtestService = testBacktestService(),
                        meanReversionSweepService = testMeanReversionSweepService(),
                        volumeFlowBacktestService = testVolumeFlowBacktestService(),
                        controlCredential = "test-control-credential",
                    )
                }

                client
                    .post("/market-data/sync") {
                        bearerAuth("test-control-credential")
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody("""{"symbol":"BTCUSDT","timeframes":["M15"],"limit":2}""")
                    }.status shouldBe HttpStatusCode.BadGateway
            }
        }

        "authorized backtest request returns estimated result" {
            testApplication {
                val stateStore = InMemoryStateStore()
                application {
                    configureApi(
                        stateStore = stateStore,
                        controlService = BotControlService(stateStore, InMemoryControlEventRecorder()),
                        marketDataSyncService = testMarketDataSyncService(),
                        backtestService =
                            BacktestService(
                                candleStore = InMemoryMarketCandleStore(backtestCandles()),
                                runner = BacktestRunner(AlwaysBuyApiStrategy()),
                            ),
                        meanReversionSweepService = testMeanReversionSweepService(),
                        volumeFlowBacktestService = testVolumeFlowBacktestService(),
                        controlCredential = "test-control-credential",
                    )
                }

                client
                    .post("/backtests/run") {
                        bearerAuth("test-control-credential")
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody("""{"symbol":"BTCUSDT","timeframe":"M15","candleLimit":30}""")
                    }.status shouldBe HttpStatusCode.OK
            }
        }

        "authorized mean reversion sweep request returns top results" {
            testApplication {
                val stateStore = InMemoryStateStore()
                val candleStore = InMemoryMarketCandleStore(sweepApiCandles())
                application {
                    configureApi(
                        stateStore = stateStore,
                        controlService = BotControlService(stateStore, InMemoryControlEventRecorder()),
                        marketDataSyncService = testMarketDataSyncService(),
                        backtestService = testBacktestService(),
                        meanReversionSweepService = MeanReversionSweepService(candleStore),
                        volumeFlowBacktestService = testVolumeFlowBacktestService(),
                        controlCredential = "test-control-credential",
                    )
                }

                client
                    .post("/backtests/mean-reversion/sweep") {
                        bearerAuth("test-control-credential")
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody(
                            """
                            {
                              "symbol":"BTCUSDT",
                              "timeframe":"M15",
                              "candleLimit":80,
                              "oversoldRsiValues":[30.0],
                              "overboughtRsiValues":[70.0],
                              "bollingerStdDevValues":[2.0],
                              "atrStopMultiplierValues":[1.2],
                              "topResults":1
                            }
                            """.trimIndent(),
                        )
                    }.status shouldBe HttpStatusCode.OK
            }
        }

        "authorized volume flow backtest request returns estimated result" {
            testApplication {
                val stateStore = InMemoryStateStore()
                val candleStore = InMemoryMarketCandleStore(volumeFlowApiCandles())
                application {
                    configureApi(
                        stateStore = stateStore,
                        controlService = BotControlService(stateStore, InMemoryControlEventRecorder()),
                        marketDataSyncService = testMarketDataSyncService(),
                        backtestService = testBacktestService(),
                        meanReversionSweepService = testMeanReversionSweepService(),
                        volumeFlowBacktestService = VolumeFlowBacktestService(candleStore),
                        controlCredential = "test-control-credential",
                    )
                }

                val response =
                    client
                        .post("/backtests/volume-flow/run") {
                            bearerAuth("test-control-credential")
                            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                            setBody(
                                """
                                {
                                  "symbol":"BTCUSDT",
                                  "m1Limit":80,
                                  "m5Limit":30,
                                  "m15Limit":30,
                                  "setupMode":"BREAKOUT_CONTINUATION",
                                  "entryMode":"RETEST_CONFIRMATION",
                                  "setupTimeframe":"M5",
                                  "volumeLookback":3,
                                  "relativeVolumeThreshold":2.0,
                                  "volumeZScoreThreshold":0.5,
                                  "setupRangeLookback":3,
                                  "requireM5Vwap":true,
                                  "m5VwapLookback":3,
                                  "contextVwapLookback":3,
                                  "requireContextVwap":true,
                                  "minBodyRatio":0.4,
                                  "minRejectionWickRatio":0.25,
                                  "entryLookaheadM1Candles":3,
                                  "entryRetestTolerancePct":0.01,
                                  "targetR":0.5,
                                  "maxHoldM1Candles":5
                                }
                                """.trimIndent(),
                            )
                        }
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldContain """"compoundDailyReturnPct":"""
                body shouldContain """"activeDayCoveragePct":"""
                body shouldContain """"averageWinR":"""
                body shouldContain """"averageLossR":"""
                body shouldContain """"payoffRatio":"""
                body shouldContain """"breakevenWinRatePct":"""
                body shouldContain """"winRateEdgePct":"""
            }
        }

        "authorized composite volume flow backtest request returns estimated result" {
            testApplication {
                val stateStore = InMemoryStateStore()
                val candleStore = InMemoryMarketCandleStore(volumeFlowApiCandles())
                application {
                    configureApi(
                        stateStore = stateStore,
                        controlService = BotControlService(stateStore, InMemoryControlEventRecorder()),
                        marketDataSyncService = testMarketDataSyncService(),
                        backtestService = testBacktestService(),
                        meanReversionSweepService = testMeanReversionSweepService(),
                        volumeFlowBacktestService = testVolumeFlowBacktestService(),
                        volumeFlowCompositeBacktestService = VolumeFlowCompositeBacktestService(candleStore),
                        controlCredential = "test-control-credential",
                    )
                }

                val response =
                    client
                        .post("/backtests/volume-flow/composite/run") {
                            bearerAuth("test-control-credential")
                            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                            setBody(
                                """
                                {
                                  "symbol":"BTCUSDT",
                                  "m1Limit":80,
                                  "m5Limit":30,
                                  "m15Limit":30,
                                  "maxConcurrentPositions":2,
                                  "tradeLimit":0,
                                  "equityCurveLimit":1,
                                  "drawdownEventLimit":1,
                                  "legs":[
                                    {
                                      "id":"primary",
                                      "setupMode":"BREAKOUT_CONTINUATION",
                                      "entryMode":"RETEST_CONFIRMATION",
                                      "setupTimeframe":"M5",
                                      "volumeLookback":3,
                                      "relativeVolumeThreshold":2.0,
                                      "volumeZScoreThreshold":0.5,
                                      "setupRangeLookback":3,
                                      "requireM5Vwap":true,
                                      "m5VwapLookback":3,
                                      "contextVwapLookback":3,
                                      "requireContextVwap":true,
                                      "minBodyRatio":0.4,
                                      "entryLookaheadM1Candles":3,
                                      "entryRetestTolerancePct":0.01,
                                      "targetR":0.5,
                                      "maxHoldM1Candles":5
                                    }
                                  ]
                                }
                                """.trimIndent(),
                            )
                        }
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldContain """"compoundDailyReturnPct":"""
                body shouldContain """"activeDayCoveragePct":"""
                body shouldContain """"averageWinR":"""
                body shouldContain """"averageLossR":"""
                body shouldContain """"payoffRatio":"""
                body shouldContain """"breakevenWinRatePct":"""
                body shouldContain """"winRateEdgePct":"""
                body shouldContain """"equityCurve":[{"""
                body shouldContain """"drawdownEvents":[{"""
                body shouldContain """"markToMarketDrawdownPct":"""
                body shouldContain """"trades":[]"""
            }
        }

        "authorized current composite volume flow backtest request loads configured strategy" {
            testApplication {
                val stateStore = InMemoryStateStore()
                val candleStore = InMemoryMarketCandleStore(volumeFlowApiCandles())
                val currentConfigProvider =
                    VolumeFlowCompositeCurrentConfigProvider {
                        VolumeFlowCompositeBacktestRequest(
                            symbol = "BTCUSDT",
                            m1Limit = 80,
                            m5Limit = 30,
                            m15Limit = 30,
                            tradeLimit = 50,
                            equityCurveLimit = 50,
                            drawdownEventLimit = 20,
                            legs =
                                listOf(
                                    VolumeFlowCompositeLegRequest(
                                        id = "primary",
                                        setupMode = "BREAKOUT_CONTINUATION",
                                        entryMode = "RETEST_CONFIRMATION",
                                        setupTimeframe = "M5",
                                        volumeLookback = 3,
                                        relativeVolumeThreshold = 2.0,
                                        volumeZScoreThreshold = 0.5,
                                        setupRangeLookback = 3,
                                        requireM5Vwap = true,
                                        m5VwapLookback = 3,
                                        contextVwapLookback = 3,
                                        requireContextVwap = true,
                                        minBodyRatio = 0.4,
                                        entryLookaheadM1Candles = 3,
                                        entryRetestTolerancePct = 0.01,
                                        targetR = 0.5,
                                        maxHoldM1Candles = 5,
                                    ),
                                ),
                        )
                    }
                application {
                    configureApi(
                        stateStore = stateStore,
                        controlService = BotControlService(stateStore, InMemoryControlEventRecorder()),
                        marketDataSyncService = testMarketDataSyncService(),
                        backtestService = testBacktestService(),
                        meanReversionSweepService = testMeanReversionSweepService(),
                        volumeFlowBacktestService = testVolumeFlowBacktestService(),
                        volumeFlowCompositeBacktestService = VolumeFlowCompositeBacktestService(candleStore),
                        volumeFlowCompositeCurrentConfigProvider = currentConfigProvider,
                        controlCredential = "test-control-credential",
                    )
                }

                val response =
                    client
                        .post("/backtests/volume-flow/composite/current/run") {
                            bearerAuth("test-control-credential")
                            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                            setBody(
                                """
                                {
                                  "m1Limit":1600001,
                                  "m5Limit":320001,
                                  "m15Limit":110001,
                                  "tradeLimit":0,
                                  "equityCurveLimit":1,
                                  "drawdownEventLimit":1
                                }
                                """.trimIndent(),
                            )
                        }
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldContain """"symbol":"BTCUSDT""""
                body shouldContain """"equityCurve":[{"""
                body shouldContain """"drawdownEvents":[{"""
                body shouldContain """"trades":[]"""
            }
        }

        "authorized volume flow sweep request returns ranked candidates" {
            testApplication {
                val stateStore = InMemoryStateStore()
                val candleStore = InMemoryMarketCandleStore(volumeFlowSweepApiCandles())
                application {
                    configureApi(
                        stateStore = stateStore,
                        controlService = BotControlService(stateStore, InMemoryControlEventRecorder()),
                        marketDataSyncService = testMarketDataSyncService(),
                        backtestService = testBacktestService(),
                        meanReversionSweepService = testMeanReversionSweepService(),
                        volumeFlowBacktestService = testVolumeFlowBacktestService(),
                        volumeFlowSweepService = VolumeFlowSweepService(candleStore),
                        controlCredential = "test-control-credential",
                    )
                }

                val response =
                    client
                        .post("/backtests/volume-flow/sweep") {
                            bearerAuth("test-control-credential")
                            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                            setBody(
                                """
                                {
                                  "symbol":"BTCUSDT",
                                  "m1Limit":160,
                                  "m5Limit":80,
                                  "m15Limit":80,
                                  "riskFractionValues":[0.0025],
                                  "setupModes":["BREAKOUT_CONTINUATION","FAILED_BREAK_REVERSAL"],
                                  "entryModes":["RETEST_CONFIRMATION"],
                                  "setupTimeframes":["M5"],
                                  "volumeLookback":3,
                                  "relativeVolumeThresholdValues":[2.0],
                                  "volumeZScoreThresholdValues":[0.5],
                                  "setupRangeLookbackValues":[3],
                                  "requireM5VwapValues":[false],
                                  "contextVwapLookback":3,
                                  "requireContextVwapValues":[true,false],
                                  "requireContextTrendValues":[false],
                                  "minRejectionWickRatioValues":[0.25],
                                  "entryLookaheadM1CandlesValues":[3],
                                  "targetRValues":[0.5],
                                  "maxHoldM1CandlesValues":[5],
                                  "maxCandidates":4,
                                  "topResults":1
                                }
                                """.trimIndent(),
                            )
                        }
                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldContain """"compoundDailyReturnPct":"""
                body shouldContain """"activeDayCoveragePct":"""
                body shouldContain """"averageWinR":"""
                body shouldContain """"averageLossR":"""
                body shouldContain """"payoffRatio":"""
                body shouldContain """"breakevenWinRatePct":"""
                body shouldContain """"winRateEdgePct":"""
            }
        }

        "authorized paper evaluate request records a paper fill" {
            testApplication {
                val stateStore = InMemoryStateStore()
                val paperStore = InMemoryPaperTradingStore()
                application {
                    configureApi(
                        stateStore = stateStore,
                        controlService = BotControlService(stateStore, InMemoryControlEventRecorder()),
                        marketDataSyncService = testMarketDataSyncService(),
                        backtestService = testBacktestService(),
                        meanReversionSweepService = testMeanReversionSweepService(),
                        volumeFlowBacktestService = testVolumeFlowBacktestService(),
                        paperTradingService =
                            PaperTradingService(
                                stateStore = stateStore,
                                candleStore = InMemoryMarketCandleStore(backtestCandles()),
                                paperTradingStore = paperStore,
                                strategy = AlwaysBuyApiStrategy(),
                                clock = Clock.fixed(Instant.parse("2026-06-30T00:10:00Z"), ZoneOffset.UTC),
                            ),
                        paperTradingReportStore = paperStore,
                        controlCredential = "test-control-credential",
                    )
                }

                client
                    .post("/paper/evaluate") {
                        bearerAuth("test-control-credential")
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody("""{"symbol":"BTCUSDT","timeframe":"M15","candleLimit":30}""")
                    }.status shouldBe HttpStatusCode.OK

                paperStore.orders.size shouldBe 1
                paperStore.fills.size shouldBe 1
                client
                    .get("/signals/recent?limit=5") {
                        bearerAuth("test-control-credential")
                    }.status shouldBe HttpStatusCode.OK
            }
        }
    })

private class InMemoryStateStore : BotStateStore {
    private var status =
        BotRuntimeStatus(
            mode = BotMode.RUNNING,
            updatedAt = Instant.parse("2026-06-30T00:00:00Z"),
            heartbeatAt = null,
        )

    override suspend fun current(): BotRuntimeStatus = status

    override suspend fun update(status: BotRuntimeStatus) {
        this.status = status
    }
}

private class InMemoryControlEventRecorder : ControlEventRecorder {
    override suspend fun record(event: ControlEvent) = Unit
}

private fun testMarketDataSyncService(store: InMemoryMarketCandleStore = InMemoryMarketCandleStore()): MarketDataSyncService =
    MarketDataSyncService(
        marketDataFeed = StaticMarketDataFeed(),
        candleStore = store,
        clock = Clock.fixed(Instant.parse("2026-06-30T00:00:00Z"), ZoneOffset.UTC),
    )

private fun testBacktestService(store: InMemoryMarketCandleStore = InMemoryMarketCandleStore()): BacktestService =
    BacktestService(
        candleStore = store,
        runner = BacktestRunner(NoTradeApiStrategy()),
    )

private fun testMeanReversionSweepService(store: InMemoryMarketCandleStore = InMemoryMarketCandleStore()): MeanReversionSweepService =
    MeanReversionSweepService(candleStore = store)

private fun testVolumeFlowBacktestService(store: InMemoryMarketCandleStore = InMemoryMarketCandleStore()): VolumeFlowBacktestService =
    VolumeFlowBacktestService(candleStore = store)

private class StaticMarketDataFeed : MarketDataFeed {
    override suspend fun fetchRecentCandles(
        symbol: Symbol,
        timeframe: Timeframe,
        limit: Int,
    ): List<Candle> =
        listOf(
            Candle(
                symbol = symbol,
                timeframe = timeframe,
                openedAt = Instant.parse("2026-06-30T00:00:00Z"),
                open = BigDecimal("100"),
                high = BigDecimal("110"),
                low = BigDecimal("90"),
                close = BigDecimal("105"),
                volume = BigDecimal("10.5"),
            ),
        )
}

private class InMemoryMarketCandleStore(
    private val existingCandles: List<Candle> = emptyList(),
) : MarketCandleStore {
    val saved = mutableListOf<List<Candle>>()

    override suspend fun upsert(candles: List<Candle>) {
        saved += candles
    }

    override suspend fun recentCandles(
        symbol: Symbol,
        timeframe: Timeframe,
        limit: Int,
    ): List<Candle> =
        existingCandles
            .filter { it.symbol == symbol && it.timeframe == timeframe }
            .sortedByDescending { it.openedAt }
            .take(limit)
}

private class FailingMarketDataFeed : MarketDataFeed {
    override suspend fun fetchRecentCandles(
        symbol: Symbol,
        timeframe: Timeframe,
        limit: Int,
    ): List<Candle> = throw MarketDataException("provider failed with raw details")
}

private class InMemoryPaperTradingStore : PaperTradingStore {
    val signals = mutableListOf<PaperSignalRecord>()
    val orders = mutableListOf<PaperOrderRecord>()
    val fills = mutableListOf<PaperFillRecord>()
    val positions = mutableListOf<PaperPositionRecord>()
    val performanceSnapshots = mutableListOf<PaperPerformanceSnapshot>()

    override suspend fun recordSignal(signal: PaperSignalRecord): Long {
        val id = signals.size + 1L
        signals += signal.copy(id = id)
        return id
    }

    override suspend fun recordOrder(order: PaperOrderRecord): Long {
        val id = orders.size + 1L
        orders += order.copy(id = id)
        return id
    }

    override suspend fun recordFill(fill: PaperFillRecord): Long {
        val id = fills.size + 1L
        fills += fill.copy(id = id)
        return id
    }

    override suspend fun recordPosition(position: PaperPositionRecord): Long {
        val id = positions.size + 1L
        positions += position.copy(id = id)
        return id
    }

    override suspend fun recordPerformanceSnapshot(snapshot: PaperPerformanceSnapshot): Long {
        val id = performanceSnapshots.size + 1L
        performanceSnapshots += snapshot.copy(id = id)
        return id
    }

    override suspend fun latestPerformanceSummary(): PaperPerformanceSnapshot? = performanceSnapshots.lastOrNull()

    override suspend fun recentSignals(limit: Int): List<PaperSignalRecord> = signals.asReversed().take(limit)

    override suspend fun recentTrades(limit: Int): List<PaperTradeRecord> =
        orders
            .asReversed()
            .take(limit)
            .map { order ->
                val fill = fills.firstOrNull { it.orderId == order.id }
                PaperTradeRecord(
                    orderId = order.id,
                    clientOrderId = order.clientOrderId,
                    signalId = order.signalId,
                    side = order.side,
                    orderType = order.orderType,
                    orderStatus = order.orderStatus,
                    intendedRisk = order.intendedRisk,
                    orderCreatedAt = order.createdAt,
                    fillId = fill?.id,
                    fillPrice = fill?.fillPrice,
                    quantity = fill?.quantity,
                    fee = fill?.fee,
                    filledAt = fill?.filledAt,
                )
            }
}

private class NoTradeApiStrategy : TradingStrategy {
    override val name: String = "no-trade-api-test"
    override val warmupCandles: Int = 2

    override fun evaluate(candles: List<Candle>): StrategyDecision = StrategyDecision.noTrade("TEST")
}

private class AlwaysBuyApiStrategy : TradingStrategy {
    override val name: String = "always-buy-api-test"
    override val warmupCandles: Int = 2

    override fun evaluate(candles: List<Candle>): StrategyDecision {
        val latest = candles.last()
        return StrategyDecision(
            intent =
                SignalIntent(
                    symbol = latest.symbol,
                    side = Side.BUY,
                    strategy = name,
                    score = SignalScore(80, listOf("TEST")),
                    invalidationPrice = Price(latest.close - BigDecimal("5")),
                    expectedR = BigDecimal("1.5"),
                ),
            reasonCodes = listOf("TEST"),
        )
    }
}

private fun backtestCandles(): List<Candle> =
    (0 until 40).map { index ->
        val close = 100 + index
        Candle(
            symbol = Symbol("BTCUSDT"),
            timeframe = Timeframe.M15,
            openedAt = Instant.parse("2026-06-30T00:00:00Z").plusSeconds(index * 900L),
            open = BigDecimal(close),
            high = BigDecimal(close + 10),
            low = BigDecimal(close - 1),
            close = BigDecimal(close),
            volume = BigDecimal("10"),
        )
    }

private fun sweepApiCandles(): List<Candle> =
    (0 until 90).map { index ->
        val close = 100 + ((index % 20) - 10)
        Candle(
            symbol = Symbol("BTCUSDT"),
            timeframe = Timeframe.M15,
            openedAt = Instant.parse("2026-06-30T00:00:00Z").plusSeconds(index * 900L),
            open = BigDecimal(close),
            high = BigDecimal(close + 3),
            low = BigDecimal(close - 3),
            close = BigDecimal(close),
            volume = BigDecimal("10"),
        )
    }

private fun volumeFlowApiCandles(): List<Candle> = volumeFlowM1Candles() + volumeFlowM5Candles() + volumeFlowM15Candles()

private fun volumeFlowSweepApiCandles(): List<Candle> =
    volumeFlowSweepM1Candles() + volumeFlowSweepM5Candles() + volumeFlowSweepM15Candles()

private fun volumeFlowM1Candles(): List<Candle> =
    (0 until 80).map { index ->
        when (index) {
            65 ->
                apiCandle(
                    index = index,
                    timeframe = Timeframe.M1,
                    seconds = 60L,
                    open = "112.0",
                    high = "113.0",
                    low = "111.8",
                    close = "112.9",
                    volume = "30",
                )
            66 ->
                apiCandle(
                    index = index,
                    timeframe = Timeframe.M1,
                    seconds = 60L,
                    open = "112.9",
                    high = "118.0",
                    low = "112.8",
                    close = "117.5",
                    volume = "40",
                )
            else ->
                apiCandle(
                    index = index,
                    timeframe = Timeframe.M1,
                    seconds = 60L,
                    open = "105",
                    high = "106",
                    low = "104",
                    close = "105",
                    volume = "10",
                )
        }
    }

private fun volumeFlowSweepM1Candles(): List<Candle> =
    (0 until 160).map { index ->
        when (index) {
            65, 125 ->
                apiCandle(
                    index = index,
                    timeframe = Timeframe.M1,
                    seconds = 60L,
                    open = "112.0",
                    high = "113.0",
                    low = "111.8",
                    close = "112.9",
                    volume = "30",
                )
            66, 126 ->
                apiCandle(
                    index = index,
                    timeframe = Timeframe.M1,
                    seconds = 60L,
                    open = "112.9",
                    high = "118.0",
                    low = "112.8",
                    close = "117.5",
                    volume = "40",
                )
            else ->
                apiCandle(
                    index = index,
                    timeframe = Timeframe.M1,
                    seconds = 60L,
                    open = "105",
                    high = "106",
                    low = "104",
                    close = "105",
                    volume = "10",
                )
        }
    }

private fun volumeFlowM5Candles(): List<Candle> =
    (0 until 30).map { index ->
        if (index == 12) {
            apiCandle(
                index = index,
                timeframe = Timeframe.M5,
                seconds = 300L,
                open = "105",
                high = "112",
                low = "104",
                close = "111.5",
                volume = "60",
            )
        } else {
            val close = 100 + index.coerceAtMost(10)
            val volume =
                when (index % 3) {
                    0 -> "8"
                    1 -> "10"
                    else -> "12"
                }
            apiCandle(
                index = index,
                timeframe = Timeframe.M5,
                seconds = 300L,
                open = close.toString(),
                high = (close + 1).toString(),
                low = (close - 1).toString(),
                close = close.toString(),
                volume = volume,
            )
        }
    }

private fun volumeFlowSweepM5Candles(): List<Candle> =
    (0 until 80).map { index ->
        if (index == 12 || index == 24) {
            apiCandle(
                index = index,
                timeframe = Timeframe.M5,
                seconds = 300L,
                open = "105",
                high = "112",
                low = "104",
                close = "111.5",
                volume = "60",
            )
        } else {
            val close = 100 + index.coerceAtMost(10)
            val volume =
                when (index % 3) {
                    0 -> "8"
                    1 -> "10"
                    else -> "12"
                }
            apiCandle(
                index = index,
                timeframe = Timeframe.M5,
                seconds = 300L,
                open = close.toString(),
                high = (close + 1).toString(),
                low = (close - 1).toString(),
                close = close.toString(),
                volume = volume,
            )
        }
    }

private fun volumeFlowM15Candles(): List<Candle> =
    (0 until 30).map { index ->
        val close = 100 + index.coerceAtMost(5)
        apiCandle(
            index = index,
            timeframe = Timeframe.M15,
            seconds = 900L,
            open = close.toString(),
            high = (close + 2).toString(),
            low = (close - 2).toString(),
            close = close.toString(),
            volume = "20",
        )
    }

private fun volumeFlowSweepM15Candles(): List<Candle> =
    (0 until 80).map { index ->
        val close = 100 + index.coerceAtMost(5)
        apiCandle(
            index = index,
            timeframe = Timeframe.M15,
            seconds = 900L,
            open = close.toString(),
            high = (close + 2).toString(),
            low = (close - 2).toString(),
            close = close.toString(),
            volume = "20",
        )
    }

private fun apiCandle(
    index: Int,
    timeframe: Timeframe,
    seconds: Long,
    open: String,
    high: String,
    low: String,
    close: String,
    volume: String,
): Candle =
    Candle(
        symbol = Symbol("BTCUSDT"),
        timeframe = timeframe,
        openedAt = Instant.parse("2026-06-30T00:00:00Z").plusSeconds(index * seconds),
        open = BigDecimal(open),
        high = BigDecimal(high),
        low = BigDecimal(low),
        close = BigDecimal(close),
        volume = BigDecimal(volume),
    )
