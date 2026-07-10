package dev.yaklede.bybittrader.engine.strategy

import dev.yaklede.bybittrader.domain.Side
import dev.yaklede.bybittrader.engine.backtest.VolumeFlowAggressiveBacktestConfig

internal fun aggressiveDirectionalClose(
    side: Side,
    closeLocation: Double,
): Double =
    when (side) {
        Side.BUY -> closeLocation
        Side.SELL -> 1.0 - closeLocation
    }

internal fun aggressiveBreakoutAllowed(
    side: Side,
    close: Double,
    boundary: Double,
    atr: Double,
    relativeVolume: Double?,
    bodyRatio: Double,
    closeLocation: Double,
    config: VolumeFlowAggressiveBacktestConfig,
): Boolean {
    if ((relativeVolume ?: 0.0) < config.breakoutRelativeVolumeMin) return false
    if (bodyRatio < config.breakoutBodyRatioMin) return false
    if (aggressiveDirectionalClose(side, closeLocation) < config.breakoutDirectionalCloseMin) return false
    val breakoutDistanceAtr =
        when (side) {
            Side.BUY -> (close - boundary) / atr
            Side.SELL -> (boundary - close) / atr
        }
    return breakoutDistanceAtr > 0.0 &&
        (config.maxBreakoutDistanceAtr == null || breakoutDistanceAtr <= config.maxBreakoutDistanceAtr)
}

internal fun aggressiveRetestConfirmed(
    side: Side,
    open: Double,
    high: Double,
    low: Double,
    close: Double,
    closeLocation: Double,
    boundary: Double,
    atr: Double,
    config: VolumeFlowAggressiveBacktestConfig,
): Boolean {
    val tolerance = atr * config.retestToleranceAtr
    if (aggressiveDirectionalClose(side, closeLocation) < config.retestDirectionalCloseMin) return false
    return when (side) {
        Side.BUY -> low <= boundary + tolerance && close > boundary && close > open
        Side.SELL -> high >= boundary - tolerance && close < boundary && close < open
    }
}
