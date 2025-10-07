package com.bromano.instrumentsgecko

/**
 * Thrown when a process exits with a non-zero exit code and retries are enabled.
 * Carries stdout/stderr so retry logic or callers can inspect.
 */
class CommandExecutionException(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) : RuntimeException("Command failed with exit code $exitCode: $command\n$stderr")