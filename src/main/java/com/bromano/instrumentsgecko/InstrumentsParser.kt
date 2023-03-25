package com.bromano.instrumentsgecko

import org.w3c.dom.Document
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import org.xml.sax.InputSource
import java.io.StringReader
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.system.exitProcess


/**
 * ThreadDescription contains a thread name and ID to identify
 * a given thread discovered in an Instruments documnent.
 */
data class ThreadDescription(
    val threadName: String,
    val tid: Int
)

/**
 * InstrumentsSample represents a profile sample discovered in an
 * Instruments document. This contains information about the thread
 * being sampled, such as when it was sampled and a corresponding backtrace.
 */
data class InstrumentsSample(
    val thread: ThreadDescription,
    val sampleTime: Long,
    val backtrace: List<SymbolEntry>
)

/**
 * Library represents a loaded binary image. This can be the primary executable
 * or any number of shared libraries / frameworks that were part of the process'
 * address space.
 */
data class Library(
    val path: String,
    val uuid: String,
    val loadAddress: ULong,
    val arch: String
)

/**
 * SymbolEntry represents a symbol, such as from a backtrace.
 */
data class SymbolEntry(
    val address: ULong,
    val name: String,
)

private const val THREAD_TAG = "thread"
private const val WEIGHT_TAG = "weight"
private const val SAMPLE_TIME_TAG = "sample-time"
private const val BACKTRACE_TAG = "backtrace"
private const val TID_TAG = "tid"
private const val FRAME_TAG = "frame"
private const val BINARY_TAG = "binary"

/**
 * Utilities for parsing Instruments files
 */
object InstrumentsParser {

    /**
     * Create a sorted list of Libraries suitable for lookup. These are sorted
     * so address queries can be resolved via binary search.
     */
    fun sortedImageList(input: Path, runNum: Int = 1): List<Library> {
        val mapped = createBinaryImageMapping(input, runNum)
        return mapped.values.sortedBy { it.loadAddress }
    }

    /**
     * Returns a mapping between a unique libraryID and the Library itself. This represents
     * the executable address space of the given process.
     *
     * Example: (<binary> tag)
     *
     *  <row>
     *   <sample-time id="1" fmt="00:00.049.937">49937041</sample-time>
     *   <thread ref="2"/>
     *   <process ref="4"/>
     *   <core ref="66"/>
     *   <thread-state ref="8"/>
     *   <weight id="585" fmt="1.13 ms">1127125</weight>
     *    <backtrace id="10">
     *       <frame id="11" name="_dyld_start" addr="0x11e52aba1">
     *         <binary id="12" name="dyld" UUID="74EAC11C-B88E-3D1E-ACDB-56EC661BB4C0" arch="arm64e" load-addr="0x11e510000" path="/usr/lib/dyld"/>
     *       </frame>
     *     </backtrace>
     *   </row>
     */
    private fun createBinaryImageMapping(input: Path, runNum: Int = 1): Map<String, Library> {
        val idToLibrary = mutableMapOf<String, Library>()
        val document = queryXCTrace(input, "/trace-toc[1]/run[$runNum]/data[1]/table[@schema=\"time-profile\"]")
        document.getElementsByTagName(FRAME_TAG)
            .asSequence()
            .flatMap { it.childNodesSequence() }
            .filter { it.nodeName == BINARY_TAG }
            .forEach {
                val binaryId = it.getIdAttrValue()
                val binaryPath = it.getPathAttrValue()
                val loadAddr = it.getLoadAddrAttrValue()
                val arch = it.getArchAttrValue()
                val uuid = it.getUUIDAttrValue()
                if (binaryId != null && binaryPath != null && loadAddr != null && arch != null && uuid != null) {
                    val library = Library(binaryPath, uuid, loadAddr.removePrefix("0x").toULong(16), arch)
                    idToLibrary[binaryId] = library
                }
            }
        return idToLibrary
    }

    /**
     * Extract Instrument Samples from trace
     *
     * Example:
     * <row>
     *     <sample-time id="584" fmt="00:00.178.410">178410208</sample-time>
     *     <thread ref="2"/>
     *     <process ref="4"/>
     *     <core ref="66"/>
     *     <thread-state ref="8"/>
     *     <weight id="585" fmt="1.13 ms">1127125</weight>
     *     <backtrace id="586">
     *         <frame id="587" name="__semwait_signal" addr="0x1e3a00a2d">
     *         <binary ref="123"/>
     *         </frame>
     *         <frame id="588" name="nanosleep" addr="0x1b6efe0e4">
     *         <binary id="589" name="libsystem_c.dylib" UUID="07B35AA1-E884-36B0-9027-55C91BACAA46" arch="arm64e" load-addr="0x1b6ef9000" path="/usr/lib/system/libsystem_c.dylib"/>
     *         </frame>
     *         <frame id="590" name="usleep" addr="0x1b6efee14">
     *         <binary ref="589"/>
     *         </frame>
     *         <frame id="591" name="SCDocObjectBusyHandler(void*, int)" addr="0x1061a1e44">
     *         <binary ref="180"/>
     *         </frame>
     *     </backtrace>
     *  </row>
     */
    fun loadSamples(input: Path, runNum: Int = 1): List<InstrumentsSample> {
        val document = queryXCTrace(input, "/trace-toc[1]/run[$runNum]/data[1]/table[@schema=\"time-profile\"]")

        // Map of thread ids to last sample time
        val timeSinceLastSample = mutableMapOf<Int, Long>()

        val previousBacktraces = mutableMapOf<String, SymbolEntry>()
        return document.getElementsByTagName(BACKTRACE_TAG)
            .asSequence()
            .flatMap { backtraceNode ->
                val rowNode = backtraceNode.parentNode
                val originalBacktraceNode = getOriginalNode(document, backtraceNode)
                val backtraceId = originalBacktraceNode.getIdAttrValue()

                val sampleTime = rowNode.childNodesSequence()
                    .firstOrNull { n -> n.nodeName == SAMPLE_TIME_TAG }
                    ?.let { getOriginalNode(document, it).getChildValue()?.toLong()?.let { it / 1000 / 1000 } }
                    ?: throw IllegalStateException(
                        "$SAMPLE_TIME_TAG node with value not found for backtrace with id $backtraceId"
                    )

                val threadNode = rowNode.childNodesSequence()
                    .firstOrNull { n -> n.nodeName == THREAD_TAG }
                    ?.let {
                        getOriginalNode(document, it)
                    }
                    ?: throw IllegalStateException(
                        "$THREAD_TAG node not found for backtrace with id $backtraceId"
                    )

                // The expected time between samples
                val weightMs = rowNode.childNodesSequence()
                    .firstOrNull { n -> n.nodeName == WEIGHT_TAG }
                    ?.let {
                        getOriginalNode(document, it)
                    }?.getChildValue()?.toLong()?.let { it / 1000 / 1000 }
                    ?: throw IllegalStateException(
                        "$WEIGHT_TAG node not found for backtrace with id $backtraceId"
                    )

                val threadName = threadNode.getFmtAttrValue() ?: "<unknown>"

                val threadId = threadNode.childNodesSequence().first { it.nodeName == TID_TAG }
                    .let {
                        getOriginalNode(document, it).getChildValue()?.toIntOrNull()
                    } ?: -1

                // There can be multiple text address "fragments"
                // The first fragment contains addresses that are unique to this backtrace
                // Addresses are ordered top of stack to bottom

                val backtrace = originalBacktraceNode.childNodesSequence()
                    .filter { it.nodeName == FRAME_TAG }
                    .map {
                        val name = it.getNameAttrValue() ?: "<unknown>"
                        val addr = it.getAddrAttrValue()
                        val ref = it.getRefAttrValue() ?: "<NO_REF>"
                        val id = it.getIdAttrValue()
                        if (name == "<unknown>") {
                            // Unnamed frame, check if this is a reference
                            // back to an earlier frame we inspected.
                            previousBacktraces.getOrDefault(ref, null)
                                ?: SymbolEntry(ULong.MAX_VALUE, name)
                        } else {
                            if (addr != null) {
                                // Found a full frame description. Store an ID mapping
                                // back to this frame for future lookups.
                                val sym = SymbolEntry(addr.removePrefix("0x").toULong(16), name)
                                if (id != null) {
                                    previousBacktraces[id] = sym
                                }
                                sym
                            } else {
                                // Found a partial frame description (no address, but we have a symbol name).
                                val sym = SymbolEntry(ULong.MAX_VALUE, name)
                                if (id != null) {
                                    previousBacktraces[id] = sym
                                }
                                sym
                            }
                        }
                    }
                    .toList()

                val sample = InstrumentsSample(
                    thread = ThreadDescription(threadName, threadId),
                    sampleTime = sampleTime,
                    backtrace = backtrace,
                )

                // When off-cpu sampling is not enabled, callstack durations will be distorted as the callstack
                // duration is computed as time since last collected sample.
                // To address this, insert empty backtraces if the time since last sample exceeds
                // the expected sample weight (i.e. sampling frequency).
                //
                // Ref: https://github.com/firefox-devtools/profiler/issues/2962#issuecomment-1480217402
                timeSinceLastSample[threadId]?.let {
                    timeSinceLastSample[threadId] = sampleTime
                    val delta = sampleTime - it
                    // Add an arbitrary buffer to avoid false positives where the expected gap between samples
                    // is slightly off from actual
                    if (delta > 5 * weightMs) {
                        listOf(
                            InstrumentsSample(
                                thread = ThreadDescription(threadName, threadId),
                                sampleTime = it + weightMs,
                                backtrace = emptyList(),
                            ),
                            sample,
                        )
                    } else {
                        listOf(sample)
                    }
                } ?: let {
                    timeSinceLastSample[threadId] = sampleTime
                    listOf(sample)
                }
            }.toList()
    }

    /**
     * Run a xpath query against a xctrace file and return an XML Document
     */
    private fun queryXCTrace(input: Path, xpath: String): Document {
        val xmlStr = ShellUtils.run(
            "xctrace export --input $input  --xpath '$xpath'",
            redirectOutput = ProcessBuilder.Redirect.PIPE,
            shell = true
        )

        // Remove XML Prolog (<xml? ... >) since parser can't handle it
        val trimmedXmlStr = xmlStr.split("\n", limit = 2)[1]

        val factory = DocumentBuilderFactory.newInstance()
        val docBuilder = factory.newDocumentBuilder()
        return docBuilder.parse(InputSource(StringReader(trimmedXmlStr)))
    }

    private fun getOriginalNode(document: Document, node: Node): Node {
        // If ID attribute exists, we are already at original node
        if (node.getIdAttrValue() != null) {
            return node
        }

        val refId = node.getRefAttrValue()?.toLongOrNull()
            ?: throw IllegalStateException("Node with tag, ${node.nodeName} does not have id or ref attribute")

        return findNodeById(document, node.nodeName, refId)
    }

    private fun findNodeById(node: Document, tag: String, id: Long): Node {
        return node.getElementsByTagName(tag).asSequence()
            .filter { it.getIdAttrValue()?.toLongOrNull() == id }
            .firstOrNull()
            ?: throw IllegalStateException("No node with tag, $tag, and id, $id, found.")
    }

    private fun Node.getFmtAttrValue(): String? = attributes?.getNamedItem("fmt")?.nodeValue

    private fun Node.getIdAttrValue(): String? = attributes?.getNamedItem("id")?.nodeValue

    private fun Node.getRefAttrValue(): String? = attributes?.getNamedItem("ref")?.nodeValue

    private fun Node.getNameAttrValue(): String? = attributes?.getNamedItem("name")?.nodeValue

    private fun Node.getAddrAttrValue(): String? = attributes?.getNamedItem("addr")?.nodeValue

    private fun Node.getPathAttrValue(): String? = attributes?.getNamedItem("path")?.nodeValue

    private fun Node.getLoadAddrAttrValue(): String? = attributes?.getNamedItem("load-addr")?.nodeValue

    private fun Node.getUUIDAttrValue(): String? = attributes?.getNamedItem("UUID")?.nodeValue

    private fun Node.getArchAttrValue(): String? = attributes?.getNamedItem("arch")?.nodeValue

    private fun Node.getChildValue(): String? = firstChild?.nodeValue

    private fun Node.childNodesSequence(): Sequence<Node> = childNodes.asSequence()

    private fun NodeList.asSequence(): Sequence<Node> {
        var i = 0
        return generateSequence { item(i++) }
    }
}