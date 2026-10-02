package dev.jacobhayes.bootleg.remotespike

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView

/** Starts the spike service and shows the node status JSON (including any login URL). */
class SpikeActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusView: TextView

    private val refresh =
        object : Runnable {
            override fun run() {
                statusView.text = SpikeService.lastStatusJson
                handler.postDelayed(this, REFRESH_INTERVAL_MS)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (stopIfRequested(intent)) return
        statusView = TextView(this).apply { setPadding(PADDING_PX, PADDING_PX, PADDING_PX, PADDING_PX) }
        setContentView(statusView)
        val missingPermissions =
            arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK, Manifest.permission.POST_NOTIFICATIONS)
                .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missingPermissions.isNotEmpty()) requestPermissions(missingPermissions.toTypedArray(), PERMISSION_REQUEST_CODE)
        startForegroundService(
            Intent(this, SpikeService::class.java)
                .putExtra(SpikeService.EXTRA_VERBOSE, intent.getBooleanExtra(SpikeService.EXTRA_VERBOSE, false)),
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        stopIfRequested(intent)
    }

    // `am start ... --ez stop true`: adb can't stop a service in a backgrounded app directly.
    private fun stopIfRequested(intent: Intent): Boolean {
        if (!intent.getBooleanExtra(SpikeService.EXTRA_STOP, false)) return false
        stopService(Intent(this, SpikeService::class.java))
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private companion object {
        const val REFRESH_INTERVAL_MS = 1_000L
        const val PADDING_PX = 48
        const val PERMISSION_REQUEST_CODE = 1
    }
}
