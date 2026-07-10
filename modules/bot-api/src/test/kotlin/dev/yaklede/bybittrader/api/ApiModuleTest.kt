package dev.yaklede.bybittrader.api

import dev.yaklede.bybittrader.api.backtest.VolumeFlowBacktestRequest
import dev.yaklede.bybittrader.api.backtest.VolumeFlowCompositeBacktestRequest
import dev.yaklede.bybittrader.api.backtest.VolumeFlowCompositeCurrentConfigProvider
import dev.yaklede.bybittrader.api.backtest.VolumeFlowCompositeLegRequest
import dev.yaklede.bybittrader.api.strategy.StrategyProfileService
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
import dev.yaklede.bybittrader.engine.backtest.TakerFlowDirectionMode
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowBacktestService
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowCompositeBacktestService
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowSweepService
import dev.yaklede.bybittrader.engine.control.BotControlService
import dev.yaklede.bybittrader.engine.control.BotRuntimeStatus
import dev.yaklede.bybittrader.engine.control.BotStateStore
import dev.yaklede.bybittrader.engine.control.ControlEvent
import dev.yaklede.bybittrader.engine.control.ControlEventRecorder
import dev.yaklede.bybittrader.engine.control.ControlResult
import dev.yaklede.bybittrader.engine.execution.ExchangeAccountBalance
import dev.yaklede.bybittrader.engine.execution.ExchangeCancelRequest
import dev.yaklede.bybittrader.engine.execution.ExchangeCancelResult
import dev.yaklede.bybittrader.engine.execution.ExchangeClosedPnl
import dev.yaklede.bybittrader.engine.execution.ExchangeCoinBalance
import dev.yaklede.bybittrader.engine.execution.ExchangeEvaluationStatus
import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionConfig
import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionException
import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionFill
import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionGateway
import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionService
import dev.yaklede.bybittrader.engine.execution.ExchangeOpenOrder
import dev.yaklede.bybittrader.engine.execution.ExchangeOrderRequest
import dev.yaklede.bybittrader.engine.execution.ExchangeOrderResult
import dev.yaklede.bybittrader.engine.execution.ExchangePosition
import dev.yaklede.bybittrader.engine.execution.ExecutionProjectionStore
import dev.yaklede.bybittrader.engine.execution.ExecutionRuntimeMode
import dev.yaklede.bybittrader.engine.execution.ExecutionTradeClosure
import dev.yaklede.bybittrader.engine.execution.LivePerformanceSnapshot
import dev.yaklede.bybittrader.engine.execution.LivePerformanceWindow
import dev.yaklede.bybittrader.engine.execution.PendingExecutionClosureAlert
import dev.yaklede.bybittrader.engine.market.MarketCandleStore
import dev.yaklede.bybittrader.engine.market.MarketDataException
import dev.yaklede.bybittrader.engine.market.MarketDataFeed
import dev.yaklede.bybittrader.engine.market.MarketDataSyncService
import dev.yaklede.bybittrader.engine.market.MarketTicker
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
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ApiModuleTest :
    StringSpec({
        "maps volume flow filter request fields into backtest configs" {
            val singleConfig =
                VolumeFlowBacktestRequest(
                    symbol = "BTCUSDT",
                    minDirectionalTakerImbalance = 0.55,
                    takerFlowDirectionMode = "OPPOSE_SIDE",
                    minOpenInterestChangePct = 2.5,
                    openInterestLookbackSnapshots = 4,
                    maxAbsPremiumIndex = 0.01,
                    maxAbsFundingRate = 0.02,
                    maxFlowDataStalenessMinutes = 15,
                    maxFundingDataStalenessMinutes = 720,
                ).validated().toConfig()
            val legConfig =
                VolumeFlowCompositeLegRequest(
                    id = "primary",
                    minDirectionalTakerImbalance = 0.55,
                    takerFlowDirectionMode = "OPPOSE_SIDE",
                    minOpenInterestChangePct = 2.5,
                    openInterestLookbackSnapshots = 4,
                    maxAbsPremiumIndex = 0.01,
                    maxAbsFundingRate = 0.02,
                    maxFlowDataStalenessMinutes = 15,
                    maxFundingDataStalenessMinutes = 720,
                ).toLeg(initialEquity = 10_000.0).config
            val defaultSingleConfig = VolumeFlowBacktestRequest(symbol = "BTCUSDT").validated().toConfig()
            val defaultLegConfig = VolumeFlowCompositeLegRequest(id = "default").toLeg(initialEquity = 10_000.0).config

            singleConfig.takerFlowDirectionMode shouldBe TakerFlowDirectionMode.OPPOSE_SIDE
            singleConfig.minDirectionalTakerImbalance shouldBe 0.55
            singleConfig.minOpenInterestChangePct shouldBe 2.5
            singleConfig.openInterestLookbackSnapshots shouldBe 4
            singleConfig.maxAbsPremiumIndex shouldBe 0.01
            singleConfig.maxAbsFundingRate shouldBe 0.02
            singleConfig.maxFlowDataStalenessMinutes shouldBe 15
            singleConfig.maxFundingDataStalenessMinutes shouldBe 720
            legConfig.takerFlowDirectionMode shouldBe TakerFlowDirectionMode.OPPOSE_SIDE
            legConfig.minDirectionalTakerImbalance shouldBe 0.55
            legConfig.minOpenInterestChangePct shouldBe 2.5
            legConfig.maxFundingDataStalenessMinutes shouldBe 720
            defaultSingleConfig.maxFundingDataStalenessMinutes shouldBe 480
            defaultLegConfig.maxFundingDataStalenessMinutes shouldBe 480
        }

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
                val controlResults = mutableListOf<ControlResult>()
                application {
                    configureApi(
                        stateStore = stateStore,
                        controlService = BotControlService(stateStore, InMemoryControlEventRecorder()),
                        marketDataSyncService = testMarketDataSyncService(),
                        backtestService = testBacktestService(),
                        meanReversionSweepService = testMeanReversionSweepService(),
                        volumeFlowBacktestService = testVolumeFlowBacktestService(),
                        onControlResult = { controlResults += it },
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
                controlResults.single().newMode shouldBe BotMode.PAUSE_ALL
            }
        }

        "authorized strategy profile request returns aggressive default" {
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
                        strategyProfileService =
                            StrategyProfileService(
                                Files
                                    .createTempDirectory("strategy-profile-test")
                                    .resolve("active-profile.txt"),
                            ),
                        controlCredential = "test-control-credential",
                    )
                }

                val response =
                    client
                        .get("/strategy/profiles") {
                            bearerAuth("test-control-credential")
                        }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain """"activeProfileId":"volume-flow-aggressive""""
                response.bodyAsText() shouldContain """"name":"공격형""""
                response.bodyAsText() shouldContain """"validationStatus":"UNVERIFIED""""
                response.bodyAsText() shouldContain """"liveExpansionAllowed":false"""
            }
        }

        "authorized strategy profile switch persists selected backtest profile" {
            testApplication {
                val stateStore = InMemoryStateStore()
                val statePath =
                    Files
                        .createTempDirectory("strategy-profile-switch-test")
                        .resolve("active-profile.txt")
                application {
                    configureApi(
                        stateStore = stateStore,
                        controlService = BotControlService(stateStore, InMemoryControlEventRecorder()),
                        marketDataSyncService = testMarketDataSyncService(),
                        backtestService = testBacktestService(),
                        meanReversionSweepService = testMeanReversionSweepService(),
                        volumeFlowBacktestService = testVolumeFlowBacktestService(),
                        strategyProfileService = StrategyProfileService(statePath),
                        controlCredential = "test-control-credential",
                    )
                }

                val response =
                    client
                        .post("/strategy/profiles/active") {
                            bearerAuth("test-control-credential")
                            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                            setBody("""{"profileId":"volume-flow-composite-current"}""")
                        }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain """"activeProfileId":"volume-flow-composite-current""""
                response.bodyAsText() shouldContain """"runtimeProfileId":"volume-flow-aggressive""""
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

        "authorized market ticker request returns latest market snapshot" {
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

                val response =
                    client
                        .get("/market-data/ticker?symbol=BTCUSDT") {
                            bearerAuth("test-control-credential")
                        }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain """"lastPrice":"61234.5""""
                response.bodyAsText() shouldContain """"price24hPcnt":"0.0123""""
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
                                  "replayStartAt":"2026-06-30T00:00:00Z",
                                  "replayEndAt":"2026-06-30T07:15:00Z",
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
                body shouldContain """"requestedCoverage":[{"""
                body shouldContain """"requestedLimit":80"""
                body shouldContain """"requestedStartAt":"2026-06-30T00:00:00Z""""
                body shouldContain """"effectiveCoverage":[{"""
                body shouldContain """"actualCount":80"""
                body shouldContain """"commonReplayWindow":{"startAt":"2026-06-30T00:00:00Z","endAt":"2026-06-30T01:19:00Z"}"""
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
                                  "replayStartAt":"2026-06-30T00:00:00Z",
                                  "replayEndAt":"2026-06-30T07:15:00Z",
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
                body shouldContain """"requestedLimit":1600001"""
                body shouldContain """"requestedEndAt":"2026-06-30T07:15:00Z""""
                body shouldContain """"effectiveCoverage":[{"""
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

        "authorized execution evaluate request submits a private exchange order" {
            testApplication {
                val stateStore = InMemoryStateStore()
                val paperStore = InMemoryPaperTradingStore()
                val gateway = RecordingExecutionGateway()
                application {
                    configureApi(
                        stateStore = stateStore,
                        controlService = BotControlService(stateStore, InMemoryControlEventRecorder()),
                        marketDataSyncService = testMarketDataSyncService(),
                        backtestService = testBacktestService(),
                        meanReversionSweepService = testMeanReversionSweepService(),
                        volumeFlowBacktestService = testVolumeFlowBacktestService(),
                        executionService =
                            ExchangeExecutionService(
                                stateStore = stateStore,
                                candleStore = InMemoryMarketCandleStore(backtestCandles()),
                                tradingStore = paperStore,
                                strategy = AlwaysBuyApiStrategy(),
                                gateway = gateway,
                                config = ExchangeExecutionConfig(enabled = true),
                                clock = Clock.fixed(Instant.parse("2026-06-30T10:15:00Z"), ZoneOffset.UTC),
                            ),
                        controlCredential = "test-control-credential",
                    )
                }

                val response =
                    client
                        .post("/execution/evaluate-and-submit") {
                            bearerAuth("test-control-credential")
                            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                            setBody("""{"symbol":"BTCUSDT","timeframe":"M15","candleLimit":30}""")
                        }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain """"status":"${ExchangeEvaluationStatus.SUBMITTED.name}""""
                gateway.placedOrders.size shouldBe 1
                paperStore.orders.single().exchangeOrderId shouldBe "exchange-1"
            }
        }

        "manual market order requires runtime acknowledgement and submits order" {
            testApplication {
                val stateStore = InMemoryStateStore()
                val paperStore = InMemoryPaperTradingStore()
                val gateway = RecordingExecutionGateway()
                application {
                    configureApi(
                        stateStore = stateStore,
                        controlService = BotControlService(stateStore, InMemoryControlEventRecorder()),
                        marketDataSyncService = testMarketDataSyncService(),
                        backtestService = testBacktestService(),
                        meanReversionSweepService = testMeanReversionSweepService(),
                        volumeFlowBacktestService = testVolumeFlowBacktestService(),
                        executionService =
                            ExchangeExecutionService(
                                stateStore = stateStore,
                                candleStore = InMemoryMarketCandleStore(backtestCandles()),
                                tradingStore = paperStore,
                                strategy = NoTradeApiStrategy(),
                                gateway = gateway,
                                config = ExchangeExecutionConfig(enabled = true),
                                clock = Clock.fixed(Instant.parse("2026-06-30T00:10:00Z"), ZoneOffset.UTC),
                            ),
                        runtimeMode = "LIVE",
                        controlCredential = "test-control-credential",
                    )
                }

                val rejected =
                    client
                        .post("/execution/manual/market-order") {
                            bearerAuth("test-control-credential")
                            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                            setBody("""{"symbol":"BTCUSDT","side":"SELL","quantity":"0.001","acknowledgement":"TESTNET_MARKET_ORDER"}""")
                        }
                rejected.status shouldBe HttpStatusCode.BadRequest

                val accepted =
                    client
                        .post("/execution/manual/market-order") {
                            bearerAuth("test-control-credential")
                            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                            setBody("""{"symbol":"BTCUSDT","side":"SELL","quantity":"0.001","acknowledgement":"LIVE_MARKET_ORDER"}""")
                        }

                accepted.status shouldBe HttpStatusCode.OK
                accepted.bodyAsText() shouldContain """"reduceOnly":false"""
                gateway.placedOrders.size shouldBe 1
                gateway.placedOrders.single().side shouldBe Side.SELL
                gateway.placedOrders.single().reduceOnly shouldBe false
                paperStore.orders.single().clientOrderId shouldContain "manual-BTCUSDT"
            }
        }

        "manual close position sends reduce only opposite side order" {
            testApplication {
                val stateStore = InMemoryStateStore()
                val paperStore = InMemoryPaperTradingStore()
                val gateway = RecordingExecutionGateway()
                application {
                    configureApi(
                        stateStore = stateStore,
                        controlService = BotControlService(stateStore, InMemoryControlEventRecorder()),
                        marketDataSyncService = testMarketDataSyncService(),
                        backtestService = testBacktestService(),
                        meanReversionSweepService = testMeanReversionSweepService(),
                        volumeFlowBacktestService = testVolumeFlowBacktestService(),
                        executionService =
                            ExchangeExecutionService(
                                stateStore = stateStore,
                                candleStore = InMemoryMarketCandleStore(backtestCandles()),
                                tradingStore = paperStore,
                                strategy = NoTradeApiStrategy(),
                                gateway = gateway,
                                config = ExchangeExecutionConfig(enabled = true),
                                clock = Clock.fixed(Instant.parse("2026-06-30T00:10:00Z"), ZoneOffset.UTC),
                            ),
                        runtimeMode = "TESTNET",
                        controlCredential = "test-control-credential",
                    )
                }

                val response =
                    client
                        .post("/execution/manual/close-position") {
                            bearerAuth("test-control-credential")
                            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                            setBody(
                                """{"symbol":"BTCUSDT","positionSide":"BUY","quantity":"0.002","acknowledgement":"TESTNET_CLOSE_POSITION"}""",
                            )
                        }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain """"reduceOnly":true"""
                gateway.placedOrders.size shouldBe 1
                gateway.placedOrders.single().side shouldBe Side.SELL
                gateway.placedOrders.single().reduceOnly shouldBe true
                paperStore.orders.single().clientOrderId shouldContain "close-BTCUSDT"
            }
        }

        "execution cancel endpoint cancels an open order by id" {
            testApplication {
                val stateStore = InMemoryStateStore()
                val gateway = RecordingExecutionGateway()
                application {
                    configureApi(
                        stateStore = stateStore,
                        controlService = BotControlService(stateStore, InMemoryControlEventRecorder()),
                        marketDataSyncService = testMarketDataSyncService(),
                        backtestService = testBacktestService(),
                        meanReversionSweepService = testMeanReversionSweepService(),
                        volumeFlowBacktestService = testVolumeFlowBacktestService(),
                        executionService =
                            ExchangeExecutionService(
                                stateStore = stateStore,
                                candleStore = InMemoryMarketCandleStore(backtestCandles()),
                                tradingStore = InMemoryPaperTradingStore(),
                                strategy = NoTradeApiStrategy(),
                                gateway = gateway,
                                config = ExchangeExecutionConfig(enabled = true),
                            ),
                        runtimeMode = "LIVE",
                        controlCredential = "test-control-credential",
                    )
                }

                val response =
                    client
                        .post("/execution/orders/cancel") {
                            bearerAuth("test-control-credential")
                            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                            setBody("""{"symbol":"BTCUSDT","exchangeOrderId":"exchange-7"}""")
                        }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain """"exchangeOrderId":"exchange-7""""
                gateway.cancelledOrders.single().exchangeOrderId shouldBe "exchange-7"
            }
        }

        "closed trade performance and mobile summary endpoints return persisted projections" {
            testApplication {
                val stateStore = InMemoryStateStore()
                val tradingStore = InMemoryPaperTradingStore()
                tradingStore.recordTradeClosure(sampleClosure())
                tradingStore.recordLivePerformanceSnapshot(samplePerformance())
                application {
                    configureApi(
                        stateStore = stateStore,
                        controlService = BotControlService(stateStore, InMemoryControlEventRecorder()),
                        marketDataSyncService = testMarketDataSyncService(),
                        backtestService = testBacktestService(),
                        meanReversionSweepService = testMeanReversionSweepService(),
                        volumeFlowBacktestService = testVolumeFlowBacktestService(),
                        executionService =
                            ExchangeExecutionService(
                                stateStore = stateStore,
                                candleStore = InMemoryMarketCandleStore(backtestCandles()),
                                tradingStore = tradingStore,
                                strategy = NoTradeApiStrategy(),
                                gateway = RecordingExecutionGateway(),
                                config = ExchangeExecutionConfig(enabled = true),
                                runtimeMode = ExecutionRuntimeMode.LIVE,
                            ),
                        runtimeMode = "LIVE",
                        controlCredential = "test-control-credential",
                    )
                }

                client
                    .get("/execution/closed-trades?symbol=BTCUSDT&limit=5") { bearerAuth("test-control-credential") }
                    .bodyAsText() shouldContain """"netPnl":"5""""
                client
                    .get("/performance/live/summary?window=all") { bearerAuth("test-control-credential") }
                    .bodyAsText() shouldContain """"tradeCount":1"""
                client
                    .get("/dashboard/mobile-summary?symbol=BTCUSDT&tradeLimit=5&signalLimit=5") {
                        bearerAuth("test-control-credential")
                    }.bodyAsText() shouldContain """"recentClosedTrades":[{"""
            }
        }

        "reconcile and dashboard reads do not pre-persist exchange closures" {
            testApplication {
                val stateStore = InMemoryStateStore()
                val tradingStore = InMemoryPaperTradingStore()
                val gateway = RecordingExecutionGateway(closedPnls = listOf(sampleClosedPnl()))
                application {
                    configureApi(
                        stateStore = stateStore,
                        controlService = BotControlService(stateStore, InMemoryControlEventRecorder()),
                        marketDataSyncService = testMarketDataSyncService(),
                        backtestService = testBacktestService(),
                        meanReversionSweepService = testMeanReversionSweepService(),
                        volumeFlowBacktestService = testVolumeFlowBacktestService(),
                        executionService =
                            ExchangeExecutionService(
                                stateStore = stateStore,
                                candleStore = InMemoryMarketCandleStore(backtestCandles()),
                                tradingStore = tradingStore,
                                strategy = NoTradeApiStrategy(),
                                gateway = gateway,
                                config = ExchangeExecutionConfig(enabled = true),
                                runtimeMode = ExecutionRuntimeMode.LIVE,
                            ),
                        runtimeMode = "LIVE",
                        controlCredential = "test-control-credential",
                    )
                }

                client
                    .post("/execution/reconcile") {
                        bearerAuth("test-control-credential")
                        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                        setBody("""{"symbol":"BTCUSDT"}""")
                    }.status shouldBe HttpStatusCode.OK
                client
                    .get("/dashboard/summary?symbol=BTCUSDT&coin=USDT&limit=5") {
                        bearerAuth("test-control-credential")
                    }.status shouldBe HttpStatusCode.OK

                tradingStore.closures shouldBe emptyList()
            }
        }

        "authorized dashboard summary returns bot state account and reconciliation snapshot" {
            testApplication {
                val stateStore = InMemoryStateStore()
                val gateway =
                    RecordingExecutionGateway(
                        positions =
                            listOf(
                                ExchangePosition(
                                    symbol = Symbol("BTCUSDT"),
                                    side = Side.BUY,
                                    size = BigDecimal("0.01"),
                                    entryPrice = BigDecimal("60000"),
                                    markPrice = BigDecimal("61000"),
                                    unrealizedPnl = BigDecimal("10"),
                                    updatedAt = Instant.parse("2026-06-30T00:00:00Z"),
                                ),
                            ),
                    )
                application {
                    configureApi(
                        stateStore = stateStore,
                        controlService = BotControlService(stateStore, InMemoryControlEventRecorder()),
                        marketDataSyncService = testMarketDataSyncService(),
                        backtestService = testBacktestService(),
                        meanReversionSweepService = testMeanReversionSweepService(),
                        volumeFlowBacktestService = testVolumeFlowBacktestService(),
                        executionService =
                            ExchangeExecutionService(
                                stateStore = stateStore,
                                candleStore = InMemoryMarketCandleStore(backtestCandles()),
                                tradingStore = InMemoryPaperTradingStore(),
                                strategy = NoTradeApiStrategy(),
                                gateway = gateway,
                                config = ExchangeExecutionConfig(enabled = true),
                            ),
                        controlCredential = "test-control-credential",
                    )
                }

                val response =
                    client
                        .get("/dashboard/summary?symbol=BTCUSDT&coin=USDT&limit=5") {
                            bearerAuth("test-control-credential")
                        }

                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldContain """"executionAvailable":true"""
                body shouldContain """"mode":"RUNNING""""
                body shouldContain """"market":{"symbol":"BTCUSDT","lastPrice":"61234.5""""
                body shouldContain """"totalEquity":"1200.5""""
                body shouldContain """"positions":[{"""
                body shouldContain """"unrealizedPnl":"10""""
            }
        }

        "operations smoke discord endpoint sends configured alert sink" {
            testApplication {
                val stateStore = InMemoryStateStore()
                val sentMessages = mutableListOf<String>()
                application {
                    configureApi(
                        stateStore = stateStore,
                        controlService = BotControlService(stateStore, InMemoryControlEventRecorder()),
                        marketDataSyncService = testMarketDataSyncService(),
                        backtestService = testBacktestService(),
                        meanReversionSweepService = testMeanReversionSweepService(),
                        volumeFlowBacktestService = testVolumeFlowBacktestService(),
                        onSmokeAlert = { message ->
                            sentMessages += message
                            dev.yaklede.bybittrader.api.operations.SmokeAlertDeliveryResponse(
                                delivered = true,
                                sinkName = "discord",
                                failureReason = null,
                            )
                        },
                        controlCredential = "test-control-credential",
                    )
                }

                val response =
                    client
                        .post("/ops/smoke/discord") {
                            bearerAuth("test-control-credential")
                            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                            setBody("""{"message":"smoke"}""")
                        }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain """"sinkName":"discord""""
                sentMessages shouldBe listOf("smoke")
            }
        }

        "operations smoke exchange read checks ticker balance and reconciliation" {
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
                        executionService =
                            ExchangeExecutionService(
                                stateStore = stateStore,
                                candleStore = InMemoryMarketCandleStore(backtestCandles()),
                                tradingStore = InMemoryPaperTradingStore(),
                                strategy = NoTradeApiStrategy(),
                                gateway = RecordingExecutionGateway(),
                                config = ExchangeExecutionConfig(enabled = true),
                            ),
                        runtimeMode = "TESTNET",
                        controlCredential = "test-control-credential",
                    )
                }

                val response =
                    client
                        .post("/ops/smoke/exchange-read") {
                            bearerAuth("test-control-credential")
                            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                            setBody("""{"symbol":"BTCUSDT","coin":"USDT"}""")
                        }

                response.status shouldBe HttpStatusCode.OK
                val body = response.bodyAsText()
                body shouldContain """"status":"PASS""""
                body shouldContain """"lastPrice":"61234.5""""
                body shouldContain """"coinCount":1"""
            }
        }

        "operations smoke control cycle pauses and resumes bot" {
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
                        runtimeMode = "TESTNET",
                        controlCredential = "test-control-credential",
                    )
                }

                val response =
                    client
                        .post("/ops/smoke/control-cycle") {
                            bearerAuth("test-control-credential")
                            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                            setBody("""{"reason":"smoke"}""")
                        }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldContain """"resumeMode":"RESUME_PENDING_CHECK""""
                stateStore.current().mode shouldBe BotMode.RESUME_PENDING_CHECK
            }
        }

        "operations smoke control actions can be verified separately" {
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
                        runtimeMode = "TESTNET",
                        controlCredential = "test-control-credential",
                    )
                }

                val pause =
                    client
                        .post("/ops/smoke/control-pause") {
                            bearerAuth("test-control-credential")
                            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                            setBody("""{"reason":"pause smoke"}""")
                        }
                pause.status shouldBe HttpStatusCode.OK
                pause.bodyAsText() shouldContain """"newMode":"PAUSE_ALL""""
                stateStore.current().mode shouldBe BotMode.PAUSE_ALL

                val resume =
                    client
                        .post("/ops/smoke/control-resume") {
                            bearerAuth("test-control-credential")
                            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                            setBody("""{"reason":"resume smoke"}""")
                        }
                resume.status shouldBe HttpStatusCode.OK
                resume.bodyAsText() shouldContain """"newMode":"RESUME_PENDING_CHECK""""
                stateStore.current().mode shouldBe BotMode.RESUME_PENDING_CHECK
            }
        }

        "operations smoke testnet market order requires acknowledgement and submits order" {
            testApplication {
                val stateStore = InMemoryStateStore()
                val paperStore = InMemoryPaperTradingStore()
                val gateway = RecordingExecutionGateway()
                application {
                    configureApi(
                        stateStore = stateStore,
                        controlService = BotControlService(stateStore, InMemoryControlEventRecorder()),
                        marketDataSyncService = testMarketDataSyncService(),
                        backtestService = testBacktestService(),
                        meanReversionSweepService = testMeanReversionSweepService(),
                        volumeFlowBacktestService = testVolumeFlowBacktestService(),
                        executionService =
                            ExchangeExecutionService(
                                stateStore = stateStore,
                                candleStore = InMemoryMarketCandleStore(backtestCandles()),
                                tradingStore = paperStore,
                                strategy = NoTradeApiStrategy(),
                                gateway = gateway,
                                config = ExchangeExecutionConfig(enabled = true),
                                clock = Clock.fixed(Instant.parse("2026-06-30T00:10:00Z"), ZoneOffset.UTC),
                            ),
                        runtimeMode = "TESTNET",
                        controlCredential = "test-control-credential",
                    )
                }

                val rejected =
                    client
                        .post("/ops/smoke/testnet-market-order") {
                            bearerAuth("test-control-credential")
                            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                            setBody("""{"symbol":"BTCUSDT","side":"BUY","quantity":"0.001","acknowledgement":"NO"}""")
                        }
                rejected.status shouldBe HttpStatusCode.BadRequest

                val accepted =
                    client
                        .post("/ops/smoke/testnet-market-order") {
                            bearerAuth("test-control-credential")
                            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                            setBody(
                                """
                                {
                                  "symbol":"BTCUSDT",
                                  "side":"BUY",
                                  "quantity":"0.001",
                                  "acknowledgement":"TESTNET_MARKET_ORDER"
                                }
                                """.trimIndent(),
                            )
                        }

                accepted.status shouldBe HttpStatusCode.OK
                accepted.bodyAsText() shouldContain """"status":"SUBMITTED""""
                gateway.placedOrders.size shouldBe 1
                paperStore.orders.single().clientOrderId shouldContain "smoke-BTCUSDT"
            }
        }

        "execution provider errors return sanitized bad gateway response" {
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
                        executionService =
                            ExchangeExecutionService(
                                stateStore = stateStore,
                                candleStore = InMemoryMarketCandleStore(backtestCandles()),
                                tradingStore = InMemoryPaperTradingStore(),
                                strategy = AlwaysBuyApiStrategy(),
                                gateway = FailingExecutionGateway(),
                                config = ExchangeExecutionConfig(enabled = true),
                            ),
                        controlCredential = "test-control-credential",
                    )
                }

                val response =
                    client
                        .post("/execution/reconcile") {
                            bearerAuth("test-control-credential")
                            header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                            setBody("""{"symbol":"BTCUSDT"}""")
                        }

                response.status shouldBe HttpStatusCode.BadGateway
                val body = response.bodyAsText()
                body shouldContain "EXCHANGE_EXECUTION_UNAVAILABLE"
                body shouldContain """"providerCode":"10001""""
                body shouldContain "[redacted] invalid"
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

    override suspend fun fetchTicker(symbol: Symbol): MarketTicker =
        MarketTicker(
            symbol = symbol,
            lastPrice = BigDecimal("61234.5"),
            markPrice = BigDecimal("61230.1"),
            indexPrice = BigDecimal("61220.2"),
            price24hPcnt = BigDecimal("0.0123"),
            fundingRate = BigDecimal("0.0001"),
            nextFundingTime = Instant.parse("2026-06-30T08:00:00Z"),
            capturedAt = Instant.parse("2026-06-30T00:00:00Z"),
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

    override suspend fun candlesBetween(
        symbol: Symbol,
        timeframe: Timeframe,
        startAt: Instant,
        endAt: Instant,
        limit: Int,
    ): List<Candle> =
        existingCandles
            .filter { candle ->
                candle.symbol == symbol &&
                    candle.timeframe == timeframe &&
                    !candle.openedAt.isBefore(startAt) &&
                    !candle.openedAt.isAfter(endAt)
            }.sortedBy { it.openedAt }
            .take(limit)

    override suspend fun candlesBefore(
        symbol: Symbol,
        timeframe: Timeframe,
        beforeAt: Instant,
        limit: Int,
    ): List<Candle> =
        existingCandles
            .filter { candle ->
                candle.symbol == symbol && candle.timeframe == timeframe && candle.openedAt.isBefore(beforeAt)
            }.sortedByDescending { it.openedAt }
            .take(limit)
}

private class FailingMarketDataFeed : MarketDataFeed {
    override suspend fun fetchRecentCandles(
        symbol: Symbol,
        timeframe: Timeframe,
        limit: Int,
    ): List<Candle> = throw MarketDataException("provider failed with raw details")
}

private class InMemoryPaperTradingStore :
    PaperTradingStore,
    ExecutionProjectionStore {
    val signals = mutableListOf<PaperSignalRecord>()
    val orders = mutableListOf<PaperOrderRecord>()
    val fills = mutableListOf<PaperFillRecord>()
    val positions = mutableListOf<PaperPositionRecord>()
    val performanceSnapshots = mutableListOf<PaperPerformanceSnapshot>()
    val closures = mutableListOf<ExecutionTradeClosure>()
    val livePerformanceSnapshots = mutableListOf<LivePerformanceSnapshot>()
    val suppressedClosureAlerts = mutableSetOf<Long>()
    val deliveredClosureAlerts = mutableSetOf<Long>()
    val closureAlertAttempts = mutableMapOf<Long, Int>()

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

    override suspend fun recordTradeClosure(
        closure: ExecutionTradeClosure,
        suppressedAt: Instant?,
    ): Long? {
        if (
            closures.any {
                it.exchangeOrderId == closure.exchangeOrderId &&
                    it.clientOrderId == closure.clientOrderId &&
                    it.closedAt == closure.closedAt
            }
        ) {
            return null
        }
        val id = closures.size + 1L
        closures += closure.copy(id = id)
        if (suppressedAt != null) suppressedClosureAlerts += id
        closureAlertAttempts[id] = 0
        return id
    }

    override suspend fun closedTrades(
        symbol: Symbol?,
        mode: ExecutionRuntimeMode?,
        limit: Int,
        cursor: Long?,
    ): List<ExecutionTradeClosure> {
        val filtered =
            closures.filter {
                (symbol == null || it.symbol == symbol) &&
                    (mode == null || it.mode == mode) &&
                    (cursor == null || it.id < cursor)
            }
        return filtered.sortedByDescending { it.id }.take(limit)
    }

    override suspend fun latestClosedTrade(symbol: Symbol): ExecutionTradeClosure? =
        closures.filter { it.symbol == symbol }.maxByOrNull { it.id }

    override suspend fun performanceClosures(
        mode: ExecutionRuntimeMode,
        closedAtOrAfter: Instant?,
    ): List<ExecutionTradeClosure> =
        closures
            .filter { closure ->
                closure.mode == mode && (closedAtOrAfter == null || !closure.closedAt.isBefore(closedAtOrAfter))
            }.sortedWith(compareBy(ExecutionTradeClosure::closedAt, ExecutionTradeClosure::id))

    override suspend fun hasClosureHistory(
        mode: ExecutionRuntimeMode,
        symbol: Symbol,
    ): Boolean = closures.any { closure -> closure.mode == mode && closure.symbol == symbol }

    override suspend fun pendingClosureAlerts(
        mode: ExecutionRuntimeMode,
        symbol: Symbol,
        limit: Int,
    ): List<PendingExecutionClosureAlert> =
        closures
            .filter { closure ->
                closure.mode == mode &&
                    closure.symbol == symbol &&
                    closure.id !in suppressedClosureAlerts &&
                    closure.id !in deliveredClosureAlerts
            }.sortedWith(compareBy(ExecutionTradeClosure::closedAt, ExecutionTradeClosure::id))
            .take(limit)
            .map { closure ->
                PendingExecutionClosureAlert(
                    closure = closure,
                    attemptCount = closureAlertAttempts[closure.id] ?: 0,
                    lastAttemptAt = null,
                )
            }

    override suspend fun recordClosureAlertAttempt(
        closureId: Long,
        attemptedAt: Instant,
        delivered: Boolean,
    ) {
        closureAlertAttempts[closureId] = (closureAlertAttempts[closureId] ?: 0) + 1
        if (delivered) deliveredClosureAlerts += closureId
    }

    override suspend fun recordLivePerformanceSnapshot(snapshot: LivePerformanceSnapshot): Long {
        val id = livePerformanceSnapshots.size + 1L
        livePerformanceSnapshots += snapshot.copy(id = id)
        return id
    }

    override suspend fun latestLivePerformanceSummary(
        mode: ExecutionRuntimeMode?,
        window: LivePerformanceWindow,
    ): LivePerformanceSnapshot? = livePerformanceSnapshots.lastOrNull { (mode == null || it.mode == mode) && it.window == window }
}

private fun sampleClosure(): ExecutionTradeClosure =
    ExecutionTradeClosure(
        mode = ExecutionRuntimeMode.LIVE,
        symbol = Symbol("BTCUSDT"),
        side = Side.BUY,
        openedAt = Instant.parse("2026-06-30T00:00:00Z"),
        closedAt = Instant.parse("2026-06-30T00:10:00Z"),
        entryPrice = BigDecimal("100"),
        exitPrice = BigDecimal("105"),
        quantity = BigDecimal("1"),
        grossPnl = BigDecimal("5.12"),
        fees = BigDecimal("0.12"),
        netPnl = BigDecimal("5"),
        exitReason = "TAKE_PROFIT",
        exchangeOrderId = "exit-1",
        clientOrderId = "client-1",
    )

private fun samplePerformance(): LivePerformanceSnapshot =
    LivePerformanceSnapshot(
        mode = ExecutionRuntimeMode.LIVE,
        window = LivePerformanceWindow.ALL,
        tradeCount = 1,
        winRatePct = BigDecimal("100"),
        grossProfit = BigDecimal("5"),
        grossLoss = BigDecimal.ZERO,
        fees = BigDecimal("0.12"),
        netPnl = BigDecimal("5"),
        profitFactor = null,
        expectancy = BigDecimal("5"),
        maxClosedTradeDrawdownPct = BigDecimal.ZERO,
        lastClosedAt = Instant.parse("2026-06-30T00:10:00Z"),
        capturedAt = Instant.parse("2026-06-30T00:11:00Z"),
    )

private class RecordingExecutionGateway(
    private val openOrders: List<ExchangeOpenOrder> = emptyList(),
    private val positions: List<ExchangePosition> = emptyList(),
    private val executions: List<ExchangeExecutionFill> = emptyList(),
    private val closedPnls: List<ExchangeClosedPnl> = emptyList(),
    private val accountBalance: ExchangeAccountBalance =
        ExchangeAccountBalance(
            accountType = "UNIFIED",
            totalEquity = BigDecimal("1200.5"),
            totalWalletBalance = BigDecimal("1000"),
            totalMarginBalance = BigDecimal("1100"),
            totalAvailableBalance = BigDecimal("900"),
            totalPerpUnrealizedPnl = BigDecimal("100.5"),
            totalInitialMargin = BigDecimal("50"),
            totalMaintenanceMargin = BigDecimal("20"),
            coins =
                listOf(
                    ExchangeCoinBalance(
                        coin = "USDT",
                        equity = BigDecimal("1200.5"),
                        usdValue = BigDecimal("1200.5"),
                        walletBalance = BigDecimal("1000"),
                        locked = BigDecimal.ZERO,
                        unrealizedPnl = BigDecimal("100.5"),
                    ),
                ),
            capturedAt = Instant.parse("2026-06-30T00:00:00Z"),
        ),
) : ExchangeExecutionGateway {
    val leverageRequests = mutableListOf<Pair<Symbol, BigDecimal>>()
    val placedOrders = mutableListOf<ExchangeOrderRequest>()
    val cancelledOrders = mutableListOf<ExchangeCancelRequest>()

    override suspend fun setLeverage(
        symbol: Symbol,
        leverage: BigDecimal,
    ) {
        leverageRequests += symbol to leverage
    }

    override suspend fun placeOrder(request: ExchangeOrderRequest): ExchangeOrderResult {
        placedOrders += request
        return ExchangeOrderResult(
            exchangeOrderId = "exchange-1",
            clientOrderId = request.clientOrderId,
            status = dev.yaklede.bybittrader.domain.OrderStatus.SUBMITTED,
        )
    }

    override suspend fun cancelOrder(request: ExchangeCancelRequest): ExchangeCancelResult {
        cancelledOrders += request
        return ExchangeCancelResult(
            exchangeOrderId = request.exchangeOrderId,
            clientOrderId = request.clientOrderId,
        )
    }

    override suspend fun openOrders(symbol: Symbol): List<ExchangeOpenOrder> = openOrders

    override suspend fun positions(symbol: Symbol): List<ExchangePosition> = positions

    override suspend fun executions(symbol: Symbol): List<ExchangeExecutionFill> = executions

    override suspend fun closedPnls(symbol: Symbol): List<ExchangeClosedPnl> = closedPnls

    override suspend fun accountBalance(coin: String?): ExchangeAccountBalance = accountBalance
}

private fun sampleClosedPnl(): ExchangeClosedPnl =
    ExchangeClosedPnl(
        exchangeOrderId = "exit-read-only",
        clientOrderId = null,
        symbol = Symbol("BTCUSDT"),
        side = Side.BUY,
        openedAt = Instant.parse("2026-06-30T00:00:00Z"),
        closedAt = Instant.parse("2026-06-30T00:10:00Z"),
        entryPrice = BigDecimal("100"),
        exitPrice = BigDecimal("105"),
        quantity = BigDecimal("1"),
        grossPnl = BigDecimal("5.12"),
        fees = BigDecimal("0.12"),
        netPnl = BigDecimal("5"),
        exitReason = "TAKE_PROFIT",
    )

private class FailingExecutionGateway : ExchangeExecutionGateway {
    override suspend fun setLeverage(
        symbol: Symbol,
        leverage: BigDecimal,
    ): Unit = throw providerFailure()

    override suspend fun placeOrder(request: ExchangeOrderRequest): ExchangeOrderResult = throw providerFailure()

    override suspend fun cancelOrder(request: ExchangeCancelRequest): ExchangeCancelResult = throw providerFailure()

    override suspend fun openOrders(symbol: Symbol): List<ExchangeOpenOrder> = throw providerFailure()

    override suspend fun positions(symbol: Symbol): List<ExchangePosition> = throw providerFailure()

    override suspend fun executions(symbol: Symbol): List<ExchangeExecutionFill> = throw providerFailure()

    override suspend fun accountBalance(coin: String?): ExchangeAccountBalance = throw providerFailure()

    private fun providerFailure(): ExchangeExecutionException =
        ExchangeExecutionException(
            message = "raw exchange detail",
            providerCode = "10001",
            providerMessage = "api secret invalid",
        )
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

private fun volumeFlowApiCandles(): List<Candle> =
    volumeFlowWarmupCandles() + volumeFlowM1Candles() + volumeFlowM5Candles() + volumeFlowM15Candles()

private fun volumeFlowWarmupCandles(): List<Candle> =
    listOf(
        Timeframe.M1 to 60L,
        Timeframe.M5 to 300L,
        Timeframe.M15 to 900L,
    ).flatMap { (timeframe, seconds) ->
        (-12 until 0).map { index ->
            apiCandle(
                index = index,
                timeframe = timeframe,
                seconds = seconds,
                open = "100",
                high = "101",
                low = "99",
                close = "100",
                volume = "10",
            )
        }
    }

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
