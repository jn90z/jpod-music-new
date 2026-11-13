package com.cappielloantonio.tempo.ui.jpod

import com.cappielloantonio.tempo.subsonic.models.Child
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

class LibraryBrowseRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: LibraryBrowseRepository
    private fun ok(content: String) = MockResponse().setBody("""{"subsonic-response":{"status":"ok",$content}}""")

    @Before fun setup() {
        server = MockWebServer()
        server.start()
        val service = Retrofit.Builder().baseUrl(server.url("/rest/"))
            .addConverterFactory(GsonConverterFactory.create()).build().create(LibraryBrowseService::class.java)
        repository = LibraryBrowseRepository(service, mapOf("u" to "test-user", "t" to "test-token", "s" to "test-salt"))
    }
    @After fun teardown() { server.shutdown() }

    @Test fun paginatesPastShortPagesAndPreservesAuthentication() = runBlocking {
        server.enqueue(ok(""""searchResult3":{"song":[{"id":"1","title":"A"},{"id":"2","title":"B"}]}"""))
        server.enqueue(ok(""""searchResult3":{"song":[{"id":"3","title":"C"}]}"""))
        server.enqueue(ok(""""searchResult3":{}"""))
        val songs = mutableListOf<Child>()
        repository.songs { songs.addAll(it) }
        assertEquals(listOf("1", "2", "3"), songs.map { it.id })
        for (offset in listOf("0", "2", "3")) {
            val url = server.takeRequest().requestUrl!!
            assertEquals("/rest/search3", url.encodedPath)
            assertEquals(offset, url.queryParameter("songOffset"))
            assertEquals("", url.queryParameter("query"))
            assertEquals("0", url.queryParameter("artistCount"))
            assertEquals("test-user", url.queryParameter("u"))
            assertEquals("test-token", url.queryParameter("t"))
        }
    }

    @Test fun loadsSongsBeyondTheFirstFiveHundred() = runBlocking {
        val first = (1..500).joinToString { "{\"id\":\"$it\"}" }
        server.enqueue(ok("\"searchResult3\":{\"song\":[$first]}"))
        server.enqueue(ok("\"searchResult3\":{\"song\":[{\"id\":\"501\"}]}"))
        server.enqueue(ok("\"searchResult3\":{}"))
        val loaded = mutableListOf<Child>()
        repository.songs { loaded.addAll(it) }
        assertEquals(501, loaded.size)
        assertEquals("501", loaded.last().id)
        assertEquals(listOf("0", "500", "501"), (1..3).map { server.takeRequest().requestUrl!!.queryParameter("songOffset") })
    }

    @Test fun doesNotLoopForeverWhenServerIgnoresOffset() = runBlocking {
        repeat(2) { server.enqueue(ok(""""searchResult3":{"song":[{"id":"1"}]}""")) }
        val loaded = mutableListOf<Child>()
        try {
            repository.songs { loaded.addAll(it) }
            fail("Repeated page must be reported as incomplete")
        } catch (_: IllegalStateException) {
            assertEquals(listOf("1"), loaded.map { it.id })
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun failedSubsonicResponseDoesNotMasqueradeAsEmptyLibrary() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"subsonic-response":{"status":"failed","error":{"code":40,"message":"Wrong credentials"}}}"""))
        try {
            repository.songs { fail("Failed response must not emit a successful page") }
            fail("Expected server failure")
        } catch (_: IllegalStateException) {
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun loadsEveryArtistAlbumAndDeduplicatesSongIds() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                return when (url.encodedPath) {
                    "/rest/getArtist" -> ok(""""artist":{"id":"artist-id","album":[${(1..6).joinToString { "{\"id\":\"a$it\"}" }}]}""")
                    "/rest/getAlbum" -> ok(""""album":{"id":"${url.queryParameter("id")}","song":[{"id":"shared"},{"id":"${url.queryParameter("id")}-song"}]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val songs = mutableListOf<Child>()
        repository.artistSongs("artist-id") { songs.addAll(it) }
        assertEquals(7, songs.size)
        assertEquals(1, songs.count { it.id == "shared" })
        assertTrue((1..6).all { n -> songs.any { it.id == "a$n-song" } })
        val requests = (1..7).map { server.takeRequest().requestUrl!! }
        assertEquals("artist-id", requests.first().queryParameter("id"))
        assertEquals((1..6).map { "a$it" }.toSet(), requests.drop(1).map { it.queryParameter("id") }.toSet())
    }

    @Test fun flattensAllArtistIndexGroupsWithoutDuplicateIds() = runBlocking {
        server.enqueue(ok(""""artists":{"index":[{"name":"A","artist":[{"id":"1","name":"Alpha"}]},{"name":"B","artist":[{"id":"2","name":"Beta"},{"id":"1","name":"Alpha"}]}]}"""))
        assertEquals(listOf("1", "2"), repository.artists().map { it.id })
    }

    @Test fun artistAlbumFailureIsReportedInsteadOfSilentlySkippingTracks() = runBlocking {
        server.enqueue(ok(""""artist":{"id":"a","album":[{"id":"one"}]}"""))
        server.enqueue(MockResponse().setResponseCode(503))
        try {
            repository.artistSongs("a") { fail("Unavailable album must not be silently skipped") }
            fail("Expected HTTP failure")
        } catch (expected: retrofit2.HttpException) {
            assertEquals(503, expected.code())
        }
    }
}
