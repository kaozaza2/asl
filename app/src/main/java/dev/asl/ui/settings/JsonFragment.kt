package dev.asl.ui.settings

import android.os.Bundle
import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import dev.asl.R
import dev.asl.core.Asl
import dev.asl.core.AslSettings
import dev.asl.databinding.SettingsJsonBinding
import dev.asl.settings.SettingsJson
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class JsonFragment : Fragment(R.layout.settings_json) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val b = SettingsJsonBinding.bind(view)
        val reset = {
            b.editor.setText(SettingsJson.encodeToString(Asl.settings.settings.value))
            b.error.isVisible = false
            b.editorLayout.error = null
        }
        b.path.text = Asl.settings.file.absolutePath
        if (savedInstanceState == null) reset()
        b.discard.setOnClickListener { reset() }
        b.save.setOnClickListener {
            try {
                val parsed = SettingsJson.decodeFromString<AslSettings>(b.editor.text.toString())
                Asl.settings.update { parsed }
                reset()
            } catch (e: IllegalArgumentException) {
                b.error.text = e.message ?: getString(R.string.settings_json_invalid)
                b.error.isVisible = true
                b.editorLayout.error = " "
            }
        }
    }
}
