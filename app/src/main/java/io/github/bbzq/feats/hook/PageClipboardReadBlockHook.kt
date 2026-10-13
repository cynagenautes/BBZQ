package io.github.bbzq.feats.hook

import android.content.ClipData
import android.content.ClipboardManager
import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.hookBefore
import io.github.bbzq.feats.symbol.PageClipboardMethodMatcher
import io.github.libxposed.api.XposedInterface

class PageClipboardReadBlockHook(env: RoamingEnv) : BaseRoamingHook(env) {
    private var readApiInstalled = false
    private var hasClipInstalled = false
    private val installedTargets = mutableSetOf<String>()

    override fun startHook() {
        if (isInstalled) return
        val targets = env.symbols?.pageClipboardReads.orEmpty()
        if (!readApiInstalled) readApiInstalled = env.hookBefore(
            ClipboardManager::class.java.getDeclaredMethod("getPrimaryClip"),
        ) { param ->
            if (PageClipboardReadScope.isBlocked()) {
                // Do not read or change the real clipboard. Empty text keeps host reply/coroutine paths complete.
                param.result = ClipData.newPlainText("BBZQ", "")
            }
        }
        if (!hasClipInstalled) hasClipInstalled = env.hookBefore(
            ClipboardManager::class.java.getDeclaredMethod("hasPrimaryClip"),
        ) { param ->
            if (PageClipboardReadScope.isBlocked()) param.result = true // The shadow clip contains one empty item.
        }
        if (!readApiInstalled || !hasClipInstalled) return
        for (target in targets) {
            val method = target.restore(classLoader) ?: continue
            val id = method.toGenericString()
            if (id in installedTargets) continue
            runCatching {
                xposed.hook(method).setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept { chain ->
                    if (ModuleSettings.isBlockPageClipboardReadEnabled(prefs) &&
                        PageClipboardMethodMatcher.isRead(target.kind, chain.getArgs().toList())) {
                        PageClipboardReadScope.blocked { chain.proceed() }
                    } else {
                        chain.proceed()
                    }
                }
                installedTargets += id
            }.onFailure { log("Page clipboard boundary registration failed: $id", it) }
        }
        // An incomplete/empty scan can be retried after symbols are refreshed.
        isInstalled = targets.size == 5 && installedTargets.size == targets.size
        log("startHook: PageClipboardReadBlock, targets=${installedTargets.size}/5")
    }
}
