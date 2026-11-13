package com.cappielloantonio.tempo.ui.jpod

import com.cappielloantonio.tempo.subsonic.base.ApiResponse
import com.cappielloantonio.tempo.subsonic.models.ArtistID3
import com.cappielloantonio.tempo.subsonic.models.Child
import com.cappielloantonio.tempo.subsonic.models.SubsonicResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import retrofit2.http.GET
import retrofit2.http.Query
import retrofit2.http.QueryMap
import kotlin.coroutines.coroutineContext

/** Uses Tempo's authenticated Retrofit client; no separate account or music provider. */
interface LibraryBrowseService {
    @GET("getArtists")
    suspend fun artists(@QueryMap params: Map<String, String>): ApiResponse

    @GET("search3")
    suspend fun songs(@QueryMap params: Map<String, String>, @Query("query") query: String,
                      @Query("songCount") count: Int, @Query("songOffset") offset: Int,
                      @Query("artistCount") artists: Int = 0, @Query("albumCount") albums: Int = 0): ApiResponse

    @GET("getArtist")
    suspend fun artist(@QueryMap params: Map<String, String>, @Query("id") id: String): ApiResponse

    @GET("getAlbum")
    suspend fun album(@QueryMap params: Map<String, String>, @Query("id") id: String): ApiResponse
}

class LibraryBrowseRepository(private val api: LibraryBrowseService, private val params: Map<String, String>) {
    private fun ApiResponse.checked(): SubsonicResponse = subsonicResponse.also {
        check(it.status == "ok" && it.error == null) { "Library request failed" }
    }

    suspend fun artists(): List<ArtistID3> = api.artists(params).checked().artists?.indices.orEmpty()
        .flatMap { it.artists.orEmpty() }.filter { !it.id.isNullOrBlank() }.distinctBy { it.id }

    suspend fun songs(onPage: (List<Child>) -> Unit) {
        var offset = 0
        val seen = HashSet<String>()
        while (true) {
            coroutineContext.ensureActive()
            // Navidrome supports an empty query for enumerating the entire catalogue.
            val response = api.songs(params, "", 500, offset).checked()
            checkNotNull(response.searchResult3) { "Missing song catalogue" }
            val page = response.searchResult3?.songs.orEmpty()
            if (page.isEmpty()) return
            val fresh = page.filter { seen.add(it.id) }
            // A server ignoring songOffset must not loop forever or imply completeness.
            check(fresh.isNotEmpty()) { "Server did not advance the song catalogue" }
            onPage(fresh)
            offset += page.size
            // Do not stop on short pages: server-side caps may be smaller than 500.
        }
    }

    suspend fun artistSongs(id: String, onPage: (List<Child>) -> Unit) = coroutineScope {
        val artist = checkNotNull(api.artist(params, id).checked().artist) { "Missing artist" }
        val albums = artist.albums.orEmpty().mapNotNull { it.id }.distinct()
        val seen = HashSet<String>()
        // Fetch all albums, not getTopSongs (which intentionally returns a limited sample).
        // Limit concurrent requests to four so a large discography does not flood the Pi.
        for (batch in albums.chunked(4)) {
            ensureActive()
            val tracks = batch.map { albumId -> async {
                checkNotNull(api.album(params, albumId).checked().album) { "Missing album" }.songs.orEmpty()
            } }.awaitAll().flatten().filter { seen.add(it.id) }
            onPage(tracks)
        }
    }
}
