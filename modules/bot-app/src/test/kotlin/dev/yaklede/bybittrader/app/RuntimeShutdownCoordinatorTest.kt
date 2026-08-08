package dev.yaklede.bybittrader.app

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

class RuntimeShutdownCoordinatorTest :
    StringSpec({
        "runtime jobs finish before auxiliary stop and shutdown notification" {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val started = CompletableDeferred<Unit>()
            var cancelled = false
            val job =
                scope.launch {
                    try {
                        started.complete(Unit)
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                }
            started.await()
            val phases = mutableListOf<String>()

            shutdownRuntimeJobs(
                jobs = listOf(job, job, null),
                stopAuxiliaryLoops = {
                    job.isCompleted shouldBe true
                    cancelled shouldBe true
                    phases += "auxiliary"
                },
                notifyShutdown = {
                    job.isCompleted shouldBe true
                    phases += "notification"
                },
                onPhaseFailure = { phase, _ -> phases += "failed:$phase" },
            )

            phases shouldBe listOf("auxiliary", "notification")
        }

        "auxiliary and notification failures cannot abort the remaining shutdown phases" {
            val failures = mutableListOf<String>()
            var notificationAttempted = false

            shutdownRuntimeJobs(
                jobs = emptyList(),
                stopAuxiliaryLoops = { error("injected auxiliary shutdown failure") },
                notifyShutdown = {
                    notificationAttempted = true
                    error("injected notification failure")
                },
                onPhaseFailure = { phase, _ -> failures += phase },
            )

            notificationAttempted shouldBe true
            failures shouldBe listOf("STOP_AUXILIARY_LOOPS", "NOTIFY_SHUTDOWN")
        }
    })
