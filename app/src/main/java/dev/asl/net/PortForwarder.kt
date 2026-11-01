package dev.asl.net

import android.util.Log
import dev.asl.core.AgentConnection
import dev.asl.core.Asl
import dev.asl.core.ForwardedPort
import dev.asl.core.MIN_FORWARDED_PORT
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** Mirrors guest TCP listeners onto host sockets; a port that could not be bound is listed with `active = -1`. */
class PortForwarder(private val open: (header: JsonObject) -> AgentConnection) {
    private val listeners = mutableMapOf<Int, Listener>()
    private var guestPorts: Collection<Int> = emptyList()
    private val _ports = MutableStateFlow(emptyList<ForwardedPort>())
    val ports: StateFlow<List<ForwardedPort>> = _ports.asStateFlow()

    /** Loopback forwards bind both 127.0.0.1 and ::1 since clients may resolve localhost to either. */
    private inner class Listener(val port: Int, val addresses: List<String>) {
        val active = AtomicInteger()
        val servers = addresses.mapNotNull { address ->
            runCatching { ServerSocket(port, 50, InetAddress.getByName(address)) }
                .onFailure { Log.w(TAG, "Cannot bind $address:$port", it) }
                .getOrNull()
        }

        init {
            servers.forEach { server ->
                thread(isDaemon = true, name = "asl-fwd-$port") {
                    while (true) {
                        val client = runCatching { server.accept() }.getOrNull() ?: break
                        thread(isDaemon = true, name = "asl-fwd-$port-up") {
                            val conn = runCatching { open(buildJsonObject { put("svc", "forward"); put("port", port) }) }
                                .onFailure { Log.w(TAG, "Guest refused port $port", it) }
                                .getOrElse { client.close(); return@thread }
                            active.incrementAndGet()
                            publish()
                            val abort = { client.close(); conn.close() }
                            val down = thread(isDaemon = true, name = "asl-fwd-$port-down") {
                                runCatching { conn.input.copyTo(client.getOutputStream()); client.shutdownOutput() }.onFailure { abort() }
                            }
                            runCatching { client.getInputStream().copyTo(conn.output); conn.shutdownOutput() }.onFailure { abort() }
                            down.join()
                            abort()
                            active.decrementAndGet()
                            publish()
                        }
                    }
                }
            }
        }
    }

    /** Reconciles listeners with [ports] (the latest `ports` event) and the current settings. */
    @Synchronized
    fun update(ports: Collection<Int> = guestPorts) {
        guestPorts = ports
        val settings = Asl.settings.settings.value.asl
        val addresses = if (settings.bindAllInterfaces) listOf("0.0.0.0") else listOf("127.0.0.1", "::1")
        val wanted = if (settings.localhostForwarding) ports.filter { it >= MIN_FORWARDED_PORT }.toSet() else emptySet()
        listeners.values.filter { it.port !in wanted || it.addresses != addresses || it.servers.isEmpty() }.forEach { listener ->
            listener.servers.forEach { it.close() }
            listeners.remove(listener.port)
        }
        (wanted - listeners.keys).forEach { listeners[it] = Listener(it, addresses) }
        publish()
    }

    @Synchronized
    private fun publish() {
        _ports.value = listeners.values.sortedBy { it.port }
            .map { ForwardedPort(it.port, it.addresses.first(), if (it.servers.isEmpty()) -1 else it.active.get()) }
    }

    private companion object {
        const val TAG = "PortForwarder"
    }
}
