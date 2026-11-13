package com.cappielloantonio.tempo.ui.jpod

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cappielloantonio.tempo.App
import com.cappielloantonio.tempo.subsonic.RetrofitClient
import com.cappielloantonio.tempo.subsonic.models.ArtistID3
import com.cappielloantonio.tempo.subsonic.models.Child
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class LibraryBrowseState(
    val artists: List<ArtistID3> = emptyList(), val songs: List<Child> = emptyList(),
    val loading: Boolean = true, val failed: Boolean = false
)

class LibraryBrowseViewModel : ViewModel() {
    private var load: Job? = null
    private var started = false
    private val data = MutableStateFlow(LibraryBrowseState())
    internal val content = data.asStateFlow()

    internal fun start(artists: Boolean, artistId: String?, refresh: Boolean = false) {
        if (started && !refresh) return
        started = true
        load?.cancel()
        load = viewModelScope.launch {
            data.value = LibraryBrowseState()
            try {
                val subsonic = App.getSubsonicClientInstance(false)
                val repository = LibraryBrowseRepository(
                    RetrofitClient(subsonic).retrofit.create(LibraryBrowseService::class.java), subsonic.params)
                when {
                    artists -> data.value = data.value.copy(artists = repository.artists())
                    artistId != null -> repository.artistSongs(artistId) { page ->
                        data.value = data.value.copy(songs = data.value.songs + page)
                    }
                    else -> repository.songs { page ->
                        data.value = data.value.copy(songs = data.value.songs + page)
                    }
                }
                data.value = data.value.copy(loading = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep loaded pages playable, but explicitly indicate incompleteness.
                data.value = data.value.copy(loading = false, failed = true)
            }
        }
    }
}
