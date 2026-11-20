package com.example.vbantx

/**
 * VBAN "service" sub-protocol (VBAN-PING) used to find receivers, plus a parser for
 * the packets we may see while discovering.
 */
object VbanService {
    private const val SUBPROTOCOL_MASK = 0xE0
    private const val SUBPROTOCOL_AUDIO = 0x00
    private const val SUBPROTOCOL_SERVICE = 0x60
    private const val PING_PAYLOAD_SIZE = 676
    private const val REPLY_FLAG = 0x80

    // Offsets inside the 676-byte PING0 payload.
    private const val OFF_BIT_TYPE = 0
    private const val OFF_APPLICATION = 292
    private const val OFF_HOST = 356
    private const val OFF_DEVICE = 164
    private const val FIELD_LEN = 64

    private const val TYPE_TRANSMITTER = 0x00000002

    /** A VBAN-PING0 request announcing ourselves as a transmitter. */
    fun buildPingRequest(): ByteArray {
        val p = ByteArray(Vban.HEADER_SIZE + PING_PAYLOAD_SIZE)
        "VBAN".toByteArray(Charsets.US_ASCII).copyInto(p)
        p[4] = SUBPROTOCOL_SERVICE.toByte() // service sub-protocol; function 0 = PING0, request
        "VBAN Service".toByteArray(Charsets.US_ASCII).copyInto(p, 8)
        val payload = Vban.HEADER_SIZE
        p[payload + OFF_BIT_TYPE] = TYPE_TRANSMITTER.toByte()
        putAscii(p, payload + OFF_APPLICATION, "VBAN Transmitter")
        putAscii(p, payload + OFF_DEVICE, "Android")
        return p
    }

    sealed interface Parsed
    /** An audio packet from some sender; tells us a stream name is active on the network. */
    data class AudioSeen(val streamName: String, val sampleRate: Int, val channels: Int) : Parsed
    /** A ping reply from a VBAN-capable device; [roles] is the PING0 bitType bit field. */
    data class ServiceReply(
        val application: String, val host: String, val device: String, val roles: Int,
    ) : Parsed

    const val ROLE_RECEPTOR = 0x01
    const val ROLE_TRANSMITTER = 0x02
    const val ROLE_RECEPTOR_SPOT = 0x04
    const val ROLE_TRANSMITTER_SPOT = 0x08
    const val ROLE_VIRTUAL_DEVICE = 0x10
    const val ROLE_VIRTUAL_MIXER = 0x20
    const val ROLE_MATRIX = 0x40
    const val ROLE_DAW = 0x80
    const val ROLE_SERVER = 0x01000000

    /** Roles that mean the device routes/mixes audio between other VBAN peers. */
    const val HUB_ROLES = ROLE_VIRTUAL_MIXER or ROLE_MATRIX or ROLE_SERVER

    fun isReceiver(roles: Int) = roles and (ROLE_RECEPTOR or ROLE_RECEPTOR_SPOT) != 0

    fun roleNames(roles: Int): List<String> = buildList {
        if (roles and (ROLE_RECEPTOR or ROLE_RECEPTOR_SPOT) != 0) add("Receiver")
        if (roles and (ROLE_TRANSMITTER or ROLE_TRANSMITTER_SPOT) != 0) add("Transmitter")
        if (roles and ROLE_VIRTUAL_DEVICE != 0) add("Virtual device")
        if (roles and ROLE_VIRTUAL_MIXER != 0) add("Mixer")
        if (roles and ROLE_MATRIX != 0) add("Matrix")
        if (roles and ROLE_DAW != 0) add("DAW")
        if (roles and ROLE_SERVER != 0) add("Server")
    }

    fun parse(d: ByteArray, len: Int): Parsed? {
        if (len < Vban.HEADER_SIZE) return null
        if (d[0] != 'V'.code.toByte() || d[1] != 'B'.code.toByte() ||
            d[2] != 'A'.code.toByte() || d[3] != 'N'.code.toByte()) return null
        return when (d[4].toInt() and SUBPROTOCOL_MASK) {
            SUBPROTOCOL_AUDIO -> AudioSeen(
                ascii(d, 8, Vban.STREAM_NAME_LEN, len),
                Vban.SAMPLE_RATES.getOrElse(d[4].toInt() and 0x1F) { 0 },
                (d[6].toInt() and 0xFF) + 1,
            )
            SUBPROTOCOL_SERVICE -> {
                // The reply bit is set on the function/service byte; accept either so we
                // are lenient with implementations, but never treat a request as a reply.
                val isReply = ((d[5].toInt() or d[6].toInt()) and REPLY_FLAG) != 0
                if (!isReply) return null
                val base = Vban.HEADER_SIZE
                ServiceReply(
                    application = ascii(d, base + OFF_APPLICATION, FIELD_LEN, len),
                    host = ascii(d, base + OFF_HOST, FIELD_LEN, len),
                    device = ascii(d, base + OFF_DEVICE, FIELD_LEN, len),
                    roles = if (len >= base + 4)
                        (d[base].toInt() and 0xFF) or ((d[base + 1].toInt() and 0xFF) shl 8) or
                            ((d[base + 2].toInt() and 0xFF) shl 16) or ((d[base + 3].toInt() and 0xFF) shl 24)
                    else 0,
                )
            }
            else -> null
        }
    }

    private fun putAscii(dst: ByteArray, off: Int, s: String) {
        val b = s.toByteArray(Charsets.US_ASCII)
        System.arraycopy(b, 0, dst, off, minOf(b.size, FIELD_LEN - 1))
    }

    /** Reads a NUL-terminated ASCII field, tolerating packets shorter than the field. */
    private fun ascii(d: ByteArray, off: Int, max: Int, len: Int): String {
        if (off >= len) return ""
        val end = minOf(off + max, len)
        var n = off
        while (n < end && d[n].toInt() != 0) n++
        return String(d, off, n - off, Charsets.US_ASCII).trim()
    }
}
