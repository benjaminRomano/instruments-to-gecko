package com.bromano.instrumentsgecko

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import org.xml.sax.InputSource
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Path
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

private const val THREAD_TAG = "thread"
private const val THREAD_STATE_TAG = "thread-state"
private const val WEIGHT_TAG = "weight"
const val SAMPLE_TIME_TAG = "sample-time"
private const val BACKTRACE_TAG = "backtrace"
private const val TID_TAG = "tid"
private const val FRAME_TAG = "frame"
private const val BINARY_TAG = "binary"
private const val ROW_TAG = "row"
private const val RUN_TAG = "run"
private const val TABLE_TAG = "table"
private const val VM_OP_TAG = "vm-op"
const val START_TIME_TAG = "start-time"

const val TIME_PROFILE_SCHEMA = "time-profile"
const val VIRTUAL_MEMORY_SCHEMA = "virtual-memory"
private const val THREAD_STATE_SCHEMA = "thread-state"
const val SYSCALL_SCHEMA = "syscall"

private const val NUMBER_ATTR = "number"
private const val SCHEMA_ATTR = "schema"


/**
 * ThreadDescription contains a thread name and ID to identify
 * a given thread discovered in an Instruments documnent.
 */
data class ThreadDescription(
    val threadName: String,
    val tid: Int
) {
    override fun toString(): String {
        return "$threadName (tid: $tid)"
    }
}

data class InstrumentsSettings(
    val highFrequency: Boolean,
    val waitingThreads: Boolean,
    val contextSwitches: Boolean,
    val kernelCallstacks: Boolean,
    val hasThreadStates: Boolean,
    val hasVirtualMemory: Boolean,
    val hasSyscalls: Boolean,
)

/**
 * InstrumentsSample represents a profile sample discovered in an
 * Instruments document. This contains information about the thread
 * being sampled, such as when it was sampled and a corresponding backtrace.
 */
data class InstrumentsSample(
    val thread: ThreadDescription,
    val sampleTime: Double,
    val weightMs: Double,
    val backtrace: List<SymbolEntry>
) {
    override fun toString(): String {
        return """
        |Time: $sampleTime
        |$thread:
        |${"\t"}${backtrace.reversed().joinToString("\n\t")}
        """.trimMargin()
    }

}

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
) {
    override fun toString(): String {
        return name
    }
}

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
        val document = queryXCTrace(input, "/trace-toc[1]/run[$runNum]/data[1]/table[@schema=\"$TIME_PROFILE_SCHEMA\"]")
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

    fun getInstrumentsSettings(input: Path, runNum: Int = 1): InstrumentsSettings {
        val runNode = queryXCTraceTOC(input)
            .getElementsByTagName(RUN_TAG)
            .asSequence()
            .firstOrNull {
                it.getAttrValue(NUMBER_ATTR) == runNum.toString()
            }

        val runElement =
            runNode as? Element ?: throw IllegalStateException("Cannot find run $runNum in table of contents")

        val timeProfileNodes = runElement.getElementsByTagName(TABLE_TAG)
            .asSequence()
            .filter { it.getAttrValue(SCHEMA_ATTR) == TIME_PROFILE_SCHEMA }
            .toList()

        val hasThreadStates = runElement.getElementsByTagName(TABLE_TAG)
            .asSequence()
            .any { it.getAttrValue(SCHEMA_ATTR) == THREAD_STATE_SCHEMA }

        val hasVirtualMemory = runElement.getElementsByTagName(TABLE_TAG)
            .asSequence()
            .any { it.getAttrValue(SCHEMA_ATTR) == VIRTUAL_MEMORY_SCHEMA }

        val hasSyscalls = runElement.getElementsByTagName(TABLE_TAG)
            .asSequence()
            .any { it.getAttrValue(SCHEMA_ATTR) == SYSCALL_SCHEMA }


        return InstrumentsSettings(
            timeProfileNodes.any { it.getAttrValue("high-frequency-sampling") == "1" },
            timeProfileNodes.any { it.getAttrValue("record-waiting-threads") == "1" },
            timeProfileNodes.any { it.getAttrValue("context-switch-sampling") == "1" },
            timeProfileNodes.any { it.getAttrValue("needs-kernel-callstack") == "1" },
            hasThreadStates,
            hasVirtualMemory,
            hasSyscalls
        )
    }

    /**
     * Extract Instrument Samples from trace
     *
     * Note: Samples may not be in-order. In some scenarios, Instruments returns out-of-order data.
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
    fun loadSamples(schema: String, timeTag: String, input: Path, runNum: Int = 1): List<InstrumentsSample> {
        val document = queryXCTrace(input, "/trace-toc[1]/run[$runNum]/data[1]/table[@schema=\"$schema\"]")

        val originalNodeCache = mutableMapOf<String, Node>()
        preloadTags(
            document,
            listOf(BACKTRACE_TAG, timeTag, VM_OP_TAG, WEIGHT_TAG, THREAD_TAG, TID_TAG),
            originalNodeCache
        )

        val previousBacktraces = mutableMapOf<String, SymbolEntry>()
        return document.getElementsByTagName(BACKTRACE_TAG)
            .asSequence()
            .map { backtraceNode ->
                val rowNode = backtraceNode.parentNode
                val originalBacktraceNode = getOriginalNode(document, backtraceNode, originalNodeCache)

                val sampleTime = rowNode.getFirstOriginalNodeByTag(document, timeTag, originalNodeCache)
                    .asTimeValue()
                    ?: throw IllegalStateException(
                        "Cannot find $timeTag for:\n${backtraceNode.toXMLString(true)}"
                    )

                val threadNode = rowNode.getFirstOriginalNodeByTag(document, THREAD_TAG, originalNodeCache)

                // The duration of the sample (Time Profile only)
                val weightMs = rowNode.getOptionalFirstOriginalNodeByTag(document, WEIGHT_TAG, originalNodeCache)
                    ?.asTimeValue()
                    ?: 0.0

                val threadName = threadNode.getFmtAttrValue() ?: "<unknown>"

                val threadId = threadNode.getFirstOriginalNodeByTag(document, TID_TAG, originalNodeCache)
                    .getChildValue()?.toIntOrNull() ?: -1

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
                    .toMutableList()

                // Append virtual memory operation onto callstack if it exists (e.g. Page Fault)
                rowNode.getOptionalFirstOriginalNodeByTag(document, VM_OP_TAG, originalNodeCache)
                    ?.getFmtAttrValue()
                    ?.let { backtrace.add(0, SymbolEntry(VIRTUAL_MEMORY_ADDR, it)) }

                InstrumentsSample(
                    thread = ThreadDescription(threadName, threadId),
                    sampleTime = sampleTime,
                    weightMs = weightMs,
                    backtrace = backtrace,
                )
            }.toList()
    }

    /**
     * Convert Idle Thread State transitions into Instruments Samples
     *
     * Note: Samples may not be in-order. In some rare cases, Instruments returns out-of-order data.
     */
    fun loadIdleThreadSamples(input: Path, runNum: Int): List<InstrumentsSample> {
        val document = queryXCTrace(input, "/trace-toc[1]/run[$runNum]/data[1]/table[@schema=\"thread-state\"]")
        val originalNodeCache = mutableMapOf<String, Node>()
        preloadTags(document, listOf(THREAD_STATE_TAG, START_TIME_TAG, THREAD_TAG, TID_TAG), originalNodeCache)

        return document.getElementsByTagName(THREAD_STATE_TAG)
            .asSequence()
            .filter {
                it.parentNode?.nodeName == ROW_TAG &&
                        getOriginalNode(document, it, originalNodeCache).getIdAttrValue() == "Idle"
            }.map { threadStateNode ->
                val rowNode = threadStateNode.parentNode
                val sampleTime = rowNode.getFirstOriginalNodeByTag(document, START_TIME_TAG, originalNodeCache)
                    .asTimeValue()
                    ?: throw IllegalStateException("row does not have start-time:\n${rowNode.toXMLString(true)}")

                val threadNode = rowNode.getFirstOriginalNodeByTag(document, THREAD_TAG, originalNodeCache)
                val threadName = threadNode.getFmtAttrValue() ?: "<unknown>"
                val threadId = threadNode.getFirstOriginalNodeByTag(document, TID_TAG, originalNodeCache)
                    .let { getOriginalNode(document, it, originalNodeCache).getChildValue()?.toIntOrNull() ?: -1 }

                InstrumentsSample(
                    thread = ThreadDescription(threadName, threadId),
                    sampleTime = sampleTime,
                    weightMs = 0.0,
                    backtrace = emptyList()
                )
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

        return processXCTraceOutput(xmlStr)
    }

    /**
     * Get the Table of Contents
     *
     * Note: It doesn't seem possible to use `--xpath` to query the TOC
     */
    private fun queryXCTraceTOC(input: Path): Document {
        val xmlStr = ShellUtils.run(
            "xctrace export --input $input --toc",
            redirectOutput = ProcessBuilder.Redirect.PIPE,
            shell = true
        )

        return processXCTraceOutput(xmlStr)
    }

    private fun processXCTraceOutput(xmlStr: String): Document {
        // Remove XML Prolog (<xml? ... >) since parser can't handle it
        val trimmedXmlStr = xmlStr.split("\n", limit = 2)[1]

        // Save original system properties
        val originalProperties = mutableMapOf<String, String?>()
        val securityProperties = listOf(
            "jdk.xml.maxGeneralEntitySizeLimit",
            "jdk.xml.maxParameterEntitySizeLimit", 
            "jdk.xml.entityExpansionLimit",
            "jdk.xml.elementAttributeLimit",
            "jdk.xml.maxXMLNameLimit",
            "jdk.xml.totalEntitySizeLimit"
        )

        try {
            // Set system properties to disable limits
            securityProperties.forEach { prop ->
                originalProperties[prop] = System.getProperty(prop)
                System.setProperty(prop, "0") // 0 means unlimited for most properties
            }

            val factory = DocumentBuilderFactory.newInstance()
            // Try to set factory attributes as well (fallback)
            try {
                factory.setAttribute("jdk.xml.maxGeneralEntitySizeLimit", 0)
                factory.setAttribute("jdk.xml.maxParameterEntitySizeLimit", 0)
                factory.setAttribute("jdk.xml.entityExpansionLimit", 0)
                factory.setAttribute("jdk.xml.elementAttributeLimit", 0)
                factory.setAttribute("jdk.xml.maxXMLNameLimit", 0)
                factory.setAttribute("jdk.xml.totalEntitySizeLimit", 0)
            } catch (e: IllegalArgumentException) {
                // Some attributes might not be supported, continue anyway
            }
            
            return factory
                .newDocumentBuilder()
                .parse(InputSource(StringReader(trimmedXmlStr)))
        } finally {
            // Restore original system properties
            securityProperties.forEach { prop ->
                val originalValue = originalProperties[prop]
                if (originalValue != null) {
                    System.setProperty(prop, originalValue)
                } else {
                    System.clearProperty(prop)
                }
            }
        }
    }

    /**
     * XCTrace XML output avoids duplicating data by having teh first node contain all the information and subsequent
     * nodes containing a reference to the original node.
     *
     * The original node is given a unique identifier using `id` attribute and the subsequent nodes use a
     * `ref` attribute with the value set to the `id` of the original node.
     *
     * Scanning the XML is expensive os to reduce that cost an originalNodeCache is expected to be provided.
     *
     * Note: This method will mutate the originalNodeCache provided.
     */
    private fun getOriginalNode(document: Document, node: Node, originalNodeCache: MutableMap<String, Node>): Node {
        // If ID attribute exists, we are already at original node
        if (node.getIdAttrValue() != null) {
            return node
        }

        val refId = node.getRefAttrValue()?.toLongOrNull()
            ?: throw IllegalStateException("Node with tag, ${node.nodeName} does not have id or ref attribute")

        val cacheKey = "${node.nodeName}:$refId"

        originalNodeCache[cacheKey]?.let { return it }

        return findNodeById(document, node.nodeName, refId).also {
            originalNodeCache[cacheKey] = it
        }
    }

    /**
     * Pre-compute the set of ID nodes to avoid expensive re-processing of XML Tree Nodes
     */
    private fun preloadTags(node: Document, tags: List<String>, originalNodeCache: MutableMap<String, Node>) {
        for (tag in tags) {
            node.getElementsByTagName(tag)
                .asSequence()
                .forEach { it.getIdAttrValue()?.let { refId -> originalNodeCache["$tag:$refId"] = it } }
        }
    }

    private fun findNodeById(node: Document, tag: String, id: Long): Node {
        return node.getElementsByTagName(tag).asSequence()
            .filter { it.getIdAttrValue()?.toLongOrNull() == id }
            .firstOrNull()
            ?: throw IllegalStateException("No node with tag, $tag, and id, $id, found.")
    }

    // TODO: Remove the unnecessary utility methods?
    private fun Node.getAttrValue(attr: String): String? = attributes?.getNamedItem(attr)?.nodeValue
    private fun Node.getFmtAttrValue(): String? = getAttrValue("fmt")
    private fun Node.getIdAttrValue(): String? = getAttrValue("id")
    private fun Node.getRefAttrValue(): String? = getAttrValue("ref")
    private fun Node.getNameAttrValue(): String? = getAttrValue("name")
    private fun Node.getAddrAttrValue(): String? = getAttrValue("addr")
    private fun Node.getPathAttrValue(): String? = getAttrValue("path")
    private fun Node.getLoadAddrAttrValue(): String? = getAttrValue("load-addr")
    private fun Node.getUUIDAttrValue(): String? = getAttrValue("UUID")
    private fun Node.getArchAttrValue(): String? = getAttrValue("arch")

    private fun Node.getChildValue(): String? = firstChild?.nodeValue

    private fun Node.asTimeValue(): Double? = firstChild?.nodeValue?.toDoubleOrNull()?.let { it / 1000.0 / 1000.0 }

    private fun Node.childNodesSequence(): Sequence<Node> = childNodes.asSequence()

    private fun NodeList.asSequence(): Sequence<Node> {
        var i = 0
        return generateSequence { item(i++) }
    }

    /**
     * Find direct descendant with a matching tag then find it's original node if not already the original
     */
    private fun Node.getFirstOriginalNodeByTag(
        document: Document,
        tag: String,
        originalNodeCache: MutableMap<String, Node>
    ): Node {
        return getOptionalFirstOriginalNodeByTag(document, tag, originalNodeCache)
            ?: throw IllegalStateException("Could not find original node with tag, $tag:\n ${toXMLString(true)}")
    }

    private fun Node.getOptionalFirstOriginalNodeByTag(
        document: Document,
        tag: String,
        originalNodeCache: MutableMap<String, Node>
    ): Node? {
        return this.childNodesSequence().firstOrNull { it.nodeName == tag }
            ?.let { getOriginalNode(document, it, originalNodeCache) }
    }

    /**
     * Convert node to pretty-printed XML String
     *
     * Ref: https://stackoverflow.com/questions/33935718/save-new-xml-node-to-file
     */
    private fun Node.toXMLString(deep: Boolean = false): String {
        val clonedNode = this.cloneNode(true)
        // Remove unwanted whitespaces
        clonedNode.normalize()
        val xpath = XPathFactory.newInstance().newXPath()
        val expr = xpath.compile("//text()[normalize-space()='']")
        val nodeList = expr.evaluate(clonedNode, XPathConstants.NODESET) as NodeList

        if (!deep) {
            for (i in 0 until nodeList.length) {
                val node = nodeList.item(i)
                node.parentNode.removeChild(node)
            }
        }

        // Create and setup transformer
        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
            setOutputProperty(OutputKeys.INDENT, "yes")
            setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4")
        }

        // Turn the node into a string
        return StringWriter().use {
            transformer.transform(DOMSource(this), StreamResult(it))
            it.toString()
        }
    }
}