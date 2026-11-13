package io.github.shaonialife.carptocar.audio

import io.github.shaonialife.carptocar.airplay.AirPlayCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioSenderClockGateTest {
    @Test
    fun `passthrough stays silent on wire before onRecorded and streams encrypted rtp after onRecorded`() {
        DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { dataRx ->
            DatagramSocket(0, InetAddress.getByName("127.0.0.1")).use { ctrlRx ->
                dataRx.soTimeout = 120
                ctrlRx.soTimeout = 120
                val key = ByteArray(32) { (it + 7).toByte() }
                val sender =
                    AudioSender(
                        host = "127.0.0.1",
                        dataPort = dataRx.localPort,
                        streamKey = key,
                        log = {},
                        sampleRate = 48_000,
                        codec = AudioCodec.PCM,
                        streamType = 100L,
                        controlPort = ctrlRx.localPort,
                    )
                try {
                    assertTrue(sender.startPassthrough())
                    val pcm =
                        ByteBuffer
                            .allocate(4_800 * 4)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .apply { repeat(4_800 * 2) { putShort(9_000) } }
                            .array()
                    sender.feedPcm(pcm, pcm.size, srcRate = 48_000, srcChannels = 2, source = PcmSource.MEDIA)

                    // RECORD 之前绝不能抢跑任何 RTP 音频包或同步包。
                    val probe = DatagramPacket(ByteArray(2048), 2048)
                    try {
                        dataRx.receive(probe)
                        fail("expected zero RTP packets before onRecorded()")
                    } catch (_: SocketTimeoutException) {
                    }
                    try {
                        ctrlRx.receive(probe)
                        fail("expected zero sync packets before onRecorded()")
                    } catch (_: SocketTimeoutException) {
                    }

                    // 激活 RECORD：同步包与加密 RTP 音频包开始按单一时钟发出。
                    dataRx.soTimeout = 500
                    ctrlRx.soTimeout = 500
                    sender.onRecorded()

                    val syncPkt = DatagramPacket(ByteArray(64), 64)
                    ctrlRx.receive(syncPkt)
                    assertEquals(20, syncPkt.length)
                    // 首个同步包携带 X 位（0x90）。
                    assertEquals(0x90, syncPkt.data[0].toInt() and 0xFF)

                    // 预缓冲 40ms 后可解出非零大端 PCM16 载荷。
                    var nonZeroFound = false
                    for (i in 0 until 15) {
                        val rtp = DatagramPacket(ByteArray(2048), 2048)
                        dataRx.receive(rtp)
                        val aad = rtp.data.copyOfRange(4, 12)
                        val cipherAndTag = rtp.data.copyOfRange(12, rtp.length - 8)
                        val nonceTail = rtp.data.copyOfRange(rtp.length - 8, rtp.length)
                        val nonce = ByteArray(12).also { System.arraycopy(nonceTail, 0, it, 4, 8) }
                        val plain = AirPlayCrypto.chachaOpen(key, nonce, cipherAndTag, aad)
                        assertEquals(AudioSender.PACKET_BYTES, plain.size)
                        val firstSampleBe =
                            ByteBuffer
                                .wrap(plain)
                                .order(ByteOrder.BIG_ENDIAN)
                                .short
                                .toInt()
                        if (firstSampleBe == 9_000) {
                            nonZeroFound = true
                            break
                        }
                    }
                    assertTrue("expected decrypted big-endian PCM sample 9000 after prebuffer", nonZeroFound)

                    sender.clearPcm(PcmSource.MEDIA)
                    assertEquals(0, sender.queuedFramesForTest(PcmSource.MEDIA))
                } finally {
                    sender.stop()
                }
            }
        }
    }
}
