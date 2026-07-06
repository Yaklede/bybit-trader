package dev.yaklede.bybittrader.engine.control

import dev.yaklede.bybittrader.domain.BotMode
import dev.yaklede.bybittrader.domain.BotModeTransitionPolicy
import dev.yaklede.bybittrader.domain.ControlAction
import java.time.Clock
import java.time.Instant

class BotControlService(
    private val stateStore: BotStateStore,
    private val eventRecorder: ControlEventRecorder,
    private val transitionPolicy: BotModeTransitionPolicy = BotModeTransitionPolicy(),
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun pauseNewEntries(
        actor: String,
        reason: String?,
    ): ControlResult = handle(ControlAction.PAUSE_NEW_ENTRIES, actor, reason)

    suspend fun pauseAll(
        actor: String,
        reason: String?,
    ): ControlResult = handle(ControlAction.PAUSE_ALL, actor, reason)

    suspend fun resume(
        actor: String,
        reason: String?,
    ): ControlResult = handle(ControlAction.RESUME, actor, reason)

    suspend fun completeResumeCheck(
        actor: String,
        reason: String?,
    ): ControlResult? {
        require(actor.isNotBlank()) { "Actor must not be blank." }
        val current = stateStore.current()
        if (current.mode != BotMode.RESUME_PENDING_CHECK) {
            return null
        }
        val now = Instant.now(clock)
        val nextStatus = current.copy(mode = BotMode.RUNNING, updatedAt = now)
        stateStore.update(nextStatus)
        eventRecorder.record(
            ControlEvent(
                action = ControlAction.RESUME,
                actor = actor,
                previousMode = current.mode,
                newMode = nextStatus.mode,
                reason = reason?.takeIf { it.isNotBlank() },
                createdAt = now,
            ),
        )
        return ControlResult(
            action = ControlAction.RESUME,
            previousMode = current.mode,
            newMode = nextStatus.mode,
            changedAt = now,
        )
    }

    suspend fun emergencyStop(
        actor: String,
        reason: String?,
    ): ControlResult = handle(ControlAction.EMERGENCY_STOP, actor, reason)

    private suspend fun handle(
        action: ControlAction,
        actor: String,
        reason: String?,
    ): ControlResult {
        require(actor.isNotBlank()) { "Actor must not be blank." }
        val current = stateStore.current()
        val nextMode = transitionPolicy.nextMode(current.mode, action)
        val now = Instant.now(clock)
        val nextStatus = current.copy(mode = nextMode, updatedAt = now)
        stateStore.update(nextStatus)
        eventRecorder.record(
            ControlEvent(
                action = action,
                actor = actor,
                previousMode = current.mode,
                newMode = nextMode,
                reason = reason?.takeIf { it.isNotBlank() },
                createdAt = now,
            ),
        )
        return ControlResult(
            action = action,
            previousMode = current.mode,
            newMode = nextMode,
            changedAt = now,
        )
    }
}
