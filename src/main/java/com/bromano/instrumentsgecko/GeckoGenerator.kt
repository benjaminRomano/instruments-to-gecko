package com.bromano.instrumentsgecko


/**
 * Generate a Gecko File
 */
object GeckoGenerator {

    fun createGeckoProfile(
        app: String,
        samples: List<InstrumentsSample>,
        symbolsInfo: SymbolsInfo
    ): GeckoProfile {
        val threads = samples.groupBy {
            it.threadId
        }.map { (threadId, samples) ->
            val frameTable = mutableListOf<GeckoFrame>()
            val stackTable = mutableListOf<GeckoStack>()
            val stringTable = mutableListOf<String>()

            val frameMap = mutableMapOf<String, Int>()
            // Stored as (stackPrefixId, frameId)
            val stackMap = mutableMapOf<Pair<Int?, Int>, Int>()

            val stringMap = mutableMapOf<String, Int>()

            val geckoSamples = samples.map {

                // Intern Frame
                val frameIds = it.backtrace.map { frame ->
                    // Best effort try to find dsym string name
                    val dsymFrame = symbolsInfo.addressToSymbol.getOrDefault(frame, frame)

                    // Intern String
                    val stringId = stringMap.getOrPut(dsymFrame) {
                        val stringId = stringTable.size
                        stringTable.add(dsymFrame)
                        stringId
                    }

                    frameMap.getOrPut(dsymFrame) {
                        val frameId = frameTable.size
                        frameTable.add(
                            GeckoFrame(
                                stringId = stringId,
                                category = getCategory(
                                    app,
                                    symbolsInfo.symbolToLib.getOrDefault(dsymFrame, null)
                                )
                            )
                        )
                        frameId
                    }
                }

                // Intern Stacks
                var prefixId: Int? = null
                frameIds.reversed().forEach { frameId ->
                    prefixId = stackMap.getOrPut(Pair(prefixId, frameId)) {
                        val stackId = stackTable.size
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
                name = samples.firstOrNull()?.threadName ?: "<unknown>",
                tid = threadId,
                // Currently, we only support single process runs
                pid = 0,
                samples = GeckoSamples(data = geckoSamples.map { it.toData() }),
                stringTable = stringTable,
                frameTable = GeckoFrameTable(data = frameTable.map { it.toData() }),
                stackTable = GeckoStackTable(data = stackTable.map { it.toData() }),
            )
        }


        return GeckoProfile(
            meta = GeckoMeta(),
            threads = threads,
        )
    }

    private fun getCategory(app: String, lib: Lib?): Int {
        if (lib == null) {
            return OTHER_CATEGORY
        }

        return when {
            lib.name.contains(app) -> USER_CATEGORY
            lib.name.startsWith("/System") -> FRAMEWORK_CATEGORY
            lib.name.startsWith("/usr") -> LIBRARY_CATEGORY
            else -> OTHER_CATEGORY
        }
    }
}
