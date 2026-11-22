package com.iosvm.emu

object NativeBridge {
    init { System.loadLibrary("iosnative") }

    const val DOWN = 0; const val MOVE = 1; const val UP = 2; const val CANCEL = 3          // touch phases
    const val FIT = 0; const val FILL = 1; const val STRETCH = 2                            // scale modes
    const val BGRA8888 = 0; const val RGBA8888 = 1                                          // pixel formats
    const val TOUCH_ABS = 0; const val TOUCH_MTT = 1                                        // touch modes

    external fun nativeDetectCpu(engineAvailable: Boolean): String
    external fun nativeRamCreate(sizeBytes: Long): Int                 // returns fd (ASharedMemory), or -1
    external fun nativeRamAttach(fd: Int, ramBaseGpa: Long, sizeBytes: Long): Boolean
    external fun nativeRamOpen(path: String, ramBaseGpa: Long, sizeBytes: Long): Boolean
    external fun nativeRamClose()
    external fun nativeSetFramebuffer(gpa: Long, width: Int, height: Int, strideBytes: Int, format: Int)
    external fun nativeSetScaleMode(mode: Int)
    external fun nativeSetSurface(surface: android.view.Surface?)
    external fun nativeRendererStart()
    external fun nativeRendererStop()
    external fun nativeGetStats(): String
    external fun nativeQmpConnect(path: String, timeoutMs: Int): Boolean
    external fun nativeQmpDisconnect()
    external fun nativeQmpConnected(): Boolean
    external fun nativeQmpExecute(cmd: String, argsJson: String, timeoutMs: Int): String?
    external fun nativeSetTouchMode(mode: Int)
    external fun nativeTouch(phase: Int, pointerId: Int, x: Float, y: Float, viewW: Float, viewH: Float): Boolean
    external fun nativeTouchGuest(phase: Int, pointerId: Int, nx: Float, ny: Float): Boolean
    external fun nativeTouchCancelAll()
    external fun nativeKey(qcode: String, down: Boolean): Boolean
}
