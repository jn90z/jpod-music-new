/*
 * J-Pod home: layout adapted from Spotui's HomeScreen.kt (GPL-3.0).
 * Upstream: Spotui/Spotui @ 2502236aa5ed69b10bb70324beb3900bfc0e5b3f.
 * All data, artwork and playback use Tempo's Navidrome/Subsonic infrastructure.
 */
package com.cappielloantonio.tempo.ui.jpod

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.util.UnstableApi
import com.bumptech.glide.Glide
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.glide.CustomGlideRequest
import com.cappielloantonio.tempo.service.MediaManager
import com.cappielloantonio.tempo.subsonic.models.AlbumID3
import com.cappielloantonio.tempo.subsonic.models.ArtistID3
import com.cappielloantonio.tempo.subsonic.models.Child
import com.cappielloantonio.tempo.subsonic.models.Playlist
import com.cappielloantonio.tempo.ui.activity.MainActivity
import com.cappielloantonio.tempo.util.Constants
import com.cappielloantonio.tempo.util.MusicUtil
import com.cappielloantonio.tempo.util.Preferences
import com.cappielloantonio.tempo.viewmodel.HomeViewModel

private val Background = Color(0xFF0B0B0F) // Spotui AppBackground
private val Tile = Color(0xFF2A2A2A) // Spotui GridBackground
private val Green = Color(0xFF1ED760)
private val Muted = Color(0xFFB3B3B3)
private val Inter = FontFamily(Font(R.font.inter_regular), Font(R.font.inter_bold, FontWeight.Bold))

@UnstableApi
class JpodHomeFragment : Fragment() {
    private val activity get() = requireActivity() as MainActivity
    private lateinit var model: HomeViewModel

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        model = ViewModelProvider(requireActivity())[HomeViewModel::class.java]
        // Acquire each LiveData once, outside composition. Tempo owns credentials,
        // caching and server switching; no parallel authentication layer is added.
        val recent = model.getRecentlyPlayedAlbumList(viewLifecycleOwner)
        val added = model.getMostRecentlyAddedAlbums(viewLifecycleOwner)
        val frequent = model.getMostPlayedAlbums(viewLifecycleOwner)
        val artists = model.getStarredArtists(viewLifecycleOwner)
        val songs = model.getDiscoverSongSample(viewLifecycleOwner)
        val playlists = model.getPinnedPlaylists(viewLifecycleOwner)
        (view as ComposeView).setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Green, onPrimary = Color.Black, background = Background,
                surface = Background, onSurface = Color.White, onBackground = Color.White
            )) {
                ProvideTextStyle(LocalTextStyle.current.copy(fontFamily = Inter)) {
                    Feed(recent, added, frequent, artists, songs, playlists)
                }
            }
        }
    }

    private fun navigate(id: Int, args: Bundle? = null) = activity.navController.navigate(id, args)
    private fun album(album: AlbumID3, options: Boolean = false) = navigate(
        if (options) R.id.albumBottomSheetDialog else R.id.albumPageFragment,
        Bundle().apply { putParcelable(Constants.ALBUM_OBJECT, album) }
    )
    private fun songOptions(song: Child) = navigate(R.id.songBottomSheetDialog,
        Bundle().apply { putParcelable(Constants.TRACK_OBJECT, song) })
    private fun likedSongs() = navigate(R.id.songListPageFragment,
        Bundle().apply { putString(Constants.MEDIA_STARRED, Constants.MEDIA_STARRED) })
    private fun history() = navigate(R.id.albumListPageFragment,
        Bundle().apply { putString(Constants.ALBUM_RECENTLY_PLAYED, Constants.ALBUM_RECENTLY_PLAYED) })
    private fun play(songs: List<Child>, index: Int = 0) {
        // Use a copy: the rating filter mutates its input, while Compose observes
        // the original LiveData value. The queue receives only permitted songs.
        val selected = songs.getOrNull(index) ?: return
        val queue = ArrayList(songs)
        MusicUtil.ratingFilter(queue)
        val position = queue.indexOfFirst { it.id == selected.id }
        if (position < 0) return
        MediaManager.startQueue(activity.mediaBrowserListenableFuture, queue, position)
        activity.setBottomSheetInPeek(true)
    }
    private fun refresh() {
        model.refreshRecentlyPlayedAlbumList(viewLifecycleOwner)
        model.refreshMostRecentlyAddedAlbums(viewLifecycleOwner)
        model.refreshMostPlayedAlbums(viewLifecycleOwner)
        model.refreshStarredArtists(viewLifecycleOwner)
        model.refreshDiscoverySongSample(viewLifecycleOwner)
        model.getPinnedPlaylists(viewLifecycleOwner)
    }

    @Composable
    private fun Feed(
        recentSource: LiveData<List<AlbumID3>>, addedSource: LiveData<List<AlbumID3>>,
        frequentSource: LiveData<List<AlbumID3>>, artistSource: LiveData<List<ArtistID3>>,
        songSource: LiveData<List<Child>>, playlistSource: LiveData<List<Playlist>>
    ) {
        val recent by recentSource.observeAsState()
        val added by addedSource.observeAsState()
        val frequent by frequentSource.observeAsState()
        val artists by artistSource.observeAsState()
        val songs by songSource.observeAsState()
        val playlists by playlistSource.observeAsState()
        LazyColumn(Modifier.fillMaxSize().background(Background),
            contentPadding = PaddingValues(bottom = 180.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val profileDescription = stringResource(R.string.jpod_profile)
                    val initial = Preferences.getUser()?.trim()?.firstOrNull()?.uppercase() ?: "J"
                    Box(Modifier.size(48.dp).clip(CircleShape).background(Color(0xFFE8622C))
                        .semantics { contentDescription = profileDescription }.clickable { navigate(R.id.settingsFragment) }, contentAlignment = Alignment.Center) {
                        Text(initial, color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                    Pill(stringResource(R.string.jpod_music), true) { navigate(R.id.libraryFragment) }
                    Pill(stringResource(R.string.jpod_history)) { history() }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { refresh() }) {
                        androidx.compose.foundation.Image(
                            androidx.compose.ui.res.painterResource(R.drawable.ic_refresh),
                            stringResource(R.string.jpod_refresh), Modifier.size(24.dp),
                            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.White))
                    }
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.jpod_your_music), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.jpod_server_caption), color = Muted, fontSize = 13.sp)
                }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Shortcut(stringResource(R.string.jpod_liked_songs), Modifier.weight(1f), Color(0xFF5038A0)) { likedSongs() }
                        Shortcut(stringResource(R.string.jpod_downloads), Modifier.weight(1f), Color(0xFF17684B)) { navigate(R.id.downloadFragment) }
                    }
                    recent.orEmpty().take(6).chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { value ->
                                Row(Modifier.weight(1f).clip(RoundedCornerShape(4.dp)).background(Tile)
                                    .clickable { album(value) }, verticalAlignment = Alignment.CenterVertically) {
                                    Artwork(value.coverArtId, Modifier.size(56.dp))
                                    Text(value.name.orEmpty(), Modifier.padding(8.dp), maxLines = 2,
                                        overflow = TextOverflow.Ellipsis, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            if (!playlists.isNullOrEmpty()) item {
                Section(stringResource(R.string.jpod_pinned_playlists)) {
                    items(playlists.orEmpty(), key = { it.id }) { value ->
                        CoverCard(value.name.orEmpty(), stringResource(R.string.jpod_playlist), value.coverArtId,
                            onClick = { navigate(R.id.playlistPageFragment,
                                Bundle().apply { putParcelable(Constants.PLAYLIST_OBJECT, value) }) })
                    }
                }
            }
            if (!recent.isNullOrEmpty()) item { Albums(stringResource(R.string.jpod_jump_back_in), recent.orEmpty()) }
            if (!added.isNullOrEmpty()) item { Albums(stringResource(R.string.jpod_recently_added), added.orEmpty()) }
            if (!frequent.isNullOrEmpty()) item { Albums(stringResource(R.string.jpod_on_repeat), frequent.orEmpty()) }
            if (!artists.isNullOrEmpty()) item {
                Section(stringResource(R.string.jpod_favorite_artists)) {
                    items(artists.orEmpty().filter { !it.id.isNullOrBlank() }.distinctBy { it.id }, key = { it.id!! }) { value ->
                        CoverCard(value.name.orEmpty(), stringResource(R.string.jpod_artist), value.coverArtId, circle = true,
                            onClick = { navigate(R.id.artistPageFragment,
                                Bundle().apply { putParcelable(Constants.ARTIST_OBJECT, value) }) })
                    }
                }
            }
            if (!songs.isNullOrEmpty()) item {
                Section(stringResource(R.string.jpod_rediscover)) {
                    items(songs.orEmpty(), key = { it.id }) { value ->
                        CoverCard(value.title.orEmpty(), value.artist.orEmpty(), value.coverArtId,
                            onClick = { play(songs.orEmpty(), songs.orEmpty().indexOf(value)) },
                            onLongClick = { songOptions(value) })
                    }
                }
            }
            if (added.isNullOrEmpty() && recent.isNullOrEmpty() && songs.isNullOrEmpty()) item {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.jpod_empty_title), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text(stringResource(R.string.jpod_empty_body), color = Muted)
                    TextButton(onClick = { refresh() }) { Text(stringResource(R.string.jpod_refresh)) }
                }
            }
        }
    }

    @Composable
    private fun Albums(title: String, albums: List<AlbumID3>) = Section(title) {
        items(albums.filter { !it.id.isNullOrBlank() }.distinctBy { it.id }, key = { it.id!! }) { value ->
            CoverCard(value.name.orEmpty(), value.artist.orEmpty(), value.coverArtId,
                onClick = { album(value) }, onLongClick = { album(value, true) })
        }
    }
}

@Composable
private fun Pill(label: String, selected: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(if (selected) Green else Tile)
        .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(label, color = if (selected) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Shortcut(title: String, modifier: Modifier, tint: Color, onClick: () -> Unit) {
    Row(modifier.clip(RoundedCornerShape(4.dp)).background(Tile).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).background(tint), contentAlignment = Alignment.Center) {
            Text(if (tint == Color(0xFF5038A0)) "♥" else "↓", color = Color.White, fontSize = 26.sp)
        }
        Text(title, Modifier.padding(8.dp), maxLines = 2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Section(title: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, Modifier.padding(horizontal = 16.dp), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CoverCard(title: String, subtitle: String, artwork: String?, circle: Boolean = false,
                      onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    Column(Modifier.width(152.dp).combinedClickable(onClick = onClick, onLongClick = onLongClick),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Artwork(artwork, Modifier.size(152.dp).clip(if (circle) CircleShape else RoundedCornerShape(6.dp)))
        Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Muted, fontSize = 12.sp)
    }
}

@Composable
private fun Artwork(id: String?, modifier: Modifier) {
    AndroidView(modifier = modifier, factory = { context ->
        ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
    }, update = { image ->
        CustomGlideRequest.Builder.from(image.context, id, CustomGlideRequest.ResourceType.Album)
            .build().into(image)
    }, onReset = null, onRelease = { Glide.with(it).clear(it) })
}
