package com.music.spotui.lossless

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Resolves a Spotify track to a lossless FLAC URL via SpotiFLAC's **public**
 * community backends — plain HTTP calls with a shared, published API key. No
 * login, no CAPTCHA, no per-user credentials.
 *
 * Providers are tried in priority order and each fails gracefully (a down or
 * account-limited backend just falls through to the next, then to YouTube):
 *   1. Tidal  — Spotify→Tidal id (the backend's own search) → `/track/?id=` → FLAC url
 *   2. Amazon — `/api/resolve/spotify/{id}` (self-resolving)   [often dormant]
 *
 * Odesli (song.link), which used to map Spotify ids to Tidal/Qobuz ids, closed its
 * public API (401 PUBLIC_API_ACCESS_DEPRECATED), so Qobuz (Odesli-only ids) is gone.
 * Endpoint lists come from [LosslessRegistry] so they follow the community's rotations.
 */
object LosslessSource {

    private const val TAG = "LosslessSource"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    data class FlacTrack(val url: String, val provider: String, val quality: String)

    sealed interface Result {
        data class Success(val track: FlacTrack) : Result
        data object NotFound : Result
        data class Error(val message: String) : Result
    }

    /** Track details from the play site; [title] blank means "ask Spotify". */
    data class TrackInfo(val title: String = "", val artist: String = "", val durationSec: Int = 0, val isrc: String = "")

    suspend fun resolve(
        context: Context,
        spotifyId: String,
        info: TrackInfo = TrackInfo(),
        preferHiRes: Boolean = true,
    ): Result =
        withContext(Dispatchers.IO) {
            // 1. Tidal — SpotiFLAC's gated backend (if verified) then direct endpoints
            tidalHit(context, spotifyId, info)?.let { hit ->
                tidalFlac(context, hit, preferHiRes)?.let { return@withContext Result.Success(it) }
            } ?: Log.d(TAG, "no Tidal match for spotify:$spotifyId")
            // 2. Amazon (self-resolves from the Spotify id; usually dormant)
            amazonFlac(spotifyId)?.let { return@withContext Result.Success(it) }
            Result.NotFound
        }

    // ── Provider: Tidal ───────────────────────────────────────────────────────
    private fun tidalFlac(context: Context, hit: TidalHit, preferHiRes: Boolean): FlacTrack? {
        val tidalId = hit.id
        // SpotiFLAC's own gated backend first (if the user completed verification) —
        // it's their most reliable source. Falls through to the direct API-key ones.
        if (SpotiflacGated.hasSession(context)) {
            SpotiflacGated.tidalFlacUrl(context, tidalId, "LOSSLESS")?.takeIf { isFullTrack(it, hit.durationSec) }?.let {
                Log.d(TAG, "SpotiFLAC gated FLAC for tidal:$tidalId")
                return FlacTrack(it, "SpotiFLAC", "16")
            }
        }
        // LOSSLESS (16-bit) is a single-file FLAC and the most reliable/fastest —
        // request only it. HI_RES usually comes back as a multi-segment manifest we
        // can't hand to the player as one URL anyway, and the extra slow call just
        // burns the timeout budget on these flaky community servers.
        val qualities = listOf("LOSSLESS")
        for (endpoint in LosslessRegistry.tidalEndpoints()) {
            for (q in qualities) {
                val track = runCatching {
                    val body = get("$endpoint/track/?id=$tidalId&quality=$q", keyHeader())
                    val data = JSONObject(body).optJSONObject("data") ?: return@runCatching null
                    val manifest = decodeBtsManifest(data.optString("manifest")) ?: return@runCatching null
                    val urls = manifest.optJSONArray("urls") ?: return@runCatching null
                    // Single-file FLAC only — a multi-segment manifest can't be one URL.
                    if (urls.length() != 1) return@runCatching null
                    val flacUrl = urls.optString(0).takeIf { it.isNotBlank() } ?: return@runCatching null
                    if (!manifest.optString("mimeType").contains("flac", true) && !flacUrl.contains(".flac")) {
                        return@runCatching null
                    }
                    val bits = data.optInt("bitDepth", 16).let { if (it in 8..32) it else 16 }
                    FlacTrack(flacUrl, "Tidal", bits.toString())
                }.getOrNull()
                if (track != null && isFullTrack(track.url, hit.durationSec)) {
                    Log.d(TAG, "Tidal ${track.quality}-bit via $endpoint (tidal:$tidalId)")
                    return track
                }
            }
        }
        return null
    }

    // ── Provider: Amazon (self-resolving; skips encrypted responses) ──────────
    private fun amazonFlac(spotifyId: String): FlacTrack? {
        for (endpoint in LosslessRegistry.amazonEndpoints()) {
            val track = runCatching {
                val body = get("$endpoint/api/resolve/spotify/$spotifyId", keyHeader())
                val json = JSONObject(body)
                val data = json.optJSONObject("data") ?: json
                // Only accept a directly-playable url with NO decryption key — we
                // don't ship Amazon's stream decryption, so encrypted responses are
                // skipped rather than saved as garbled audio.
                if (data.has("decryption_key") || json.has("decryption_key")) return@runCatching null
                val url = firstNonBlank(
                    data.optString("url"), data.optString("stream_url"),
                    data.optString("download_url"), data.optString("streamUrl"),
                )
                if (url.isBlank() || !url.startsWith("http")) return@runCatching null
                if (!url.contains("flac", true)) return@runCatching null
                FlacTrack(url, "Amazon", "16")
            }.getOrNull()
            if (track != null) {
                Log.d(TAG, "Amazon FLAC via $endpoint (spotify:$spotifyId)")
                return track
            }
        }
        return null
    }

    // ── Shared helpers ────────────────────────────────────────────────────────

    internal data class TidalHit(
        val id: String, val title: String, val artist: String, val durationSec: Int, val isrc: String,
    )

    /** Spotify id → Tidal track via the Tidal backend's search, matched against the track's details. */
    private suspend fun tidalHit(context: Context, spotifyId: String, known: TrackInfo): TidalHit? {
        // The play site usually knows the track; Spotify's REST track lookup is often
        // rate limited (429) for the web token, so it is only the fallback.
        val info = known.takeIf { it.title.isNotBlank() && it.artist.isNotBlank() } ?: run {
            if (!com.music.spotui.data.api.SpotifyTokenProvider.ensureToken(context)) return null
            val t = com.metrolist.spotify.Spotify.track(spotifyId).getOrNull() ?: return null
            TrackInfo(t.name, t.artists.firstOrNull()?.name.orEmpty(), t.durationMs / 1000, t.isrc.orEmpty())
        }
        val q = URLEncoder.encode("${info.title} ${info.artist}", "UTF-8")
        for (endpoint in LosslessRegistry.tidalEndpoints()) {
            val hits = runCatching {
                val items = JSONObject(get("$endpoint/search/?s=$q", keyHeader()))
                    .optJSONObject("data")?.optJSONArray("items") ?: return@runCatching emptyList()
                (0 until items.length()).mapNotNull { i ->
                    val o = items.optJSONObject(i) ?: return@mapNotNull null
                    TidalHit(
                        id = o.optString("id"), title = o.optString("title"),
                        artist = o.optJSONObject("artist")?.optString("name").orEmpty(),
                        durationSec = o.optInt("duration"), isrc = o.optString("isrc"),
                    )
                }
            }.getOrDefault(emptyList())
            pickTidalHit(hits, info.isrc, info.title, info.artist, info.durationSec)?.let { return it }
        }
        return null
    }

    /**
     * Same recording by ISRC if Spotify has one; otherwise same title and artist with
     * a length within 3 s, so a live version or a cover is never streamed instead.
     */
    internal fun pickTidalHit(hits: List<TidalHit>, isrc: String, title: String, artist: String, durationSec: Int): TidalHit? {
        fun key(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        hits.firstOrNull { isrc.isNotBlank() && it.isrc.equals(isrc, ignoreCase = true) && it.id.isNotBlank() }?.let { return it }
        return hits.firstOrNull {
            it.id.isNotBlank() && key(it.title) == key(title) && key(it.artist) == key(artist) &&
                (durationSec <= 0 || kotlin.math.abs(it.durationSec - durationSec) <= 3)
        }
    }

    /**
     * The backends sometimes hand out a 30 s preview instead of the song; played as
     * the real track it ends early and the queue skips ahead every 30 s. A FLAC file
     * states its length in STREAMINFO (first 42 bytes), so one tiny range read tells.
     */
    private fun isFullTrack(url: String, expectedSec: Int): Boolean {
        if (expectedSec <= 0) return true
        val seconds = runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Range", "bytes=0-41")
            }
            val head = try {
                conn.inputStream.use { it.readNBytes(42) }
            } finally {
                conn.disconnect()
            }
            flacDurationSec(head)
        }.getOrNull() ?: return true // not a plain FLAC header: can't tell, keep it
        val full = seconds >= expectedSec - 5
        if (!full) Log.d(TAG, "rejected ${seconds}s preview (expected ${expectedSec}s)")
        return full
    }

    /** Length from a FLAC STREAMINFO header, or null if [head] is not one. */
    internal fun flacDurationSec(head: ByteArray): Int? {
        if (head.size < 26 || String(head, 0, 4, Charsets.US_ASCII) != "fLaC") return null
        // Bytes 18..25: sample rate (20 bits), channels (3), bits/sample (5), total samples (36).
        var bits = 0L
        for (i in 18..25) bits = (bits shl 8) or (head[i].toLong() and 0xFF)
        val sampleRate = (bits ushr 44).toInt()
        val totalSamples = bits and 0xFFFFFFFFFL
        if (sampleRate <= 0 || totalSamples <= 0) return null
        return (totalSamples / sampleRate).toInt()
    }

    private fun decodeBtsManifest(b64: String): JSONObject? {
        if (b64.isBlank()) return null
        return runCatching {
            JSONObject(String(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), Charsets.UTF_8))
        }.getOrNull()
    }

    private fun keyHeader() = mapOf("X-API-Key" to LosslessRegistry.API_KEY)

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() }.orEmpty()

    private fun get(urlString: String, headers: Map<String, String>): String {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json, text/plain, */*")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return try {
            conn.connect()
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw java.io.IOException("HTTP $code: ${text.take(120)}")
            text
        } finally {
            conn.disconnect()
        }
    }
}
