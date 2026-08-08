package dev.yaklede.bybittrader.engine.strategy

import org.slf4j.Logger
import kotlin.coroutines.cancellation.CancellationException

internal suspend fun notifyTradingLoopFailure(
    logger: Logger,
    loopName: String,
    onFailure: suspend (Throwable) -> Unit,
    error: Throwable,
) {
    try {
        onFailure(error)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (callbackError: Throwable) {
        logger.error("$loopName failure callback failed", callbackError)
    }
}
