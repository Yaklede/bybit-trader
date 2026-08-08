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
import dev.yaklede.bybittrader.engine.execution.ExchangeClosedPnl
import dev.yaklede.bybittrader.engine.execution.ExchangeCoinBalance
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
import dev.yaklede.bybittrader.engine.execution.ExchangeSpotHedgingStatus
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

        "approval report outage without a prior live state remains private-read free" {
            val gateway = FakeTrendLiveGateway()
            val store = InMemoryTrendLiveStore()
            val service =
                service(
                    gateway = gateway,
                    store = store,
                    approvalReportProvider = { error("injected approval report outage") },
                )

            val result = service.reconcile()

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.APPROVAL_BLOCKED
            result.approvalFailures shouldBe listOf(VolumeConfirmedTrendLiveApprovalFailure.APPROVAL_REPORT_UNAVAILABLE)
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

        "revoked approval submits one reduce-only safety exit for an owned position" {
            val position = position(Side.BUY, "0.007")
            val gateway = FakeTrendLiveGateway(mutableListOf(position))
            val store = InMemoryTrendLiveStore(state = openState(position))
            val service = service(gateway, store, approved = false)

            val result = service.reconcile()

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.ORDER_SUBMITTED
            result.state.status shouldBe VolumeConfirmedTrendLiveStatus.EXIT_SUBMITTED
            result.state.approvalId shouldBe APPROVAL_ID
            result.plan?.reasonCode shouldBe TREND_APPROVAL_REVOKED_EXIT_REASON_CODE
            result.approvalFailures.contains(VolumeConfirmedTrendLiveApprovalFailure.RECEIPT_NOT_APPROVED) shouldBe true
            gateway.submittedOrders.single().apply {
                side shouldBe Side.SELL
                quantity shouldBe BigDecimal("0.007")
                reduceOnly shouldBe true
                takeProfit shouldBe null
                stopLoss shouldBe null
            }
        }

        "approval report outage blocks entry but still exits an owned position" {
            val position = position(Side.SELL, "0.004")
            val gateway = FakeTrendLiveGateway(mutableListOf(position))
            val store = InMemoryTrendLiveStore(state = openState(position))
            val service =
                service(
                    gateway = gateway,
                    store = store,
                    approvalReportProvider = { error("injected approval report outage") },
                )

            val result = service.reconcile()

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.ORDER_SUBMITTED
            result.approvalFailures shouldBe listOf(VolumeConfirmedTrendLiveApprovalFailure.APPROVAL_REPORT_UNAVAILABLE)
            gateway.submittedOrders.single().apply {
                side shouldBe Side.BUY
                reduceOnly shouldBe true
            }
        }

        "revoked approval recovers a pending entry before submitting its safety exit" {
            val gateway = FakeTrendLiveGateway()
            val store = InMemoryTrendLiveStore()
            val approvedService = service(gateway, store)
            val submitted = approvedService.evaluate(command(Side.BUY), BigDecimal("60000"))
            val clientOrderId = requireNotNull(submitted.state.clientOrderId)
            gateway.positions += position(Side.BUY, "0.007")
            gateway.executionFills += executionFill(clientOrderId, "execution-before-revocation")
            gateway.openOrders[0] =
                gateway.openOrders.single().copy(
                    status = OrderStatus.FILLED,
                    filledQuantity = BigDecimal("0.007"),
                    providerStatus = "Filled",
                )
            val revokedService = service(gateway, store, approved = false)

            val recovered = revokedService.reconcile()
            val safetyExit = revokedService.reconcile()

            recovered.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.RECOVERED
            recovered.state.status shouldBe VolumeConfirmedTrendLiveStatus.OPEN
            safetyExit.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.ORDER_SUBMITTED
            safetyExit.plan?.reasonCode shouldBe TREND_APPROVAL_REVOKED_EXIT_REASON_CODE
            gateway.submittedOrders.last().reduceOnly shouldBe true
        }

        "revoked approval clears settled unfilled entry identity before halting" {
            val gateway = FakeTrendLiveGateway()
            val store = InMemoryTrendLiveStore()
            val approvedService = service(gateway, store)
            approvedService.evaluate(command(Side.BUY), BigDecimal("60000"))
            gateway.openOrders[0] =
                gateway.openOrders.single().copy(
                    status = OrderStatus.CANCELLED,
                    filledQuantity = BigDecimal.ZERO,
                    providerStatus = "Cancelled",
                    cancelType = "CancelByCannotAffordOrderCost",
                )
            val revokedService = service(gateway, store, approved = false)

            val settled = revokedService.reconcile()
            val blocked = revokedService.reconcile()

            settled.state.status shouldBe VolumeConfirmedTrendLiveStatus.ENTRY_NOT_FILLED
            blocked.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.APPROVAL_BLOCKED
            blocked.state.status shouldBe VolumeConfirmedTrendLiveStatus.HALTED
            blocked.state.haltedReasonCode shouldBe "TREND_LIVE_NOT_APPROVED"
            blocked.state.clientOrderId shouldBe null
            blocked.state.exchangeOrderId shouldBe null
            gateway.submittedOrders.size shouldBe 1
        }

        "approval loss preserves an unresolved order halt for operator recovery" {
            val gateway = FakeTrendLiveGateway()
            val store = InMemoryTrendLiveStore()
            var now = Instant.parse("2026-08-07T00:00:10Z")
            val approvedService = service(gateway, store, clock = { now })
            approvedService.evaluate(command(Side.BUY), BigDecimal("60000"))
            gateway.openOrders.clear()
            now = now.plusSeconds(11)
            val unresolved = approvedService.reconcile()
            val eventsBeforeRevocation = store.events.size
            val revokedService = service(gateway, store, approved = false, clock = { now })

            val blocked = revokedService.reconcile()

            unresolved.state.haltedReasonCode shouldBe "TREND_ENTRY_ORDER_STATE_UNKNOWN"
            blocked.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.APPROVAL_BLOCKED
            blocked.state shouldBe unresolved.state
            blocked.state.clientOrderId shouldBe unresolved.state.clientOrderId
            store.events.size shouldBe eventsBeforeRevocation
            gateway.submittedOrders.size shouldBe 1
        }

        "revoked approval clears a stale owned position after the exchange confirms flat" {
            val position = position(Side.BUY, "0.007")
            listOf(
                VolumeConfirmedTrendLiveStatus.OPEN,
                VolumeConfirmedTrendLiveStatus.EXIT_NOT_FILLED,
            ).forEach { status ->
                val stored =
                    openState(position).copy(
                        status = status,
                        clientOrderId = "vct-order-settled",
                        exchangeOrderId = "exchange-order-settled",
                    )
                val gateway = FakeTrendLiveGateway()
                val store = InMemoryTrendLiveStore(state = stored)
                val service = service(gateway, store, approved = false)

                val blocked = service.reconcile()

                blocked.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.APPROVAL_BLOCKED
                blocked.state.status shouldBe VolumeConfirmedTrendLiveStatus.HALTED
                blocked.state.haltedReasonCode shouldBe "TREND_LIVE_NOT_APPROVED"
                blocked.state.observedPositionSide shouldBe null
                blocked.state.observedPositionQuantity shouldBe null
                blocked.state.clientOrderId shouldBe null
                blocked.state.exchangeOrderId shouldBe null
                gateway.submittedOrders.size shouldBe 0
            }
        }

        "revoked approval clears stale position data but preserves unresolved halted order evidence" {
            val position = position(Side.BUY, "0.007")
            val stored =
                openState(position).copy(
                    status = VolumeConfirmedTrendLiveStatus.HALTED,
                    clientOrderId = "vct-entry-unresolved",
                    exchangeOrderId = "exchange-entry-unresolved",
                    haltedReasonCode = "TREND_ENTRY_ORDER_STATE_UNKNOWN",
                )
            val gateway = FakeTrendLiveGateway()
            val store = InMemoryTrendLiveStore(state = stored)
            val service = service(gateway, store, approved = false)

            val blocked = service.reconcile()

            blocked.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.APPROVAL_BLOCKED
            blocked.state.haltedReasonCode shouldBe "TREND_ENTRY_ORDER_STATE_UNKNOWN"
            blocked.state.observedPositionSide shouldBe null
            blocked.state.observedPositionQuantity shouldBe null
            blocked.state.clientOrderId shouldBe "vct-entry-unresolved"
            blocked.state.exchangeOrderId shouldBe "exchange-entry-unresolved"
            store.events.single().type shouldBe VolumeConfirmedTrendLiveEventType.RECONCILED
            gateway.submittedOrders.size shouldBe 0
        }

        "revoked approval retries a known unfilled safety exit only after its delay" {
            var now = TEST_NOW
            val position = position(Side.BUY, "0.007")
            val gateway = FakeTrendLiveGateway(mutableListOf(position))
            val store = InMemoryTrendLiveStore(state = openState(position))
            val service = service(gateway, store, approved = false, clock = { now })

            val submitted = service.reconcile()
            gateway.openOrders[0] =
                gateway.openOrders.single().copy(
                    status = OrderStatus.CANCELLED,
                    providerStatus = "Cancelled",
                    cancelType = "UNKNOWN",
                )
            val notFilled = service.reconcile()
            val waiting = service.reconcile()
            now = now.plusSeconds(61)
            val retried = service.reconcile()

            submitted.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.ORDER_SUBMITTED
            notFilled.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.ORDER_NOT_FILLED
            waiting.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.APPROVAL_BLOCKED
            retried.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.ORDER_SUBMITTED
            gateway.submittedOrders.size shouldBe 2
            gateway.submittedOrders
                .map { it.clientOrderId }
                .distinct()
                .size shouldBe 2
        }

        "safety exit is not suppressed when its position is below minimum notional" {
            val position = position(Side.BUY, "0.001").copy(markPrice = BigDecimal("1000"))
            val gateway = FakeTrendLiveGateway(mutableListOf(position))
            val store = InMemoryTrendLiveStore(state = openState(position))
            val service = service(gateway, store, approved = false)

            val result = service.reconcile()

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.ORDER_SUBMITTED
            gateway.submittedOrders.single().apply {
                quantity * requireNotNull(price) shouldBe BigDecimal("0.9998")
                reduceOnly shouldBe true
            }
        }

        "revoked approval never closes a position whose ownership is not proven" {
            val position = position(Side.BUY, "0.007")
            val gateway = FakeTrendLiveGateway(mutableListOf(position))
            val store = InMemoryTrendLiveStore(state = openState(position).copy(observedPositionQuantity = BigDecimal("0.006")))
            val service = service(gateway, store, approved = false)

            val result = service.reconcile()
            val repeated = service.reconcile()

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.HALTED
            repeated.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.HALTED
            result.state.approvalId shouldBe APPROVAL_ID
            result.state.haltedReasonCode shouldBe "TREND_APPROVAL_REVOKED_POSITION_OWNERSHIP_UNCONFIRMED"
            result.state.observedPositionQuantity shouldBe BigDecimal("0.006")
            repeated.state.observedPositionQuantity shouldBe BigDecimal("0.006")
            gateway.submittedOrders.size shouldBe 0
        }

        "revoked approval never duplicates an unresolved owned order" {
            val position = position(Side.BUY, "0.007")
            val gateway = FakeTrendLiveGateway(mutableListOf(position))
            gateway.openOrders += foreignOpenOrder(clientOrderId = "vct-x-s-orphan")
            val store = InMemoryTrendLiveStore(state = openState(position))
            val service = service(gateway, store, approved = false)

            val result = service.reconcile()

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.HALTED
            result.state.haltedReasonCode shouldBe "TREND_UNRESOLVED_OWNED_OPEN_ORDER_OBSERVED"
            result.approvalFailures.contains(VolumeConfirmedTrendLiveApprovalFailure.RECEIPT_NOT_APPROVED) shouldBe true
            gateway.submittedOrders.size shouldBe 0
        }

        "revoked approval does not submit an exit when reduce-only execution is restricted" {
            val position = position(Side.BUY, "0.007")
            val gateway = FakeTrendLiveGateway(mutableListOf(position))
            gateway.positionProfileResponse = gateway.positionProfileResponse.copy(reduceOnlyRestricted = true)
            val store = InMemoryTrendLiveStore(state = openState(position))
            val service = service(gateway, store, approved = false)

            val result = service.reconcile()

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.HALTED
            result.state.haltedReasonCode shouldBe "TREND_APPROVAL_REVOKED_EXIT_CONTRACT_UNAVAILABLE"
            gateway.submittedOrders.size shouldBe 0
        }

        "foreign manual order does not block an approval-revocation exit" {
            val position = position(Side.SELL, "0.004")
            val gateway = FakeTrendLiveGateway(mutableListOf(position))
            gateway.openOrders += foreignOpenOrder()
            val store = InMemoryTrendLiveStore(state = openState(position))
            val service = service(gateway, store, approved = false)

            val result = service.reconcile()

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.ORDER_SUBMITTED
            gateway.submittedOrders.single().apply {
                side shouldBe Side.BUY
                reduceOnly shouldBe true
            }
        }

        "repeated safety halt with unchanged state is persisted once" {
            val gateway = FakeTrendLiveGateway()
            val store = InMemoryTrendLiveStore()
            val service = service(gateway, store)

            service.haltForSafety("TREND_TEST_HALT")
            service.haltForSafety("TREND_TEST_HALT")

            store.events.map { it.type } shouldBe listOf(VolumeConfirmedTrendLiveEventType.HALTED)
        }

        "safety halt submits a reduce-only exit for an owned position" {
            val ownedPosition = position(Side.BUY, "0.007")
            val gateway = FakeTrendLiveGateway(mutableListOf(ownedPosition))
            val store = InMemoryTrendLiveStore(state = openState(ownedPosition))
            val service = service(gateway, store)

            val result = service.haltForSafety("TREND_SIGNAL_FROM_FUTURE")

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.ORDER_SUBMITTED
            result.state.status shouldBe VolumeConfirmedTrendLiveStatus.EXIT_SUBMITTED
            result.plan?.reasonCode shouldBe
                "$TREND_SAFETY_HALT_EXIT_REASON_CODE_PREFIX|TREND_SIGNAL_FROM_FUTURE"
            gateway.submittedOrders.single().apply {
                side shouldBe Side.SELL
                quantity shouldBe BigDecimal("0.007")
                reduceOnly shouldBe true
            }
        }

        "safety halt never exits a position without persisted ownership" {
            val gateway = FakeTrendLiveGateway(mutableListOf(position(Side.BUY, "0.007")))
            val store = InMemoryTrendLiveStore()
            val service = service(gateway, store)

            val result = service.haltForSafety("TREND_SHADOW_TARGET_SIGNAL_MISMATCH")
            val repeated = service.haltForSafety("TREND_SHADOW_TARGET_SIGNAL_MISMATCH")

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.HALTED
            repeated.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.HALTED
            result.state.haltedReasonCode shouldBe
                "TREND_SHADOW_TARGET_SIGNAL_MISMATCH|TREND_SAFETY_POSITION_OWNERSHIP_UNCONFIRMED"
            result.state.observedPositionSide shouldBe null
            result.state.observedPositionQuantity shouldBe null
            gateway.submittedOrders.size shouldBe 0
        }

        "halted reconciliation never adopts a newly observed unowned position" {
            val exchangePosition = position(Side.SELL, "0.004")
            val gateway = FakeTrendLiveGateway(mutableListOf(exchangePosition))
            val haltedWithoutOwnership =
                openState(exchangePosition).copy(
                    status = VolumeConfirmedTrendLiveStatus.HALTED,
                    observedPositionSide = null,
                    observedPositionQuantity = null,
                    haltedReasonCode = "TREND_TEST_HALT",
                )
            val store = InMemoryTrendLiveStore(state = haltedWithoutOwnership)
            val service = service(gateway, store)

            val reconciled = service.reconcile()
            val safetyCheck = service.haltForSafety("TREND_SIGNAL_FROM_FUTURE")

            reconciled.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.HALTED
            reconciled.state.haltedReasonCode shouldBe "TREND_HALTED_POSITION_OWNERSHIP_MISMATCH"
            reconciled.state.observedPositionSide shouldBe null
            reconciled.state.observedPositionQuantity shouldBe null
            safetyCheck.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.HALTED
            gateway.submittedOrders.size shouldBe 0
        }

        "safety halt does not order when reduce-only execution becomes restricted" {
            val ownedPosition = position(Side.BUY, "0.007")
            val gateway = FakeTrendLiveGateway(mutableListOf(ownedPosition))
            gateway.positionProfileResponse = gateway.positionProfileResponse.copy(reduceOnlyRestricted = true)
            val store = InMemoryTrendLiveStore(state = openState(ownedPosition))
            val service = service(gateway, store)

            val result = service.haltForSafety("TREND_SIGNAL_FROM_FUTURE")

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.HALTED
            result.state.haltedReasonCode shouldBe
                "TREND_SIGNAL_FROM_FUTURE|TREND_SAFETY_EXIT_CONTRACT_UNAVAILABLE"
            gateway.submittedOrders.size shouldBe 0
        }

        "contract mismatch exits an owned position when reduce-only execution remains safe" {
            val ownedPosition = position(Side.BUY, "0.007")
            val gateway = FakeTrendLiveGateway(mutableListOf(ownedPosition))
            gateway.positionProfileResponse =
                gateway.positionProfileResponse.copy(
                    buyLeverage = BigDecimal("2"),
                    sellLeverage = BigDecimal("2"),
                )
            val store = InMemoryTrendLiveStore(state = openState(ownedPosition))
            val service = service(gateway, store)

            val result = service.reconcile()

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.ORDER_SUBMITTED
            result.contractFailures shouldBe
                listOf(
                    VolumeConfirmedTrendExchangeContractFailure.BUY_LEVERAGE_NOT_ONE,
                    VolumeConfirmedTrendExchangeContractFailure.SELL_LEVERAGE_NOT_ONE,
                )
            result.plan?.reasonCode shouldBe
                "$TREND_SAFETY_HALT_EXIT_REASON_CODE_PREFIX|TREND_EXCHANGE_CONTRACT_MISMATCH"
            gateway.submittedOrders.single().apply {
                side shouldBe Side.SELL
                quantity shouldBe BigDecimal("0.007")
                reduceOnly shouldBe true
            }
        }

        "contract mismatch does not exit when the position mode is unsafe" {
            val ownedPosition = position(Side.BUY, "0.007")
            val gateway = FakeTrendLiveGateway(mutableListOf(ownedPosition))
            gateway.positionProfileResponse =
                gateway.positionProfileResponse.copy(positionMode = ExchangePositionMode.HEDGE)
            val store = InMemoryTrendLiveStore(state = openState(ownedPosition))
            val service = service(gateway, store)

            val result = service.reconcile()

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.HALTED
            result.state.haltedReasonCode shouldBe
                "TREND_EXCHANGE_CONTRACT_MISMATCH|TREND_SAFETY_EXIT_CONTRACT_UNAVAILABLE"
            result.contractFailures shouldBe
                listOf(VolumeConfirmedTrendExchangeContractFailure.POSITION_MODE_NOT_ONE_WAY)
            gateway.submittedOrders.size shouldBe 0
        }

        "persisted contract mismatch halt resumes an owned reduce-only exit" {
            val ownedPosition = position(Side.SELL, "0.004")
            val gateway = FakeTrendLiveGateway(mutableListOf(ownedPosition))
            gateway.positionProfileResponse =
                gateway.positionProfileResponse.copy(
                    buyLeverage = BigDecimal("2"),
                    sellLeverage = BigDecimal("2"),
                )
            val halted =
                openState(ownedPosition).copy(
                    status = VolumeConfirmedTrendLiveStatus.HALTED,
                    haltedReasonCode = "TREND_EXCHANGE_CONTRACT_MISMATCH",
                )
            val store = InMemoryTrendLiveStore(state = halted)
            val service = service(gateway, store)

            val result = service.reconcile()

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.ORDER_SUBMITTED
            gateway.submittedOrders.single().apply {
                side shouldBe Side.BUY
                quantity shouldBe BigDecimal("0.004")
                reduceOnly shouldBe true
            }
        }

        "contract mismatch still recovers a pending order before any safety action" {
            val gateway = FakeTrendLiveGateway()
            val store = InMemoryTrendLiveStore()
            val service = service(gateway, store)
            val submitted = service.evaluate(command(Side.BUY), BigDecimal("60000"))
            gateway.positionProfileResponse =
                gateway.positionProfileResponse.copy(
                    buyLeverage = BigDecimal("2"),
                    sellLeverage = BigDecimal("2"),
                )

            val result = service.reconcile()

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.RECOVERY_PENDING
            result.state.status shouldBe VolumeConfirmedTrendLiveStatus.ENTRY_SUBMITTED
            result.contractFailures shouldBe emptyList()
            gateway.submittedOrders.size shouldBe 1
            submitted.state.clientOrderId shouldBe result.state.clientOrderId
        }

        "pending order recovery runs before an exchange-contract read outage" {
            val gateway = FakeTrendLiveGateway()
            val store = InMemoryTrendLiveStore()
            val service = service(gateway, store)
            val submitted = service.evaluate(command(Side.BUY), BigDecimal("60000"))
            val clientOrderId = requireNotNull(submitted.state.clientOrderId)
            gateway.positions += position(Side.BUY, "0.007")
            gateway.executionFills += executionFill(clientOrderId, "execution-contract-outage-001")
            gateway.openOrders[0] =
                gateway.openOrders.single().copy(
                    status = OrderStatus.FILLED,
                    filledQuantity = BigDecimal("0.007"),
                    providerStatus = "Filled",
                )
            gateway.instrumentRulesFailure = IllegalStateException("injected instrument outage")

            val result = service.reconcile()

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.RECOVERED
            result.state.status shouldBe VolumeConfirmedTrendLiveStatus.OPEN
            result.state.lastExecutionId shouldBe "execution-contract-outage-001"
            gateway.submittedOrders.size shouldBe 1
        }

        "persisted contract halt exits before a due accounting outage" {
            val ownedPosition = position(Side.BUY, "0.007")
            val gateway = FakeTrendLiveGateway(mutableListOf(ownedPosition))
            gateway.positionProfileResponse =
                gateway.positionProfileResponse.copy(
                    buyLeverage = BigDecimal("2"),
                    sellLeverage = BigDecimal("2"),
                )
            gateway.accountTransactionsFailure = IllegalStateException("injected accounting outage")
            val halted =
                openState(ownedPosition).copy(
                    status = VolumeConfirmedTrendLiveStatus.HALTED,
                    haltedReasonCode = "TREND_EXCHANGE_CONTRACT_MISMATCH",
                )
            val accountingRequest =
                VolumeConfirmedTrendLiveAccountingRequest(
                    requestedAt = TEST_NOW,
                    closuresDue = false,
                    transactionsDue = true,
                    closureStartAt = null,
                    transactionStartAt = TEST_NOW.minusSeconds(3_600),
                )
            val projection = RecordingTrendLiveProjectionSink(accountingRequest = accountingRequest)
            val store = InMemoryTrendLiveStore(state = halted)
            val service = service(gateway, store, projectionSink = projection)

            val result = service.reconcile()

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.ORDER_SUBMITTED
            gateway.submittedOrders.single().reduceOnly shouldBe true
            gateway.accountTransactionRanges.size shouldBe 0
            projection.accountingFailures.size shouldBe 0
        }

        "safety halt retries a known unfilled exit only after its delay" {
            var now = TEST_NOW
            val ownedPosition = position(Side.BUY, "0.007")
            val gateway = FakeTrendLiveGateway(mutableListOf(ownedPosition))
            val store = InMemoryTrendLiveStore(state = openState(ownedPosition))
            val service = service(gateway, store, clock = { now })

            val submitted = service.haltForSafety("TREND_SIGNAL_FROM_FUTURE")
            gateway.openOrders[0] =
                gateway.openOrders.single().copy(
                    status = OrderStatus.CANCELLED,
                    providerStatus = "Cancelled",
                    cancelType = "UNKNOWN",
                )
            val notFilled = service.haltForSafety("TREND_SIGNAL_FROM_FUTURE")
            val waiting = service.haltForSafety("TREND_SIGNAL_FROM_FUTURE")
            now = now.plusSeconds(61)
            val retried = service.haltForSafety("TREND_SIGNAL_FROM_FUTURE")

            submitted.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.ORDER_SUBMITTED
            notFilled.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.ORDER_NOT_FILLED
            waiting.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.ORDER_NOT_FILLED
            retried.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.ORDER_SUBMITTED
            gateway.submittedOrders.size shouldBe 2
            gateway.submittedOrders
                .map { it.clientOrderId }
                .distinct()
                .size shouldBe 2
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

        "foreign USDT-settled position halts before a BTC entry" {
            val gateway =
                FakeTrendLiveGateway(
                    mutableListOf(position(Side.BUY, "0.2", symbol = Symbol("ETHUSDT"))),
                )
            val store = InMemoryTrendLiveStore()
            val service = service(gateway, store)

            val result = service.evaluate(command(Side.BUY), BigDecimal("60000"))

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.HALTED
            result.state.haltedReasonCode shouldBe "TREND_FOREIGN_POSITION_OBSERVED"
            gateway.submittedOrders.size shouldBe 0
        }

        "foreign position blocks entries without stranding an owned BTC exit" {
            val ownedPosition = position(Side.BUY, "0.007")
            val gateway =
                FakeTrendLiveGateway(
                    mutableListOf(
                        ownedPosition,
                        position(Side.BUY, "0.2", symbol = Symbol("ETHUSDT")),
                    ),
                )
            val store = InMemoryTrendLiveStore(state = openState(ownedPosition))
            val service = service(gateway, store)

            val blocked = service.evaluate(command(Side.BUY), BigDecimal("60000"))
            val close = service.evaluate(command(Side.SELL), BigDecimal("60000"))

            blocked.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.RISK_BLOCKED
            blocked.state.status shouldBe VolumeConfirmedTrendLiveStatus.OPEN
            blocked.riskReasonCodes shouldBe listOf("TREND_FOREIGN_POSITION_OBSERVED")
            close.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.ORDER_SUBMITTED
            gateway.submittedOrders.single().apply {
                symbol shouldBe Symbol("BTCUSDT")
                side shouldBe Side.SELL
                quantity shouldBe BigDecimal("0.007")
                reduceOnly shouldBe true
            }
        }

        "unowned USDT-settled order halts before flat initialization or a BTC entry" {
            val gateway = FakeTrendLiveGateway()
            gateway.openOrders +=
                ExchangeOpenOrder(
                    exchangeOrderId = "foreign-order-001",
                    clientOrderId = "manual-eth-order",
                    symbol = Symbol("ETHUSDT"),
                    side = Side.BUY,
                    orderType = OrderType.LIMIT,
                    status = OrderStatus.SUBMITTED,
                    quantity = BigDecimal("0.2"),
                    createdAt = TEST_NOW,
                    reduceOnly = false,
                )
            val store = InMemoryTrendLiveStore()
            val service = service(gateway, store)

            val result = service.reconcile()

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.HALTED
            result.state.haltedReasonCode shouldBe "TREND_UNOWNED_OPEN_ORDER_OBSERVED"
            gateway.submittedOrders.size shouldBe 0
        }

        "foreign order does not block an owned BTC reduce-only exit" {
            val ownedPosition = position(Side.SELL, "0.004")
            val gateway = FakeTrendLiveGateway(mutableListOf(ownedPosition))
            gateway.openOrders += foreignOpenOrder()
            val store = InMemoryTrendLiveStore(state = openState(ownedPosition))
            val service = service(gateway, store)

            val result = service.evaluate(command(Side.BUY), BigDecimal("60000"))

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.ORDER_SUBMITTED
            gateway.submittedOrders.single().apply {
                side shouldBe Side.BUY
                quantity shouldBe BigDecimal("0.004")
                reduceOnly shouldBe true
            }
        }

        "unresolved owned order remains a hard halt to prevent duplicate exits" {
            val ownedPosition = position(Side.BUY, "0.007")
            val gateway = FakeTrendLiveGateway(mutableListOf(ownedPosition))
            gateway.openOrders += foreignOpenOrder(clientOrderId = "vct-x-s-orphan")
            val store = InMemoryTrendLiveStore(state = openState(ownedPosition))
            val service = service(gateway, store)

            val result = service.evaluate(command(Side.SELL), BigDecimal("60000"))

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.HALTED
            result.state.haltedReasonCode shouldBe "TREND_UNRESOLVED_OWNED_OPEN_ORDER_OBSERVED"
            gateway.submittedOrders.size shouldBe 0
        }

        "legacy inventory halt resumes owned position management" {
            val ownedPosition = position(Side.BUY, "0.007")
            val gateway =
                FakeTrendLiveGateway(
                    mutableListOf(
                        ownedPosition,
                        position(Side.SELL, "0.2", symbol = Symbol("ETHUSDT")),
                    ),
                )
            val haltedState =
                openState(ownedPosition).copy(
                    status = VolumeConfirmedTrendLiveStatus.HALTED,
                    haltedReasonCode = "TREND_FOREIGN_POSITION_OBSERVED",
                )
            val store = InMemoryTrendLiveStore(state = haltedState)
            val service = service(gateway, store)

            val resumed = service.reconcile()
            val close = service.evaluate(command(Side.SELL), BigDecimal("60000"))

            resumed.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.RISK_BLOCKED
            resumed.state.status shouldBe VolumeConfirmedTrendLiveStatus.OPEN
            resumed.riskReasonCodes shouldBe listOf("TREND_FOREIGN_POSITION_OBSERVED")
            store.events.first().type shouldBe VolumeConfirmedTrendLiveEventType.RESUMED
            close.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.ORDER_SUBMITTED
            gateway.submittedOrders.single().reduceOnly shouldBe true
        }

        "legacy inventory halt keeps an independent account risk block" {
            val ownedPosition = position(Side.BUY, "0.007")
            val gateway = FakeTrendLiveGateway(mutableListOf(ownedPosition))
            val haltedState =
                openState(ownedPosition).copy(
                    status = VolumeConfirmedTrendLiveStatus.HALTED,
                    haltedReasonCode = "TREND_FOREIGN_POSITION_OBSERVED",
                )
            val store = InMemoryTrendLiveStore(state = haltedState)
            val projection =
                RecordingTrendLiveProjectionSink(
                    riskReasonCodes = listOf("ACCOUNT_DRAWDOWN_LIMIT_REACHED"),
                )
            val service = service(gateway, store, projectionSink = projection)

            val resumed = service.reconcile()

            resumed.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.RISK_BLOCKED
            resumed.state.status shouldBe VolumeConfirmedTrendLiveStatus.OPEN
            resumed.riskReasonCodes shouldBe listOf("ACCOUNT_DRAWDOWN_LIMIT_REACHED")
            gateway.submittedOrders.size shouldBe 0
        }

        "foreign collateral halts before flat initialization" {
            val gateway = FakeTrendLiveGateway()
            gateway.balance =
                isolatedAccountBalance(
                    additionalCoins = listOf(coinBalance("USDC", "25")),
                )
            val store = InMemoryTrendLiveStore()
            val service = service(gateway, store)

            val result = service.reconcile()

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.HALTED
            result.state.haltedReasonCode shouldBe "TREND_FOREIGN_COLLATERAL_OBSERVED"
            gateway.submittedOrders.size shouldBe 0
        }

        "borrowed settlement balance halts before entry" {
            val gateway = FakeTrendLiveGateway()
            gateway.balance =
                isolatedAccountBalance().copy(
                    coins =
                        listOf(
                            coinBalance("USDT", "660").copy(
                                borrowAmount = BigDecimal("10"),
                                spotBorrow = BigDecimal("10"),
                            ),
                        ),
                )
            val store = InMemoryTrendLiveStore()
            val service = service(gateway, store)

            val result = service.evaluate(command(Side.BUY), BigDecimal("60000"))

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.HALTED
            result.state.haltedReasonCode shouldBe "TREND_ACCOUNT_LIABILITY_OBSERVED"
            gateway.submittedOrders.size shouldBe 0
        }

        "entry sizing uses settlement equity instead of account-wide total equity" {
            val gateway = FakeTrendLiveGateway()
            gateway.balance =
                isolatedAccountBalance(
                    settlementEquity = "100",
                    totalEquity = "1000",
                    availableBalance = "1000",
                )
            val store = InMemoryTrendLiveStore()
            val service = service(gateway, store)

            val result = service.evaluate(command(Side.BUY), BigDecimal("60000"))

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.ORDER_SUBMITTED
            gateway.submittedOrders.single().quantity shouldBe BigDecimal("0.001")
        }

        "approved reconciliation periodically records exchange equity" {
            val gateway = FakeTrendLiveGateway()
            val store = InMemoryTrendLiveStore()
            val projection = RecordingTrendLiveProjectionSink(accountSnapshotDue = true)
            val service = service(gateway, store, projectionSink = projection)

            service.reconcile()

            projection.balances.single().totalEquity shouldBe BigDecimal("660")
        }

        "due accounting reads executions closures and the bounded transaction range once" {
            val gateway = FakeTrendLiveGateway()
            val store = InMemoryTrendLiveStore()
            val request =
                VolumeConfirmedTrendLiveAccountingRequest(
                    requestedAt = TEST_NOW,
                    closuresDue = true,
                    transactionsDue = true,
                    closureStartAt = TEST_NOW.minusSeconds(3_600),
                    transactionStartAt = TEST_NOW.minusSeconds(3_600),
                )
            val projection = RecordingTrendLiveProjectionSink(accountingRequest = request)
            val service = service(gateway, store, projectionSink = projection)
            gateway.executionFills += executionFill("vct-entry-test", "execution-accounting-001")
            gateway.closedPnlRecords += closedPnl("vct-exit-test")
            gateway.accountTransactionRecords += accountTransaction("transaction-accounting-001")

            service.reconcile()

            projection.accountingObservations.single().apply {
                executions.mapNotNull(ExchangeExecutionFill::executionId) shouldBe listOf("execution-accounting-001")
                closedPnls.mapNotNull(ExchangeClosedPnl::clientOrderId) shouldBe listOf("vct-exit-test")
                accountTransactions.map(ExchangeAccountTransaction::transactionId) shouldBe
                    listOf("transaction-accounting-001")
            }
            gateway.accountTransactionRanges.single() shouldBe
                (TEST_NOW.minusSeconds(3_600) to TEST_NOW)
            gateway.executionRanges.single() shouldBe
                (TEST_NOW.minusSeconds(3_600) to TEST_NOW)
            gateway.closedPnlRanges.single() shouldBe
                (TEST_NOW.minusSeconds(3_600) to TEST_NOW)
        }

        "entry recovery records exact fills and their latest execution id" {
            val gateway = FakeTrendLiveGateway()
            val store = InMemoryTrendLiveStore()
            val projection = RecordingTrendLiveProjectionSink()
            val service = service(gateway, store, projectionSink = projection)

            val submitted = service.evaluate(command(Side.BUY), BigDecimal("60000"))
            val clientOrderId = requireNotNull(submitted.state.clientOrderId)
            gateway.positions += position(Side.BUY, "0.007")
            gateway.executionFills += executionFill(clientOrderId, "execution-001")
            gateway.openOrders[0] =
                gateway.openOrders.single().copy(
                    status = OrderStatus.FILLED,
                    filledQuantity = BigDecimal("0.007"),
                    providerStatus = "Filled",
                )

            val recovered = service.reconcile()

            recovered.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.RECOVERED
            recovered.state.lastExecutionId shouldBe "execution-001"
            projection.fills.single().executionId shouldBe "execution-001"
            gateway.executionRanges.single() shouldBe
                (submitted.state.updatedAt.minusSeconds(300) to TEST_NOW)
        }

        "entry recovery requests the full persisted range after a long shutdown" {
            val gateway = FakeTrendLiveGateway()
            val store = InMemoryTrendLiveStore()
            var now = TEST_NOW
            val service = service(gateway, store, clock = { now })

            val submitted = service.evaluate(command(Side.BUY), BigDecimal("60000"))
            val clientOrderId = requireNotNull(submitted.state.clientOrderId)
            gateway.positions += position(Side.BUY, "0.007")
            gateway.executionFills += executionFill(clientOrderId, "execution-after-downtime")
            gateway.openOrders[0] =
                gateway.openOrders.single().copy(
                    status = OrderStatus.FILLED,
                    filledQuantity = BigDecimal("0.007"),
                    providerStatus = "Filled",
                )
            now = now.plusSeconds(10L * 24L * 60L * 60L)

            val recovered = service.reconcile()

            recovered.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.RECOVERED
            gateway.executionRanges.single() shouldBe
                (submitted.state.updatedAt.minusSeconds(300) to now)
        }

        "entry recovery does not adopt a position larger than its exact fill evidence" {
            val gateway = FakeTrendLiveGateway()
            val store = InMemoryTrendLiveStore()
            var now = Instant.parse("2026-08-07T00:00:10Z")
            val service = service(gateway, store, clock = { now })

            val submitted = service.evaluate(command(Side.BUY), BigDecimal("60000"))
            val clientOrderId = requireNotNull(submitted.state.clientOrderId)
            gateway.positions += position(Side.BUY, "0.007")
            gateway.executionFills += executionFill(clientOrderId, "execution-entry-quantity-mismatch", quantity = "0.003")
            gateway.openOrders[0] =
                gateway.openOrders.single().copy(
                    status = OrderStatus.FILLED,
                    filledQuantity = BigDecimal("0.003"),
                    providerStatus = "Filled",
                )
            now = now.plusSeconds(11)

            val result = service.reconcile()
            val safetyCheck = service.haltForSafety("TREND_SIGNAL_FROM_FUTURE")

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.HALTED
            result.state.haltedReasonCode shouldBe "TREND_ENTRY_POSITION_QUANTITY_MISMATCH"
            result.state.observedPositionSide shouldBe null
            result.state.observedPositionQuantity shouldBe null
            safetyCheck.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.HALTED
            gateway.submittedOrders.size shouldBe 1
        }

        "entry recovery rejects inconsistent order and execution fill quantities" {
            val gateway = FakeTrendLiveGateway()
            val store = InMemoryTrendLiveStore()
            var now = TEST_NOW
            val service = service(gateway, store, clock = { now })

            val submitted = service.evaluate(command(Side.BUY), BigDecimal("60000"))
            val clientOrderId = requireNotNull(submitted.state.clientOrderId)
            gateway.positions += position(Side.BUY, "0.003")
            gateway.executionFills += executionFill(clientOrderId, "execution-entry-evidence-mismatch", quantity = "0.003")
            gateway.openOrders[0] =
                gateway.openOrders.single().copy(
                    status = OrderStatus.FILLED,
                    filledQuantity = BigDecimal("0.004"),
                    providerStatus = "Filled",
                )
            now = now.plusSeconds(11)

            val result = service.reconcile()

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.HALTED
            result.state.haltedReasonCode shouldBe "TREND_ENTRY_FILL_QUANTITY_EVIDENCE_MISMATCH"
            result.state.observedPositionSide shouldBe null
            result.state.observedPositionQuantity shouldBe null
            gateway.submittedOrders.size shouldBe 1
        }

        "entry recovery rejects a zero order fill that conflicts with an execution" {
            val gateway = FakeTrendLiveGateway()
            val store = InMemoryTrendLiveStore()
            var now = TEST_NOW
            val service = service(gateway, store, clock = { now })

            val submitted = service.evaluate(command(Side.BUY), BigDecimal("60000"))
            val clientOrderId = requireNotNull(submitted.state.clientOrderId)
            gateway.positions += position(Side.BUY, "0.003")
            gateway.executionFills += executionFill(clientOrderId, "execution-entry-zero-order-fill", quantity = "0.003")
            gateway.openOrders[0] =
                gateway.openOrders.single().copy(
                    status = OrderStatus.CANCELLED,
                    filledQuantity = BigDecimal.ZERO,
                    providerStatus = "Cancelled",
                )
            now = now.plusSeconds(11)

            val result = service.reconcile()

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.HALTED
            result.state.haltedReasonCode shouldBe "TREND_ENTRY_FILL_QUANTITY_EVIDENCE_MISMATCH"
            result.state.observedPositionSide shouldBe null
            result.state.observedPositionQuantity shouldBe null
            gateway.submittedOrders.size shouldBe 1
        }

        "partial entry cancels its active remainder before becoming open" {
            val gateway = FakeTrendLiveGateway()
            val store = InMemoryTrendLiveStore()
            var now = Instant.parse("2026-08-07T00:00:10Z")
            val service = service(gateway, store, clock = { now })

            val submitted = service.evaluate(command(Side.BUY), BigDecimal("60000"))
            val clientOrderId = requireNotNull(submitted.state.clientOrderId)
            gateway.positions += position(Side.BUY, "0.003")
            gateway.executionFills += executionFill(clientOrderId, "execution-partial-001", quantity = "0.003")
            gateway.openOrders[0] =
                gateway.openOrders.single().copy(
                    status = OrderStatus.PARTIALLY_FILLED,
                    filledQuantity = BigDecimal("0.003"),
                    providerStatus = "PartiallyFilled",
                )
            now = now.plusSeconds(11)

            val cancellationPending = service.reconcile()

            cancellationPending.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.RECOVERY_PENDING
            cancellationPending.state.status shouldBe VolumeConfirmedTrendLiveStatus.ENTRY_SUBMITTED
            cancellationPending.state.observedPositionSide shouldBe null
            cancellationPending.state.observedPositionQuantity shouldBe null
            gateway.cancelRequests.single().clientOrderId shouldBe clientOrderId
            gateway.openOrders[0] =
                gateway.openOrders.single().copy(
                    status = OrderStatus.CANCELLED,
                    providerStatus = "PartiallyFilledCanceled",
                )
            now = now.plusSeconds(1)
            val recovered = service.reconcile()

            recovered.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.RECOVERED
            recovered.state.observedPositionQuantity shouldBe BigDecimal("0.003")
        }

        "projection write failure leaves the submitted checkpoint recoverable" {
            val gateway = FakeTrendLiveGateway()
            val store = InMemoryTrendLiveStore()
            val projection = RecordingTrendLiveProjectionSink(failExecutionRecordingOnce = true)
            val service = service(gateway, store, projectionSink = projection)

            val submitted = service.evaluate(command(Side.BUY), BigDecimal("60000"))
            val clientOrderId = requireNotNull(submitted.state.clientOrderId)
            gateway.positions += position(Side.BUY, "0.007")
            gateway.executionFills += executionFill(clientOrderId, "execution-retry-001")
            gateway.openOrders[0] =
                gateway.openOrders.single().copy(
                    status = OrderStatus.FILLED,
                    filledQuantity = BigDecimal("0.007"),
                    providerStatus = "Filled",
                )

            shouldThrow<IllegalStateException> { service.reconcile() }
            store.state?.status shouldBe VolumeConfirmedTrendLiveStatus.ENTRY_SUBMITTED
            val recovered = service.reconcile()

            recovered.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.RECOVERED
            recovered.state.lastExecutionId shouldBe "execution-retry-001"
            projection.fills.single().executionId shouldBe "execution-retry-001"
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

        "risk failure blocks a new entry without submitting an exchange order" {
            val gateway = FakeTrendLiveGateway()
            val store = InMemoryTrendLiveStore()
            val projection =
                RecordingTrendLiveProjectionSink(
                    riskReasonCodes = listOf("ACCOUNT_DRAWDOWN_LIMIT_REACHED"),
                )
            val service = service(gateway, store, projectionSink = projection)

            val result = service.evaluate(command(Side.BUY), BigDecimal("60000"))

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.RISK_BLOCKED
            result.state.status shouldBe VolumeConfirmedTrendLiveStatus.FLAT
            result.riskReasonCodes shouldBe listOf("ACCOUNT_DRAWDOWN_LIMIT_REACHED")
            gateway.submittedOrders.size shouldBe 0
            store.events.single().reasonCode shouldBe
                "TREND_ENTRY_RISK_BLOCKED|ACCOUNT_DRAWDOWN_LIMIT_REACHED"
        }

        "intent persistence failure submits no exchange order" {
            val gateway = FakeTrendLiveGateway()
            val store = InMemoryTrendLiveStore(failOnCommitNumber = 1)
            val service = service(gateway, store)

            shouldThrow<IllegalStateException> {
                service.evaluate(command(Side.BUY), BigDecimal("60000"))
            }

            gateway.submittedOrders.size shouldBe 0
            store.state shouldBe null
        }

        "ambiguous place-order failure never retries the recorded intent blindly" {
            val gateway = FakeTrendLiveGateway()
            val store = InMemoryTrendLiveStore()
            var now = Instant.parse("2026-08-07T00:00:10Z")
            val service = service(gateway, store, clock = { now })
            gateway.beforePlaceOrder = { throw IllegalStateException("injected ambiguous transport failure") }

            shouldThrow<IllegalStateException> {
                service.evaluate(command(Side.BUY), BigDecimal("60000"))
            }
            gateway.beforePlaceOrder = {}
            service.evaluate(command(Side.BUY), BigDecimal("60000")).status shouldBe
                VolumeConfirmedTrendLiveEvaluationStatus.RECOVERY_PENDING
            now = now.plusSeconds(11)
            val halted = service.evaluate(command(Side.BUY), BigDecimal("60000"))

            halted.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.HALTED
            halted.state.haltedReasonCode shouldBe "TREND_ENTRY_ORDER_STATE_UNKNOWN"
            gateway.submittedOrders.size shouldBe 0
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

        "pending order cancellation does not wait for a risk projection update" {
            var now = Instant.parse("2026-08-07T00:00:10Z")
            val gateway = FakeTrendLiveGateway()
            val store = InMemoryTrendLiveStore()
            val projection = RecordingTrendLiveProjectionSink()
            val service = service(gateway, store, projectionSink = projection, clock = { now })

            val submitted = service.evaluate(command(Side.BUY), BigDecimal("60000"))
            projection.riskReasonCodes = listOf("ACCOUNT_LEDGER_MISMATCH_PENDING")
            now = now.plusSeconds(11)

            val cancellationPending = service.reconcile()

            submitted.state.status shouldBe VolumeConfirmedTrendLiveStatus.ENTRY_SUBMITTED
            cancellationPending.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.RECOVERY_PENDING
            cancellationPending.state.riskReasonCodes shouldBe emptyList()
            cancellationPending.recoveryReasonCode shouldBe TREND_ACTIVE_ORDER_CANCEL_REQUESTED_REASON_CODE
            gateway.cancelRequests.size shouldBe 1
            gateway.submittedOrders.size shouldBe 1
        }

        "submitted active order remains pending before timeout without a false recovery event" {
            val gateway = FakeTrendLiveGateway()
            val store = InMemoryTrendLiveStore()
            var now = Instant.parse("2026-08-07T00:00:10Z")
            val service = service(gateway, store, clock = { now })

            service.evaluate(command(Side.BUY), BigDecimal("60000"))
            val eventCountAfterSubmission = store.events.size
            now = now.plusSeconds(1)

            val pending = service.reconcile()

            pending.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.RECOVERY_PENDING
            pending.recoveryReasonCode shouldBe null
            store.events.size shouldBe eventCountAfterSubmission
            gateway.cancelRequests.size shouldBe 0
        }

        "unknown provider entry status still cancels the internally active exact order" {
            val gateway = FakeTrendLiveGateway()
            val store = InMemoryTrendLiveStore()
            var now = Instant.parse("2026-08-07T00:00:10Z")
            val service = service(gateway, store, clock = { now })

            val submitted = service.evaluate(command(Side.BUY), BigDecimal("60000"))
            val clientOrderId = requireNotNull(submitted.state.clientOrderId)
            gateway.openOrders[0] =
                gateway.openOrders.single().copy(
                    status = OrderStatus.SUBMITTED,
                    providerStatus = "FutureActiveStatus",
                )
            now = now.plusSeconds(11)

            val cancellationPending = service.reconcile()

            cancellationPending.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.RECOVERY_PENDING
            cancellationPending.state.status shouldBe VolumeConfirmedTrendLiveStatus.ENTRY_SUBMITTED
            cancellationPending.state.haltedReasonCode shouldBe null
            gateway.cancelRequests.single().clientOrderId shouldBe clientOrderId
        }

        "unknown provider exit status still cancels the internally active exact order" {
            val openPosition = position(Side.BUY, "0.007")
            val gateway = FakeTrendLiveGateway(positions = mutableListOf(openPosition))
            val store = InMemoryTrendLiveStore(state = openState(openPosition))
            var now = Instant.parse("2026-08-07T00:00:10Z")
            val service = service(gateway, store, clock = { now })

            val submitted = service.evaluate(command(Side.SELL), BigDecimal("60000"))
            val clientOrderId = requireNotNull(submitted.state.clientOrderId)
            gateway.openOrders[0] =
                gateway.openOrders.single().copy(
                    status = OrderStatus.SUBMITTED,
                    providerStatus = "FutureActiveStatus",
                )
            now = now.plusSeconds(11)

            val cancellationPending = service.reconcile()

            cancellationPending.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.RECOVERY_PENDING
            cancellationPending.state.status shouldBe VolumeConfirmedTrendLiveStatus.EXIT_SUBMITTED
            cancellationPending.state.haltedReasonCode shouldBe null
            gateway.cancelRequests.single().clientOrderId shouldBe clientOrderId
        }

        "active order halts when cancellation is still unconfirmed after another timeout" {
            val gateway = FakeTrendLiveGateway()
            val store = InMemoryTrendLiveStore()
            var now = Instant.parse("2026-08-07T00:00:10Z")
            val service = service(gateway, store, clock = { now })

            service.evaluate(command(Side.BUY), BigDecimal("60000"))
            now = now.plusSeconds(11)
            val cancellationPending = service.reconcile()
            now = now.plusSeconds(1)
            val awaitingReadback = service.reconcile()
            now = now.plusSeconds(10)

            val halted = service.reconcile()

            cancellationPending.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.RECOVERY_PENDING
            awaitingReadback.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.RECOVERY_PENDING
            awaitingReadback.recoveryReasonCode shouldBe TREND_ACTIVE_ORDER_CANCEL_REQUESTED_REASON_CODE
            halted.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.HALTED
            halted.state.haltedReasonCode shouldBe
                "TREND_ENTRY_IOC_REMAINS_ACTIVE|TREND_ACTIVE_ORDER_CANCEL_UNCONFIRMED"
            gateway.cancelRequests.size shouldBe 1
        }

        "flat exit cancels its still-active reduce-only remainder before becoming flat" {
            val openPosition = position(Side.BUY, "0.007")
            val gateway = FakeTrendLiveGateway(positions = mutableListOf(openPosition))
            val store = InMemoryTrendLiveStore(state = openState(openPosition))
            var now = Instant.parse("2026-08-07T00:00:10Z")
            val service = service(gateway, store, clock = { now })

            val submitted = service.evaluate(command(Side.SELL), BigDecimal("60000"))
            val clientOrderId = requireNotNull(submitted.state.clientOrderId)
            gateway.positions.clear()
            gateway.executionFills += executionFill(clientOrderId, "execution-exit-partial-001", quantity = "0.007")
            now = now.plusSeconds(11)

            val cancellationPending = service.reconcile()

            cancellationPending.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.RECOVERY_PENDING
            cancellationPending.state.status shouldBe VolumeConfirmedTrendLiveStatus.EXIT_SUBMITTED
            gateway.cancelRequests.single().clientOrderId shouldBe clientOrderId
            gateway.openOrders[0] =
                gateway.openOrders.single().copy(
                    status = OrderStatus.CANCELLED,
                    filledQuantity = BigDecimal("0.007"),
                    providerStatus = "PartiallyFilledCanceled",
                )
            now = now.plusSeconds(1)

            val recovered = service.reconcile()

            recovered.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.RECOVERED
            recovered.state.status shouldBe VolumeConfirmedTrendLiveStatus.FLAT
        }

        "partial exit updates the owned remainder only when exact fill evidence matches" {
            val openPosition = position(Side.BUY, "0.007")
            val gateway = FakeTrendLiveGateway(positions = mutableListOf(openPosition))
            val store = InMemoryTrendLiveStore(state = openState(openPosition))
            val service = service(gateway, store)

            val submitted = service.evaluate(command(Side.SELL), BigDecimal("60000"))
            val clientOrderId = requireNotNull(submitted.state.clientOrderId)
            gateway.positions[0] = position(Side.BUY, "0.004")
            gateway.executionFills += executionFill(clientOrderId, "execution-exit-quantity-proven", quantity = "0.003")
            gateway.openOrders[0] =
                gateway.openOrders.single().copy(
                    status = OrderStatus.CANCELLED,
                    filledQuantity = BigDecimal("0.003"),
                    providerStatus = "PartiallyFilledCanceled",
                )

            val result = service.reconcile()

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.ORDER_NOT_FILLED
            result.state.status shouldBe VolumeConfirmedTrendLiveStatus.EXIT_NOT_FILLED
            result.state.observedPositionSide shouldBe Side.BUY
            result.state.observedPositionQuantity shouldBe BigDecimal("0.004")
            gateway.submittedOrders.size shouldBe 1
        }

        "partial exit does not replace ownership quantity without exact fill evidence" {
            val openPosition = position(Side.BUY, "0.007")
            val gateway = FakeTrendLiveGateway(positions = mutableListOf(openPosition))
            val store = InMemoryTrendLiveStore(state = openState(openPosition))
            var now = Instant.parse("2026-08-07T00:00:10Z")
            val service = service(gateway, store, clock = { now })

            service.evaluate(command(Side.SELL), BigDecimal("60000"))
            gateway.positions[0] = position(Side.BUY, "0.004")
            gateway.openOrders[0] =
                gateway.openOrders.single().copy(
                    status = OrderStatus.CANCELLED,
                    filledQuantity = BigDecimal.ZERO,
                    providerStatus = "Cancelled",
                )
            now = now.plusSeconds(11)

            val result = service.reconcile()

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.HALTED
            result.state.haltedReasonCode shouldBe "TREND_EXIT_FILL_QUANTITY_EVIDENCE_MISSING"
            result.state.observedPositionSide shouldBe Side.BUY
            result.state.observedPositionQuantity shouldBe BigDecimal("0.007")
            gateway.submittedOrders.size shouldBe 1
        }

        "a same-side position without exact order evidence is not adopted" {
            val gateway = FakeTrendLiveGateway()
            val store = InMemoryTrendLiveStore()
            var now = Instant.parse("2026-08-07T00:00:10Z")
            val service = service(gateway, store, clock = { now })

            service.evaluate(command(Side.BUY), BigDecimal("60000"))
            gateway.openOrders.clear()
            gateway.positions += position(Side.BUY, "0.007")
            now = now.plusSeconds(11)

            val result = service.reconcile()
            val safetyCheck = service.haltForSafety("TREND_SIGNAL_FROM_FUTURE")

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.HALTED
            result.state.haltedReasonCode shouldBe "TREND_ENTRY_POSITION_WITHOUT_ORDER_EVIDENCE"
            result.state.observedPositionSide shouldBe null
            result.state.observedPositionQuantity shouldBe null
            safetyCheck.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.HALTED
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
            gateway.executionFills += executionFill(requireNotNull(close.state.clientOrderId), "execution-exit-001")
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

        "risk failure never prevents an existing position from closing" {
            val openPosition = position(Side.BUY, "0.007")
            val gateway = FakeTrendLiveGateway(positions = mutableListOf(openPosition))
            val store = InMemoryTrendLiveStore(state = openState(openPosition))
            val projection =
                RecordingTrendLiveProjectionSink(
                    riskReasonCodes = listOf("ACCOUNT_DRAWDOWN_LIMIT_REACHED"),
                )
            val service = service(gateway, store, projectionSink = projection)

            val result = service.evaluate(command(Side.SELL), BigDecimal("60000"))

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.ORDER_SUBMITTED
            result.state.status shouldBe VolumeConfirmedTrendLiveStatus.EXIT_SUBMITTED
            gateway.submittedOrders.single().apply {
                side shouldBe Side.SELL
                reduceOnly shouldBe true
            }
        }

        "accounting outage never prevents an existing position from closing" {
            val openPosition = position(Side.BUY, "0.007")
            val gateway = FakeTrendLiveGateway(positions = mutableListOf(openPosition))
            gateway.accountTransactionsFailure = IllegalStateException("injected accounting outage")
            val accountingRequest =
                VolumeConfirmedTrendLiveAccountingRequest(
                    requestedAt = TEST_NOW,
                    closuresDue = false,
                    transactionsDue = true,
                    closureStartAt = null,
                    transactionStartAt = TEST_NOW.minusSeconds(3_600),
                )
            val projection = RecordingTrendLiveProjectionSink(accountingRequest = accountingRequest)
            val store = InMemoryTrendLiveStore(state = openState(openPosition))
            val service = service(gateway, store, projectionSink = projection)

            val result = service.evaluate(command(Side.SELL), BigDecimal("60000"))

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.ORDER_SUBMITTED
            gateway.submittedOrders.single().reduceOnly shouldBe true
            gateway.accountTransactionRanges.size shouldBe 0
            projection.accountingFailures.size shouldBe 0
        }

        "entry-account contract outage never prevents an existing position from closing" {
            val openPosition = position(Side.SELL, "0.004")
            val gateway = FakeTrendLiveGateway(positions = mutableListOf(openPosition))
            gateway.accountExecutionProfileFailure = IllegalStateException("injected account-profile outage")
            val store = InMemoryTrendLiveStore(state = openState(openPosition))
            val service = service(gateway, store)

            val result = service.evaluate(command(Side.BUY), BigDecimal("60000"))

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.ORDER_SUBMITTED
            gateway.submittedOrders.single().apply {
                side shouldBe Side.BUY
                reduceOnly shouldBe true
            }
        }

        "opposite signal does not exit when reduce-only execution is restricted" {
            val openPosition = position(Side.BUY, "0.007")
            val gateway = FakeTrendLiveGateway(positions = mutableListOf(openPosition))
            gateway.positionProfileResponse = gateway.positionProfileResponse.copy(reduceOnlyRestricted = true)
            val store = InMemoryTrendLiveStore(state = openState(openPosition))
            val service = service(gateway, store)

            val result = service.evaluate(command(Side.SELL), BigDecimal("60000"))

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.HALTED
            result.state.haltedReasonCode shouldBe "TREND_POSITION_EXIT_CONTRACT_UNAVAILABLE"
            gateway.submittedOrders.size shouldBe 0
        }

        "opposite signal never exits a position from a disabled state without ownership evidence" {
            val exchangePosition = position(Side.BUY, "0.007")
            val gateway = FakeTrendLiveGateway(positions = mutableListOf(exchangePosition))
            val disabled =
                openState(exchangePosition).copy(
                    status = VolumeConfirmedTrendLiveStatus.DISABLED,
                    approvalId = null,
                    observedPositionSide = null,
                    observedPositionQuantity = null,
                )
            val store = InMemoryTrendLiveStore(state = disabled)
            val service = service(gateway, store)

            val result = service.evaluate(command(Side.SELL), BigDecimal("60000"))

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.HALTED
            result.state.haltedReasonCode shouldBe "TREND_UNOWNED_POSITION_OBSERVED"
            gateway.submittedOrders.size shouldBe 0
        }

        "account isolation failure never prevents an existing position from closing" {
            val openPosition = position(Side.BUY, "0.007")
            val gateway = FakeTrendLiveGateway(positions = mutableListOf(openPosition))
            gateway.balance =
                isolatedAccountBalance(
                    additionalCoins = listOf(coinBalance("USDC", "25")),
                )
            val store = InMemoryTrendLiveStore(state = openState(openPosition))
            val service = service(gateway, store)

            val result = service.evaluate(command(Side.SELL), BigDecimal("60000"))

            result.status shouldBe VolumeConfirmedTrendLiveEvaluationStatus.ORDER_SUBMITTED
            gateway.submittedOrders.single().reduceOnly shouldBe true
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
    val closedPnlRecords = mutableListOf<ExchangeClosedPnl>()
    val accountTransactionRecords = mutableListOf<ExchangeAccountTransaction>()
    val accountTransactionRanges = mutableListOf<Pair<Instant, Instant>>()
    val executionRanges = mutableListOf<Pair<Instant, Instant>>()
    val closedPnlRanges = mutableListOf<Pair<Instant, Instant>>()
    val submittedOrders = mutableListOf<ExchangeOrderRequest>()
    val cancelRequests = mutableListOf<ExchangeCancelRequest>()
    var exchangeReadCount = 0
    var beforePlaceOrder: suspend () -> Unit = {}
    var balance: ExchangeAccountBalance = isolatedAccountBalance()
    var accountExecutionProfileFailure: Throwable? = null
    var instrumentRulesFailure: Throwable? = null
    var accountTransactionsFailure: Throwable? = null
    var positionProfileResponse =
        ExchangePositionExecutionProfile(
            symbol = Symbol("BTCUSDT"),
            positionMode = ExchangePositionMode.ONE_WAY,
            buyLeverage = BigDecimal.ONE,
            sellLeverage = BigDecimal.ONE,
            observedPositionIndices = setOf(0),
            reduceOnlyRestricted = false,
        )

    override suspend fun accountExecutionProfile(): ExchangeAccountExecutionProfile {
        exchangeReadCount += 1
        accountExecutionProfileFailure?.let { throw it }
        return ExchangeAccountExecutionProfile(
            accountType = "UNIFIED",
            accountMode = ExchangeAccountMode.UNIFIED_2,
            unifiedMarginStatus = 5,
            marginMode = ExchangeMarginMode.CROSS,
            spotHedgingStatus = ExchangeSpotHedgingStatus.OFF,
            updatedAt = TEST_NOW,
        )
    }

    override suspend fun positionExecutionProfile(symbol: Symbol): ExchangePositionExecutionProfile {
        exchangeReadCount += 1
        return positionProfileResponse
    }

    override suspend fun instrumentRules(symbol: Symbol): ExchangeInstrumentRules {
        exchangeReadCount += 1
        instrumentRulesFailure?.let { throw it }
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

    override suspend fun cancelOrder(request: ExchangeCancelRequest): ExchangeCancelResult {
        cancelRequests += request
        return ExchangeCancelResult(request.exchangeOrderId, request.clientOrderId)
    }

    override suspend fun openOrders(symbol: Symbol): List<ExchangeOpenOrder> {
        exchangeReadCount += 1
        return openOrders.filter { it.symbol == symbol }
    }

    override suspend fun openOrdersBySettleCoin(settleCoin: String): List<ExchangeOpenOrder> {
        exchangeReadCount += 1
        return openOrders.filter { order ->
            order.status in setOf(OrderStatus.CREATED, OrderStatus.SUBMITTED, OrderStatus.PARTIALLY_FILLED)
        }
    }

    override suspend fun positions(symbol: Symbol): List<ExchangePosition> {
        exchangeReadCount += 1
        return positions.filter { it.symbol == symbol }
    }

    override suspend fun positionsBySettleCoin(settleCoin: String): List<ExchangePosition> {
        exchangeReadCount += 1
        return positions.toList()
    }

    override suspend fun executions(symbol: Symbol): List<ExchangeExecutionFill> {
        exchangeReadCount += 1
        return executionFills.toList()
    }

    override suspend fun executions(
        symbol: Symbol,
        startAt: Instant,
        endAt: Instant,
    ): List<ExchangeExecutionFill> {
        exchangeReadCount += 1
        executionRanges += startAt to endAt
        return executionFills.filter { fill -> fill.executedAt in startAt..endAt }
    }

    override suspend fun closedPnls(symbol: Symbol): List<ExchangeClosedPnl> {
        exchangeReadCount += 1
        return closedPnlRecords.toList()
    }

    override suspend fun closedPnls(
        symbol: Symbol,
        startAt: Instant,
        endAt: Instant,
    ): List<ExchangeClosedPnl> {
        exchangeReadCount += 1
        closedPnlRanges += startAt to endAt
        return closedPnlRecords.filter { closedPnl -> closedPnl.closedAt in startAt..endAt }
    }

    override suspend fun accountBalance(coin: String?): ExchangeAccountBalance {
        exchangeReadCount += 1
        return balance
    }

    override suspend fun accountTransactions(
        currency: String,
        startAt: Instant,
        endAt: Instant,
    ): List<ExchangeAccountTransaction> {
        exchangeReadCount += 1
        accountTransactionsFailure?.let { throw it }
        accountTransactionRanges += startAt to endAt
        return accountTransactionRecords.toList()
    }
}

private fun isolatedAccountBalance(
    settlementEquity: String = "660",
    totalEquity: String = settlementEquity,
    availableBalance: String = settlementEquity,
    additionalCoins: List<ExchangeCoinBalance> = emptyList(),
): ExchangeAccountBalance =
    ExchangeAccountBalance(
        accountType = "UNIFIED",
        totalEquity = BigDecimal(totalEquity),
        totalWalletBalance = BigDecimal(settlementEquity),
        totalMarginBalance = BigDecimal(settlementEquity),
        totalAvailableBalance = BigDecimal(availableBalance),
        totalPerpUnrealizedPnl = BigDecimal.ZERO,
        totalInitialMargin = BigDecimal.ZERO,
        totalMaintenanceMargin = BigDecimal.ZERO,
        coins =
            listOf(coinBalance("USDT", settlementEquity)) + additionalCoins,
        capturedAt = TEST_NOW,
    )

private fun coinBalance(
    coin: String,
    equity: String,
): ExchangeCoinBalance =
    ExchangeCoinBalance(
        coin = coin,
        equity = BigDecimal(equity),
        usdValue = BigDecimal(equity),
        walletBalance = BigDecimal(equity),
        locked = BigDecimal.ZERO,
        unrealizedPnl = BigDecimal.ZERO,
        cumulativeRealizedPnl = BigDecimal.ZERO,
        borrowAmount = BigDecimal.ZERO,
        spotBorrow = BigDecimal.ZERO,
        accruedInterest = BigDecimal.ZERO,
        spotHedgingQuantity = BigDecimal.ZERO,
        bonus = BigDecimal.ZERO,
    )

private fun foreignOpenOrder(clientOrderId: String = "manual-eth-order"): ExchangeOpenOrder =
    ExchangeOpenOrder(
        exchangeOrderId = "foreign-order-001",
        clientOrderId = clientOrderId,
        symbol = Symbol("ETHUSDT"),
        side = Side.BUY,
        orderType = OrderType.LIMIT,
        status = OrderStatus.SUBMITTED,
        quantity = BigDecimal("0.2"),
        createdAt = TEST_NOW,
        reduceOnly = false,
    )

private fun service(
    gateway: FakeTrendLiveGateway,
    store: InMemoryTrendLiveStore,
    approved: Boolean = true,
    clock: () -> Instant = { TEST_NOW },
    projectionSink: VolumeConfirmedTrendLiveProjectionSink = NoopVolumeConfirmedTrendLiveProjectionSink,
    approvalReportProvider: suspend () -> VolumeConfirmedTrendApprovalReport = { approvalReport() },
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
        approvalReportProvider = approvalReportProvider,
        shadowEvidenceSha256 = EVIDENCE_SHA,
        approvalReportSha256 = REPORT_SHA,
        projectionSink = projectionSink,
        clock = clock,
    )

private class RecordingTrendLiveProjectionSink(
    private val accountSnapshotDue: Boolean = false,
    private var failExecutionRecordingOnce: Boolean = false,
    private var accountingRequest: VolumeConfirmedTrendLiveAccountingRequest? = null,
    var riskReasonCodes: List<String> = emptyList(),
) : VolumeConfirmedTrendLiveProjectionSink {
    val balances = mutableListOf<ExchangeAccountBalance>()
    val fills = mutableListOf<ExchangeExecutionFill>()
    val accountingObservations = mutableListOf<VolumeConfirmedTrendLiveAccountingObservation>()
    val accountingFailures = mutableListOf<VolumeConfirmedTrendLiveAccountingRequest>()

    override suspend fun accountSnapshotDue(now: Instant): Boolean = accountSnapshotDue

    override suspend fun recordAccountBalance(balance: ExchangeAccountBalance) {
        balances += balance
    }

    override suspend fun recordExecutionFills(
        fills: List<ExchangeExecutionFill>,
        receivedAt: Instant,
    ) {
        if (failExecutionRecordingOnce) {
            failExecutionRecordingOnce = false
            throw IllegalStateException("injected trend projection failure")
        }
        this.fills += fills
    }

    override suspend fun reserveAccountingRequest(now: Instant): VolumeConfirmedTrendLiveAccountingRequest? =
        accountingRequest.also { accountingRequest = null }

    override suspend fun recordAccounting(observation: VolumeConfirmedTrendLiveAccountingObservation) {
        accountingObservations += observation
    }

    override suspend fun recordAccountingFailure(
        request: VolumeConfirmedTrendLiveAccountingRequest,
        failedAt: Instant,
    ) {
        accountingFailures += request
    }

    override suspend fun assessEntryRisk(
        previous: dev.yaklede.bybittrader.engine.execution.ExecutionRiskState?,
        now: Instant,
        policy: VolumeConfirmedTrendLiveRiskPolicy,
    ): VolumeConfirmedTrendLiveRiskAssessment =
        VolumeConfirmedTrendLiveRiskAssessment(
            state = previous,
            reasonCodes = riskReasonCodes,
        )
}

private fun executionFill(
    clientOrderId: String,
    executionId: String,
    quantity: String = "0.007",
): ExchangeExecutionFill =
    ExchangeExecutionFill(
        exchangeOrderId = "exchange-fill-001",
        clientOrderId = clientOrderId,
        symbol = Symbol("BTCUSDT"),
        side = Side.BUY,
        price = BigDecimal("60000"),
        quantity = BigDecimal(quantity),
        fee = BigDecimal("0.252"),
        executedAt = TEST_NOW,
        executionId = executionId,
        executionType = "Trade",
        executionPnl = BigDecimal.ZERO,
    )

private fun closedPnl(clientOrderId: String): ExchangeClosedPnl =
    ExchangeClosedPnl(
        exchangeOrderId = "exchange-close-001",
        clientOrderId = clientOrderId,
        symbol = Symbol("BTCUSDT"),
        side = Side.BUY,
        openedAt = TEST_NOW.minusSeconds(14_400),
        closedAt = TEST_NOW.minusSeconds(1),
        entryPrice = BigDecimal("59000"),
        exitPrice = BigDecimal("60000"),
        quantity = BigDecimal("0.007"),
        grossPnl = BigDecimal("7"),
        fees = BigDecimal("0.5"),
        netPnl = BigDecimal("6.5"),
        exitReason = "CLOSED_PNL",
    )

private fun accountTransaction(transactionId: String): ExchangeAccountTransaction =
    ExchangeAccountTransaction(
        transactionId = transactionId,
        symbol = Symbol("BTCUSDT"),
        category = "linear",
        side = Side.BUY,
        transactionAt = TEST_NOW.minusSeconds(30),
        type = "TRADE",
        subtype = null,
        quantity = BigDecimal("0.007"),
        size = BigDecimal("0.007"),
        currency = "USDT",
        tradePrice = BigDecimal("60000"),
        funding = BigDecimal("-0.01"),
        fee = BigDecimal("-0.25"),
        cashFlow = BigDecimal.ZERO,
        change = BigDecimal("6.24"),
        cashBalance = BigDecimal("666.24"),
        feeRate = BigDecimal("0.0006"),
        tradeId = "trade-$transactionId",
        exchangeOrderId = "exchange-close-001",
        clientOrderId = "vct-exit-test",
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
        gates =
            VolumeConfirmedTrendApprovalGateContract.requiredIds.map { id ->
                VolumeConfirmedTrendApprovalGate(
                    id = id,
                    status = VolumeConfirmedTrendApprovalGateStatus.PASS,
                    actual = "PASS",
                    required = "FROZEN",
                    reason = "Frozen gate passed.",
                )
            },
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
    symbol: Symbol = Symbol("BTCUSDT"),
): ExchangePosition =
    ExchangePosition(
        symbol = symbol,
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
