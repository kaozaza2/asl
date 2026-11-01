package dev.asl.core

import kotlinx.serialization.Serializable

/** Mirrors the shape of Windows Terminal's settings.json, plus an `asl` block standing in for .wslconfig. */
@Serializable
data class AslSettings(
    val defaultProfile: String? = null,
    val theme: String = "dark",
    val launchMode: String = "default",
    val startupActions: String = "",
    val profiles: Profiles = Profiles(),
    val schemes: List<ColorScheme> = emptyList(),
    val asl: VmSettings = VmSettings(),
)

@Serializable
data class Profiles(
    val defaults: ProfileAppearance = ProfileAppearance(),
    val list: List<Profile> = emptyList(),
)

@Serializable
data class ProfileAppearance(
    val colorScheme: String = "Campbell",
    val fontFace: String = "Cascadia Mono",
    val fontSize: Int = 12,
    val cursorShape: String = "bar",
    val opacity: Int = 100,
    val padding: Int = 8,
    val scrollbackLines: Int = 9001,
    val bellStyle: String = "audible",
)

@Serializable
data class Profile(
    val guid: String,
    val name: String,
    val source: String? = null,
    val distroId: String? = null,
    val commandline: String? = null,
    val startingDirectory: String? = "~",
    val user: String? = null,
    val icon: String? = null,
    val tabTitle: String? = null,
    val hidden: Boolean = false,
    val appearance: ProfileAppearance? = null,
)

@Serializable
data class ColorScheme(
    val name: String,
    val background: String,
    val foreground: String,
    val cursorColor: String,
    val selectionBackground: String,
    val black: String, val red: String, val green: String, val yellow: String,
    val blue: String, val purple: String, val cyan: String, val white: String,
    val brightBlack: String, val brightRed: String, val brightGreen: String, val brightYellow: String,
    val brightBlue: String, val brightPurple: String, val brightCyan: String, val brightWhite: String,
)

/** The .wslconfig equivalent. AVF only offers one vCPU or a host-matching topology. */
@Serializable
data class VmSettings(
    val memoryMb: Int = 2048,
    val matchHostCpus: Boolean = true,
    val kernelCommandLine: String = "",
    val localhostForwarding: Boolean = true,
    val bindAllInterfaces: Boolean = false,
    val mountAndroidStorage: Boolean = true,
    val androidStoragePath: String = "/storage/emulated/0",
    val defaultDiskSizeGb: Int = 64,
    val autoStart: Boolean = false,
)

@Serializable
data class Distro(
    val id: String,
    val name: String,
    val diskPath: String,
    val defaultUser: String = "root",
    val isDefault: Boolean = false,
    val systemd: Boolean = false,
    val hostname: String? = null,
    val source: String? = null,
    val createdAt: Long = 0,
    val pendingImport: Boolean = false,
    /** Set until the image's /etc/wsl-distribution.conf [oobe] has run (or the image has none). */
    val oobePending: Boolean = false,
)

@Serializable
data class CatalogEntry(
    val id: String,
    val friendlyName: String,
    val url: String,
    val sha256: String? = null,
    val compression: String = "gz",
    val icon: String? = null,
)

data class UsbDeviceInfo(
    val key: String,
    val busId: String,
    val vendorId: Int,
    val productId: Int,
    val name: String,
    val hasPermission: Boolean,
    val attached: Boolean,
    val error: String? = null,
)

/** Binding below 1024 needs CAP_NET_BIND_SERVICE, which an Android app never has. */
const val MIN_FORWARDED_PORT = 1024
const val AGENT_VSOCK_PORT = 5000L
