package local.kugou

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.spotube.plugin_interfaces.plugin_apis.audio.AudioSource
import kotlin.test.*

class ProviderTest {
    private val hash="0123456789ABCDEF0123456789ABCDEF"
    private fun api(session: Session?=null, expire: suspend ()->Unit={}, fetch: Transport)=
        Gateway("123","-",{ "test-digest" },fetch,{session},expire)
    private fun basic()=AudioSource.Basic("kugou:"+hash+":12:3","歌曲","歌手",null,emptyList(),null,1f)
    @Test fun metadata() {
        val row=Json.parseToJsonElement("""{"FileHash":"$hash","MixSongID":12,"AlbumID":"3","Duration":180,"SongName":"歌曲","SingerName":"歌手","Image":"https://example.org/{size}.jpg"}""").jsonObject
        val track=assertNotNull(parseTrack(row))
        assertEquals(180000L,track.durationMs)
        assertEquals("12",Recording.parse(track.id)?.mix)
        assertEquals("歌手",track.artists.single().name)
        assertEquals("https://example.org/400.jpg",track.thumbnails?.single()?.url)
    }
    @Test fun coldTrackDetailsAndLyrics()=runTest {
        val encoded=kotlin.io.encoding.Base64.encode("[00:01.50]一句 [合唱]".encodeToByteArray())
        val gateway=Gateway("123","-",{"digest"},{url,_ ->
            when {
                "/search?" in url -> Reply(200,"""{"status":200,"errcode":200,"candidates":[{"id":"1","accesskey":"key"},{"id":"2","accesskey":"key"}]}""")
                "id=1" in url -> Reply(200,"""{"content":""}""")
                else -> Reply(200,"""{"content":"$encoded"}""")
            }
        },{null},{}) { _,headers,_ ->
            assertEquals("238",headers["KG-TID"])
            Reply(200,"""{"data":[{"base":{"album_audio_id":12,"album_id":3,"songname":"歌曲","author_name":"歌手"},"album_info":{"cover":"http://example.org/{size}.jpg","album_name":"专辑"}}]}""")
        }
        val catalog=Catalog(gateway)
        val track=catalog.getTrack(basic().id)
        assertEquals("专辑",track.album?.title)
        assertEquals("歌手",track.artists.single().name)
        assertEquals("https://example.org/400.jpg",track.thumbnails?.single()?.url)
        val line=Media(gateway,catalog).getLyrics(track)?.syncedLyrics?.single()
        assertEquals(1500L,line?.time)
        assertEquals("一句 [合唱]",line?.text)
    }
    @Test fun cloudPlaylistToMediaCard()=runTest {
        val gateway=Gateway("123","-",{"digest"},{_,_->error("unexpected GET")},{Session("test","1")},{}) { url,_,_ ->
            if("/v4/get_list" in url)
                Reply(200,"""{"data":{"songs":[{"hash":"$hash","mixsongid":133320627,"filename":"MINMI - 四季ノ唄 (四季之歌)","duration":298}],"count":1}}""")
            else Reply(200,"""{"data":[{"base":{"album_audio_id":133320627,"album_id":16058348,"songname":"四季ノ唄 (四季之歌)","author_name":"MINMI","album_name":"MINMI BEST 2002-2008"},"album_info":{"cover":"http://imge.kugou.com/stdmusic/{size}/20241227/20241227180003452485.jpg"}}]}""")
        }
        val track=Playlists(gateway).getPlaylistTracks("2",null).items.single()
        assertEquals("MINMI",track.artists.single().name)
        assertEquals("四季ノ唄 (四季之歌)",track.title)
        assertEquals(298000L,track.durationMs)
        assertEquals("https://imge.kugou.com/stdmusic/400/20241227/20241227180003452485.jpg",track.thumbnails!!.single().url)
        val offline=parseTrack(Json.parseToJsonElement("""{"hash":"$hash","filename":"MINMI - 四季ノ唄"}""").jsonObject)!!
        assertEquals("MINMI",offline.artists.single().name)
        assertEquals("四季ノ唄",offline.title)
    }
    @Test fun playlistScreenSavedStatus() = runTest {
        val gateway = Gateway("123", "-", {"digest"}, {_,_ -> error("unexpected GET")}, {Session("test","1")}, {}) { url,_,_ ->
            when {
                "/v7/get_all_list" in url -> Reply(200, """{"data":{"info":[{"listid":2,"name":"Favorites"}],"total":1}}""")
                "/v4/get_list_all_file_v3" in url -> Reply(200, """{"data":{"songs":[{"hash":"$hash","filename":"Artist - Song"}],"count":1}}""")
                else -> error("unexpected request")
            }
        }
        val playlists = Playlists(gateway)
        assertEquals(listOf(true, false), playlists.isSavedPlaylists(listOf("2","9")))
        val id = basic().id
        assertEquals(listOf(true, false, true), Catalog(gateway, playlists).isSavedTracks(listOf(id, "missing", id)))
    }
    @Test fun homeAndLibrarySurviveOfflineRestart() = runTest {
        val values = mutableMapOf<String,String>()
        val storage = object : org.spotube.plugin_interfaces.host_apis.PersistedStorageAPI {
            override suspend fun getString(key: String) = values[key]
            override suspend fun putString(key: String, value: String) { values[key] = value }
            override suspend fun getKeys() = values.keys.toList()
            override suspend fun remove(key: String) { values.remove(key) }
        }
        var online = true
        var user = "1"
        val gateway = Gateway("123", "-", {"digest"}, {_,_ -> error("unexpected GET")}, {Session("test",user)}, {}) { url,_,_ ->
            if (!online) error("offline")
            when {
                "/v7/get_all_list" in url -> Reply(200, """{"data":{"info":[{"listid":2,"name":"Favorites"}],"total":1}}""")
                "/v4/get_list" in url -> Reply(200, """{"data":{"songs":[{"hash":"$hash","filename":"Artist - Song","timelen":0}],"count":1}}""")
                "/v2/get_res_privilege" in url -> Reply(200, """{"data":[{"hash":"$hash","info":{"duration":50677}}]}""")
                else -> error("unexpected POST")
            }
        }
        for (connected in listOf(true,false)) {
            online = connected
            val library = Playlists(gateway, storage)
            val browse = Browse(library)
            assertEquals("Favorites", library.savedPlaylists(null).items.single().title)
            assertEquals("Favorites", library.getPlaylist("2").title)
            assertEquals(50677L, library.getPlaylistTracks("2",null).items.single().durationMs)
            assertEquals(1, browse.list(browse.genres().single().id,null).items.single().items.size)
        }
        user = "2"
        assertTrue(Playlists(gateway, storage).savedPlaylists(null).items.isEmpty())
    }
    @Test fun playlistDurationFields() {
        fun millis(fields: String) = parseTrack(Json.parseToJsonElement("""{"hash":"$hash","filename":"Song",$fields}""").jsonObject)?.durationMs
        assertEquals(252000L, millis(""""duration":0,"timelen":252000"""))
        assertEquals(298500L, millis(""""Duration":"298.5"""".trim()))
        assertEquals(3200L, millis(""""duration_ms":3200"""))
    }
    @Test fun trackTitlesWithoutMp3Extension() {
        fun title(fields: String) = parseTrack(Json.parseToJsonElement("""{"hash":"$hash",$fields}""").jsonObject)?.title
        assertEquals("恶魔之子 (Live)", title(""""filename":"Artist - 恶魔之子 (Live).mp3""""))
        assertEquals("One Last Kiss", title(""""SongName":"One Last Kiss.MP3""""))
        assertEquals("demo.mp3 remix", title(""""songname":"demo.mp3 remix""""))
    }
    @Test fun lyrics() {
        val lines=parseLyrics("[00:01.5][01:01.05]重复").syncedLyrics!!
        assertEquals(listOf(1500L,61050L),lines.map { it.time })
        assertNull(parseLyrics("纯文本").syncedLyrics)
    }
    @Test fun rejectedSession()=runTest {
        var expired=false
        val gateway=api(Session("sample","1"),{expired=true}) { _,_-> Reply(200,"""{"error_code":20018}""") }
        assertFails { gateway.playback(Recording.parse(basic().id)!!,"128") }
        assertTrue(expired)
    }
    @Test fun deniedPlayback()=runTest {
        val gateway=api(Session("sample","1")) { _,_-> Reply(200,"""{"status":2,"priv_status":1}""") }
        assertFails { Media(gateway,Catalog(gateway)).getStreamsOfAudioSource(basic()) }
    }
    @Test fun refreshAndAnonymous()=runTest {
        var calls=0
        val gateway=api { url,_->
            assertContains(url,"ssl=1")
            assertFalse(url.contains("token="))
            calls++
            Reply(200,"""{"status":1,"url":["https://example.org/$calls.mp3"],"bitRate":128,"extName":"mp3"}""")
        }
        val media=Media(gateway,Catalog(gateway))
        val first=media.getStreamsOfAudioSource(basic())
        val second=media.getStreamsOfAudioSource(basic())
        assertNotEquals(first.single().streams.first().url,second.single().streams.first().url)
    }
    @Test fun rejectDifferentRecording()=runTest {
        val gateway=api { _,_->Reply(200,"""{"status":1,"data":{"lists":[{"FileHash":"$hash","SongName":"Song (Live)","SingerName":"Artist","Duration":100}]}}""") }
        val studio=parseTrack(Json.parseToJsonElement("""{"FileHash":"$hash","SongName":"Song","SingerName":"Artist","Duration":100}""").jsonObject)!!
        assertTrue(Media(gateway,Catalog(gateway)).getStreamsByTrack(studio.copy(id="external")).isEmpty())
    }
    @Test fun timeout()=runTest {
        val gateway=api { _,_->delay(20000);Reply(200,"{}") }
        assertContains(assertFails { gateway.search("test",1,1) }.message.orEmpty(),"超时")
    }
}

