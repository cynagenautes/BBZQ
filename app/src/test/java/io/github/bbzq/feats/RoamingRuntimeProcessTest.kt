package io.github.bbzq.feats

import org.junit.Assert.*
import org.junit.Test

class RoamingRuntimeProcessTest {
    @Test fun privacyHooksReachHostChildProcessesWithoutScanningAllFeatures() {
        for (pkg in listOf("tv.danmaku.bili", "com.bilibili.app.blue")) {
            for (suffix in listOf(":game", ":pushservice", ":other")) {
                assertTrue(RoamingRuntime.isProcessSupported(pkg, pkg + suffix))
                assertFalse(RoamingRuntime.isSymbolResolverProcess(pkg, pkg + suffix))
            }
            for (suffix in listOf("", ":web", ":download")) {
                assertTrue(RoamingRuntime.isProcessSupported(pkg, pkg + suffix))
                assertTrue(RoamingRuntime.isSymbolResolverProcess(pkg, pkg + suffix))
            }
        }
    }

    @Test fun unrelatedProcessesDoNotGetTheNewPrivacyScope() {
        assertFalse(RoamingRuntime.isProcessSupported("tv.danmaku.bili", "different:game"))
        assertFalse(RoamingRuntime.isProcessSupported("org.readera", "org.readera:game"))
    }
}
