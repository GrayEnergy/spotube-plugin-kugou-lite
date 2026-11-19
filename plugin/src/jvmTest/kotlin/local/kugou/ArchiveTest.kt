package local.kugou

import app.cash.zipline.Zipline
import app.cash.zipline.loader.ZiplineFile.Companion.toZiplineFile
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okio.ByteString.Companion.toByteString
import org.spotube.plugin_interfaces.core.*
import org.spotube.plugin_interfaces.host_apis.*
import org.spotube.plugin_interfaces.plugin_apis.core.*
import kotlin.test.*

class ArchiveTest {
    @Test fun initializeArchive() = runBlocking {
        val archive=File("build/distributions").listFiles()!!.single { it.extension=="smplug" }
        ZipFile(archive).use { file ->
            fun bytes(name: String)=file.getInputStream(file.getEntry(name)).use { it.readBytes() }
            val manifest=Json.parseToJsonElement(bytes("manifest.zipline.json").decodeToString()).jsonObject
            Zipline.create(Dispatchers.Unconfined).use { runtime ->
                val values=mutableMapOf<String,String>()
                val crypto=java.lang.reflect.Proxy.newProxyInstance(CryptoAPI::class.java.classLoader, arrayOf(CryptoAPI::class.java)) { _,method,args ->
                    when(method.name) {
                        "generateRandomBytes" -> ByteArray(args[0] as Int)
                        "hash" -> java.security.MessageDigest.getInstance("MD5").digest(args[1] as ByteArray)
                        "close" -> Unit
                        "toString" -> "TestCrypto"
                        else -> error("Unexpected crypto call: "+method.name)
                    }
                } as CryptoAPI
                runtime.bind<CryptoAPI>(CryptoAPI_SERVICE_NAME,crypto)
                val storage=object: PersistedStorageAPI {
                    override suspend fun getString(key:String)=values[key]
                    override suspend fun putString(key:String,value:String) { values[key]=value }
                    override suspend fun remove(key:String) {}
                    override suspend fun getKeys()=values.keys.toList()
                }
                runtime.bind<PersistedStorageAPI>(PersistedStorageAPI_SERVICE_NAME,storage)
                for ((id,module) in manifest.getValue("modules").jsonObject) {
                    val data=bytes(module.jsonObject.getValue("url").jsonPrimitive.content).toByteString()
                    runtime.loadJsModule(data.toZiplineFile().quickjsBytecode.toByteArray(),id)
                }
                val module=manifest.getValue("mainModuleId").jsonPrimitive.content
                runtime.quickJs.evaluate("require('$module').local.kugou.main()")
                runtime.take<Initializer>(Initializer_SERVICE_NAME).initialize()
                val core=runtime.take<CoreAPI>(CoreAPI_SERVICE_NAME)
                assertTrue(core.requiresAuthentication)
                val digest=java.security.MessageDigest.getInstance("MD5").digest("0".repeat(32).toByteArray())
                assertEquals(java.math.BigInteger(1,digest).toString(),values["mid"])
            }
        }
    }
}

