package local.kugou

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import kotlin.js.Date

internal fun JsonObject.value(vararg keys: String): String =
    keys.firstNotNullOfOrNull { (get(it) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }.orEmpty()

internal data class Recording(val hash: String, val mix: String, val album: String) {
    val id get() = "kugou:$hash:$mix:$album"
    companion object {
        fun parse(id: String): Recording? {
            val match = Regex("kugou:([0-9a-fA-F]{32}):([0-9]+):([0-9]+)").matchEntire(id) ?: return null
            return Recording(match.groupValues[1], match.groupValues[2], match.groupValues[3])
        }
    }
}

internal data class Session(val token: String, val user: String)
internal class CloudUnavailable(message: String) : Exception(message)
internal data class Reply(val status: Int, val body: String)
internal typealias Transport = suspend (String, Map<String,String>) -> Reply
internal external fun encodeURIComponent(value: String): String

internal class Gateway(
    val mid: String, val dfid: String, val digest: suspend (String) -> String,
    val transport: Transport, val session: () -> Session?, val expire: suspend () -> Unit,
    val post: suspend (String, Map<String,String>, String) -> Reply = { _,_,_ -> error("缺少网络服务") }
) {
    suspend fun get(host: String, path: String, extra: Map<String,String>, router: String? = null,
                    web: Boolean = false, authenticated: Boolean = false, body: String? = null): JsonObject {
        require(host in setOf("gateway.kugou.com","login-user.kugou.com","kugouvip.kugou.com","lyrics.kugou.com"))
        val params = mutableMapOf<String,String>()
        if (host != "lyrics.kugou.com") {
            params.putAll(mapOf("mid" to mid,"dfid" to dfid,"uuid" to "-","appid" to "3116",
                "clientver" to "11440","clienttime" to (Date.now()/1000).toLong().toString()))
            if (authenticated) session()?.let { params.putAll(mapOf("userid" to it.user,"token" to it.token)) }
        }
        params.putAll(extra)
        if (host != "lyrics.kugou.com") {
            val salt = if (web) "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt" else "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"
            val canonical = params.keys.sorted().joinToString("") { it+"="+params.getValue(it) }
            params["signature"] = digest(salt+canonical+body.orEmpty()+salt)
        }
        val url = "https://"+host+path+"?"+params.entries.joinToString("&") { it.key+"="+encodeURIComponent(it.value) }
        val headers = mutableMapOf("mid" to mid,"dfid" to dfid,"User-Agent" to "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi")
        router?.let { headers["x-router"]=it }
        if(path=="/kmr/v2/audio") headers["KG-TID"]="238"
        suspend fun request() = withTimeout(12000) { if(body==null) transport(url,headers) else post(url,headers+("Content-Type" to "application/json"),body) }
        val reply = try { val first=request(); if (first.status in 502..504) { kotlinx.coroutines.delay(300); request() } else first }
        catch (e: TimeoutCancellationException) { throw CloudUnavailable("连接酷狗超时") }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { throw CloudUnavailable("酷狗网络连接失败") }
        when(reply.status) {
            401 -> { if (authenticated && session()!=null) expire(); error("需要重新登录酷狗") }
            403 -> error("酷狗拒绝此请求")
            429, in 500..599 -> throw CloudUnavailable("酷狗服务暂时不可用")
        }
        check(reply.status in 200..299) { "酷狗服务异常 HTTP "+reply.status }
        val json = runCatching { Json.parseToJsonElement(reply.body).jsonObject }.getOrNull() ?: error("酷狗响应格式异常")
        val code = json.value("error_code","errcode","err_code").toIntOrNull() ?: 0
        if (authenticated && code in setOf(20018,20017,34175)) { expire(); error("登录会话已过期") }
        check(json.value("status")!="0" && code in setOf(0,200)) { "酷狗拒绝请求，业务码 "+code }
        return json["data"] as? JsonObject ?: json
    }

    suspend fun search(keyword: String,page: Int,size: Int) = get("gateway.kugou.com","/v2/search/song",
        mapOf("keyword" to keyword,"page" to page.toString(),"pagesize" to size.toString(),"platform" to "AndroidFilter",
            "iscorrection" to "1","privilegefilter" to "0","area_code" to "1","dopicfull" to "1","clientver" to "201"),"complexsearch.kugou.com")

    suspend fun authorize(recording: Recording): Map<String,String> {
        if (session()==null) return emptyMap()
        val router="trackercdngz.kugou.com"
        val host="gateway.kugou.com"
        val auth=get(host,"/v1/user_verify",mapOf("module_id" to "51"),router,authenticated=true).value("auth")
        check(auth.isNotEmpty()) { "酷狗未授予播放权限" }
        val data=get(host,"/v1/authorization",mapOf("authorization" to auth,"module_id" to "51",
            "album_audio_id" to recording.mix,"clientver" to "11561","hash" to recording.hash.lowercase()),router,authenticated=true)
        return mapOf("auth" to data.value("auth").ifEmpty { auth },"module_id" to "51","clientver" to "11561",
            "open_time" to data.value("open_time","openTime","time").ifEmpty { "0" },"need_m" to "0","ptype" to "0","mtype" to "0")
    }

    suspend fun playback(recording: Recording,quality: String,authorization: Map<String,String> = emptyMap()): JsonObject {
        val hash=recording.hash.lowercase()
        val key=digest(hash+"185672dd44712f60bb1736df5a377e82"+"3116"+mid+(session()?.user ?: "0"))
        return get("gateway.kugou.com","/v5/url",mapOf("album_id" to recording.album,"album_audio_id" to recording.mix,
            "hash" to hash,"key" to key,"quality" to quality,"clientver" to "11430","version" to "11430","area_code" to "1",
            "ssa_flag" to "is_fromtrack","page_id" to "967177915","behavior" to "play","pid" to "411","cmd" to "26",
            "pidversion" to "3001","IsFreePart" to "0","ppage_id" to "356753938,823673182,967485191","cdnBackup" to "1","ssl" to "1","module" to "")+authorization,
            "trackercdn.kugou.com",authenticated=true)
    }
}




