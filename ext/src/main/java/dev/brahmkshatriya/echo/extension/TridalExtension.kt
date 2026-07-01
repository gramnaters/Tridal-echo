package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.Page
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeedData
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Streamable.Companion.server
import dev.brahmkshatriya.echo.common.models.Streamable.Media.Companion.toServerMedia
import dev.brahmkshatriya.echo.common.models.Tab
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.settings.SettingList
import dev.brahmkshatriya.echo.common.settings.SettingTextInput
import dev.brahmkshatriya.echo.common.settings.Settings

/**
 * Tridal — Apple Music global search + Hi-Res Qobuz streaming for Echo.
 *
 * Architecture:
 *  - Home/Explore: hot.monochrome.tf (trending + editorial shelves) + popular searches
 *  - Search: TRIDAL /global/search/ (Apple Music catalog, ISRC-based)
 *  - Track/Album/Artist: TRIDAL /info/, /album/, /artist/ (Tidal metadata)
 *  - Stream: TRIDAL /global/trackStream/ (Qobuz CDN, up to 24-bit/48kHz Hi-Res)
 *  - Cache: Stream URLs cached for 5 min (Qobuz signed URLs valid ~10 min)
 *
 * Track IDs use "isrc:ISRC_CODE" for global (Apple) tracks and plain numeric
 * IDs for Tidal tracks. The streaming endpoint uses ISRC.
 */
class TridalExtension : ExtensionClient, HomeFeedClient, SearchFeedClient,
    TrackClient, AlbumClient, ArtistClient, PlaylistClient {

    override suspend fun getSettingItems() = listOf(
        SettingList(
            "quality", "Quality", "Streaming quality",
            listOf("Hi-Res FLAC", "Lossless FLAC", "High (MP3)", "Low (MP3)"),
            listOf("HI_RES_LOSSLESS", "LOSSLESS", "HIGH", "LOW"),
            0
        ),
        SettingTextInput(
            "tridalApi", "TRIDAL API URL",
            "Leave empty for default", ""
        ),
    )

    private lateinit var setting: Settings
    override fun setSettings(settings: Settings) { setting = settings }

    private val tridalApiUrl get() = setting.getString("tridalApi")
    private val preferredQuality get() = setting.getString("quality") ?: "HI_RES_LOSSLESS"

    val tridalApi by lazy { TridalApi({ tridalApiUrl }) }

    // Stream URL cache (5 min TTL — Qobuz signed URLs valid ~10 min)
    private val streamCache = java.util.concurrent.ConcurrentHashMap<String, StreamCacheEntry>()

    // ── Home Feed (Monochrome explore shelves) ──
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
                "TRACK_LIST" -> items.toTidalTrackShelf("section-${section.title}", section.title ?: "Tracks")?.let { shelves.add(it) }
                "ALBUM_LIST" -> items.toAlbumShelf("section-${section.title}", section.title ?: "Albums")?.let { shelves.add(it) }
                "PLAYLIST_LIST" -> items.toPlaylistShelf("section-${section.title}", section.title ?: "Playlists")?.let { shelves.add(it) }
            }
        }

        return shelves
    }

    // ── Search (TRIDAL global search — Apple Music catalog) ──
    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        if (query.isBlank()) {
            return Feed(emptyList()) {
                PagedData.Single<Shelf> {
                    runCatching { buildExploreShelves() }.getOrDefault(emptyList())
                }.toFeedData()
            }
        }
        val tabs = listOf("TRACKS", "ARTISTS", "ALBUMS").map {
            Tab(it, it.lowercase().replaceFirstChar { c -> c.uppercase() })
        }
        return Feed(tabs) { tab ->
            when (tab?.id) {
                "TRACKS" -> PagedData.Single<Shelf> {
                    // Use global (Apple Music) search for tracks — returns ISRCs
                    val tracks = runCatching { tridalApi.globalSearch(query, 50) }.getOrDefault(emptyList())
                    listOfNotNull(tracks.toTrackShelf("search-tracks", "Tracks"))
                }.toFeedData()
                "ARTISTS" -> PagedData.Single<Shelf> {
                    // Use Tidal search for artists (global doesn't support artist search)
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

    // ── Track (set up streamables with quality options) ──
    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track {
        // Track ID is "isrc:ISRC_CODE" or a plain Tidal numeric ID
        val isrc = if (track.id.startsWith("isrc:")) track.id.removePrefix("isrc:")
                   else track.isrc ?: error("No ISRC for track")
        val qualities = buildList {
            add("HI_RES_LOSSLESS" to 5)
            add("LOSSLESS" to 3)
            add("HIGH" to 2)
            add("LOW" to 1)
        }
        val servers = qualities.map { (q, lvl) ->
            server(q, lvl, "Tridal $q", mapOf("isrc" to isrc, "quality" to q))
        }
        return track.copy(streamables = servers)
    }

    // ── Streaming (Progressive — Qobuz CDN, 5-min cache) ──
    override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean): Streamable.Media {
        val isrc = streamable.extras["isrc"] ?: error("ISRC not found")
        val quality = streamable.extras["quality"] ?: "LOSSLESS"

        // Check cache (5 min TTL)
        val cacheKey = "$isrc|$quality"
        val now = System.currentTimeMillis()
        streamCache[cacheKey]?.let { cached ->
            if (now - cached.timestamp < CACHE_TTL_MS) {
                return cached.url.toServerMedia(emptyMap(), Streamable.SourceType.Progressive, isDownload)
            }
        }

        // Build quality fallback chain
        val qualitiesToTry = buildList {
            add(quality)
            when (quality) {
                "HI_RES_LOSSLESS" -> addAll(listOf("LOSSLESS", "HIGH", "LOW"))
                "LOSSLESS" -> addAll(listOf("HIGH", "LOW"))
                "HIGH" -> addAll(listOf("LOSSLESS", "LOW"))
                "LOW" -> addAll(listOf("HIGH", "LOSSLESS"))
            }
        }.distinct().joinToString(",")

        // Fetch stream URL from TRIDAL
        val resp = tridalApi.getStreamUrl(isrc, qualitiesToTry)
        val url = resp.streamUrl ?: throw IllegalStateException("No stream URL for ISRC $isrc")

        // Cache for 5 min
        streamCache[cacheKey] = StreamCacheEntry(url, now)

        return url.toServerMedia(emptyMap(), Streamable.SourceType.Progressive, isDownload)
    }

    // ── Track Feed (related tracks via same artist search) ──
    override suspend fun loadFeed(track: Track): Feed<Shelf> {
        val shelves = mutableListOf<Shelf>()
        val artistName = track.artists.firstOrNull()?.name
        if (artistName != null) {
            runCatching {
                val related = tridalApi.globalSearch(artistName, 20)
                    .filter { it.isrc != null && "isrc:${it.isrc}" != track.id }
                    .take(15).map { it.toEchoTrack() }
                if (related.isNotEmpty()) {
                    shelves.add(Shelf.Lists.Tracks("more-${track.id}", "More by $artistName", related))
                }
            }
        }
        return shelves.toFeed()
    }

    // ── Album (Tidal metadata + tracks) ──
    override suspend fun loadAlbum(album: Album): Album {
        val albumData = tridalApi.album(album.id)
        return Album(
            id = album.id,
            title = albumData.title ?: album.title,
            cover = (albumData.cover ?: albumData.image?.jsonToString()).toTidalImageSafe() ?: album.cover,
            artists = albumData.artists.orEmpty().map { Artist(id = it.id?.toString() ?: "0", name = it.name ?: "Unknown") },
            trackCount = albumData.numberOfTracks?.jsonToLong(),
            duration = albumData.duration?.jsonToLong(),
            releaseDate = albumData.releaseDate?.let { parseDateSafe(it) },
            extras = mapOf("json" to "loaded"),
        )
    }

    override suspend fun loadTracks(album: Album): Feed<Track>? {
        val albumData = tridalApi.album(album.id)
        // Tracks are wrapped in {item, type} elements
        val tracks = albumData.items.orEmpty().mapNotNull { it.item }.map { it.toEchoTrack() }
        return tracks.toFeed()
    }

    override suspend fun loadFeed(album: Album): Feed<Shelf>? = null

    // ── Artist (Tidal metadata + discography) ──
    override suspend fun loadArtist(artist: Artist): Artist {
        val artistData = tridalApi.artist(artist.id)
        val a = artistData.artist ?: return artist
        return Artist(
            id = artist.id,
            name = a.name ?: artist.name,
            cover = a.picture?.let { "https://resources.tidal.com/images/${it.replace('-', '/')}/640x640.jpg".toImageHolderSafe() } ?: artist.cover,
            bio = null,
        )
    }

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> {
        val shelves = mutableListOf<Shelf>()
        val artistName = artist.name ?: return shelves.toFeed()
        val artistNameLower = artistName.lowercase().trim()

        // Use Tidal search (/search/?s=) — it's reliable and returns 50 tracks
        // with proper artist names for filtering. The global (Apple Music) search
        // is flaky (rate-limited, returns 0 results sometimes).
        runCatching {
            val searchResults = tridalApi.tidalSearchTracks(artistName, 50)
            // Filter: only tracks where the artist name appears in the artists list
            val filtered = searchResults.filter { item ->
                item.artists.orEmpty().any { a ->
                    a.name?.lowercase()?.contains(artistNameLower) == true
                }
            }.take(20).map { it.toEchoTrack() }
            if (filtered.isNotEmpty()) {
                shelves.add(Shelf.Lists.Tracks("artist-top-${artist.id}", "Top Tracks", filtered))
            }
        }

        // Also fetch albums via Tidal search (/search/?al=)
        runCatching {
            val albumResults = tridalApi.tidalSearchAlbums(artistName, 50)
            val filtered = albumResults.filter { item ->
                item.artists.orEmpty().any { a ->
                    a.name?.lowercase()?.contains(artistNameLower) == true
                }
            }.take(20)
            if (filtered.isNotEmpty()) {
                shelves.add(Shelf.Lists.Items(
                    "artist-albums-${artist.id}", "Albums",
                    filtered.map { it.toEchoAlbum() as EchoMediaItem }
                ))
            }
        }

        return shelves.toFeed()
    }

    // ── Playlist (TRIDAL /playlist/?id=UUID) ──
    override suspend fun loadPlaylist(playlist: Playlist): Playlist {
        val resp = tridalApi.playlist(playlist.id)
        val p = resp.playlist ?: return playlist
        return Playlist(
            id = p.uuid ?: playlist.id,
            title = p.title ?: playlist.title,
            isEditable = false,
            isPrivate = false,
            cover = (p.squareImage ?: p.image?.jsonToString()).toTidalImageSafe() ?: playlist.cover,
            trackCount = p.numberOfTracks?.jsonToLong(),
            duration = p.duration?.jsonToLong(),
        )
    }

    override suspend fun loadTracks(playlist: Playlist): Feed<Track> {
        val resp = tridalApi.playlist(playlist.id)
        // Tracks are wrapped in {item, type} elements — unwrap and convert
        val tracks = resp.items.orEmpty().mapNotNull { it.item }.map { it.toEchoTrack() }
        return tracks.toFeed()
    }

    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? = null

    // ── Helpers ──
    private fun String.toImageHolderSafe() = toImageHolder(crop = false)

    private fun parseDateSafe(s: String): dev.brahmkshatriya.echo.common.models.Date? {
        return runCatching {
            val parts = s.substring(0, minOf(10, s.length)).split("-")
            if (parts.size == 3) dev.brahmkshatriya.echo.common.models.Date(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
            else null
        }.getOrNull()
    }

    data class StreamCacheEntry(val url: String, val timestamp: Long)

    companion object {
        const val CACHE_TTL_MS = 5 * 60 * 1000L
    }
}
