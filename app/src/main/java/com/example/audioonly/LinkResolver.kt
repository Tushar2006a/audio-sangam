package com.example.audioonly

import android.net.Uri
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns pasted links into tracks, and track pages into real audio-only stream URLs.
 *
 * Sites the extractor knows (YouTube, SoundCloud, Bandcamp, PeerTube, ...) are looked up.
 * Any other http(s) link is treated as a direct audio / video / radio-stream URL.
 */
object LinkResolver {

    private const val MAX_PLAYLIST_ITEMS = 300
    private const val CACHE_MS = 3 * 60 * 60 * 1000L // stream URLs expire after a few hours

    data class Track(
        val pageUrl: String,
        val title: String,
        val artist: String?,
        val artworkUrl: String?
    )

    private class Cached(val streamUrl: String, val savedAt: Long)

    private val cache = ConcurrentHashMap<String, Cached>()

    /** Blocking (network). Call from a background thread. */
    fun loadTracks(text: String): List<Track> {
        val url = Regex("https?://\\S+").find(text)?.value
            ?: throw IOException("That doesn't look like a link")

        val service: StreamingService = try {
            NewPipe.getServiceByUrl(url)
        } catch (e: Exception) {
            // Not a known site: try it as a direct audio/video file or radio stream.
            val name = Uri.parse(url).lastPathSegment ?: url
            return listOf(Track(url, name, null, null))
        }

        return when (service.getLinkTypeByUrl(url)) {
            StreamingService.LinkType.STREAM -> {
                val info = StreamInfo.getInfo(service, url)
                cache[url] = Cached(pickAudioUrl(info), System.currentTimeMillis())
                listOf(
                    Track(
                        url,
                        info.name,
                        info.uploaderName,
                        info.thumbnails.firstOrNull()?.url
                    )
                )
            }

            StreamingService.LinkType.PLAYLIST -> {
                val info = PlaylistInfo.getInfo(service, url)
                val tracks = ArrayList<Track>()
                info.relatedItems.forEach { tracks.add(toTrack(it)) }
                var page = info.nextPage
                while (page != null && tracks.size < MAX_PLAYLIST_ITEMS) {
                    val more = PlaylistInfo.getMoreItems(service, url, page)
                    more.items.forEach { tracks.add(toTrack(it)) }
                    page = more.nextPage
                }
                if (tracks.isEmpty()) throw IOException("The playlist is empty or private")
                tracks
            }

            else -> throw IOException("That link isn't a single video/song or a playlist")
        }
    }

    /**
     * Called by the player right before a track starts loading.
     * Returns the real audio URL, or null if [pageUrl] is already a direct media link.
     */
    fun streamUrlFor(pageUrl: String): String? {
        val hit = cache[pageUrl]
        if (hit != null && System.currentTimeMillis() - hit.savedAt < CACHE_MS) return hit.streamUrl

        val service = try {
            NewPipe.getServiceByUrl(pageUrl)
        } catch (e: Exception) {
            return null
        }
        val streamUrl = pickAudioUrl(StreamInfo.getInfo(service, pageUrl))
        cache[pageUrl] = Cached(streamUrl, System.currentTimeMillis())
        return streamUrl
    }

    private fun toTrack(item: StreamInfoItem) = Track(
        item.url,
        item.name,
        item.uploaderName,
        item.thumbnails.firstOrNull()?.url
    )

    private fun pickAudioUrl(info: StreamInfo): String {
        val best = info.audioStreams
            .filter {
                it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                    it.isUrl &&
                    !it.content.isNullOrEmpty()
            }
            .maxByOrNull { it.averageBitrate }
        return best?.content
            ?: throw IOException("No playable audio found (live streams aren't supported yet)")
    }
}
