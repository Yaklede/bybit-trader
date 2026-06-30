package dev.yaklede.bybittrader.api

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
import dev.yaklede.bybittrader.engine.control.BotControlService
import dev.yaklede.bybittrader.engine.control.BotRuntimeStatus
import dev.yaklede.bybittrader.engine.control.BotStateStore
import dev.yaklede.bybittrader.engine.control.ControlEvent
import dev.yaklede.bybittrader.engine.control.ControlEventRecorder
import dev.yaklede.bybittrader.engine.market.MarketCandleStore
import dev.yaklede.bybittrader.engine.market.MarketDataException
import dev.yaklede.bybittrader.engine.market.MarketDataFeed
import dev.yaklede.bybittrader.engine.market.MarketDataSyncService
import dev.yaklede.bybittrader.strategy.StrategyDecision
import dev.yaklede.bybittrader.strategy.TradingStrategy
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
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
