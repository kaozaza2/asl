package dev.asl.vm

import android.content.Context
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.Executor

/** Reflective facade over the hidden android.system.virtualmachine API. */
internal object Avf {
    private const val TAG = "Avf"
    private const val STOP_REASON_SHUTDOWN = 3

    // VirtualMachineConfig.CPU_TOPOLOGY_ONE_CPU / CPU_TOPOLOGY_MATCH_HOST (hidden-API values).
    private const val CPU_TOPOLOGY_ONE_CPU = 0
    private const val CPU_TOPOLOGY_MATCH_HOST = 1

    private fun cls(name: String): Class<*> = Class.forName("android.system.virtualmachine.$name")

    fun call(target: Any, name: String, vararg args: Any?): Any? = invoke(target.javaClass, target, name, args)

    private fun static(type: Class<*>, name: String, vararg args: Any?): Any? = invoke(type, null, name, args)

    private fun invoke(type: Class<*>, target: Any?, name: String, args: Array<out Any?>): Any? {
        val method = generateSequence(type) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .firstOrNull { m ->
                m.name == name && m.parameterCount == args.size &&
                    m.parameterTypes.zip(args).all { (p, a) -> if (a == null) !p.isPrimitive else p.kotlin.javaObjectType.isInstance(a) }
            } ?: throw NoSuchMethodException("${type.name}.$name(${args.size} args)")
        method.isAccessible = true
        return try {
            method.invoke(target, *args)
        } catch (e: InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    private fun newBuilder(name: String, vararg args: Any): Any =
        cls(name).declaredConstructors.first { it.parameterCount == args.size }.apply { isAccessible = true }.newInstance(*args)

    fun createVm(
        context: Context,
        name: String,
        memoryMb: Int,
        matchHostCpus: Boolean,
        kernel: String,
        initrd: String,
        cmdline: String,
        disks: List<String>,
    ): Any {
        val image = newBuilder("VirtualMachineCustomImageConfig\$Builder")
        call(image, "setName", name)
        call(image, "setKernelPath", kernel)
        call(image, "setInitrdPath", initrd)
        cmdline.split(' ').filter { it.isNotBlank() }.forEach { call(image, "addParam", it) }
        val disk = cls("VirtualMachineCustomImageConfig\$Disk")
        disks.forEach { call(image, "addDisk", static(disk, "RWDisk", it)) }
        val imageNetwork = runCatching { call(image, "useNetwork", true) }.isSuccess
        // A headless GPU routes the VM to the full crosvm; crosvm_minimal lacks virtio-net.
        runCatching {
            val gpu = newBuilder("VirtualMachineCustomImageConfig\$GpuConfig\$Builder")
            call(gpu, "setBackend", "2d")
            runCatching { call(gpu, "setRendererUseSurfaceless", true) }
            call(image, "setGpuConfig", call(gpu, "build"))
        }.onFailure { Log.w(TAG, "Headless GpuConfig unavailable", it) }

        val builder = newBuilder("VirtualMachineConfig\$Builder", context)
        val optional = setOf("setConsoleInputDevice", "setConnectVmConsole")
        listOf(
            "setProtectedVm" to false,
            "setMemoryBytes" to memoryMb * 1024L * 1024L,
            "setCpuTopology" to if (matchHostCpus) CPU_TOPOLOGY_MATCH_HOST else CPU_TOPOLOGY_ONE_CPU,
            "setDebugLevel" to 1,
            "setVmOutputCaptured" to true,
            "setConsoleInputDevice" to "hvc0",
            "setConnectVmConsole" to false,
            "setCustomImageConfig" to call(image, "build"),
        ).forEach { (method, value) ->
            runCatching { call(builder, method, value) }.onFailure { if (method !in optional) throw it }
        }
        // Android 15 has the network switch on VirtualMachineConfig.Builder instead.
        if (!imageNetwork) {
            runCatching { call(builder, "setNetworkSupported", true) }
                .onFailure { Log.w(TAG, "No network API; the guest will have no network", it) }
        }
        val config = call(builder, "build")

        val manager = call(context, "getSystemService", cls("VirtualMachineManager"))
            ?: error("VirtualMachineManager unavailable")
        val vm = call(manager, "getOrCreate", name, config)!!
        return runCatching { call(vm, "setConfig", config); vm }.getOrElse {
            Log.i(TAG, "Recreating VM with incompatible config: ${it.message}")
            call(manager, "delete", name)
            call(manager, "create", name, config)!!
        }
    }

    /** [onStop] gets null on a clean guest power-off, else a description of the failure. */
    fun setCallback(vm: Any, onStop: (String?) -> Unit) {
        val type = cls("VirtualMachineCallback")
        val callback = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { self, method, args ->
            when (method.name) {
                "onError" -> onStop("AVF error ${args?.get(1)}: ${args?.get(2)}")
                "onStopped" -> onStop(if (args?.get(1) == STOP_REASON_SHUTDOWN) null else "VM stopped (AVF reason ${args?.get(1)})")
                "hashCode" -> System.identityHashCode(self)
                "equals" -> self === args?.get(0)
                "toString" -> "AslVmCallback"
                else -> null
            }
        }
        call(vm, "setCallback", Executor { it.run() }, callback)
    }
}
