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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    private var exploreFallback: List<Shelf>? = null

    private suspend fun getExploreShelves(): List<Shelf> = withContext(Dispatchers.IO) {
        val fresh = runCatching { buildExploreShelves() }
        if (fresh.isSuccess && fresh.getOrNull()?.isNotEmpty() == true) { exploreFallback = fresh.getOrNull(); exploreFallback!! }
        else exploreFallback ?: runCatching { buildExploreShelves() }.getOrDefault(emptyList())
    }

    private suspend fun getSearchExploreShelves(): List<Shelf> = withContext(Dispatchers.IO) {
        val explorePage = runCatching { tridalApi.tidalPage("pages/explore") }.getOrNull()
        if (explorePage != null) { val shelves = buildExploreCategoryShelves(explorePage); if (shelves.isNotEmpty()) return@withContext shelves }
        getExploreShelves()
    }

    override suspend fun loadHomeFeed() = PagedData.Continuous<Shelf> { Page(getExploreShelves(), null) }.toFeed()

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

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        if (query.isBlank()) {
            return Feed(emptyList()) { PagedData.Single<Shelf> { getSearchExploreShelves() }.toFeedData() }
        }
        val tabs = listOf("TRACKS", "ARTISTS", "ALBUMS").map { Tab(it, it.lowercase().replaceFirstChar { c -> c.uppercase() }) }
        return Feed(tabs) { tab ->
            when (tab?.id) {
                "TRACKS" -> PagedData.Single<Shelf> {
                    val tracks: List<Track> = runCatching { tridalApi.globalSearch(query, 50) }.getOrDefault(emptyList())
                        .takeIf { it.isNotEmpty() }?.map { it.toEchoTrack() }
                        ?: runCatching { tridalApi.tidalSearchTracks(query, 50) }.getOrDefault(emptyList()).map { it.toEchoTrack() }
                    listOfNotNull(if (tracks.isNotEmpty()) Shelf.Lists.Tracks("search-tracks", "Tracks", tracks) else null)
                }.toFeedData()
                "ARTISTS" -> PagedData.Single<Shelf> {
                    listOfNotNull(runCatching { tridalApi.tidalSearchArtists(query, 50) }.getOrDefault(emptyList()).toArtistShelf("search-artists", "Artists"))
                }.toFeedData()
                "ALBUMS" -> PagedData.Single<Shelf> {
                    listOfNotNull(runCatching { tridalApi.tidalSearchAlbums(query, 50) }.getOrDefault(emptyList()).toAlbumShelf("search-albums", "Albums"))
                }.toFeedData()
                else -> throw Exception("Unknown tab: ${tab?.id}")
            }
        }
    }

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        val isIsrcBased = track.id.startsWith("isrc:")
        val qualities = listOf("HI_RES_LOSSLESS" to 5, "LOSSLESS" to 3, "HIGH" to 2, "LOW" to 1)
        val servers = qualities.map { (q, lvl) ->
            server(q, lvl, "Tridal $q", mapOf("trackId" to track.id, "quality" to q, "isIsrc" to isIsrcBased.toString()))
        }
        return track.copy(streamables = servers)
    }

    override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean): Streamable.Media {
        val trackId = streamable.extras["trackId"] ?: error("Track ID not found")
        val quality = streamable.extras["quality"] ?: "LOSSLESS"
        val isIsrc = streamable.extras["isIsrc"]?.toBoolean() ?: false
        val cacheKey = "$trackId|$quality"
        val now = System.currentTimeMillis()
        streamCache[cacheKey]?.let { if (now - it.timestamp < CACHE_TTL_MS) return it.url.toServerMedia(emptyMap(), Streamable.SourceType.Progressive, isDownload) }
        val url = if (isIsrc) {
            val isrc = trackId.removePrefix("isrc:")
            val qualitiesToTry = buildList {
                add(quality)
                when (quality) {
                    "HI_RES_LOSSLESS" -> addAll(listOf("LOSSLESS", "HIGH", "LOW"))
                    "LOSSLESS" -> addAll(listOf("HIGH", "LOW"))
                    "HIGH" -> addAll(listOf("LOSSLESS", "LOW"))
                    "LOW" -> addAll(listOf("HIGH", "LOSSLESS"))
                }
            }.distinct().joinToString(",")
            val resp = tridalApi.getStreamUrlByIsrc(isrc, qualitiesToTry)
            resp.streamUrl ?: throw IllegalStateException("No stream URL for ISRC $isrc")
        } else {
            tridalApi.getStreamUrlByTidalId(trackId, quality)
        }
        streamCache[cacheKey] = StreamCacheEntry(url, now)
        return url.toServerMedia(emptyMap(), Streamable.SourceType.Progressive, isDownload)
    }

    override suspend fun loadFeed(track: Track): Feed<Shelf> {
        val shelves = mutableListOf<Shelf>()
        track.artists.firstOrNull()?.name?.let { artistName ->
            runCatching {
                val related = tridalApi.globalSearch(artistName, 20).filter { it.isrc != null && "isrc:${it.isrc}" != track.id }.take(15).map { it.toEchoTrack() }
                if (related.isNotEmpty()) shelves.add(Shelf.Lists.Tracks("more-${track.id}", "More by $artistName", related))
            }
        }
        return shelves.toFeed()
    }

    override suspend fun loadAlbum(album: Album): Album {
        val a = tridalApi.album(album.id)
        return Album(id = album.id, title = a.title ?: album.title, type = null,
            cover = (a.cover ?: a.image?.jsonToString()).toTidalImageSafe() ?: album.cover,
            artists = a.artists.orEmpty().map { Artist(it.id?.toString() ?: "0", it.name ?: "Unknown") },
            trackCount = a.numberOfTracks?.jsonToLong(), duration = a.duration?.jsonToLong())
    }
    override suspend fun loadTracks(album: Album): Feed<Track>? {
        val a = tridalApi.album(album.id)
        return a.items.orEmpty().mapNotNull { it.item }.map { it.toEchoTrack() }.toFeed()
    }
    override suspend fun loadFeed(album: Album): Feed<Shelf>? = null

    override suspend fun loadArtist(artist: Artist): Artist {
        val a = tridalApi.artist(artist.id).artist ?: return artist
        return Artist(artist.id, a.name ?: artist.name, a.picture.toTidalImageSafe() ?: artist.cover)
    }
    override suspend fun loadFeed(artist: Artist): Feed<Shelf> {
        val shelves = mutableListOf<Shelf>()
        val name = artist.name ?: return shelves.toFeed()
        val nameLower = name.lowercase().trim()
        runCatching { tridalApi.tidalSearchTracks(name, 50).filter { it.artists.orEmpty().any { a -> a.name?.lowercase()?.contains(nameLower) == true } }.take(20).map { it.toEchoTrack() }.let { if (it.isNotEmpty()) shelves.add(Shelf.Lists.Tracks("artist-top-${artist.id}", "Top Tracks", it)) } }
        runCatching { tridalApi.tidalSearchAlbums(name, 50).filter { it.artists.orEmpty().any { a -> a.name?.lowercase()?.contains(nameLower) == true } }.take(20).let { if (it.isNotEmpty()) shelves.add(Shelf.Lists.Items("artist-albums-${artist.id}", "Albums", it.map { it.toEchoAlbum() as EchoMediaItem })) } }
        return shelves.toFeed()
    }

    override suspend fun loadPlaylist(playlist: Playlist): Playlist {
        val p = tridalApi.playlist(playlist.id).playlist ?: return playlist
        return Playlist(id = p.uuid ?: playlist.id, title = p.title ?: playlist.title, isEditable = false, isPrivate = false,
            cover = (p.squareImage ?: p.image?.jsonToString()).toTidalImageSafe() ?: playlist.cover,
            authors = emptyList(), trackCount = p.numberOfTracks?.jsonToLong(), duration = p.duration?.jsonToLong())
    }
    override suspend fun loadTracks(playlist: Playlist): Feed<Track> {
        val resp = tridalApi.playlist(playlist.id)
        return resp.items.orEmpty().mapNotNull { it.item }.map { it.toEchoTrack() }.toFeed()
    }
    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? = null

    private fun buildExploreCategoryShelves(page: TidalPageResponse): List<Shelf> {
        val shelves = mutableListOf<Shelf>()
        page.rows.orEmpty().forEach { row ->
            row.modules.orEmpty().forEach { mod ->
                val mtype = mod.type
                if (mtype != "PAGE_LINKS_CLOUD" && mtype != "PAGE_LINKS") return@forEach
                val items = mod.pagedList?.items.orEmpty()
                if (items.isEmpty()) return@forEach
                val categories = items.mapNotNull { item ->
                    val apiPath = item.apiPath ?: return@mapNotNull null
                    val title = item.title ?: item.text ?: "Unknown"
                    Shelf.Category(id = apiPath, title = title, subtitle = null,
                        feed = Feed(emptyList()) { runCatching { val subPage = tridalApi.tidalPage(apiPath); buildSubPageShelves(subPage) }.getOrDefault(emptyList()).toFeedData() })
                }
                if (categories.isEmpty()) return@forEach
                when (mtype) {
                    "PAGE_LINKS_CLOUD" -> { val modTitle = mod.title ?: return@forEach; shelves.add(Shelf.Lists.Categories(id = "cat-$modTitle", title = modTitle, list = categories)) }
                    "PAGE_LINKS" -> shelves.addAll(categories)
                }
            }
        }
        return shelves
    }

    private fun buildSubPageShelves(page: TidalPageResponse): List<Shelf> {
        val shelves = mutableListOf<Shelf>()
        page.rows.orEmpty().forEach { row ->
            row.modules.orEmpty().forEach { mod ->
                val mtype = mod.type; val modTitle = mod.title ?: return@forEach
                val pageItems = mod.pagedList?.items.orEmpty()
                if (pageItems.isEmpty()) return@forEach
                when (mtype) {
                    "TRACK_LIST" -> { val t = pageItems.mapNotNull { it.toEchoTrack() }; if (t.isNotEmpty()) shelves.add(Shelf.Lists.Tracks("sub-$modTitle", modTitle, t)) }
                    "ALBUM_LIST" -> { val a = pageItems.mapNotNull { it.toEchoAlbum() }; if (a.isNotEmpty()) shelves.add(Shelf.Lists.Items("sub-$modTitle", modTitle, a.map { it as EchoMediaItem })) }
                    "PLAYLIST_LIST" -> { val p = pageItems.mapNotNull { it.toEchoPlaylist() }; if (p.isNotEmpty()) shelves.add(Shelf.Lists.Items("sub-$modTitle", modTitle, p.map { it as EchoMediaItem })) }
                    "ARTIST_LIST" -> { val ar = pageItems.mapNotNull { it.toEchoArtist() }; if (ar.isNotEmpty()) shelves.add(Shelf.Lists.Items("sub-$modTitle", modTitle, ar.map { it as EchoMediaItem })) }
                    "VIDEO_LIST" -> { val v = pageItems.mapNotNull { it.toEchoTrack() }; if (v.isNotEmpty()) shelves.add(Shelf.Lists.Tracks("sub-$modTitle", modTitle, v)) }
                }
            }
        }
        return shelves
    }

    data class StreamCacheEntry(val url: String, val timestamp: Long)
    companion object { const val CACHE_TTL_MS = 5 * 60 * 1000L }
}
