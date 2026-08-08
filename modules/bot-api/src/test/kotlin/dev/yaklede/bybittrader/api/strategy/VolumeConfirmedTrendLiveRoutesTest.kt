package dev.yaklede.bybittrader.api.strategy

import dev.yaklede.bybittrader.api.security.configureControlAuthentication
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionFill
import dev.yaklede.bybittrader.engine.execution.ExecutionAccountSnapshot
import dev.yaklede.bybittrader.engine.execution.ExecutionFillEvent
import dev.yaklede.bybittrader.engine.execution.ExecutionRuntimeMode
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveEvent
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveEventType
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveState
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveStatus
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.math.BigDecimal
import java.time.Instant

class VolumeConfirmedTrendLiveRoutesTest :
    StringSpec({
        "live status requires operator authentication" {
            testApplication {
                applicationWithLiveProvider { sampleLiveSnapshot() }

                client.get(LIVE_PATH).status shouldBe HttpStatusCode.Unauthorized
            }
        }

        "disabled live status contains no synthetic state" {
            testApplication {
                applicationWithLiveProvider(null)

                val response = client.get(LIVE_PATH) { bearerAuth(CREDENTIAL) }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe
                    """{"enabled":false,"state":null,"recentEvents":[],"account":null,"recentExecutionFills":[]}"""
            }
        }

        "live status exposes persisted order and halt evidence" {
            var requestedLimit = 0
            testApplication {
                applicationWithLiveProvider { limit ->
                    requestedLimit = limit
                    sampleLiveSnapshot()
                }

                val response = client.get("$LIVE_PATH?limit=7") { bearerAuth(CREDENTIAL) }

                response.status shouldBe HttpStatusCode.OK
                requestedLimit shouldBe 7
                response.bodyAsText().also { body ->
                    body shouldContain "\"enabled\":true"
                    body shouldContain "\"status\":\"HALTED\""
                    body shouldContain "\"approvalId\":\"approval-001\""
                    body shouldContain "\"clientOrderId\":\"vcte-order-001\""
                    body shouldContain "\"haltedReasonCode\":\"TREND_POSITION_MISMATCH\""
                    body shouldContain "\"type\":\"HALTED\""
                    body shouldContain "\"reasonCode\":\"TREND_POSITION_MISMATCH\""
                    body shouldContain "\"totalEquity\":\"660.50\""
                    body shouldContain "\"executionId\":\"execution-001\""
                    body shouldContain "\"fee\":\"0.252\""
                }
            }
        }

        "invalid live event limit is rejected" {
            testApplication {
                applicationWithLiveProvider { sampleLiveSnapshot() }

                client.get("$LIVE_PATH?limit=invalid") { bearerAuth(CREDENTIAL) }.status shouldBe
                    HttpStatusCode.BadRequest
                client.get("$LIVE_PATH?limit=101") { bearerAuth(CREDENTIAL) }.status shouldBe HttpStatusCode.BadRequest
            }
        }
    })

private fun io.ktor.server.testing.ApplicationTestBuilder.applicationWithLiveProvider(
    provider: VolumeConfirmedTrendLiveSnapshotProvider?,
) {
    application {
        install(ContentNegotiation) { json() }
        install(StatusPages) {
            exception<IllegalArgumentException> { call, _ -> call.respond(HttpStatusCode.BadRequest) }
        }
        configureControlAuthentication(CREDENTIAL)
        routing {
            configureVolumeConfirmedTrendShadowRoutes(
                reportProvider = null,
                liveSnapshotProvider = provider,
            )
        }
    }
}

private fun sampleLiveSnapshot(): VolumeConfirmedTrendLiveSnapshot =
    VolumeConfirmedTrendLiveSnapshot(
        enabled = true,
        state = sampleLiveState(),
        recentEvents = listOf(sampleLiveEvent()),
        accountSnapshot =
            ExecutionAccountSnapshot(
                mode = ExecutionRuntimeMode.LIVE,
                accountType = "UNIFIED",
                totalEquity = BigDecimal("660.50"),
                totalWalletBalance = BigDecimal("655.25"),
                totalMarginBalance = BigDecimal("660.50"),
                totalAvailableBalance = BigDecimal("300.00"),
                totalPerpUnrealizedPnl = BigDecimal("5.25"),
                totalInitialMargin = BigDecimal("350.00"),
                totalMaintenanceMargin = BigDecimal("10.00"),
                trackedCoin = "USDT",
                trackedCoinEquity = BigDecimal("660.50"),
                trackedCoinWalletBalance = BigDecimal("655.25"),
                trackedCoinUnrealizedPnl = BigDecimal("5.25"),
                trackedCoinCumulativeRealizedPnl = BigDecimal("20.00"),
                capturedAt = OBSERVED_AT,
            ),
        recentExecutionFills =
            listOf(
                ExecutionFillEvent(
                    mode = ExecutionRuntimeMode.LIVE,
                    fill =
                        ExchangeExecutionFill(
                            executionId = "execution-001",
                            exchangeOrderId = "exchange-order-001",
                            clientOrderId = "vcte-order-001",
                            symbol = Symbol("BTCUSDT"),
                            side = Side.BUY,
                            price = BigDecimal("60000"),
                            quantity = BigDecimal("0.007"),
                            fee = BigDecimal("0.252"),
                            executedAt = OBSERVED_AT,
                            executionType = "Trade",
                            executionPnl = BigDecimal.ZERO,
                        ),
                    receivedAt = OBSERVED_AT.plusSeconds(1),
                ),
            ),
    )

private fun sampleLiveState(): VolumeConfirmedTrendLiveState =
    VolumeConfirmedTrendLiveState(
        protocolId = PROTOCOL_ID,
        candidateId = "vcte_4h_majority_001",
        protocolSha256 = PROTOCOL_SHA,
        symbol = Symbol("BTCUSDT"),
        status = VolumeConfirmedTrendLiveStatus.HALTED,
        approvalId = "approval-001",
        activeDecisionKey = "decision-001",
        pendingTargetSide = Side.BUY,
        clientOrderId = "vcte-order-001",
        exchangeOrderId = "exchange-order-001",
        observedPositionSide = Side.SELL,
        observedPositionQuantity = BigDecimal("0.007"),
        lastExecutionId = "execution-001",
        haltedReasonCode = "TREND_POSITION_MISMATCH",
        updatedAt = OBSERVED_AT,
    )

private fun sampleLiveEvent(): VolumeConfirmedTrendLiveEvent =
    VolumeConfirmedTrendLiveEvent(
        eventId = "event-001",
        protocolId = PROTOCOL_ID,
        protocolSha256 = PROTOCOL_SHA,
        symbol = Symbol("BTCUSDT"),
        decisionKey = "decision-001",
        type = VolumeConfirmedTrendLiveEventType.HALTED,
        targetSide = Side.BUY,
        orderSide = Side.BUY,
        orderQuantity = BigDecimal("0.007"),
        referencePrice = BigDecimal("60000"),
        limitPrice = BigDecimal("60012"),
        clientOrderId = "vcte-order-001",
        exchangeOrderId = "exchange-order-001",
        executionId = "execution-001",
        reasonCode = "TREND_POSITION_MISMATCH",
        occurredAt = OBSERVED_AT,
    )

private const val LIVE_PATH = "/strategy/volume-confirmed-trend/live"
private const val CREDENTIAL = "test-control-token"
private const val PROTOCOL_ID = "volume-confirmed-trend-ensemble-v1"
private val PROTOCOL_SHA = "a".repeat(64)
private val OBSERVED_AT = Instant.parse("2026-08-08T00:00:00Z")
