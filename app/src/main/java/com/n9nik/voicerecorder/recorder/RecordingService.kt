package com.n9nik.voicerecorder.recorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.n9nik.voicerecorder.MainActivity
import com.n9nik.voicerecorder.domain.RecordingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service hosting the microphone recording session so recording
 * survives the app going to background / screen off.
 *
 * Android 14 hard-crashes if a microphone foreground service is missing
 * android:foregroundServiceType="microphone" in the manifest AND the
 * FOREGROUND_SERVICE_MICROPHONE permission — both are declared.
 */
class RecordingService : Service() {

    companion object {
        const val ACTION_START = "com.n9nik.voicerecorder.action.START"
        const val ACTION_PAUSE = "com.n9nik.voicerecorder.action.PAUSE"
        const val ACTION_RESUME = "com.n9nik.voicerecorder.action.RESUME"
        const val ACTION_STOP = "com.n9nik.voicerecorder.action.STOP"

        private const val CHANNEL_ID = "tinyvoice_recording"
        private const val NOTIF_ID = 1001

        fun startAction(context: Context, action: String) {
            val intent = Intent(context, RecordingService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_PAUSE -> {
                VoiceRecorder.pause()
                updateNotification()
            }
            ACTION_RESUME -> {
                VoiceRecorder.resume()
                updateNotification()
            }
            ACTION_STOP -> handleStop()
        }
        return START_NOT_STICKY
    }

    private fun handleStart() {
        createChannel()
        if (!VoiceRecorder.start(this)) {
            stopSelf()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
    }

    private fun handleStop() {
        scope.launch {
            val file = VoiceRecorder.stop()
            if (file != null) {
                try {
                    val uri = RecordingRepository.saveRecording(this@RecordingService, file)
                    if (uri != null) VoiceRecorder.markSaved()
                } catch (_: Exception) {
                }
                file.delete()
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val paused = VoiceRecorder.state.value == VoiceRecorder.State.PAUSED
        val toggleAction = if (paused) ACTION_RESUME else ACTION_PAUSE
        val toggleTitle = if (paused) "Resume" else "Pause"
        val toggleIntent = PendingIntent.getService(
            this, 1,
            Intent(this, RecordingService::class.java).setAction(toggleAction),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (paused) "Recording paused" else "Recording…")
            .setContentText("TinyVoice is capturing audio")
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, toggleTitle, toggleIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Recording",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
