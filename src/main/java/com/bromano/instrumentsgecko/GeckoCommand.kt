package com.bromano.instrumentsgecko

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import kotlin.concurrent.thread
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    // Configure XML security properties once at application startup
    // to handle large Instruments trace files without entity size limits
    configureXMLSecurityProperties()
    
    GeckoCommand().main(args)
}

/**
 * Configure XML security properties to disable entity size limits.
 * This allows parsing of large Instruments trace files that exceed default XML security limits.
 */
private fun configureXMLSecurityProperties() {
    val xmlSecurityProperties = mapOf(
        "jdk.xml.maxGeneralEntitySizeLimit" to "0",
        "jdk.xml.maxParameterEntitySizeLimit" to "0", 
        "jdk.xml.entityExpansionLimit" to "0",
        "jdk.xml.elementAttributeLimit" to "0",
        "jdk.xml.maxXMLNameLimit" to "0",
        "jdk.xml.totalEntitySizeLimit" to "0"
    )
    
    xmlSecurityProperties.forEach { (property, value) ->
        System.setProperty(property, value)
    }
}

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

        // xctrace queries can be quite slow so parallelize them
        Logger.timedLog("Loading Symbols, Samples and Load Addresses...") {
            val thread1 = thread(start = true) {
                samples = InstrumentsParser.loadSamples(TIME_PROFILE_SCHEMA, SAMPLE_TIME_TAG, input, runNum)
            }
                .addUncaughtExceptionHandler()

            val thread2 = thread(start = true) { loadedImageList = InstrumentsParser.sortedImageList(input, runNum) }
                .addUncaughtExceptionHandler()

            val thread3: Thread? = if (timeProfilerSettings.hasThreadStates) {
                thread(start = true) { threadIdSamples = InstrumentsParser.loadIdleThreadSamples(input, runNum) }
                    .addUncaughtExceptionHandler()
            } else null

            val thread4: Thread? = if (timeProfilerSettings.hasVirtualMemory) {
                thread(start = true) {
                    virtualMemorySamples =
                        InstrumentsParser.loadSamples(VIRTUAL_MEMORY_SCHEMA, START_TIME_TAG, input, runNum)
                }
                    .addUncaughtExceptionHandler()
            } else null

            val thread5: Thread? = if (timeProfilerSettings.hasSyscalls) {
                thread(start = true) {
                    syscallSamples = InstrumentsParser.loadSamples(SYSCALL_SCHEMA, START_TIME_TAG, input, runNum)
                }
                    .addUncaughtExceptionHandler()
            } else null

            thread1.join()
            thread2.join()
            thread3?.join()
            thread4?.join()
            thread5?.join()
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