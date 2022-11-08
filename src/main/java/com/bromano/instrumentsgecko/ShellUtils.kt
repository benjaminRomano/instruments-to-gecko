package com.bromano.instrumentsgecko

import com.github.ajalt.clikt.core.CliktError
import java.awt.Color.red
import java.io.InputStream

/**
 * Utilities for running commands
 */
class ShellUtils {
    companion object {
        fun run(
            command: String,
            ignoreErrors: Boolean = false,
            shell: Boolean = true,
            redirectOutput: ProcessBuilder.Redirect = ProcessBuilder.Redirect.INHERIT,
            redirectError: ProcessBuilder.Redirect = ProcessBuilder.Redirect.INHERIT,
        ): String {
            var output = ""
            run(
                command = command,
                ignoreErrors = ignoreErrors,
                shell = shell,
                redirectOutput = redirectOutput,
                redirectError = redirectError,
            ) { inputStream ->
                output = inputStream.bufferedReader().readText().trim()
            }

            return if (redirectOutput == ProcessBuilder.Redirect.PIPE || redirectError == ProcessBuilder.Redirect.PIPE) {
                output
            } else {
                ""
            }
        }

        private fun run(
            command: String,
            ignoreErrors: Boolean = false,
            shell: Boolean = true,
            redirectOutput: ProcessBuilder.Redirect = ProcessBuilder.Redirect.INHERIT,
            redirectError: ProcessBuilder.Redirect = ProcessBuilder.Redirect.INHERIT,
            outputParser: (InputStream) -> Unit
        ) {
            val cmds = if (shell) {
                arrayOf("/bin/bash", "-c", command)
            } else {
                arrayOf(command)
            }

            val proc = ProcessBuilder(*cmds).apply {
                redirectOutput(redirectOutput)
                redirectError(redirectError)
            }
                .redirectInput(ProcessBuilder.Redirect.INHERIT)
                .start()

            proc.inputStream.use(outputParser)
            val exitCode = proc.waitFor()

            if (exitCode != 0 && !ignoreErrors) {
                val error = proc.errorStream.bufferedReader().readText().trim()
                throw CliktError("Command failed: ${"$command\n$error"}")
            }
        }
    }
}