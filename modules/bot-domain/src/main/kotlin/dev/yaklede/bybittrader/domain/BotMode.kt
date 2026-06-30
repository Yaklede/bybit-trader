package dev.yaklede.bybittrader.domain

enum class BotMode {
    RUNNING,
    PAUSE_NEW_ENTRIES,
    PAUSE_ALL,
    EMERGENCY_STOP,
    RESUME_PENDING_CHECK,
}
