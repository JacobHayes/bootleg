package dev.jacobhayes.bootleg

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jacobhayes.bootleg.ui.BootlegRoot
import dev.jacobhayes.bootleg.ui.Tab
import dev.jacobhayes.bootleg.ui.theme.BootlegTheme

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
        const val EXTRA_TAB = "tab"
        val TAB_DOWNLOADS = Tab.DOWNLOADS.name
        private const val STATE_TAB = "tab"
    }
}
