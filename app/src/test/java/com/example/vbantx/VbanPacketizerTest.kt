package com.example.vbantx

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class VbanPacketizerTest {

    @Test
    fun stereo48k_headerAndSizing() {
        val p = VbanPacketizer("Stream1", 48000, 2)
        assertEquals(256, p.framesPerPacket)
        assertEquals(1024, p.payloadBytes)

        val pcm = ByteArray(p.payloadBytes) { it.toByte() }
        val out = ByteArray(p.packetSize)
        assertEquals(1052, p.build(pcm, 0, out))

        assertEquals("VBAN", String(out, 0, 4, Charsets.US_ASCII))
        assertEquals(3, out[4].toInt())      // 48000 Hz, audio sub-protocol
        assertEquals(255, out[5].toInt() and 0xFF) // 256 samples - 1
        assertEquals(1, out[6].toInt())      // 2 channels - 1
        assertEquals(1, out[7].toInt())      // int16 PCM
        assertEquals("Stream1", String(out, 8, 7, Charsets.US_ASCII))
        assertEquals(0, out[15].toInt())     // zero padded
        assertArrayEquals(pcm, out.copyOfRange(28, 28 + pcm.size))
    }

    @Test
    fun frameCounterIncrementsLittleEndian() {
        val p = VbanPacketizer("x", 44100, 1)
        val pcm = ByteArray(p.payloadBytes)
        val out = ByteArray(p.packetSize)
        repeat(258) { p.build(pcm, 0, out) }
        p.build(pcm, 0, out) // counter value 258 = 0x102
        assertEquals(0x02, out[24].toInt())
        assertEquals(0x01, out[25].toInt())
        assertEquals(16, out[4].toInt())     // 44100 Hz index
    }

    @Test
    fun longStreamNameIsTruncatedTo16Bytes() {
        val p = VbanPacketizer("ABCDEFGHIJKLMNOPQRSTUV", 8000, 1)
        val out = ByteArray(p.packetSize)
        p.build(ByteArray(p.payloadBytes), 0, out)
        assertEquals("ABCDEFGHIJKLMNOP", String(out, 8, 16, Charsets.US_ASCII))
        assertEquals(7, out[4].toInt())      // 8000 Hz index
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedRateRejected() {
        VbanPacketizer("x", 12345, 1)
    }
}

class VbanServiceTest {

    @Test
    fun pingRequestLayout() {
        val p = VbanService.buildPingRequest()
        assertEquals(28 + 676, p.size)
        assertEquals("VBAN", String(p, 0, 4, Charsets.US_ASCII))
        assertEquals(0x60, p[4].toInt() and 0xFF)
        assertEquals("VBAN Service", String(p, 8, 12, Charsets.US_ASCII))
        // our own request must not be mistaken for a reply
        assertEquals(null, VbanService.parse(p, p.size))
    }

    @Test
    fun parsesPingReply() {
        val p = VbanService.buildPingRequest()
        p[6] = 0x80.toByte() // mark as reply
        val r = VbanService.parse(p, p.size) as VbanService.ServiceReply
        assertEquals("VBAN Transmitter", r.application)
        assertEquals("Android", r.device)
        assertEquals(listOf("Transmitter"), VbanService.roleNames(r.roles))
        assertEquals(false, VbanService.isReceiver(r.roles))
    }

    @Test
    fun parsesAudioPacketStreamName() {
        val pk = VbanPacketizer("MyMix", 48000, 2)
        val out = ByteArray(pk.packetSize)
        pk.build(ByteArray(pk.payloadBytes), 0, out)
        assertEquals(VbanService.AudioSeen("MyMix", 48000, 2), VbanService.parse(out, out.size))
    }

    @Test
    fun rejectsGarbage() {
        assertEquals(null, VbanService.parse(ByteArray(10), 10))
        assertEquals(null, VbanService.parse(ByteArray(100), 100))
    }
}
