package dev.jacobhayes.bootleg.tile

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.jacobhayes.bootleg.BootlegApp
import dev.jacobhayes.bootleg.MainActivity
import dev.jacobhayes.bootleg.R
import dev.jacobhayes.bootleg.core.gadget.GadgetState
import dev.jacobhayes.bootleg.service.ejectAllWithFeedback
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Quick Settings tile: active while media is exported. Tap ejects everything, or opens the app when idle. */
class BootlegTileService : TileService() {
    private val app by lazy { BootlegApp.from(this) }
    private var listening: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        listening =
            app.appScope.launch {
                runCatching { app.systemBackend.refreshGadget() }
                combine(app.systemBackend.gadgetState, app.systemBackend.usbTethering) { state, tethering -> state to tethering }
                    .collect { (state, tethering) -> render(state, tethering == true) }
            }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        if (isActive(app.systemBackend.gadgetState.value)) {
            app.appScope.launch { ejectAllWithFeedback(app, app.systemBackend) }
            return
        }
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render(
        state: GadgetState?,
        tethering: Boolean,
    ) {
        val tile = qsTile ?: return
        val loadedCount = state?.luns?.values?.count { it.file.isNotEmpty() } ?: 0
        tile.state = if (loadedCount > 0) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        val drives =
            if (loadedCount > 0) {
                resources.getQuantityString(R.plurals.notification_exported, loadedCount, loadedCount)
            } else {
                getString(R.string.tile_subtitle_inactive)
            }
        tile.subtitle = if (tethering) getString(R.string.tile_subtitle_tethering, drives) else drives
        tile.updateTile()
    }

    private fun isActive(state: GadgetState?) = state?.luns?.values?.any { it.file.isNotEmpty() } == true
}
