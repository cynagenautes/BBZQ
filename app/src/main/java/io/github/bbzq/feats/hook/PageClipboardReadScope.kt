package io.github.bbzq.feats.hook

/** A script read boundary affects only synchronous work in that invocation, including coroutine resumes. */
internal object PageClipboardReadScope {
    private val depth = ThreadLocal<Int>()
    fun isBlocked(): Boolean = (depth.get() ?: 0) > 0

    fun <T> blocked(block: () -> T): T {
        val previous = depth.get()
        depth.set((previous ?: 0) + 1)
        try {
            return block()
        } finally {
            if (previous == null) depth.remove() else depth.set(previous)
        }
    }
}
