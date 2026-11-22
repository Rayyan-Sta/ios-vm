package com.iosvm.emu.vm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.iosvm.emu.MainActivity
import com.iosvm.emu.R
import com.iosvm.emu.input.GuestInput
import com.iosvm.emu.setup.SetupPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

/**
 * Foreground service that owns the QEMU child process(es). While it is in the foreground state the
 * Low Memory Killer treats the app as visible-priority, so the multi-GB guest survives backgrounding.
 */
class QemuService : Service() {

    companion object {
        const val ACTION_START = "com.iosvm.emu.START"
        const val ACTION_SAVE_CLOSE = "com.iosvm.emu.SAVE_CLOSE"
        const val ACTION_TURN_OFF = "com.iosvm.emu.TURN_OFF"
        const val ACTION_SETUP = "com.iosvm.emu.SETUP"
        const val EXTRA_RESUME = "resume"
        private const val CHANNEL = "vm"
        private const val NOTIF_ID = 1

        fun send(ctx: Context, action: String, resume: Boolean = false) {
            val i = Intent(ctx, QemuService::class.java).setAction(action).putExtra(EXTRA_RESUME, resume)
            ContextCompat.startForegroundService(ctx, i)
        }
    }

    private var wake: PowerManager.WakeLock? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> GuestInput.onHostScreenOff()   // -> iOS lock
                Intent.ACTION_SCREEN_ON -> GuestInput.onHostScreenOn()     // -> iOS wake
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Virtual machine", NotificationManager.IMPORTANCE_LOW))
        ContextCompat.registerReceiver(
            this, screenReceiver,
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) },
            ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch { VmController.state.collect { notifyState(it) } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                goForeground("Starting iOS…")
                VmController.start(this, IosBootConfig(), intent.getBooleanExtra(EXTRA_RESUME, false))
            }
            ACTION_SETUP -> {
                goForeground("Setting up iOS image…")
                thread(name = "setup", isDaemon = true) { SetupPipeline.run(applicationContext); stopSelf() }
            }
            ACTION_SAVE_CLOSE -> VmController.saveAndClose { stopSelf() }
            ACTION_TURN_OFF -> VmController.turnOff { stopSelf() }
            else -> stopSelf()
        }
        return START_NOT_STICKY    // if the process dies the VM is gone too: nothing to resurrect
    }

    private fun goForeground(text: String) {
        if (wake == null) {
            wake = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "iosvm:vm").apply { acquire() }
        }
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    private fun action(label: String, action: String, req: Int) = NotificationCompat.Action.Builder(
        0, label,
        PendingIntent.getService(this, req, Intent(this, QemuService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_vm)
            .setContentTitle("iOS VM")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE))
            .addAction(action("Save & Close", ACTION_SAVE_CLOSE, 1))
            .addAction(action("Turn Off", ACTION_TURN_OFF, 2))
            .build()

    private fun notifyState(s: VmState) {
        val text = when (s) {
            VmState.Idle -> return
            VmState.Starting -> "Booting…"
            VmState.Running -> "Running"
            VmState.Saving -> "Saving…"
            VmState.Stopping -> "Shutting down…"
            is VmState.Crashed -> "Stopped unexpectedly (exit ${s.exitCode})"
            is VmState.Failed -> "Failed: ${s.message}"
        }
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        if (s is VmState.Crashed || s is VmState.Failed) stopSelf()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        wake?.takeIf { it.isHeld }?.release()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
