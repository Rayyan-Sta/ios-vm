/*
 * SKELETON: the QKeyCode -> IRQ plumbing is complete; the GPIO line numbers and polarity are
 * SoC/firmware specific and must be taken from the device tree's button nodes (and the fork's
 * GPIO/PMU model) for your target.
 */
#include "qemu/osdep.h"
#include "hw/input/apple_hwbuttons.h"
#include "hw/qdev-core.h"
#include "qapi/qapi-types-ui.h"
#include "ui/input.h"

typedef struct AppleHwButtons {
    qemu_irq power, vol_up, vol_down;
} AppleHwButtons;

static AppleHwButtons g_btn;

/* TODO(polarity): buttons are usually active-low on the GPIO; invert here if the DT says so. */
static void apple_btn_event(DeviceState *dev, QemuConsole *src, InputEvent *evt)
{
    InputKeyEvent *key = evt->u.key.data;
    int qcode = qemu_input_key_value_to_qcode(key->key);
    int level = key->down ? 1 : 0;

    switch (qcode) {
    case Q_KEY_CODE_POWER:        qemu_set_irq(g_btn.power, level);    break;
    case Q_KEY_CODE_AUDIOVOLUP:   qemu_set_irq(g_btn.vol_up, level);   break;
    case Q_KEY_CODE_AUDIOVOLDOWN: qemu_set_irq(g_btn.vol_down, level); break;
    default: break;
    }
}

static const QemuInputHandler apple_btn_handler = {
    .name  = "apple-hwbuttons",
    .mask  = INPUT_EVENT_MASK_KEY,
    .event = apple_btn_event,
};

void apple_hwbuttons_create(qemu_irq power, qemu_irq vol_up, qemu_irq vol_down)
{
    g_btn = (AppleHwButtons){ power, vol_up, vol_down };
    /* dev may be NULL: it is only used to bind the handler to a named qdev (input-linux style). */
    qemu_input_handler_register(NULL, &apple_btn_handler);
}
