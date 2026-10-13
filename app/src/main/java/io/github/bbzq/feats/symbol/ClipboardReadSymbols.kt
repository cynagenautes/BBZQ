package io.github.bbzq.feats.symbol

import android.content.ClipboardManager
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

data class ClipboardReadSymbols(
    val readMethod: MethodDescriptor,
    val evidence: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("readMethod", readMethod.toJson())
        .put("evidence", evidence)

    fun restore(classLoader: ClassLoader): Method? {
        val owner = classLoader.loadClassOrNull(readMethod.declaringClassName) ?: return null
        val method = readMethod.restore(owner) ?: return null
        // Recheck uniqueness when restoring a cache; never hook every method with a similar shape.
        return method.takeIf { ClipboardReadMethodMatcher.findUnique(owner) == it }
    }

    companion object {
        fun fromJson(obj: JSONObject): ClipboardReadSymbols = ClipboardReadSymbols(
            readMethod = MethodDescriptor.fromJson(obj.getJSONObject("readMethod")),
            evidence = obj.optString("evidence", "-"),
        )
    }
}
