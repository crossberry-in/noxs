/*
 * Noxs — original implementation.
 * Process-wide APT security bootstrap runner.
 *
 * The signed APT/CA/TLS bootstrap repairs the Ubuntu package layer in the
 * background. It must NEVER gate shell creation: the terminal always clears
 * straight to an active shell while this runs (and retries on later launches).
 * State intentionally lives outside any Activity/Service so it survives
 * Activity recreation and service restarts within the same process.
 *
 * Anti-loop policy: after MAX_IDENTICAL failures in a row with the SAME
 * failure signature, further automatic attempts inside this process are
 * rate-limited to one per MIN_INTERVAL_MS (the network may come back, so the
 * loop must stay eventually-successful — but it may not spin).
 */
package com.crossberry.noxs.runtime

import com.crossberry.noxs.shared.NoxsLog
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

object NoxsAptSetup {

    enum class State { IDLE, RUNNING, READY, FAILED }

    private val _state = MutableStateFlow(State.IDLE)

    /** Observable bootstrap state (single source of truth for the UI). */
    val state: StateFlow<State> = _state

    /** Last failure detail, for diagnostics. Never contains secrets. */
    @Volatile
    var detail: String = ""
        private set

    private val running = AtomicBoolean(false)

    /** Identical-failure bookkeeping (monotonic clock, JVM-testable policy). */
    @Volatile
    private var lastFailureSignature: String? = null
    @Volatile
    private var identicalFailures: Int = 0
    @Volatile
    private var lastAttemptAtNanos: Long = 0L

    /**
     * Starts the bootstrap at most once at a time, only for installed
     * environments. Safe to call on every service start:
     * - IDLE + readiness marker present (verified by the setup wizard) -> READY
     * - READY -> no-op (nothing to repair)
     * - otherwise -> run/repair in [scope] on a background dispatcher,
     *   rate-limited when the same failure keeps repeating
     */
    @Synchronized
    fun startIfInstalled(paths: NoxsPaths, launcher: ProotLauncher, scope: CoroutineScope) {
        if (!paths.isInstalled()) return
        if (File(paths.base, "offline-packages-v1.ready").isFile && !paths.aptReadyMarker.isFile) {
            NoxsLog.i("AptSetup", "automatic APT network bootstrap deferred for bundled offline Ubuntu")
            return
        }
        when (_state.value) {
            State.RUNNING -> return
            State.READY -> if (paths.aptReadyMarker.isFile) return
            State.IDLE -> if (paths.aptReadyMarker.isFile) {
                _state.value = State.READY
                return
            }
            State.FAILED -> Unit // retry on this launch (subject to backoff)
        }
        if (!running.compareAndSet(false, true)) return

        val sinceLastMs = if (lastAttemptAtNanos == 0L) Long.MAX_VALUE
        else (System.nanoTime() - lastAttemptAtNanos) / 1_000_000L
        if (!AptSetupBackoff.shouldAttempt(identicalFailures, sinceLastMs)) {
            running.set(false)
            NoxsLog.i(
                "AptSetup",
                "background APT bootstrap skipped: identical failure #$identicalFailures, " +
                    "next automatic attempt in ${AptSetupBackoff.remainingMs(sinceLastMs) / 1000}s"
            )
            return
        }

        _state.value = State.RUNNING
        detail = ""
        scope.launch(Dispatchers.IO) {
            lastAttemptAtNanos = System.nanoTime()
            NoxsLog.i("AptSetup", "background APT bootstrap started")
            val result = runCatching { NoxsAptBootstrapper(paths, launcher).initialize() }
                .getOrElse { NoxsAptBootstrapper.Result(false, it.message ?: it.javaClass.simpleName) }
            detail = result.detail
            recordOutcome(result.success, result.detail)
            _state.value = if (result.success) State.READY else State.FAILED
            running.set(false)
            NoxsLog.i("AptSetup", "background APT bootstrap finished success=${result.success}")
        }
    }

    /** Pure-ish state update so the backoff bookkeeping stays in one place. */
    private fun recordOutcome(success: Boolean, failureDetail: String) {
        if (success) {
            lastFailureSignature = null
            identicalFailures = 0
            return
        }
        val signature = AptSetupBackoff.signature(failureDetail)
        identicalFailures = if (signature == lastFailureSignature) identicalFailures + 1 else 1
        lastFailureSignature = signature
    }

    /** Test/diagnostic hook: forget the cached state for this process. */
    fun resetForTests() {
        running.set(false)
        detail = ""
        _state.value = State.IDLE
        lastFailureSignature = null
        identicalFailures = 0
        lastAttemptAtNanos = 0L
    }
}

/**
 * Pure backoff policy for repeated identical background bootstrap failures
 * (JVM-testable). Distinct failures always retry immediately: a changed
 * error means conditions changed, so the next attempt may genuinely help.
 */
object AptSetupBackoff {

    /** Identical failures allowed before rate limiting kicks in. */
    const val MAX_IDENTICAL_BEFORE_BACKOFF: Int = 3

    /** Minimum spacing between attempts once rate limiting is active. */
    const val MIN_INTERVAL_MS: Long = 15L * 60_000L

    fun shouldAttempt(identicalFailures: Int, msSinceLastAttempt: Long): Boolean =
        identicalFailures < MAX_IDENTICAL_BEFORE_BACKOFF || msSinceLastAttempt >= MIN_INTERVAL_MS

    fun remainingMs(msSinceLastAttempt: Long): Long =
        (MIN_INTERVAL_MS - msSinceLastAttempt).coerceAtLeast(0L)

    /** Stable, short signature of a failure detail (first meaningful lines). */
    fun signature(detail: String): String =
        detail.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString(" | ")
            .take(300)
}
