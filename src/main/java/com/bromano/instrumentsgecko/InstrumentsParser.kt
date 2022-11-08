package com.bromano.instrumentsgecko

import org.w3c.dom.Document
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import org.xml.sax.InputSource
import java.io.File
import java.io.StringReader
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.streams.toList

data class InstrumentsSample(
    val threadName: String,
    val threadId: Int,
    val sampleTime: Long,
    val backtrace: List<String>
)

// These values come from kdebug.h
private const val DBG_DYLD = 31
private const val DBG_UUID = 5

// Ref: https://github.com/apple-oss-distributions/dyld/blob/main/dyld/Tracing.h#L40
private const val DBG_DYLD_UUID_MAP_A = 0
private const val DBG_DYLD_UUID_MAP_B = 1

private const val KDEBUG_CLASS_TAG = "kdebug-class"
private const val KDEBUG_SUBCLASS_TAG = "kdebug-subclass"
private const val KDEBUG_CODE_TAG = "kdebug-code"
private const val KDEBUG_FUNC_TAG = "kdebug-func"
private const val KDEBUG_STRING_TAG = "kdebug-string"
private const val RAW_STRING_TAG = "raw-string"
private const val TEXT_ADDRESSES_TAG = "text-addresses"
private const val THREAD_TAG = "thread"
private const val SAMPLE_TIME_TAG = "sample-time"
private const val BACKTRACE_TAG = "backtrace"
private const val TID_TAG = "tid"

data class SymbolsInfo(
    val addressToSymbol: Map<String, String>,
    val symbolToLib: Map<String, Lib>
)

data class Lib(val name: String, val loadAddress: String)

/**
 * Utilities for parsing Instruments files
 *
 * Note: This implementation is not very efficient. There are likely ways to significantly speed up the
 * XML operations.
 */
object InstrumentsParser {

    /**
     * Desymbolicate backtraces from a set of samples using dSYMs
     *
     * Note: This takes ~1m30s for desymbolicating using ~800 dSym files. Much of the cost is associated with `atos`
     * startup times. Possibly, there is a way to extract the desymbolicated symbols from the Instruments file directly
     */
    fun desymbolicate(
        app: String,
        arch: String,
        osVersion: String,
        dSym: Path,
        loadAddresses: List<Lib>,
        samples: List<InstrumentsSample>,
        shouldUseSupportDsyms: Boolean = false,
    ): SymbolsInfo {
        val addresses = samples.flatMap { it.backtrace }.distinct()
        assert(addresses.isNotEmpty()) { "Atleast one text address expected" }

        val addressesFile = File.createTempFile("address", ".txt").apply {
            writeText(addresses.joinToString("\n"))
        }

        // TODO: Do not assume iOS
        val deviceSupport = Paths.get(System.getProperty("user.home"))
            .resolve("Library/Developer/Xcode/iOS DeviceSupport")

        val baseSupportSymbolsPath = Files.list(deviceSupport)
            .filter { it.toString().contains(osVersion) }
            .findFirst().orElseGet {
                throw IllegalStateException("Could not find Device Support files for OS Version $osVersion")
            }.resolve("Symbols")

        // TODO: Is there a better way to de-obfuscate a list of addresses given a list of dSYMs?
        val desymbolicatedSymbolsList = loadAddresses.stream()
            .filter { it.name.contains(app) || shouldUseSupportDsyms }
            .parallel().map {
                val symbolFile = if (it.name.contains(app)) dSym else baseSupportSymbolsPath
                    // Remove leading slash; otherwise, base path is ignored
                    .resolve(it.name.drop(1))

                val result = try {
                    ShellUtils.run(
                        "atos -arch $arch -o '$symbolFile' -l ${it.loadAddress} -f $addressesFile",
                        shell = true,
                        redirectOutput = ProcessBuilder.Redirect.PIPE,
                    ).split("\n")
                } catch (e: Exception) {
                    addresses
                }

                Pair(it, result)
            }.toList()

        val symbolToLib = mutableMapOf<String, Lib>()

        var mergedDesymbolicatedSymbols = addresses
        desymbolicatedSymbolsList.forEach { (lib, symbolMapping) ->
            mergedDesymbolicatedSymbols = mergedDesymbolicatedSymbols.mapIndexed { index, value ->
                // Replace symbol if the mapping has a non-hex symbol
                if (symbolMapping[index].startsWith("0x")) {
                    value
                } else {
                    symbolToLib[symbolMapping[index]] = lib
                    symbolMapping[index]
                }
            }
        }

        return SymbolsInfo(addresses.zip(mergedDesymbolicatedSymbols).toMap(), symbolToLib)
    }

    /**
     * Extract Instrument Samples from trace
     *
     * Example:
     * <row>
     *     <sample-time id="177" fmt="00:00.411.046">411046250</sample-time>
     *     <thread ref="169"/>
     *     <process ref="40"/>
     *     <core ref="171"/><thread-state ref="172"/>
     *     <weight ref="173"/>
     *     <backtrace id="178" fmt="0x11d690681 ← (1 other frames)">
     *         <process ref="40"/>
     *         <text-addresses id="179" fmt="frag 801">4788389505</text-addresses>
     *         <process ref="40"/>
     *         <text-addresses id="180" fmt="frag 802">4788032064</text-addresses>
     *     </backtrace>
     * </row>
     */
    fun loadSamples(input: Path, runNum: Int = 1): List<InstrumentsSample> {
        // TODO: time-sample table may make more sense to parse
        val document = queryXCTrace(input, "/trace-toc[1]/run[$runNum]/data[1]/table[@schema=\"time-profile\"]")

        return document.getElementsByTagName(BACKTRACE_TAG)
            .asSequence()
            .map { backtraceNode ->
                val rowNode = backtraceNode.parentNode
                val originalBacktraceNode = getOriginalNode(document, backtraceNode)
                val backtraceId = originalBacktraceNode.getIdAttrValue()

                val sampleTime = rowNode.childNodesSequence()
                    .firstOrNull { n -> n.nodeName == SAMPLE_TIME_TAG }
                    ?.let {
                        getOriginalNode(document, it).getChildValue()?.toLongOrNull()?.let { it / 1000 / 1000 }
                    }
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

                val threadName = threadNode.getFmtAttrValue() ?: "<unknown>"

                val threadId = threadNode.childNodesSequence().first { it.nodeName == TID_TAG }
                    .let {
                        getOriginalNode(document, it).getChildValue()?.toIntOrNull()
                    } ?: -1

                // There can be multiple text address "fragments"
                // The first fragment contains addresses that are unique to this backtrace
                // Addresses are ordered top of stack to bottom
                val backtrace = originalBacktraceNode.childNodesSequence()
                    .filter { it.nodeName == TEXT_ADDRESSES_TAG }
                    .flatMap { it.getChildValue()?.split(" ") ?: emptyList() }
                    .map { "0x${BigInteger(it).toString(16)}" }
                    .toList()

                InstrumentsSample(
                    threadName = threadName,
                    threadId = threadId,
                    sampleTime = sampleTime,
                    backtrace = backtrace,
                )
            }.toList()
    }

    /**
     * Find load addresses of all Bundles loaded
     *
     * Load addresses are found by searching for KDBG_CODE(DBG_DYLD, DBG_DYLD_UUID, DBG_DYLD_UUID_MAP_A)
     * Subsequently search for the equivalent KDBG_CODE(DBG_DYLD, DBG_DYLD_UUID, DBG_DYLD_UUID_MAP_B)
     * which contains stringID of the bundle name.
     *
     *
     * Reference: https://github.com/apple-oss-distributions/dyld/blob/c8a445f88f9fc1713db34674e79b00e30723e79d/dyld/dyldMain.cpp#L680
     */
    fun findDylibLoadAddresses(input: Path, runNum: Int = 1): List<Lib> {
        // Note: There is a dyld-library-load table with this data which would likely be simpler to parse
        // However, querying for it by xpath returns empty results.
        val document = queryXCTrace(input, "/trace-toc/run[$runNum]/data/table[@schema=\"kdebug\"]")
        val stringTable = queryXCTrace(
            input,
            "/trace-toc/run[$runNum]/data/table[@schema=\"kdebug-strings\"][@codes=\"&quot;0x1f,0x05&quot; &quot;0x1f,0x07&quot; &quot;0x1f,0x08&quot;\"]"
        ) // ktlint-disable

        val mapA = findKDebugEntries(document, DBG_DYLD, DBG_UUID, DBG_DYLD_UUID_MAP_A)
        val mapB = findKDebugEntries(document, DBG_DYLD, DBG_UUID, DBG_DYLD_UUID_MAP_B)
        return mapA.zip(mapB).map { (mapA, mapB) ->
            val loadAddress = getKDebugFuncArg(document, mapA, 2).getFmtAttrValue()
                ?: throw IllegalStateException("Load address argument not found")

            val nameStringId = getKDebugFuncArg(document, mapB, 1).getFmtAttrValue()
                ?: throw IllegalStateException("Name string argument not found")

            val bundleName = findString(stringTable, nameStringId)
            Lib(bundleName, loadAddress)
        }.toList()
    }

    private fun findKDebugEntries(document: Document, clazz: Int, subclass: Int, code: Int): Sequence<Node> {
        val kDebugClassNodeId = findNodeId(document, KDEBUG_CLASS_TAG) {
            it.getChildValue()?.toIntOrNull() == clazz
        }

        val kDebugSubclassNodeId = findNodeId(document, KDEBUG_SUBCLASS_TAG) {
            it.getChildValue()?.toIntOrNull() == subclass
        }

        val kDebugCodeNodeId = findNodeId(document, KDEBUG_CODE_TAG) {
            it.getChildValue()?.toIntOrNull() == code
        }

        return findNodesById(document, KDEBUG_CLASS_TAG, kDebugClassNodeId)
            .filter {
                it.parentNode.childNodesSequence().any {
                    matchesNodeId(it, KDEBUG_SUBCLASS_TAG, kDebugSubclassNodeId)
                } && it.parentNode.childNodesSequence().any {
                    matchesNodeId(it, KDEBUG_CODE_TAG, kDebugCodeNodeId)
                }
            }.map { it.parentNode }
    }

    /**
     * Find the ID for some given tag, set of attributes and value
     *
     * The xctrace XML format tries to avoids duplication by using references.
     * That is the first time a node with a given tag, a set of attributes and a value is seen for the first time it is
     * assigned an ID.
     *
     * Subsequent nodes created with the same tag, set of attributes and value will be empty except for a "ref" attribute
     * that's value is the ID of the matching node.
     *
     * e.g. The following two nodes are equivalent:
     * <kdebug-class id="1" fmt="0x1">1</kdebug-class>
     * <kdebug-class ref="1 />
     *
     * @param matcher match the node to be found using its attributes and node value
     */
    private fun findNodeId(node: Document, tag: String, matcher: (n: Node) -> Boolean): Long {
        return node.getElementsByTagName(tag).asSequence().filter { it.getIdAttrValue() != null }
            .filter(matcher)
            .firstOrNull()?.getIdAttrValue()?.toLongOrNull()
            ?: throw IllegalStateException("No node with tag, $tag, matched")
    }

    private fun findOriginalNode(node: Document, tag: String, matcher: (n: Node) -> Boolean): Node {
        return node.getElementsByTagName(tag).asSequence().filter { it.getIdAttrValue() != null }
            .filter(matcher)
            .firstOrNull() ?: throw IllegalStateException("No node with tag, $tag, matched")
    }

    /**
     * Given a node ID find the node itself and all other nodes that reference it (i.e. tag matches and "ref" attribute matches ID)
     */
    private fun findNodesById(document: Document, tag: String, id: Long): Sequence<Node> {
        return document.getElementsByTagName(tag).asSequence()
            .filter { matchesNodeId(it, tag, id) }
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

    private fun matchesNodeId(node: Node, tag: String, id: Long): Boolean {
        return node.nodeName == tag &&
                (
                        node.getIdAttrValue()?.toLongOrNull() == id ||
                                node.getRefAttrValue()?.toLongOrNull() == id
                        )
    }

    /**
     * Run an xpath query against an xctrace file and return an XML Document
     */
    private fun queryXCTrace(input: Path, xpath: String): Document {
        val xmlStr = ShellUtils.run(
            "xctrace export --input $input  --xpath '$xpath'",
            redirectOutput = ProcessBuilder.Redirect.PIPE,
            shell = true,
        )

        // Remove XML Prolog (<xml? ... >) since parser can't handle it
        val trimmedXmlStr = xmlStr.split("\n", limit = 2)[1]

        val factory = DocumentBuilderFactory.newInstance()
        val docBuilder = factory.newDocumentBuilder()
        return docBuilder.parse(InputSource(StringReader(trimmedXmlStr)))
    }

    /**
     * Get kdebug-arg node at given index for kdebug-func
     *
     * Note: argIndex is zero-indexed
     */
    private fun getKDebugFuncArg(document: Document, node: Node, argIndex: Int): Node {
        val funcNode = node.childNodesSequence().first { it.nodeName == KDEBUG_FUNC_TAG }
        var arg: Node = funcNode.nextSibling
        repeat(argIndex) { arg = arg.nextSibling }

        return getOriginalNode(document, arg)
    }

    /**
     * Find String in table given a String ID
     *
     * @param stringId the stringID in Hex format
     */
    private fun findString(stringTable: Document, stringId: String): String {
        val kDebugStringNode = findOriginalNode(stringTable, KDEBUG_STRING_TAG) { it.getFmtAttrValue() == stringId }

        val rawStringNode = kDebugStringNode.parentNode.childNodesSequence()
            .firstOrNull { it.nodeName == RAW_STRING_TAG }
            ?: throw IllegalStateException("No string found with id, $stringId")

        return getOriginalNode(stringTable, rawStringNode).getChildValue()
            ?: throw IllegalStateException("No string found with id, $stringId")
    }

    private fun Node.getFmtAttrValue(): String? = attributes?.getNamedItem("fmt")?.nodeValue

    private fun Node.getIdAttrValue(): String? = attributes?.getNamedItem("id")?.nodeValue

    private fun Node.getRefAttrValue(): String? = attributes?.getNamedItem("ref")?.nodeValue

    private fun Node.getChildValue(): String? = firstChild?.nodeValue

    private fun Node.childNodesSequence(): Sequence<Node> = childNodes.asSequence()

    private fun NodeList.asSequence(): Sequence<Node> {
        var i = 0
        return generateSequence { item(i++) }
    }
}
