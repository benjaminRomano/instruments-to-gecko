package com.bromano.instrumentsgecko

import com.google.gson.Gson
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.util.zip.GZIPOutputStream

const val USER_CATEGORY = 0
const val FRAMEWORK_CATEGORY = 1
const val LIBRARY_CATEGORY = 2
const val OTHER_CATEGORY = 3
const val VIRTUAL_MEMORY_CATEGORY = 4

val VIRTUAL_MEMORY_ADDR = ULong.MAX_VALUE - 1UL

val DEFAULT_CATEGORIES = listOf(
    Category("User", "yellow", listOf("Other")),
    Category("Framework", "green", listOf("Other")),
    Category("System", "orange", listOf("Other")),
    Category("Other", "grey", listOf("Other")),
    Category("Virtual Memory", "blue", listOf("Other")),
)

/**
 * Gecko Profile format
 *
 * Original types can be found here:
 * https://github.com/firefox-devtools/profiler/blob/53970305b51b9b472e26d7457fee1d66cd4e2737/src/types/gecko-profile.js#L216
 *
 * Example code constructing valid Gecko Profiles can be found here:
 * https://android.googlesource.com/platform/system/extras/+/master/simpleperf/scripts/gecko_profile_generator.py
 *
 * Note: Anything typed as List<Any> is not yet supported
 */
data class GeckoProfile(
    val meta: GeckoMeta,
    // TODO: Look into having Gecko do desymbolication
    // ref: https://github.com/firefox-devtools/profiler/blob/53970305b51b9b472e26d7457fee1d66cd4e2737/src/types/profile.js#L396
    val libs: List<Any> = emptyList(),
    val threads: List<GeckoThread>,
    val pausedRange: List<Any> = emptyList(),
    val processes: List<Any> = emptyList(),
) {
    /**
     * Write GeckoProfile to file as Gzipped Json
     */
    fun toFile(output: Path) {
        val json = Gson().toJson(this)
        GZIPOutputStream(FileOutputStream(output.toFile())).bufferedWriter(UTF_8).use { it.write(json) }
    }
}

data class GeckoThread(
    val name: String,
    val registerTime: Long = 0,
    val processType: String = "default",
    val processName: String? = null,
    val unregisterTime: Long? = null,
    val tid: Int,
    val pid: Long,
    val markers: GeckoMarkers = GeckoMarkers(),
    val samples: GeckoSamples,
    val frameTable: GeckoFrameTable,
    val stackTable: GeckoStackTable,
    val stringTable: List<String>,
)

data class GeckoMarkers(
    val schema: GeckoMarkersSchema = GeckoMarkersSchema(),
    val data: List<Array<Int>> = emptyList(),
)

data class GeckoMarkersSchema(
    val name: Int = 0,
    val startTime: Int = 1,
    val endTime: Int = 2,
    val phase: Int = 3,
    val category: Int = 4,
    val data: Int = 5,
)

data class GeckoMeta(
    val version: Long = 24,
    val startTime: Double,
    val shutdownTime: Long? = null,
    val categories: List<Category> = DEFAULT_CATEGORIES,
    val markerSchema: List<Any> = emptyList(),
    val interval: Int = 1,
    val stackwalk: Int = 1,
    val debug: Int = 0,
    val gcpoision: Int = 0,
    val processType: Int = 0,
    val presymbolicated: Boolean? = true,
)

data class Category(
    val name: String,
    val color: String,
    val subcategories: List<String>
)

data class GeckoSamples(
    val schema: GeckoSampleSchema = GeckoSampleSchema(),
    val data: List<Array<out Any?>>,
)

data class GeckoSampleSchema(
    val stack: Int = 0,
    val time: Int = 1,
    val eventDelay: Int = 2,
)

class GeckoSample(
    val stackId: Long?,
    val timeMs: Double,
    val eventDelay: Double = 0.0
) {
    fun toData() = arrayOf(stackId, timeMs, eventDelay)
}

data class GeckoStackTable(
    val schema: GeckoStackTableSchema = GeckoStackTableSchema(),
    val data: List<Array<out Any?>>
)

data class GeckoStackTableSchema(
    val prefix: Int = 0,
    val frame: Int = 1,
)

data class GeckoStack(
    // Id of stack with matching prefix
    val prefixId: Long?,
    val frameId: Long,
    val category: Int = 0,
) {
    fun toData() = arrayOf(prefixId, frameId, category)
}

data class GeckoFrameTable(
    val schema: GeckoFrameTableSchema = GeckoFrameTableSchema(),
    val data: List<Array<out Any?>>
)

data class GeckoFrameTableSchema(
    val location: Int = 0,
    val relevantForJS: Int = 1,
    val innerWindowID: Int = 2,
    val implementation: Int = 3,
    val optimizations: Int = 4,
    val line: Int = 5,
    val column: Int = 6,
    val category: Int = 7,
    val subcategory: Int = 8,
)

data class GeckoFrame(
    val stringId: Long,
    val relevantForJS: Boolean = false,
    val innerWindowID: Int = 0,
    val implementation: String? = null,
    val optimizations: String? = null,
    val line: String? = null,
    val column: String? = null,
    val category: Int = 0,
    val subcategory: Int = 0,
) {
    fun toData() = arrayOf(
        stringId,
        relevantForJS,
        innerWindowID,
        implementation,
        optimizations,
        line,
        column,
        category,
        subcategory,
    )
}