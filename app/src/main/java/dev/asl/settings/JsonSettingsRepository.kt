package dev.asl.settings

import android.content.Context
import android.util.AtomicFile
import dev.asl.core.AslSettings
import dev.asl.core.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

val SettingsJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

class JsonSettingsRepository(context: Context) : SettingsRepository {
    override val file = File(context.filesDir, "settings.json")
    private val atomic = AtomicFile(file)
    private val state = MutableStateFlow(load())
    override val settings: StateFlow<AslSettings> = state.asStateFlow()

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            state.collectLatest { settings ->
                delay(200)
                val out = atomic.startWrite()
                try {
                    out.write(SettingsJson.encodeToString(settings).toByteArray())
                    atomic.finishWrite(out)
                } catch (e: IOException) {
                    atomic.failWrite(out)
                }
            }
        }
    }

    override fun update(transform: (AslSettings) -> AslSettings) = state.update { transform(it).withBuiltInSchemes() }

    private fun load(): AslSettings {
        val text = runCatching { atomic.readFully().decodeToString() }.getOrNull()
        val parsed = text?.let { json ->
            // Keep an unreadable file around instead of silently overwriting the user's edits.
            runCatching { SettingsJson.decodeFromString<AslSettings>(json) }
                .onFailure { file.copyTo(File(file.path + ".bak"), overwrite = true) }
                .getOrNull()
        }
        return (parsed ?: AslSettings()).withBuiltInSchemes()
    }

    private fun AslSettings.withBuiltInSchemes() =
        copy(
            schemes = BuiltInSchemes.map { builtIn -> schemes.firstOrNull { it.name == builtIn.name } ?: builtIn } +
                schemes.filter { scheme -> BuiltInSchemes.none { it.name == scheme.name } },
        )
}
