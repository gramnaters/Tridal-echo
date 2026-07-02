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

private fun tidalCoverUrl(coverId: String?, px: Int = 640): String? {
    if (coverId.isNullOrBlank()) return null
    return "https://resources.tidal.com/images/${coverId.replace('-', '/')}/${px}x${px}.jpg"
}

private fun String?.toTidalImage(px: Int = 640) = tidalCoverUrl(this, px)?.toImageHolder(crop = false)
private fun String?.toUrlImage() = if (!isNullOrBlank()) toImageHolder(crop = false) else null

fun JsonElement?.jsonToString(): String? = when (this) {
    null -> null
    is JsonPrimitive -> content
    else -> toString().trim('"')
}

fun JsonElement?.jsonToLong(): Long? = when (this) {
    null -> null
    is JsonPrimitive -> content.toLongOrNull() ?: content.trim('"').toLongOrNull()
    else -> null
}

fun String?.toTidalImageSafe(px: Int = 640) = this?.let { id ->
    if (id.isBlank()) null
    else "https://resources.tidal.com/images/${id.replace('-', '/')}/${px}x${px}.jpg".toImageHolder(crop = false)
}

private fun extractAlbumTitle(album: JsonElement?): String? = when (album) {
    null -> null
    is JsonPrimitive -> album.content
    is JsonObject -> album["title"]?.jsonToString()
    else -> null
}

private fun extractAlbumCover(album: JsonElement?): String? {
    if (album == null || album !is JsonObject) return null
    album["cover"]?.jsonToString()?.let { if (it.isNotBlank()) return it }
    val image = album["image"] as? JsonObject ?: return null
    return image["large"]?.jsonToString() ?: image["small"]?.jsonToString()
}

private fun albumElementToAlbum(album: JsonElement?, px: Int): Album? = when (album) {
    null -> null
    is JsonPrimitive -> Album(id = "0", title = album.content)
    is JsonObject -> Album(
        id = album["id"]?.jsonToString()?.trim('"') ?: "0",
        title = album["title"]?.jsonToString() ?: "Unknown",
        cover = (album["cover"]?.jsonToString() ?: extractAlbumCover(album)).toTidalImage(px),
    )
    else -> null
}

fun TridalTrack.toEchoTrack(): Track {
    val trackId = if (!isrc.isNullOrBlank()) "isrc:$isrc" else (id ?: "0")
    val albumTitle = extractAlbumTitle(album)
    return Track(
        id = trackId,
        title = title ?: "Unknown",
        cover = (albumCover ?: artwork ?: extractAlbumCover(album))?.toUrlImage(),
        artists = if (!artist.isNullOrBlank()) listOf(Artist(id = artistId ?: "0", name = artist)) else emptyList(),
        album = if (!albumTitle.isNullOrBlank()) Album(id = albumId ?: "0", title = albumTitle) else null,
        duration = duration?.let { it * 1000 },
        albumOrderNumber = trackNumber,
        albumDiscNumber = discNumber,
        isrc = isrc,
        isExplicit = explicit == true,
    )
}

fun TidalItem.toEchoTrack(px: Int = 640): Track {
    return Track(
        id = id?.jsonToString()?.trim('"') ?: "0",
        title = title ?: "Unknown",
        cover = (cover ?: image?.jsonToString() ?: extractAlbumCover(album)).toTidalImage(px),
        artists = artists.orEmpty().map { Artist(id = it.id?.toString() ?: "0", name = it.name ?: "Unknown") },
        album = albumElementToAlbum(album, px),
        duration = duration?.jsonToLong()?.let { it * 1000 },
        albumOrderNumber = trackNumber?.jsonToLong(),
        albumDiscNumber = volumeNumber?.jsonToLong(),
        isrc = isrc,
        isExplicit = explicit == true,
    )
}

fun TidalItem.toEchoAlbum(px: Int = 640): Album = Album(
    id = id?.jsonToString()?.trim('"') ?: "0",
    title = title ?: "Unknown Album",
    cover = (cover ?: image?.jsonToString()).toTidalImage(px),
    artists = artists.orEmpty().map { Artist(id = it.id?.toString() ?: "0", name = it.name ?: "Unknown") },
    trackCount = numberOfTracks?.jsonToLong(),
    duration = duration?.jsonToLong(),
)

fun TidalItem.toEchoArtist(px: Int = 640): Artist = Artist(
    id = id?.jsonToString()?.trim('"') ?: "0",
    name = name ?: title ?: "Unknown Artist",
    cover = (picture ?: cover).toTidalImage(px),
)

fun TidalItem.toEchoPlaylist(px: Int = 640): Playlist = Playlist(
    id = uuid ?: id?.jsonToString()?.trim('"') ?: "0",
    title = title ?: "Unknown Playlist",
    isEditable = false,
    isPrivate = false,
    cover = (squareImage ?: image?.jsonToString()).toTidalImage(px),
    trackCount = numberOfTracks?.jsonToLong(),
    duration = duration?.jsonToLong(),
)

fun List<TridalTrack>.toTrackShelf(id: String, title: String): Shelf.Lists.Tracks? =
    if (isEmpty()) null else Shelf.Lists.Tracks(id, title, map { it.toEchoTrack() })

fun List<TidalItem>.toTidalTrackShelf(id: String, title: String, px: Int = 640): Shelf.Lists.Tracks? =
    if (isEmpty()) null else Shelf.Lists.Tracks(id, title, map { it.toEchoTrack(px) })

fun List<TidalItem>.toAlbumShelf(id: String, title: String, px: Int = 640): Shelf.Lists.Items? =
    if (isEmpty()) null else Shelf.Lists.Items(id, title, map { it.toEchoAlbum(px) as EchoMediaItem })

fun List<TidalItem>.toArtistShelf(id: String, title: String, px: Int = 640): Shelf.Lists.Items? =
    if (isEmpty()) null else Shelf.Lists.Items(id, title, map { it.toEchoArtist(px) as EchoMediaItem })

fun List<TidalItem>.toPlaylistShelf(id: String, title: String, px: Int = 640): Shelf.Lists.Items? =
    if (isEmpty()) null else Shelf.Lists.Items(id, title, map { it.toEchoPlaylist(px) as EchoMediaItem })
