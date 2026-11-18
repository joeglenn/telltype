package net.jbglenn.telltype.debug

import android.content.res.AssetManager
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A bundled speech clip as samples: by default `sample/phoebe.wav` (7.4 s, 16 kHz mono 16-bit; Parakeet's test
 * clip `0.wav`), whose transcript is [TRANSCRIPT]; the feature tests' synthetic clips sit beside it.
 */
internal class SampleClip private constructor(val sampleRateHz: Int, val samples: ShortArray) {
    companion object {
        /** Asset path of the clip. */
        const val ASSET = "sample/phoebe.wav"

        /** What the server's speech model returns for the clip. */
        const val TRANSCRIPT =
            "Well, I don't wish to see it any more, observed Phoebe, turning away her eyes. " +
                "It is certainly very like the old portrait"

        /** Asset directory of the feature clips (`android/scripts/make-feature-clips.py`). */
        private const val CLIP_DIR = "sample"

        /** Clip names: lower-case letters, digits and dashes. */
        private val ClipName = Regex("[a-z0-9-]+")

        /**
         * The asset the clip marker file's [content] names: `sample/<name>.wav` for a clip name (for example
         * `snippet`), [ASSET] for no content, `on` or anything that is not a clip name.
         */
        fun assetFor(content: String?): String {
            val name = content?.trim().orEmpty()
            return if (name != "on" && ClipName.matches(name)) "$CLIP_DIR/$name.wav" else ASSET
        }

        /** Reads the clip at [asset]; null when it is not a mono 16-bit PCM WAV file. */
        fun load(assets: AssetManager, asset: String = ASSET): SampleClip? =
            parse(assets.open(asset).use { it.readBytes() })

        private fun parse(bytes: ByteArray): SampleClip? {
            val wav = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var offset = RIFF_HEADER
            var rate = 0
            var mono16 = false
            while (offset + CHUNK_HEADER <= bytes.size) {
                val id = String(bytes, offset, ID_LENGTH, Charsets.US_ASCII)
                val size = wav.getInt(offset + ID_LENGTH)
                val body = offset + CHUNK_HEADER
                when (id) {
                    "fmt " -> {
                        rate = wav.getInt(body + RATE_OFFSET)
                        mono16 = wav.getShort(body + CHANNELS_OFFSET).toInt() == 1 &&
                            wav.getShort(body + BITS_OFFSET).toInt() == BITS
                    }

                    "data" -> {
                        if (!mono16) return null
                        val samples = ShortArray(minOf(size, bytes.size - body) / 2)
                        wav.position(body)
                        wav.asShortBuffer().get(samples)
                        return SampleClip(rate, samples)
                    }
                }
                offset = body + size + size % 2
            }
            return null
        }

        private const val RIFF_HEADER = 12
        private const val CHUNK_HEADER = 8
        private const val ID_LENGTH = 4
        private const val CHANNELS_OFFSET = 2
        private const val RATE_OFFSET = 4
        private const val BITS_OFFSET = 14
        private const val BITS = 16
    }
}
