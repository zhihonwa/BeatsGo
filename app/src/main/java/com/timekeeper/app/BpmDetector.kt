package com.timekeeper.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt

object BpmDetector {

    private const val TAG = "BpmDetector"
    private const val TARGET_SAMPLE_RATE = 22050
    private const val MAX_DECODE_SECONDS = 45
    private const val HOP = 512
    private const val MIN_BPM = 60
    private const val MAX_BPM = 200

    fun detect(context: Context, uri: Uri): Int? {
        return try {
            val samples = decodeMonoPcm(context, uri) ?: return null
            val envelope = energyEnvelope(samples, HOP)
            estimateBpm(envelope, TARGET_SAMPLE_RATE.toFloat() / HOP)
        } catch (e: Exception) {
            Log.w(TAG, "BPM 检测失败", e)
            null
        }
    }

    private fun decodeMonoPcm(context: Context, uri: Uri): FloatArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) return null
            extractor.selectTrack(trackIndex)

            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val mime = format.getString(MediaFormat.KEY_MIME)!!

            codec = MediaCodec.createDecoderByType(mime).also {
                it.configure(format, null, null, 0)
                it.start()
            }

            val maxSamples = TARGET_SAMPLE_RATE * MAX_DECODE_SECONDS
            val out = FloatArray(maxSamples)
            var outCount = 0
            var sawInputEos = false
            var sawOutputEos = false
            val info = MediaCodec.BufferInfo()

            while (!sawOutputEos && outCount < maxSamples) {
                if (!sawInputEos) {
                    val inIndex = codec.dequeueInputBuffer(10_000L)
                    if (inIndex >= 0) {
                        val input = codec.getInputBuffer(inIndex)!!
                        input.clear()
                        val read = extractor.readSampleData(input, 0)
                        if (read < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, read, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outIndex = codec.dequeueOutputBuffer(info, 10_000L)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    else -> {
                        if (outIndex >= 0) {
                            val buffer = codec.getOutputBuffer(outIndex)!!
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val shorts = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val stride = max(1, sampleRate / TARGET_SAMPLE_RATE)
                            val frames = info.size / 2 / channels
                            var frame = 0
                            while (frame < frames && outCount < maxSamples) {
                                if (frame % stride == 0) {
                                    out[outCount++] = shorts.get(frame * channels) / 32768f
                                }
                                frame++
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                sawOutputEos = true
                            }
                        }
                    }
                }
            }

            if (outCount < TARGET_SAMPLE_RATE * 8) return null
            return out.copyOf(outCount)
        } finally {
            try {
                codec?.stop()
            } catch (e: Exception) {
            }
            try {
                codec?.release()
            } catch (e: Exception) {
            }
            try {
                extractor.release()
            } catch (e: Exception) {
            }
        }
    }

    private fun energyEnvelope(samples: FloatArray, hop: Int): FloatArray {
        val frames = samples.size / hop
        val env = FloatArray(frames)
        for (i in 0 until frames) {
            var sum = 0f
            val base = i * hop
            for (j in 0 until hop) {
                val v = samples[base + j]
                sum += v * v
            }
            env[i] = kotlin.math.sqrt(sum / hop)
        }
        return env
    }

    private fun estimateBpm(env: FloatArray, fps: Float): Int? {
        val n = env.size
        if (n < fps * 8) return null

        val onset = FloatArray(n)
        for (i in 1 until n) {
            onset[i] = max(0f, env[i] - env[i - 1])
        }
        var mean = 0f
        for (v in onset) mean += v
        mean /= n
        val x = FloatArray(n)
        for (i in 0 until n) {
            x[i] = onset[i] - mean
        }

        val minLag = (fps * 60 / MAX_BPM).roundToInt()
        val maxLag = (fps * 60 / MIN_BPM).roundToInt()
        if (minLag < 1 || maxLag >= n / 2) return null

        val corr = FloatArray(maxLag + 1)
        var bestLag = -1
        var bestVal = -1f
        for (lag in minLag..maxLag) {
            var c = 0f
            for (i in 0 until n - lag) {
                c += x[i] * x[i + lag]
            }
            corr[lag] = c
            if (c > bestVal) {
                bestVal = c
                bestLag = lag
            }
        }
        if (bestLag < 0 || bestVal <= 0f) return null

        val halfLag = bestLag / 2
        if (halfLag >= minLag && corr[halfLag] > bestVal * 0.85f) {
            bestLag = halfLag
        }

        val bpm = 60f * fps / bestLag
        if (bpm < MIN_BPM || bpm > MAX_BPM) return null
        return bpm.roundToInt()
    }
}