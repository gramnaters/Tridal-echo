package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Companion.server
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toServerMedia
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.SettingList
import dev.brahmkshatriya.echo.common.settings.SettingTextInput
import dev.brahmkshatriya.echo.common.settings.Settings

class TridalExtension : ExtensionClient, HomeFeedClient, SearchFeedClient,
    TrackClient, AlbumClient, ArtistClient, PlaylistClient {

    override suspend fun getSettingItems() = listOf(
        SettingList("quality", "Quality", "Streaming quality",
            listOf("Hi-Res FLAC", "Lossless FLAC", "High (MP3)", "Low (MP3)"),
            listOf("HI_RES_LOSSLESS", "LOSSLESS", "HIGH", "LOW"), 0),
        SettingTextInput("tridalApi", "TRIDAL API URL", "Leave empty for default", ""),
    )

    private lateinit var setting: Settings
    override fun setSettings(settings: Settings) { setting = settings }
    private val tridalApiUrl get() = setting.getString("tridalApi")
    val tridalApi by lazy { TridalApi({ tridalApiUrl }) }
    private val streamCache = java.util.concurrent.ConcurrentHashMap<String, StreamCacheEntry>()

    // ── Home ──
    override suspend fun loadHomeFeed() = PagedData.Continuous<Shelf> {
        val shelves = runCatching { buildExploreShelves() }.getOrElse { emptyList() }
        Page(shelves, null)
    }.toFeed()

    private suspend fun buildExploreShelves(): List<Shelf> {
        val explore = tridalApi.explore()
        val shelves = mutableListOf<Shelf>()
        explore.top_albums?.toAlbumShelf("top-albums", "Trending Albums")?.let { shelves.add(it) }
        explore.top_tracks?.toTidalTrackShelf("top-tracks", "Trending Tracks")?.let { shelves.add(it) }
        explore.featured_playlists?.toPlaylistShelf("featured-playlists", "Featured Playlists")?.let { shelves.add(it) }
        explore.sections.orEmpty().forEach { section ->
            val items = section.items.orEmpty()
            if (items.isEmpty()) return@forEach
            when (section.type) {
                "TRACK_LIST" -> items.toTidalTrackShelf("sec-${section.title}", section.title ?: "Tracks")?.let { shelves.add(it) }
                "ALBUM_LIST" -> items.toAlbumShelf("sec-${section.title}", section.title ?: "Albums")?.let { shelves.add(it) }
                "PLAYLIST_LIST" -> items.toPlaylistShelf("sec-${section.title}", section.title ?: "Playlists")?.let { shelves.add(it) }
            }
        }
        return shelves
    }

    // ── Search (global first, Tidal fallback if empty) ──
    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        if (query.isBlank()) {
            return Feed(emptyList()) {
                PagedData.Single<Shelf> { runCatching { buildExploreShelves() }.getOrDefault(emptyList()) }.toFeedData()
            }
        }
        val tabs = listOf("TRACKS", "ARTISTS", "ALBUMS").map { Tab(it, it.lowercase().replaceFirstChar { c -> c.uppercase() }) }
        return Feed(tabs) { tab ->
            when (tab?.id) {
                "TRACKS" -> PagedData.Single<Shelf> {
                    // Try global (Apple Music) first. If empty, fall back to Tidal search.
                    val tracks: List<Track> = runCatching { tridalApi.globalSearch(query, 50) }.getOrDefault(emptyList())
                        .takeIf { it.isNotEmpty() }
                        ?.map { it.toEchoTrack() }
                        ?: runCatching { tridalApi.tidalSearchTracks(query, 50) }.getOrDefault(emptyList())
                            .map { it.toEchoTrack() }
                    listOfNotNull(if (tracks.isNotEmpty()) Shelf.Lists.Tracks("search-tracks", "Tracks", tracks) else null)
                }.toFeedData()
                "ARTISTS" -> PagedData.Single<Shelf> {
                    val artists = runCatching { tridalApi.tidalSearchArtists(query, 50) }.getOrDefault(emptyList())
                    listOfNotNull(artists.toArtistShelf("search-artists", "Artists"))
                }.toFeedData()
                "ALBUMS" -> PagedData.Single<Shelf> {
                    val albums = runCatching { tridalApi.tidalSearchAlbums(query, 50) }.getOrDefault(emptyList())
                    listOfNotNull(albums.toAlbumShelf("search-albums", "Albums"))
                }.toFeedData()
                else -> throw Exception("Unknown tab: ${tab?.id}")
            }
        }
    }

    // ── Track ──
    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        val isrc = if (track.id.startsWith("isrc:")) track.id.removePrefix("isrc:") else track.isrc ?: error("No ISRC")
        val servers = listOf("HI_RES_LOSSLESS" to 5, "LOSSLESS" to 3, "HIGH" to 2, "LOW" to 1).map { (q, lvl) ->
            server(q, lvl, "Tridal $q", mapOf("isrc" to isrc, "quality" to q))
        }
        return track.copy(streamables = servers)
    }

    override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean): Streamable.Media {
        val isrc = streamable.extras["isrc"] ?: error("ISRC not found")
        val quality = streamable.extras["quality"] ?: "LOSSLESS"
        val cacheKey = "$isrc|$quality"
        val now = System.currentTimeMillis()
        streamCache[cacheKey]?.let { if (now - it.timestamp < CACHE_TTL_MS) return it.url.toServerMedia(emptyMap(), Streamable.SourceType.Progressive, isDownload) }
        val qualitiesToTry = buildList {
            add(quality)
            when (quality) {
                "HI_RES_LOSSLESS" -> addAll(listOf("LOSSLESS", "HIGH", "LOW"))
                "LOSSLESS" -> addAll(listOf("HIGH", "LOW"))
                "HIGH" -> addAll(listOf("LOSSLESS", "LOW"))
                "LOW" -> addAll(listOf("HIGH", "LOSSLESS"))
            }
        }.distinct().joinToString(",")
        val resp = tridalApi.getStreamUrl(isrc, qualitiesToTry)
        val url = resp.streamUrl ?: throw IllegalStateException("No stream URL for ISRC $isrc")
        streamCache[cacheKey] = StreamCacheEntry(url, now)
        return url.toServerMedia(emptyMap(), Streamable.SourceType.Progressive, isDownload)
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf> {
        val shelves = mutableListOf<Shelf>()
        track.artists.firstOrNull()?.name?.let { artistName ->
            runCatching {
                val related = tridalApi.globalSearch(artistName, 20)
                    .filter { it.isrc != null && "isrc:${it.isrc}" != track.id }
                    .take(15).map { it.toEchoTrack() }
                if (related.isNotEmpty()) shelves.add(Shelf.Lists.Tracks("more-${track.id}", "More by $artistName", related))
            }
        }
        return shelves.toFeed()
    }

    // ── Album ──
    override suspend fun loadAlbum(album: Album): Album {
        val a = tridalApi.album(album.id)
        return Album(
            id = album.id,
            title = a.title ?: album.title,
            type = null,
            cover = (a.cover ?: a.image?.jsonToString()).toTidalImageSafe() ?: album.cover,
            artists = a.artists.orEmpty().map { Artist(it.id?.toString() ?: "0", it.name ?: "Unknown") },
            trackCount = a.numberOfTracks?.jsonToLong(),
            duration = a.duration?.jsonToLong(),
        )
    }

    override suspend fun loadTracks(album: Album): Feed<Track>? {
        val a = tridalApi.album(album.id)
        return a.items.orEmpty().mapNotNull { it.item }.map { it.toEchoTrack() }.toFeed()
    }

    override suspend fun loadFeed(album: Album): Feed<Shelf>? = null

    // ── Artist ──
    override suspend fun loadArtist(artist: Artist): Artist {
        val a = tridalApi.artist(artist.id).artist ?: return artist
        return Artist(artist.id, a.name ?: artist.name, a.picture.toTidalImageSafe() ?: artist.cover)
    }

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> {
        val shelves = mutableListOf<Shelf>()
        val name = artist.name ?: return shelves.toFeed()
        val nameLower = name.lowercase().trim()
        runCatching {
            tridalApi.tidalSearchTracks(name, 50)
                .filter { it.artists.orEmpty().any { a -> a.name?.lowercase()?.contains(nameLower) == true } }
                .take(20).map { it.toEchoTrack() }
                .let { if (it.isNotEmpty()) shelves.add(Shelf.Lists.Tracks("artist-top-${artist.id}", "Top Tracks", it)) }
        }
        runCatching {
            tridalApi.tidalSearchAlbums(name, 50)
                .filter { it.artists.orEmpty().any { a -> a.name?.lowercase()?.contains(nameLower) == true } }
                .take(20)
                .let { if (it.isNotEmpty()) shelves.add(Shelf.Lists.Items("artist-albums-${artist.id}", "Albums", it.map { it.toEchoAlbum() as EchoMediaItem })) }
        }
        return shelves.toFeed()
    }

    // ── Playlist ──
    override suspend fun loadPlaylist(playlist: Playlist): Playlist {
        val p = tridalApi.playlist(playlist.id).playlist ?: return playlist
        return Playlist(
            id = p.uuid ?: playlist.id,
            title = p.title ?: playlist.title,
            isEditable = false,
            isPrivate = false,
            cover = (p.squareImage ?: p.image?.jsonToString()).toTidalImageSafe() ?: playlist.cover,
            authors = emptyList(),
            trackCount = p.numberOfTracks?.jsonToLong(),
            duration = p.duration?.jsonToLong(),
        )
    }

    override suspend fun loadTracks(playlist: Playlist): Feed<Track> {
        val resp = tridalApi.playlist(playlist.id)
        return resp.items.orEmpty().mapNotNull { it.item }.map { it.toEchoTrack() }.toFeed()
    }

    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? = null

    data class StreamCacheEntry(val url: String, val timestamp: Long)
    companion object { const val CACHE_TTL_MS = 5 * 60 * 1000L }
}
