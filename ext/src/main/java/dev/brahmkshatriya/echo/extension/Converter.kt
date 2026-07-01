package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.EchoMediaItem
import dev.brahmkshatriya.echo.common.models.ImageHolder.Companion.toImageHolder
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Track
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// ── Image URL helpers ────────────────────────────────────────────────────────

private fun tidalCoverUrl(coverId: String?, px: Int = 640): String? {
    if (coverId.isNullOrBlank()) return null
    return "https://resources.tidal.com/images/${coverId.replace('-', '/')}/${px}x${px}.jpg"
}

private fun String?.toTidalImage(px: Int = 640) = tidalCoverUrl(this, px)?.toImageHolder(crop = false)
private fun String?.toUrlImage() = if (!isNullOrBlank()) toImageHolder(crop = false) else null

// ── Global (Apple Music) track → Echo Track ──────────────────────────────────

fun TridalTrack.toEchoTrack(): Track {
    val trackId = if (!isrc.isNullOrBlank()) "isrc:$isrc" else (id ?: "0")
    // album can be a String (Apple Music) or an object (Qobuz) — extract title
    val albumTitle = extractAlbumTitle(album)
    val albumCoverFromAlbum = extractAlbumCover(album)
    return Track(
        id = trackId,
        title = title ?: "Unknown",
        cover = (albumCover ?: artwork ?: albumCoverFromAlbum)?.let { it.toUrlImage() },
        artists = if (!artist.isNullOrBlank()) listOf(Artist(id = artistId ?: "0", name = artist)) else emptyList(),
        album = if (!albumTitle.isNullOrBlank()) Album(id = albumId ?: "0", title = albumTitle) else null,
        duration = duration?.let { it * 1000 },
        albumOrderNumber = trackNumber,
        albumDiscNumber = discNumber,
        isrc = isrc,
        isExplicit = explicit == true,
    )
}

// album can be a String (Apple Music: "Album Name") or JsonObject (Qobuz: {"title":..., "image":{...}})
private fun extractAlbumTitle(album: JsonElement?): String? {
    if (album == null) return null
    return when {
        album is JsonPrimitive -> album.content
        album is JsonObject -> album["title"]?.let { it.toString().trim('"') }
            ?: album["title"]?.toString()?.trim('"')
        else -> null
    }
}

private fun extractAlbumCover(album: JsonElement?): String? {
    if (album == null || album !is JsonObject) return null
    val image = album["image"] as? JsonObject ?: return null
    return image["large"]?.toString()?.trim('"')
        ?: image["small"]?.toString()?.trim('"')
        ?: image["thumbnail"]?.toString()?.trim('"')
}

// ── Tidal item → Echo models ─────────────────────────────────────────────────

fun TidalItem.toEchoTrack(px: Int = 640): Track {
    val idStr = id?.jsonToString()?.trim('"') ?: "0"
    return Track(
        id = idStr,
        title = title ?: "Unknown",
        cover = (cover ?: image?.jsonToString() ?: albumCoverFromElement(album)).toTidalImage(px),
        artists = artists.orEmpty().map { Artist(id = it.id?.toString() ?: "0", name = it.name ?: "Unknown") },
        album = albumElementToAlbum(album, px),
        duration = duration?.jsonToLong()?.let { it * 1000 },
        albumOrderNumber = trackNumber?.jsonToLong(),
        albumDiscNumber = volumeNumber?.jsonToLong(),
        isrc = isrc,
        isExplicit = explicit == true,
    )
}

fun TidalItem.toEchoAlbum(px: Int = 640): Album {
    val idStr = id?.jsonToString()?.trim('"') ?: "0"
    return Album(
        id = idStr,
        title = title ?: "Unknown Album",
        cover = (cover ?: image?.jsonToString()).toTidalImage(px),
        artists = artists.orEmpty().map { Artist(id = it.id?.toString() ?: "0", name = it.name ?: "Unknown") },
        trackCount = numberOfTracks?.jsonToLong(),
        duration = duration?.jsonToLong(),
        releaseDate = releaseDate?.let { parseDate(it) },
    )
}

fun TidalItem.toEchoArtist(px: Int = 640): Artist {
    val idStr = id?.jsonToString()?.trim('"') ?: "0"
    return Artist(
        id = idStr,
        name = name ?: title ?: "Unknown Artist",
        cover = (picture ?: cover).toTidalImage(px),
    )
}

fun TidalItem.toEchoPlaylist(px: Int = 640): Playlist {
    return Playlist(
        id = uuid ?: id?.jsonToString()?.trim('"') ?: "0",
        title = title ?: "Unknown Playlist",
        isEditable = false,
        isPrivate = false,
        cover = (squareImage ?: image?.jsonToString()).toTidalImage(px),
        trackCount = numberOfTracks?.jsonToLong(),
        duration = duration?.jsonToLong(),
    )
}

// ── JsonElement helpers (handle both String and Object responses) ──

fun JsonElement?.jsonToString(): String? {
    this ?: return null
    return when (this) {
        is JsonPrimitive -> content
        else -> toString().trim('"')
    }
}

fun JsonElement?.jsonToLong(): Long? {
    this ?: return null
    return when (this) {
        is JsonPrimitive -> content.toLongOrNull() ?: content.trim('"').toLongOrNull()
        else -> null
    }
}

fun String?.toTidalImageSafe(px: Int = 640) = this?.let { id ->
    if (id.isBlank()) null
    else "https://resources.tidal.com/images/${id.replace('-', '/')}/${px}x${px}.jpg".toImageHolder(crop = false)
}

private fun albumCoverFromElement(album: JsonElement?): String? {
    if (album == null || album !is JsonObject) return null
    val cover = album["cover"]?.jsonToString()
    if (!cover.isNullOrBlank()) return cover
    val image = album["image"] as? JsonObject
    return image?.get("large")?.jsonToString()
        ?: image?.get("small")?.jsonToString()
}

private fun albumElementToAlbum(album: JsonElement?, px: Int): dev.brahmkshatriya.echo.common.models.Album? {
    if (album == null) return null
    return when (album) {
        is JsonPrimitive -> dev.brahmkshatriya.echo.common.models.Album(
            id = "0", title = album.content
        )
        is JsonObject -> {
            val title = album["title"]?.jsonToString() ?: "Unknown"
            val cover = album["cover"]?.jsonToString() ?: albumCoverFromElement(album)
            val id = album["id"]?.jsonToString()?.trim('"') ?: "0"
            dev.brahmkshatriya.echo.common.models.Album(
                id = id, title = title, cover = cover.toTidalImage(px)
            )
        }
        else -> null
    }
}

// ── Shelf builders ───────────────────────────────────────────────────────────

fun List<TridalTrack>.toTrackShelf(id: String, title: String): Shelf.Lists.Tracks? {
    if (isEmpty()) return null
    return Shelf.Lists.Tracks(id, title, map { it.toEchoTrack() })
}

fun List<TidalItem>.toTidalTrackShelf(id: String, title: String, px: Int = 640): Shelf.Lists.Tracks? {
    if (isEmpty()) return null
    return Shelf.Lists.Tracks(id, title, map { it.toEchoTrack(px) })
}

fun List<TidalItem>.toAlbumShelf(id: String, title: String, px: Int = 640): Shelf.Lists.Items? {
    if (isEmpty()) return null
    return Shelf.Lists.Items(id, title, map { it.toEchoAlbum(px) as EchoMediaItem })
}

fun List<TidalItem>.toArtistShelf(id: String, title: String, px: Int = 640): Shelf.Lists.Items? {
    if (isEmpty()) return null
    return Shelf.Lists.Items(id, title, map { it.toEchoArtist(px) as EchoMediaItem })
}

fun List<TidalItem>.toPlaylistShelf(id: String, title: String, px: Int = 640): Shelf.Lists.Items? {
    if (isEmpty()) return null
    return Shelf.Lists.Items(id, title, map { it.toEchoPlaylist(px) as EchoMediaItem })
}

// ── Date parsing ─────────────────────────────────────────────────────────────

private fun parseDate(s: String): dev.brahmkshatriya.echo.common.models.Date? {
    return runCatching {
        // Try ISO format: 2023-08-18 or 2023-08-18T00:00:00
        val parts = s.substring(0, 10).split("-")
        if (parts.size == 3) {
            dev.brahmkshatriya.echo.common.models.Date(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
        } else null
    }.getOrNull()
}
