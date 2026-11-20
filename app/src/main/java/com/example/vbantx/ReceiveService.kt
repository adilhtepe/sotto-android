package com.example.vbantx

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow
import vban.sdk.VbanNetworkLocks
import vban.sdk.VbanReceiver

data class ReceiveStatus(
    val running: Boolean = false,
    val packets: Long = 0,
    val lost: Long = 0,
    val repaired: Long = 0,
    val underruns: Long = 0,
    val latencyMs: Float = 0f,
    val streamRate: Long = 0,
    val message: String? = null,
)

/** Process-wide receive status shared between the service and the UI. */
object ReceiveState {
    val status = MutableStateFlow(ReceiveStatus())
}

/**
 * Foreground service that plays a VBAN stream: the Sotto SDK's jitter buffer (adaptive, clock-drift
 * resampling, loss concealment) feeds a low-latency AudioTrack. The phone answers VBAN pings, so a
 * hub or Voicemeeter can find it.
 */
class ReceiveService : Service() {

    @Volatile private var running = false
    private var player: Thread? = null
    private var rx: VbanReceiver? = null
    private var locks: VbanNetworkLocks? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            else -> { stopReceiving(); stopSelf() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopReceiving()
        super.onDestroy()
    }

    private fun start(intent: Intent) {
        stopReceiving()
        val stream = intent.getStringExtra(EXTRA_STREAM).orEmpty()     // "" = any stream name
        val port = intent.getIntExtra(EXTRA_PORT, Vban.DEFAULT_PORT)
        val targetMs = intent.getIntExtra(EXTRA_BUFFER_MS, 20)

        try {
            val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(stream, port), type)
        } catch (e: Exception) {
            fail("Cannot start foreground service: ${e.message}")
            return
        }

        val am = getSystemService(AudioManager::class.java)
        val rate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
        val burst = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull()?.takeIf { it > 0 } ?: 256

        val receiver = try {
            VbanReceiver(stream, port = port, channels = 2, outputRate = rate, targetMs = targetMs)
                .setIdentity(Build.MODEL ?: "Android", "Sotto (Android)", preferredRate = rate)
        } catch (e: Exception) {
            fail("Cannot listen on UDP port $port: ${e.message}")
            return
        }
        rx = receiver
        locks = VbanNetworkLocks(this, "sotto-rx").acquire()

        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, burst * 4 * 2))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        running = true
        receiver.start { e -> fail("Receive failed: ${e.message}") }
        player = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ShortArray(burst * 2)
            var nextStatus = 0L
            try {
                track.play()
                while (running) {
                    receiver.read(buf, burst)                 // always fills: audio, concealment or silence
                    track.write(buf, 0, buf.size)             // blocks at the device's pace
                    val now = System.nanoTime()
                    if (now >= nextStatus) {
                        val s = receiver.stats()
                        ReceiveState.status.value = ReceiveStatus(
                            running = true, packets = s.packets, lost = s.lost, repaired = s.repaired,
                            underruns = s.underruns, latencyMs = s.latencyMicros / 1000f, streamRate = s.streamRate,
                        )
                        nextStatus = now + 500_000_000L
                    }
                }
            } catch (e: Exception) {
                fail("Playback failed: ${e.message}")
            } finally {
                runCatching { track.stop() }
                track.release()
            }
        }, "sotto-play").also { it.start() }
        ReceiveState.status.value = ReceiveStatus(running = true)
    }

    private fun stopReceiving() {
        running = false
        player?.join(1000)
        player = null
        rx?.close()
        rx = null
        locks?.close()
        locks = null
        val last = ReceiveState.status.value
        ReceiveState.status.value = last.copy(running = false)
    }

    private fun fail(message: String) {
        ReceiveState.status.value = ReceiveState.status.value.copy(running = false, message = message)
        running = false
        stopSelf()
    }

    private fun buildNotification(stream: String, port: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "VBAN receiving", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ReceiveService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Receiving VBAN")
            .setContentText("${stream.ifEmpty { "any stream" }} on UDP $port")
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stop)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.example.vbantx.RX_START"
        const val ACTION_STOP = "com.example.vbantx.RX_STOP"
        const val EXTRA_STREAM = "stream"
        const val EXTRA_PORT = "port"
        const val EXTRA_BUFFER_MS = "buffer_ms"
        private const val CHANNEL_ID = "vban_rx"
        private const val NOTIFICATION_ID = 2
    }
}
