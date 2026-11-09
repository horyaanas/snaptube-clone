package com.snaptube.clone.media

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext

/**
 * Converts any audio track Android can decode (M4A/AAC, MP3, Opus, Vorbis,
 * FLAC, or the audio track of a downloaded MP4) into a real MP3 file.
 *
 * Decoding is done by [MediaCodec] and encoding by the pure-Java LAME port,
 * so no FFmpeg binary or native library is required.
 */
object Mp3Transcoder {

    suspend fun convert(
        input: File,
        output: File,
        bitrateKbps: Int = Mp3Encoder.DEFAULT_BITRATE_KBPS,
        onProgress: (Float) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        if (!input.exists() || input.length() <= 0L) {
            throw IOException("Input file is missing or empty")
        }
        output.parentFile?.mkdirs()
        if (output.exists()) output.delete()

        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: Mp3Encoder? = null
        var out: FileOutputStream? = null
        try {
            extractor.setDataSource(input.absolutePath)
            val trackIndex = findAudioTrack(extractor)
            if (trackIndex < 0) throw IOException("No audio track found")

            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IOException("Unknown audio format")
            val durationUs = runCatching {
                inputFormat.getLong(MediaFormat.KEY_DURATION)
            }.getOrDefault(0L)

            decoder = MediaCodec.createDecoderByType(mime)
            // Request 16-bit PCM output where the platform allows it. The codec
            // still reports its effective encoding in INFO_OUTPUT_FORMAT_CHANGED.
            try {
                inputFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            } catch (_: Exception) {
            }
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            out = FileOutputStream(output)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var lastProgress = 0f

            while (!outputDone && coroutineContext.isActive) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuffer = decoder.getInputBuffer(inIndex)
                        val sampleSize = if (inBuffer != null) extractor.readSampleData(inBuffer, 0) else -1
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(
                                inIndex, 0, sampleSize, extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = decoder.outputFormat
                        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            .coerceIn(1, 2)
                        pcmEncoding = runCatching {
                            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }.getOrDefault(AudioFormat.ENCODING_PCM_16BIT)
                        encoder?.close()
                        encoder = Mp3Encoder(sampleRate, channelCount, bitrateKbps)
                    }

                    outIndex >= 0 -> {
                        val buffer = decoder.getOutputBuffer(outIndex)
                        if (buffer != null && info.size > 0) {
                            val enc = encoder ?: throw IOException("Decoder produced no PCM format")
                            val frames = if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                encodeFloatBuffer(buffer, info, enc, out)
                            } else {
                                encodeShortBuffer(buffer, info, enc, out)
                            }
                            if (frames <= 0) {
                                // Mono/stereo conversion may round down; nothing to do.
                            }
                            if (durationUs > 0L) {
                                val progress = (info.presentationTimeUs.toFloat() / durationUs)
                                    .coerceIn(0f, 1f)
                                if (progress - lastProgress >= 0.01f) {
                                    lastProgress = progress
                                    onProgress(progress)
                                }
                            }
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }

                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // Keep looping; input side is fed above.
                    }
                }
            }

            val finalEncoder = encoder ?: throw IOException("No audio could be decoded")
            val flush = ByteArray(Mp3Encoder.OUTPUT_BUFFER_SIZE)
            val written = finalEncoder.finish(flush)
            if (written > 0) out.write(flush, 0, written)
            out.flush()
            out.fd.sync()
            onProgress(1f)

            if (!output.exists() || output.length() <= 0L) {
                throw IOException("MP3 encoder produced an empty file")
            }
            output
        } catch (t: Throwable) {
            output.delete()
            throw t
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.close() }
            runCatching { out?.close() }
            runCatching { extractor.release() }
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith("audio/")) return i
        }
        return -1
    }

    private fun encodeShortBuffer(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        encoder: Mp3Encoder,
        out: FileOutputStream
    ): Int {
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val pcm = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = ShortArray(pcm.remaining())
        pcm.get(samples)
        val frameCount = samples.size / encoder.channels
        return encodePcm(samples, frameCount, encoder, out)
    }

    private fun encodeFloatBuffer(
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo,
        encoder: Mp3Encoder,
        out: FileOutputStream
    ): Int {
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val floats = buffer.order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val floatArray = FloatArray(floats.remaining())
        floats.get(floatArray)
        val samples = ShortArray(floatArray.size) { i ->
            (floatArray[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
        }
        val frameCount = samples.size / encoder.channels
        return encodePcm(samples, frameCount, encoder, out)
    }

    private fun encodePcm(
        samples: ShortArray,
        frameCount: Int,
        encoder: Mp3Encoder,
        out: FileOutputStream
    ): Int {
        if (frameCount <= 0) return 0
        var offsetFrames = 0
        val encoded = ByteArray(Mp3Encoder.OUTPUT_BUFFER_SIZE)
        while (offsetFrames < frameCount) {
            val chunkFrames = minOf(Mp3Encoder.MAX_FRAMES_PER_CALL, frameCount - offsetFrames)
            val chunk = if (offsetFrames == 0 && chunkFrames == frameCount) {
                samples
            } else {
                ShortArray(chunkFrames * encoder.channels).also { dest ->
                    System.arraycopy(
                        samples,
                        offsetFrames * encoder.channels,
                        dest,
                        0,
                        chunkFrames * encoder.channels
                    )
                }
            }
            val written = encoder.encode(chunk, chunkFrames, encoded)
            if (written > 0) out.write(encoded, 0, written)
            offsetFrames += chunkFrames
        }
        return frameCount
    }
}
