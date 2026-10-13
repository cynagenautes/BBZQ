package io.github.bbzq.feats.symbol

import android.content.ClipboardManager
import org.junit.Assert.*
import org.junit.Test

class ClipboardReadSymbolsTest {
    class RenamedChecker {
        companion object {
            @JvmStatic fun renamedRead(manager: ClipboardManager?): String = "original"
            @JvmStatic fun unrelated(text: String): String = text
        }
        fun instanceRead(manager: ClipboardManager?): String = "instance"
    }

    class AmbiguousChecker {
        companion object {
            @JvmStatic fun first(manager: ClipboardManager?): String = "first"
            @JvmStatic fun second(manager: ClipboardManager?): String = "second"
        }
    }

    class WrongShape {
        companion object {
            @JvmStatic fun wrongReturn(manager: ClipboardManager?): Boolean = true
            @JvmStatic fun extraParameter(manager: ClipboardManager?, value: Int): String = value.toString()
        }
        fun instanceRead(manager: ClipboardManager?): String = "instance"
    }

    @Test fun identifiesRenamedHelperWithoutMatchingInstanceOrUnrelatedMethods() {
        val method = ClipboardReadMethodMatcher.findUnique(RenamedChecker::class.java)
        assertEquals("renamedRead", method?.name)
    }

    @Test fun rejectsAmbiguousAndWrongSignatures() {
        assertNull(ClipboardReadMethodMatcher.findUnique(AmbiguousChecker::class.java))
        assertNull(ClipboardReadMethodMatcher.findUnique(WrongShape::class.java))
    }

    @Test fun cacheRoundTripRestoresMethodAndInvalidatesOldSchemasAndRules() {
        val method = ClipboardReadMethodMatcher.findUnique(RenamedChecker::class.java)!!
        val symbol = ClipboardReadSymbols(MethodDescriptor.of(method), "fixture")
        val cached = BiliHookSymbols(fingerprint = "host-9000200", hookPoints = emptyList(), scanErrors = emptyList(), clipboardRead = symbol)
        val restored = BiliHookSymbols.fromJson(cached.toJson().toString())!!
        assertEquals(symbol, restored.clipboardRead)
        assertEquals(method, restored.clipboardRead?.restore(javaClass.classLoader!!))
        assertTrue(restored.isUsableWith("host-9000200"))
        assertFalse(restored.isUsableWith("different-host"))
        assertFalse(restored.copy(cacheSchemaVersion = BiliHookSymbols.CACHE_SCHEMA_VERSION - 1).isUsableWith("host-9000200"))
        assertFalse(restored.copy(dexKitRuleVersion = DexKitRuleVersions.CURRENT - 1).isUsableWith("host-9000200"))
    }

    @Test fun rejectsCachedMethodWithWrongOrAmbiguousShape() {
        val instance = WrongShape::class.java.getDeclaredMethod("instanceRead", ClipboardManager::class.java)
        val ambiguous = AmbiguousChecker::class.java.getDeclaredMethod("first", ClipboardManager::class.java)
        assertNull(ClipboardReadSymbols(MethodDescriptor.of(instance), "fixture").restore(javaClass.classLoader!!))
        assertNull(ClipboardReadSymbols(MethodDescriptor.of(ambiguous), "fixture").restore(javaClass.classLoader!!))
    }
}
