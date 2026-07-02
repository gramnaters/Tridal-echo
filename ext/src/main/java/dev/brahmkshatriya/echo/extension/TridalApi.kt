package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

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
            .header("User-Agent", "Monochrome/1.0")
            .build()
        val res = client.newCall(req).await()
        val body = res.body.string()
        if (!res.isSuccessful) throw IllegalStateException("TRIDAL $path failed: ${res.code}")
        return body
    }

    suspend fun globalSearch(query: String, limit: Int = 50): List<TridalTrack> {
        val raw = call("/global/search/?term=${enc(query)}&limit=$limit")
        val parsed = json.decodeFromString<GlobalSearchResponse>(raw)
        return parsed.tracks.orEmpty()
    }

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

    suspend fun album(albumId: String): TidalItem {
        val raw = call("/album/?id=${enc(albumId)}")
        val parsed = json.decodeFromString<TidalEnvelope<TidalItem>>(raw)
        return parsed.data ?: throw IllegalStateException("No album data for $albumId")
    }

    suspend fun artist(artistId: String): ArtistResponse {
        val raw = call("/artist/?id=${enc(artistId)}")
        return json.decodeFromString(raw)
    }

    suspend fun playlist(uuid: String): PlaylistResponse {
        val raw = call("/playlist/?id=${enc(uuid)}")
        return json.decodeFromString(raw)
    }

    suspend fun explore(): ExploreResponse {
        val req = Request.Builder()
            .url(EXPLORE_URL)
            .header("Accept", "application/json")
            .header("User-Agent", "Monochrome/1.0")
            .build()
        val res = client.newCall(req).await()
        if (!res.isSuccessful) throw IllegalStateException("Explore failed: ${res.code}")
        return json.decodeFromString(res.body.string())
    }

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

@Serializable
data class GlobalSearchResponse(
    val tracks: List<TridalTrack>? = null,
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
)

@Serializable
data class StreamResponse(
    val streamUrl: String? = null,
    val provider: String? = null,
    val audioQuality: String? = null,
    val mimeType: String? = null,
    val track: JsonElement? = null,
    val bitDepth: JsonElement? = null,
    val sampleRate: JsonElement? = null,
)

@Serializable
data class TidalEnvelope<T>(val data: T? = null)

@Serializable
data class TidalSearchResponse<T>(val data: T? = null)

@Serializable
data class TidalList<T>(val items: List<T>? = null)

@Serializable
data class TidalTopHits<T>(val topHits: List<TidalHit<T>>? = null)

@Serializable
data class TidalHit<T>(val value: T? = null)

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
    val items: List<TidalItemElement>? = null,
    val uuid: String? = null,
)

@Serializable
data class TidalItemElement(val item: TidalItem? = null, val type: String? = null)

@Serializable
data class TidalArtist(
    val id: Long? = null,
    val name: String? = null,
    val picture: String? = null,
)

@Serializable
data class ArtistResponse(val artist: TidalItem? = null)

@Serializable
data class PlaylistResponse(
    val playlist: TidalItem? = null,
    val items: List<TidalItemElement>? = null,
)

@Serializable
data class ExploreResponse(
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
