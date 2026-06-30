package dev.yaklede.bybittrader.engine.control

interface BotStateStore {
    suspend fun current(): BotRuntimeStatus

    suspend fun update(status: BotRuntimeStatus)
}
