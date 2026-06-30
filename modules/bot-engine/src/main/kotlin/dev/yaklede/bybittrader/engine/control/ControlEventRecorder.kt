package dev.yaklede.bybittrader.engine.control

interface ControlEventRecorder {
    suspend fun record(event: ControlEvent)
}
