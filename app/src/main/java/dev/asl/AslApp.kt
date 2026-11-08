package dev.asl

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import dev.asl.core.Asl
import dev.asl.distro.DistroRepository
import dev.asl.settings.JsonSettingsRepository
import dev.asl.ui.TabRegistry
import dev.asl.usb.UsbIpPassthrough
import dev.asl.vm.AvfVmController
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.lsposed.hiddenapibypass.HiddenApiBypass

private val NightModes = mapOf("dark" to AppCompatDelegate.MODE_NIGHT_YES, "light" to AppCompatDelegate.MODE_NIGHT_NO)

class AslApp : Application() {
    override fun onCreate() {
        super.onCreate()
        HiddenApiBypass.addHiddenApiExemptions("Landroid/system/virtualmachine/", "Landroid/system/virtualizationservice/")
        Asl.settings = JsonSettingsRepository(this)
        Asl.vm = AvfVmController(this)
        Asl.distros = DistroRepository(this)
        Asl.usb = UsbIpPassthrough(this)
        TabRegistry.start(this)
        MainScope().launch {
            Asl.settings.settings.map { it.theme }.distinctUntilChanged().collect {
                AppCompatDelegate.setDefaultNightMode(NightModes[it] ?: AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
        }
    }
}
