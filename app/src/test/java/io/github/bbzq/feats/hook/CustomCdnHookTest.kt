package io.github.bbzq.feats.hook

import android.content.SharedPreferences
import io.github.bbzq.ModuleSettings
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.Proxy

class CustomCdnHookTest {

    class FakeUrlItem(private var baseUrl: String, backups: List<String>) {
        private val backupUrl = backups.toMutableList()
        fun getBaseUrl(): String = baseUrl
        fun setBaseUrl(value: String) { baseUrl = value }
        fun getBackupUrlList(): List<String> = backupUrl.toList()
        fun clearBackupUrl() = backupUrl.clear()
        fun addAllBackupUrl(values: Iterable<String>) { backupUrl.addAll(values) }
    }

    class FakeStream(private val dashVideo: FakeUrlItem) {
        fun getDashVideo(): FakeUrlItem = dashVideo
    }

    class FakeVodInfo(private val streams: List<FakeStream>) {
        fun getStreamListList(): List<FakeStream> = streams
    }

    class FakeReply(private val vodInfo: FakeVodInfo) {
        fun getVodInfo(): FakeVodInfo = vodInfo
    }

    private fun prefs(values: Map<String, Any>): SharedPreferences =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            values[args?.firstOrNull()] ?: args?.getOrNull(1)
        }.let { it as SharedPreferences }

    @Test
    fun testRpcName() {
        // 同步與非同步入口都要歸到同一個 RPC，才能用生成的類名找到 Req / Reply
        assertEquals("playViewUnite", CustomCdnHook.rpcName("executePlayViewUnite"))
        assertEquals("playViewUnite", CustomCdnHook.rpcName("playViewUnite"))
        assertEquals("playView", CustomCdnHook.rpcName("executePlayView"))
        assertEquals("playView", CustomCdnHook.rpcName("playView"))
    }

    @Test
    fun testRewriteTwiceKeepsOriginalSource() {
        // 同一份回應可能同時經過 Java 與 Kotlin Moss 兩條路徑，第二次改寫不能把原始來源擠出備用清單
        val prefs = prefs(
            mapOf(
                ModuleSettings.KEY_CDN_WIFI_ENABLED to true,
                ModuleSettings.KEY_CDN_WIFI_PRIORITY to "upos-sz-mirrorcos.bilivideo.com,upos-sz-mirrorhw.bilivideo.com",
            ),
        )
        val source = "https://upos-sz-mirrorali.bilivideo.com/upgcxcode/1/2/3/3-1-100.m4s?e=1"
        val item = FakeUrlItem(source, emptyList())
        val reply = FakeReply(FakeVodInfo(listOf(FakeStream(item))))

        CustomCdnProcessor.rewriteResponse(reply, prefs)
        val firstBase = item.getBaseUrl()
        val firstBackups = item.getBackupUrlList()
        CustomCdnProcessor.rewriteResponse(reply, prefs)

        assertEquals("https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/1/2/3/3-1-100.m4s?e=1", firstBase)
        assertEquals(
            listOf("https://upos-sz-mirrorhw.bilivideo.com/upgcxcode/1/2/3/3-1-100.m4s?e=1", source),
            firstBackups,
        )
        assertEquals(firstBase, item.getBaseUrl())
        assertEquals(firstBackups, item.getBackupUrlList())
    }
}
