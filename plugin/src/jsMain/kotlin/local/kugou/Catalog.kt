package local.kugou

import kotlinx.serialization.json.*
import org.spotube.plugin_interfaces.plugin_apis.metadata.album.*
import org.spotube.plugin_interfaces.plugin_apis.metadata.artist.MetadataArtist
import org.spotube.plugin_interfaces.plugin_apis.metadata.common.*
import org.spotube.plugin_interfaces.plugin_apis.metadata.search.*
import org.spotube.plugin_interfaces.plugin_apis.metadata.track.*
import kotlin.js.Date

private fun durationMs(row: JsonObject): Long {
    fun positive(vararg keys: String) = keys.firstNotNullOfOrNull {
        row.value(it).toDoubleOrNull()?.takeIf { value -> value.isFinite() && value > 0 }
    }
    positive("duration_ms", "durationMs")?.let { return it.toLong() }
    val duration = positive("Duration", "duration", "timelen", "timelength", "time_length", "timeLength") ?: return 0
    return (if (duration < 10000) duration * 1000 else duration).toLong()
}

internal fun displayTitle(title: String): String =
    if (title.endsWith(".mp3", ignoreCase = true)) title.dropLast(4).trimEnd().ifEmpty { title } else title

internal fun parseTrack(row: JsonObject): MetadataTrack? {
    val recording = Recording(row.value("FileHash","hash","audio_hash","Hash"),row.value("MixSongID","mixsongid","album_audio_id","add_mixsongid").ifEmpty { "0" },
        row.value("AlbumID","album_id").ifEmpty { "0" })
    if (Recording.parse(recording.id)==null) return null
    val songName=row.value("SongName","songname","OriSongName","song_name")
    val label=songName.ifEmpty { row.value("audio_name","name","filename","FileName") }.trim()
    if (label.isBlank()) return null
    val structured=sequenceOf("singerinfo","authors","Singers","singers").mapNotNull { row[it] as? JsonArray }
        .flatMap { it.asSequence() }.filterIsInstance<JsonObject>().mapNotNull {
            val name=it.value("name","author_name","singername","singer_name","Name")
            if(name.isBlank()) null else MetadataArtist.Basic(it.value("id","author_id","singerid","singer_id","Id").ifEmpty { "name:"+name },name,emptyList(),null)
        }.distinctBy { it.name }.toList()
    val textArtist=row.value("SingerName","singername","singer_name","author_name","singer")
    val prefix=label.substringBefore(" - ","").trim()
    val split=prefix.isNotEmpty() && label.substringAfter(" - ").isNotBlank() &&
        (songName.isEmpty() || prefix.equals(textArtist,true) || structured.any { it.name.equals(prefix,true) })
    val title=displayTitle(if(split) label.substringAfter(" - ").trim() else label)
    val artists=structured.ifEmpty { textArtist.ifEmpty { if(split) prefix else "" }.split("、","&").map(String::trim).filter(String::isNotEmpty)
        .mapIndexed { i,name -> MetadataArtist.Basic(((row["SingerId"] as? JsonArray)?.getOrNull(i) as? JsonPrimitive)?.contentOrNull ?: "name:"+name,name,emptyList(),null) }
    }
    val albumInfo=row["album_info"] as? JsonObject ?: row["albuminfo"] as? JsonObject
    val image=row.value("Image","AlbumImage","sizable_cover","album_sizable_cover","album_img","cover","imgurl","image")
        .ifEmpty { albumInfo?.value("sizable_cover","cover","imgurl").orEmpty() }
        .ifEmpty { (row["trans_param"] as? JsonObject)?.value("union_cover").orEmpty() }
        .replace("{size}","400").replace("http://","https://").let { if(it.startsWith("//")) "https:"+it else it }
    val covers=if(image.startsWith("https://")) listOf(Thumbnail(image,400,400)) else emptyList()
    val album=row.value("AlbumName","album_name").ifEmpty { albumInfo?.value("album_name","name").orEmpty() }.takeIf(String::isNotBlank)?.let {
        MetadataAlbum.Detailed(null,emptyList(),0,recording.album,it,null,covers,MetadataAlbumType.Album,artists,null)
    }
    return MetadataTrack(recording.id,title,durationMs(row),null,null,artists,album,covers,null,null,null,null)
}

internal suspend fun Gateway.fillQueueMetadata(tracks:List<MetadataTrack>):List<MetadataTrack> {
    val ids=tracks.filter { it.artists.isEmpty() || it.thumbnails.isNullOrEmpty() }
        .mapNotNull { Recording.parse(it.id)?.mix?.toLongOrNull() }.filter { it>0 }.distinct()
    if(ids.isEmpty()) return tracks
    val details=mutableMapOf<String,JsonObject>()
    for(batch in ids.chunked(50)) {
        val body=buildJsonObject {
            put("fields","base")
            put("data",buildJsonArray { batch.forEach { id -> add(buildJsonObject { put("entity_id",id) }) } })
        }
        val response=try { get("gateway.kugou.com","/kmr/v2/audio",emptyMap(),"openapi.kugou.com",body=body.toString()) }
        catch(e:kotlinx.coroutines.CancellationException) { throw e }
        catch(_:Exception) { break }
        for(row in (response["data"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()) {
            val base=row["base"] as? JsonObject ?: continue
            details[base.value("album_audio_id")]=JsonObject(row+base)
        }
    }
    return tracks.map { track ->
        val rec=Recording.parse(track.id) ?: return@map track
        val data=details[rec.mix] ?: return@map track
        val full=parseTrack(JsonObject(data+("FileHash" to JsonPrimitive(rec.hash)))) ?: return@map track
        track.copy(title=full.title,durationMs=track.durationMs.takeIf { it>0 } ?: full.durationMs,artists=full.artists.ifEmpty { track.artists },album=full.album ?: track.album,
            thumbnails=full.thumbnails?.takeIf { it.isNotEmpty() } ?: track.thumbnails)
    }
}

internal suspend fun Gateway.fillMissingDurations(tracks: List<MetadataTrack>): List<MetadataTrack> {
    val missing = tracks.filter { it.durationMs <= 0 }.mapNotNull { Recording.parse(it.id) }.distinctBy { it.hash.lowercase() }
    if (missing.isEmpty()) return tracks
    val durations = mutableMapOf<String, Long>()
    for (batch in missing.chunked(50)) {
        val body = buildJsonObject {
            put("appid", 3116)
            put("clientver", 11440)
            put("area_code", 1)
            put("behavior", "play")
            put("relate", 1)
            put("qualities", buildJsonArray { add("128") })
            put("resource", buildJsonArray {
                batch.forEach { add(buildJsonObject {
                    put("id", 0)
                    put("type", "audio")
                    put("hash", it.hash.lowercase())
                    put("album_id", it.album.toLongOrNull() ?: 0L)
                }) }
            })
        }
        val response = try {
            get("gateway.kugou.com", "/v2/get_res_privilege/lite", emptyMap(), "media.store.kugou.com", body = body.toString())
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { break }
        for (row in (response["data"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()) {
            val ms = (row["info"] as? JsonObject)?.value("duration")?.toLongOrNull() ?: continue
            if (ms > 0) durations[row.value("hash").lowercase()] = ms
        }
    }
    return tracks.map { track ->
        val duration = durations[Recording.parse(track.id)?.hash?.lowercase()]
        if (track.durationMs <= 0 && duration != null) track.copy(durationMs = duration) else track
    }
}

internal class Catalog(private val gateway: Gateway,private val playlists: Playlists=Playlists(gateway)): MetadataSearchAPI, MetadataTrackAPI {
    override fun close() {}
    private val recent=mutableMapOf<String,Pair<Double,MetadataTrack>>()
    override val supportedSearchTypes=listOf(MetadataSupportedSearchType.TRACK)
    override suspend fun search(query: String)=searchTracks(query,null).items
    override suspend fun searchTracks(query: String,pagination: PaginationStrategy?): PaginationResult<MetadataSearchResult.Track> {
        val size=(when(pagination) {
            is PaginationStrategy.Page -> pagination.pageSize
            is PaginationStrategy.Offset -> pagination.limit
            else -> 30
        }).coerceIn(1,100)
        val offset=(pagination as? PaginationStrategy.Offset)?.offset?.coerceAtLeast(0)
        val page=offset?.let { it/size+1 } ?: (pagination as? PaginationStrategy.Page)?.page?.coerceAtLeast(1) ?: 1
        val data=gateway.search(query,page,size)
        val rows=data["lists"] as? JsonArray ?: data["info"] as? JsonArray ?: emptyList()
        val tracks=gateway.fillQueueMetadata(rows.drop((offset ?: 0)%size).mapNotNull { (it as? JsonObject)?.let(::parseTrack) })
        for(track in tracks) {
            recent[track.id]=Date.now() to track
            if(recent.size>300) recent.remove(recent.keys.first())
        }
        val total=data.value("total").toIntOrNull() ?: ((page-1)*size+rows.size)
        return PaginationResult(tracks.map { MetadataSearchResult.Track(it) },total,
            if(page*size<total) PaginationStrategy.Page(page+1,size) else null)
    }
    override suspend fun getTrack(id: String): MetadataTrack {
        val cached=recent[id]
        if(cached!=null && Date.now()-cached.first<600000 && !cached.second.thumbnails.isNullOrEmpty()) return cached.second
        val recording=Recording.parse(id) ?: error("无法识别歌曲 ID")
        val entity=recording.mix.toLongOrNull()?.takeIf { it>0 } ?: return cached?.second ?: error("歌曲缺少详情 ID")
        val body=buildJsonObject {
            put("fields","base")
            put("data", buildJsonArray { add(buildJsonObject { put("entity_id",entity) }) })
        }
        val response=gateway.get("gateway.kugou.com","/kmr/v2/audio",emptyMap(),"openapi.kugou.com",body=body.toString())
        val row=(response["data"] as? JsonArray)?.firstOrNull() as? JsonObject
        val base=row?.get("base") as? JsonObject
        if(base==null || base.value("album_audio_id")!=recording.mix) return cached?.second ?: error("未找到歌曲详情")
        val result=parseTrack(JsonObject(row+base+("FileHash" to JsonPrimitive(recording.hash)))) ?: return cached?.second ?: error("歌曲详情缺少名称")
        val track=result.copy(id=id,durationMs=cached?.second?.durationMs?.takeIf { it>0 } ?: result.durationMs)
        recent[id]=Date.now() to track
        if(recent.size>300) recent.remove(recent.keys.first())
        return track
    }
    override suspend fun searchAlbums(query: String,pagination: PaginationStrategy?)=PaginationResult<MetadataSearchResult.Album>(emptyList(),0,null)
    override suspend fun searchArtists(query: String,pagination: PaginationStrategy?)=PaginationResult<MetadataSearchResult.Artist>(emptyList(),0,null)
    override suspend fun searchPlaylists(query: String,pagination: PaginationStrategy?)=PaginationResult<MetadataSearchResult.Playlist>(emptyList(),0,null)
    override suspend fun searchUsers(query: String,pagination: PaginationStrategy?)=PaginationResult<MetadataSearchResult.User>(emptyList(),0,null)
    override suspend fun savedTracks(pagination: PaginationStrategy?): PaginationResult<MetadataTrack> = playlists.getPlaylistTracks("2",pagination)
    override suspend fun isSavedTracks(ids: List<String>) = playlists.savedTrackFlags(ids)
    override suspend fun saveTracks(ids: List<String>): Unit = error("暂不支持收藏")
    override suspend fun removeSavedTracks(ids: List<String>): Unit = error("暂不支持取消收藏")
    override suspend fun recommendationsBasedOnTracks(seedTrackIds: List<String>,limit: Int)=emptyList<MetadataTrack>()
}

