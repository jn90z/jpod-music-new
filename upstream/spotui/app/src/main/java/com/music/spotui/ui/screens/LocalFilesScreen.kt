package com.music.spotui.ui.screens

import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import com.bumptech.glide.integration.compose.placeholder
import com.music.spotui.R
import com.music.spotui.data.local.LocalImport
import com.music.spotui.data.preferences.addLocalTracks
import com.music.spotui.data.preferences.getLocalSongs
import com.music.spotui.data.preferences.removeLocalTrack
import com.music.spotui.di.SongPlayer
import com.music.spotui.ui.theme.AppBackground
import com.music.spotui.ui.theme.AppPalette
import com.music.spotui.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalGlideComposeApi::class, ExperimentalFoundationApi::class)
@Composable
fun LocalFilesScreen(navController: NavController) {
    val playerViewModel: PlayerViewModel = hiltViewModel()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accent = AppPalette

    var songs by remember { mutableStateOf(getLocalSongs(context)) }
    var importing by remember { mutableStateOf(false) }

    fun toast(msg: String) =
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()

    val addSongs = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        if (uris.isNullOrEmpty()) return@rememberLauncherForActivityResult
        importing = true
        scope.launch(Dispatchers.IO) {
            val tracks = LocalImport.importFiles(context, uris)
            addLocalTracks(context, tracks)
            withContext(Dispatchers.Main) {
                songs = getLocalSongs(context)
                importing = false
                toast("Imported ${tracks.size} song${if (tracks.size == 1) "" else "s"}")
            }
        }
    }

    val addFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch(Dispatchers.IO) {
            val tracks = LocalImport.importFolder(context, treeUri)
            addLocalTracks(context, tracks)
            withContext(Dispatchers.Main) {
                songs = getLocalSongs(context)
                importing = false
                toast("Imported ${tracks.size} song${if (tracks.size == 1) "" else "s"} from folder")
            }
        }
    }

    var query by remember { mutableStateOf("") }
    // FLAC / MP3 / ... per song, read once off the main thread (a content-resolver call each).
    val formats by androidx.compose.runtime.produceState(emptyMap<String, String>(), songs) {
        value = withContext(Dispatchers.IO) { songs.associate { it.url to audioFormat(context, it.url) } }
    }
    val shown = remember(songs, query) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) songs else songs.filter {
            it.title.lowercase().contains(q) || it.singer.lowercase().contains(q) || it.album.lowercase().contains(q)
        }
    }
    // Library views: grouped once per change of the (filtered) song list.
    val albums = remember(shown) {
        shown.groupBy { it.album.ifBlank { "Unknown album" } to albumArtist(it.singer) }
            .map { (k, list) -> LocalAlbum(k.first, k.second, list.firstOrNull { it.coverUri.isNotBlank() }?.coverUri.orEmpty(), list) }
            .sortedBy { it.name.lowercase() }
    }
    val artists = remember(shown) {
        shown.groupBy { albumArtist(it.singer) }
            .map { (name, list) -> LocalArtist(name, list.firstOrNull { it.coverUri.isNotBlank() }?.coverUri.orEmpty(), list) }
            .sortedBy { it.name.lowercase() }
    }
    var tab by remember { mutableStateOf("Songs") }
    var openAlbum by remember { mutableStateOf<LocalAlbum?>(null) }
    var openArtist by remember { mutableStateOf<LocalArtist?>(null) }
    // Back from an album/artist page returns to the grid/list, not out of the screen.
    androidx.activity.compose.BackHandler(enabled = openAlbum != null || openArtist != null) {
        openAlbum = null; openArtist = null
    }

    fun play(list: List<com.music.spotui.data.entity.SongsModel>, index: Int) {
        val song = list.getOrNull(index) ?: return
        playerViewModel.updateQueue(list)
        SongPlayer.playSong(song.url, context)
        playerViewModel.updateSongState(song.coverUri, song.title, song.singer, true, song.id, index, song.album)
    }
    fun remove(song: com.music.spotui.data.entity.SongsModel) {
        removeLocalTrack(context, song.url)
        songs = getLocalSongs(context)
        toast("Removed from library")
    }

    Surface(
        color = Color(AppBackground.toArgb()),
        modifier = Modifier.fillMaxSize(),
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            // Green fades into the background down the whole screen, behind the top bar too.
            modifier = Modifier.background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    0f to Color(0xFF1B6B3A), 0.55f to Color(AppBackground.toArgb()),
                ),
            ),
            topBar = {
                CenterAlignedTopAppBar(
                    modifier = Modifier.padding(16.dp, 0.dp),
                    navigationIcon = {
                        Icon(
                            modifier = Modifier.clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) {
                                if (openAlbum != null || openArtist != null) { openAlbum = null; openArtist = null }
                                else navController.navigateUp()
                            },
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = Color.White,
                    ),
                    // The hero below already says "Local files"; the bar only names album/artist pages.
                    title = { Text(openAlbum?.name ?: openArtist?.name ?: "", color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1) },
                )
            },
        ) { innerPadding ->
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .consumeWindowInsets(innerPadding)
                    .padding(top = innerPadding.calculateTopPadding(), bottom = innerPadding.calculateBottomPadding()),
            ) {
                val album = openAlbum
                val artist = openArtist
                when {
                    songs.isEmpty() -> item {
                        EmptyLibrary(importing, accent, onSongs = { addSongs.launch(arrayOf("audio/*")) }, onFolder = { addFolder.launch(null) })
                    }
                    album != null -> {
                        item { CollectionHeader(album.cover, album.name, "${album.artist} · ${album.songs.size} songs", accent, round = false, onPlay = { play(album.songs, 0) }, onShuffle = { play(album.songs.shuffled(), 0) }) }
                        album.songs.forEachIndexed { i, song -> item(key = "a" + song.url) { LocalSongRow(song, playerViewModel.currentSongId.value, formats[song.url].orEmpty(), onClick = { play(album.songs, i) }, onRemove = { remove(song) }) } }
                    }
                    artist != null -> {
                        item { CollectionHeader(artist.cover, artist.name, "${artist.songs.size} songs", accent, round = true, onPlay = { play(artist.songs, 0) }, onShuffle = { play(artist.songs.shuffled(), 0) }) }
                        artist.songs.forEachIndexed { i, song -> item(key = "r" + song.url) { LocalSongRow(song, playerViewModel.currentSongId.value, formats[song.url].orEmpty(), onClick = { play(artist.songs, i) }, onRemove = { remove(song) }) } }
                    }
                    else -> {
                        item {
                            val minutes = songs.sumOf { it.durationMs.toLong() } / 60_000
                            LocalHero(
                                summary = "${songs.size} songs" + (if (minutes > 0) " · ${formatTotal(minutes)}" else "") + " · on this phone",
                                importing = importing,
                                onPlay = { play(songs, 0) }, onShuffle = { play(songs.shuffled(), 0) },
                                onSongs = { addSongs.launch(arrayOf("audio/*")) }, onFolder = { addFolder.launch(null) },
                            )
                        }
                        item { SearchField(query) { query = it } }
                        item {
                            Row(Modifier.padding(16.dp, 4.dp, 16.dp, 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("Songs", "Albums", "Artists").forEach { t -> TabPill(t, t == tab) { tab = t } }
                            }
                        }
                        if (shown.isEmpty()) item {
                            Text("Nothing matches \"$query\"", color = Color.Gray, fontSize = 14.sp, modifier = Modifier.padding(20.dp, 12.dp))
                        }
                        when (tab) {
                            "Albums" -> albums.chunked(2).forEach { pair ->
                                item(key = "g" + pair.first().name + pair.first().artist) {
                                    Row(Modifier.fillMaxWidth().padding(16.dp, 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                        pair.forEach { a -> AlbumCard(a, Modifier.weight(1f)) { openAlbum = a } }
                                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                            "Artists" -> artists.forEach { a ->
                                item(key = "ar" + a.name) { ArtistRow(a) { openArtist = a } }
                            }
                            else -> shown.forEachIndexed { i, song ->
                                item(key = "s" + song.url) {
                                    LocalSongRow(song, playerViewModel.currentSongId.value, formats[song.url].orEmpty(), onClick = { play(shown, i) }, onRemove = { remove(song) })
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(100.dp)) }
            }
        }
    }
}

private data class LocalAlbum(val name: String, val artist: String, val cover: String, val songs: List<com.music.spotui.data.entity.SongsModel>)
private data class LocalArtist(val name: String, val cover: String, val songs: List<com.music.spotui.data.entity.SongsModel>)

/** "A, B feat. C" groups under its first artist, like Spotify's album artist. */
private fun albumArtist(singer: String) = singer.substringBefore(",").substringBefore(" feat").trim().ifBlank { "Unknown artist" }

private val Green = Color(0xFF1ED760)
private val LosslessFormats = setOf("FLAC", "WAV", "ALAC", "AIFF", "APE", "WV")

/** FLAC / MP3 / M4A ... from the file's MIME type, else its extension. */
private fun audioFormat(context: android.content.Context, uri: String): String {
    val mime = runCatching { context.contentResolver.getType(android.net.Uri.parse(uri)) }.getOrNull().orEmpty().lowercase()
    return when {
        "flac" in mime -> "FLAC"
        "mpeg" in mime || "mp3" in mime -> "MP3"
        "wav" in mime -> "WAV"
        "mp4" in mime || "m4a" in mime || "aac" in mime -> "M4A"
        "ogg" in mime -> "OGG"
        "opus" in mime -> "OPUS"
        "aiff" in mime -> "AIFF"
        else -> uri.substringBefore('?').substringAfterLast('.', "").uppercase().takeIf { it.length in 2..4 }.orEmpty()
    }
}

private fun formatTotal(minutes: Long) = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"

/** Cover-style header, like Spotify's "Local Files" playlist. */
@Composable
private fun LocalHero(
    summary: String, importing: Boolean,
    onPlay: () -> Unit, onShuffle: () -> Unit, onSongs: () -> Unit, onFolder: () -> Unit,
) {
    var addMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(20.dp, 8.dp, 20.dp, 12.dp)) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(150.dp).clip(RoundedCornerShape(6.dp))
                .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color(0xFF1ED760), Color(0xFF0B3D20)))),
        ) {
            Icon(
                painter = androidx.compose.ui.res.painterResource(R.drawable.ic_devices),
                contentDescription = null, tint = Color.White, modifier = Modifier.size(64.dp),
            )
        }
        Text("Local files", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
        Text(summary, color = Color(0xFFB3B3B3), fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Box {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .background(Color.Transparent)
                        .clickable(enabled = !importing) { addMenu = true }
                        .padding(end = 12.dp, top = 6.dp, bottom = 6.dp),
                ) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(R.drawable.ic_add),
                        contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp),
                    )
                    Text(
                        if (importing) "Importing…" else "Add music",
                        color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                androidx.compose.material3.DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Add songs") }, onClick = { addMenu = false; onSongs() })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Add a folder") }, onClick = { addMenu = false; onFolder() })
                }
            }
            Spacer(Modifier.weight(1f))
            ShuffleButton(onShuffle)
            Spacer(Modifier.width(8.dp))
            PlayButton(Green, onPlay)
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    androidx.compose.foundation.text.BasicTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 14.sp),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(Green),
        modifier = Modifier.fillMaxWidth().padding(16.dp, 4.dp),
        decorationBox = { field ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF242424)).padding(12.dp, 10.dp),
            ) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.ic_search_big),
                    contentDescription = null, tint = Color.Gray, modifier = Modifier.size(18.dp),
                )
                Box(Modifier.padding(start = 10.dp).weight(1f)) {
                    if (query.isEmpty()) Text("Search in local files", color = Color.Gray, fontSize = 14.sp)
                    field()
                }
            }
        },
    )
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun CollectionHeader(
    cover: String, title: String, subtitle: String, accent: Color, round: Boolean,
    onPlay: () -> Unit, onShuffle: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(20.dp, 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        GlideImage(
            model = cover,
            failure = placeholder(R.drawable.placeholder),
            loading = placeholder(R.drawable.placeholder),
            contentScale = ContentScale.Crop,
            contentDescription = title,
            modifier = Modifier.size(220.dp).clip(if (round) androidx.compose.foundation.shape.CircleShape else RoundedCornerShape(6.dp)),
        )
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                Text(subtitle, color = Color.Gray, fontSize = 13.sp, maxLines = 1)
            }
            ShuffleButton(onShuffle)
            Spacer(Modifier.width(8.dp))
            PlayButton(Green, onPlay)
        }
    }
}

@Composable
private fun PlayButton(accent: Color, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(52.dp).clip(androidx.compose.foundation.shape.CircleShape).background(accent).clickable(onClick = onClick),
    ) {
        Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.Black, modifier = Modifier.size(30.dp))
    }
}

@Composable
private fun ShuffleButton(onClick: () -> Unit) {
    Icon(
        painter = androidx.compose.ui.res.painterResource(R.drawable.ic_player_shuffle),
        contentDescription = "Shuffle",
        tint = Color.White,
        modifier = Modifier.size(44.dp).clip(androidx.compose.foundation.shape.CircleShape).clickable(onClick = onClick).padding(10.dp),
    )
}

@Composable
private fun TabPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label, color = if (selected) Color.Black else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) Green else Color(0xFF2A2A2A))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun AlbumCard(album: LocalAlbum, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick)) {
        GlideImage(
            model = album.cover,
            failure = placeholder(R.drawable.placeholder),
            loading = placeholder(R.drawable.placeholder),
            contentScale = ContentScale.Crop,
            contentDescription = album.name,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(6.dp)),
        )
        Text(album.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
        Text("${album.artist} · ${album.songs.size}", color = Color.Gray, fontSize = 12.sp, maxLines = 1)
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun ArtistRow(artist: LocalArtist, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(20.dp, 8.dp),
    ) {
        GlideImage(
            model = artist.cover,
            failure = placeholder(R.drawable.placeholder),
            loading = placeholder(R.drawable.placeholder),
            contentScale = ContentScale.Crop,
            contentDescription = artist.name,
            modifier = Modifier.size(56.dp).clip(androidx.compose.foundation.shape.CircleShape),
        )
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            Text(artist.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("${artist.songs.size} songs", color = Color.Gray, fontSize = 12.sp)
        }
    }
}

@OptIn(ExperimentalGlideComposeApi::class)
@Composable
private fun LocalSongRow(
    song: com.music.spotui.data.entity.SongsModel,
    currentId: Int,
    format: String,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    com.music.spotui.ui.components.SwipeToQueue(song) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(20.dp, 8.dp, 8.dp, 8.dp),
        ) {
            GlideImage(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)),
                model = song.coverUri,
                failure = placeholder(R.drawable.placeholder),
                loading = placeholder(R.drawable.placeholder),
                contentScale = ContentScale.Crop,
                contentDescription = "",
            )
            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                Text(song.title, color = if (song.id == currentId) AppPalette else Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (format.isNotEmpty()) {
                        // Lossless formats are the point of local files: say so.
                        val lossless = format in LosslessFormats
                        Text(
                            format,
                            color = if (lossless) Color.Black else Color(0xFFB3B3B3),
                            fontSize = 9.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(3.dp))
                                .background(if (lossless) Green else Color(0xFF3A3A3A)).padding(horizontal = 4.dp, vertical = 1.dp),
                        )
                    }
                    Text(
                        listOf(song.singer, song.album).filter { it.isNotBlank() }.joinToString(" · "),
                        color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1,
                    )
                }
            }
            if (song.durationMs > 0) {
                val sec = song.durationMs / 1000
                Text("%d:%02d".format(sec / 60, sec % 60), color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
            }
            Box {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(R.drawable.ic_dots),
                    contentDescription = "More options for ${song.title}",
                    tint = Color.Gray,
                    modifier = Modifier.size(40.dp).clip(androidx.compose.foundation.shape.CircleShape).clickable { menu = true }.padding(10.dp),
                )
                androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("Remove from library") },
                        onClick = { menu = false; onRemove() },
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyLibrary(importing: Boolean, accent: Color, onSongs: () -> Unit, onFolder: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(28.dp, 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Your music, in lossless", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            "Import FLAC, MP3, WAV and more from your phone. They play offline, with your albums and artists, next to Spotify.",
            color = Color.Gray, fontSize = 14.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            ImportButton("Add songs", accent, Modifier.weight(1f), enabled = !importing, onClick = onSongs)
            ImportButton("Add folder", Color(0xFF2A2A33), Modifier.weight(1f), enabled = !importing, onClick = onFolder)
        }
        if (importing) Text("Importing…", color = accent, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun ImportButton(
    label: String,
    container: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        color = if (container == Color(0xFF2A2A33)) Color.White else Color.Black,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (enabled) container else Color(0xFF333333))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 12.dp),
    )
}
