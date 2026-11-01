package dev.asl.core

/** Process-wide service locator, populated in AslApp.onCreate. */
object Asl {
    lateinit var settings: SettingsRepository
    lateinit var vm: VmController
    lateinit var distros: DistroManager
    lateinit var usb: UsbPassthrough
}
