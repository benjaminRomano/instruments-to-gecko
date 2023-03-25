package com.bromano.instrumentsgecko

import java.util.Collections
import java.lang.Math

private val libraryComparator = Comparator<Library> {libA, libB ->
    when {
        (libA.loadAddress > libB.loadAddress) -> 1
        (libA.loadAddress < libB.loadAddress) -> -1
        (libA.loadAddress == libB.loadAddress) -> 0
        else -> 0
    }

}

/**
 * Generate a Gecko File
 */
object GeckoGenerator {

    fun getLibraryPathForSymbol(libraryList: List<Library>, symbol: SymbolEntry): String? {
        val dummyLib = Library("", "", symbol.address, "")
        val position = Collections.binarySearch(libraryList, dummyLib, libraryComparator)

        /** If key is not present, Collections.binarySearch returns "(-(insertion point) - 1)".
         *  Since we're searching through a list of loaded libraries, we don't expect
         *  the symbol to match any of the values exactly. Instead, we'll look for the library
         *  that contains this address.
         *
         * For example, consider the following symbol lookup on the given sorted library list:
         *                       symbol@0x1000052
         *                             |
         *                     v<<<<<<<|
         *     +---------------+---------------+---------------+
         *     | dyld          | Foundation    | UIKit         |
         *     +---------------+---------------+---------------+
         *     0x1000000        0x1a00000        0x1f00000
         *
         *      Though it matches against insert position == 1, we should attribute this
         *      symbol to dyld.
         */

        if (position >= 0) {
            // Found an exact match. This is unlikely, but should still be handled.
            val res = libraryList.elementAt(position).path
            return res
        }

        // Inverse operation of -(insertion point) - 1
        val insertPosition = Math.abs(position) - 1
        if (insertPosition == 0) {
            return null
        }
        val res = libraryList.elementAt(insertPosition - 1).path
        return res

    }
    fun createGeckoProfile(
        app: String,
        samples: List<InstrumentsSample>,
        symbolsInfo: List<Library>,
    ): GeckoProfile {
        val threads = samples.groupBy {
            it.thread.tid
        }.map { (threadId, samples) ->
            val frameTable = mutableListOf<GeckoFrame>()
            val stackTable = mutableListOf<GeckoStack>()
            val stringTable = mutableListOf<String>()

            val frameMap = mutableMapOf<String, Long>()
            // Stored as (stackPrefixId, frameId)
            val stackMap = mutableMapOf<Pair<Long?, Long>, Long>()
            val stringMap = mutableMapOf<String, Long>()


            val geckoSamples = samples.map {

                // Intern Frame
                val frameIds = it.backtrace.map { frame ->
                    // Best effort try to find dsym string name
                    val dsymFrame = frame.name

                    // Intern String
                    val stringId = stringMap.getOrPut(dsymFrame) {
                        val stringId = stringTable.size.toLong()
                        stringTable.add(dsymFrame)
                        stringId
                    }

                    frameMap.getOrPut(dsymFrame) {
                        val frameId = frameTable.size.toLong()
                        frameTable.add(
                            GeckoFrame(
                                stringId = stringId,
                                category = getLibraryCategory(
                                    app,
                                    getLibraryPathForSymbol(symbolsInfo, frame)
                                )
                            )
                        )
                        frameId
                    }
                }

                // Intern Stacks
                var prefixId: Long? = null
                frameIds.reversed().forEach { frameId ->
                    prefixId = stackMap.getOrPut(Pair(prefixId, frameId)) {
                        val stackId = stackTable.size.toLong()
                        stackTable.add(
                            GeckoStack(
                                prefixId,
                                frameId,
                            )
                        )
                        stackId
                    }
                }

                GeckoSample(
                    stackId = prefixId,
                    timeMs = it.sampleTime
                )
            }

            GeckoThread(
                name = samples.firstOrNull()?.thread?.threadName ?: "<unknown>",
                tid = threadId,
                // Currently, we only support single process runs
                pid = 0,
                samples = GeckoSamples(data = geckoSamples.map { it.toData() }),
                stringTable = stringTable,
                frameTable = GeckoFrameTable(data = frameTable.map { it.toData() }),
                stackTable = GeckoStackTable(data = stackTable.map { it.toData() }),
            )

        }

        val traceStartTime = samples.map { it.sampleTime }.minOrNull() ?: 0

        return GeckoProfile(
            meta = GeckoMeta(startTime = traceStartTime),
            threads = threads,
        )
    }

    private fun getLibraryCategory(app: String, library: String?): Int {
        if (library == null) {
            return OTHER_CATEGORY
        }
        return when {
            library.contains(app) -> USER_CATEGORY
            library.contains("/System/Library/") -> FRAMEWORK_CATEGORY
            library.contains("/Symbols/usr/") -> LIBRARY_CATEGORY
            library.startsWith("/usr") -> LIBRARY_CATEGORY
            else -> OTHER_CATEGORY
        }
    }
}