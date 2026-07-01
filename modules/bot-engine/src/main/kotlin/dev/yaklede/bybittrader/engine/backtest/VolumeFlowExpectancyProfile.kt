package dev.yaklede.bybittrader.engine.backtest

import kotlin.math.abs

data class VolumeFlowExpectancyProfile(
    val averageWinR: Double,
    val averageLossR: Double,
    val payoffRatio: Double?,
    val breakevenWinRatePct: Double?,
    val winRateEdgePct: Double?,
)

internal fun volumeFlowExpectancyProfile(
    returnRs: List<Double>,
    winRatePct: Double,
): VolumeFlowExpectancyProfile {
    val winRs = returnRs.filter { it > 0.0 }
    val lossRs = returnRs.filter { it < 0.0 }.map { abs(it) }
    val averageWinR = if (winRs.isEmpty()) 0.0 else winRs.average()
    val averageLossR = if (lossRs.isEmpty()) 0.0 else lossRs.average()
    val payoffRatio = if (averageLossR > 0.0) averageWinR / averageLossR else null
    val breakevenWinRatePct =
        payoffRatio?.let { ratio ->
            if (ratio <= 0.0) 100.0 else (1.0 / (1.0 + ratio)) * 100.0
        }
    return VolumeFlowExpectancyProfile(
        averageWinR = averageWinR,
        averageLossR = averageLossR,
        payoffRatio = payoffRatio,
        breakevenWinRatePct = breakevenWinRatePct,
        winRateEdgePct = breakevenWinRatePct?.let { winRatePct - it },
    )
}
