package app.olauncher.features.space.platform

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
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.olauncher.R
import app.olauncher.core.waypoint
import app.olauncher.features.assistant.ui.ChatActivity
import kotlinx.coroutines.launch
import java.io.File

/**
 * Keeps a Space recording alive with the screen off (foreground service, microphone type) and shows a
 * notification with Stop. When it stops, the note is saved and transcription starts.
 */
class RecordingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            else -> startRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        channel()
        val open = PendingIntent.getActivity(this, 1, ChatActivity.intent(this, space = true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 2, Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_wayfinder)
            .setContentTitle(getString(R.string.wp_space_recording))
            .setContentText(getString(R.string.wp_space_recording_text))
            .setOngoing(true)
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis())
            .setContentIntent(open)
            .addAction(0, getString(R.string.wp_space_stop), stop)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type) }
            .onFailure { stopSelf(); return }
        val file = File(File(filesDir, "space").apply { mkdirs() }, "note-${System.currentTimeMillis()}.wav")
        if (!Recorder.start(file)) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopRecording() {
        val result = Recorder.stop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        if (result != null) {
            val container = applicationContext.waypoint
            container.scope.launch { container.space.saveRecording(result.first, result.second) }
        }
    }

    override fun onDestroy() {
        // the process may be going away: don't lose a recording in progress
        if (Recorder.state.value.recording) stopRecording()
        super.onDestroy()
    }

    private fun channel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.wp_space), NotificationManager.IMPORTANCE_LOW))
        }
    }

    companion object {
        private const val CHANNEL = "space_recording"
        private const val NOTIFICATION_ID = 4401
        private const val ACTION_STOP = "app.waypoint.space.STOP"

        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java))
        fun stop(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).setAction(ACTION_STOP))
        }
    }
}
