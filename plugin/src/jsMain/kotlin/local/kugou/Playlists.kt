package local.kugou

import kotlinx.serialization.json.*
import org.spotube.plugin_interfaces.plugin_apis.metadata.common.*
import org.spotube.plugin_interfaces.plugin_apis.metadata.playlist.*
import org.spotube.plugin_interfaces.plugin_apis.metadata.track.MetadataTrack

internal class Playlists(
    private val gateway: Gateway,
    private val storage: org.spotube.plugin_interfaces.host_apis.PersistedStorageAPI? = null
): MetadataPlaylistAPI {
    private suspend inline fun <reified T> cachedPage(
        collection: String, p: PaginationStrategy.Page, fetch: () -> PaginationResult<T>
    ): PaginationResult<T> {
        val user = gateway.session()?.user ?: return PaginationResult(emptyList(), 0, null)
        val key = "library.v1.$user.$collection.${p.pageSize}.${p.page}"
        val result = try {
            fetch()
        } catch (e: CloudUnavailable) {
            val saved = storage?.getString(key)
            return saved?.let { runCatching { Json.decodeFromString<PaginationResult<T>>(it) }.getOrNull() }
                ?: PaginationResult(emptyList(), 0, null)
        }
        if (gateway.session()?.user == user) storage?.putString(key, Json.encodeToString(result))
        return result
    }

    private suspend fun cloud(path:String,body: JsonObjectBuilder.()->Unit):JsonObject {
        val account=gateway.session() ?: error("请先登录酷狗")
        val payload=buildJsonObject { put("userid",account.user);put("token",account.token);body() }
        return gateway.get("gateway.kugou.com",path,mapOf("plat" to "1"),"cloudlist.service.kugou.com",authenticated=true,body=payload.toString())
    }
    private fun page(p:PaginationStrategy?) = when(p) {
        null -> PaginationStrategy.Page(1,30)
        is PaginationStrategy.Page -> PaginationStrategy.Page(p.page.coerceAtLeast(1),p.pageSize.coerceIn(1,200))
        is PaginationStrategy.Offset -> {
            val size=p.limit.coerceIn(1,200)
            require(p.offset>=0 && p.offset%size==0) { "请使用按页翻页" }
            PaginationStrategy.Page(1+p.offset/size,size)
        }
        else -> error("此接口使用页码分页")
    }
    override suspend fun savedPlaylists(pagination:PaginationStrategy?):PaginationResult<MetadataPlaylist> {
        val p=page(pagination)
        return cachedPage("lists", p) { playlistPage(p) }
    }

    private suspend fun playlistPage(p: PaginationStrategy.Page): PaginationResult<MetadataPlaylist> {
        val d=cloud("/v7/get_all_list") { put("total_ver",979);put("type",2);put("page",p.page);put("pagesize",p.pageSize) }
        val rows=(d["info"] as? JsonArray ?: d["list"] as? JsonArray).orEmpty()
        val items=rows.mapNotNull { item ->
            val row=item as? JsonObject ?: return@mapNotNull null
            val id=row.value("listid").takeIf { it.toLongOrNull()!=null } ?: return@mapNotNull null
            val cover=row.value("pic","cover").replace("{size}","400").replace("http://","https://")
            MetadataPlaylist(id,row.value("name"),row.value("intro"),if(cover.startsWith("https://")) listOf(Thumbnail(cover,400,400)) else emptyList(),
                row.value("count").toIntOrNull() ?: 0,null,null)
        }
        val total=d.value("total","count").toIntOrNull()
        return PaginationResult(items,total ?: ((p.page-1)*p.pageSize+rows.size),
            if(rows.size==p.pageSize && (total==null || total>p.page*p.pageSize)) p.copy(page=p.page+1) else null)
    }
    override suspend fun getPlaylist(id:String):MetadataPlaylist {
        var next:PaginationStrategy?=null
        do {
            val result=savedPlaylists(next)
            result.items.firstOrNull { it.id==id }?.let { return it }
            next=result.nextPagination
        } while (next != null)
        error("未找到歌单")
    }
    override suspend fun getPlaylistTracks(id:String,pagination:PaginationStrategy?):PaginationResult<MetadataTrack> {
        val p = page(pagination)
        val page = cachedPage("tracks.$id", p) {
            val result = trackPage(id, p)
            val tracks = gateway.fillMissingDurations(gateway.fillQueueMetadata(result.items))
            PaginationResult(tracks, result.totalCount, result.nextPagination)
        }
        // Pages stored by older versions may still contain file extensions.
        return page.copy(items = page.items.map { track ->
            val title = displayTitle(track.title)
            if (title == track.title) track else track.copy(title = title)
        })
    }

    private suspend fun trackPage(id: String, p: PaginationStrategy.Page): PaginationResult<MetadataTrack> {
        val d=cloud("/v4/get_list_all_file_v3") {
            put("listid",id.toLong());put("type",0);put("page",p.page);put("pagesize",p.pageSize);put("area_code",1);put("allplatform",1);put("show_cover",1)
        }
        val rows=(d["songs"] as? JsonArray ?: d["info"] as? JsonArray ?: d["list"] as? JsonArray).orEmpty()
        val tracks=rows.mapNotNull { item ->
            val r=item as? JsonObject ?: return@mapNotNull null
            parseTrack(JsonObject(r+(r["base"] as? JsonObject).orEmpty()+(r["audio_info"] as? JsonObject).orEmpty()))
        }
        val total=d.value("count","total").toIntOrNull() ?: ((p.page-1)*p.pageSize+rows.size)
        return PaginationResult(tracks,total,if(p.page*p.pageSize<total) p.copy(page=p.page+1) else null)
    }
    private suspend fun savedFlags(keys: List<String>, loadPage: suspend (PaginationStrategy.Page) -> Pair<List<String>, PaginationStrategy?>): List<Boolean> {
        val missing = keys.toMutableSet()
        var next: PaginationStrategy.Page? = PaginationStrategy.Page(1, 200)
        while (next != null && missing.isNotEmpty() && gateway.session() != null) {
            val (pageKeys, following) = loadPage(next)
            missing.removeAll(pageKeys.toSet())
            next = following as? PaginationStrategy.Page
        }
        return keys.map { it !in missing }
    }

    override suspend fun isSavedPlaylists(ids: List<String>) = savedFlags(ids) { page ->
        val result = savedPlaylists(page)
        result.items.map { it.id } to result.nextPagination
    }

    suspend fun savedTrackFlags(ids: List<String>): List<Boolean> {
        fun key(id: String) = Recording.parse(id)?.hash?.uppercase() ?: id
        return savedFlags(ids.map(::key)) { page ->
            val result = cachedPage("favorites", page) { trackPage("2", page) }
            result.items.map { key(it.id) } to result.nextPagination
        }
    }
    override suspend fun savePlaylists(ids:List<String>):Unit = error("暂不支持收藏歌单")
    override suspend fun removeSavedPlaylists(ids:List<String>):Unit = error("暂不支持取消收藏歌单")
    override suspend fun createPlaylist(name:String,description:String?,isPublic:Boolean,isCollaborating:Boolean,imageBase64:String,trackIds:List<String>):MetadataPlaylist = error("暂不支持创建歌单")
    override suspend fun updatePlaylist(id:String,name:String?,description:String?,isPublic:Boolean?,isCollaborating:Boolean?,imageBase64:String?,trackIds:List<String>?):MetadataPlaylist = error("暂不支持编辑歌单")
    override suspend fun deletePlaylist(id:String):Unit = error("暂不支持删除歌单")
    override suspend fun addTracksToPlaylist(playlistId:String,trackIds:List<String>):Unit = error("暂不支持添加歌曲")
    override suspend fun removeTracksFromPlaylist(playlistId:String,trackIds:List<String>):Unit = error("暂不支持移除歌曲")
}
