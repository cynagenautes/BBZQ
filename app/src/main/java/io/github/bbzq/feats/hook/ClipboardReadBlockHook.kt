package io.github.bbzq.feats.hook

import android.content.ClipboardManager
import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.hookBefore

class ClipboardReadBlockHook(env: RoamingEnv) : BaseRoamingHook(env) {
    private var readInstalled = false
    private var callbackInstalled = false
    private var registrationInstalled = false

    override fun startHook() {
        if (isInstalled) return
        val symbols = env.symbols?.clipboardRead ?: run {
            log("startHook: ClipboardReadBlock skipped because symbols are unavailable")
            return
        }
        val readMethod = symbols.restore(classLoader) ?: run {
            log("startHook: ClipboardReadBlock skipped because the read helper could not be restored")
            return
        }
        if (!readInstalled) readInstalled = env.hookBefore(readMethod) { param ->
            if (enabled()) {
                // The host handles empty content by cancelling recognition and resetting its checking state.
                // Keep that completion path; skipping the entire check would leave its observers waiting.
                param.result = ""
            }
        }
        val callback = symbols.restoreListener(classLoader)
        if (callback != null) {
            if (!callbackInstalled) callbackInstalled = env.hookBefore(callback) { param ->
                // Also stop listeners registered before the setting changed or before this hook installed.
                if (enabled()) param.result = null
            }
            if (!registrationInstalled) {
                val addListener = ClipboardManager::class.java.getDeclaredMethod(
                    "addPrimaryClipChangedListener", ClipboardManager.OnPrimaryClipChangedListener::class.java,
                )
                registrationInstalled = env.hookBefore(addListener) { param ->
                    if (enabled() && callback.declaringClass.isInstance(param.args.firstOrNull())) {
                        param.result = null
                    }
                }
            }
        }
        isInstalled = readInstalled && callbackInstalled && registrationInstalled
        log("startHook: ClipboardReadBlock, read=$readInstalled, callback=$callbackInstalled, registration=$registrationInstalled, target=${readMethod.declaringClass.name}.${readMethod.name}")
    }

    private fun enabled(): Boolean = ModuleSettings.isBlockAutomaticClipboardReadEnabled(prefs)
}
