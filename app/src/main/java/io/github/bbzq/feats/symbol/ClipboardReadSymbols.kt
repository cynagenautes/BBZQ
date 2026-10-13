package io.github.bbzq.feats.symbol

import android.content.ClipboardManager
import android.content.Context
import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/** Shape of ClipboardChecker's read helper; deliberately independent of its obfuscated name. */
internal object ClipboardReadMethodMatcher {
    fun matches(method: Method): Boolean =
        Modifier.isStatic(method.modifiers) &&
            method.returnType == String::class.java &&
            method.parameterTypes.contentEquals(arrayOf(ClipboardManager::class.java))

    fun findUnique(type: Class<*>): Method? = type.declaredMethods.filter(::matches).singleOrNull()
}

internal object ClipboardListenerMatcher {
    fun callback(type: Class<*>): Method? {
        if (!ClipboardManager.OnPrimaryClipChangedListener::class.java.isAssignableFrom(type)) return null
        if (type.declaredFields.none { it.type == ClipboardManager::class.java } ||
            type.declaredFields.none { it.type == Context::class.java }) return null
        return type.declaredMethods.singleOrNull {
            it.name == "onPrimaryClipChanged" && it.parameterCount == 0 &&
                it.returnType == Void.TYPE && !Modifier.isStatic(it.modifiers) && !Modifier.isAbstract(it.modifiers)
        }
    }
}

data class ClipboardReadSymbols(
    val readMethod: MethodDescriptor,
    val evidence: String,
    val listenerCallback: MethodDescriptor? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("readMethod", readMethod.toJson())
        .put("evidence", evidence)
        .putOpt("listenerCallback", listenerCallback?.toJson())

    fun restore(classLoader: ClassLoader): Method? {
        val owner = classLoader.loadClassOrNull(readMethod.declaringClassName) ?: return null
        val method = readMethod.restore(owner) ?: return null
        // Recheck uniqueness when restoring a cache; never hook every method with a similar shape.
        return method.takeIf { ClipboardReadMethodMatcher.findUnique(owner) == it }
    }

    fun restoreListener(classLoader: ClassLoader): Method? {
        val descriptor = listenerCallback ?: return null
        val owner = classLoader.loadClassOrNull(descriptor.declaringClassName) ?: return null
        return descriptor.restore(owner)?.takeIf { ClipboardListenerMatcher.callback(owner) == it }
    }

    companion object {
        fun fromJson(obj: JSONObject): ClipboardReadSymbols = ClipboardReadSymbols(
            readMethod = MethodDescriptor.fromJson(obj.getJSONObject("readMethod")),
            evidence = obj.optString("evidence", "-"),
            listenerCallback = obj.optJSONObject("listenerCallback")?.let(MethodDescriptor::fromJson),
        )
    }
}
