package com.music.spotui.soulseek

/**
 * One file match returned by a peer in a Soulseek [SoulseekClient] search.
 * Attributes (bitrate/duration/sampleRate/bitDepth) are optional and depend on
 * what the sharing client reported; FLAC shares usually carry sample rate + bit
 * depth but no bitrate.
 */
data class SlskFile(
    val username: String,
    val filename: String,
    val size: Long,
    val bitrate: Int = 0,
    val durationSec: Int = 0,
    val sampleRate: Int = 0,
    val bitDepth: Int = 0,
    val slotsFree: Boolean = false,
    val avgSpeed: Int = 0,
    val queueLength: Long = 0,
) {
    val ext: String get() = filename.substringAfterLast('.', "").lowercase()
    val isFlac: Boolean get() = ext == "flac"
    val displayName: String get() = filename.substringAfterLast('\\').substringAfterLast('/')

    /**
     * Higher is better. Prefers a free upload slot (no queue wait), then lossless
     * quality (bit depth / sample rate), then faster peers and shorter queues.
     */
    fun score(): Long {
        var s = 0L
        if (slotsFree) s += 1_000_000
        s += bitDepth.toLong() * 20_000          // 24-bit beats 16-bit
        s += (sampleRate / 1000).toLong() * 200  // 96kHz beats 44.1kHz
        s += (avgSpeed / 1000).toLong()          // faster uploader
        s -= queueLength * 50                     // shorter queue
        return s
    }
}
