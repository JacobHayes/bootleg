package dev.jacobhayes.bootleg.core.hid

/**
 * Smooths a status for display. A value ranked at least as high as the shown one shows at once; a
 * lower one shows only after the values stayed below the shown one for [holdMs], so the brief drops
 * of a host enumerating, resetting and handing off from firmware to the OS don't flicker. A value
 * [rank] maps to null (a deliberate change, not a flicker) shows at once. Display only: nothing that
 * acts on the link may read the held value.
 */
class DropHold<T : Any>(
    private val holdMs: Long,
    private val rank: (T) -> Int?,
) {
    private var shown: T? = null
    private var latest: T? = null
    private var dropSinceMs: Long? = null
    private var lastNowMs = Long.MIN_VALUE

    init {
        require(holdMs > 0) { "holdMs must be positive: $holdMs" }
    }

    /** When the pending drop shows if nothing else arrives first, or null with none pending. */
    val dropShowsAtMs: Long? get() = dropSinceMs?.plus(holdMs)

    /** Records [value], seen at [nowMs], and returns what to show. */
    fun observe(
        value: T,
        nowMs: Long,
    ): T {
        latest = value
        return shownAt(nowMs)
    }

    /** What to show at [nowMs] given the last observed value. */
    fun shownAt(nowMs: Long): T {
        val value = checkNotNull(latest) { "nothing observed yet" }
        check(nowMs >= lastNowMs) { "clock went backwards: $nowMs < $lastNowMs" }
        lastNowMs = nowMs
        val current = shown
        val valueRank = rank(value)
        val dropping = current != null && valueRank != null && rank(current).let { it != null && valueRank < it }
        if (!dropping) {
            shown = value
            dropSinceMs = null
            return value
        }
        val sinceMs = dropSinceMs ?: nowMs.also { dropSinceMs = it }
        if (nowMs - sinceMs >= holdMs) {
            shown = value
            dropSinceMs = null
        }
        return checkNotNull(shown)
    }
}
