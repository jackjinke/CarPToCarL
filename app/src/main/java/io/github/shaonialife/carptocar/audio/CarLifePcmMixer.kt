package io.github.shaonialife.carptocar.audio

/** Source identity is independent of the receiver's single media stream. */
enum class PcmSource { MEDIA, NAVIGATION }

/** Bounded PCM16LE queues and streaming linear resamplers, consumed by one output clock.
 * Source readers never encode or send. Appending two sources to one FIFO serializes their
 * durations (and doubles the RTP clock rate); they must instead share each output sample.
 */
internal class CarLifePcmMixer(private val outputRate: Int) {
    private val media = Source()
    private val navigation = Source()
    private var mixedFrames = 0L
    private var silentFrames = 0L
    private var clippedSamples = 0L
    private var outputPeak = 0
    private var navigationHoldFrames = 0
    private var mediaGain = 65_536
    private val gainStep = (65_536 / (outputRate / 100)).coerceAtLeast(1)

    private inner class Source {
        // Half a second tolerates source bursts without accumulating seconds of stale speech.
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

        fun append(left: Int, right: Int, now: Long) {
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

        fun acceptFrame(raw: ByteArray, offset: Int, now: Long) {
            val left = readSample(raw, offset)
            val right = if (channels == 1) left else readSample(raw, offset + 2)
            inputPeak = maxOf(inputPeak, kotlin.math.abs(left), kotlin.math.abs(right))
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
            // Flush the interpolation tail once, without borrowing samples from the next track.
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

        fun stats(now: Long, elapsedMillis: Long): String {
            val result = "inputBps=${inputBytes * 1000 / elapsedMillis} rate=$rate ch=$channels " +
                "peak=$inputPeak ageMs=${if (lastFeedNanos == 0L) -1 else (now - lastFeedNanos) / 1_000_000} " +
                "queueFrames=${count / 2} overflowFrames=$overflowFrames underrunFrames=$underrunFrames"
            inputBytes = 0
            inputPeak = 0
            overflowFrames = 0
            underrunFrames = 0
            return result
        }
    }

    private fun source(source: PcmSource) = if (source == PcmSource.MEDIA) media else navigation

    @Synchronized
    fun feed(source: PcmSource, pcm: ByteArray, size: Int, rate: Int, channels: Int, now: Long) {
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

    /** STOP, pause or transport replacement discards only this source's obsolete PCM. */
    @Synchronized
    fun clear(source: PcmSource) {
        val target = source(source)
        target.discard(target.count / 2)
        // Rearm only for an explicit source reset, never for ordinary queue drainage.
        target.readyAtNanos = 0L
        target.resetResampler()
        target.active = false
    }

    @Synchronized
    fun skip(frames: Int) {
        media.discard(frames)
        navigation.discard(frames)
    }

    /** Fill a reusable stereo frame. Empty lanes contribute zero, not extra RTP duration. */
    @Synchronized
    fun render(out: ByteArray, now: Long) {
        for (offset in out.indices step 4) {
            val haveMedia = media.count >= 2 && now >= media.readyAtNanos
            val haveNavigation = navigation.count >= 2 && now >= navigation.readyAtNanos
            if (haveNavigation) navigationHoldFrames = outputRate * 150 / 1000
            else if (navigationHoldFrames > 0) navigationHoldFrames--
            val targetGain = if (navigationHoldFrames > 0) 16_384 else 65_536
            mediaGain += (targetGain - mediaGain).coerceIn(-gainStep, gainStep)
            if (haveMedia && haveNavigation) mixedFrames++
            if (!haveMedia && !haveNavigation) silentFrames++
            for (lane in 0..1) {
                val music = if (haveMedia) media.samples[(media.head + lane) % media.samples.size].toInt() else 0
                val speech = if (haveNavigation) navigation.samples[(navigation.head + lane) % navigation.samples.size].toInt() else 0
                // Ramp the duck envelope to avoid a discontinuity at each TTS boundary.
                val sum = music * mediaGain / 65_536 + speech
                val sample = sum.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                if (sample != sum) clippedSamples++
                outputPeak = maxOf(outputPeak, kotlin.math.abs(sample))
                out[offset + lane * 2] = sample.toByte()
                out[offset + lane * 2 + 1] = (sample shr 8).toByte()
            }
            if (haveMedia) media.discard(1) else if (media.active) media.underrunFrames++
            if (haveNavigation) navigation.discard(1) else if (navigation.active) navigation.underrunFrames++
        }
    }

    @Synchronized
    fun report(now: Long, elapsedMillis: Long): String {
        val result = "media={${media.stats(now, elapsedMillis)}} navigation={${navigation.stats(now, elapsedMillis)}} " +
            "mixedFrames=$mixedFrames silenceFrames=$silentFrames outputPeak=$outputPeak clippedSamples=$clippedSamples"
        mixedFrames = 0
        silentFrames = 0
        outputPeak = 0
        clippedSamples = 0
        return result
    }

    private companion object {
        const val PREBUFFER_NANOS = 40_000_000L
        fun readSample(raw: ByteArray, offset: Int): Int =
            ((raw[offset].toInt() and 0xFF) or (raw[offset + 1].toInt() shl 8)).toShort().toInt()
    }
}
