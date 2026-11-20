package com.example.vbantx

/** VBAN protocol constants (audio sub-protocol, PCM codec). */
object Vban {
    const val HEADER_SIZE = 28
    const val MAX_SAMPLES_PER_PACKET = 256
    const val MAX_PACKET_SIZE = 1436
    const val DEFAULT_PORT = 6980
    const val STREAM_NAME_LEN = 16

    /** Index in this array is the 5-bit sample-rate code in the header. */
    val SAMPLE_RATES = intArrayOf(
        6000, 12000, 24000, 48000, 96000, 192000, 384000,
        8000, 16000, 32000, 64000, 128000, 256000, 512000,
        11025, 22050, 44100, 88200, 176400, 352800, 705600,
    )

    private const val FORMAT_INT16 = 0x01

    fun sampleRateIndex(rate: Int): Int =
        SAMPLE_RATES.indexOf(rate).also { require(it >= 0) { "Unsupported VBAN sample rate: $rate" } }

    internal const val BIT_FORMAT_INT16 = FORMAT_INT16
}

/**
 * Builds VBAN audio packets carrying 16-bit little-endian PCM.
 *
 * Header layout (28 bytes, little-endian):
 *  0..3   "VBAN"
 *  4      sample-rate index (bits 0-4) | sub-protocol (bits 5-7, 0 = audio)
 *  5      samples per frame - 1
 *  6      channels - 1
 *  7      data format (bits 0-2) | codec (bits 4-7, 0 = PCM)
 *  8..23  stream name, zero padded
 *  24..27 frame counter
 */
class VbanPacketizer(streamName: String, val sampleRate: Int, val channels: Int) {
    init {
        require(channels in 1..256) { "channels out of range" }
    }

    private val header = ByteArray(Vban.HEADER_SIZE)
    private var counter = 0

    /** PCM frames (one sample for every channel) carried in each packet. */
    val framesPerPacket: Int =
        minOf(Vban.MAX_SAMPLES_PER_PACKET, (Vban.MAX_PACKET_SIZE - Vban.HEADER_SIZE) / (channels * 2))

    /** Bytes of PCM payload in each packet. */
    val payloadBytes: Int = framesPerPacket * channels * 2

    val packetSize: Int = Vban.HEADER_SIZE + payloadBytes

    init {
        header[0] = 'V'.code.toByte()
        header[1] = 'B'.code.toByte()
        header[2] = 'A'.code.toByte()
        header[3] = 'N'.code.toByte()
        header[4] = Vban.sampleRateIndex(sampleRate).toByte()
        header[5] = (framesPerPacket - 1).toByte()
        header[6] = (channels - 1).toByte()
        header[7] = Vban.BIT_FORMAT_INT16.toByte()
        val name = streamName.toByteArray(Charsets.US_ASCII).copyOf(Vban.STREAM_NAME_LEN)
        // copyOf pads with zeros; if the name was longer it is truncated to 16 bytes.
        System.arraycopy(name, 0, header, 8, Vban.STREAM_NAME_LEN)
    }

    /**
     * Writes one packet into [out] from [payloadBytes] bytes of PCM in [pcm] starting at [pcmOffset].
     * @return the packet length
     */
    fun build(pcm: ByteArray, pcmOffset: Int, out: ByteArray): Int {
        require(out.size >= packetSize) { "output buffer too small" }
        System.arraycopy(header, 0, out, 0, Vban.HEADER_SIZE)
        out[24] = counter.toByte()
        out[25] = (counter ushr 8).toByte()
        out[26] = (counter ushr 16).toByte()
        out[27] = (counter ushr 24).toByte()
        counter++
        System.arraycopy(pcm, pcmOffset, out, Vban.HEADER_SIZE, payloadBytes)
        return packetSize
    }
}
