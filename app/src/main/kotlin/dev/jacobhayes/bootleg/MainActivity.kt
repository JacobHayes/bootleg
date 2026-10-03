package dev.jacobhayes.bootleg

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dev.jacobhayes.bootleg.service.BootlegService
import dev.jacobhayes.bootleg.ui.BootlegRoot
import dev.jacobhayes.bootleg.ui.Tab
import dev.jacobhayes.bootleg.ui.theme.BootlegTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var selectedTab by mutableStateOf(Tab.DRIVES)

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = application as BootlegApp
        selectedTab =
            savedInstanceState?.getString(STATE_TAB)?.let { name -> Tab.entries.find { it.name == name } } ?: tabFrom(intent) ?: Tab.DRIVES
        requestNotificationsOnce(app.prefs)
        setContent {
            BootlegTheme {
                BootlegRoot(app, selectedTab, onSelectTab = { selectedTab = it })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Nothing else reads the root side's journal when the app opens (the service may not be
        // running), so the Drives draft would start empty and an Apply would drop the live exports.
        val backend = (application as BootlegApp).systemBackend
        lifecycleScope.launch {
            try {
                backend.refreshGadget()
                // After a process restart (update, kill) the kernel still exports; bring back the
                // notification and the host-eject watch.
                if (backend.hasExports) BootlegService.start(this@MainActivity)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "reading the gadget state failed", e)
            }
        }
        // Likewise the image list: without it Library and the slot picker are empty after a restart,
        // and files added meanwhile (a USB file copy, another app) show up on return.
        lifecycleScope.launch {
            try {
                backend.refreshImages()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "listing images failed", e)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        tabFrom(intent)?.let { selectedTab = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TAB, selectedTab.name)
    }

    private fun requestNotificationsOnce(prefs: Prefs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || prefs.notificationPermissionAsked) return
        prefs.notificationPermissionAsked = true
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun tabFrom(intent: Intent?): Tab? = intent?.getStringExtra(EXTRA_TAB)?.let { name -> Tab.entries.find { it.name == name } }

    companion object {
        private const val TAG = "MainActivity"
        const val EXTRA_TAB = "tab"
        val TAB_DOWNLOADS = Tab.DOWNLOADS.name
        private const val STATE_TAB = "tab"
    }
}
