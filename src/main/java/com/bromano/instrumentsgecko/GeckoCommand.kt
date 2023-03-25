package com.bromano.instrumentsgecko

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
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

    private val app by option("--app", help = "Name of app to match the dSyms to (e.g. YourApp)")
        .required()

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

        // These operations take ~3s each so we parallelize them
        Logger.timedLog("Loading Symbols and Load Address") {
            val thread1 = thread(start = true) { samples = InstrumentsParser.loadSamples(input, runNum) }
            val thread2 = thread(start = true) { loadedImageList = InstrumentsParser.sortedImageList(input, runNum)}
            thread1.setUncaughtExceptionHandler { _, ex ->
                ex.printStackTrace()
                exitProcess(1)
            }
            thread2.setUncaughtExceptionHandler { _, ex ->
                ex.printStackTrace()
                exitProcess(1)
            }
            thread1.join()
            thread2.join()
        }

        val profile = Logger.timedLog("Converting to Gecko format") {
            GeckoGenerator.createGeckoProfile(app, samples, loadedImageList)
        }

        Logger.timedLog("Gzipping and writing to disk") {
            profile.toFile(output)
        }
    }
}