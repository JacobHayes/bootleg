package dev.jacobhayes.bootleg.ui.remote

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.jacobhayes.bootleg.remote.RemoteManager
import kotlinx.coroutines.delay

/** [RemoteScreen] wired to the [RemoteManager], with the runtime permission prompts. */
@Composable
fun RemoteRoute(remote: RemoteManager) {
    val state by remote.state.collectAsState()
    val context = LocalContext.current
    var explainLocalNetwork by remember { mutableStateOf(false) }
    var nowElapsedMs by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    LifecycleResumeEffect(Unit) {
        remote.refreshPermissions()
        onPauseOrDispose {}
    }
    if (state.status.pairing.code != null) {
        LaunchedEffect(state.status.pairing.code) {
            while (true) {
                nowElapsedMs = SystemClock.elapsedRealtime()
                delay(COUNTDOWN_TICK_MS)
            }
        }
    }

    // Whatever the answer, Remote still works; without the permission traffic goes through relays.
    val localNetwork =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            remote.refreshPermissions()
            if (!remote.isEnabled) remote.setEnabled(true)
        }
    val nearbyWifi =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) remote.startHotspot()
        }

    RemoteScreen(
        state = state,
        actions = remote,
        nowElapsedMs = nowElapsedMs,
        onToggle = { enabled ->
            if (enabled && !state.localNetworkGranted) explainLocalNetwork = true else remote.setEnabled(enabled)
        },
        onStartHotspot = {
            if (context.checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED) {
                remote.startHotspot()
            } else {
                nearbyWifi.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        },
        onGrantLocalNetwork = { localNetwork.launch(RemoteManager.PERMISSION_ACCESS_LOCAL_NETWORK) },
        onOpenUrl = { url ->
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: ActivityNotFoundException) {
                // No browser: nothing to open it with.
            }
        },
    )

    if (explainLocalNetwork) {
        AlertDialog(
            onDismissRequest = { explainLocalNetwork = false },
            title = { Text("Allow local network access") },
            text = {
                Text(
                    "Bootleg runs its own Tailscale node. Android asks before an app talks to devices on your Wi-Fi; " +
                        "with access, a computer in the same place connects directly (fast). Without it, " +
                        "connections go through Tailscale's relays and offline mode can't serve your laptop.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    explainLocalNetwork = false
                    localNetwork.launch(RemoteManager.PERMISSION_ACCESS_LOCAL_NETWORK)
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = {
                    explainLocalNetwork = false
                    remote.setEnabled(true)
                }) { Text("Use relays only") }
            },
        )
    }
}

private const val COUNTDOWN_TICK_MS = 1_000L
