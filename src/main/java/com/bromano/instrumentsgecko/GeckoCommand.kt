package com.bromano.instrumentsgecko

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import kotlin.concurrent.thread

fun main(args: Array<String>) = GeckoCommand().main(args)

class GeckoCommand : CliktCommand(help = "Convert Instruments Trace to Gecko Format (Firefox Profiler)") {

    private val input by option(
        "-i", "--input",
        help = "Input Instruments Trace",
    )
        .path(mustExist = true, canBeDir = true)
        .required()

    private val arch by option(
        "-a", "--arch",
        help = "Architecture of device instrumented (arm64e, x86_64)",
    ).default("arm64e")

    private val app by option("--app", help = "Name of app to match the dSyms to (e.g. YourApp)")
        .required()

    private val osVersion by option(
        "--os-version",
        help = "Name of OS Version to use for desymbolicating (e.g. 15.6, 16.1)"
    ).default("16.1")

    private val dSym by option(
        "--dsym",
        help = "Path to DSYM File for app. This can point to .dSYM or symbols directory with an app  (e.g. YourApp.app/YourApp)",
    )
        .path(mustExist = true, canBeDir = true)
        .required()

    private val shouldUseSupportDsyms by option(
        "--support",
        help = "Whether to de-symbolicate using iOS Device Support libraries (This takes ~1 minute)"
    ).flag("--no-support", default = false)

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
        lateinit var loadAddresses: List<Lib>
        lateinit var samples: List<InstrumentsSample>

        // These operations take ~3s each so we parallelize them
        Logger.timedLog("Loading Symbols and Load Address") {
            val thread1 = thread(start = true) { loadAddresses = InstrumentsParser.findDylibLoadAddresses(input, runNum) }
            val thread2 = thread(start = true) { samples = InstrumentsParser.loadSamples(input, runNum) }

            thread1.join()
            thread2.join()
        }

        val symbolsInfo = Logger.timedLog("Creating Symbols Mapping") {
            InstrumentsParser.desymbolicate(app, arch, osVersion, dSym, loadAddresses, samples, shouldUseSupportDsyms)
        }

        val profile = Logger.timedLog("Converting to Gecko format") {
            GeckoGenerator.createGeckoProfile(app, samples, symbolsInfo)
        }

        Logger.timedLog("Gzipping and writing to disk") {
            profile.toFile(output)
        }
    }
}
