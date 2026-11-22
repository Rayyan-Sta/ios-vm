package com.iosvm.emu.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iosvm.emu.GuestSurfaceView
import com.iosvm.emu.input.GuestInput
import com.iosvm.emu.setup.SetupPipeline
import com.iosvm.emu.vm.QemuService
import com.iosvm.emu.vm.VmController
import com.iosvm.emu.vm.VmState
import kotlin.concurrent.thread

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun VmScreen() {
    val ctx = LocalContext.current
    val state by VmController.state.collectAsStateWithLifecycle()
    val setup by SetupPipeline.progress.collectAsStateWithLifecycle()
    var touchLocked by remember { mutableStateOf(false) }
    var pinned by remember { mutableStateOf(false) }

    // Android back gesture -> iOS left-edge back swipe. (Leave the app via the menu / notification.)
    BackHandler(enabled = state is VmState.Running) { GuestInput.back() }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    val actions = listOf(
        MenuAction("Home") { GuestInput.home() },
        MenuAction("Side") { GuestInput.side() },
        MenuAction("Touch lock", touchLocked) { touchLocked = !touchLocked },
        MenuAction("Pin screen", pinned) {
            val act = ctx.findActivity() ?: return@MenuAction
            runCatching { if (pinned) act.stopLockTask() else act.startLockTask() }
            pinned = !pinned
        },
        MenuAction("Snapshot save") { thread { toast(VmController.snapshotSave()) }.let { } },
        MenuAction("Snapshot load") { thread { toast(VmController.snapshotLoad()) }.let { } },
        MenuAction("Save & close") { QemuService.send(ctx, QemuService.ACTION_SAVE_CLOSE) },
        MenuAction("Shut down") { VmController.cleanShutdown { QemuService.send(ctx, QemuService.ACTION_TURN_OFF) } },
    )

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { GuestSurfaceView(it) },
            update = { it.touchLocked = touchLocked },
            modifier = Modifier.fillMaxSize(),
        )
        val banner = when {
            state is VmState.Starting -> "Booting…"
            state is VmState.Saving -> "Saving…"
            state is VmState.Stopping -> "Shutting down…"
            state is VmState.Failed -> "Failed: ${(state as VmState.Failed).message}"
            state is VmState.Crashed -> "Stopped unexpectedly"
            setup.stage != "idle" && !setup.done -> "Setup: ${setup.stage} ${setup.detail}"
            else -> null
        }
        banner?.let {
            Text(it, color = Color(0xFF00FF88), modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.dp))
        }
        if (touchLocked) Text("Touch locked", color = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
        EdgeHandleMenu(actions)
    }
}
