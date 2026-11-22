#!/usr/bin/env bash
# Builds the companion-VM payload on an x86_64 Linux box (Codespace is fine):
#   Image.gz (4K pages), Image16k.gz (16K pages), initrd.gz, asoft-setup.json
# -> app/src/main/assets/helper/
# Needs: aarch64-linux-gnu-gcc make bc flex bison libssl-dev libelf-dev cpio gzip xz curl
#        qemu-user-static + binfmt (to run apk inside the aarch64 rootfs).
# Version pins below are starting points: verify they exist (apk search / kernel.org).
set -euo pipefail
cd "$(dirname "$0")/.."
LINUX_VER="${LINUX_VER:-6.12.30}"
ALPINE_BRANCH="${ALPINE_BRANCH:-v3.22}"; ALPINE_REL="${ALPINE_REL:-3.22.0}"
WORK="${WORK:-$PWD/out/helper}"; ASSETS="${ASSETS:-$PWD/app/src/main/assets/helper}"
mkdir -p "$WORK" "$ASSETS"

# ---------------------------------------------------------------- kernels
fetch_linux() {
  [ -d "$WORK/linux-$LINUX_VER" ] && return
  curl -fL "https://cdn.kernel.org/pub/linux/kernel/v6.x/linux-$LINUX_VER.tar.xz" | tar -xJ -C "$WORK"
}
build_kernel() {   # $1 = 4k|16k   $2 = output file
  local pages="$1" out="$2" src="$WORK/linux-$LINUX_VER" bld="$WORK/build-$1"
  mkdir -p "$bld"
  make -C "$src" O="$bld" ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- defconfig
  local cfg="$src/scripts/config --file $bld/.config"
  $cfg -e DEVTMPFS -e DEVTMPFS_MOUNT -e BLK_DEV_INITRD -e VIRTIO_BLK -e VIRTIO_PCI -e VIRTIO_NET \
       -e EXT4_FS -e USB -e USB_EHCI_HCD -e USB_EHCI_PCI -e SERIAL_AMBA_PL011 -e SERIAL_AMBA_PL011_CONSOLE \
       -e TMPFS -e PROC_FS -e SYSFS
  if [ "$pages" = 16k ]; then $cfg -d ARM64_4K_PAGES -e ARM64_16K_PAGES; fi
  make -C "$src" O="$bld" ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- olddefconfig
  make -C "$src" O="$bld" ARCH=arm64 CROSS_COMPILE=aarch64-linux-gnu- -j"$(nproc)" Image
  gzip -9 < "$bld/arch/arm64/boot/Image" > "$out"
}
fetch_linux
build_kernel 4k  "$ASSETS/Image.gz"
build_kernel 16k "$ASSETS/Image16k.gz"

# ---------------------------------------------------------------- initrd (Alpine aarch64)
RFS="$WORK/rootfs"; rm -rf "$RFS"; mkdir -p "$RFS"
curl -fL "https://dl-cdn.alpinelinux.org/alpine/$ALPINE_BRANCH/releases/aarch64/alpine-minirootfs-$ALPINE_REL-aarch64.tar.gz" | tar -xz -C "$RFS"
cp /usr/bin/qemu-aarch64-static "$RFS/usr/bin/"
cp /etc/resolv.conf "$RFS/etc/resolv.conf"
mount --bind /proc "$RFS/proc" 2>/dev/null || true
chroot "$RFS" /sbin/apk add --no-cache python3 e2fsprogs util-linux usbutils usbmuxd \
    libimobiledevice libimobiledevice-progs idevicerestore tar iproute2 busybox-extras
# Tools that have no Alpine package: build inside the chroot, then drop the toolchain.
chroot "$RFS" /bin/sh -eux <<'CHROOT'
apk add --no-cache --virtual .build build-base git openssl-dev zlib-dev linux-headers
git clone --depth 1 --recursive https://github.com/xerub/img4lib /tmp/img4lib
make -C /tmp/img4lib && install -m755 /tmp/img4lib/img4 /usr/local/bin/img4
git clone --depth 1 https://github.com/ChefKissInc/QEMUAppleSiliconTools /opt/qemu-tools
apk del .build; rm -rf /tmp/* /var/cache/apk/*
CHROOT
umount "$RFS/proc" 2>/dev/null || true
rm -f "$RFS/usr/bin/qemu-aarch64-static"
rm -rf "$RFS"/usr/share/man "$RFS"/usr/share/doc "$RFS"/usr/share/info

install -d "$RFS/opt/asoft/user" "$RFS/etc"
install -m755 helper/asoft_runner.py "$RFS/opt/asoft/asoft_runner.py"
install -m755 helper/user-scripts/*.sh "$RFS/opt/asoft/user/"
install -m644 helper/asoft-setup.json "$RFS/etc/asoft-setup.json"
install -m755 helper/init "$RFS/init"
( cd "$RFS" && find . | cpio -H newc -o --quiet | gzip -9 > "$ASSETS/initrd.gz" )
cp helper/asoft-setup.json "$ASSETS/asoft-setup.json"
ls -lh "$ASSETS"
