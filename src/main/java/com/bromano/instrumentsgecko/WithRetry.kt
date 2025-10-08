package com.bromano.instrumentsgecko

/**
 * Executes [block] and retries it when an exception is thrown, up to [maxAttempts] times.
 *
 * @param maxAttempts total number of attempts; must be at least 1.
 * @param delayMillis sleep duration in milliseconds between retries.
 * @param shouldRetry predicate that decides whether the caught [Throwable] should trigger another attempt.
 * @param block operation to run with retry semantics.
 * @throws Throwable the last error encountered when retries are exhausted or `shouldRetry` returns false.
 */
inline fun <T> withRetry(
    maxAttempts: Int = 3,
    delayMillis: Long = 0,
    shouldRetry: (Throwable) -> Boolean = { true },
    block: () -> T
): T {
    require(maxAttempts >= 1) { "maxAttempts must be at least 1" }

    var lastError: Throwable? = null
    repeat(maxAttempts) { attemptIndex ->
        try {
            return block()
        } catch (error: Throwable) {
            lastError = error
            val isLastAttempt = attemptIndex == maxAttempts - 1
            if (!shouldRetry(error) || isLastAttempt) {
                throw error
            }

            if (delayMillis > 0) {
                Thread.sleep(delayMillis)
            }
        }
    }

    throw lastError ?: IllegalStateException("withRetry failed without executing block")
}
