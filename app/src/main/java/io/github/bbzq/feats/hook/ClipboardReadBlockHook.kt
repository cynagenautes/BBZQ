package io.github.bbzq.feats.hook

import io.github.bbzq.ModuleSettings
import io.github.bbzq.feats.BaseRoamingHook
import io.github.bbzq.feats.RoamingEnv
import io.github.bbzq.feats.hookBefore

class ClipboardReadBlockHook(env: RoamingEnv) : BaseRoamingHook(env) {
    override fun startHook() {
        if (isInstalled || env.processName != env.packageName) return
        val symbols = env.symbols?.clipboardRead ?: run {
            log("startHook: ClipboardReadBlock skipped because symbols are unavailable")
            return
        }
        val readMethod = symbols.restore(classLoader) ?: run {
            log("startHook: ClipboardReadBlock skipped because the read helper could not be restored")
            return
        }
        isInstalled = env.hookBefore(readMethod) { param ->
            if (ModuleSettings.isBlockAutomaticClipboardReadEnabled(prefs)) {
                // The host handles empty content by cancelling recognition and resetting its checking state.
                // Keep that completion path; skipping the entire check would leave its observers waiting.
                param.result = ""
            }
        }
        log("startHook: ClipboardReadBlock, installed=$isInstalled, target=${readMethod.declaringClass.name}.${readMethod.name}")
    }
}
