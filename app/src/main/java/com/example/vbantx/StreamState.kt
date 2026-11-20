package com.example.vbantx

import kotlinx.coroutines.flow.MutableStateFlow

/** MEDIA = music/video (default); ALL_DEVICE = every capturable playback usage; MIC = microphone. */
enum class Source(val usesProjection: Boolean) {
    MEDIA(true), ALL_DEVICE(true), MIC(false);

    companion object {
        fun parse(name: String?, fallback: Source): Source = when (name) {
            "DEVICE_AUDIO" -> ALL_DEVICE // value stored by an earlier version
            else -> entries.firstOrNull { it.name == name } ?: fallback
        }
    }
}

data class StreamConfig(
    val host: String,
    val port: Int,
    val streamName: String,
    val source: Source,
    val sampleRate: Int,
    val channels: Int,
    val gain: Float,
)

data class StreamStatus(
    val running: Boolean = false,
    val packets: Long = 0,
    val errors: Long = 0,
    val level: Float = 0f,
    val message: String? = null,
)

/** Process-wide status shared between the service and the UI. */
object StreamState {
    val status = MutableStateFlow(StreamStatus())
}
