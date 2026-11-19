package local.kugou

import kotlinx.serialization.json.*
import org.spotube.plugin_interfaces.plugin_apis.audio.*
import org.spotube.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack
import org.spotube.plugin_interfaces.plugin_apis.lyrics.*
import kotlin.io.encoding.Base64
import kotlin.math.abs

internal class Media(private val gateway: Gateway, private val catalog: Catalog): AudioAPI,LyricsAPI {
    override fun close() {}
    override val supportedQualities=listOf(AudioFormat("mp3","mp3",listOf(AudioQuality.Lossy(128),AudioQuality.Lossy(320))),
        AudioFormat("flac","flac",listOf(AudioQuality.Lossless(44100,2))))
    private suspend fun match(track: MetadataTrack): MetadataTrack? {
        if(Recording.parse(track.id)!=null) return track
        if(track.artists.isEmpty() || track.durationMs<=0) return null
        val query=track.artists.first().name+" "+track.title
        return catalog.searchTracks(query,null).items.map { it.data }.filter { candidate ->
            candidate.title.equals(track.title,true) && abs(track.durationMs-candidate.durationMs)<=2000 &&
            track.artists.any { a -> candidate.artists.any { it.name.equals(a.name,true) } } &&
            (track.album==null || track.album?.title.equals(candidate.album?.title,true))
        }.distinctBy { it.id }.singleOrNull()
    }
    override suspend fun getStreamsByTrack(track: MetadataTrack): List<AudioSource> {
        val item=match(track) ?: return emptyList()
        return listOf(AudioSource.Basic(item.id,item.title,item.artists.joinToString("、") { it.name },item.album?.title,
            item.thumbnails.orEmpty(),null,if(item.id==track.id) 1f else 0.9f))
    }
    override suspend fun getStreamsOfAudioSource(source: AudioSource.Basic): List<AudioSource.Streamed> {
        val recording=Recording.parse(source.id) ?: return emptyList()
        val authorization=gateway.authorize(recording)
        for(q in listOf("high","flac","320","128")) {
            val result=gateway.playback(recording,q,authorization)
            val url=listOf("url","backupUrl","backup_url").flatMap { key -> (result[key] as? JsonArray).orEmpty() }
                .mapNotNull { (it as? JsonPrimitive)?.content }.firstOrNull { it.startsWith("https://") } ?: continue
            if (result.value("is_full_audio")=="0" || "/p_0_" in url) continue
            val ext=result.value("extName","ext").ifEmpty { url.substringBefore('?').substringAfterLast('.') }.lowercase()
            val stream=when(ext) {
                "mp3" -> AudioStream.Lossy(url,ext,ext,bitrate=(result.value("bitRate","bitrate").toIntOrNull() ?: continue).let { if(it>10000) it/1000 else it })
                "flac" -> AudioStream.Lossless(url,ext,ext,sampleRate=result.value("sampleRate","sample_rate").toIntOrNull() ?: continue,
                    channels=result.value("channels","channel").toIntOrNull() ?: continue)
                else -> continue
            }
            return listOf(AudioSource.Streamed(source.id,source.title,source.artist,source.album,source.thumbnails,null,source.confidence,listOf(stream)))
        }
        error(if(gateway.session()==null) "无可用音源，请登录酷狗后重试" else "当前账号无可用音源，请检查歌曲版权或会员权益")
    }
    override suspend fun getLyrics(track: MetadataTrack): LyricsResponse? {
        val recording=match(track)?.id?.let(Recording::parse) ?: return null
        val host="lyrics.kugou.com"
        val search=gateway.get(host,"/search",mapOf("ver" to "1","man" to "yes","client" to "mobi","keyword" to "",
            "duration" to "","hash" to recording.hash,"album_audio_id" to recording.mix))
        for(candidate in (search["candidates"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().take(3)) {
        if(candidate.value("id").isEmpty() || candidate.value("accesskey").isEmpty()) continue
        val data=gateway.get(host,"/download",mapOf("ver" to "1","client" to "android","id" to candidate.value("id"),
            "accesskey" to candidate.value("accesskey"),"fmt" to "lrc","charset" to "utf8"))
        val content=data.value("content").filterNot(Char::isWhitespace)
        val raw=runCatching { Base64.decode(content).decodeToString() }.getOrNull()
        if(!raw.isNullOrBlank()) return parseLyrics(raw.removePrefix("\uFEFF"))
        }
        return null
    }
}

internal fun parseLyrics(raw: String): LyricsResponse {
    val stamp=Regex("""\[(\d+):(\d{2})(?:[.:](\d{1,3}))?\]""")
    val lines=raw.lineSequence().flatMap { line ->
        stamp.findAll(line).map {
            val (min,sec,fraction)=it.destructured
            LyricsLine(min.toLong()*60000+sec.toLong()*1000+fraction.padEnd(3,'0').toLong(),stamp.replace(line,"").trim())
        }
    }.sortedBy { it.time }.toList()
    return LyricsResponse(lines.ifEmpty { null },if(lines.isEmpty()) raw else lines.joinToString("\n") { it.text })
}



