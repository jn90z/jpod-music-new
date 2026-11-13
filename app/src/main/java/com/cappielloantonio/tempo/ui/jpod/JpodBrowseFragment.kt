package com.cappielloantonio.tempo.ui.jpod

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.service.MediaManager
import com.cappielloantonio.tempo.ui.activity.MainActivity
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.MusicUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@UnstableApi
class JpodBrowseFragment : Fragment() {
    companion object {
        const val MODE = "browse_mode"
        const val ARTISTS = "artists"
        const val ARTIST_ID = "browse_artist_id"
        const val ARTIST_NAME = "browse_artist_name"
    }
    private val activity get() = requireActivity() as MainActivity

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        ComposeView(requireContext()).apply {
            id = R.id.jpod_browse_compose_view
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }

    override fun onStart() {
        super.onStart()
        activity.setBottomNavigationBarVisibility(true)
        activity.setBottomSheetVisibility(true)
    }

    @OptIn(ExperimentalFoundationApi::class)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val artistsMode = arguments?.getString(MODE) == ARTISTS
        val artistId = arguments?.getString(ARTIST_ID)
        val model = ViewModelProvider(this)[LibraryBrowseViewModel::class.java]
        model.start(artistsMode, artistId)
        (view as ComposeView).setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF1ED760),
                background = Color(0xFF0B0B0F), surface = Color(0xFF0B0B0F))) {
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    val state by model.content.collectAsState()
                    var query by rememberSaveable { mutableStateOf("") }
                    // Filtering/sorting stays off the UI thread even for a very large library.
                    val artists by produceState(initialValue = emptyList<com.cappielloantonio.tempo.subsonic.models.ArtistID3>(), state.artists, query) {
                        if (query.isNotEmpty()) delay(150)
                        value = withContext(Dispatchers.Default) { LibraryBrowseFilter.artists(state.artists, query) }
                    }
                    val songs by produceState(initialValue = emptyList<com.cappielloantonio.tempo.subsonic.models.Child>(), state.songs, query) {
                        if (query.isNotEmpty()) delay(150)
                        value = withContext(Dispatchers.Default) { LibraryBrowseFilter.songs(state.songs, query) }
                    }
                    val scroll = rememberLazyListState()
                    val scope = rememberCoroutineScope()
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                        .padding(bottom = 160.dp)) {
                        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { activity.navController.navigateUp() }) {
                                Text(stringResource(R.string.jpod_back))
                            }
                            Text(if (artistsMode) stringResource(R.string.jpod_artists) else
                                arguments?.getString(ARTIST_NAME) ?: stringResource(R.string.jpod_songs),
                                Modifier.weight(1f), fontSize = 24.sp, fontWeight = FontWeight.Bold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { model.start(artistsMode, artistId, refresh = true) }) {
                                Text(stringResource(R.string.jpod_reload))
                            }
                        }
                        OutlinedTextField(value = query, onValueChange = { query = it; scope.launch { scroll.scrollToItem(0) } }, singleLine = true,
                            label = { Text(stringResource(if (artistsMode) R.string.jpod_search_artists else R.string.jpod_search_songs)) },
                            trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = ""; scope.launch { scroll.scrollToItem(0) } }) {
                                Text(stringResource(R.string.jpod_clear))
                            } }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                        val count = if (artistsMode) artists.size else songs.size
                        Text(resources.getQuantityString(if (artistsMode) R.plurals.jpod_artist_count else R.plurals.jpod_song_count, count, count),
                            Modifier.padding(horizontal = 20.dp, vertical = 10.dp), color = Color(0xFFB3B3B3), fontSize = 13.sp)
                        if (state.loading) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(stringResource(R.string.jpod_loading_library), Modifier.padding(horizontal = 20.dp, vertical = 8.dp), fontSize = 13.sp)
                        }
                        if (state.failed) {
                            Text(stringResource(R.string.jpod_browse_failed), Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                                color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { model.start(artistsMode, artistId, refresh = true) }) {
                                Text(stringResource(R.string.jpod_retry))
                            }
                        }
                        if (!state.loading && !state.failed && count == 0) {
                            Text(stringResource(if (query.isNotBlank()) R.string.jpod_no_matches else R.string.jpod_empty_library),
                                Modifier.padding(24.dp))
                        }
                        LazyColumn(state = scroll, modifier = Modifier.weight(1f).fillMaxWidth()) {
                            if (artistsMode) {
                                items(artists, key = { it.id!! }) { artist ->
                                    Column(Modifier.fillMaxWidth().clickable {
                                        activity.navController.navigate(R.id.jpodBrowseFragment, Bundle().apply {
                                            putString(ARTIST_ID, artist.id)
                                            putString(ARTIST_NAME, artist.name)
                                        })
                                    }.padding(horizontal = 20.dp, vertical = 16.dp)) {
                                        Text(artist.name.orEmpty(), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            } else {
                                items(songs, key = { it.id }) { song ->
                                    Column(Modifier.fillMaxWidth().combinedClickable(onClick = {
                                        // Start at the chosen row in the visible, filtered list.
                                        val queue = ArrayList(songs)
                                        MusicUtil.ratingFilter(queue)
                                        val position = queue.indexOfFirst { it.id == song.id }
                                        if (position >= 0) {
                                            MediaManager.startQueue(activity.mediaBrowserListenableFuture, queue, position)
                                            activity.setBottomSheetInPeek(true)
                                        }
                                    }, onLongClick = {
                                        activity.navController.navigate(R.id.songBottomSheetDialog, Bundle().apply {
                                            putParcelable(Constants.TRACK_OBJECT, song)
                                        })
                                    }).padding(horizontal = 20.dp, vertical = 12.dp)) {
                                        Text(song.title.orEmpty(), fontSize = 16.sp, fontWeight = FontWeight.Bold,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(listOfNotNull(song.artist, song.album).joinToString(" • "),
                                            color = Color(0xFFB3B3B3), fontSize = 13.sp,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
