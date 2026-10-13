package io.github.bbzq.feats.hook

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PageClipboardReadScopeTest {
    @Test fun nativeClipboardOperationsOutsideThePageCallRemainUnblocked() {
        assertFalse(PageClipboardReadScope.isBlocked())
        assertEquals("reply", PageClipboardReadScope.blocked {
            assertTrue(PageClipboardReadScope.isBlocked())
            "reply"
        })
        assertFalse(PageClipboardReadScope.isBlocked())
    }

    @Test fun exceptionsRestoreBothNestedAndOuterScopes() {
        runCatching {
            PageClipboardReadScope.blocked {
                runCatching { PageClipboardReadScope.blocked<Unit> { error("nested") } }
                assertTrue(PageClipboardReadScope.isBlocked())
                error("outer")
            }
        }
        assertFalse(PageClipboardReadScope.isBlocked())
    }

    @Test fun concurrentNativePasteDoesNotInheritThePageReadRestriction() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            PageClipboardReadScope.blocked {
                assertFalse(executor.submit<Boolean> { PageClipboardReadScope.isBlocked() }.get(2, TimeUnit.SECONDS))
            }
            assertFalse(executor.submit<Boolean> { PageClipboardReadScope.isBlocked() }.get(2, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
    }
}
