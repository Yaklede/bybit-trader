package dev.yaklede.bybittrader.domain

class BotModeTransitionPolicy {
    fun nextMode(
        currentMode: BotMode,
        action: ControlAction,
    ): BotMode =
        when (action) {
            ControlAction.PAUSE_NEW_ENTRIES -> {
                if (currentMode == BotMode.EMERGENCY_STOP) {
                    BotMode.EMERGENCY_STOP
                } else {
                    BotMode.PAUSE_NEW_ENTRIES
                }
            }
            ControlAction.PAUSE_ALL -> {
                if (currentMode == BotMode.EMERGENCY_STOP) {
                    BotMode.EMERGENCY_STOP
                } else {
                    BotMode.PAUSE_ALL
                }
            }
            ControlAction.RESUME -> BotMode.RESUME_PENDING_CHECK
            ControlAction.EMERGENCY_STOP -> BotMode.EMERGENCY_STOP
        }
}
