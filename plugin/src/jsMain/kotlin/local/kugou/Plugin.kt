package local.kugou

import app.cash.zipline.Zipline
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import net.swiftzer.semver.SemVer
import org.spotube.plugin_interfaces.core.runPluginInitialized
import org.spotube.plugin_interfaces.host_apis.*
import org.spotube.plugin_interfaces.plugin_apis.core.*
import org.spotube.plugin_interfaces.plugin_apis.audio.*
import org.spotube.plugin_interfaces.plugin_apis.metadata.playlist.*
import org.spotube.plugin_interfaces.plugin_apis.lyrics.*
import org.spotube.plugin_interfaces.plugin_apis.metadata.track.*
import org.spotube.plugin_interfaces.plugin_apis.metadata.search.*
import org.spotube.plugin_interfaces.plugin_apis.metadata.browse.*
import kotlin.js.Date

internal class Account(private val storage: PersistedStorageAPI,private val view: WebViewAPI): CoreAPI {
    lateinit var gateway: Gateway
    var session: Session?=null
        private set
    override val loggedInFlow=MutableStateFlow(false)
    override val requiresAuthentication=true
    private var entitlement = "尚未查询会员状态"
    private suspend fun refreshEntitlement() {
        try {
            val data=gateway.get("kugouvip.kugou.com","/v1/get_union_vip",mapOf("busi_type" to "concept"),authenticated=true)
            val active=(data["busi_vip"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.filter { it.value("is_vip")=="1" }
            val end=active.map { it.value("vip_end_time","end_time") }.maxOrNull().orEmpty()
            entitlement=when {
                active.isNotEmpty() || data.value("is_vip")=="1" -> "会员有效 "+end
                data.value("is_vip")=="0" || data["busi_vip"] is JsonArray -> "当前未检测到有效会员"
                else -> "未取得会员状态"
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { entitlement="会员状态暂时无法查询" }
    }
    suspend fun restore() {
        val saved=storage.getString("account") ?: return
        val obj=runCatching { Json.parseToJsonElement(saved).jsonObject }.getOrNull() ?: return
        if(obj.value("token").isNotEmpty() && obj.value("userid").isNotEmpty()) {
            session=Session(obj.value("token"),obj.value("userid"))
            loggedInFlow.value=true
            refreshEntitlement()
        }
    }
    override suspend fun logout() {
        storage.remove("account")
        session=null
        loggedInFlow.value=false
    }
    override suspend fun checkPluginUpdates(currentVersion: SemVer): PluginUpdateInfo?=null
    override fun supportMarkdownText(currentVersion: SemVer)=if (session==null) "请使用酷狗或酷狗概念版扫描授权二维码。" else "已登录。$entitlement。播放音质取决于单曲授权。"
    override suspend fun login() {
        val host="login-user.kugou.com"
        val key=gateway.get(host,"/v2/qrcode",mapOf("appid" to "1001","type" to "1","plat" to "4","srcappid" to "2919",
            "qrcode_txt" to "https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=3116&"),web=true).value("qrcode","key")
        require(Regex("[A-Za-z0-9_-]{1,512}").matches(key)) { "酷狗未返回有效二维码" }
        view.navigateToHTML(qrPage(key))
        try {
            val until=Date.now()+240000
            while(Date.now()<until) {
                delay(2000)
                val result=gateway.get(host,"/v2/get_userinfo_qrcode",mapOf("plat" to "4","srcappid" to "2919","qrcode" to key,"dev" to gateway.mid),web=true)
                when(result.value("status")) {
                    "0" -> error("二维码已过期，请重新登录")
                    "4" -> {
                        val token=result.value("token")
                        val user=result.value("userid")
                        check(token.isNotBlank() && user.toLongOrNull()?.let { it>0 }==true) { "酷狗授权信息不完整" }
                        storage.putString("account",buildJsonObject { put("token",token); put("userid",user) }.toString())
                        session=Session(token,user)
                        loggedInFlow.value=true
            refreshEntitlement()
                        return
                    }
                }
            }
            error("扫码授权超时")
        } finally { view.exitWebView() }
    }
}

/** Convert a 128-bit digest without depending on QuickJS BigInt support. */
internal fun decimalDeviceId(hex: String): String {
    val digits = mutableListOf(0)
    for (nibble in hex) {
        var carry = nibble.digitToInt(16)
        for (i in digits.indices) {
            val value = digits[i] * 16 + carry
            digits[i] = value % 10
            carry = value / 10
        }
        while (carry > 0) {
            digits.add(carry % 10)
            carry /= 10
        }
    }
    return digits.asReversed().joinToString("")
}

@OptIn(ExperimentalJsExport::class)
@JsExport
fun main() {
    val zip=Zipline.get()
    runPluginInitialized {
        val http=zip.take<HttpClientAPI>(HttpClientAPI_SERVICE_NAME)
        val crypto=zip.take<CryptoAPI>(CryptoAPI_SERVICE_NAME)
        val storage=zip.take<PersistedStorageAPI>(PersistedStorageAPI_SERVICE_NAME)
        val account=Account(storage,zip.take<WebViewAPI>(WebViewAPI_SERVICE_NAME))
        val md5: suspend (String)->String={ crypto.hash(HashAlgorithms.MD5,it.encodeToByteArray()).toHexString() }
        val mid=storage.getString("mid") ?: run {
            val hex=md5(crypto.generateRandomBytes(16).toHexString())
            val decimal=decimalDeviceId(hex)
            storage.putString("mid",decimal)
            decimal
        }
        val gateway=Gateway(mid,"-",md5,{ url,headers ->
            val result=http.request(HttpMethod.Get,url,headers,null)
            Reply(result.statusCode,result.body.orEmpty())
        },{ account.session },account::logout) { url,headers,body ->
            val result=http.request(HttpMethod.Post,url,headers,body)
            Reply(result.statusCode,result.body.orEmpty())
        }
        account.gateway=gateway
        account.restore()
        val playlists=Playlists(gateway, storage)
        zip.bind<MetadataBrowseAPI>(MetadataBrowseAPI_SERVICE_NAME, Browse(playlists))
        zip.bind<MetadataPlaylistAPI>(MetadataPlaylistAPI_SERVICE_NAME,playlists)
        val catalog=Catalog(gateway,playlists)
        val media=Media(gateway,catalog)
        zip.bind<CoreAPI>(CoreAPI_SERVICE_NAME,account)
        zip.bind<MetadataSearchAPI>(MetadataSearchAPI_SERVICE_NAME,catalog)
        zip.bind<MetadataTrackAPI>(MetadataTrackAPI_SERVICE_NAME,catalog)
        zip.bind<AudioAPI>(AudioAPI_SERVICE_NAME,media)
        zip.bind<LyricsAPI>(LyricsAPI_SERVICE_NAME,media)
    }
}



