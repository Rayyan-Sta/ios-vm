/*
 * iosvm bridge: lets the host app (a) share guest DRAM zero-copy and (b) discover the guest
 * framebuffer geometry without screen-scraping or VNC.
 *
 * NOTE: QEMU >= 10 moved these headers: use "system/memory.h" / "system/hwaddr.h".
 */
#ifndef HW_ARM_IOS_VM_BRIDGE_H
#define HW_ARM_IOS_VM_BRIDGE_H

#include "exec/hwaddr.h"
#include "exec/memory.h"

/*
 * Initialise guest DRAM. If $IOSVM_RAM_FILE is set (the app passes /proc/<pid>/fd/<memfd>), DRAM is
 * a shared mapping of it, so the app can mmap the same pages read-only. Otherwise plain anonymous RAM.
 * The caller still maps @mr into the system address space at @base.
 */
void ios_vm_bridge_init_dram(MemoryRegion *mr, Object *owner, const char *name,
                             hwaddr base, uint64_t size);

/* Call whenever the guest-visible framebuffer (boot-args video info / display driver) changes. */
void ios_vm_bridge_set_fb(hwaddr base, uint32_t width, uint32_t height,
                          uint32_t stride_bytes, bool bgra);

#endif
