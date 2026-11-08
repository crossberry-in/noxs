/*
 * Noxs — original implementation.
 * ReconnectPolicy: bounded exponential backoff for the NoxOS emulator
 * VNC display bridge (integration spec §5: "bounded retry backoff +
 * manual retry" — the bridge must NEVER retry forever, and the user
 * must always keep a manual path).
 *
 * Contract:
 *   - Each call to [nextDelayMs] consumes one attempt and returns the
 *     delay to wait BEFORE that attempt (ms): 1s, 2s, 4s, 8s, then
 *     capped at 15s.
 *   - [canRetry] is false once [maxAttempts] attempts were consumed;
 *     [nextDelayMs] then returns -1 (callers must show the manual
 *     retry button instead of scheduling anything).
 *   - [reset] is called on every successful (re)connect and on an
 *     explicit manual retry, so the ladder always starts fresh.
 *
 * Pure JVM: no android.* imports, unit tested in ReconnectPolicyTest.
 */
package com.crossberry.noxs.shared.emulator

class ReconnectPolicy(private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS) {

    companion object {
        /** Bounded: after this many automatic attempts the UI must stop
         *  and wait for the user (never a silent infinite loop). */
        const val DEFAULT_MAX_ATTEMPTS: Int = 5

        /** Base delay of the exponential ladder. */
        const val BASE_DELAY_MS: Long = 1_000L

        /** Backoff cap — one automatic attempt never waits longer. */
        const val MAX_DELAY_MS: Long = 15_000L
    }

    private var attempts: Int = 0

    /** Attempts consumed since the last [reset]. */
    fun attemptsUsed(): Int = attempts

    /** True while another automatic attempt is still allowed. */
    fun canRetry(): Boolean = attempts < maxAttempts

    /**
     * Consumes one attempt and returns the delay to wait BEFORE it (ms).
     * Returns -1 when the bound is exhausted (never schedules again).
     */
    fun nextDelayMs(): Long {
        if (attempts >= maxAttempts) return -1L
        val delay = minOf(BASE_DELAY_MS shl attempts, MAX_DELAY_MS)
        attempts++
        return delay
    }

    /** Fresh ladder: after a live connection or a manual retry. */
    fun reset() {
        attempts = 0
    }
}
