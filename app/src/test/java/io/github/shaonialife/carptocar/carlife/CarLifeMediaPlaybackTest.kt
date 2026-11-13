package io.github.shaonialife.carptocar.carlife

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarLifeMediaPlaybackTest {
    @Test
    fun `setup init stop init without focus and pcm does not authorize playback`() {
        val playback = CarLifeMediaPlayback()

        playback.onActivity(CarLifeMediaActivity.INITIALIZED)
        playback.onActivity(CarLifeMediaActivity.STOP)
        playback.onActivity(CarLifeMediaActivity.INITIALIZED)
        assertFalse(playback.playing)

        playback.onActivity(CarLifeMediaActivity.FOCUS_GAINED)
        assertFalse(playback.playing)

        playback.onActivity(CarLifeMediaActivity.PCM)
        assertTrue(playback.playing)
        assertTrue(playback.canForwardPcm)
    }

    @Test
    fun `car pause blocks residual pcm and car resume restores playback without needing 0x30004`() {
        val playback = CarLifeMediaPlayback()
        playback.onActivity(CarLifeMediaActivity.FOCUS_GAINED)
        playback.onActivity(CarLifeMediaActivity.INITIALIZED)
        playback.onActivity(CarLifeMediaActivity.PCM)
        assertTrue(playback.playing)

        playback.onCarPause()
        assertFalse(playback.playing)
        assertFalse(playback.canForwardPcm)

        // 暂停期间引擎残留 PCM 不得重新点燃播放。
        playback.onActivity(CarLifeMediaActivity.PCM)
        assertFalse(playback.playing)
        assertFalse(playback.canForwardPcm)

        // 车机解除静音：即便第三方播放器只继续推裸 PCM 而不发 0x30004，也能立即恢复。
        playback.onCarResume()
        assertTrue(playback.playing)
        assertTrue(playback.canForwardPcm)
    }

    @Test
    fun `explicit media session pause vetoes car resume`() {
        val playback = CarLifeMediaPlayback()
        playback.onSessionPlayback(false)
        playback.onCarResume()
        assertFalse(playback.playing)
        assertFalse(playback.canForwardPcm)
    }

    @Test
    fun `carlife hardkey and audio packet constants match verified md wire protocol`() {
        assertEquals(0x0F, CarLifeProtocol.HardKey.SEEK_SUB)
        assertEquals(0x10, CarLifeProtocol.HardKey.SEEK_ADD)
        assertEquals(0x1F, CarLifeProtocol.HardKey.MEDIA_START)
        assertEquals(0x20, CarLifeProtocol.HardKey.MEDIA_STOP)
        assertEquals(98318, CarLifeProtocol.HU_PAUSE_MEDIA)
        assertEquals(0x30002, CarLifeProtocol.MUSIC_PKG_STOP)
        assertEquals(0x30003, CarLifeProtocol.MUSIC_PKG_PAUSE)
        assertEquals(0x30004, CarLifeProtocol.MUSIC_PKG_RESUME)
        assertEquals(0x40002, CarLifeProtocol.TTS_PKG_STOP)
        assertEquals(0x40003, CarLifeProtocol.TTS_PKG_NORMAL)
    }
}
