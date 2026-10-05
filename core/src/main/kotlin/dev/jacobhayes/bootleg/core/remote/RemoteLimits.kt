package dev.jacobhayes.bootleg.core.remote

import dev.jacobhayes.bootleg.core.hid.HidLimits

/** Hard limits for remote control (TigerStyle: every resource is bounded). Mirrored in remote/PROTOCOL.md. */
object RemoteLimits {
    const val PROTOCOL_VERSION = 1

    // Connections and identities.
    const val CONNECTION_COUNT_MAX = 8
    const val PENDING_CONNECTION_COUNT_MAX = 4

    /** Unknown tailnet nodes waiting for in-app approval; the oldest is evicted. */
    const val PENDING_APPROVAL_COUNT_MAX = 4
    const val APPROVAL_COUNT_MAX = 64
    const val APPROVAL_LIFETIME_MS = 180L * 24 * 60 * 60 * 1000
    const val SESSION_ID_LENGTH_MAX = 64
    const val IDENTITY_TEXT_LENGTH_MAX = 256
    const val IDENTITY_TAG_COUNT_MAX = 32

    /** Go's `connJson`, in UTF-8 bytes. */
    const val CONN_JSON_SIZE_BYTES_MAX = 16 * 1024

    // Hotspot pairing.
    const val PAIRING_CODE_DIGIT_COUNT = 6
    const val PAIRING_CODE_SPACE = 1_000_000
    const val PAIRING_CODE_LIFETIME_MS = 2L * 60 * 1000
    const val PAIRING_ATTEMPT_COUNT_MAX = 10
    const val PAIRING_ATTEMPT_WINDOW_MS = 60L * 60 * 1000
    const val PAIRING_LABEL_LENGTH_MAX = 64
    const val TOKEN_SIZE_BYTES = 32
    const val TOKEN_COUNT_MAX = 16
    const val TOKEN_LIFETIME_MS = 90L * 24 * 60 * 60 * 1000

    // Bridge queues (Kotlin side). Go bounds its own outbound queue to OUTBOUND_QUEUE_LENGTH_MAX.
    const val INBOUND_QUEUE_LENGTH_MAX = 64
    const val PRIORITY_QUEUE_LENGTH_MAX = 256
    const val OUTBOUND_QUEUE_LENGTH_MAX = 32

    // Requests.

    /** One JSON command (a WebSocket text message), in UTF-16 chars as seen by Kotlin; Go enforces the byte limit. */
    const val COMMAND_SIZE_CHARS_MAX = 64 * 1024
    const val REQUEST_ID_LENGTH_MAX = 64
    const val REQUEST_ID_CACHE_SIZE = 64

    // Lease.
    const val LEASE_EPOCH_MODULUS = 1 shl 16
    const val TAKEOVER_PROMPT_TIMEOUT_MS = 30_000L
    const val IDLE_KEY_RELEASE_MS = 30_000L
    const val IDLE_LEASE_RELEASE_MS = 10L * 60 * 1000

    // Release barrier: retries double from the initial delay until the window ends, then `release_pending`.
    const val RELEASE_RETRY_INITIAL_MS = 1_000L
    const val RELEASE_RETRY_WINDOW_MS = 60_000L

    /** How often the actor polls the HID session while the barrier is up (input is refused meanwhile). */
    const val RELEASE_BARRIER_POLL_MS = 10L

    // Pushes and input.

    /** At most 10 pushes per second per kind (state, lease) per session. */
    const val PUSH_INTERVAL_MS_MIN = 100L
    const val HID_FRAME_RATE_PER_SECOND_MAX = 200

    // Paste.
    const val PASTE_PLAN_COUNT_MAX = 4
    const val PASTE_PLAN_LIFETIME_MS = 2L * 60 * 1000
    const val PASTE_SIZE_BYTES_MAX = HidLimits.PASTE_SIZE_BYTES_MAX
    const val PASTE_DURATION_MS_MAX = 5L * 60 * 1000
    const val PASTE_REPORT_INTERVAL_MS = 8L
    const val PASTE_UNSUPPORTED_REPORTED_MAX = 64

    // Boot keys.
    const val BOOT_KEY_DURATION_MS_DEFAULT = 20_000L
    const val BOOT_KEY_MASH_INTERVAL_MS_MIN = 100L
    const val BOOT_KEY_HOLD_RESEND_INTERVAL_MS = 200L

    /** Reports a paste/boot-key job may emit in one actor step, so a late tick can't flood the HID queue. */
    const val JOB_REPORTS_PER_STEP_MAX = 32

    // Device (gadget) operations.
    const val DEVICE_OP_TIMEOUT_MS = 10_000L

    /** `tether.set`: a first use or a mode change sets the prefix up (a gadget change with a USB mode round trip) before Android's start. */
    const val TETHER_OP_TIMEOUT_MS = 75_000L

    const val IMAGE_COUNT_MAX = 512

    // Catalog downloads.

    /** Catalog entries the controller keeps (the scrapers cap themselves well below this). */
    const val CATALOG_ENTRY_COUNT_MAX = 2048

    /** `catalog.list` reply: entries and JSON size, well under one 64 KiB WebSocket message. */
    const val CATALOG_REPLY_ENTRY_COUNT_MAX = 256
    const val CATALOG_REPLY_SIZE_BYTES_MAX = 48 * 1024

    /** A remote `catalog.list` lets the app refresh a stale catalog at most this often (for all sessions together). */
    const val CATALOG_REFRESH_INTERVAL_MS_MIN = 60_000L

    /** Downloads queued or running (from any source) when the remote may start another. */
    const val DOWNLOAD_ACTIVE_COUNT_MAX = 4

    /** Download records in a `downloads` push (the app's store holds at most this many). */
    const val DOWNLOAD_COUNT_MAX = 64

    /** A `downloads` push's JSON, well under one 64 KiB WebSocket message; active downloads go first. */
    const val DOWNLOADS_PUSH_SIZE_BYTES_MAX = 48 * 1024

    /** At most 2 `downloads` pushes per second per session (progress is coalesced). */
    const val DOWNLOAD_PUSH_INTERVAL_MS_MIN = 500L
    const val DOWNLOAD_OP_TIMEOUT_MS = 10_000L

    /** Catalog and download display strings (names, versions, errors), in chars. */
    const val CATALOG_TEXT_LENGTH_MAX = 200

    /** The actor wakes at least this often so lazy expirations (plans, approvals) are processed. */
    const val TICK_INTERVAL_MS_MAX = 1_000L
}
