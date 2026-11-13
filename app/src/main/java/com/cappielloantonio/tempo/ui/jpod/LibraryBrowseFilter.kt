package com.cappielloantonio.tempo.ui.jpod

import com.cappielloantonio.tempo.subsonic.models.ArtistID3
import com.cappielloantonio.tempo.subsonic.models.Child

/** Pure filtering shared by the screens; query changes never issue network requests. */
internal object LibraryBrowseFilter {
    fun artists(all: List<ArtistID3>, query: String): List<ArtistID3> {
        val term = query.trim()
        return all.asSequence().filter { !it.id.isNullOrBlank() }
            .distinctBy { it.id }.filter { it.name.orEmpty().contains(term, ignoreCase = true) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name.orEmpty() }).toList()
    }

    fun songs(all: List<Child>, query: String): List<Child> {
        val term = query.trim()
        return all.asSequence().distinctBy { it.id }.filter {
            it.title.orEmpty().contains(term, ignoreCase = true) ||
                it.artist.orEmpty().contains(term, ignoreCase = true) ||
                it.album.orEmpty().contains(term, ignoreCase = true)
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title.orEmpty() }).toList()
    }
}
