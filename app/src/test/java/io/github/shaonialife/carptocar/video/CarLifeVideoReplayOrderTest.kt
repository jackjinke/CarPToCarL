package io.github.shaonialife.carptocar.video

import io.github.shaonialife.carptocar.airplay.AirPlayCrypto
import io.github.shaonialife.carptocar.airplay.StreamFrameCipher
import io.github.shaonialife.carptocar.carlife.CarLifeHijack
import io.github.shaonialife.carptocar.carlife.CarLifeHuClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class CarLifeVideoReplayOrderTest {
    @Test
    fun `ordered encoder init requires both versionMatched and featureConfigAcked`() {
        assertFalse(
            CarLifeHuClient.canSendOrderedEncoderInit(
                orderedInit = true,
                versionMatched = false,
                featureConfigAcked = false,
            ),
        )
        assertFalse(
            CarLifeHuClient.canSendOrderedEncoderInit(
                orderedInit = true,
                versionMatched = true,
                featureConfigAcked = false,
            ),
        )
        assertFalse(
            CarLifeHuClient.canSendOrderedEncoderInit(
                orderedInit = true,
                versionMatched = false,
                featureConfigAcked = true,
            ),
        )
        assertTrue(
            CarLifeHuClient.canSendOrderedEncoderInit(
                orderedInit = true,
                versionMatched = true,
                featureConfigAcked = true,
            ),
        )
        assertTrue(
            CarLifeHuClient.canSendOrderedEncoderInit(
                orderedInit = false,
                versionMatched = false,
                featureConfigAcked = false,
            ),
        )
    }

    @Test
    fun `passthrough emits cropped sps VideoConfig before first encrypted IDR and local stop suppresses onEnded`() {
        val endedCalled = AtomicBoolean(false)
        val key = ByteArray(32) { (it + 3).toByte() }
        // Baseline 4:2:0: coded 1424x656, cropped visible 1412x654
        val sps = hex("67 42 80 28 da 01 64 14 f9 e9")
        val pps = hex("68 ce 3c 80")
        val idr = byteArrayOf(0x65, 0x11, 0x22, 0x33, 0x44)
        val pFrame = byteArrayOf(0x41, 0x55, 0x66, 0x77)
        val annexBKey = annexB(sps, pps, idr)
        val annexBP = annexB(pFrame)

        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val sender =
                VideoSender(
                    host = "127.0.0.1",
                    dataPort = server.localPort,
                    frameCipher = StreamFrameCipher.ChaCha(key),
                    width = 1280,
                    height = 720,
                    log = {},
                    onEnded = { endedCalled.set(true) },
                    liveSize = { 1280 to 720 },
                )
            try {
                assertTrue(sender.startPassthrough())
                val accepted = server.accept()
                accepted.soTimeout = 2_000
                val input = DataInputStream(accepted.getInputStream())

                // 新流首帧前到达的 P 帧必须被挡住，绝不能抢在 VideoConfig+IDR 前写出。
                assertFalse(sender.submitH264(annexBP, keyFrame = false))

                // 注入带裁剪 SPS 的 IDR + 紧随的 P 帧。
                assertTrue(sender.submitH264(annexBKey, keyFrame = true))
                assertTrue(sender.submitH264(annexBP, keyFrame = false))

                // 第 1 包必须是 VideoConfig (opcode=1)，且头部声明的是裁剪后 visible 1412x654。
                val cfgHead = ByteArray(128)
                input.readFully(cfgHead)
                assertEquals(1, cfgHead[4].toInt() and 0xFF)
                val cfgBodyLen = ByteBuffer.wrap(cfgHead, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val cfgDims = ByteBuffer.wrap(cfgHead, 16, 8).order(ByteOrder.LITTLE_ENDIAN)
                assertEquals(1412f, cfgDims.float, 0.001f)
                assertEquals(654f, cfgDims.float, 0.001f)
                val cfgBody = ByteArray(cfgBodyLen)
                input.readFully(cfgBody)
                assertEquals(1, cfgBody[0].toInt() and 0xFF) // avcC configurationVersion=1

                // 第 2 包必须是加密 IDR 帧 (opcode=0, nonce=0)，第 3 包是后续 P 帧 (nonce=1)。
                val idrHead = ByteArray(128)
                input.readFully(idrHead)
                assertEquals(0, idrHead[4].toInt() and 0xFF)
                val idrLen = ByteBuffer.wrap(idrHead, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val idrCipher = ByteArray(idrLen)
                input.readFully(idrCipher)
                val idrPlain = AirPlayCrypto.chachaOpen(key, AirPlayCrypto.nonce64(0), idrCipher, idrHead)
                assertEquals((4 + sps.size) + (4 + pps.size) + (4 + idr.size), idrPlain.size)
                assertEquals(listOf(7, 8, 5), nalTypesOfLengthPrefixed(idrPlain))

                val pHead = ByteArray(128)
                input.readFully(pHead)
                assertEquals(0, pHead[4].toInt() and 0xFF)
                val pLen = ByteBuffer.wrap(pHead, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val pCipher = ByteArray(pLen)
                input.readFully(pCipher)
                val pPlain = AirPlayCrypto.chachaOpen(key, AirPlayCrypto.nonce64(1), pCipher, pHead)
                assertEquals(4 + pFrame.size, pPlain.size)
                assertEquals(0x41, pPlain[4].toInt() and 0xFF)

                // 本地主动 stop() + detach() 不得误触发「对端断开」的 onEnded。
                CarLifeHijack.detach()
                sender.stop()
                accepted.close()
                Thread.sleep(50)
                assertFalse(endedCalled.get())
            } finally {
                sender.stop()
            }
        }
    }

    private fun annexB(vararg nals: ByteArray): ByteArray {
        val total = nals.sumOf { 4 + it.size }
        val out = ByteBuffer.allocate(total)
        nals.forEach { nal ->
            out.put(byteArrayOf(0, 0, 0, 1))
            out.put(nal)
        }
        return out.array()
    }

    private fun nalTypesOfLengthPrefixed(data: ByteArray): List<Int> {
        val types = ArrayList<Int>()
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        while (buf.remaining() >= 4) {
            val len = buf.int
            types += buf.get().toInt() and 0x1F
            buf.position(buf.position() + len - 1)
        }
        return types
    }

    private fun hex(value: String): ByteArray =
        value
            .split(" ")
            .map { it.toInt(16).toByte() }
            .toByteArray()
}
