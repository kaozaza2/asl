# ASL host ↔ guest protocol

The guest agent `asl-init` is PID 1 of the utility VM. It listens on **AF_VSOCK port 5000** (`VMADDR_CID_ANY`).
Every connection is **host-initiated** (`VirtualMachine.connectVsock(5000)`); an Android app cannot listen on vsock.

## Connection handshake

1. Host writes one UTF-8 JSON object terminated by `\n` — the *header*. It always has `"svc"`.
2. Agent replies with one JSON line: `{"ok":true, ...}` or `{"ok":false,"error":"..."}`. On error the agent closes.
3. The connection then switches to the service-specific stream below.

All integers in binary frames are **big-endian**.

**The host can never `shutdown()` a vsock socket** — SELinux grants untrusted apps only `read write getattr getopt` on `vsock_socket` (see AVF_NOTES.md). So wherever the host must signal end-of-stream, the host→guest direction is **chunked**: `[u32 len][len bytes]` repeated, a zero-length chunk means EOF. The guest may `shutdown(SHUT_WR)` freely; the host sees an ordinary EOF. To abort, the host closes the fd.

## Shared constants

Both sides must stay in sync — the app side lives in `dev.asl/core/Constants.kt`; if one changes, the other must change too:

| Constant | App (Kotlin) | Guest (Go) |
|---|---|---|
| Agent vsock port | `dev.asl.core.AGENT_VSOCK_PORT` = 5000 | `agentPort` (guest/agent/main.go) |
| Minimum forwarded port | `dev.asl.core.MIN_FORWARDED_PORT` = 1024 | `minForwardedPort` (guest/agent/control.go) |
| 9P msize | `NinepServer.MAX_MSIZE` = 262144+4096 (server) | `mountMsize` = 262144 (client mount option) |

## VM boot

- Kernel cmdline: `console=hvc0 rdinit=/init quiet asl.port=5000 arm64.nompam asl.time=<epoch seconds>` + user `kernelCommandLine`. The agent sets the clock from `asl.time` (the guest otherwise boots at 1970).
- Kernel must be a raw arm64 `Image` (not gzip). The host must drain both `getConsoleOutput()` and `getLogOutput()` (guest hvc2) or the guest can block.
- Distro disks are attached as virtio-blk in registry order: first distro → `/dev/vda`, second → `/dev/vdb`, ...
  Disks can only be attached at boot, so registering/importing a distro restarts the VM (like `wsl --shutdown`).
- Each distro disk is a raw ext4 image (sparse file on the host). A new disk is all zeroes until `import` formats it.
- Guest network: virtio-net via AVF `useNetwork(true)`; the agent runs DHCP on `eth0`.

## Services

### `control` — long-lived, one per VM boot
Request: `{"id":1,"op":"...", ...}` → response `{"id":1,"ok":true,"result":...}`.
Agent may push events at any time: `{"event":"...", ...}` (no `id`).

| op | args | result |
|----|------|--------|
| `hello` | | `{"version":"0.1.0","kernel":"6.x"}` |
| `configure` | `distros:[{id,name,device,defaultUser,systemd,hostname}]`, `androidMount:"/mnt/android"\|null` | `{}` |
| `start` | `distro` (id) | `{"pid":123}` |
| `terminate` | `distro` | `{}` |
| `setDefaultUser` | `distro`, `user` | `{}`; the user must exist in the distro's `/etc/passwd`. Rewrites `/etc/wsl.conf` `[user] default` and keeps every other line. |
| `list` | | `[{"id","state":"running\|stopped","pid"}]` |
| `shutdown` | | `{}` then the VM powers off |

Events:
- `{"event":"ports","ports":[8080,5173]}` — full set of TCP ports in LISTEN state, `>= 1024` only, any distro (distros share one network namespace, as in WSL2). Sent on change; agent polls `/proc/net/tcp{,6}` every second.
- `{"event":"distro","distro":"<id>","state":"running|stopped","user":"alice"}` — `user` (on `running` only) is the effective default user: wsl.conf `[user] default`, then the configured `defaultUser`, then root. The host records it as the distro's default user.
- `{"event":"oobe","distro":"<id>","user":"alice"}` — first-run setup finished (`user` absent when the image has no OOBE or no `defaultUid`). The host clears `oobePending` and records `user` as the default.

### `session` — interactive shell or command
Header: `{"svc":"session","distro":"<id>","user":"alice","cwd":"~","argv":[],"env":{},"cols":80,"rows":24,"tty":true,"oobe":true}`
- Empty `argv` means login shell. Starts the distro if needed.
- Runs inside the distro's namespaces: `nsenter -t <pid> -m -u -i -p -r -w -- su -l -- <user> [-c '<script>']` (busybox nsenter has no `-a`; `--` keeps `-c` away from su, which would otherwise `setsid()` and lose job control). The script exports env, `cd`s to cwd and runs argv joined with spaces, like wsl.exe.
- First run, like WSL: when `oobe` is true and the session is an interactive login (tty, no argv, no user), the agent reads `/etc/wsl-distribution.conf`. With an `[oobe] command`, it runs that command as root on the same pty, then continues into the login shell; on exit 0 it emits the `oobe` event with the user owning `[oobe] defaultUid`, otherwise the setup runs again next time. Without one it emits `oobe` with no user and the distro stays root by default. Only one tab runs it at a time.
- Every session's script starts with `[ -n "$LANG" ] || export LANG=C.UTF-8`, so minimal rootfs tarballs without a locale still handle UTF-8 input.
- User precedence: header > wsl.conf `[user] default` > configure `defaultUser` > root. On a tty, EOF writes ^D.

Frames after the reply, both directions: `[u8 type][u32 len][payload]`.

| type | dir | payload |
|------|-----|---------|
| 0 DATA | both | stdin / pty output bytes |
| 1 RESIZE | host→guest | `u16 cols, u16 rows` |
| 2 EOF | host→guest | empty; closes stdin |
| 3 STDERR | guest→host | only when `tty:false` |
| 4 EXIT | guest→host | `i32 exit code`, then agent closes |

### `fs` — one metadata operation per connection
Header: `{"svc":"fs","distro":"<id>","op":"list|stat|mkdir|remove|rename","path":"/home/alice","to":"/new/path","recursive":false}`
Reply: `{"ok":true,"entries":[{"name","type":"file|dir|link|other","target":"file|dir|other","size","mtime","mode"}]}` (`mtime` in epoch seconds; `target` only for links) (`list`), `{"ok":true,"entry":{...}}` (`stat`, `name` is basename), `{"ok":true}` otherwise.
Paths are resolved inside the distro root with `openat2(RESOLVE_IN_ROOT)`; new files are chowned to the distro default user.

### `file` — random-access file I/O (backs DocumentsProvider proxy fds)
Header: `{"svc":"file","distro":"<id>","path":"/x","mode":"r|w|rw","create":true,"truncate":false}` → reply `{"ok":true,"size":1234}`.
Requests: `[u8 op][i64 offset][u32 len][len bytes if WRITE]`; responses: `[i32 result][u32 len][len bytes]`.
`result < 0` is `-errno`.

| op | meaning | response |
|----|---------|----------|
| 0 READ | read `len` at `offset` | result = bytes read, data follows |
| 1 WRITE | write data at `offset` | result = bytes written |
| 2 SIZE | | result = 0, 8-byte i64 size |
| 3 FSYNC | | result = 0 |
| 4 TRUNCATE | new size = `offset` | result = 0 |

Closing the connection closes the file.

### `forward` — TCP port forwarding
Header: `{"svc":"forward","port":8080}` → reply, then spliced to `127.0.0.1:8080` in the guest (falls back to `[::1]`).
Host→guest is **chunked** (EOF chunk = client half-closed → agent does `shutdown(SHUT_WR)` on the TCP side); guest→host is raw bytes, and the agent half-closes vsock when the guest server half-closes.
Host side binds `127.0.0.1:<port>` (or `0.0.0.0` when `bindAllInterfaces`) for every reported port; ports `< 1024` are never forwarded (Android apps lack CAP_NET_BIND_SERVICE).

### `usbip` — USB passthrough (USB/IP, like usbipd-win)
Header: `{"svc":"usbip","busid":"1-2","devid":65538,"speed":3}` (speed per Linux `enum usb_device_speed`: 1 low, 2 full, 3 high, 5 super).
Agent picks a free `vhci_hcd` port (HS hub, or SS hub when speed ≥ 5) from `/sys/devices/platform/vhci_hcd.0/status`, replies `{"ok":true,"port":N}` **first** (the kernel may send `CMD_SUBMIT` the moment attach succeeds), then writes `"<port> <fd> <devid> <speed>"` to `.../attach` using this vsock fd. If attach fails the agent closes the socket.
The stream is then the USB/IP data phase with Android as the server: guest sends `USBIP_CMD_SUBMIT`/`USBIP_CMD_UNLINK`, host answers `USBIP_RET_SUBMIT`/`USBIP_RET_UNLINK`. No `OP_REQ_IMPORT` exchange — the header replaces it. Closing the socket detaches the device. The host stops a device by closing the fd (no shutdown).

### `import` — create a distro filesystem from a rootfs tarball
Header: `{"svc":"import","distro":"<id>","device":"/dev/vdb","compression":"gz|xz|zst|none"}`.
Agent runs `mkfs.ext4 -F`, mounts, replies `{"ok":true}`, then extracts the stream with `tar -xp --numeric-owner`. Host sends the tarball **chunked** and ends with the zero-length chunk; agent unmounts, then writes a final line `{"ok":true}` or `{"ok":false,"error":"..."}`, unmounts and closes.

### `export` — `wsl --export`
Header: `{"svc":"export","distro":"<id>"}` → reply, then an uncompressed tar of the distro root (`--numeric-owner`, excluding `/proc /sys /dev /run /tmp /mnt/android`), then EOF.

### `share9p` — Android storage inside the guest (`/mnt/c` equivalent)
Header: `{"svc":"share9p","tag":"android","mountpoint":"/mnt/android"}`.
Agent replies `{"ok":true}`, then `mount -t 9p -o trans=fd,rfdno=<fd>,wfdno=<fd>,version=9p2000.L,msize=262144,cache=none` on this connection; after `mount()` the agent never touches the fd again (the kernel sets O_NONBLOCK on it). The host runs a 9P2000.L server over the stream rooted at `VmSettings.androidStoragePath`. The mount is propagated into every distro as `/mnt/android`.

## Distro configuration inside the guest

Like WSL, the agent honors `/etc/wsl.conf` (also read as `/etc/asl.conf`) in each distro:
`[boot] systemd, command` · `[user] default` · `[network] hostname, generateHosts` (writes `/etc/hosts` and `/etc/hostname`, so an init like systemd keeps the name)`, generateResolvConf` · `[automount] enabled, root`.
