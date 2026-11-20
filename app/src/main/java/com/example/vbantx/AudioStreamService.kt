package com.example.vbantx

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.math.abs
import kotlin.math.max

/**
 * Foreground service that captures audio (microphone or device playback) and
 * sends it to a VBAN receiver as UDP packets.
 */
class AudioStreamService : Service() {

    @Volatile private var running = false
    private var worker: Thread? = null
    private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            else -> { stopStreaming(); stopSelf() }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }

    private fun start(intent: Intent) {
        stopStreaming()

        val config = StreamConfig(
            host = intent.getStringExtra(EXTRA_HOST).orEmpty(),
            port = intent.getIntExtra(EXTRA_PORT, Vban.DEFAULT_PORT),
            streamName = intent.getStringExtra(EXTRA_STREAM_NAME) ?: "Stream1",
            source = Source.parse(intent.getStringExtra(EXTRA_SOURCE), Source.MEDIA),
            sampleRate = intent.getIntExtra(EXTRA_SAMPLE_RATE, 48000),
            channels = intent.getIntExtra(EXTRA_CHANNELS, 2),
            gain = intent.getFloatExtra(EXTRA_GAIN, 1f),
        )

        // The foreground type must match what we are about to do, and a
        // mediaProjection service has to be promoted before the projection is used.
        val type = if (config.source.usesProjection)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        else
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(config), type)
        } catch (e: Exception) {
            fail("Cannot start foreground service: ${e.message}")
            return
        }

        if (config.source.usesProjection) {
            val resultCode = intent.getIntExtra(EXTRA_PROJECTION_RESULT, 0)
            val data = IntentCompat.getParcelableExtra(intent, EXTRA_PROJECTION_DATA, Intent::class.java)
            if (data == null) { fail("Screen-capture permission missing"); return }
            val mgr = getSystemService(MediaProjectionManager::class.java)
            val p = mgr.getMediaProjection(resultCode, data)
            if (p == null) { fail("Could not start media projection"); return }
            // Android 14+ requires a callback to be registered before capture starts.
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopStreaming(); stopSelf() }
            }, null)
            projection = p
        }

        running = true
        StreamState.status.value = StreamStatus(running = true)
        worker = Thread({ streamLoop(config) }, "vban-tx").also { it.start() }
    }

    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested by MainActivity before starting.
    private fun streamLoop(config: StreamConfig) {
        var record: AudioRecord? = null
        var socket: DatagramSocket? = null
        var message: String? = null
        try {
            val packetizer = VbanPacketizer(config.streamName, config.sampleRate, config.channels)
            val address = InetAddress.getByName(config.host)
            socket = DatagramSocket()
            record = buildRecord(config, packetizer.payloadBytes)
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("Audio capture could not be initialised")
            }

            val pcm = ByteArray(packetizer.payloadBytes)
            val out = ByteArray(packetizer.packetSize)
            val datagram = DatagramPacket(out, packetizer.packetSize, address, config.port)
            var packets = 0L
            var errors = 0L
            var peak = 0

            record.startRecording()
            while (running) {
                var got = 0
                while (got < pcm.size && running) {
                    val n = record.read(pcm, got, pcm.size - got)
                    if (n < 0) throw IllegalStateException("Audio read failed (code $n)")
                    got += n
                }
                if (got < pcm.size) break

                peak = max(peak, applyGain(pcm, config.gain))
                packetizer.build(pcm, 0, out)
                try {
                    socket.send(datagram)
                    packets++
                } catch (e: java.io.IOException) {
                    errors++ // e.g. network briefly unreachable; keep capturing
                }

                if ((packets + errors) % STATUS_EVERY == 0L) {
                    StreamState.status.value =
                        StreamStatus(true, packets, errors, peak / 32768f)
                    peak = 0
                }
            }
        } catch (e: Exception) {
            message = e.message ?: e.javaClass.simpleName
        } finally {
            try { record?.stop() } catch (_: IllegalStateException) {}
            record?.release()
            socket?.close()
            running = false
            StreamState.status.value = StreamStatus(running = false, message = message)
            projection?.stop()
            projection = null
            stopSelf()
        }
    }

    @SuppressLint("MissingPermission")
    private fun buildRecord(config: StreamConfig, payloadBytes: Int): AudioRecord {
        val mask = if (config.channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(config.sampleRate)
            .setChannelMask(mask)
            .build()
        val min = AudioRecord.getMinBufferSize(config.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val builder = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(max(min, payloadBytes) * 2)
        if (config.source.usesProjection) {
            check(Build.VERSION.SDK_INT >= 29) { "Device audio capture needs Android 10+" }
            // Android only lets apps capture these three usages; to duplicate "whatever is
            // playing" we take all of them. Media-only is the default.
            val usages = if (config.source == Source.MEDIA)
                listOf(AudioAttributes.USAGE_MEDIA)
            else
                listOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN)
            val capture = AudioPlaybackCaptureConfiguration.Builder(projection!!).apply {
                usages.forEach { addMatchingUsage(it) }
            }.build()
            builder.setAudioPlaybackCaptureConfig(capture)
        } else {
            builder.setAudioSource(MediaRecorder.AudioSource.MIC)
        }
        return builder.build()
    }

    /** Scales 16-bit LE samples in place and returns the peak absolute value seen. */
    private fun applyGain(buf: ByteArray, gain: Float): Int {
        var peak = 0
        var i = 0
        while (i + 1 < buf.size) {
            var s = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF))
            if (gain != 1f) {
                s = (s * gain).toInt().coerceIn(-32768, 32767)
                buf[i] = s.toByte()
                buf[i + 1] = (s shr 8).toByte()
            }
            val a = abs(s)
            if (a > peak) peak = a
            i += 2
        }
        return peak
    }

    private fun stopStreaming() {
        running = false
        val t = worker
        if (t != null && t !== Thread.currentThread()) {
            try { t.join(1500) } catch (_: InterruptedException) {}
        }
        worker = null
    }

    private fun fail(msg: String) {
        StreamState.status.value = StreamStatus(running = false, message = msg)
        projection?.stop()
        projection = null
        stopSelf()
    }

    private fun buildNotification(config: StreamConfig): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "VBAN streaming", NotificationManager.IMPORTANCE_LOW)
        )
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, AudioStreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Streaming audio via VBAN")
            .setContentText("${config.streamName} → ${config.host}:${config.port}")
            .setOngoing(true)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        const val ACTION_START = "com.example.vbantx.START"
        const val ACTION_STOP = "com.example.vbantx.STOP"
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_STREAM_NAME = "stream"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_SAMPLE_RATE = "rate"
        const val EXTRA_CHANNELS = "channels"
        const val EXTRA_GAIN = "gain"
        const val EXTRA_PROJECTION_RESULT = "proj_result"
        const val EXTRA_PROJECTION_DATA = "proj_data"
        private const val CHANNEL_ID = "vban_stream"
        private const val NOTIFICATION_ID = 1
        private const val STATUS_EVERY = 8L
    }
}
