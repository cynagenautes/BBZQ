package io.github.bbzq.feats.hook

import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.hookAfter
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Rewrites UPos URLs at the protobuf boundary, before they reach the player or downloader.
 *
 * PlayView replies arrive through three paths: the blocking `execute*` call, the Java
 * `MossResponseHandler` callback and the Kotlin Moss callback behind the suspend API.
 * Only the first one returns the reply, so the callbacks are wrapped as well.
 */
class CustomCdnHook(env: RoamingEnv) : BaseRoamingHook(env) {
    private val activeLogged = AtomicBoolean(false)

    override fun startHook() {
        val methods = env.symbols?.tryFreeQuality?.restore(classLoader)?.playViewMethods.orEmpty()
        val filter: (Any) -> Any = { reply ->
            rewrite(reply)
            reply
        }
        var installed = 0
        methods.groupBy { it.declaringClass to rpcName(it.name) }.forEach { (key, group) ->
            val (owner, rpc) = key
            val packageName = owner.name.substringBeforeLast('.')
            val type = rpc.replaceFirstChar(Char::uppercaseChar)
            val moss = runCatching {
                env.hookJavaMoss(owner.name, rpc, "$packageName.${type}Req", "$packageName.${type}Reply", ::isEnabled, filter) +
                    env.hookKotlinMoss(owner.name, rpc, "$packageName.${type}Reply", ::isEnabled, filter = filter)
            }.onFailure { log("CustomCdn: failed to hook ${owner.name}.$rpc callbacks", it) }.getOrDefault(0)
            installed += if (moss > 0) moss else group.count(::hookReturnedReply)
        }
        if (installed == 0) {
            log("CustomCdn: no compatible PlayView method found")
        } else {
            log("CustomCdn: hooked $installed response path(s)")
        }
    }

    /** Fallback for a PlayView method whose Moss owner does not follow the generated naming. */
    private fun hookReturnedReply(method: Method): Boolean {
        if (method.returnType == Void.TYPE || method.parameterTypes.lastOrNull()?.name == CONTINUATION) return false
        return runCatching {
            env.hookAfter(method) { param -> if (isEnabled()) param.result?.let(::rewrite) }
            true
        }.onFailure { log("CustomCdn: failed to hook ${method.declaringClass.name}.${method.name}", it) }
            .getOrDefault(false)
    }

    private fun rewrite(reply: Any) {
        if (activeLogged.compareAndSet(false, true)) log("CustomCdn: response rewrite active (${reply.javaClass.name})")
        CustomCdnProcessor.rewriteResponse(reply, prefs, isCellular(), ::log)
    }

    private fun isEnabled(): Boolean = ModuleSettings.getActiveCdnHosts(prefs, isCellular()).isNotEmpty()

    private fun isCellular(): Boolean = env.hostContext?.let { NetworkTypeDetector.isCellular(it) } ?: false

    internal companion object {
        private const val CONTINUATION = "kotlin.coroutines.Continuation"

        /** `executePlayViewUnite` and `playViewUnite` both belong to the `playViewUnite` RPC. */
        fun rpcName(methodName: String): String =
            methodName.removePrefix("execute").replaceFirstChar(Char::lowercaseChar)
    }
}
