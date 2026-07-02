package dev.yaklede.bybittrader.domain

object ResearchCandleLimits {
    const val MAX_HISTORY_DAYS_BACK = 3_650
    const val MAX_HISTORY_REQUESTS_PER_TIMEFRAME = 10_000
    const val MAX_M1_REPLAY_CANDLES = 6_000_000
    const val MAX_M5_REPLAY_CANDLES = 1_200_000
    const val MAX_M15_REPLAY_CANDLES = 400_000
}
