package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.helpers.ContinuationCallback.Companion.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

class TridalApi(private val getBaseUrl: suspend () -> String?) {
    val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun base(): String {
        val custom = getBaseUrl()?.trimEnd('/')
        return if (!custom.isNullOrEmpty()) custom else DEFAULT_URL
    }

    private val tidalInstances = listOf(
        "https://this-shit-is-not-working.anothermoumen4.workers.dev",
        "https://monochrome-api.samidy.com",
        "https://eu-central.monochrome.tf",
        "https://us-west.monochrome.tf",
        "https://api.monochrome.tf",
    )

    private suspend fun callGlobal(path: String): String {
        val req = Request.Builder().url("${base()}$path")
            .header("Accept", "application/json").header("User-Agent", "Monochrome/1.0").build()
        val res = client.newCall(req).await()
        if (!res.isSuccessful) throw IllegalStateException("TRIDAL $path failed: ${res.code}")
        return res.body.string()
    }

    private suspend fun callTidal(path: String): String {
        var lastError: Throwable? = null
        for (instance in tidalInstances) {
            try {
                val req = Request.Builder().url("$instance$path")
                    .header("Accept", "application/json").header("User-Agent", "Monochrome/1.0").build()
                val res = client.newCall(req).await()
                if (res.isSuccessful) return res.body.string()
            } catch (e: Throwable) { lastError = e }
        }
        throw lastError ?: IllegalStateException("All instances failed for $path")
    }

    suspend fun globalSearch(query: String, limit: Int = 50): List<TridalTrack> {
        val raw = callGlobal("/global/search/?term=${enc(query)}&limit=$limit")
        return json.decodeFromString<GlobalSearchResponse>(raw).tracks.orEmpty()
    }

    suspend fun tidalPage(pageId: String): TidalPageResponse {
        val url = "https://tidal.com/v1/$pageId?deviceType=BROWSER&platform=WEB&locale=en_US&countryCode=US"
        val req = Request.Builder().url(url)
            .header("x-tidal-token", "txNoH4kkV41MfH25")
            .header("User-Agent", "Mozilla/5.0").header("Accept", "application/json").build()
        val res = client.newCall(req).await()
        if (!res.isSuccessful) throw IllegalStateException("Tidal page $pageId failed: ${res.code}")
        return json.decodeFromString(res.body.string())
    }

    suspend fun tidalSearchTracks(query: String, limit: Int = 50): List<TidalItem> =
        json.decodeFromString<TidalSearchResponse<TidalList<TidalItem>>>(callTidal("/search/?s=${enc(query)}&limit=$limit")).data?.items.orEmpty()
    suspend fun tidalSearchAlbums(query: String, limit: Int = 50): List<TidalItem> =
        json.decodeFromString<TidalSearchResponse<TidalTopHits<TidalItem>>>(callTidal("/search/?al=${enc(query)}&limit=$limit")).data?.topHits.orEmpty().mapNotNull { it.value }
    suspend fun tidalSearchArtists(query: String, limit: Int = 50): List<TidalItem> =
        json.decodeFromString<TidalSearchResponse<TidalTopHits<TidalItem>>>(callTidal("/search/?a=${enc(query)}&limit=$limit")).data?.topHits.orEmpty().mapNotNull { it.value }.filter { !it.name.isNullOrBlank() }

    suspend fun album(albumId: String): TidalItem = json.decodeFromString<TidalEnvelope<TidalItem>>(callTidal("/album/?id=${enc(albumId)}")).data ?: throw IllegalStateException("No album")
    suspend fun artist(artistId: String): ArtistResponse = json.decodeFromString(callTidal("/artist/?id=${enc(artistId)}"))
    suspend fun playlist(uuid: String): PlaylistResponse = json.decodeFromString(callTidal("/playlist/?id=${enc(uuid)}"))
    suspend fun explore(): ExploreResponse {
        val req = Request.Builder().url(EXPLORE_URL).header("Accept", "application/json").header("User-Agent", "Monochrome/1.0").build()
        val res = client.newCall(req).await()
        if (!res.isSuccessful) throw IllegalStateException("Explore failed: ${res.code}")
        return json.decodeFromString(res.body.string())
    }

    suspend fun getStreamUrlByIsrc(isrc: String, qualities: String): StreamResponse =
        json.decodeFromString(callGlobal("/global/trackStream/?isrc=${enc(isrc)}&qualities=${enc(qualities)}"))

    // Stream via Tidal track ID — look up ISRC first, then use /global/trackStream/
    // This is exactly how the 8spine TRIDAL module works:
    // 1. Look up ISRC via /info/?id=ID
    // 2. Stream via /global/trackStream/?isrc=ISRC (Qobuz CDN, Hi-Res, all qualities)
    suspend fun getStreamUrlByTidalId(trackId: String, quality: String): String {
        val infoRaw = callTidal("/info/?id=${enc(trackId)}")
        val info = json.decodeFromString<TidalEnvelope<TidalItem>>(infoRaw)
        val isrc = info.data?.isrc
            ?: throw IllegalStateException("No ISRC found for track $trackId")
        val qualitiesToTry = mutableListOf(quality)
        when (quality) {
            "HI_RES_LOSSLESS" -> qualitiesToTry.addAll(listOf("LOSSLESS", "HIGH", "LOW"))
            "LOSSLESS" -> qualitiesToTry.addAll(listOf("HIGH", "LOW"))
            "HIGH" -> qualitiesToTry.addAll(listOf("LOSSLESS", "LOW"))
            "LOW" -> qualitiesToTry.addAll(listOf("HIGH", "LOSSLESS"))
        }
        val qualityParam = qualitiesToTry.distinct().joinToString(",")
        val resp = getStreamUrlByIsrc(isrc, qualityParam)
        return resp.streamUrl
            ?: throw IllegalStateException("No stream URL for ISRC $isrc")
    }

    companion object {
        const val DEFAULT_URL = "https://this-shit-is-not-working.anothermoumen4.workers.dev"
        const val EXPLORE_URL = "https://hot.monochrome.tf/"
    }
    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}

@Serializable data class GlobalSearchResponse(val tracks: List<TridalTrack>? = null)
@Serializable data class TridalTrack(
    val id: String? = null, val title: String? = null, val artist: String? = null, val artistId: String? = null,
    val album: JsonElement? = null, val albumId: String? = null, val albumCover: String? = null, val artwork: String? = null,
    val duration: Long? = null, val trackNumber: Long? = null, val discNumber: Long? = null, val isrc: String? = null, val explicit: Boolean? = null,
)
@Serializable data class StreamResponse(
    val streamUrl: String? = null, val provider: String? = null, val mimeType: String? = null,
    val track: JsonElement? = null, val bitDepth: JsonElement? = null, val sampleRate: JsonElement? = null,
)
@Serializable data class TidalTrackResponse(val streamUrl: String? = null, val manifest: String? = null, val manifestMimeType: String? = null, val audioQuality: String? = null)
@Serializable data class TidalTrackEnvelope(val data: TidalTrackResponse? = null)
@Serializable data class ManifestResponse(val urls: List<String>? = null, val mimeType: String? = null, val codecs: String? = null)
@Serializable data class TidalEnvelope<T>(val data: T? = null)
@Serializable data class TidalSearchResponse<T>(val data: T? = null)
@Serializable data class TidalList<T>(val items: List<T>? = null)
@Serializable data class TidalTopHits<T>(val topHits: List<TidalHit<T>>? = null)
@Serializable data class TidalHit<T>(val value: T? = null)
@Serializable data class TidalItem(
    val id: JsonElement? = null, val title: String? = null, val name: String? = null, val artist: JsonElement? = null,
    val artists: List<TidalArtist>? = null, val album: JsonElement? = null, val cover: String? = null, val picture: String? = null,
    val image: JsonElement? = null, val squareImage: String? = null, val duration: JsonElement? = null, val trackNumber: JsonElement? = null,
    val volumeNumber: JsonElement? = null, val isrc: String? = null, val explicit: Boolean? = null, val releaseDate: String? = null,
    val numberOfTracks: JsonElement? = null, val items: List<TidalItemElement>? = null, val uuid: String? = null,
)
@Serializable data class TidalItemElement(val item: TidalItem? = null, val type: String? = null)
@Serializable data class TidalArtist(val id: Long? = null, val name: String? = null, val picture: String? = null)
@Serializable data class ArtistResponse(val artist: TidalItem? = null)
@Serializable data class PlaylistResponse(val playlist: TidalItem? = null, val items: List<TidalItemElement>? = null)
@Serializable data class ExploreResponse(
    val top_albums: List<TidalItem>? = null, val top_tracks: List<TidalItem>? = null,
    val featured_playlists: List<TidalItem>? = null, val sections: List<ExploreSection>? = null,
)
@Serializable data class ExploreSection(val title: String? = null, val type: String? = null, val items: List<TidalItem>? = null)
@Serializable data class TidalPageResponse(val rows: List<TidalPageRow>? = null)
@Serializable data class TidalPageRow(val modules: List<TidalPageModule>? = null)
@Serializable data class TidalPageModule(val type: String? = null, val title: String? = null, val pagedList: TidalPagedList? = null)
@Serializable data class TidalPagedList(val items: List<TidalPageItem>? = null)
@Serializable data class TidalPageItem(
    val apiPath: String? = null, val text: String? = null, val icon: String? = null, val imageId: String? = null,
    val id: JsonElement? = null, val title: String? = null, val name: String? = null, val artist: JsonElement? = null,
    val artists: List<TidalArtist>? = null, val album: JsonElement? = null, val cover: String? = null, val picture: String? = null,
    val image: JsonElement? = null, val squareImage: String? = null, val duration: JsonElement? = null, val trackNumber: JsonElement? = null,
    val volumeNumber: JsonElement? = null, val isrc: String? = null, val explicit: Boolean? = null, val numberOfTracks: JsonElement? = null,
    val uuid: String? = null, val url: String? = null, val item: TidalItem? = null,
)
