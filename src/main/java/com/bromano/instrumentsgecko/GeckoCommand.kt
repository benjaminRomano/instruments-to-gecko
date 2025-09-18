package com.bromano.instrumentsgecko

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import kotlin.concurrent.thread
import kotlin.system.exitProcess

fun main(args: Array<String>) = GeckoCommand().main(args)

class GeckoCommand : CliktCommand(help = "Convert Instruments Trace to Gecko Format (Firefox Profiler)") {

    private val input by option(
        "-i", "--input",
        help = "Input Instruments Trace",
    )
        .path(mustExist = true, canBeDir = true)
        .required()

    private val app by option("--app", help = "Name of app (e.g. YourApp)")

    private val runNum by option(
        "--run",
        help = "Which run within the trace file to analyze",
    ).int().default(1)

    private val output by option(
        "-o", "--output",
        help = "Output Path for gecko profile",
    )
        .path(mustExist = false, canBeDir = false)
        .required()

    override fun run() {
        lateinit var samples: List<InstrumentsSample>
        lateinit var loadedImageList: List<Library>

        var threadIdSamples: List<InstrumentsSample>? = null
        var virtualMemorySamples: List<InstrumentsSample>? = null
        var syscallSamples: List<InstrumentsSample>? = null

        val timeProfilerSettings = InstrumentsParser.getInstrumentsSettings(input, runNum)

        // Load data sequentially
        Logger.timedLog("Loading Symbols, Samples and Load Addresses...") {
            samples = InstrumentsParser.loadSamples(TIME_PROFILE_SCHEMA, SAMPLE_TIME_TAG, input, runNum)
            loadedImageList = InstrumentsParser.sortedImageList(input, runNum)
            
            if (timeProfilerSettings.hasThreadStates) {
                threadIdSamples = InstrumentsParser.loadIdleThreadSamples(input, runNum)
            }
            
            if (timeProfilerSettings.hasVirtualMemory) {
                virtualMemorySamples = InstrumentsParser.loadSamples(VIRTUAL_MEMORY_SCHEMA, START_TIME_TAG, input, runNum)
            }
            
            if (timeProfilerSettings.hasSyscalls) {
                syscallSamples = InstrumentsParser.loadSamples(SYSCALL_SCHEMA, START_TIME_TAG, input, runNum)
            }
        }

        val concatenatedSamples =
            (syscallSamples ?: emptyList()) + (threadIdSamples ?: emptyList()) + (virtualMemorySamples
                ?: emptyList()) + samples

        val profile = Logger.timedLog("Converting to Gecko format") {
            GeckoGenerator.createGeckoProfile(app, concatenatedSamples, loadedImageList, timeProfilerSettings)
        }

        Logger.timedLog("Gzipping and writing to disk") {
            profile.toFile(output)
        }
    }

    private fun Thread.addUncaughtExceptionHandler() = also {
        setUncaughtExceptionHandler { _, ex ->
            ex.printStackTrace()
            exitProcess(1)
        }
    }
}