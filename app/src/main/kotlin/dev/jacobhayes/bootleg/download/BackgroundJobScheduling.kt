package dev.jacobhayes.bootleg.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.jacobhayes.bootleg.BootlegApp
import dev.jacobhayes.bootleg.storage.StoragePaths
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Schedules a download's user-initiated job while the app is in the background.
 *
 * Android schedules a user-initiated job only for an app that is visible or may start activities
 * from the background; a foreground service isn't enough. Root sends [ScheduleDownloadReceiver] a
 * broadcast that carries background-activity-start privileges (`am broadcast
 * --allow-background-activity-starts`), and the receiver schedules the very same job the Catalog
 * would while the broadcast is being delivered. Each attempt carries a random token, claimed
 * once, so only the broadcast this attempt asked for can answer it.
 */
class BackgroundJobScheduling(
    /** Asks root to send the broadcast; returns once it has been delivered. */
    private val sendBroadcast: suspend (fileName: String, token: String) -> Unit,
    private val random: SecureRandom = SecureRandom(),
) {
    private class Attempt(
        val token: String,
        /** The caller's own id for what it is scheduling, handed back to the receiver's callback. */
        val attemptId: String,
        val result: CompletableDeferred<Boolean> = CompletableDeferred(),
    )

    private val waiting = ConcurrentHashMap<String, Attempt>()

    /** True if the job was scheduled. */
    suspend fun schedule(
        fileName: String,
        attemptId: String,
    ): Boolean {
        val token = ByteArray(TOKEN_SIZE_BYTES).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val attempt = Attempt(token, attemptId)
        if (waiting.putIfAbsent(fileName, attempt) != null) return false
        try {
            runCatching { sendBroadcast(fileName, attempt.token) }
                .onFailure { Log.w(TAG, "background schedule broadcast failed: ${it.message}") }
            return withTimeoutOrNull(BROADCAST_WAIT_MS) { attempt.result.await() } ?: false
        } finally {
            waiting.remove(fileName, attempt)
        }
    }

    /**
     * Called by [ScheduleDownloadReceiver] inside the privileged broadcast; runs [schedule] with
     * that attempt's id only for the matching attempt, once.
     */
    fun onBroadcast(
        fileName: String,
        token: String,
        schedule: (attemptId: String) -> Boolean,
    ) {
        val attempt = waiting[fileName]
        if (attempt == null || attempt.token != token || !waiting.remove(fileName, attempt)) {
            Log.w(TAG, "unrequested schedule broadcast for $fileName")
            return
        }
        attempt.result.complete(runCatching { schedule(attempt.attemptId) }.getOrDefault(false))
    }

    companion object {
        private const val TAG = "BootlegDownloads"
        private const val TOKEN_SIZE_BYTES = 16

        /** The broadcast is delivered before `am broadcast` returns; this only bounds a lost one. */
        const val BROADCAST_WAIT_MS = 5_000L
        const val EXTRA_FILE_NAME = "file_name"
        const val EXTRA_TOKEN = "token"
        val TOKEN_REGEX = Regex("[0-9a-f]{${TOKEN_SIZE_BYTES * 2}}")

        /** Root's argv (no shell): a broadcast to [ScheduleDownloadReceiver] that may start activities from the background. */
        fun broadcastArgv(
            packageName: String,
            fileName: String,
            token: String,
        ): List<String> {
            // A name `am` could read as an option is refused, as is anything that isn't a store name.
            require(StoragePaths.isValidImageName(fileName) && !fileName.startsWith("-")) { "bad download name: $fileName" }
            require(TOKEN_REGEX.matches(token)) { "bad schedule token" }
            return listOf(
                "am",
                "broadcast",
                "--user",
                "0",
                "--allow-background-activity-starts",
                "-n",
                "$packageName/${ScheduleDownloadReceiver::class.java.name}",
                "--es",
                EXTRA_FILE_NAME,
                fileName,
                "--es",
                EXTRA_TOKEN,
                token,
            )
        }
    }
}

/**
 * Receives root's privileged broadcast (see [BackgroundJobScheduling]). Exported for root: the
 * DUMP permission keeps ordinary apps out (root, the adb shell and anything granted DUMP can send
 * it), and it schedules only the attempt the app is waiting on, so any other broadcast does
 * nothing.
 */
class ScheduleDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val fileName = intent.getStringExtra(BackgroundJobScheduling.EXTRA_FILE_NAME) ?: return
        val token = intent.getStringExtra(BackgroundJobScheduling.EXTRA_TOKEN) ?: return
        BootlegApp.from(context).downloads.onBackgroundScheduleBroadcast(fileName, token)
    }
}
