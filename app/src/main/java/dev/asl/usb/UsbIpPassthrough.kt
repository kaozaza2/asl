package dev.asl.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import dev.asl.core.Asl
import dev.asl.core.USB_SPEED_FULL
import dev.asl.core.USB_SPEED_HIGH
import dev.asl.core.USB_SPEED_SUPER
import dev.asl.core.UsbDeviceInfo
import dev.asl.core.UsbPassthrough
import dev.asl.core.VmState
import dev.asl.core.openAgentChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** usbipd-win for Android: exports host USB devices to the guest's vhci_hcd over the `usbip` agent channel. */
class UsbIpPassthrough(context: Context) : UsbPassthrough {
    private val context = context.applicationContext
    private val manager: UsbManager? = this.context.getSystemService(UsbManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val servers = ConcurrentHashMap<String, UsbIpServer>()
    private val jobs = HashMap<String, Job>()
    private val wanted = ConcurrentHashMap.newKeySet<String>()
    private val errors = ConcurrentHashMap<String, String>()
    private val permissionIntent = PendingIntent.getBroadcast(
        this.context, 0, Intent(ACTION_PERMISSION).setPackage(this.context.packageName), PendingIntent.FLAG_MUTABLE,
    )
    private val _devices = MutableStateFlow(emptyList<UsbDeviceInfo>())
    override val devices: StateFlow<List<UsbDeviceInfo>> = _devices.asStateFlow()

    // Linux names usbfs nodes /dev/bus/usb/BBB/DDD.
    private val UsbDevice.busAndAddress get() = deviceName.split('/').takeLast(2).map { it.toIntOrNull() ?: 0 }

    init {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                when {
                    device == null -> Unit
                    intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED -> detach(device.deviceName)
                    intent.action != ACTION_PERMISSION -> Unit
                    intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) -> attach(device.deviceName)
                    else -> {
                        wanted.remove(device.deviceName)
                        errors[device.deviceName] = "USB permission denied"
                    }
                }
                refresh()
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_PERMISSION)
        }
        this.context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        scope.launch {
            Asl.vm.status.map { it.state == VmState.Running }.distinctUntilChanged().collect { running ->
                if (running) wanted.forEach(::attach)
            }
        }
        refresh()
    }

    override fun refresh() {
        val manager = manager ?: return
        _devices.value = manager.deviceList.values.sortedBy { it.deviceName }.map { device ->
            val (bus, address) = device.busAndAddress
            UsbDeviceInfo(
                key = device.deviceName,
                busId = "$bus-$address",
                vendorId = device.vendorId,
                productId = device.productId,
                name = device.productName ?: device.manufacturerName ?: "%04x:%04x".format(device.vendorId, device.productId),
                hasPermission = manager.hasPermission(device),
                attached = servers.containsKey(device.deviceName),
                error = errors[device.deviceName],
            )
        }
    }

    override fun attach(key: String) {
        val manager = manager ?: return
        val device = manager.deviceList[key] ?: return
        wanted += key
        if (!manager.hasPermission(device)) return manager.requestPermission(device, permissionIntent)
        synchronized(jobs) {
            if (servers.containsKey(key) || jobs[key]?.isActive == true) return
            errors.remove(key)
            jobs[key] = scope.launch {
                var connection: UsbDeviceConnection? = null
                try {
                    connection = manager.openDevice(device) ?: error("Cannot open ${device.deviceName}")
                    val (bus, address) = device.busAndAddress
                    val bcdUsb = ByteBuffer.wrap(connection.rawDescriptors).order(ByteOrder.LITTLE_ENDIAN).getShort(2).toInt() and 0xffff
                    val maxPacket = device.configurations
                        .flatMap { c -> (0 until c.interfaceCount).map(c::getInterface) }
                        .flatMap { i -> (0 until i.endpointCount).map { i.getEndpoint(it).maxPacketSize } }
                        .maxOrNull() ?: 0
                    // Linux usb_device_speed. Full-speed devices often claim bcdUSB 2.0, so high speed also needs a >64-byte endpoint; low speed is undetectable and reported as full.
                    val speed = when {
                        bcdUsb >= 0x0300 -> USB_SPEED_SUPER
                        bcdUsb >= 0x0200 && maxPacket > 64 -> USB_SPEED_HIGH
                        else -> USB_SPEED_FULL
                    }
                    val channel = Asl.vm.openAgentChannel(buildJsonObject {
                        put("svc", "usbip")
                        put("busid", "$bus-$address")
                        put("devid", (bus shl 16) or address)
                        put("speed", speed)
                    })
                    val server = UsbIpServer(device, connection, channel)
                    servers[key] = server
                    thread(name = "usbip-$bus-$address") {
                        runCatching(server::run).onFailure {
                            Log.w(TAG, "USB/IP server for $key failed", it)
                            errors[key] = it.message ?: it.toString()
                        }
                        servers.remove(key, server)
                        refresh()
                    }
                    if (!isActive) server.close()
                } catch (e: Exception) {
                    connection?.close()
                    if (e !is CancellationException) errors[key] = e.message ?: e.toString()
                } finally {
                    refresh()
                }
            }
        }
    }

    override fun detach(key: String) {
        wanted.remove(key)
        errors.remove(key)
        synchronized(jobs) { jobs.remove(key)?.cancel() }
        servers.remove(key)?.close()
        refresh()
    }

    private companion object {
        const val TAG = "UsbIp"
        const val ACTION_PERMISSION = "dev.asl.usb.PERMISSION"
    }
}
