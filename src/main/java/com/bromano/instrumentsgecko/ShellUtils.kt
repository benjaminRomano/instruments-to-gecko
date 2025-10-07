package com.bromano.instrumentsgecko

import com.github.ajalt.clikt.core.CliktError
import kotlin.concurrent.thread

data class RunResult(val stdout: String, val stderr: String, val exitCode: Int)

/**
 * Utilities for running commands
 */
class ShellUtils {
    companion object {
        /**
         * Simple one-shot run (NO retries by default now).
         * Returns stdout only when redirectOutput == PIPE, else empty string.
         */
        fun run(
            command: String,
            ignoreErrors: Boolean = false,
            shell: Boolean = true,
            redirectOutput: ProcessBuilder.Redirect = ProcessBuilder.Redirect.INHERIT,
            redirectError: ProcessBuilder.Redirect = ProcessBuilder.Redirect.INHERIT,
        ): String {
            val res = runRaw(command, shell, redirectOutput, redirectError)

            if (!ignoreErrors && res.exitCode != 0) {
                val err = res.stderr.trim()
                throw CliktError("Command failed: $command\n$err")
            }

            return if (redirectOutput == ProcessBuilder.Redirect.PIPE) {
                res.stdout.trim()
            } else {
                ""
            }
        }

        /**
         * Backwards-compatible retrying runner using withRetry.
         *
         * Note: 'retries' still means TOTAL attempts (was previous behavior).
         * So retries=5 => up to 5 executions; internally mapped to retryCount = retries - 1.
         *
         * If ignoreErrors = true, this executes only once (no retries), same as legacy behavior.
         */
        fun runWithRetries(
            command: String,
            ignoreErrors: Boolean = false,
            shell: Boolean = false,
            redirectOutput: ProcessBuilder.Redirect = ProcessBuilder.Redirect.PIPE,
            redirectError: ProcessBuilder.Redirect = ProcessBuilder.Redirect.PIPE,
            retries: Int = 5,
            retryableExitCodes: Set<Int> = setOf(139),
            baseBackoffMs: Long = 200L,
            maxBackoffMs: Long? = null,
            jitterFraction: Double = 0.2
        ): RunResult {
            require(retries >= 1) { "retries must be >= 1 (represents total attempts)" }

            if (ignoreErrors) {
                val res = runRaw(command, shell, redirectOutput, redirectError)
                if (res.exitCode != 0) {
                    println("❌ Command failed with exit code: ${res.exitCode}")
                    if (res.stderr.isNotBlank()) println("Stderr: ${res.stderr.trim()}")
                }
                return res
            }

            return withRetry(
                retryCount = retries - 1,
                exponentialBackoffMs = baseBackoffMs,
                maxBackoffMs = maxBackoffMs,
                jitterFraction = jitterFraction,
                retryOn = { t ->
                    t is CommandExecutionException && t.exitCode in retryableExitCodes
                }
            ) {
                val res = runRaw(command, shell, redirectOutput, redirectError)
                if (res.exitCode != 0) {
                    println("❌ Command failed with exit code: ${res.exitCode}")
                    if (res.stderr.isNotBlank()) println("Stderr: ${res.stderr.trim()}")
                    throw CommandExecutionException(
                        command = command,
                        exitCode = res.exitCode,
                        stdout = res.stdout,
                        stderr = res.stderr
                    )
                }
                res
            }
        }

        private fun runRaw(
            command: String,
            shell: Boolean,
            redirectOutput: ProcessBuilder.Redirect,
            redirectError: ProcessBuilder.Redirect
        ): RunResult {
            val cmds = if (shell) arrayOf("/bin/bash", "-c", command) else splitCommand(command)

            val pb = ProcessBuilder(*cmds).apply {
                if (redirectOutput == ProcessBuilder.Redirect.PIPE) redirectOutput(ProcessBuilder.Redirect.PIPE)
                if (redirectError == ProcessBuilder.Redirect.PIPE) redirectError(ProcessBuilder.Redirect.PIPE)
                if (redirectOutput == ProcessBuilder.Redirect.INHERIT) redirectOutput(ProcessBuilder.Redirect.INHERIT)
                if (redirectError == ProcessBuilder.Redirect.INHERIT) redirectError(ProcessBuilder.Redirect.INHERIT)
            }.redirectInput(ProcessBuilder.Redirect.INHERIT)

            val proc = pb.start()

            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()

            val outThread = thread(start = true, isDaemon = true) {
                proc.inputStream.bufferedReader().use { r ->
                    var line: String? = r.readLine()
                    while (line != null) {
                        stdoutBuilder.appendLine(line)
                        line = r.readLine()
                    }
                }
            }

            val errThread = thread(start = true, isDaemon = true) {
                proc.errorStream.bufferedReader().use { r ->
                    var line: String? = r.readLine()
                    while (line != null) {
                        stderrBuilder.appendLine(line)
                        line = r.readLine()
                    }
                }
            }

            val exitCode = proc.waitFor()

            outThread.join(5000)
            errThread.join(5000)

            return RunResult(stdoutBuilder.toString(), stderrBuilder.toString(), exitCode)
        }

        private fun splitCommand(cmd: String): Array<String> {
            return cmd.split(' ').filter { it.isNotEmpty() }.toTypedArray()
        }
    }
}