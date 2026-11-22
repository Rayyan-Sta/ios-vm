#!/usr/bin/env bash
# Copies the new files into a fork checkout and prints the (few) manual hook-ups.
set -euo pipefail
: "${QEMU_SRC:?path to the Apple-SoC QEMU fork}"
here="$(cd "$(dirname "$0")" && pwd)"
cp -v "$here/include/hw/arm/ios_vm_bridge.h"       "$QEMU_SRC/include/hw/arm/"
cp -v "$here/include/hw/input/apple_hwbuttons.h"   "$QEMU_SRC/include/hw/input/"
cp -v "$here/hw/arm/ios_vm_bridge.c"               "$QEMU_SRC/hw/arm/"
cp -v "$here/hw/input/apple_hwbuttons.c"           "$QEMU_SRC/hw/input/"
cp -v "$here/qapi/ios-vm.json"                     "$QEMU_SRC/qapi/"
cat <<'MSG'

Manual hook-ups (see qemu-patches/README.md):
  1. hw/arm/meson.build     : add files('ios_vm_bridge.c')            (arm_ss or system_ss)
     hw/input/meson.build   : add files('apple_hwbuttons.c')
  2. qapi/meson.build       : add 'ios-vm.json' to the qapi modules list
     qapi/qapi-schema.json  : add   { 'include': 'ios-vm.json' }
  3. hw/arm/t8030.c         : DRAM alloc -> ios_vm_bridge_init_dram(); boot-args video -> ios_vm_bridge_set_fb()
MSG
