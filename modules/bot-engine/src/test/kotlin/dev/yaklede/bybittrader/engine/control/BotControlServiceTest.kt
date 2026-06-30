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

        "resume switches into pending reconciliation instead of running directly" {
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
