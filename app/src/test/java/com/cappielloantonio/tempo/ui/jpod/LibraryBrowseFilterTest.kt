package com.cappielloantonio.tempo.ui.jpod

import com.cappielloantonio.tempo.subsonic.models.ArtistID3
import com.cappielloantonio.tempo.subsonic.models.Child
import org.junit.Assert.*
import org.junit.Test

class LibraryBrowseFilterTest {
    private fun artist(id: String, name: String) = ArtistID3().apply { this.id = id; this.name = name }

    @Test fun artistSearchIsCaseInsensitiveAndTrimsWhitespace() {
        val input = listOf(artist("1", "Zulu"), artist("2", "abba"), artist("3", "ABBA"))
        assertEquals(listOf("2", "3"), LibraryBrowseFilter.artists(input, "  ABB  ").map { it.id })
        assertEquals(listOf("2", "3", "1"), LibraryBrowseFilter.artists(input, "").map { it.id })
    }

    @Test fun songsMatchTitleArtistOrAlbumAndClearRestoresWholeList() {
        val input = listOf(Child("1", title = "Zulu", artist = "Prince", album = "Purple Rain"),
            Child("2", title = "Alpha", artist = "Other", album = "Different"))
        for (query in listOf("zULu", "prince", "  PURPLE  ")) {
            assertEquals(listOf("1"), LibraryBrowseFilter.songs(input, query).map { it.id })
        }
        assertTrue(LibraryBrowseFilter.songs(input, "no matches").isEmpty())
        assertEquals(listOf("2", "1"), LibraryBrowseFilter.songs(input, "").map { it.id })
    }

    @Test fun duplicateIdsAndMissingNamesCannotCrashArtistList() {
        val input = listOf(ArtistID3(), artist("1", "Artist"), artist("1", "Artist"))
        assertEquals(listOf("1"), LibraryBrowseFilter.artists(input, "").map { it.id })
    }
}
