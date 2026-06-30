package dev.yaklede.bybittrader.engine.control

import dev.yaklede.bybittrader.domain.BotMode
import java.time.Instant

data class BotRuntimeStatus(
    val mode: BotMode,
    val updatedAt: Instant,
    val heartbeatAt: Instant?,
)
