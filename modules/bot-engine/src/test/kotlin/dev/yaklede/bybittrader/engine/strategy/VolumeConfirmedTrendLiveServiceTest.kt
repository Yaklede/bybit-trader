package dev.yaklede.bybittrader.engine.strategy

import dev.yaklede.bybittrader.domain.OrderStatus
import dev.yaklede.bybittrader.domain.OrderType
import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.domain.Symbol
import dev.yaklede.bybittrader.engine.execution.ExchangeAccountBalance
import dev.yaklede.bybittrader.engine.execution.ExchangeAccountExecutionProfile
import dev.yaklede.bybittrader.engine.execution.ExchangeAccountMode
import dev.yaklede.bybittrader.engine.execution.ExchangeAccountTransaction
import dev.yaklede.bybittrader.engine.execution.ExchangeCancelRequest
import dev.yaklede.bybittrader.engine.execution.ExchangeCancelResult
import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionFill
import dev.yaklede.bybittrader.engine.execution.ExchangeExecutionGateway
import dev.yaklede.bybittrader.engine.execution.ExchangeInstrumentRules
import dev.yaklede.bybittrader.engine.execution.ExchangeMarginMode
import dev.yaklede.bybittrader.engine.execution.ExchangeOpenOrder
import dev.yaklede.bybittrader.engine.execution.ExchangeOrderRequest
import dev.yaklede.bybittrader.engine.execution.ExchangeOrderResult
import dev.yaklede.bybittrader.engine.execution.ExchangePosition
import dev.yaklede.bybittrader.engine.execution.ExchangePositionExecutionProfile
import dev.yaklede.bybittrader.engine.execution.ExchangePositionMode
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.yield
import java.math.BigDecimal
import java.time.Instant

class VolumeConfirmedTrendLiveServiceTest :
    StringSpec({
        "non-approved receipt persists disabled state without private exchange access" {
            val gateway = FakeTrendLiveGateway()
            val store = InMemoryTrendLiveStore()
            val service = service(gateway, store, approved = false)

            val result = service.evaluate(command(Side.BUY), BigDecimal("60000"))

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.APPROVAL_BLOCKED
            result.state.status shouldBe VolumeConfirmedTrendLiveStatus.DISABLED
            gateway.exchangeReadCount shouldBe 0
            gateway.submittedOrders.size shouldBe 0
        }

        "non-approved reconciliation remains private-read free" {
            val gateway = FakeTrendLiveGateway()
            val store = InMemoryTrendLiveStore()
            val service = service(gateway, store, approved = false)

            val result = service.reconcile()

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.APPROVAL_BLOCKED
            result.state.status shouldBe VolumeConfirmedTrendLiveStatus.DISABLED
            gateway.exchangeReadCount shouldBe 0
        }

        "repeated approval blocking does not append duplicate halt events" {
            val gateway = FakeTrendLiveGateway()
            val store = InMemoryTrendLiveStore()
            val service = service(gateway, store, approved = false)

            service.reconcile()
            service.reconcile()

            store.events.map { it.type } shouldBe listOf(VolumeConfirmedTrendLiveEventType.HALTED)
            gateway.exchangeReadCount shouldBe 0
        }

        "repeated safety halt with unchanged state is persisted once" {
            val gateway = FakeTrendLiveGateway()
            val store = InMemoryTrendLiveStore()
            val service = service(gateway, store)

            service.haltForSafety("TREND_TEST_HALT")
            service.haltForSafety("TREND_TEST_HALT")

            store.events.map { it.type } shouldBe listOf(VolumeConfirmedTrendLiveEventType.HALTED)
        }

        "approved reconciliation initializes a flat checkpoint before the first signal" {
            val gateway = FakeTrendLiveGateway()
            val store = InMemoryTrendLiveStore()
            val service = service(gateway, store)

            val result = service.reconcile()

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.RECONCILED
            result.state.status shouldBe VolumeConfirmedTrendLiveStatus.FLAT
            store.events.single().type shouldBe VolumeConfirmedTrendLiveEventType.INITIALIZED
            gateway.submittedOrders.size shouldBe 0
        }

        "approved flat account records intent before submitting one bounded IOC order" {
            val gateway = FakeTrendLiveGateway()
            val store = InMemoryTrendLiveStore()
            val service = service(gateway, store)

            val result = service.evaluate(command(Side.BUY), BigDecimal("60000"))

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.ORDER_SUBMITTED
            result.state.status shouldBe VolumeConfirmedTrendLiveStatus.ENTRY_SUBMITTED
            store.events.map { it.type } shouldBe
                listOf(
                    VolumeConfirmedTrendLiveEventType.ENTRY_INTENT_RECORDED,
                    VolumeConfirmedTrendLiveEventType.ENTRY_SUBMITTED,
                )
            gateway.submittedOrders.single().apply {
                side shouldBe Side.BUY
                orderType shouldBe OrderType.LIMIT
                quantity shouldBe BigDecimal("0.007")
                price shouldBe BigDecimal("60012")
                reduceOnly shouldBe false
            }
        }

        "concurrent evaluations are serialized and submit at most one order" {
            val gateway = FakeTrendLiveGateway()
            val store = InMemoryTrendLiveStore()
            val service = service(gateway, store)
            val placeOrderEntered = CompletableDeferred<Unit>()
            val allowPlaceOrder = CompletableDeferred<Unit>()
            gateway.beforePlaceOrder = {
                placeOrderEntered.complete(Unit)
                allowPlaceOrder.await()
            }

            coroutineScope {
                val first = async { service.evaluate(command(Side.BUY), BigDecimal("60000")) }
                placeOrderEntered.await()
                val second = async { service.evaluate(command(Side.BUY), BigDecimal("60000")) }
                yield()
                allowPlaceOrder.complete(Unit)
                awaitAll(first, second)
            }

            gateway.submittedOrders.size shouldBe 1
        }

        "ack persistence failure recovers the exchange order without duplicate submission" {
            val gateway = FakeTrendLiveGateway()
            val store = InMemoryTrendLiveStore(failOnCommitNumber = 2)
            var now = Instant.parse("2026-08-07T00:00:10Z")
            val service = service(gateway, store, clock = { now })

            shouldThrow<IllegalStateException> {
                service.evaluate(command(Side.BUY), BigDecimal("60000"))
            }
            store.state?.status shouldBe VolumeConfirmedTrendLiveStatus.ENTRY_INTENT_RECORDED
            gateway.submittedOrders.size shouldBe 1

            store.failOnCommitNumber = null
            now = now.plusSeconds(1)
            val recovered = service.evaluate(command(Side.BUY), BigDecimal("60000"))

            recovered.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.RECOVERED
            recovered.state.status shouldBe VolumeConfirmedTrendLiveStatus.ENTRY_SUBMITTED
            gateway.submittedOrders.size shouldBe 1
        }

        "cancelled unfilled IOC consumes the H4 decision without resubmitting it" {
            val gateway = FakeTrendLiveGateway()
            val store = InMemoryTrendLiveStore()
            val service = service(gateway, store)

            service.evaluate(command(Side.BUY), BigDecimal("60000"))
            gateway.openOrders[0] =
                gateway.openOrders.single().copy(
                    status = OrderStatus.CANCELLED,
                    filledQuantity = BigDecimal.ZERO,
                    providerStatus = "Cancelled",
                    cancelType = "CancelByCannotAffordOrderCost",
                )

            val cancelled = service.evaluate(command(Side.BUY), BigDecimal("60000"))
            val duplicateAttempt = service.evaluate(command(Side.BUY), BigDecimal("60000"))

            cancelled.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.ORDER_NOT_FILLED
            cancelled.state.status shouldBe VolumeConfirmedTrendLiveStatus.ENTRY_NOT_FILLED
            duplicateAttempt.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.ORDER_NOT_FILLED
            gateway.submittedOrders.size shouldBe 1
            store.events.last().type shouldBe VolumeConfirmedTrendLiveEventType.ENTRY_NOT_FILLED
        }

        "missing exchange order state halts after recovery timeout without blind retry" {
            val gateway = FakeTrendLiveGateway()
            val store = InMemoryTrendLiveStore()
            var now = Instant.parse("2026-08-07T00:00:10Z")
            val service = service(gateway, store, clock = { now })

            service.evaluate(command(Side.BUY), BigDecimal("60000"))
            gateway.openOrders.clear()
            now = now.plusSeconds(11)

            val result = service.evaluate(command(Side.BUY), BigDecimal("60000"))

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.HALTED
            result.state.haltedReasonCode shouldBe "TREND_ENTRY_ORDER_STATE_UNKNOWN"
            gateway.submittedOrders.size shouldBe 1
        }

        "opposite signal closes first and cannot enter until exchange confirms flat" {
            val openPosition = position(Side.BUY, "0.007")
            val gateway = FakeTrendLiveGateway(positions = mutableListOf(openPosition))
            val store = InMemoryTrendLiveStore(state = openState(openPosition))
            val service = service(gateway, store)

            val close = service.evaluate(command(Side.SELL), BigDecimal("60000"))

            close.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.ORDER_SUBMITTED
            close.state.status shouldBe VolumeConfirmedTrendLiveStatus.EXIT_SUBMITTED
            gateway.submittedOrders.single().apply {
                side shouldBe Side.SELL
                quantity shouldBe BigDecimal("0.007")
                reduceOnly shouldBe true
            }

            val waiting = service.evaluate(command(Side.SELL), BigDecimal("60000"))
            waiting.state.status shouldBe VolumeConfirmedTrendLiveStatus.EXIT_SUBMITTED
            gateway.submittedOrders.size shouldBe 1

            gateway.positions.clear()
            gateway.openOrders.clear()
            val flat = service.evaluate(command(Side.SELL), BigDecimal("60000"))
            flat.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.RECOVERED
            flat.state.status shouldBe VolumeConfirmedTrendLiveStatus.FLAT
            gateway.submittedOrders.size shouldBe 1

            val entry = service.evaluate(command(Side.SELL), BigDecimal("60000"))
            entry.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.ORDER_SUBMITTED
            gateway.submittedOrders.size shouldBe 2
            gateway.submittedOrders.last().apply {
                side shouldBe Side.SELL
                reduceOnly shouldBe false
            }
        }
    })

private class InMemoryTrendLiveStore(
    var state: VolumeConfirmedTrendLiveState? = null,
    var failOnCommitNumber: Int? = null,
) : VolumeConfirmedTrendLiveStore {
    val events = mutableListOf<VolumeConfirmedTrendLiveEvent>()
    private var commitCount = 0

    override suspend fun trendLiveState(
        protocolId: String,
        symbol: Symbol,
    ): VolumeConfirmedTrendLiveState? = state

    override suspend fun commitTrendLive(
        state: VolumeConfirmedTrendLiveState,
        events: List<VolumeConfirmedTrendLiveEvent>,
    ) {
        commitCount += 1
        if (commitCount == failOnCommitNumber) throw IllegalStateException("injected trend live store failure")
        events.forEach { event ->
            if (this.events.none { it.eventId == event.eventId }) this.events += event
        }
        this.state = state
    }

    override suspend fun trendLiveEvents(
        protocolId: String,
        symbol: Symbol,
        limit: Int,
    ): List<VolumeConfirmedTrendLiveEvent> = events.takeLast(limit)
}

private class FakeTrendLiveGateway(
    val positions: MutableList<ExchangePosition> = mutableListOf(),
) : ExchangeExecutionGateway {
    val openOrders = mutableListOf<ExchangeOpenOrder>()
    val executionFills = mutableListOf<ExchangeExecutionFill>()
    val submittedOrders = mutableListOf<ExchangeOrderRequest>()
    var exchangeReadCount = 0
    var beforePlaceOrder: suspend () -> Unit = {}

    override suspend fun accountExecutionProfile(): ExchangeAccountExecutionProfile {
        exchangeReadCount += 1
        return ExchangeAccountExecutionProfile(
            accountType = "UNIFIED",
            accountMode = ExchangeAccountMode.UNIFIED_2,
            unifiedMarginStatus = 5,
            marginMode = ExchangeMarginMode.CROSS,
            updatedAt = TEST_NOW,
        )
    }

    override suspend fun positionExecutionProfile(symbol: Symbol): ExchangePositionExecutionProfile {
        exchangeReadCount += 1
        return ExchangePositionExecutionProfile(
            symbol = symbol,
            positionMode = ExchangePositionMode.ONE_WAY,
            buyLeverage = BigDecimal.ONE,
            sellLeverage = BigDecimal.ONE,
            observedPositionIndices = setOf(0),
            reduceOnlyRestricted = false,
        )
    }

    override suspend fun instrumentRules(symbol: Symbol): ExchangeInstrumentRules {
        exchangeReadCount += 1
        return ExchangeInstrumentRules(
            symbol = symbol,
            status = "Trading",
            contractType = "LinearPerpetual",
            baseCoin = "BTC",
            quoteCoin = "USDT",
            settleCoin = "USDT",
            unifiedMarginTrade = true,
            minimumOrderQuantity = BigDecimal("0.001"),
            quantityStep = BigDecimal("0.001"),
            minimumNotional = BigDecimal("5"),
            priceTick = BigDecimal("0.1"),
            minimumLeverage = BigDecimal.ONE,
            maximumLeverage = BigDecimal("100"),
            leverageStep = BigDecimal("0.01"),
        )
    }

    override suspend fun setLeverage(
        symbol: Symbol,
        leverage: BigDecimal,
    ) = Unit

    override suspend fun placeOrder(request: ExchangeOrderRequest): ExchangeOrderResult {
        beforePlaceOrder()
        submittedOrders += request
        val exchangeOrderId = "exchange-${submittedOrders.size}"
        openOrders.removeAll { it.clientOrderId == request.clientOrderId }
        openOrders +=
            ExchangeOpenOrder(
                exchangeOrderId = exchangeOrderId,
                clientOrderId = request.clientOrderId,
                symbol = request.symbol,
                side = request.side,
                orderType = request.orderType,
                status = OrderStatus.SUBMITTED,
                quantity = request.quantity,
                createdAt = TEST_NOW,
                reduceOnly = request.reduceOnly,
                filledQuantity = BigDecimal.ZERO,
                updatedAt = TEST_NOW,
                providerStatus = "New",
            )
        return ExchangeOrderResult(exchangeOrderId, request.clientOrderId, OrderStatus.SUBMITTED)
    }

    override suspend fun cancelOrder(request: ExchangeCancelRequest): ExchangeCancelResult =
        ExchangeCancelResult(request.exchangeOrderId, request.clientOrderId)

    override suspend fun openOrders(symbol: Symbol): List<ExchangeOpenOrder> {
        exchangeReadCount += 1
        return openOrders.toList()
    }

    override suspend fun positions(symbol: Symbol): List<ExchangePosition> {
        exchangeReadCount += 1
        return positions.toList()
    }

    override suspend fun executions(symbol: Symbol): List<ExchangeExecutionFill> {
        exchangeReadCount += 1
        return executionFills.toList()
    }

    override suspend fun accountBalance(coin: String?): ExchangeAccountBalance {
        exchangeReadCount += 1
        return ExchangeAccountBalance(
            accountType = "UNIFIED",
            totalEquity = BigDecimal("660"),
            totalWalletBalance = BigDecimal("660"),
            totalMarginBalance = BigDecimal("660"),
            totalAvailableBalance = BigDecimal("660"),
            totalPerpUnrealizedPnl = BigDecimal.ZERO,
            totalInitialMargin = BigDecimal.ZERO,
            totalMaintenanceMargin = BigDecimal.ZERO,
            coins = emptyList(),
            capturedAt = TEST_NOW,
        )
    }

    override suspend fun accountTransactions(
        currency: String,
        startAt: Instant,
        endAt: Instant,
    ): List<ExchangeAccountTransaction> = emptyList()
}

private fun service(
    gateway: FakeTrendLiveGateway,
    store: InMemoryTrendLiveStore,
    approved: Boolean = true,
    clock: () -> Instant = { TEST_NOW },
): VolumeConfirmedTrendLiveService =
    VolumeConfirmedTrendLiveService(
        gateway = gateway,
        store = store,
        config =
            VolumeConfirmedTrendLiveConfig(
                protocolId = PROTOCOL_ID,
                candidateId = CANDIDATE_ID,
                protocolSha256 = PROTOCOL_SHA,
                symbol = Symbol("BTCUSDT"),
            ),
        approvalReceipt = approvalReceipt(approved),
        approvalReportProvider = { approvalReport() },
        shadowEvidenceSha256 = EVIDENCE_SHA,
        approvalReportSha256 = REPORT_SHA,
        clock = clock,
    )

private fun approvalReceipt(approved: Boolean): VolumeConfirmedTrendLiveApprovalReceipt =
    VolumeConfirmedTrendLiveApprovalReceipt(
        schemaVersion = 1,
        status = if (approved) VolumeConfirmedTrendLiveApprovalStatus.APPROVED else VolumeConfirmedTrendLiveApprovalStatus.NOT_APPROVED,
        approvalId = if (approved) APPROVAL_ID else null,
        protocolId = PROTOCOL_ID,
        candidateId = CANDIDATE_ID,
        protocolSha256 = PROTOCOL_SHA,
        policyId = POLICY_ID,
        policySha256 = POLICY_SHA,
        shadowSessionId = if (approved) SESSION_ID else null,
        shadowEvidenceSha256 = if (approved) EVIDENCE_SHA else null,
        approvalReportSha256 = if (approved) REPORT_SHA else null,
        approvedAt = if (approved) TEST_NOW else null,
        approvedBy = if (approved) "human-owner" else null,
        liveExecutionAllowed = approved,
        reasonCode = if (approved) "HUMAN_REVIEW_APPROVED" else "FRESH_SHADOW_AND_HUMAN_REVIEW_REQUIRED",
    )

private fun approvalReport(): VolumeConfirmedTrendApprovalReport =
    VolumeConfirmedTrendApprovalReport(
        status = VolumeConfirmedTrendApprovalStatus.READY_FOR_HUMAN_REVIEW,
        protocolId = PROTOCOL_ID,
        candidateId = CANDIDATE_ID,
        protocolSha256 = PROTOCOL_SHA,
        policyId = POLICY_ID,
        policySha256 = POLICY_SHA,
        evaluatedAt = TEST_NOW,
        sessionId = SESSION_ID,
        observedCalendarDays = 90.0,
        sessionReturnPct = 5.0,
        closedTradeProfitFactor = 1.5,
        gates = emptyList(),
        readyForHumanReview = true,
    )

private fun command(side: Side): VolumeConfirmedTrendCommand =
    VolumeConfirmedTrendCommand(
        side = side,
        decisionAt = Instant.parse("2026-08-06T20:00:00Z"),
        executionAt = Instant.parse("2026-08-07T00:00:00Z"),
        decisionIndex = 600,
        executionIndex = 601,
        netVotes = if (side == Side.BUY) 3 else -3,
        decisionVolume = 100.0,
        priorVolumeMedian = 80.0,
    )

private fun position(
    side: Side,
    quantity: String,
): ExchangePosition =
    ExchangePosition(
        symbol = Symbol("BTCUSDT"),
        side = side,
        size = BigDecimal(quantity),
        openedAt = Instant.parse("2026-08-06T00:00:00Z"),
        entryPrice = BigDecimal("59000"),
        markPrice = BigDecimal("60000"),
        unrealizedPnl = BigDecimal("7"),
        updatedAt = TEST_NOW,
    )

private fun openState(position: ExchangePosition): VolumeConfirmedTrendLiveState =
    VolumeConfirmedTrendLiveState(
        protocolId = PROTOCOL_ID,
        candidateId = CANDIDATE_ID,
        protocolSha256 = PROTOCOL_SHA,
        symbol = position.symbol,
        status = VolumeConfirmedTrendLiveStatus.OPEN,
        approvalId = APPROVAL_ID,
        activeDecisionKey = "$PROTOCOL_SHA|2026-08-06T00:00:00Z|BUY",
        pendingTargetSide = position.side,
        clientOrderId = null,
        exchangeOrderId = null,
        observedPositionSide = position.side,
        observedPositionQuantity = position.size,
        lastExecutionId = null,
        haltedReasonCode = null,
        updatedAt = TEST_NOW.minusSeconds(14_400),
    )

private const val PROTOCOL_ID = "volume-confirmed-trend-ensemble-v1"
private const val CANDIDATE_ID = "vcte_4h_majority_001"
private const val POLICY_ID = "volume-confirmed-trend-ensemble-v1-forward-policy"
private const val SESSION_ID = "trend-shadow-forward-session"
private const val APPROVAL_ID = "approval-2026-11-07"
private val PROTOCOL_SHA = "a".repeat(64)
private val POLICY_SHA = "b".repeat(64)
private val EVIDENCE_SHA = "c".repeat(64)
private val REPORT_SHA = "e".repeat(64)
private val TEST_NOW: Instant = Instant.parse("2026-11-07T00:00:10Z")
