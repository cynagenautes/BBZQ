package io.github.bbzq.feats.symbol

import org.junit.Assert.*
import org.junit.Test

class PageClipboardSymbolsTest {
    class UpdateClipboard { class Request(val content: String?) }
    class Rpc {
        fun renamed(request: UpdateClipboard.Request, success: Any, failure: Any) {}
        fun unrelated(request: String, success: Any, failure: Any) {}
    }
    class CoroutineReader {
        fun invokeSuspend(result: Any): Any = result
        fun other(result: Any): Any = result
    }
    class WebDispatch {
        fun renamed(operation: String, arg: String, id: String, callback: Any): String = operation
    }

    @Test fun getAndSetClipboardDataTakeDifferentPaths() {
        assertTrue(PageClipboardMethodMatcher.isRead(PageClipboardReadKind.WEB_DISPATCH, listOf("getClipboardData")))
        assertFalse(PageClipboardMethodMatcher.isRead(PageClipboardReadKind.WEB_DISPATCH, listOf("setClipboardData")))
        assertFalse(PageClipboardMethodMatcher.isRead(PageClipboardReadKind.WEB_DISPATCH, listOf("unknown")))
    }

    @Test fun playerRpcPreservesWritesIncludingCopyingEmptyText() {
        assertTrue(PageClipboardMethodMatcher.isRead(PageClipboardReadKind.PLAYER_RPC, listOf(UpdateClipboard.Request(null))))
        assertFalse(PageClipboardMethodMatcher.isRead(PageClipboardReadKind.PLAYER_RPC, listOf(UpdateClipboard.Request("copied"))))
        assertFalse(PageClipboardMethodMatcher.isRead(PageClipboardReadKind.PLAYER_RPC, listOf(UpdateClipboard.Request(""))))
    }

    @Test fun validatesRpcRequestTypeAndCoroutineEntryInsteadOfMatchingEveryRead() {
        assertTrue(PageClipboardMethodMatcher.matches(Rpc::class.java.getDeclaredMethod("renamed", UpdateClipboard.Request::class.java, Any::class.java, Any::class.java), PageClipboardReadKind.PLAYER_RPC))
        assertFalse(PageClipboardMethodMatcher.matches(Rpc::class.java.getDeclaredMethod("unrelated", String::class.java, Any::class.java, Any::class.java), PageClipboardReadKind.PLAYER_RPC))
        assertTrue(PageClipboardMethodMatcher.matches(CoroutineReader::class.java.getDeclaredMethod("invokeSuspend", Any::class.java), PageClipboardReadKind.READ))
        assertFalse(PageClipboardMethodMatcher.matches(CoroutineReader::class.java.getDeclaredMethod("other", Any::class.java), PageClipboardReadKind.READ))
    }

    @Test fun cachedTargetsKeepTheirReadKindAndRejectWrongKind() {
        val method = WebDispatch::class.java.getDeclaredMethod("renamed", String::class.java, String::class.java, String::class.java, Any::class.java)
        val target = PageClipboardReadTarget(MethodDescriptor.of(method), PageClipboardReadKind.WEB_DISPATCH)
        val symbols = BiliHookSymbols(fingerprint = "9000200", hookPoints = emptyList(), scanErrors = emptyList(), pageClipboardReads = listOf(target))
        val restored = BiliHookSymbols.fromJson(symbols.toJson().toString())!!
        assertEquals(listOf(target), restored.pageClipboardReads)
        assertEquals(method, restored.pageClipboardReads.single().restore(javaClass.classLoader!!))
        assertNull(target.copy(kind = PageClipboardReadKind.PLAYER_RPC).restore(javaClass.classLoader!!))
    }
}
