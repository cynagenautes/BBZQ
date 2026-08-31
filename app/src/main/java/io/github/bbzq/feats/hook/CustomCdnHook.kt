package io.github.bbzq.feats.hook

import android.net.Uri
import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.allFields
import io.github.bbzq.feats.allMethods
import io.github.bbzq.feats.callMethod
import io.github.bbzq.feats.hookAfter
import io.github.bbzq.feats.hookBefore
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

/**
 * Rewrites UPos URLs at the protobuf boundary, before they reach the player or downloader.
 *
 * This deliberately uses the PlayView symbols already resolved for the quality hook instead
 * of a fixed app class name: Bilibili moves the client implementation frequently, while the
 * generated protobuf getters/setters remain stable.
 *
 * PCDN blocking works on both ends of the same request: the traffic-free spoof stops the server
 * from picking PCDN endpoints in the first place, and the response rewrite drops whatever PCDN
 * URLs still come back. Either half alone is enough to keep playback off PCDN.
 */
class CustomCdnHook(env: io.github.bbzq.feats.RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        val methods = env.symbols?.tryFreeQuality?.restore(classLoader)?.playViewMethods.orEmpty()
        var installed = 0
        methods.forEach { method ->
            runCatching {
                env.hookBefore(method) { param ->
                    val handler = param.args.getOrNull(1) ?: return@hookBefore
                    wrapHandler(handler)?.let { wrapped -> param.args[1] = wrapped }
                }
                env.hookAfter(method) { param -> rewriteResponse(param.result) }
                installed++
            }.onFailure { log("CustomCdn: failed to hook ${method.declaringClass.name}.${method.name}", it) }
        }
        if (installed == 0) {
            log("CustomCdn: no compatible PlayView method found")
        } else {
            log("CustomCdn: hooked $installed response method(s)")
        }
        installTrafficFreeSpoof()
    }

    /**
     * The server only hands out PCDN/mcdn endpoints when the client reports tf == 0, so raising
     * the reported traffic-free state keeps mirror URLs in the response instead of leaving the
     * rewrite step with nothing but PCDN candidates to work with.
     */
    private fun installTrafficFreeSpoof() {
        val methods = env.symbols?.trafficFree?.restore(classLoader)?.stateMethods.orEmpty()
        if (methods.isEmpty()) {
            log("CustomCdn: traffic-free state method unavailable, PCDN blocking stays response-side only")
            return
        }
        var installed = 0
        methods.forEach { method ->
            runCatching {
                env.hookAfter(method) { param ->
                    if (ModuleSettings.isBlockPcdnEnabled(prefs) && param.result == 0) {
                        param.result = 1
                    }
                }
                installed++
            }.onFailure {
                log("CustomCdn: failed to hook ${method.declaringClass.name}.${method.name}", it)
            }
        }
        log("CustomCdn: traffic-free spoof hooked $installed method(s)")
    }

    private fun wrapHandler(handler: Any): Any? {
        val callback = handler.javaClass.interfaces.firstOrNull { type ->
            type.methods.any { it.name == "onNext" && it.parameterCount == 1 }
        } ?: return null
        return Proxy.newProxyInstance(
            handler.javaClass.classLoader ?: classLoader,
            (handler.javaClass.interfaces.toSet() + callback).toTypedArray(),
        ) { _, method, args ->
            if (method.name == "onNext") rewriteResponse(args?.firstOrNull())
            invokeHandler(handler, method, args)
        }
    }

    private fun invokeHandler(handler: Any, method: Method, args: Array<out Any?>?): Any? = try {
        if (args == null) method.invoke(handler) else method.invoke(handler, *args)
    } catch (throwable: Throwable) {
        throw (throwable as? InvocationTargetException)?.targetException ?: throwable
    }

    private fun rewriteResponse(response: Any?) {
        if (response == null) return
        val options = currentOptions() ?: return
        runCatching {
            sequenceOf(
                response.callMethod("getVideoInfo"),
                response.callMethod("getVodInfo"),
                response.callMethod("getViewInfo"),
                response,
            ).filterNotNull().distinct().forEach { rewriteVideoInfo(it, options) }
        }.onFailure { log("CustomCdn: response rewrite failed", it) }
    }

    /**
     * PCDN 阻止不依赖自定义节点：没有选节点时也能把 base 换成备用地址里的非 PCDN 项。
     */
    private fun currentOptions(): RewriteOptions? {
        val host = ModuleSettings.getCustomCdnHost(prefs)
            ?.takeIf { ModuleSettings.isCustomCdnEnabled(prefs) }
        val blockPCdn = ModuleSettings.isBlockPcdnEnabled(prefs)
        if (host == null && !blockPCdn) return null
        return RewriteOptions(host = host, blockPCdn = blockPCdn)
    }

    private fun rewriteVideoInfo(videoInfo: Any, options: RewriteOptions) {
        val streams = videoInfo.callMethod("getStreamListList")
            ?: videoInfo.callMethod("getStreamList")
        (streams as? Iterable<*>)?.forEach { stream ->
            stream ?: return@forEach
            listOf("getDashVideo", "getMultiDashVideo", "getSegmentVideo")
                .forEach { getter -> stream.callMethod(getter)?.let { rewriteVideoContent(it, options) } }
            stream.callMethod("getContent")?.callMethod("getValue")
                ?.let { rewriteVideoContent(it, options) }
            // 部分版本把音频挂在每个 stream 下，而不是 VodInfo 下。
            rewriteAudioLists(stream, options)
        }
        rewriteAudioLists(videoInfo, options)
    }

    private fun rewriteVideoContent(content: Any, options: RewriteOptions) {
        if (content.callMethod("getBaseUrl") is String || content.callMethod("getUrl") is String) {
            rewriteUrlItem(content, options)
        }
        val dashVideos = content.callMethod("getDashVideosList") ?: content.callMethod("getDashVideos")
        (dashVideos as? Iterable<*>)
            ?.forEach { it?.let { item -> rewriteUrlItem(item, options) } }
        val segments = content.callMethod("getSegmentList") ?: content.callMethod("getSegment")
        (segments as? Iterable<*>)
            ?.forEach { it?.let { item -> rewriteUrlItem(item, options) } }
    }

    private fun rewriteAudioLists(owner: Any, options: RewriteOptions) {
        listOf("getDashAudioList", "getDashAudioListList", "getAudioDashVideoList", "getDashAudio")
            .forEach { getter ->
                when (val result = owner.callMethod(getter)) {
                    is Iterable<*> -> result.forEach { it?.let { item -> rewriteUrlItem(item, options) } }
                    else -> result?.let { rewriteUrlItem(it, options) }
                }
            }
    }

    private fun rewriteUrlItem(item: Any, options: RewriteOptions) {
        val baseGetter = when {
            item.callMethod("getBaseUrl") is String -> "getBaseUrl"
            item.callMethod("getUrl") is String -> "getUrl"
            else -> null
        }
        val baseSetter = when (baseGetter) {
            "getBaseUrl" -> "setBaseUrl"
            "getUrl" -> "setUrl"
            else -> null
        }
        val base = (baseGetter?.let { item.callMethod(it) } as? String)
            ?: findHttpStringFieldValue(item)
            ?: return
        if (base.isBlank()) return
        val rawBackups = item.callMethod("getBackupUrlList")
            ?: item.callMethod("getBackupUrl")
            ?: findUrlListFieldValue(item)
        val backups = (rawBackups as? Iterable<*>)
            ?.filterIsInstance<String>().orEmpty()
        val source = listOf(base).plus(backups).firstOrNull { !it.isPCdn() }
            // A PCDN URL still carries the UPos path and signature, so re-hosting it is the only
            // way out when the server hands back nothing but PCDN endpoints.
            ?: base.takeIf { options.blockPCdn && options.host != null }
            ?: return
        val rewrittenBase = options.rewrite(source)
        val rewrittenBackups = buildList {
            addAll(backups.filter { !it.isPCdn() }.take(2).map(options::rewrite))
            // Keep the untouched source as a final fallback if the selected endpoint is down,
            // unless it is the PCDN URL the user asked us to get rid of.
            if (!source.isPCdn()) add(source)
        }.filter { it != rewrittenBase }.distinct()
        if (rewrittenBase == base && rewrittenBackups == backups) return

        if (baseSetter == null || !invokeOneArg(item, baseSetter, rewrittenBase)) {
            replaceStoredValue(item, base, rewrittenBase)
        }
        clearAndAddBackups(item, rawBackups, rewrittenBackups)
    }

    private fun findHttpStringFieldValue(target: Any): String? =
        target.javaClass.allFields().mapNotNull { field ->
            if (Modifier.isStatic(field.modifiers) || field.type != String::class.java) null
            else runCatching { field.get(target) as? String }.getOrNull()
        }.firstOrNull { it.startsWith("http://") || it.startsWith("https://") }

    private fun findUrlListFieldValue(target: Any): Iterable<*>? =
        target.javaClass.allFields().mapNotNull { field ->
            if (Modifier.isStatic(field.modifiers)) null
            else runCatching { field.get(target) as? Iterable<*> }.getOrNull()
        }.firstOrNull { values ->
            values.all { it is String } && values.any {
                val value = it as String
                value.startsWith("http://") || value.startsWith("https://")
            }
        }

    private fun clearAndAddBackups(item: Any, original: Any?, urls: List<String>) {
        val cleared = invokeNoArg(item, "clearBackupUrl") || invokeNoArg(item, "clearBackupUrlList")
        val added = urls.isEmpty() ||
            invokeOneArg(item, "addAllBackupUrl", urls) ||
            invokeOneArg(item, "addAllBackupUrlList", urls)
        if (!cleared || !added) {
            replaceStoredValue(item, original, urls)
        }
    }

    /**
     * 9.x 的 KPlayerMoss 模型由 kotlinx.serialization 生成，字段是 final 且没有 setter。
     * 通过 getter 返回值定位对应实例字段，可避开每个版本不同的混淆字段名。
     */
    private fun replaceStoredValue(target: Any, original: Any?, replacement: Any): Boolean {
        if (original == null) return false
        return target.javaClass.allFields().firstOrNull { field ->
            !Modifier.isStatic(field.modifiers) && runCatching {
                val value = field.get(target)
                value === original || (original is String && value == original)
            }.getOrDefault(false)
        }?.let { field ->
            runCatching { field.set(target, replacement); true }.getOrDefault(false)
        } ?: false
    }

    /**
     * @param host selected UPos endpoint, or null when only PCDN blocking is enabled.
     * @param blockPCdn drop PCDN endpoints even when that means re-hosting a PCDN URL.
     */
    private inner class RewriteOptions(val host: String?, val blockPCdn: Boolean) {
        fun rewrite(url: String): String = host?.let { url.replaceHost(it) } ?: url
    }

    private fun String.isPCdn(): Boolean {
        val uri = runCatching { Uri.parse(this) }.getOrNull()
        val host = uri?.host.orEmpty()
        return host.matches(Regex("\\d{1,3}(?:\\.\\d{1,3}){3}")) ||
            host.contains(".mcdn.bilivideo", ignoreCase = true) ||
            host.contains("szbdyd.com", ignoreCase = true)
    }

    private fun String.replaceHost(selectedHost: String): String = runCatching {
        val uri = Uri.parse(this)
        // xy_usource is supplied by the server for special routing and must take precedence.
        val host = uri.getQueryParameter("xy_usource").takeUnless { it.isNullOrBlank() } ?: selectedHost
        // Match BiliRoamingX's UPos behaviour: several non-default hosts reject the
        // server-selected bandwidth parameter, so clamp it to the portable value.
        uri.buildUpon().encodedAuthority(host).build().toString()
            .replace(Regex("([?&])bw=[^&]*"), "\$1bw=1280000")
    }.getOrDefault(this)

    private fun invokeNoArg(target: Any, name: String): Boolean =
        target.javaClass.allMethods().firstOrNull { it.name == name && it.parameterCount == 0 }?.let { method ->
            runCatching { method.invoke(target); true }.getOrDefault(false)
        } ?: false

    private fun invokeOneArg(target: Any, name: String, value: Any): Boolean =
        target.javaClass.allMethods().firstOrNull { it.name == name && it.parameterCount == 1 }?.let { method ->
            runCatching { method.invoke(target, value); true }.getOrDefault(false)
        } ?: false

}
