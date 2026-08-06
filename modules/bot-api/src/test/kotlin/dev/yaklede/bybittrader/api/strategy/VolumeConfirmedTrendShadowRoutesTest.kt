package dev.yaklede.bybittrader.api.strategy

import dev.yaklede.bybittrader.api.security.configureControlAuthentication
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalGate
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalGateStatus
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalReport
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendApprovalStatus
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendEmaState
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendIndicatorState
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowEvent
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowEventType
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowReport
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowState
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendShadowStatus
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
import java.time.Instant

class VolumeConfirmedTrendShadowRoutesTest :
    StringSpec({
        "shadow status requires operator authentication" {
            testApplication {
                applicationWithShadowProvider(provider = { sampleReport() })

                client.get(ENDPOINT).status shouldBe HttpStatusCode.Unauthorized
            }
        }

        "disabled shadow status is explicit and contains no synthetic state" {
            testApplication {
                applicationWithShadowProvider(null)

                val response = client.get(ENDPOINT) { bearerAuth(CREDENTIAL) }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText() shouldBe
                    """{"enabled":false,"protocolId":null,"candidateId":null,"protocolSha256":null,"symbol":null,"state":null,"recentEvents":[]}"""
            }
        }

        "enabled shadow status exposes persisted performance risk and event data" {
            var requestedLimit = 0
            testApplication {
                applicationWithShadowProvider(
                    provider = { limit ->
                        requestedLimit = limit
                        sampleReport()
                    },
                )

                val response = client.get("$ENDPOINT?limit=7") { bearerAuth(CREDENTIAL) }

                response.status shouldBe HttpStatusCode.OK
                requestedLimit shouldBe 7
                response.bodyAsText().also { body ->
                    body shouldContain "\"enabled\":true"
                    body shouldContain "\"candidateId\":\"vcte_4h_majority_001\""
                    body shouldContain "\"sessionReturnPct\":\"5.000000000000004\""
                    body shouldContain "\"maximumDrawdownPct\":\"3.25\""
                    body shouldContain "\"liquidationCount\":0"
                    body shouldContain "\"type\":\"SESSION_INVALIDATED\""
                    body shouldContain "\"reason\":\"MISSED_H4_COUNT=1\""
                }
            }
        }

        "invalid event limit is rejected" {
            testApplication {
                applicationWithShadowProvider(provider = { sampleReport() })

                client.get("$ENDPOINT?limit=invalid") { bearerAuth(CREDENTIAL) }.status shouldBe HttpStatusCode.BadRequest
                client.get("$ENDPOINT?limit=101") { bearerAuth(CREDENTIAL) }.status shouldBe HttpStatusCode.BadRequest
            }
        }

        "approval status exposes every blocker without granting execution" {
            testApplication {
                applicationWithShadowProvider(
                    provider = { sampleReport() },
                    approvalProvider = { sampleApprovalReport() },
                )

                val response = client.get(APPROVAL_ENDPOINT) { bearerAuth(CREDENTIAL) }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText().also { body ->
                    body shouldContain "\"status\":\"SHADOW_COLLECTING\""
                    body shouldContain "\"id\":\"FRESH_SHADOW_DAYS\""
                    body shouldContain "\"actual\":\"12.5\""
                    body shouldContain "\"required\":\">=90.0\""
                    body shouldContain "\"readyForHumanReview\":false"
                    body shouldContain "\"automaticExecutionAllowed\":false"
                    body shouldContain "\"liveExecutionAllowed\":false"
                }
            }
        }
    })

private fun io.ktor.server.testing.ApplicationTestBuilder.applicationWithShadowProvider(
    provider: VolumeConfirmedTrendShadowReportProvider?,
    approvalProvider: VolumeConfirmedTrendApprovalReportProvider? = null,
) {
    application {
        install(ContentNegotiation) { json() }
        install(StatusPages) {
            exception<IllegalArgumentException> { call, _ -> call.respond(HttpStatusCode.BadRequest) }
        }
        configureControlAuthentication(CREDENTIAL)
        routing { configureVolumeConfirmedTrendShadowRoutes(provider, approvalProvider) }
    }
}

private fun sampleApprovalReport(): VolumeConfirmedTrendApprovalReport =
    VolumeConfirmedTrendApprovalReport(
        status = VolumeConfirmedTrendApprovalStatus.SHADOW_COLLECTING,
        protocolId = "volume-confirmed-trend-ensemble-v1",
        candidateId = "vcte_4h_majority_001",
        protocolSha256 = "a".repeat(64),
        policyId = "forward-policy",
        policySha256 = "f".repeat(64),
        evaluatedAt = Instant.parse("2026-08-07T00:00:00Z"),
        sessionId = "shadow-session-2",
        observedCalendarDays = 12.5,
        sessionReturnPct = 1.5,
        closedTradeProfitFactor = 1.2,
        gates =
            listOf(
                VolumeConfirmedTrendApprovalGate(
                    id = "FRESH_SHADOW_DAYS",
                    status = VolumeConfirmedTrendApprovalGateStatus.PENDING,
                    actual = "12.5",
                    required = ">=90.0",
                    reason = "Fresh shadow observation is incomplete.",
                ),
            ),
        readyForHumanReview = false,
    )

private fun sampleReport(): VolumeConfirmedTrendShadowReport {
    val state = sampleState()
    return VolumeConfirmedTrendShadowReport(
        protocolId = state.protocolId,
        candidateId = state.candidateId,
        protocolSha256 = state.protocolSha256,
        symbol = state.symbol,
        state = state,
        recentEvents = listOf(sampleEvent(state)),
    )
}

private fun sampleState(): VolumeConfirmedTrendShadowState =
    VolumeConfirmedTrendShadowState(
        protocolId = "volume-confirmed-trend-ensemble-v1",
        candidateId = "vcte_4h_majority_001",
        protocolSha256 = "a".repeat(64),
        symbol = Symbol("BTCUSDT"),
        sessionId = "shadow-session-2",
        status = VolumeConfirmedTrendShadowStatus.OBSERVING,
        sessionStartedAt = Instant.parse("2026-08-01T00:00:00Z"),
        indicatorState =
            VolumeConfirmedTrendIndicatorState(
                processedBars = 600,
                lastBarOpenedAt = Instant.parse("2026-08-06T20:00:00Z"),
                emaStates = listOf(VolumeConfirmedTrendEmaState(61_000.0, 60_000.0)),
                targetSide = Side.BUY,
                recentVolumes = listOf(10.0, 12.0),
            ),
        lastAppliedFundingAt = Instant.parse("2026-08-07T00:00:00Z"),
        lastObservedAt = Instant.parse("2026-08-07T00:00:10Z"),
        position = null,
        sessionStartingEquity = 100.0,
        cash = 105.0,
        equity = 105.0,
        peakEquity = 108.0,
        maximumDrawdownPct = 3.25,
        totalFees = 1.2,
        totalSlippage = 0.4,
        totalFundingPnl = -0.1,
        closedTrades = 4,
        executedTransitions = 4,
        invalidatedSessionCount = 1,
        updatedAt = Instant.parse("2026-08-07T00:00:10Z"),
        maximumEntryExposureFraction = 0.65,
        maximumAdverseExposureFraction = 0.7,
        liquidationCount = 0,
    )

private fun sampleEvent(state: VolumeConfirmedTrendShadowState): VolumeConfirmedTrendShadowEvent =
    VolumeConfirmedTrendShadowEvent(
        eventId = "event-1",
        sessionId = "shadow-session-1",
        protocolId = state.protocolId,
        protocolSha256 = state.protocolSha256,
        symbol = state.symbol,
        type = VolumeConfirmedTrendShadowEventType.SESSION_INVALIDATED,
        eventAt = Instant.parse("2026-07-31T20:00:00Z"),
        observedAt = Instant.parse("2026-07-31T20:30:00Z"),
        h4OpenedAt = Instant.parse("2026-07-31T16:00:00Z"),
        side = null,
        referencePrice = 60_000.0,
        fillPrice = null,
        quantity = null,
        fee = 0.0,
        slippage = 0.0,
        fundingPnl = 0.0,
        grossPnl = 0.0,
        netPnl = 0.0,
        cash = 100.0,
        equity = 100.0,
        reason = "MISSED_H4_COUNT=1",
    )

private const val ENDPOINT = "/strategy/volume-confirmed-trend/shadow"
private const val APPROVAL_ENDPOINT = "/strategy/volume-confirmed-trend/approval"
private const val CREDENTIAL = "test-control-credential"
