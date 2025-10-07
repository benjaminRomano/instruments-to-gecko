package com.bromano.instrumentsgecko

import kotlin.math.pow
import kotlin.random.Random
import java.lang.Thread.sleep

/**
 * Retries [block] up to [retryCount] times after the first attempt (total attempts = retryCount + 1).
 *
 * @param retryCount number of retries after the initial attempt (0 = try once).
 * @param exponentialBackoffMs base delay in ms; grows as base * 2^attempt (attempt starts at 0 for first retry).
 * @param maxBackoffMs optional cap on backoff (null = uncapped).
 * @param jitterFraction 0.0..1.0 adds ±fraction jitter to delay.
 * @param retryOn predicate: return true to retry for a given Throwable.
 */
fun <T> withRetry(
    retryCount: Int,
    exponentialBackoffMs: Long,
    maxBackoffMs: Long? = null,
    jitterFraction: Double = 0.2,
    retryOn: (Throwable) -> Boolean = { true },
    block: () -> T
): T {
    require(retryCount >= 0) { "retryCount must be >= 0" }
    require(exponentialBackoffMs >= 0) { "exponentialBackoffMs must be >= 0" }
    require(jitterFraction >= 0.0) { "jitterFraction must be >= 0.0" }

    var attempt = 0
    var lastError: Throwable? = null

    while (attempt <= retryCount) {
        try {
            return block()
        } catch (t: Throwable) {
            lastError = t
            if (attempt == retryCount || !retryOn(t)) break

            val base = exponentialBackoffMs * 2.0.pow(attempt.toDouble())
            val capped = maxBackoffMs?.let { base.coerceAtMost(it.toDouble()) } ?: base
            val jitter = 1 + Random.nextDouble(-jitterFraction, jitterFraction)
            val delayMs = (capped * jitter).toLong().coerceAtLeast(0L)
            sleep(delayMs)

            attempt++
        }
    }

    throw lastError ?: IllegalStateException("withRetry failed without throwable")
}