package dev.yaklede.bybittrader.api.strategy

import dev.yaklede.bybittrader.api.security.configureControlAuthentication
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.execution.ExchangeAccountTransaction
import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionFill
import dev.yaklede.bybittrader.engine.execution.ExecutionAccountSnapshot
import dev.yaklede.bybittrader.engine.execution.ExecutionAccountTransactionEvent
import dev.yaklede.bybittrader.engine.execution.ExecutionFillEvent
import dev.yaklede.bybittrader.engine.execution.ExecutionRiskNavStatus
import dev.yaklede.bybittrader.engine.execution.ExecutionRiskState
import dev.yaklede.bybittrader.engine.execution.ExecutionRuntimeMode
import dev.yaklede.bybittrader.engine.execution.ExecutionTradeClosure
import dev.yaklede.bybittrader.engine.execution.ExecutionWalletReconciliationState
import dev.yaklede.bybittrader.engine.execution.ExecutionWalletReconciliationStatus
import dev.yaklede.bybittrader.engine.execution.LivePerformanceSnapshot
import dev.yaklede.bybittrader.engine.execution.LivePerformanceWindow
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveEvent
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveEventType
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLivePerformanceEvidence
import dev.yaklede.bybittrader.engine.strategy.VolumeConfirmedTrendLiveRuntimeMode
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
                    """{"enabled":false,"runtimeMode":"DISABLED","runtimeActive":false,"state":null,"recentEvents":[],"account":null,"risk":null,"walletReconciliation":null,"performance":[],"recentClosedTrades":[],"recentExecutionFills":[],"recentAccountTransactions":[]}"""
            }
        }

        "management-only live status is explicit" {
            testApplication {
                applicationWithLiveProvider {
                    sampleLiveSnapshot(
                        runtimeMode = VolumeConfirmedTrendLiveRuntimeMode.MANAGEMENT_ONLY,
                        runtimeActive = true,
                    )
                }

                val response = client.get(LIVE_PATH) { bearerAuth(CREDENTIAL) }

                response.status shouldBe HttpStatusCode.OK
                response.bodyAsText().also { body ->
                    body shouldContain "\"enabled\":true"
                    body shouldContain "\"runtimeMode\":\"MANAGEMENT_ONLY\""
                    body shouldContain "\"runtimeActive\":true"
                }
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
                    body shouldContain "\"runtimeMode\":\"SIGNAL_ENABLED\""
                    body shouldContain "\"runtimeActive\":true"
                    body shouldContain "\"status\":\"HALTED\""
                    body shouldContain "\"approvalId\":\"approval-001\""
                    body shouldContain "\"clientOrderId\":\"vct-entry-order-001\""
                    body shouldContain "\"haltedReasonCode\":\"TREND_POSITION_MISMATCH\""
                    body shouldContain "\"type\":\"HALTED\""
                    body shouldContain "\"reasonCode\":\"TREND_POSITION_MISMATCH\""
                    body shouldContain "\"totalEquity\":\"660.50\""
                    body shouldContain "\"executionId\":\"execution-001\""
                    body shouldContain "\"fee\":\"0.252\""
                    body shouldContain "\"reasonCodes\":[\"ACCOUNT_DRAWDOWN_LIMIT_REACHED\"]"
                    body shouldContain "\"currentAccountDrawdownFraction\":\"0.40000000\""
                    body shouldContain "\"maximumAccountDrawdownFraction\":\"0.35\""
                    body shouldContain "\"walletReconciliation\":{\"status\":\"MATCHED\""
                    body shouldContain "\"window\":\"ALL\""
                    body shouldContain "\"btcFundingPnl\":\"-0.01\""
                    body shouldContain "\"netPnl\":\"6.5\""
                    body shouldContain "\"transactionId\":\"transaction-001\""
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

private fun sampleLiveSnapshot(
    runtimeMode: VolumeConfirmedTrendLiveRuntimeMode = VolumeConfirmedTrendLiveRuntimeMode.SIGNAL_ENABLED,
    runtimeActive: Boolean = true,
): VolumeConfirmedTrendLiveSnapshot =
    VolumeConfirmedTrendLiveSnapshot(
        enabled = true,
        runtimeMode = runtimeMode,
        runtimeActive = runtimeActive,
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
                            clientOrderId = "vct-entry-order-001",
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
        maximumAccountDrawdownFraction = BigDecimal("0.35"),
        walletReconciliation =
            ExecutionWalletReconciliationState(
                mode = ExecutionRuntimeMode.LIVE,
                currency = "USDT",
                status = ExecutionWalletReconciliationStatus.MATCHED,
                baselineSnapshotId = 1,
                baselineCapturedAt = OBSERVED_AT.minusSeconds(300),
                baselineWalletBalance = BigDecimal("666.24"),
                currentSnapshotId = 2,
                currentCapturedAt = OBSERVED_AT,
                currentWalletBalance = BigDecimal("655.25"),
                observedWalletChange = BigDecimal("-10.99"),
                ledgerChange = BigDecimal("-10.99"),
                difference = BigDecimal.ZERO,
                tolerance = BigDecimal("0.01"),
                consecutiveMismatches = 0,
                lastMatchedAt = OBSERVED_AT,
                reconciledAt = OBSERVED_AT,
            ),
        performance =
            listOf(
                VolumeConfirmedTrendLivePerformanceEvidence(
                    snapshot =
                        LivePerformanceSnapshot(
                            mode = ExecutionRuntimeMode.LIVE,
                            window = LivePerformanceWindow.ALL,
                            tradeCount = 1,
                            winRatePct = BigDecimal("100"),
                            grossProfit = BigDecimal("6.5"),
                            grossLoss = BigDecimal.ZERO,
                            fees = BigDecimal("0.5"),
                            netPnl = BigDecimal("6.5"),
                            profitFactor = null,
                            expectancy = BigDecimal("6.5"),
                            maxClosedTradeDrawdownPct = BigDecimal.ZERO,
                            lastClosedAt = OBSERVED_AT.minusSeconds(1),
                            capturedAt = OBSERVED_AT,
                            accountEquity = BigDecimal("660.50"),
                            accountPeakEquity = BigDecimal("666.24"),
                            maxAccountDrawdownPct = BigDecimal("0.86157"),
                            accountEquityCapturedAt = OBSERVED_AT,
                        ),
                    btcFundingPnl = BigDecimal("-0.01"),
                    strategyTransactionFees = BigDecimal("-0.5"),
                ),
            ),
        recentClosures = listOf(sampleClosure()),
        recentAccountTransactions = listOf(sampleAccountTransaction()),
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
        clientOrderId = "vct-entry-order-001",
        exchangeOrderId = "exchange-order-001",
        observedPositionSide = Side.SELL,
        observedPositionQuantity = BigDecimal("0.007"),
        lastExecutionId = "execution-001",
        haltedReasonCode = "TREND_POSITION_MISMATCH",
        updatedAt = OBSERVED_AT,
        riskState =
            ExecutionRiskState(
                mode = ExecutionRuntimeMode.LIVE,
                peakEquity = BigDecimal("1100"),
                utcDayStartedAt = OBSERVED_AT,
                dayStartEquity = BigDecimal("660.50"),
                latestEquity = BigDecimal("660.50"),
                consecutiveLosses = 0,
                lastClosureId = 1,
                updatedAt = OBSERVED_AT,
                navStatus = ExecutionRiskNavStatus.READY,
                strategyUnits = BigDecimal("660.50"),
                latestUnitizedNav = BigDecimal("0.6"),
                peakUnitizedNav = BigDecimal.ONE,
                dayStartUnitizedNav = BigDecimal("0.6"),
                cumulativeExternalCashFlow = BigDecimal.ZERO,
                lastAccountTransactionId = 1,
            ),
        riskReasonCodes = listOf("ACCOUNT_DRAWDOWN_LIMIT_REACHED"),
    )

private fun sampleClosure(): ExecutionTradeClosure =
    ExecutionTradeClosure(
        id = 1,
        mode = ExecutionRuntimeMode.LIVE,
        symbol = Symbol("BTCUSDT"),
        side = Side.BUY,
        openedAt = OBSERVED_AT.minusSeconds(14_400),
        closedAt = OBSERVED_AT.minusSeconds(1),
        entryPrice = BigDecimal("59000"),
        exitPrice = BigDecimal("60000"),
        quantity = BigDecimal("0.007"),
        grossPnl = BigDecimal("7"),
        fees = BigDecimal("0.5"),
        netPnl = BigDecimal("6.5"),
        exitReason = "STRATEGY_EXIT",
        exchangeOrderId = "exchange-order-001",
        clientOrderId = "vct-exit-order-001",
    )

private fun sampleAccountTransaction(): ExecutionAccountTransactionEvent =
    ExecutionAccountTransactionEvent(
        id = 1,
        mode = ExecutionRuntimeMode.LIVE,
        transaction =
            ExchangeAccountTransaction(
                transactionId = "transaction-001",
                symbol = Symbol("BTCUSDT"),
                category = "linear",
                side = Side.BUY,
                transactionAt = OBSERVED_AT.minusSeconds(30),
                type = "SETTLEMENT",
                subtype = "FUNDING",
                quantity = null,
                size = BigDecimal("0.007"),
                currency = "USDT",
                tradePrice = BigDecimal("60000"),
                funding = BigDecimal("-0.01"),
                fee = BigDecimal.ZERO,
                cashFlow = BigDecimal.ZERO,
                change = BigDecimal("-0.01"),
                cashBalance = BigDecimal("660.50"),
                feeRate = null,
                tradeId = null,
                exchangeOrderId = null,
                clientOrderId = null,
            ),
        receivedAt = OBSERVED_AT,
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
        clientOrderId = "vct-entry-order-001",
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
