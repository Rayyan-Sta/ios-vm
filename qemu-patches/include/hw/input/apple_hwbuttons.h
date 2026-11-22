#ifndef HW_INPUT_APPLE_HWBUTTONS_H
#define HW_INPUT_APPLE_HWBUTTONS_H

#include "hw/irq.h"

/*
 * Maps host QKeyCodes to the SoC's button inputs:
 *   power -> side button, audiovolup/audiovoldown -> volume buttons.
 * Wire the three IRQs to the GPIO/PMU lines the device tree names for the buttons.
 */
void apple_hwbuttons_create(qemu_irq power, qemu_irq vol_up, qemu_irq vol_down);

#endif
