package dev.yaklede.bybittrader.app

import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll

internal suspend fun shutdownRuntimeJobs(
    jobs: List<Job?>,
    stopAuxiliaryLoops: suspend () -> Unit,
    notifyShutdown: suspend () -> Unit,
    onPhaseFailure: (phase: String, error: Throwable) -> Unit,
) {
    val runtimeJobs = jobs.filterNotNull().distinct()
    runtimeJobs.forEach(Job::cancel)
    runtimeJobs.joinAll()
    runShutdownPhase("STOP_AUXILIARY_LOOPS", stopAuxiliaryLoops, onPhaseFailure)
    runShutdownPhase("NOTIFY_SHUTDOWN", notifyShutdown, onPhaseFailure)
}

private suspend fun runShutdownPhase(
    phase: String,
    action: suspend () -> Unit,
    onPhaseFailure: (phase: String, error: Throwable) -> Unit,
) {
    try {
        action()
    } catch (error: Throwable) {
        onPhaseFailure(phase, error)
    }
}
