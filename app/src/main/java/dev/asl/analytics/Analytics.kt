package dev.asl.analytics

import android.app.ActivityManager
import android.content.Context
import dev.asl.core.Asl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicLong

@Serializable
data class BootEvent(val timestamp: Long, val success: Boolean, val durationMs: Long, val error: String? = null)

@Serializable
data class TabCountSample(val timestamp: Long, val count: Int)

@Serializable
data class ResourceSample(val timestamp: Long, val cpuPercent: Float, val ramMb: Float)

@Serializable
data class AnalyticsData(
    val bootEvents: List<BootEvent> = emptyList(),
    val distroUsage: Map<String, Int> = emptyMap(),
    val tabSamples: List<TabCountSample> = emptyList(),
    val resourceSamples: List<ResourceSample> = emptyList(),
    val sessionDurationsMs: List<Long> = emptyList(),
)

class AnalyticsRepository(context: Context) {
    private val file = File(context.filesDir, "analytics.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val state = MutableStateFlow(load())
    val data: StateFlow<AnalyticsData> = state.asStateFlow()

    private val prevCpuTicks = AtomicLong(-1)
    private val prevSampleTime = AtomicLong(0)

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    fun recordBoot(success: Boolean, durationMs: Long, error: String? = null) {
        state.value = state.value.copy(
            bootEvents = (state.value.bootEvents + BootEvent(System.currentTimeMillis(), success, durationMs, error)).takeLast(MAX_EVENTS),
        )
        persist()
    }

    fun recordDistroUsage(distroId: String) {
        val current = state.value.distroUsage
        state.value = state.value.copy(distroUsage = current + (distroId to (current[distroId] ?: 0) + 1))
        persist()
    }

    fun recordTabCount(count: Int) {
        state.value = state.value.copy(
            tabSamples = (state.value.tabSamples + TabCountSample(System.currentTimeMillis(), count)).takeLast(MAX_SAMPLES),
        )
        persist()
    }

    fun recordSessionDuration(durationMs: Long) {
        state.value = state.value.copy(
            sessionDurationsMs = (state.value.sessionDurationsMs + durationMs).takeLast(MAX_SAMPLES),
        )
        persist()
    }

    fun recordResource(cpuPercent: Float, ramMb: Float) {
        state.value = state.value.copy(
            resourceSamples = (state.value.resourceSamples + ResourceSample(System.currentTimeMillis(), cpuPercent, ramMb)).takeLast(MAX_SAMPLES),
        )
        persist()
    }

    fun sampleResources(context: Context) {
        val now = System.currentTimeMillis()
        val cpuTicks = readCpuTicks()
        val ramMb = readRamMb(context)
        val prevTicks = prevCpuTicks.get()
        val prevTime = prevSampleTime.get()
        val cpuPercent = if (prevTicks >= 0 && now > prevTime) {
            val deltaTicks = cpuTicks - prevTicks
            (deltaTicks * 1000f / (now - prevTime)).coerceIn(0f, 100f * Runtime.getRuntime().availableProcessors())
        } else 0f
        prevCpuTicks.set(cpuTicks)
        prevSampleTime.set(now)
        if (prevTicks >= 0) recordResource(cpuPercent, ramMb)
    }

    private fun readCpuTicks(): Long {
        return try {
            val text = File("/proc/self/stat").readText()
            val after = text.substringAfterLast(')')
            val fields = after.trim().split(Regex("\\s+"))
            // fields[0] is state; utime is 13th (index 12), stime 14th (index 13) of the whole stat, which
            // after removing the comm field leaves utime at index 11 and stime at 12.
            val utime = fields.getOrNull(11)?.toLongOrNull() ?: 0L
            val stime = fields.getOrNull(12)?.toLongOrNull() ?: 0L
            utime + stime
        } catch (e: Exception) {
            0L
        }
    }

    private fun readRamMb(context: Context): Float {
        val rt = Runtime.getRuntime()
        return (rt.totalMemory() - rt.freeMemory()) / 1024f / 1024f
    }

    private fun load(): AnalyticsData {
        val text = runCatching { file.readText() }.getOrNull() ?: return AnalyticsData()
        return runCatching { json.decodeFromString<AnalyticsData>(text) }.getOrDefault(AnalyticsData())
    }

    private fun persist() {
        try {
            file.writeText(json.encodeToString(state.value))
        } catch (e: Exception) {
            // Best-effort; analytics must never crash the app.
        }
    }

    companion object {
        private const val MAX_EVENTS = 200
        private const val MAX_SAMPLES = 500
    }
}
