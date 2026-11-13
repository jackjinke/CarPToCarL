package io.github.shaonialife.carptocar.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class CarLifePcmMixerTest {
    private fun pcm16Le(
        frames: Int,
        channels: Int,
        amplitude: Short,
    ): ByteArray {
        val buf = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames * channels) { buf.putShort(amplitude) }
        return buf.array()
    }

    private fun peakAbs(stereoFrame: ByteArray): Int {
        val buf = ByteBuffer.wrap(stereoFrame).order(ByteOrder.LITTLE_ENDIAN)
        var peak = 0
        while (buf.remaining() >= 2) {
            peak = maxOf(peak, abs(buf.short.toInt()))
        }
        return peak
    }

    @Test
    fun `concurrent media and navigation mix on single clock with media ducking`() {
        val mixer = CarLifePcmMixer(outputRate = 48_000)
        val t0 = 1_000_000_000L
        val mediaIn = pcm16Le(frames = 4_410, channels = 2, amplitude = 12_000)
        val navIn = pcm16Le(frames = 1_600, channels = 1, amplitude = 8_000)

        mixer.feed(PcmSource.MEDIA, mediaIn, mediaIn.size, rate = 44_100, channels = 2, now = t0)
        mixer.feed(PcmSource.NAVIGATION, navIn, navIn.size, rate = 16_000, channels = 1, now = t0)

        // 100ms 44.1k 与 100ms 16k 重采样到 48k 后各自约 4800 帧（而非串行叠加成 9600 帧）。
        assertTrue(mixer.queuedFrames(PcmSource.MEDIA) in 4_790..4_810)
        assertTrue(mixer.queuedFrames(PcmSource.NAVIGATION) in 4_790..4_810)

        val out = ByteArray(AudioSender.PACKET_BYTES)
        val readyNow = t0 + 50_000_000L
        repeat(4) { mixer.render(out, readyNow) }

        // 压低增益收敛后：12000 * 0.25 + 8000 = 11000（而非未压低的 20000）。
        val mixedPeak = peakAbs(out)
        assertTrue("expected ducked mix ~11000, got $mixedPeak", mixedPeak in 10_500..11_500)
    }

    @Test
    fun `clearing media queue stops music immediately without dropping navigation speech`() {
        val mixer = CarLifePcmMixer(outputRate = 48_000)
        val t0 = 2_000_000_000L
        val mediaIn = pcm16Le(frames = 4_800, channels = 2, amplitude = 14_000)
        val navIn = pcm16Le(frames = 1_600, channels = 1, amplitude = 6_000)

        mixer.feed(PcmSource.MEDIA, mediaIn, mediaIn.size, rate = 48_000, channels = 2, now = t0)
        mixer.feed(PcmSource.NAVIGATION, navIn, navIn.size, rate = 16_000, channels = 1, now = t0)

        mixer.clear(PcmSource.MEDIA)
        assertEquals(0, mixer.queuedFrames(PcmSource.MEDIA))
        assertTrue(mixer.queuedFrames(PcmSource.NAVIGATION) > 4_000)

        val out = ByteArray(AudioSender.PACKET_BYTES)
        mixer.render(out, t0 + 50_000_000L)
        val navOnlyPeak = peakAbs(out)
        assertTrue("expected navigation speech ~6000, got $navOnlyPeak", navOnlyPeak in 5_900..6_100)
    }
}
