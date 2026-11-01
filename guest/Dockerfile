# syntax=docker/dockerfile:1.7
ARG KERNEL_VERSION=6.12.115
ARG ALPINE_VERSION=3.20
ARG GO_VERSION=1.23

FROM --platform=$BUILDPLATFORM debian:bookworm-slim AS kernel
ARG KERNEL_VERSION
RUN apt-get update && apt-get install -y --no-install-recommends \
        bc bison ca-certificates ccache flex gcc libc6-dev libelf-dev libssl-dev make perl python3 wget xz-utils \
    && if [ "$(uname -m)" != aarch64 ]; then apt-get install -y --no-install-recommends gcc-aarch64-linux-gnu binutils-aarch64-linux-gnu; fi \
    && rm -rf /var/lib/apt/lists/*
COPY kernel/asl.config /tmp/asl.config
# One layer from download to cleanup, so the cached result is just the Image, not the build tree.
# A VM needs none of the SoC platforms, and they pull in most of defconfig's built-in drivers, so they are dropped first.
# ccache lives in a cache mount (persisted by CI) so a config change recompiles only what it touches.
RUN --mount=type=cache,id=asl-kernel-ccache,target=/ccache set -eu; \
    mkdir /src && cd /src; \
    wget -qO- https://cdn.kernel.org/pub/linux/kernel/v${KERNEL_VERSION%%.*}.x/linux-${KERNEL_VERSION}.tar.xz | tar -xJ --strip-components=1; \
    if [ "$(uname -m)" != aarch64 ]; then export CROSS_COMPILE=aarch64-linux-gnu-; fi; \
    export ARCH=arm64 CCACHE_DIR=/ccache CCACHE_MAXSIZE=2G; \
    for p in $(sed -En 's/^(menu)?config (ARCH_[A-Z0-9_]+)$/\2/p' arch/arm64/Kconfig.platforms); do \
        sed -i "/^CONFIG_${p}=y$/d" arch/arm64/configs/defconfig; \
    done; \
    make defconfig; \
    scripts/kconfig/merge_config.sh -m .config /tmp/asl.config; \
    make olddefconfig; \
    for opt in VSOCKETS VIRTIO_VSOCKETS NET_9P_FD 9P_FS USBIP_VHCI_HCD EXT4_FS PID_NS PCI_HOST_GENERIC VIRTIO_PCI VIRTIO_BLK VIRTIO_NET VIRTIO_CONSOLE; do \
        grep -qx "CONFIG_${opt}=y" .config || { echo "CONFIG_${opt} is not =y" >&2; exit 1; }; \
    done; \
    make -j"$(nproc)" CC="ccache ${CROSS_COMPILE:-}gcc" Image; \
    ccache -s; \
    cp arch/arm64/boot/Image /Image; \
    cd / && rm -rf /src

FROM --platform=$BUILDPLATFORM golang:${GO_VERSION} AS agent
WORKDIR /src
COPY agent/go.mod agent/go.sum ./
RUN go mod download
COPY agent/ ./
RUN CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags="-s -w" -o /asl-init .

# Installs linux/arm64 Alpine packages from the native builder without emulation.
FROM --platform=$BUILDPLATFORM alpine:${ALPINE_VERSION} AS initramfs
ARG ALPINE_VERSION
RUN apk add --no-cache busybox-static cpio
RUN set -eu; \
    mkdir -p /root-fs/etc/apk; \
    printf 'https://dl-cdn.alpinelinux.org/alpine/v%s/main\n' "$ALPINE_VERSION" > /root-fs/etc/apk/repositories; \
    apk add --root /root-fs --initdb --arch aarch64 --no-scripts --no-cache \
        --keys-dir /usr/share/apk/keys/aarch64 \
        busybox-static e2fsprogs tar xz zstd gzip; \
    rm -rf /root-fs/var/cache/apk /root-fs/lib/apk; \
    cd /root-fs; \
    mkdir -p bin sbin usr/bin usr/sbin usr/share/udhcpc proc sys dev run tmp mnt root etc; \
    for applet in $(busybox.static --list-full); do \
        name="${applet##*/}"; \
        for dir in bin sbin usr/bin usr/sbin; do [ -e "$dir/$name" ] && continue 2; done; \
        ln -s /bin/busybox.static "$applet"; \
    done; \
    ln -sf /bin/busybox.static bin/nsenter; \
    test -e sbin/udhcpc && test -e sbin/mkfs.ext4; \
    cp /usr/share/udhcpc/default.script usr/share/udhcpc/default.script; \
    chmod 1777 tmp
COPY --from=agent /asl-init /root-fs/init
RUN set -eu; cd /root-fs; \
    [ -e dev/console ] || mknod -m 600 dev/console c 5 1; \
    find . | cpio -o -H newc --quiet | gzip -9 > /initrd.img

FROM scratch
COPY --from=kernel /Image /Image
COPY --from=initramfs /initrd.img /initrd.img
