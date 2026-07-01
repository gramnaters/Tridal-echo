package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * TRIDAL API client — Monochrome HiFi API v3.2.
 *
 * Two search modes:
 *  - /global/search/?term=  → Apple Music catalog (ISRC-based, global)
 *  - /search/?s=            → Tidal catalog (numeric ID-based)
 *
 * Metadata endpoints (Tidal-based):
 *  - /info/?id=ID           → Track metadata
 *  - /album/?id=ID          → Album with tracks
 *  - /artist/?id=ID         → Artist profile
 *  - /artist/?f=ID          → Artist discography (albums + tracks)
 *
 * Streaming:
 *  - /global/trackStream/?isrc=ISRC&qualities=  → Qobuz CDN (Hi-Res, 30ms TTFB)
 *
 * Explore:
 *  - hot.monochrome.tf/     → Trending albums/tracks/playlists + editorial sections
 */
class TridalApi(
    private val getBaseUrl: suspend () -> String?,
) {
    val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun base(): String {
        val custom = getBaseUrl()?.trimEnd('/')
        return if (!custom.isNullOrEmpty()) custom else DEFAULT_URL
    }

    private suspend fun call(path: String): String {
        val req = Request.Builder()
            .url("${base()}$path")
            .header("Accept", "application/json")
            .header("User-Agent", "Echo-Tridal/1.0 (Android)")
            .build()
        val res = client.newCall(req).await()
        val body = res.body.string()
        if (!res.isSuccessful) throw IllegalStateException("TRIDAL $path failed: ${res.code}")
        return body
    }

    // ── Global search (Apple Music) ──
    suspend fun globalSearch(query: String, limit: Int = 50): List<TridalTrack> {
        val raw = call("/global/search/?term=${enc(query)}&limit=$limit")
        val parsed = json.decodeFromString<GlobalSearchResponse>(raw)
        return parsed.tracks.orEmpty()
    }

    // ── Tidal search (fallback) ──
    suspend fun tidalSearchTracks(query: String, limit: Int = 50): List<TidalItem> {
        val raw = call("/search/?s=${enc(query)}&limit=$limit")
        val parsed = json.decodeFromString<TidalSearchResponse<TidalList<TidalItem>>>(raw)
        return parsed.data?.items.orEmpty()
    }

    suspend fun tidalSearchAlbums(query: String, limit: Int = 50): List<TidalItem> {
        val raw = call("/search/?al=${enc(query)}&limit=$limit")
        val parsed = json.decodeFromString<TidalSearchResponse<TidalTopHits<TidalItem>>>(raw)
        return parsed.data?.topHits.orEmpty().mapNotNull { it.value }
    }

    suspend fun tidalSearchArtists(query: String, limit: Int = 50): List<TidalItem> {
        val raw = call("/search/?a=${enc(query)}&limit=$limit")
        val parsed = json.decodeFromString<TidalSearchResponse<TidalTopHits<TidalItem>>>(raw)
        return parsed.data?.topHits.orEmpty().mapNotNull { it.value }
    }

    // ── Metadata endpoints ──
    suspend fun trackInfo(trackId: String): TidalItem {
        val raw = call("/info/?id=${enc(trackId)}")
        val parsed = json.decodeFromString<TidalEnvelope<TidalItem>>(raw)
        return parsed.data ?: throw IllegalStateException("No track data for $trackId")
    }

    suspend fun album(albumId: String): TidalItem {
        val raw = call("/album/?id=${enc(albumId)}")
        val parsed = json.decodeFromString<TidalEnvelope<TidalItem>>(raw)
        return parsed.data ?: throw IllegalStateException("No album data for $albumId")
    }

    suspend fun artist(artistId: String): ArtistResponse {
        val raw = call("/artist/?id=${enc(artistId)}")
        return json.decodeFromString(raw)
    }

    suspend fun artistDiscography(artistId: String): ArtistDiscographyResponse {
        val raw = call("/artist/?f=${enc(artistId)}")
        return json.decodeFromString(raw)
    }

    // ── Playlist ──
    suspend fun playlist(uuid: String): PlaylistResponse {
        val raw = call("/playlist/?id=${enc(uuid)}")
        return json.decodeFromString(raw)
    }

    // ── Explore (from hot.monochrome.tf) ──
    suspend fun explore(): ExploreResponse {
        val req = Request.Builder()
            .url(EXPLORE_URL)
            .header("Accept", "application/json")
            .header("User-Agent", "Echo-Tridal/1.0 (Android)")
            .build()
        val res = client.newCall(req).await()
        if (!res.isSuccessful) throw IllegalStateException("Explore failed: ${res.code}")
        return json.decodeFromString(res.body.string())
    }

    // ── Stream URL resolution ──
    suspend fun getStreamUrl(isrc: String, qualities: String): StreamResponse {
        val raw = call("/global/trackStream/?isrc=${enc(isrc)}&qualities=${enc(qualities)}")
        return json.decodeFromString(raw)
    }

    companion object {
        const val DEFAULT_URL = "https://this-shit-is-not-working.anothermoumen4.workers.dev"
        const val EXPLORE_URL = "https://hot.monochrome.tf/"
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}

// ── Global (Apple Music) response models ─────────────────────────────────────

@Serializable
data class GlobalSearchResponse(
    val version: String? = null,
    val source: String? = null,
    val storefront: String? = null,
    val tracks: List<TridalTrack>? = null,
    val total: Int? = null,
)

@Serializable
data class TridalTrack(
    val id: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val artistId: String? = null,
    val album: JsonElement? = null,
    val albumId: String? = null,
    val albumCover: String? = null,
    val artwork: String? = null,
    val duration: Long? = null,
    val trackNumber: Long? = null,
    val discNumber: Long? = null,
    val isrc: String? = null,
    val explicit: Boolean? = null,
    val url: String? = null,
    val releaseDate: String? = null,
)

@Serializable
data class StreamResponse(
    val version: String? = null,
    val isrc: String? = null,
    val preferredProvider: String? = null,
    val provider: String? = null,
    val streamUrl: String? = null,
    val formatId: JsonElement? = null,
    val quality: JsonElement? = null,
    val audioQuality: String? = null,
    val bitDepth: JsonElement? = null,
    val sampleRate: JsonElement? = null,
    val bitrate: JsonElement? = null,
    val mimeType: String? = null,
    val track: JsonElement? = null,
)

// ── Tidal (v2.x) response models ─────────────────────────────────────────────

@Serializable
data class TidalEnvelope<T>(
    val version: String? = null,
    val data: T? = null,
)

@Serializable
data class TidalSearchResponse<T>(
    val version: String? = null,
    val data: T? = null,
)

@Serializable
data class TidalList<T>(
    val items: List<T>? = null,
)

@Serializable
data class TidalTopHits<T>(
    val topHits: List<TidalHit<T>>? = null,
)

@Serializable
data class TidalHit<T>(
    val value: T? = null,
    val type: String? = null,
)

@Serializable
data class TidalItem(
    val id: JsonElement? = null,
    val title: String? = null,
    val name: String? = null,
    val artist: JsonElement? = null,
    val artists: List<TidalArtist>? = null,
    val album: JsonElement? = null,
    val cover: String? = null,
    val picture: String? = null,
    val image: JsonElement? = null,
    val squareImage: String? = null,
    val duration: JsonElement? = null,
    val trackNumber: JsonElement? = null,
    val volumeNumber: JsonElement? = null,
    val isrc: String? = null,
    val explicit: Boolean? = null,
    val releaseDate: String? = null,
    val numberOfTracks: JsonElement? = null,
    val audioQuality: String? = null,
    val mediaMetadata: TidalMediaMetadata? = null,
    val items: List<TidalItemElement>? = null,
    val uuid: String? = null,
    val description: JsonElement? = null,
    val url: String? = null,
    val popularity: JsonElement? = null,
    val artistTypes: List<String>? = null,
    val handle: String? = null,
)

@Serializable
data class TidalItemElement(
    val item: TidalItem? = null,
    val type: String? = null,
)

@Serializable
data class TidalArtist(
    val id: Long? = null,
    val name: String? = null,
    val picture: String? = null,
    val handle: String? = null,
)

@Serializable
data class TidalAlbum(
    val id: Long? = null,
    val title: String? = null,
    val cover: String? = null,
    val releaseDate: String? = null,
)

@Serializable
data class TidalMediaMetadata(
    val tags: List<String>? = null,
)

// ── Artist response ──────────────────────────────────────────────────────────

@Serializable
data class ArtistResponse(
    val version: String? = null,
    val artist: TidalItem? = null,
    val cover: JsonElement? = null,
)

@Serializable
data class ArtistDiscographyResponse(
    val version: String? = null,
    val albums: TidalPagedList? = null,
    val tracks: List<TidalItem>? = null,
)

@Serializable
data class TidalPagedList(
    val items: List<TidalItem>? = null,
    val limit: Long? = null,
    val offset: Long? = null,
    val totalNumberOfItems: Long? = null,
)

// ── Explore response (hot.monochrome.tf) ─────────────────────────────────────

@Serializable
data class ExploreResponse(
    val version: String? = null,
    val repo: String? = null,
    val top_albums: List<TidalItem>? = null,
    val top_tracks: List<TidalItem>? = null,
    val featured_playlists: List<TidalItem>? = null,
    val sections: List<ExploreSection>? = null,
)

@Serializable
data class ExploreSection(
    val title: String? = null,
    val type: String? = null,
    val items: List<TidalItem>? = null,
)

// ── Playlist response ────────────────────────────────────────────────────────

@Serializable
data class PlaylistResponse(
    val version: String? = null,
    val playlist: TidalItem? = null,
    val items: List<TidalItemElement>? = null,
)
