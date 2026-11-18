package net.jbglenn.telltype.network.check

/** A silent 16 kHz mono 16-bit PCM WAV, for checking a speech server that has no model list. */
internal object SilentWav {
    private const val SAMPLE_RATE = 16_000
    private const val BYTES_PER_SAMPLE = 2
    private const val HEADER_BYTES = 44
    private const val FMT_CHUNK_BYTES = 16
    private const val PCM = 1
    private const val MONO = 1
    private const val BITS = 16
    private const val RIFF_OVERHEAD = 36

    /** [seconds] of silence with its 44-byte header. */
    fun bytes(seconds: Int = 1): ByteArray {
        val data = SAMPLE_RATE * BYTES_PER_SAMPLE * seconds
        val out = java.nio.ByteBuffer.allocate(HEADER_BYTES + data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        out.put("RIFF".toByteArray()).putInt(RIFF_OVERHEAD + data).put("WAVE".toByteArray())
        out.put("fmt ".toByteArray()).putInt(FMT_CHUNK_BYTES).putShort(PCM.toShort()).putShort(MONO.toShort())
        out.putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * BYTES_PER_SAMPLE).putShort(BYTES_PER_SAMPLE.toShort())
        out.putShort(BITS.toShort())
        out.put("data".toByteArray()).putInt(data)
        return out.array()
    }
}
