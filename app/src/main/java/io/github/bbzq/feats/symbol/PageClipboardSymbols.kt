package io.github.bbzq.feats.symbol

import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Modifier

enum class PageClipboardReadKind { READ, WEB_DISPATCH, PLAYER_RPC }

data class PageClipboardReadTarget(val method: MethodDescriptor, val kind: PageClipboardReadKind) {
    fun toJson(): JSONObject = JSONObject().put("method", method.toJson()).put("kind", kind.name)

    fun restore(classLoader: ClassLoader): Method? {
        val owner = classLoader.loadClassOrNull(method.declaringClassName) ?: return null
        return method.restore(owner)?.takeIf { PageClipboardMethodMatcher.matches(it, kind) }
    }

    companion object {
        fun fromJson(obj: JSONObject): PageClipboardReadTarget = PageClipboardReadTarget(
            MethodDescriptor.fromJson(obj.getJSONObject("method")), PageClipboardReadKind.valueOf(obj.getString("kind")),
        )
    }
}

internal object PageClipboardMethodMatcher {
    fun matches(method: Method, kind: PageClipboardReadKind): Boolean {
        if (Modifier.isStatic(method.modifiers) || Modifier.isAbstract(method.modifiers)) return false
        return when (kind) {
            PageClipboardReadKind.READ ->
                (method.name == "run" && method.parameterCount == 0 && method.returnType == Void.TYPE) ||
                    (method.name == "invokeSuspend" && method.returnType == Any::class.java &&
                        method.parameterTypes.contentEquals(arrayOf(Any::class.java)))
            PageClipboardReadKind.WEB_DISPATCH -> method.returnType == String::class.java &&
                method.parameterCount == 4 && method.parameterTypes.take(3).all { it == String::class.java }
            PageClipboardReadKind.PLAYER_RPC -> method.returnType == Void.TYPE && method.parameterCount == 3 &&
                method.parameterTypes[0].name.endsWith("UpdateClipboard\$Request") &&
                runCatching { method.parameterTypes[0].getDeclaredMethod("getContent").returnType == String::class.java }.getOrDefault(false)
        }
    }

    fun isRead(kind: PageClipboardReadKind, args: List<Any?>): Boolean = when (kind) {
        PageClipboardReadKind.READ -> true
        PageClipboardReadKind.WEB_DISPATCH -> args.firstOrNull() == "getClipboardData"
        PageClipboardReadKind.PLAYER_RPC -> args.firstOrNull()?.let { request ->
            // Validated during restoration. A non-null content is the write branch and must stay usable.
            request.javaClass.getDeclaredMethod("getContent").invoke(request) == null
        } ?: true
    }
}
