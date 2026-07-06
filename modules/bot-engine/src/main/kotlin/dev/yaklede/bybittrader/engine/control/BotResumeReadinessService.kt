package dev.yaklede.bybittrader.engine.control

import dev.yaklede.bybittrader.domain.BotMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

class BotResumeReadinessService(
    private val stateStore: BotStateStore,
    private val controlService: BotControlService,
    private val readinessProbe: suspend () -> Unit,
    private val config: BotResumeReadinessConfig = BotResumeReadinessConfig(),
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(BotResumeReadinessService::class.java)

    suspend fun checkOnce(
        actor: String = DEFAULT_ACTOR,
        reason: String = DEFAULT_REASON,
    ): BotResumeReadinessResult {
        val current = stateStore.current()
        val checkedAt = Instant.now(clock)
        if (current.mode != BotMode.RESUME_PENDING_CHECK) {
            return BotResumeReadinessResult(
                status = BotResumeReadinessStatus.SKIPPED,
                previousMode = current.mode,
                currentMode = current.mode,
                checkedAt = checkedAt,
                message = "Bot is not waiting for resume readiness check.",
                controlResult = null,
            )
        }

        return try {
            readinessProbe()
            val controlResult = controlService.completeResumeCheck(actor = actor, reason = reason)
            if (controlResult == null) {
                val latest = stateStore.current()
                return BotResumeReadinessResult(
                    status = BotResumeReadinessStatus.SKIPPED,
                    previousMode = current.mode,
                    currentMode = latest.mode,
                    checkedAt = checkedAt,
                    message = "Bot mode changed before resume readiness could be completed.",
                    controlResult = null,
                )
            }
            BotResumeReadinessResult(
                status = BotResumeReadinessStatus.CONFIRMED,
                previousMode = current.mode,
                currentMode = controlResult.newMode,
                checkedAt = checkedAt,
                message = "Resume readiness check passed.",
                controlResult = controlResult,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            BotResumeReadinessResult(
                status = BotResumeReadinessStatus.FAILED,
                previousMode = current.mode,
                currentMode = current.mode,
                checkedAt = checkedAt,
                message = error.message ?: error::class.simpleName.orEmpty(),
                controlResult = null,
            )
        }
    }

    fun start(
        scope: CoroutineScope,
        onConfirmed: suspend (ControlResult) -> Unit = {},
    ): Job =
        scope.launch {
            while (isActive) {
                val result = checkOnce()
                when (result.status) {
                    BotResumeReadinessStatus.CONFIRMED ->
                        result.controlResult?.let { controlResult ->
                            logger.info(
                                "resume readiness check passed mode={}->{}",
                                controlResult.previousMode.name,
                                controlResult.newMode.name,
                            )
                            try {
                                onConfirmed(controlResult)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Throwable) {
                                logger.warn("resume readiness alert failed error={}", error::class.simpleName)
                            }
                        }

                    BotResumeReadinessStatus.FAILED ->
                        logger.warn("resume readiness check failed reason={}", result.message)

                    BotResumeReadinessStatus.SKIPPED -> Unit
                }
                delay(config.interval.toMillis())
            }
        }
}

data class BotResumeReadinessConfig(
    val interval: Duration = Duration.ofSeconds(5),
) {
    init {
        require(!interval.isNegative && !interval.isZero) { "Resume readiness interval must be positive." }
    }
}

data class BotResumeReadinessResult(
    val status: BotResumeReadinessStatus,
    val previousMode: BotMode,
    val currentMode: BotMode,
    val checkedAt: Instant,
    val message: String,
    val controlResult: ControlResult?,
)

enum class BotResumeReadinessStatus {
    SKIPPED,
    FAILED,
    CONFIRMED,
}

private const val DEFAULT_ACTOR = "resume-readiness"
private const val DEFAULT_REASON = "서버 상태 확인을 통과해 재가동을 완료했어요."
