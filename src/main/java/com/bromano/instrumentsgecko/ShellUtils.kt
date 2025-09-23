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
         * Backwards-compatible run: returns stdout when redirectOutput is PIPE.
         * Internally uses runWithRetries with default params.
         */
        fun run(
            command: String,
            ignoreErrors: Boolean = false,
            shell: Boolean = true,
            redirectOutput: ProcessBuilder.Redirect = ProcessBuilder.Redirect.INHERIT,
            redirectError: ProcessBuilder.Redirect = ProcessBuilder.Redirect.INHERIT,
        ): String {
            val res = runWithRetries(
                command = command,
                ignoreErrors = ignoreErrors,
                shell = shell,
                redirectOutput = redirectOutput,
                redirectError = redirectError,
                retries = 3
            )

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
         * Robust runner with retries and backoff for transient failures.
         * - command: full command string OR a single program if shell=false
         * - shell: if true, runs via /bin/bash -c; recommended false for list-style commands
         */
        fun runWithRetries(
            command: String,
            ignoreErrors: Boolean = false,
            shell: Boolean = false,
            redirectOutput: ProcessBuilder.Redirect = ProcessBuilder.Redirect.PIPE,
            redirectError: ProcessBuilder.Redirect = ProcessBuilder.Redirect.PIPE,
            retries: Int = 5,
            retryableExitCodes: Set<Int> = setOf(139, 1),
            baseBackoffMs: Long = 200L
        ): RunResult {
            var attempt = 0
            var lastErr = ""

            while (attempt < retries) {
                attempt++

                val res = runRaw(command, shell, redirectOutput, redirectError)
                
                // To log exit code and stderr
                if (res.exitCode != 0) {
                    println("❌ Command failed with exit code: ${res.exitCode}")
                    if (res.stderr.isNotBlank()) {
                        println("Stderr: ${res.stderr.trim()}")
                    }
                }

                if (res.exitCode == 0 || (!checkExitCode(ignoreErrors, res.exitCode))) {
                    return res
                }

                lastErr = res.stderr
                if (res.exitCode in retryableExitCodes && attempt < retries) {
                    Thread.sleep(baseBackoffMs * (1 shl (attempt - 1)).coerceAtMost(10))
                    // retry loop continues
                    continue
                }

                if (!ignoreErrors) throw CliktError("Command failed: $command\n${res.stderr}")
                return res
            }

            throw CliktError("Command failed after $retries attempts: $command\n$lastErr")
        }

        private fun checkExitCode(ignoreErrors: Boolean, exitCode: Int): Boolean {
            return !(ignoreErrors || exitCode == 0)
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