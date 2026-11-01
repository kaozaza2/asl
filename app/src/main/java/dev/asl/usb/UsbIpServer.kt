package dev.asl.usb

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbRequest
import android.system.OsConstants
import android.util.Log
import dev.asl.core.AgentConnection
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** USB/IP data phase (Documentation/usb/usbip_protocol.rst) for one device; the guest's vhci_hcd is the client. */
// Linux errno; android.system.OsConstants does not expose it.
private const val ESHUTDOWN = 108


/** The device's configurations, in declaration order. */
internal val UsbDevice.configurations: List<UsbConfiguration>
    get() = (0 until configurationCount).map(::getConfiguration)


class UsbIpServer(
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val channel: AgentConnection,
) : AutoCloseable {

    private enum class Command(val code: Int) { CmdSubmit(1), CmdUnlink(2), RetSubmit(3), RetUnlink(4) }

    private class Urb(val seqnum: Int, val packets: Int, val buffer: ByteBuffer?, val request: UsbRequest?)

    private val out = BufferedOutputStream(channel.output, 1 shl 16)
    private val pending = ConcurrentHashMap<Int, Urb>()
    private val queued = AtomicInteger()
    private val closed = AtomicBoolean()
    private val control = Executors.newSingleThreadExecutor { Thread(it, "usbip-control") }
    private val configurations = device.configurations
    private var alternates = emptyList<UsbInterface>()
    private var isoLogged = false

    @Volatile private var endpoints = emptyMap<Int, UsbEndpoint>()

    @Volatile private var interfaces = emptyMap<Int, UsbInterface>()
        set(value) {
            field = value
            endpoints = value.values.flatMap { i -> (0 until i.endpointCount).map(i::getEndpoint) }.associateBy { it.address }
        }

    /** Blocks until the guest closes the stream, the device goes away, or [close] is called. */
    fun run() {
        var reaper: Thread? = null
        try {
            // GET_CONFIGURATION, so we claim what the kernel actually selected.
            val active = ByteArray(1).also { connection.controlTransfer(0x80, 8, 0, 0, it, 1, CONTROL_TIMEOUT_MS) }[0].toInt() and 0xff
            configure(configurations.find { it.id == active } ?: configurations.first(), apply = false)
            reaper = thread(name = "usbip-reaper") {
                while (!closed.get() || queued.get() > 0) {
                    val request = try {
                        connection.requestWait(REAP_POLL_MS)
                    } catch (timeout: TimeoutException) {
                        continue
                    }
                    // A null reap means usbfs failed, which in practice is the device going away.
                    if (request == null) {
                        close()
                        break
                    }
                    queued.decrementAndGet()
                    val urb = request.clientData as Urb
                    val buffer = urb.buffer!!.apply { flip() }
                    synchronized(out) {
                        if (pending.remove(urb.seqnum) != null) {
                            val isIn = request.endpoint.direction == UsbConstants.USB_DIR_IN
                            reply(Command.RetSubmit, urb.seqnum, 0, buffer.remaining(), 0, urb.packets, 0, payload = buffer.takeIf { isIn })
                        }
                    }
                    request.close()
                }
            }

            val input = DataInputStream(BufferedInputStream(channel.input, 1 shl 16))
            val header = ByteBuffer.allocate(HEADER_SIZE)
            val setup = ByteArray(8)
            while (true) {
                header.clear()
                input.readFully(header.array())
                val command = header.int
                val seqnum = header.int
                header.int // devid
                val dirIn = header.int == 1
                val ep = header.int
                when (command) {
                    Command.CmdUnlink.code -> {
                        val target = header.int
                        synchronized(out) {
                            val urb = pending.remove(target)
                            urb?.request?.cancel()
                            reply(Command.RetUnlink, seqnum, if (urb != null) -OsConstants.ECONNRESET else 0)
                        }
                    }
                    Command.CmdSubmit.code -> {
                        header.int // transfer_flags
                        val length = header.int
                        header.int // start_frame
                        val packets = header.int
                        header.int // interval
                        header.get(setup)
                        if (length !in 0..MAX_TRANSFER || packets > MAX_ISO_PACKETS) throw IOException("bad CMD_SUBMIT: length $length, packets $packets")
                        val data = ByteArray(length)
                        if (!dirIn) input.readFully(data)
                        val iso = if (packets > 0) ByteBuffer.allocate(packets * ISO_DESCRIPTOR_SIZE).also { input.readFully(it.array()) } else null
                        val endpoint = endpoints[ep or if (dirIn) UsbConstants.USB_DIR_IN else 0]
                        when {
                            iso != null || endpoint?.type == UsbConstants.USB_ENDPOINT_XFER_ISOC -> {
                                if (!isoLogged) Log.w(TAG, "isochronous transfers are not supported by the Android USB host API (${device.deviceName} ep $ep)")
                                isoLogged = true
                                iso?.let { for (i in 0 until packets) it.putInt(i * ISO_DESCRIPTOR_SIZE + 8, 0).putInt(i * ISO_DESCRIPTOR_SIZE + 12, -OsConstants.EXDEV) }
                                synchronized(out) { reply(Command.RetSubmit, seqnum, -OsConstants.EXDEV, 0, 0, packets, maxOf(packets, 0), payload = iso) }
                            }
                            ep == 0 -> {
                                val fields = ByteBuffer.wrap(setup).order(ByteOrder.LITTLE_ENDIAN)
                                val requestType = fields.get().toInt() and 0xff
                                val request = fields.get().toInt() and 0xff
                                val value = fields.short.toInt() and 0xffff
                                val index = fields.short.toInt() and 0xffff
                                pending[seqnum] = Urb(seqnum, packets, null, null)
                                control.execute {
                                    val result = when {
                                        // Standard SET_CONFIGURATION / SET_INTERFACE go through usbfs so it keeps its claims and endpoint state.
                                        requestType == 0x00 && request == 9 -> configurations.find { it.id == value }?.let { if (configure(it, apply = true)) 0 else -1 } ?: -1
                                        requestType == 0x01 && request == 11 -> alternates.find { it.id == index && it.alternateSetting == value }
                                            ?.takeIf(connection::setInterface)?.let { interfaces += it.id to it; 0 } ?: -1
                                        else -> connection.controlTransfer(requestType, request, value, index, data, length, CONTROL_TIMEOUT_MS)
                                    }
                                    synchronized(out) {
                                        if (pending.remove(seqnum) != null) {
                                            val payload = if (dirIn && result > 0) ByteBuffer.wrap(data, 0, result) else null
                                            reply(Command.RetSubmit, seqnum, if (result < 0) -OsConstants.EPIPE else 0, maxOf(result, 0), 0, packets, 0, payload = payload)
                                        }
                                    }
                                }
                            }
                            endpoint == null -> synchronized(out) { reply(Command.RetSubmit, seqnum, -OsConstants.EPIPE, 0, 0, packets, 0) }
                            else -> {
                                val buffer = ByteBuffer.wrap(data)
                                val request = UsbRequest()
                                val urb = Urb(seqnum, packets, buffer, request)
                                request.clientData = urb
                                pending[seqnum] = urb
                                queued.incrementAndGet()
                                if (!request.initialize(connection, endpoint) || !request.queue(buffer.takeIf { length > 0 })) {
                                    queued.decrementAndGet()
                                    request.close()
                                    synchronized(out) {
                                        if (pending.remove(seqnum) != null) reply(Command.RetSubmit, seqnum, -ESHUTDOWN, 0, 0, packets, 0)
                                    }
                                }
                            }
                        }
                    }
                    else -> throw IOException("unexpected USB/IP command 0x%08x".format(command))
                }
            }
        } catch (e: Exception) {
            if (e !is EOFException && !closed.get()) throw e
        } finally {
            close()
            control.shutdownNow()
            control.awaitTermination(CONTROL_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            reaper?.join(SHUTDOWN_GRACE_MS)
            interfaces.values.forEach(connection::releaseInterface)
            connection.close()
            channel.close()
        }
    }

    /** Fails outstanding URBs with -ESHUTDOWN and ends the stream; [run] then releases the device. */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(out) {
            pending.keys.forEach { seqnum ->
                pending.remove(seqnum)?.let { urb ->
                    urb.request?.cancel()
                    reply(Command.RetSubmit, seqnum, -ESHUTDOWN, 0, 0, urb.packets, 0)
                }
            }
        }
        // SELinux denies shutdown() on vsock for apps; closing the fds is what unblocks the reader.
        runCatching { channel.close() }
    }

    private fun configure(target: UsbConfiguration, apply: Boolean): Boolean {
        interfaces.values.forEach(connection::releaseInterface)
        interfaces = emptyMap()
        if (apply && !connection.setConfiguration(target)) return false
        alternates = (0 until target.interfaceCount).map(target::getInterface)
        interfaces = alternates.filter { it.alternateSetting == 0 }.associateBy { it.id }
        interfaces.values.filterNot { connection.claimInterface(it, true) }
            .forEach { Log.w(TAG, "cannot claim interface ${it.id} of ${device.deviceName}") }
        return true
    }

    // Callers hold the `out` lock, which orders replies against pending-map removal.
    private fun reply(command: Command, seqnum: Int, vararg fields: Int, payload: ByteBuffer? = null) {
        val header = ByteBuffer.allocate(HEADER_SIZE).putInt(command.code).putInt(seqnum).putInt(0).putInt(0).putInt(0)
        fields.forEach(header::putInt)
        try {
            out.write(header.array())
            payload?.let { out.write(it.array(), it.arrayOffset() + it.position(), it.remaining()) }
            out.flush()
        } catch (broken: IOException) {
            close()
        }
    }

    private companion object {
        const val TAG = "UsbIp"
        const val HEADER_SIZE = 48
        const val ISO_DESCRIPTOR_SIZE = 16
        const val MAX_TRANSFER = 64 shl 20
        const val MAX_ISO_PACKETS = 1024
        const val CONTROL_TIMEOUT_MS = 5000
        const val REAP_POLL_MS = 200L
        const val SHUTDOWN_GRACE_MS = 2000L
    }
}
