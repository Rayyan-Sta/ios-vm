#include "qemu/osdep.h"
#include "qapi/error.h"
#include "qapi/qapi-commands-ios-vm.h"
#include "hw/arm/ios_vm_bridge.h"

static struct {
    hwaddr dram_base;
    uint64_t dram_size;
    bool fb_valid;
    hwaddr fb_base;
    uint32_t fb_w, fb_h, fb_stride;
    bool fb_bgra;
} g_ios;

void ios_vm_bridge_init_dram(MemoryRegion *mr, Object *owner, const char *name,
                             hwaddr base, uint64_t size)
{
    const char *path = getenv("IOSVM_RAM_FILE");

    g_ios.dram_base = base;
    g_ios.dram_size = size;

    if (path && *path) {
        Error *err = NULL;
        /*
         * Signature differs across QEMU versions (offset arg added ~7.x, bool return ~9.x):
         * adjust to your fork's base. The backing fd must already be >= size bytes.
         */
        memory_region_init_ram_from_file(mr, owner, name, size, 0, RAM_SHARED,
                                         path, 0, &err);
        if (err) {
            error_report_err(err);
            exit(1);
        }
        return;
    }
    memory_region_init_ram(mr, owner, name, size, &error_fatal);
}

void ios_vm_bridge_set_fb(hwaddr base, uint32_t width, uint32_t height,
                          uint32_t stride_bytes, bool bgra)
{
    g_ios.fb_base = base;
    g_ios.fb_w = width;
    g_ios.fb_h = height;
    g_ios.fb_stride = stride_bytes;
    g_ios.fb_bgra = bgra;
    g_ios.fb_valid = width && height && stride_bytes >= width * 4;
}

IosVmInfo *qmp_x_ios_vm_info(Error **errp)
{
    IosVmInfo *i = g_new0(IosVmInfo, 1);

    i->dram_base = g_ios.dram_base;
    i->dram_size = g_ios.dram_size;
    i->fb_valid = g_ios.fb_valid;
    i->fb_base = g_ios.fb_base;
    i->fb_width = g_ios.fb_w;
    i->fb_height = g_ios.fb_h;
    i->fb_stride = g_ios.fb_stride;
    i->fb_format = g_strdup(g_ios.fb_bgra ? "bgra8888" : "rgba8888");
    return i;
}
