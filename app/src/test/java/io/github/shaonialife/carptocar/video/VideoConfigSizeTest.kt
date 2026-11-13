package io.github.shaonialife.carptocar.video

import io.github.shaonialife.carptocar.carlife.H264Sps
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * VideoConfig 尺寸选择：显式 CAR 覆盖优先，否则必须与本份 avcC 的裁剪后尺寸一致。
 * 协商值可能已被引擎降档；源先于 attach 就绪时，上层回调也可能尚未注册。
 */
class VideoConfigSizeTest {
    @Test
    fun canvasWinsOverDowngradedStream() {
        val size =
            VideoSender.resolveConfigSize(
                canvas = 1412 to 654,
                live = 1280 to 720,
                fallbackWidth = 1280,
                fallbackHeight = 720,
                sps = 1280 to 720,
            )
        assertEquals(1412 to 654, size)
    }

    @Test
    fun rawUsesCurrentSpsInsteadOfNegotiatedSize() {
        // 2560×800 是用户配置的手机输出；2662×812 是 BMW 画布，二者有意不同。
        val sps = requireNotNull(H264Sps.parseNal(hex("67 42 80 28 da 00 a0 06 5a 6a 02 02 02 0d a1 42 6a")))
        assertEquals(2560 to 800, sps.visible)
        assertEquals(2560, sps.codedWidth)
        assertEquals(800, sps.codedHeight)
        assertEquals(
            2560 to 800,
            VideoSender.resolveConfigSize(null, 2662 to 812, 2662, 812, sps.visible),
        )
    }

    @Test
    fun zeroCanvasIsTreatedAsUnknown() {
        val size =
            VideoSender.resolveConfigSize(
                canvas = 0 to 0,
                live = 1280 to 720,
                fallbackWidth = 1280,
                fallbackHeight = 720,
                sps = null,
            )
        assertEquals(1280 to 720, size)
    }

    @Test
    fun usesLiveThenConstructorWhenSpsUnavailable() {
        assertEquals(
            1280 to 720,
            VideoSender.resolveConfigSize(null, 1280 to 720, 2662, 812, null),
        )
        assertEquals(
            1130 to 523,
            VideoSender.resolveConfigSize(null, null, 1130, 523, null),
        )
        assertEquals(
            1920 to 1080,
            VideoSender.resolveConfigSize(null, null, 0, 0, 1920 to 1080),
        )
    }

    @Test
    fun usesCroppedDisplaySizeRatherThanMacroblockPadding() {
        // Baseline 4:2:0: 89×41 宏块 = 1424×656，crop_right=6 / crop_bottom=1。
        val size = requireNotNull(H264Sps.parseNal(hex("67 42 80 28 da 01 64 14 f9 e9")))
        assertEquals(1424, size.codedWidth)
        assertEquals(656, size.codedHeight)
        assertEquals(1412 to 654, size.visible)
        assertEquals(1412 to 654, VideoSender.resolveConfigSize(null, 1424 to 656, 1424, 656, size.visible))
    }

    @Test
    fun headerCarriesDeclaredCanvasAsTwoFloats() {
        val header = VideoSender.buildVideoConfigHeader(bodySize = 33, width = 1412, height = 654)
        val buffer = ByteBuffer.wrap(header, 16, 8).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1412f, buffer.float, 0.001f)
        assertEquals(654f, buffer.float, 0.001f)
        // small_param[1] = Encrypted：不声明的话车机按明文 NAL 解，同样黑屏。
        assertEquals(0x04, header[6].toInt() and 0xFF)
    }

    @Test
    fun noViewAreaLeavesGeometryFieldsZero() {
        // 默认形态（对齐 AMCar：只写解码尺寸、不写视口）：32..48 必须全是 0。
        // 旧车机不读这些字段，多写反而是「给车机的额外信息」，所以默认不写。
        val header = VideoSender.buildVideoConfigHeader(bodySize = 33, width = 1280, height = 720)
        for (offset in 32 until 48) {
            assertEquals("偏移 $offset 必须为 0", 0, header[offset].toInt())
        }
    }

    @Test
    fun viewAreaIsWrittenSeparatelyFromDecoderSize() {
        // 4580：解码尺寸与显示视口是两个字段。码流只能 720p 时，
        // params[1] 报 1280x720、params[4] 报车机画布 1412x654。
        val header =
            VideoSender.buildVideoConfigHeader(
                bodySize = 33,
                width = 1280,
                height = 720,
                viewArea = 1412 to 654,
            )
        val decoder = ByteBuffer.wrap(header, 16, 8).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1280f, decoder.float, 0.001f)
        assertEquals(720f, decoder.float, 0.001f)

        val origin = ByteBuffer.wrap(header, 32, 8).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0f, origin.float, 0.001f)
        assertEquals(0f, origin.float, 0.001f)

        val viewport = ByteBuffer.wrap(header, 40, 8).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1412f, viewport.float, 0.001f)
        assertEquals(654f, viewport.float, 0.001f)
    }

    private fun hex(value: String): ByteArray =
        value.split(" ").map { it.toInt(16).toByte() }.toByteArray()
}
