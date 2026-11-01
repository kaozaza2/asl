# AVF notes for ASL

Reference for the AVF backend. Checked against AOSP `main` on 2026-11-01, the `android15-release` and
`android16-release` branches, and [Podroid](https://github.com/ExTV/Podroid) (GPL-2.0; read for facts, no code copied).

**Confidence tags:** **V** = verified in source, **E** = empirical (podroid ships it on real Pixels),
**I** = inferred, needs a device test.

**Source keys** (all paths relative to the repo root unless noted):

| Key | Location |
|-----|----------|
| `[API]` | `https://android.googlesource.com/platform/packages/modules/Virtualization/+/refs/heads/main/libs/framework-virtualization/api/system-current.txt` |
| `[FW]` | same repo, `libs/framework-virtualization/src/android/system/virtualmachine/*.java` (on A15: `java/framework/src/...`) |
| `[VMGR]` | same repo, `android/virtmgr/src/aidl.rs`, `android/virtmgr/src/crosvm.rs` |
| `[AIDL]` | same repo, `android/virtualizationservice/aidl/android/system/virtualizationservice/*.aidl` |
| `[RES]` | same repo, `android/android.system.virtualmachine.res/AndroidManifest.xml` |
| `[TA]` | same repo, `android/TerminalApp/java/com/android/virtualization/terminal/*.kt`, `build/debian/vm_config.json` |
| `[FWD]` | same repo, `android/forwarder_host/src/forwarder_host.rs` |
| `[SEP]` | `https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/` plus the file named |
| `[LNX]` | `https://git.kernel.org/pub/scm/linux/kernel/git/torvalds/linux.git/plain/` plus the file named |
| `[PD]` | `https://github.com/ExTV/Podroid/tree/main/app/src/main/java/com/excp/podroid/engine/avf/` plus the file named |
| `[MS]` | `https://learn.microsoft.com/en-us/windows/wsl/wsl-config` |

---

## 0. Corrections to act on now

1. **The host cannot half-close a vsock socket. `Os.shutdown()` returns EACCES.** (V, `[SEP] private/app_neverallows.te`:
   `neverallow all_untrusted_apps *:vsock_socket ~{ getattr getopt read write };` and `public/te_macros`
   `virtualizationservice_use`: `allow $1 virtualizationmanager:vsock_socket { getattr getopt read write };`.)
   The kernel checks `shutdown`, `setopt` and `ioctl` separately, and untrusted apps never get them. As a result:
   - `VsockConnection.shutdownOutput()` and the `close()` path's `Os.shutdown(SHUT_RDWR)` fail. So does `UsbIpServer.close()`'s shutdown.
   - The PROTOCOL.md `import` service ("host sends the tarball and half-closes") can never finish. The `forward`
     service's "half-close propagates in both directions" only works guest→host.
   - `InputStream.available()` (FIONREAD ioctl) and `SO_RCVTIMEO` (setsockopt) are denied too.
   - **Fix:** signal host→guest EOF in-band. For `import`, either send the byte length in the header (`"size":N`) or
     send `[u32 len][bytes]` chunks ending with `len=0`. For `forward`, use the same chunk framing with a zero-length
     FIN, or drop host-side half-close. To wake a blocked reader, close **both** pfds (the reader and its `dup()`).
     Leaving the dup open keeps the connection alive (E, `[PD] AvfDownloadsShare.kt stop()`). Closing through
     `ParcelFileDescriptor.close()` goes through libcore's `IoBridge.closeAndSignalBlockedThreads` (I).
   - The guest side is unrestricted: the agent may `shutdown()` freely, and the host sees a normal EOF.
   - podroid's `AvfTcpRelay.shutdownOutput()` calls `Os.shutdown`. Treat that as evidence of nothing; it hits the same policy.
2. **Network API differs by release.** On A16 and `main`, the call is `VirtualMachineCustomImageConfig.Builder.useNetwork(boolean)` (V).
   On A15 there is no `useNetwork`. The call is **`VirtualMachineConfig.Builder.setNetworkSupported(boolean)`**, a
   `@TestApi` that needs `USE_CUSTOM_VIRTUAL_MACHINE` + `INTERNET` (V, `[FW]` on android15-release).
   `Avf.kt` (and podroid) fall back to `setNetworkSupported` on the *custom-image* builder, which never exists. Move that
   fallback to the `VirtualMachineConfig.Builder`. Networking is rejected for protected VMs (V, `[VMGR] aidl.rs`).
3. **The minimum vsock port is 1024.** `connectVsock` throws `IllegalArgumentException` below `MIN_VSOCK_PORT = 1024` (V, `[API]`).
   Port 5000 is fine. Keep every future agent port at 1024 or above.
4. **The forwarding threshold is off by one.** The Terminal app skips only `port < 1024` (V, `[FWD]`), and Linux
   privileged ports are 0–1023, so the app can bind 1024. Change `MIN_FORWARDED_PORT` to 1024, and change PROTOCOL.md's
   "`> 1024`" and "`<= 1024`" to "`>= 1024`" and "`< 1024`".
5. **There is no USB device-attach API for apps.** `UsbConfig(controller)` only removes crosvm's `--no-usb` and gives an
   empty xHCI controller. Neither `IVirtualMachine` nor `VirtualMachine` has an attach method (V, `[VMGR] crosvm.rs`, `[AIDL] IVirtualMachine.aidl`).
   USB/IP over vsock (PROTOCOL `usbip`) is the only route, and vhci accepts a vsock fd (§9).
6. **`getLogOutput()` must also be drained** when `setVmOutputCaptured(true)`. Otherwise the guest blocks when the pipe
   fills (V, `[FW] VirtualMachineConfig.setVmOutputCaptured` javadoc). crosvm wires the log pipe to guest `/dev/hvc2`
   (V, `[VMGR] crosvm.rs`). Never write to hvc2 from the guest unless the log is drained.
7. **`VirtualMachineCallback` has no `onDied`.** Its methods are `onPayloadStarted/Ready/Finished`, `onError(vm,int,String)` and
   `onStopped(vm,int)` (V, `[API]`). `onDied` exists only on the internal AIDL callback. `Avf.kt` handles this correctly.
8. **Storage sharing has a better option on A16 and later:** virtiofs through `SharedPath(..., appDomain=false, ...)`, which is
   how the Terminal app shares `/storage/emulated/<user>/Download` (V, `[TA] ConfigJson.kt`). virtmgr spawns
   `crosvm device fs`, and `crosvm.te` allows `fuse` file/dir access (V, `[SEP] private/crosvm.te`). Whether this works
   for an untrusted app is still untested (I). Keep 9p-over-vsock (PROTOCOL `share9p`) as the A15 path and the fallback (§8).
9. **Disk resize must be done in the guest.** The Terminal app runs `/system/bin/e2fsck` and `/system/bin/resize2fs` from its
   privileged `vmlauncher_app` domain (V, `[TA] InstalledImage.kt`). An untrusted app is unlikely to be allowed to exec
   them (I). Grow the sparse file with `RandomAccessFile.setLength` on the host and run `resize2fs` in the guest
   (`[PD] AvfEngine.ensureStorageImage`, E).

---

## 1. Platform requirements and permissions

| Item | Value | Conf / source |
|------|-------|---------------|
| Device feature | `android.software.virtualization_framework` (`PackageManager.FEATURE_VIRTUALIZATION_FRAMEWORK`); `VirtualMachineManager` is `@RequiresFeature` it | V `[FW] VirtualMachineManager.java` |
| `MANAGE_VIRTUAL_MACHINE` | `signature\|preinstalled\|development` → `adb shell pm grant <pkg> android.permission.MANAGE_VIRTUAL_MACHINE` works | V `[RES]` |
| `USE_CUSTOM_VIRTUAL_MACHINE` | `signature\|development` ("Not for use by third-party applications") → `pm grant` works | V `[RES]` |
| When grants take effect | Grant, then force-stop the app; podroid re-picks its engine only at process start | E `[PD]`/CLAUDE.md |
| Custom kernel (raw config) | Needs `USE_CUSTOM_VIRTUAL_MACHINE`; any `RawConfig` counts as custom | V `[VMGR] aidl.rs is_custom_config` |
| Non-protected VM | `setProtectedVm(false)` throws `UnsupportedOperationException("Non-protected VMs are not supported…")` unless `ro.boot.hypervisor.vm.supported` | V `[FW] VirtualMachineConfig.Builder.setProtectedVm` |
| Capabilities | `getCapabilities()` bitmask: `CAPABILITY_PROTECTED_VM=1`, `CAPABILITY_NON_PROTECTED_VM=2` | V `[API]` |
| Network feature | `isFeatureEnabled("com.android.kvm.NETWORK")`, a `@TestApi`. It is false on APEXes built without networking | V `[AIDL] IVirtualizationService.aidl`; E `[PD] AvfReflect.isNetworkFeatureEnabled` |
| Hidden API | Needs reflection plus `HiddenApiBypass.addHiddenApiExemptions("Landroid/system/virtualmachine/", "Landroid/system/virtualizationservice/")` on A14+ | E `[PD] PodroidApplication.kt` (ASL `AslApp.kt` already does this) |
| Protected VM + custom image | pVMs require SELinux-labeled kernel and disks (`apk_data_file`, `system_file`, …). App-downloaded files are rejected, and pVMs get no network | V `[VMGR] aidl.rs check_label_is_allowed` |
| Non-protected file labels | No label check, so disks, kernel and initrd may live in `filesDir` (`app_data_file`). The **app process** opens them (`ParcelFileDescriptor.open` in `toVsRawConfig`), and crosvm may use `app_data_file` | V `[FW] VirtualMachineConfig.toVsRawConfig`, `[SEP] private/crosvm.te` |
| VM lifetime | The app execs `virtmgr` (`domain_auto_trans(app, virtualizationmanager_exec, …)`), and `virtmgr` spawns crosvm, so the VM dies with the app process. Run it from a foreground service | V `[SEP] public/te_macros`; I for the death semantics |

**Version matrix** (the framework jar lives in the updatable `com.android.virt` APEX, so Pixels can be ahead of the OS release):

| Release | Custom-image config | Network | SharedPath / UsbConfig / AudioConfig | GpuConfig |
|---------|---------------------|---------|----------------------------------------|-----------|
| A14 (`android14-release`) | **absent** (no `VirtualMachineCustomImageConfig`) | – | – | – |
| A15 (`android15-release`) | present (`setName/KernelPath/InitrdPath/BootloaderPath/addDisk/addParam/Display/Gpu/useTouch…`) | `VirtualMachineConfig.Builder.setNetworkSupported` | absent | present |
| A16 / main | present | `CustomImageConfig.Builder.useNetwork` | present | present |

(V, `[FW]` on each branch.) Plan for A15 as the minimum and A16 as the main target.

---

## 2. API reference (exact signatures)

### VirtualMachineManager (V, `[API]`, `[FW]`)
```
VirtualMachine create(String name, VirtualMachineConfig)        // @SystemApi, MANAGE perm
VirtualMachine get(String name)                                 // nullable
VirtualMachine getOrCreate(String name, VirtualMachineConfig)
void delete(String name)
int getCapabilities()
VirtualMachine importFromDescriptor(String, VirtualMachineDescriptor)
boolean isFeatureEnabled(String)                                // @TestApi @hide
List<String> getSupportedOSList()                               // @TestApi
```
Get it with `context.applicationContext.getSystemService(VirtualMachineManager::class.java)` (use the application context, b/359439878 per `[TA] Runner.kt`).

### VirtualMachineConfig.Builder(Context) (V, `[FW]`)
```
setProtectedVm(boolean)            // MUST be called; build() throws "setProtectedVm must be called explicitly"
setMemoryBytes(long)               // > 0
setCpuTopology(int)                // CPU_TOPOLOGY_ONE_CPU=0 | CPU_TOPOLOGY_MATCH_HOST=1, nothing else
setDebugLevel(int)                 // DEBUG_LEVEL_NONE=0 | DEBUG_LEVEL_FULL=1
setVmOutputCaptured(boolean)       // needs FULL; enables getConsoleOutput()+getLogOutput()
setVmConsoleInputSupported(boolean)// @TestApi; needs FULL; required for getConsoleInput()
setConnectVmConsole(boolean)       // @hide; needs FULL. Keep false (podroid: inherits Activity fds -> SELinux denial, E)
setConsoleInputDevice(String?)     // @hide; "hvc0" (default) or "ttyS0"; anything else -> virtmgr bail
setCustomImageConfig(VirtualMachineCustomImageConfig) // @hide, USE_CUSTOM perm
setShouldUseHugepages(boolean)     // flagged SystemApi
setShouldBoostUclamp(boolean)      // @hide
setNetworkSupported(boolean)       // A15 ONLY, @TestApi, USE_CUSTOM + INTERNET
```
`isCompatibleWith()` compares only debug level, protected, outputCaptured, consoleInputSupported, connectVmConsole,
consoleInputDevice, payload, package, OS and vendor disk. **A change to the custom-image config (disks, params, network)
is always "compatible"**, so `vm.setConfig(newCfg)` on a stopped VM is enough to change disks; no delete is needed
(V, `[FW] isCompatibleWith`). `setConfig` throws `VirtualMachineException("incompatible config")` otherwise, and
throws unless the VM is stopped.

### VirtualMachine (V, `[API]`, `[FW]`)
```
void run()                                   // MANAGE perm; @WorkerThread
void stop(); void close(); int getStatus()   // STATUS_STOPPED=0, RUNNING=1, DELETED=2
ParcelFileDescriptor connectVsock(long port) // 1024..0xffffffff; host->guest connect; throws if not running
IBinder connectToVsockServer(long port)      // binder RPC over vsock (needs libbinder_rpc in guest) - not for us
InputStream getConsoleOutput()               // needs vmOutputCaptured
OutputStream getConsoleInput()               // @TestApi, needs vmConsoleInputSupported
InputStream getLogOutput()                   // needs vmOutputCaptured; MUST be drained
void setCallback(Executor, VirtualMachineCallback); void clearCallback()
VirtualMachineConfig setConfig(VirtualMachineConfig)  // returns old config
void suspend(); void resume()                // @TestApi
long getMemoryBalloon(); void setMemoryBalloon(long)  // @hide
```

### VirtualMachineCallback (V, `[API]`)
`onPayloadStarted(vm)`, `onPayloadReady(vm)`, `onPayloadFinished(vm,int)` (Microdroid only), `onError(vm,int,String)`,
`onStopped(vm,int)`. Stop reasons: `-1 VIRTUALIZATION_SERVICE_DIED, 0 INFRASTRUCTURE_ERROR, 1 KILLED, 2 UNKNOWN, 3 SHUTDOWN,
4 START_FAILED, 5 REBOOT, 6 CRASH, … 16 HANGUP`. A guest `reboot` shows up as `5 REBOOT`, not a restart.

### VirtualMachineCustomImageConfig.Builder (@hide, all V `[FW]` main/A16)
```
Builder()                                 setName(String)   setKernelPath(String)  setInitrdPath(String)
setBootloaderPath(String)                 addParam(String)  // joined with " "
addDisk(Disk)                             Disk.RWDisk(String path) / Disk.RODisk(String path) (static); Disk.addPartition(Partition)
Partition(String label, String imagePath, boolean writable, String guid)
addSharedPath(SharedPath)                 // A16+
useNetwork(boolean)                       // A16+
setGpuConfig(GpuConfig)  setDisplayConfig(DisplayConfig)  setAudioConfig(AudioConfig)  setUsbConfig(UsbConfig) // UsbConfig A16+
useTouch/useKeyboard/useMouse/useSwitches/useTrackpad(boolean)   useAutoMemoryBalloon(boolean)
build()
SharedPath(String path, int hostUid, int hostGid, int guestUid, int guestGid, int mask,
           String tag, String socket, boolean appDomain, String socketPath)
GpuConfig.Builder(): setBackend(String) setContextTypes(String[]) setPciAddress(String) setRendererFeatures(String)
           setRendererUseEgl/Gles/Glx/Surfaceless/Vulkan(java.lang.Boolean)   // BOXED Boolean, not primitive
UsbConfig(boolean controller) / UsbConfig.Builder().setController(boolean).build()
AudioConfig.Builder().setUseMicrophone(boolean).setUseSpeaker(boolean).build()
```
`GpuConfig` setters take a **boxed `java.lang.Boolean`**. Reflection that looks up `Boolean.TYPE` fails. podroid's
`setRendererUseSurfaceless` lookup is wrapped in `runCatching`, so it silently never applies. `Avf.kt`'s `isInstance`
matcher handles the boxed type correctly. Even without `surfaceless`, `backend=2d` alone is what the Terminal app ships
(V, `[TA] build/debian/vm_config.json`).

If neither kernel nor bootloader is set, the framework defaults to the bundled U-Boot (V, `[FW] toVsRawConfig`).

---

## 3. vsock model

- Only the host can open connections: `connectVsock(port)` returns a socket that **virtmgr** created and connected. Apps
  cannot `create/bind/listen/accept/connect` vsock (V, `[SEP]` neverallows above, `private/virtualizationmanager.te:64`).
  The Terminal app listens on vsock only because it runs as the privileged `vmlauncher_app` domain (V, `[SEP] private/vmlauncher_app.te:32`).
- On the returned fd, the app may only `read`, `write`, `getattr` and `getopt` (see §0.1). `poll()`/`select()` need no extra permission (I).
- The guest listens on `VMADDR_CID_ANY`. A guest connect to CID 2 reaches nothing app-owned. PROTOCOL.md's "host-initiated only" is correct.
- `connectVsock` fails until the guest listener is up. Retry with backoff: podroid uses 30 attempts × 500 ms (E, `[PD] AvfDownloadsShare.connectWithRetry`).
- Give the read side and the write side (`pfd.dup()`) one owner each, and close both (E, `[PD] AvfDownloadsShare`, ASL `VsockConnection` already does this).

---

## 4. Console and boot

- crosvm serial layout (V, `[VMGR] crosvm.rs` ~l.1236): `ttyS0` = console out (+ input if `consoleInputDevice=ttyS0`),
  `ttyS1` = failure pipe, **`hvc0` = console out (+ input by default)**, `hvc1` = ramdump, `hvc2` = **log output**.
- `consoleInputDevice` defaults to `hvc0` (the `CONSOLE_HVC0` constant). The Terminal app uses `ttyS0` (V, `[TA] vm_config.json`).
  podroid uses `hvc0` because crosvm's PL011 runs at roughly 14 KB/s (E, `[PD] AvfEngine.buildConfig`).
- If `debugLevel==NONE` but output is captured, virtmgr appends `printk.devkmsg=on console=hvc0` itself (V).
- Useful cmdline additions:
  - `earlycon keep_bootcon`: early panics reach the console stream (E).
  - `arm64.nompam`: Pixel 8 Pro on A17 traps `MPAMIDR_EL1` (E, podroid #108).
  - A wall-clock seed such as `asl.epoch=$(now)`: podroid reports the guest boots at 1970 without it (E). Set it from the agent.
- **The kernel must be a raw arm64 `Image`** (magic `ARM\x64` at 0x38). crosvm does not gunzip `vmlinuz` (E, `[PD] AvfEngine.ensureRawKernel`).
- crosvm is launched with `--no-pmu` on aarch64 and with `--no-balloon` unless `useAutoMemoryBalloon(true)` (V).

---

## 5. Networking and port forwarding

- `useNetwork(true)` makes virtualizationservice create a TAP and pass `--net tap-fd=N` to crosvm. It is rejected for pVMs (V, `[VMGR]`).
  The guest gets virtio-net and uses DHCP (E: podroid `podroid-network`; Terminal Debian uses networkd).
- **crosvm_minimal trap (E, podroid only; AOSP main has a single `CROSVM_PATH`):** on Pixel's `com.google.android.virt` APEX,
  GPU-less VMs run `crosvm_minimal`, which is built without `net` and aborts on `--net` (exit 35). Declare a
  headless `GpuConfig(backend="2d")` to force the full crosvm. The Terminal app does exactly this in its stock
  `vm_config.json` (`"gpu": {"backend": "2d"}`) (V).
- **Terminal app forwarding** (V, `[FWD]`, `[TA] DebianServiceImpl.kt`, `VmLauncherService.kt`):
  - The guest's `forwarder_guest_launcher` reports listening TCP ports over gRPC `reportVmActivePorts`. The gRPC server is
    plain **TCP** on the host (`OkHttpServerBuilder.forPort(0)`, "gRPC for java doesn't support vsock"). The interceptor
    accepts only calls from the guest IP. The port is written to `filesDir/debian_service_port`, which the guest reads
    through the virtiofs share of `$APP_DATA_DIR/files`. The guest IP is discovered over mDNS (`_http._tcp`, "ttyd").
  - The host binds **both `127.0.0.1:p` and `[::1]:p`** for each enabled port with `p >= 1024` ("the host may resolve localhost to either").
    For each accepted TCP connection, it listens on vsock (`VMADDR_PORT_ANY`) and tells the guest `(guestTcpPort, vsockPort)`. The guest connects back.
  - Users toggle ports on and off in Settings (`PortsStateManager`), with a notification for each new port (`PortNotifier`).
- **For ASL:** the reverse-connect design cannot work (the app cannot listen on vsock). PROTOCOL's host-connects
  `forward` service is the right shape. Bind both v4 and v6 loopback to match Terminal/WSL behavior.
- Whether app→guest TCP to the guest's DHCP IP works from an untrusted app is untested (I). Don't depend on it.
- Guest→Android `localhost` (WSL "mirrored" mode) is not feasible. The guest can reach the phone only through its LAN IP over the NAT (I).

---

## 6. Disks

- `addDisk(Disk.RWDisk(path))` → `--block path=<fd>,ro=false,lock=false`, attached as virtio-blk `/dev/vda, vdb…` in
  `addDisk` order (V, `[VMGR] crosvm.rs`; ordering E from podroid, which relies on `/dev/vda` = first disk).
- Disks are fixed at `run()`. There is no hotplug API (V: none in `[AIDL] IVirtualMachine.aidl`). PROTOCOL's "registering restarts the VM" is correct.
- Use sparse raw files in `filesDir`. Grow with `setLength`, never shrink, and run `resize2fs` in the guest (E, `[PD]`).
  The Terminal app uses `Os.posix_fallocate` (not sparse) unless its storage-balloon flag is on (V, `[TA] InstalledImage.kt`).
- `Disk.addPartition(Partition(label, path, writable, guid))` builds a GPT composite disk from separate files.
  The Terminal app uses it for `ROOT` and `EFI` (V).

---

## 7. Shared storage (`/mnt/android`)

| Option | How | Availability | Verdict |
|--------|-----|--------------|---------|
| **virtiofs, appDomain=false** | `addSharedPath(SharedPath("/storage/emulated/0[/sub]", appUid, appUid, guestUid, guestGid, 7, "android", "android", false, ""))`; guest `mount -t virtiofs android /mnt/android`. virtmgr runs `crosvm device fs --shared-dir … --cfg ugid_map='g g h h mask /'` | A16+ | Terminal uses it for Download (V). `crosvm.te` allows `fuse` (V). The FUSE daemon checks the app's uid, so `MANAGE_EXTERNAL_STORAGE` is needed for broad paths (I). **Test on device first.** |
| virtiofs, appDomain=true | The app process execs `/apex/com.android.virt/bin/crosvm device fs` | – | **Blocked:** `neverallow { domain -crosvm -virtualizationmanager -vmlauncher_app … } crosvm_exec:file no_x_file_perms` (V, `[SEP] private/crosvm.te`) |
| 9p2000.L over vsock (`share9p`) | The guest accepts a vsock connection and runs `mount -t 9p -o trans=fd,rfdno=N,wfdno=N,version=9p2000.L,msize=262144,cache=none,access=any`. The host runs the 9P server over `connectVsock` | A15+ | E: podroid ships it (`[PD] AvfDownloadsShare.kt`, `ninep/`, guest `podroid-vsock-agent.c downloads_9p_serve`) |

### 9p `trans=fd` over AF_VSOCK (V, `[LNX] net/9p/trans_fd.c`, `net/9p/Kconfig`)
- It needs `CONFIG_NET_9P=y`, `CONFIG_NET_9P_FD` (a separate tristate since 6.0, `default NET_9P`) and `CONFIG_9P_FS=y`.
  On older kernels, fd transport is built into 9pnet.
- `p9_fd_open` does only `fget()` plus an FMODE_READ/WRITE check. Nothing is socket-family specific, so a vsock fd works.
  It **sets `O_NONBLOCK` on the shared `struct file`**, so after `mount()` the agent's copy of the fd is non-blocking.
  Never read or write it again; only `poll(events=0)` for HUP (E, podroid does exactly this). The mount holds its own
  reference, so the agent may close its fd after `mount()` returns.
- The fd transport caps message size at `MAX_SOCK_BUF = 1 MiB`, so `msize=262144` is safe.
- The handshake ordering in PROTOCOL works as written: the agent sends the `{"ok":true}` line, then calls `mount()`, and
  the kernel sends `Tversion` first. Reading the reply byte-at-a-time is correct, since the host must not over-read.
- When the host closes the socket, the mount goes dead. The agent should `umount2(MNT_DETACH)` on POLLHUP and re-accept (E).

---

## 8. USB

- **No passthrough through AVF.** `setUsbConfig(UsbConfig(true))` only adds crosvm's xHCI. Attaching devices
  would need crosvm's control socket (`crosvm usb attach`), and virtmgr owns that socket with no AIDL or Java method exposed (V).
  podroid's USB passthrough is QEMU-only (E, Podroid `CLAUDE.md`).
- **USB/IP over vsock works by construction** (V, `[LNX] drivers/usb/usbip/vhci_sysfs.c attach_store`):
  - `attach` parses `"%u %u %u %u"` = `port sockfd devid speed`, then `sockfd_lookup()` and only checks
    `socket->type == SOCK_STREAM`. There is **no address-family check**, so an AF_VSOCK stream socket is accepted.
  - `rhport = port % VHCI_HC_PORTS`; the **hub is chosen by `speed`** (`>= USB_SPEED_SUPER` → SS hub). The `port`
    must come from a free row of the matching hub in `status` (rows look like `hs  0000 004 …` / `ss  0008 004 …`, status 4 = `VDEV_ST_NULL`).
    `-EBUSY` means the port is in use: retry another. Accepted speeds: 1, 2, 3, 4 (wireless), 5, 6 (super-plus).
  - The kernel holds its own socket reference, so the agent can close its fd. Detach writes the port to `detach`,
    or happens automatically when the stream errors.
  - Guest kernel config: `CONFIG_USBIP_CORE`, `CONFIG_USBIP_VHCI_HCD`, `CONFIG_VSOCKETS`, `CONFIG_VIRTIO_VSOCKETS` (I).
- **Host-side limits:**
  - `UsbRequest` (used by `UsbIpServer.kt`) supports only bulk and interrupt endpoints; control transfers go through
    `controlTransfer`. **Isochronous** traffic (webcams, audio) needs native `USBDEVFS_SUBMITURB` on
    `UsbDeviceConnection.getFileDescriptor()`. The app may ioctl `usb_device` (V, `[SEP] private/app.te:157`).
  - The Android kernel driver must be detached first (`claimInterface(iface, force=true)`).

---

## 9. CPU and memory (podroid empirical)

- The public API offers only `ONE_CPU` or `MATCH_HOST`. `MATCH_HOST` passes `--host-cpu-topology`. On Tensor G3/G4,
  cross-cluster vCPUs reset the guest in early boot (`STOP_REASON_REBOOT`, issue #29) (E).
  - Mitigation one: add `nr_cpus=N` to the cmdline.
  - Mitigation two (A16+): rewrite `rawConfig.cpuOptions = CpuTopology.cpuCount(N)` through a binder `Proxy` on
    `VirtualizationService.mBinder` (E, `[PD] AvfReflect.installExplicitCpuCount`). `CpuTopology.cpuCount` exists in the AIDL (V, `[FW] toVsRawConfig`).
  - podroid's fallback ladder steps from N down to 2, then to 1, and persists the cap for each device (E, `[PD] AvfCpuPolicy.kt`).
  - ASL's `matchHostCpus` boolean is right for the public API. Add an `nr_cpus` cap setting.
- The Terminal app defaults are 4096 MiB, `match_host` and `auto_memory_balloon: true` (V, `[TA] vm_config.json`).

---

## 10. Reference: Terminal app launch sequence (V, `[TA]`)
```
cfg = VirtualMachineConfig.Builder(ctx).setProtectedVm(false).setMemoryBytes(..).setConsoleInputDevice("ttyS0")
      .setCpuTopology(MATCH_HOST).setCustomImageConfig(custom).setDebugLevel(FULL)
      .setVmOutputCaptured(true).setConnectVmConsole(false).build()
custom: setName("debian").setKernelPath(..).setInitrdPath(..).useNetwork(true).useAutoMemoryBalloon(true)
        .addParam("root=/dev/vda1").addDisk(RWDisk w/ partitions ROOT,EFI).addSharedPath(..x2)
        .setGpuConfig(backend "2d").setAudioConfig(speaker+mic)
vm = vmm.getOrCreate(name, cfg); try { vm.config = cfg } catch (VirtualMachineException) { vmm.delete(name); vm = vmm.create(name, cfg) }
vm.setCallback(ForkJoinPool.commonPool(), cb); vm.run()
```
ASL `Avf.createVm` matches this, with the network-fallback fix from §0.2.

---

## 11. WSL feature parity on AVF

| WSL feature | ASL equivalent | Verdict |
|-------------|----------------|---------|
| `wsl --install <distro>` | Download a rootfs tarball, then `import` | Feasible |
| `--import <name> <dir> <tar>` | `import` svc onto a new sparse disk (VM restart to attach) | Feasible; the EOF signal must change (§0.1) |
| `--import-in-place <vhdx>` | Register an existing ext4 image file | Feasible (raw images only) |
| `--export <name> <tar>` | `export` svc (guest→host EOF works) | Feasible |
| `--unregister` | Delete the disk file and the registry entry (restart if attached) | Feasible |
| `--set-default`, `-d`, `-u`, `--cd`, `-e`, `--` | Session header fields | Feasible |
| `--terminate <d>` | Kill the distro namespace | Feasible |
| `--shutdown` | `shutdown` op → `onStopped(3)` | Feasible |
| `--list --running/--verbose`, `--status`, `--version` | `list` op | Feasible |
| `--set-version 1/2` | – | N/A (VM only) |
| `--mount <disk>` / `--mount --bare` | No block device passthrough; only image files via `addDisk` + restart | Partial (image files only) |
| `--manage --resize` / `--set-sparse` | Host `setLength` plus guest `resize2fs`; sparse by default | Feasible (grow only) |
| `\\wsl$` / `\\wsl.localhost` | `DocumentsProvider` over the `fs`/`file` svcs | Feasible |
| `/mnt/c` automount (DrvFs) | `/mnt/android` through 9p-vsock (A15+) or virtiofs (A16+, untested) | Feasible; may need `MANAGE_EXTERNAL_STORAGE` |
| Windows interop (`notepad.exe` from Linux, `$PATH` append) | Guest→host requests can't originate on vsock; needs a host-polled channel (e.g. a long-lived `control` connection carrying guest-initiated events) | Partial: intents/notifications only |
| `localhostForwarding` (NAT mode) | `forward` svc, bind `127.0.0.1` and `::1`, ports ≥ 1024 | Feasible |
| Mirrored networking / `hostAddressLoopback` | – | Not feasible |
| `networkingMode=none` | `useNetwork(false)` | Feasible |
| systemd (`[boot] systemd`) | Per-distro PID namespace with systemd as its init. Needs cgroup v2, a guest kernel built with systemd options, and `/run` as tmpfs | Feasible (WSL does the same) |
| `[boot] command` | Run after distro start | Feasible |
| `[user] default` | Feasible | Feasible |
| `[network] hostname/generateHosts/generateResolvConf` | Agent writes `/etc/hosts` and `/etc/resolv.conf` | Feasible |
| `[automount] enabled/root/options/mountFsTab` | Map `options` to 9p/virtiofs uid/gid; run `mount -a` for fstab | Feasible (`metadata`/`case` N/A) |
| `[interop] enabled/appendWindowsPath` | – | Mostly N/A |
| `[gpu]`, `[time] useWindowsTimezone` | GPU: virgl only on Terminal's privileged path (I). Time: pass the TZ in `configure` | GPU no; time yes |
| `.wslconfig memory` | `setMemoryBytes` | Feasible |
| `.wslconfig processors` | `ONE_CPU` / `MATCH_HOST` + `nr_cpus` (exact count only via the A16 binder hook) | Partial |
| `.wslconfig swap/swapFile` | An extra `RWDisk` as swap, or zram in the guest | Feasible (restart) |
| `.wslconfig kernel/kernelCommandLine/kernelModules` | `setKernelPath`, `addParam`; modules as an `RODisk` | Feasible |
| `vmIdleTimeout`, `instanceIdleTimeout` | App-side timers | Feasible |
| `autoMemoryReclaim` | `useAutoMemoryBalloon(true)` (A16; `--balloon-page-reporting`) | Feasible on A16 |
| `sparseVhd`, `defaultVhdSize` | `defaultDiskSizeGb`, sparse files | Feasible |
| `nestedVirtualization` | – | Not feasible |
| `guiApplications` (WSLg) | AVF display/GPU paths are tied to `vmlauncher_app` (crosvm binder to `vmlauncher_app`, V `[SEP] crosvm.te:112`) | Not feasible for untrusted apps (I) |
| `dnsTunneling`, `autoProxy`, `firewall` | – | N/A |
| `usbipd-win` (`usbipd attach --wsl`) | USB/IP over vsock with Android as server | Feasible (bulk/int/ctrl via `UsbRequest`; iso needs JNI) |
| `wsl.exe` from another app / shell | `dev.asl.action.RUN` intent; `adb shell content call` | Feasible |

---

## 12. Contracts.kt and PROTOCOL.md mismatches

| Location | Problem | Fix |
|----------|---------|-----|
| `AgentConnection.shutdownOutput()` | Always EACCES on vsock | Remove it, or implement it as an in-band EOF frame |
| PROTOCOL `import` | Ends on half-close | Put `"size"` in the header, or use chunked framing |
| PROTOCOL `forward` | Host→guest half-close is impossible | Use a framed mode with FIN, or full-close semantics |
| `VsockConnection.close()` | `Os.shutdown` is denied (harmless inside `runCatching`, but it is not the wakeup) | Close both pfds |
| `UsbIpServer.close()` | Same | Same |
| `MIN_FORWARDED_PORT = 1025`, PROTOCOL "`> 1024`" | 1024 is bindable | 1024 / `>= 1024` |
| `Avf.createVm` network fallback | `setNetworkSupported` belongs on `VirtualMachineConfig.Builder` (A15) | Move it |
| `Avf.createVm` | Does not drain `getLogOutput()` | Drain it (or never write to hvc2) |
| `Avf.createVm` | `setVmConsoleInputSupported` not set | Fine as long as `getConsoleInput()` is never called |
| `VmSettings.matchHostCpus` | Tensor G3/G4 resets | Add an `nr_cpus` cap with the podroid-style ladder |
| PROTOCOL VM boot | Kernel must be a raw `Image`; no RTC seed | Ship an uncompressed Image; add `asl.epoch=` |
| PROTOCOL `usbip` | Correct as written; one clarification | Note that the hub comes from `speed`, and `port` must be a free row in that hub |
