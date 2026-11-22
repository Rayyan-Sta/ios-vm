package com.iosvm.emu

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView

/** Renders natively (EGL in C++); this view only owns the Surface and forwards touches. */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class GuestSurfaceView(ctx: Context) : SurfaceView(ctx), SurfaceHolder.Callback {

    /** Touch Lock: swallow all touches so a pocket/palm can't drive the guest. */
    @Volatile var touchLocked = false

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun surfaceCreated(h: SurfaceHolder) = NativeBridge.nativeSetSurface(h.surface)
    override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hh: Int) = Unit  // size is queried per frame
    override fun surfaceDestroyed(h: SurfaceHolder) {
        NativeBridge.nativeTouchCancelAll()
        NativeBridge.nativeSetSurface(null)   // blocks until the render thread released the window
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (touchLocked) return true
        val vw = width.toFloat(); val vh = height.toFloat()
        fun send(phase: Int, i: Int) =
            NativeBridge.nativeTouch(phase, ev.getPointerId(i), ev.getX(i), ev.getY(i), vw, vh)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> send(NativeBridge.DOWN, ev.actionIndex)
            MotionEvent.ACTION_MOVE -> for (i in 0 until ev.pointerCount) send(NativeBridge.MOVE, i)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> send(NativeBridge.UP, ev.actionIndex)
            MotionEvent.ACTION_CANCEL -> NativeBridge.nativeTouchCancelAll()
        }
        return true
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) NativeBridge.nativeTouchCancelAll()
    }
}
