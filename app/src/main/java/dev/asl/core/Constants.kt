package dev.asl.core

/**
 * Values that were previously hardcoded in several places. Guest-side counterparts are named in
 * docs/PROTOCOL.md; both sides must stay in sync.
 */

/** Authority of the per-distro DocumentsProvider roots ("ASL: <name>"). */
const val DOCUMENTS_AUTHORITY = "dev.asl.documents"

/** Distro names are validated identically in the distro repository and the installer UI. */
val DISTRO_NAME = Regex("^[A-Za-z0-9._-]{1,64}$")

/** A distro's registry index becomes a virtio-blk device: 0 → /dev/vda, 1 → /dev/vdb, … */
const val MAX_DISTROS = 26

fun distroDevice(index: Int): String {
    require(index in 0 until MAX_DISTROS) { "At most $MAX_DISTROS distributions are supported" }
    return "/dev/vd${'a' + index}"
}

/** Linux usb_device_speed codes spoken over the guest's `usbip` channel (guest/agent/usbip.go). */
const val USB_SPEED_FULL = 2
const val USB_SPEED_HIGH = 3
const val USB_SPEED_SUPER = 5
