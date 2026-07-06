package dev.yaklede.bybittrader.engine.control

import dev.yaklede.bybittrader.domain.BotMode
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class BotControlServiceTest :
    StringSpec({
        "pause new entries updates state and records control event" {
            val store = InMemoryStateStore(BotMode.RUNNING)
            val recorder = InMemoryControlEventRecorder()
            val service =
                BotControlService(
                    stateStore = store,
                    eventRecorder = recorder,
                    clock = fixedClock(),
                )

            val result = service.pauseNewEntries(actor = "operator", reason = "manual")

            result.previousMode shouldBe BotMode.RUNNING
            result.newMode shouldBe BotMode.PAUSE_NEW_ENTRIES
            store.current().mode shouldBe BotMode.PAUSE_NEW_ENTRIES
            recorder.events shouldHaveSize 1
            recorder.events.single().actor shouldBe "operator"
        }

        "resume waits for readiness check" {
            val store = InMemoryStateStore(BotMode.PAUSE_ALL)
            val service =
                BotControlService(
                    stateStore = store,
                    eventRecorder = InMemoryControlEventRecorder(),
                    clock = fixedClock(),
                )

            val result = service.resume(actor = "operator", reason = null)

            result.newMode shouldBe BotMode.RESUME_PENDING_CHECK
            store.current().mode shouldBe BotMode.RESUME_PENDING_CHECK
        }

        "complete resume check switches pending state to running" {
            val store = InMemoryStateStore(BotMode.RESUME_PENDING_CHECK)
            val recorder = InMemoryControlEventRecorder()
            val service =
                BotControlService(
                    stateStore = store,
                    eventRecorder = recorder,
                    clock = fixedClock(),
                )

            val result = service.completeResumeCheck(actor = "readiness", reason = "ready")

            result?.previousMode shouldBe BotMode.RESUME_PENDING_CHECK
            result?.newMode shouldBe BotMode.RUNNING
            store.current().mode shouldBe BotMode.RUNNING
            recorder.events shouldHaveSize 1
            recorder.events.single().actor shouldBe "readiness"
        }

        "complete resume check ignores non pending state" {
            val store = InMemoryStateStore(BotMode.RUNNING)
            val recorder = InMemoryControlEventRecorder()
            val service =
                BotControlService(
                    stateStore = store,
                    eventRecorder = recorder,
                    clock = fixedClock(),
                )

            val result = service.completeResumeCheck(actor = "readiness", reason = null)

            result shouldBe null
            store.current().mode shouldBe BotMode.RUNNING
            recorder.events shouldHaveSize 0
        }

        "resume readiness check confirms pending state after probe success" {
            val store = InMemoryStateStore(BotMode.RESUME_PENDING_CHECK)
            val controlService =
                BotControlService(
                    stateStore = store,
                    eventRecorder = InMemoryControlEventRecorder(),
                    clock = fixedClock(),
                )
            var probed = false
            val readinessService =
                BotResumeReadinessService(
                    stateStore = store,
                    controlService = controlService,
                    readinessProbe = { probed = true },
                    clock = fixedClock(),
                )

            val result = readinessService.checkOnce()

            probed shouldBe true
            result.status shouldBe BotResumeReadinessStatus.CONFIRMED
            result.currentMode shouldBe BotMode.RUNNING
            store.current().mode shouldBe BotMode.RUNNING
        }

        "resume readiness check keeps pending state after probe failure" {
            val store = InMemoryStateStore(BotMode.RESUME_PENDING_CHECK)
            val controlService =
                BotControlService(
                    stateStore = store,
                    eventRecorder = InMemoryControlEventRecorder(),
                    clock = fixedClock(),
                )
            val readinessService =
                BotResumeReadinessService(
                    stateStore = store,
                    controlService = controlService,
                    readinessProbe = { error("exchange unavailable") },
                    clock = fixedClock(),
                )

            val result = readinessService.checkOnce()

            result.status shouldBe BotResumeReadinessStatus.FAILED
            result.currentMode shouldBe BotMode.RESUME_PENDING_CHECK
            store.current().mode shouldBe BotMode.RESUME_PENDING_CHECK
        }
    })

private fun fixedClock(): Clock = Clock.fixed(Instant.parse("2026-06-30T00:00:00Z"), ZoneOffset.UTC)

private class InMemoryStateStore(
    initialMode: BotMode,
) : BotStateStore {
    private var status =
        BotRuntimeStatus(
            mode = initialMode,
            updatedAt = Instant.parse("2026-06-29T00:00:00Z"),
            heartbeatAt = null,
        )

    override suspend fun current(): BotRuntimeStatus = status

    override suspend fun update(status: BotRuntimeStatus) {
        this.status = status
    }
}

private class InMemoryControlEventRecorder : ControlEventRecorder {
    val events = mutableListOf<ControlEvent>()

    override suspend fun record(event: ControlEvent) {
        events += event
    }
}
