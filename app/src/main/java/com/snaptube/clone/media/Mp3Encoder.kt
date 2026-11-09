package com.snaptube.clone.media

import de.sciss.jump3r.mp3.BRHist
import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.GetAudio
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.LameGlobalFlags
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Parse
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.Util
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import kotlin.math.max

/**
 * Minimal JNI-free wrapper around the pure-Java LAME port (jump3r).
 *
 * The library's high-level `LameEncoder` depends on `javax.sound.sampled`,
 * which Android does not ship. This class wires up the low-level LAME modules
 * directly and accepts 16-bit signed PCM, so it works on every Android device.
 */
class Mp3Encoder(
    sampleRate: Int,
    channels: Int,
    bitrateKbps: Int = DEFAULT_BITRATE_KBPS
) {
    private val lame = Lame()
    private val gfp: LameGlobalFlags
    private var modules: List<Any> = emptyList()

    init {
        require(channels == 1 || channels == 2) { "Only mono/stereo PCM is supported" }
        require(sampleRate > 0) { "Invalid sample rate" }

        val gainAnalysis = GainAnalysis()
        val bitStream = BitStream()
        val presets = Presets()
        val quantizePvt = QuantizePVT()
        val quantize = Quantize()
        val vbrTag = VBRTag()
        val version = Version()
        val id3Tag = ID3Tag()
        val reservoir = Reservoir()
        val takehiro = Takehiro()
        val parse = Parse()
        val brHist = BRHist()
        val mpgLib = MPGLib()
        val mpgInterface = Interface()
        val common = Common()
        val getAudio = GetAudio()

        lame.setModules(
            gainAnalysis, bitStream, presets, quantizePvt, quantize,
            vbrTag, version, id3Tag, mpgLib
        )
        bitStream.setModules(gainAnalysis, mpgLib, version, vbrTag)
        id3Tag.setModules(bitStream, version)
        presets.setModules(lame)
        quantize.setModules(bitStream, reservoir, quantizePvt, takehiro)
        quantizePvt.setModules(takehiro, reservoir, lame.enc.psy)
        reservoir.setModules(bitStream)
        takehiro.setModules(quantizePvt)
        vbrTag.setModules(lame, bitStream, version)
        getAudio.setModules(parse, mpgLib)
        parse.setModules(version, id3Tag, presets)
        mpgLib.setModules(mpgInterface, common)
        mpgInterface.setModules(vbrTag, common)

        gfp = lame.lame_init()
        gfp.num_channels = channels
        gfp.in_samplerate = sampleRate
        gfp.brate = bitrateKbps
        gfp.quality = 2
        gfp.mode = if (channels == 1) MPEGMode.MONO else MPEGMode.JOINT_STEREO
        gfp.write_id3tag_automatic = false
        gfp.findReplayGain = false

        val result = lame.lame_init_params(gfp)
        check(result >= 0) { "LAME rejected the encoder parameters ($result)" }
        // Keep the module references strongly reachable while encoding.
        modules = listOf(
            gainAnalysis, bitStream, presets, quantizePvt, quantize, vbrTag,
            version, id3Tag, reservoir, takehiro, parse, brHist, mpgLib,
            mpgInterface, common, getAudio
        )
    }

    val channels: Int get() = gfp.num_channels
    val sampleRate: Int get() = gfp.in_samplerate

    /**
     * Encodes interleaved signed 16-bit PCM.
     *
     * @return the number of MP3 bytes written to [out].
     */
    fun encode(pcm: ShortArray, frameCount: Int, out: ByteArray): Int {
        if (frameCount <= 0) return 0
        val left = IntArray(frameCount)
        val right = IntArray(frameCount)
        if (channels == 1) {
            for (i in 0 until frameCount) {
                val sample = pcm[i].toInt() shl 16
                left[i] = sample
                right[i] = sample
            }
        } else {
            for (i in 0 until frameCount) {
                left[i] = pcm[i * 2].toInt() shl 16
                right[i] = pcm[i * 2 + 1].toInt() shl 16
            }
        }
        val written = lame.lame_encode_buffer_int(gfp, left, right, frameCount, out, 0, out.size)
        check(written >= 0) { "MP3 encoding failed ($written)" }
        return written
    }

    /** Flushes the encoder's internal frames. */
    fun finish(out: ByteArray): Int {
        val written = lame.lame_encode_flush(gfp, out, 0, out.size)
        check(written >= 0) { "MP3 flush failed ($written)" }
        return written
    }

    fun close() {
        runCatching { lame.lame_close(gfp) }
    }

    companion object {
        const val DEFAULT_BITRATE_KBPS = 192
        const val MAX_FRAMES_PER_CALL = 1152
        const val OUTPUT_BUFFER_SIZE = (MAX_FRAMES_PER_CALL * 2 * 5) + 7200

        fun suggestedOutputSize(frames: Int): Int = max(OUTPUT_BUFFER_SIZE, frames * 6 + 7200)
    }
}
