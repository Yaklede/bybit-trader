package dev.yaklede.bybittrader.engine.control

import dev.yaklede.bybittrader.domain.BotMode
import dev.yaklede.bybittrader.domain.ControlAction
import java.time.Instant

data class ControlResult(
    val action: ControlAction,
    val previousMode: BotMode,
    val newMode: BotMode,
    val changedAt: Instant,
)
