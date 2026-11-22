package com.iosvm.emu.input

import com.iosvm.emu.NativeBridge
import java.util.concurrent.Executors

/**
 * Synthetic iOS hardware/gesture input on top of the QMP bridge. iPhone 11 has no Home button, so
 * "Home" is the swipe-up gesture; "Back" is the left-edge swipe. All coordinates are normalized
 * guest-screen coordinates (0..1), independent of how the guest is letterboxed on the host.
 */
object GuestInput {
    /** QKeyCode names the fork must map to the SoC's button GPIOs (see qemu-patches apple_hwbuttons.c). */
    object Keys { const val POWER = "power"; const val VOL_UP = "audiovolup"; const val VOL_DOWN = "audiovoldown" }

    /** Heuristic "slide to power off" slider geometry on iPhone 11 portrait. Tune per iOS version. */
    object PowerOffUi { const val KNOB_X = 0.17f; const val KNOB_Y = 0.185f; const val END_X = 0.88f }

    private const val SYNTH_POINTER = 9      // slot 9: never collides with real fingers 0..8
    private val exec = Executors.newSingleThreadExecutor { Thread(it, "guest-input").apply { isDaemon = true } }
    @Volatile private var guestAsleep = false

    fun post(block: () -> Unit) { exec.execute(block) }

    // ---- blocking primitives (call from a worker thread) ----
    fun keyNow(qcode: String, holdMs: Long = 90) {
        NativeBridge.nativeKey(qcode, true); Thread.sleep(holdMs); NativeBridge.nativeKey(qcode, false)
    }

    fun swipeNow(x0: Float, y0: Float, x1: Float, y1: Float, durMs: Long = 180, steps: Int = 14) {
        NativeBridge.nativeTouchGuest(NativeBridge.DOWN, SYNTH_POINTER, x0, y0)
        for (i in 1..steps) {
            Thread.sleep(durMs / steps)
            val t = i / steps.toFloat()
            NativeBridge.nativeTouchGuest(NativeBridge.MOVE, SYNTH_POINTER, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)
        }
        NativeBridge.nativeTouchGuest(NativeBridge.UP, SYNTH_POINTER, x1, y1)
    }

    /** Side + Volume Up held: iOS shows "slide to power off"; then drag the slider. */
    fun powerOffSequenceNow() {
        NativeBridge.nativeKey(Keys.POWER, true); NativeBridge.nativeKey(Keys.VOL_UP, true)
        Thread.sleep(3200)
        NativeBridge.nativeKey(Keys.VOL_UP, false); NativeBridge.nativeKey(Keys.POWER, false)
        Thread.sleep(1500)
        swipeNow(PowerOffUi.KNOB_X, PowerOffUi.KNOB_Y, PowerOffUi.END_X, PowerOffUi.KNOB_Y, 600, 20)
    }

    // ---- async actions (UI thread safe) ----
    fun home() = post { swipeNow(0.5f, 0.995f, 0.5f, 0.55f, 160) }
    fun back() = post { swipeNow(0.003f, 0.5f, 0.42f, 0.5f, 160) }
    fun side() = post { keyNow(Keys.POWER) }
    fun hwKey(qcode: String, down: Boolean) = post { NativeBridge.nativeKey(qcode, down) }

    // Android screen off/on -> iOS lock/wake
    fun onHostScreenOff() = post {
        if (NativeBridge.nativeQmpConnected() && !guestAsleep) { keyNow(Keys.POWER); guestAsleep = true }
    }
    fun onHostScreenOn() = post {
        if (NativeBridge.nativeQmpConnected() && guestAsleep) { keyNow(Keys.POWER); guestAsleep = false }
    }
}
