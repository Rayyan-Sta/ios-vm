# QEMU fork patches (Phase 4)

Three small additions to the Apple-SoC QEMU fork. None change guest-visible behaviour.

| file | purpose |
|---|---|
| `ios_vm_bridge.{h,c}` + `qapi/ios-vm.json` | (a) DRAM as a *shared* mapping of the app's memfd (`IOSVM_RAM_FILE=/proc/<pid>/fd/<fd>`), so the renderer reads guest RAM with no copy and no flash write-back. (b) QMP `x-ios-vm-info` returning DRAM base/size + framebuffer geometry. |
| `apple_hwbuttons.{h,c}` | QKeyCode (`power`, `audiovolup`, `audiovoldown`) -> button IRQs. Skeleton: GPIO lines/polarity come from the device tree. |

## Hook-ups in the machine file (`hw/arm/t8030.c`, names vary by fork commit)

```c
/* 1. DRAM: was memory_region_init_ram(&s->dram, OBJECT(s), "dram", size, ...) */
ios_vm_bridge_init_dram(&s->dram, OBJECT(s), "dram", T8030_DRAM_BASE, size);
memory_region_add_subregion(sysmem, T8030_DRAM_BASE, &s->dram);

/* 2. Where the machine fills the XNU boot_args `video` block (base_addr, row_bytes, width, height): */
ios_vm_bridge_set_fb(video->base_addr, video->v_width, video->v_height, video->v_rowBytes,
                     /* B,G,R,X in memory */ true);
/* Call it again if the display driver reprograms the framebuffer later: the app polls the geometry. */

/* 3. Buttons: pass the three GPIO IRQs the DT's button nodes point at. */
apple_hwbuttons_create(power_irq, volup_irq, voldown_irq);
```

## Checks after rebuilding
```
qmp: {"execute":"x-ios-vm-info"}   -> dram-base/size set immediately; fb-valid flips true once iBoot/XNU draw
```
`dram-size` must be <= the memfd size the app created (`IosBootConfig.dramBytes`, default 4 GiB).

## Known follow-ups (not included)
* Frame doorbell / dirty-rect hook so the renderer can skip idle frames and avoid tearing.
* `VMStateDescription`s on the Apple devices (AIC, SEP mailbox, NVMe/ANS, ...) if you want live snapshots.
* A multitouch (`mtt`) consumer; until then the app runs single-touch via abs pointer.
