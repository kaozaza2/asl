# ASL — Android Subsystem for Linux

[![Build](https://github.com/kaozaza2/asl/actions/workflows/build.yml/badge.svg)](https://github.com/kaozaza2/asl/actions/workflows/build.yml)

Run real Linux distributions on Android the way WSL2 runs them on Windows. ASL boots one lightweight utility VM through the **Android Virtualization Framework (AVF)**, hosts every distribution in it in its own namespaces, and gives you a terminal modeled on **Windows Terminal**.

> **Status: early development.** It runs on real hardware (tested on a Galaxy Tab S10 with Android 17), but expect rough edges. Only **aarch64 (arm64-v8a)** devices are supported for now.

## Features

| WSL | ASL |
|-----|-----|
| Utility VM, one namespace set per distro | Non-protected AVF VM running `asl-init` as PID 1; each distro gets its own mount, pid, uts and ipc namespaces and shares one network |
| `wsl --install`, `--import`, `--export`, `--unregister`, `--set-default`, `--terminate`, `--shutdown` | Settings → Distributions: install from a catalog (Ubuntu, Debian, Alpine, Arch Linux ARM, Fedora), import any rootfs tarball, export, unregister, set default, terminate; Settings → Virtual machine: start, shut down, restart |
| First-run setup from `/etc/wsl-distribution.conf` `[oobe]` | Same file and keys: the image's OOBE runs once in the first terminal; without one the distro logs in as root |
| `wsl -d X -u U --cd D -- cmd` | Profiles, plus the `dev.asl.action.RUN` intent (`distro`, `command`, `cwd` extras) |
| `\\wsl$\<distro>` in Explorer | A DocumentsProvider root per distro, "ASL: &lt;name&gt;", in the Files app and every file picker |
| `/mnt/c` automount | Android shared storage at `/mnt/android`, served by a 9P2000.L server in the app |
| localhost forwarding | Guest TCP listeners on ports ≥ 1024 are bound on Android's `127.0.0.1`/`::1`, or on all interfaces |
| usbipd-win | USB passthrough over USB/IP: Android is the server, the guest's `vhci_hcd` attaches over vsock |
| `wsl.conf` / `.wslconfig` | `/etc/wsl.conf` (`[boot]`, `[user]`, `[network]`, `[automount]`) in each distro / Settings → Virtual machine |
| systemd | `[boot] systemd=true` runs the distro's systemd as PID 1 in its namespace |
| Windows Terminal | Tab strip, profiles and color schemes with the same `settings.json` shape, a native terminal (Termux's emulator), an extra-keys row for touch keyboards |

## Requirements

- An arm64 device with **Android 15 or later** and AVF with pKVM. Check with:
  ```sh
  adb shell pm has-feature android.software.virtualization_framework
  ```
- AVF's APIs are system APIs. Grant the two development permissions once, then restart the app:
  ```sh
  adb shell pm grant dev.asl android.permission.MANAGE_VIRTUAL_MACHINE
  adb shell pm grant dev.asl android.permission.USE_CUSTOM_VIRTUAL_MACHINE
  adb shell am force-stop dev.asl
  ```
- For `/mnt/android`, allow all-files access in Settings → Virtual machine.

## Building

You need JDK 17, the Android SDK (platform 36) and Docker with `buildx`. The guest kernel and initramfs are built in Docker, so this works on macOS and Linux, on Intel or Apple silicon.

```sh
git clone <this repo> asl && cd asl
guest/build.sh                       # builds the kernel Image and initrd.img into app/src/main/assets/vm/
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then grant the permissions above, open ASL, and install a distribution from Settings → Distributions.

Every push to `main` and every pull request is built by [GitHub Actions](.github/workflows/build.yml). The debug APK, with the guest image bundled, is attached to each run as the `asl-debug-apk` artifact.

## How it works

```
Android app (dev.asl)                                 Utility VM (AVF / crosvm)
┌───────────────────────────────┐   vsock :5000   ┌─────────────────────────────────┐
│ Terminal tabs ──── session ───┼────────────────►│ asl-init (PID 1)                │
│ DocumentsProvider ── fs/file ─┼────────────────►│  ├─ distro A namespaces (ext4)  │
│ Port forwarder ──── forward ──┼────────────────►│  ├─ distro B namespaces (ext4)  │
│ USB/IP server ───── usbip ────┼────────────────►│  ├─ vhci_hcd (USB/IP client)    │
│ 9P server ───────── share9p ──┼────────────────►│  └─ /mnt/android (9p)           │
└───────────────────────────────┘                 └─────────────────────────────────┘
```

Every connection is opened by the app; Android apps cannot listen on vsock. The wire protocol is specified in [`docs/PROTOCOL.md`](docs/PROTOCOL.md). [`docs/AVF_NOTES.md`](docs/AVF_NOTES.md) collects the AVF API research, with sources, and a WSL feature-parity table.

Each distribution is a sparse ext4 disk image attached to the VM at boot, so installing or removing one restarts the VM, much like `wsl --shutdown`.

## Repository layout

| Path | Contents |
|------|----------|
| `app/src/main/java/dev/asl/core/` | Contracts and models shared by every package |
| `app/src/main/java/dev/asl/vm/` | AVF via reflection, VM lifecycle, vsock connections, terminal sessions, foreground service |
| `app/src/main/java/dev/asl/distro/` | Distro registry, catalog, install, import, export |
| `app/src/main/java/dev/asl/storage/` | `\\wsl$`-style DocumentsProvider |
| `app/src/main/java/dev/asl/net/`, `share/`, `usb/` | Port forwarding, 9P server, USB/IP server |
| `app/src/main/java/dev/asl/settings/`, `ui/` | `settings.json`, the Windows Terminal-style Activity, Fragments and Preferences |
| `terminal-emulator/`, `terminal-view/` | Termux's terminal, vendored; the session runs over vsock instead of a local pty |
| `guest/agent/` | `asl-init`, the guest agent (Go) |
| `guest/kernel/`, `guest/Dockerfile` | Kernel config fragment and the kernel and initramfs build |

## Known limitations

- **Platform:**
  - Ports below 1024 can't be forwarded, because Android apps lack `CAP_NET_BIND_SERVICE`.
  - There is no mirrored networking, and the guest can't reach Android's localhost.
  - AVF offers either one vCPU or the host's CPU topology, nothing in between.
  - Networking depends on the device's AVF build enabling it.
- **USB:**
  - No isochronous transfers (audio devices, webcams), because the Android USB host API doesn't support them.
  - Low-speed devices are reported as full speed.
- **Not available:** WSLg (GUI apps), Windows-style interop, and nested virtualization.
- **Abrupt shutdowns:** if Android kills the app, the VM stops without a clean shutdown. The guest writes changes to disk within a few seconds to limit what's lost.

## Contributing

Contributions are welcome. See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

ASL is licensed under the [Apache License 2.0](LICENSE). It includes the Termux terminal modules (Apache 2.0); see [NOTICE](NOTICE) for third-party components. The guest image built by `guest/build.sh` contains GPL-licensed software (the Linux kernel, BusyBox) under its own terms.
