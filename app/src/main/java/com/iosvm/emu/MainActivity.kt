package com.iosvm.emu

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.iosvm.emu.input.GuestInput
import com.iosvm.emu.setup.SetupPipeline
import com.iosvm.emu.ui.SetupScreen
import com.iosvm.emu.ui.VmScreen
import com.iosvm.emu.vm.DiskLayout
import com.iosvm.emu.vm.QemuService
import com.iosvm.emu.vm.VmController
import com.iosvm.emu.vm.VmPaths
import com.iosvm.emu.vm.VmState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        NativeBridge.nativeSetScaleMode(NativeBridge.FIT)
        NativeBridge.nativeRendererStart()       // idempotent; idles until a Surface arrives

        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Surface(Modifier.fillMaxSize()) { AppRoot() } } }
    }

    /** Hardware volume keys -> iOS volume buttons (held key = held button; Android auto-repeat swallowed). */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val q = when (e.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> GuestInput.Keys.VOL_UP
            KeyEvent.KEYCODE_VOLUME_DOWN -> GuestInput.Keys.VOL_DOWN
            else -> return super.dispatchKeyEvent(e)
        }
        if (VmController.state.value !is VmState.Running) return super.dispatchKeyEvent(e)
        when (e.action) {
            KeyEvent.ACTION_DOWN -> if (e.repeatCount == 0) GuestInput.hwKey(q, true)
            KeyEvent.ACTION_UP -> GuestInput.hwKey(q, false)
        }
        return true
    }
}

@Composable
private fun AppRoot() {
    val ctx = LocalContext.current
    val vm by VmController.state.collectAsStateWithLifecycle()
    val setup by SetupPipeline.progress.collectAsStateWithLifecycle()
    val paths = VmPaths(ctx)
    val vmActive = vm is VmState.Starting || vm is VmState.Running || vm is VmState.Saving || vm is VmState.Stopping
    when {
        vmActive -> VmScreen()
        paths.setupDone.exists() || setup.done -> LaunchScreen(paths)
        else -> SetupScreen()
    }
}

@Composable
private fun LaunchScreen(paths: VmPaths) {
    val ctx = LocalContext.current
    val vm by VmController.state.collectAsStateWithLifecycle()
    val hasSave = DiskLayout.hasCheckpoint(paths, VmController.QUICK_TAG)
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(CpuReport.detect().summary())
        when (val s = vm) {
            is VmState.Failed -> Text("Last start failed: ${s.message}")
            is VmState.Crashed -> Text("VM stopped unexpectedly (exit ${s.exitCode})")
            else -> {}
        }
        Button(onClick = { QemuService.send(ctx, QemuService.ACTION_START) }) { Text("Boot") }
        if (hasSave) Button(onClick = { QemuService.send(ctx, QemuService.ACTION_START, resume = true) }) { Text("Resume last save") }
    }
}
