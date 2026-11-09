package com.snaptube.clone.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Merges a video-only MP4 track with an audio-only M4A track into a single MP4.
 *
 * YouTube does not expose progressive high-resolution streams, so every HD
 * download is assembled from two tracks with the platform MediaMuxer.
 */
object MediaMuxerUtil {

    private const val BUFFER_SIZE = 1 shl 20 // 1 MiB

    class MuxException(message: String, cause: Throwable? = null) : Exception(message, cause)

    fun mux(videoFile: File, audioFile: File, outputFile: File) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)

            val videoTrack = findTrack(videoExtractor, "video/")
            val audioTrack = findTrack(audioExtractor, "audio/")
            if (videoTrack < 0) throw MuxException("No video track in ${videoFile.name}")
            if (audioTrack < 0) throw MuxException("No audio track in ${audioFile.name}")

            videoExtractor.selectTrack(videoTrack)
            audioExtractor.selectTrack(audioTrack)

            val videoFormat = videoExtractor.getTrackFormat(videoTrack)
            val audioFormat = audioExtractor.getTrackFormat(audioTrack)

            if (outputFile.exists()) outputFile.delete()
            outputFile.parentFile?.mkdirs()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outVideoTrack = muxer.addTrack(videoFormat)
            val outAudioTrack = muxer.addTrack(audioFormat)
            muxer.start()

            val buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
            val info = MediaCodec.BufferInfo()

            copyTrack(videoExtractor, muxer, outVideoTrack, buffer, info)
            copyTrack(audioExtractor, muxer, outAudioTrack, buffer, info)

            muxer.stop()
        } catch (e: Exception) {
            if (outputFile.exists()) outputFile.delete()
            throw if (e is MuxException) e else MuxException("Failed to merge tracks", e)
        } finally {
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
            videoExtractor.release()
            audioExtractor.release()
        }
    }

    private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix, ignoreCase = true)) return i
        }
        return -1
    }

    private fun copyTrack(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        outTrack: Int,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo
    ) {
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break

            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags

            muxer.writeSampleData(outTrack, buffer, info)
            if (!extractor.advance()) break
        }
    }
}
