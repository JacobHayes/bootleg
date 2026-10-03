package dev.jacobhayes.bootleg.service

import android.content.Context
import android.widget.Toast
import dev.jacobhayes.bootleg.R
import dev.jacobhayes.bootleg.core.gadget.ApplyResult
import dev.jacobhayes.bootleg.core.gadget.DefaultGadgetController

/** What the user is told after "Eject all" from the notification or the Quick Settings tile. */
sealed interface EjectAllFeedback {
    /** The host still holds a drive (macOS keeps prevent-removal set until it ejects the disk itself). */
    data object HostUsing : EjectAllFeedback

    data class Failed(
        val reason: String,
    ) : EjectAllFeedback

    companion object {
        /** Null when everything was ejected: the notification and tile update by themselves. */
        fun of(result: ApplyResult): EjectAllFeedback? =
            when (result) {
                is ApplyResult.Applied -> null
                is ApplyResult.NeedsAttention ->
                    if (result.reason.startsWith(DefaultGadgetController.EJECT_PREVENTED_REASON)) HostUsing else Failed(result.reason)
                is ApplyResult.Failed -> Failed(result.reason)
                is ApplyResult.Partial -> Failed(result.slotFailures.values.joinToString())
            }
    }
}

/**
 * Ejects every loaded drive and, since neither shortcut has a screen to show the outcome on, toasts
 * when the host refused or the eject failed. Without it a refused eject looked like a dead button.
 */
suspend fun ejectAllWithFeedback(
    context: Context,
    backend: BootlegBackendImpl,
) {
    val message =
        when (val feedback = EjectAllFeedback.of(backend.ejectAll())) {
            null -> return
            EjectAllFeedback.HostUsing -> context.getString(R.string.eject_all_host_using)
            is EjectAllFeedback.Failed -> context.getString(R.string.eject_all_failed, feedback.reason)
        }
    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
}
