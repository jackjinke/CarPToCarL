package io.github.shaonialife.carptocar.audio

import kotlin.math.abs

/** 音源标识与接收端单一媒体流解耦：媒体与导航播报各自独立排队，在同一输出时钟按采样点混音。 */
enum class PcmSource {
    MEDIA,
    NAVIGATION,
}

/**
 * 双路有界 PCM16LE 混音器与流式线性重采样器，由单一输出时钟按固定节拍拉取。
 *
 * 为什么不能把两路源直接顺序写进同一个 FIFO（2026-11-12 实车问题根因）：
 * 媒体（9240）与导航 TTS（9241）并发到达时，顺序拼接会把两路时长串行化，等同于以 2 倍速率推进
 * RTP 时间戳，接收端表现为破音、倍速或缓冲溢出；正确做法是在每个输出采样间隔上按样本叠加，并在
 * 导航播报活跃及尾音保持期内平滑压低媒体增益（ducking）。
 */
internal class CarLifePcmMixer(
    private val outputRate: Int,
) {
    private val media = Source()
    private val navigation = Source()
    private var mixedFrames = 0L
    private var silentFrames = 0L
    private var clippedSamples = 0L
    private var outputPeak = 0
    private var navigationHoldFrames = 0
    private var mediaGain = FULL_GAIN
    private val gainStep = (FULL_GAIN / (outputRate / 100)).coerceAtLeast(1)

    private inner class Source {
        // 0.5 秒立体声采样容量（outputRate 个 Short = outputRate/2 立体声帧），吸收瞬时突发且不积压陈旧语音。
        val samples = ShortArray(outputRate)
        var head = 0
        var count = 0
        var readyAtNanos = 0L
        var rate = 0
        var channels = 0
        var inputFrames = 0L
        var nextPosition = 0L
        var previousLeft = 0
        var previousRight = 0
        val partial = ByteArray(4)
        var partialSize = 0
        var inputBytes = 0L
        var inputPeak = 0
        var lastFeedNanos = 0L
        var overflowFrames = 0L
        var underrunFrames = 0L
        var active = false

        fun append(
            left: Int,
            right: Int,
            now: Long,
        ) {
            if (count == samples.size) {
                head = (head + 2) % samples.size
                count -= 2
                overflowFrames++
            }
            if (readyAtNanos == 0L) readyAtNanos = now + PREBUFFER_NANOS
            val tail = (head + count) % samples.size
            samples[tail] = left.toShort()
            samples[(tail + 1) % samples.size] = right.toShort()
            count += 2
        }

        fun resetResampler() {
            inputFrames = 0
            nextPosition = 0
            partialSize = 0
        }

        fun acceptFrame(
            raw: ByteArray,
            offset: Int,
            now: Long,
        ) {
            val left = readSample(raw, offset)
            val right = if (channels == 1) left else readSample(raw, offset + 2)
            inputPeak = maxOf(inputPeak, abs(left), abs(right))
            val frameIndex = inputFrames++
            if (frameIndex == 0L) {
                append(left, right, now)
                nextPosition = rate.toLong()
            } else {
                val end = frameIndex * outputRate
                val start = end - outputRate
                while (nextPosition <= end) {
                    val fraction = nextPosition - start
                    append(
                        previousLeft + ((left - previousLeft).toLong() * fraction / outputRate).toInt(),
                        previousRight + ((right - previousRight).toLong() * fraction / outputRate).toInt(),
                        now,
                    )
                    nextPosition += rate
                }
            }
            previousLeft = left
            previousRight = right
        }

        fun finish(now: Long) {
            // 采样率/声道切换时一次性冲刷插值尾帧，避免跨曲目借样。
            while (inputFrames > 0 && nextPosition < inputFrames * outputRate) {
                append(previousLeft, previousRight, now)
                nextPosition += rate
            }
            resetResampler()
        }

        fun discard(frames: Int) {
            val shorts = minOf(count / 2, frames) * 2
            head = (head + shorts) % samples.size
            count -= shorts
        }

        fun stats(
            now: Long,
            elapsedMillis: Long,
        ): String {
            val ageMs = if (lastFeedNanos == 0L) -1 else (now - lastFeedNanos) / 1_000_000
            val result =
                "inputBps=${inputBytes * 1000 / elapsedMillis} rate=$rate ch=$channels " +
                    "peak=$inputPeak ageMs=$ageMs queueFrames=${count / 2} " +
                    "overflowFrames=$overflowFrames underrunFrames=$underrunFrames"
            inputBytes = 0
            inputPeak = 0
            overflowFrames = 0
            underrunFrames = 0
            return result
        }
    }

    private fun source(source: PcmSource): Source = if (source == PcmSource.MEDIA) media else navigation

    @Synchronized
    fun feed(
        source: PcmSource,
        pcm: ByteArray,
        size: Int,
        rate: Int,
        channels: Int,
        now: Long,
    ) {
        require(size in 0..pcm.size && rate in 8_000..48_000 && channels in 1..2)
        val target = source(source)
        if (target.rate != rate || target.channels != channels) {
            target.finish(now)
            target.readyAtNanos = 0L
            target.rate = rate
            target.channels = channels
        }
        target.active = true
        target.lastFeedNanos = now
        target.inputBytes += size
        val frameBytes = channels * 2
        var offset = 0
        if (target.partialSize > 0) {
            val copied = minOf(frameBytes - target.partialSize, size)
            pcm.copyInto(target.partial, target.partialSize, 0, copied)
            target.partialSize += copied
            offset += copied
            if (target.partialSize == frameBytes) {
                target.acceptFrame(target.partial, 0, now)
                target.partialSize = 0
            }
        }
        while (offset + frameBytes <= size) {
            target.acceptFrame(pcm, offset, now)
            offset += frameBytes
        }
        if (offset < size) {
            target.partialSize = size - offset
            pcm.copyInto(target.partial, 0, offset, size)
        }
    }

    /** 暂停、STOP 或通道重建时仅清空对应音源的排队样本，不影响另一路与输出时钟。 */
    @Synchronized
    fun clear(source: PcmSource) {
        val target = source(source)
        target.discard(target.count / 2)
        target.readyAtNanos = 0L
        target.resetResampler()
        target.active = false
        if (source == PcmSource.NAVIGATION) {
            navigationHoldFrames = 0
        }
    }

    @Synchronized
    fun queuedFrames(source: PcmSource): Int = source(source).count / 2

    @Synchronized
    fun skip(frames: Int) {
        media.discard(frames)
        navigation.discard(frames)
    }

    /** 填充一帧立体声小端 PCM；空队列贡献零值，不拉长 RTP 时长。 */
    @Synchronized
    fun render(
        out: ByteArray,
        now: Long,
    ) {
        for (offset in out.indices step 4) {
            val haveMedia = media.count >= 2 && now >= media.readyAtNanos
            val haveNavigation = navigation.count >= 2 && now >= navigation.readyAtNanos
            if (haveNavigation) {
                navigationHoldFrames = outputRate * NAV_HOLD_MILLIS / 1000
            } else if (navigationHoldFrames > 0) {
                navigationHoldFrames--
            }
            val targetGain = if (navigationHoldFrames > 0) DUCK_GAIN else FULL_GAIN
            mediaGain += (targetGain - mediaGain).coerceIn(-gainStep, gainStep)
            if (haveMedia && haveNavigation) mixedFrames++
            if (!haveMedia && !haveNavigation) silentFrames++
            for (lane in 0..1) {
                val music = if (haveMedia) media.samples[(media.head + lane) % media.samples.size].toInt() else 0
                val speech =
                    if (haveNavigation) {
                        navigation.samples[(navigation.head + lane) % navigation.samples.size].toInt()
                    } else {
                        0
                    }
                val sum = music * mediaGain / FULL_GAIN + speech
                val sample = sum.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                if (sample != sum) clippedSamples++
                outputPeak = maxOf(outputPeak, abs(sample))
                out[offset + lane * 2] = sample.toByte()
                out[offset + lane * 2 + 1] = (sample shr 8).toByte()
            }
            if (haveMedia) {
                media.discard(1)
            } else if (media.active) {
                media.underrunFrames++
            }
            if (haveNavigation) {
                navigation.discard(1)
            } else if (navigation.active) {
                navigation.underrunFrames++
            }
        }
    }

    @Synchronized
    fun report(
        now: Long,
        elapsedMillis: Long,
    ): String {
        val safeElapsed = elapsedMillis.coerceAtLeast(1L)
        val result =
            "media={${media.stats(now, safeElapsed)}} navigation={${navigation.stats(now, safeElapsed)}} " +
                "mixedFrames=$mixedFrames silenceFrames=$silentFrames " +
                "outputPeak=$outputPeak clippedSamples=$clippedSamples"
        mixedFrames = 0
        silentFrames = 0
        outputPeak = 0
        clippedSamples = 0
        return result
    }

    private companion object {
        const val PREBUFFER_NANOS = 40_000_000L
        const val NAV_HOLD_MILLIS = 150
        const val FULL_GAIN = 65_536
        const val DUCK_GAIN = 16_384

        fun readSample(
            raw: ByteArray,
            offset: Int,
        ): Int = ((raw[offset].toInt() and 0xFF) or (raw[offset + 1].toInt() shl 8)).toShort().toInt()
    }
}
