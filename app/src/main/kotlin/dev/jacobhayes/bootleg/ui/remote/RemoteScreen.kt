package dev.jacobhayes.bootleg.ui.remote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material.icons.outlined.WifiTethering
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.jacobhayes.bootleg.core.remote.ApprovalRecord
import dev.jacobhayes.bootleg.core.remote.AuthStatus
import dev.jacobhayes.bootleg.core.remote.LeaseHolder
import dev.jacobhayes.bootleg.core.remote.PendingApproval
import dev.jacobhayes.bootleg.core.remote.Principal
import dev.jacobhayes.bootleg.core.remote.SessionStatus
import dev.jacobhayes.bootleg.core.remote.TokenRecord
import dev.jacobhayes.bootleg.remote.HotspotInfo
import dev.jacobhayes.bootleg.remote.HotspotState
import dev.jacobhayes.bootleg.remote.RemoteActions
import dev.jacobhayes.bootleg.remote.RemoteUiState
import dev.jacobhayes.bootleg.ui.common.NoticeCard
import dev.jacobhayes.bootleg.ui.common.Tone
import java.text.DateFormat
import java.util.Date

/**
 * Remote control: the tailnet node, devices waiting for approval, approved devices, live sessions
 * and the offline hotspot. Stateless: [RemoteRoute] supplies the state, permission prompts and the
 * clock ([nowElapsedMs], for the pairing code countdown).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteScreen(
    state: RemoteUiState,
    actions: RemoteActions,
    nowElapsedMs: Long,
    onToggle: (Boolean) -> Unit,
    onStartHotspot: () -> Unit,
    onGrantLocalNetwork: () -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Remote") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { EnableCard(state, onToggle) }
            state.error?.let { error ->
                item { NoticeCard(Icons.Outlined.ErrorOutline, "Remote problem", error, Tone.ERROR) }
            }
            if (state.enabled && !state.localNetworkGranted) {
                item {
                    NoticeCard(
                        Icons.Outlined.Lock,
                        "Local network access is off",
                        "Computers on your Wi-Fi then reach the phone through Tailscale's relays, which is slower. " +
                            "Offline mode needs it too.",
                        Tone.WARNING,
                    ) { TextButton(onClick = onGrantLocalNetwork) { Text("Allow") } }
                }
            }
            if (!state.enabled && !state.running) return@LazyColumn
            item { TailnetCard(state, onOpenUrl) }
            if (state.status.pending.isNotEmpty()) {
                item { SectionTitle("Waiting for approval") }
                items(state.status.pending, key = { "pending:" + it.nodeId }) { pending ->
                    PendingCard(pending, actions)
                }
            }
            item { ControlCard(state) }
            if (state.status.sessions.isNotEmpty()) {
                item { SectionTitle("Connected") }
                items(state.status.sessions, key = { "session:" + it.sessionId }) { SessionRow(it) }
            }
            item { SectionTitle("Approved devices") }
            if (state.status.approvals.isEmpty()) {
                item { Muted("None yet. Devices on your tailnet show up above when they connect, and you approve them once.") }
            }
            items(state.status.approvals, key = { "approval:" + it.nodeId }) { ApprovalRow(it, actions) }
            item { SectionTitle("Offline mode") }
            item { HotspotCard(state, actions, nowElapsedMs, onStartHotspot) }
            if (state.status.tokens.isNotEmpty()) {
                item { SectionTitle("Paired over the hotspot") }
                items(state.status.tokens, key = { "token:" + it.sha256 }) { TokenRow(it, actions) }
            }
        }
    }
}

@Composable
private fun EnableCard(
    state: RemoteUiState,
    onToggle: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.clickable { onToggle(!state.enabled) }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.SettingsRemote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text("Remote control", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Control drives, keyboard and mouse from a browser or the bootleg CLI on your tailnet. " +
                        "Each computer needs your approval here once.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = state.enabled, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun TailnetCard(
    state: RemoteUiState,
    onOpenUrl: (String) -> Unit,
) {
    val tailnet = state.tailnet
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tailscale", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StatusChip(tailnetLabel(state))
            }
            when {
                tailnet.isRunning -> {
                    Text(
                        tailnet.fqdn.ifEmpty { state.hostname },
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace,
                    )
                    if (tailnet.tailscaleIPs.isNotEmpty()) {
                        Muted(tailnet.tailscaleIPs.joinToString("  "))
                    }
                    Muted("Open http://${state.hostname}/ in a browser, or run: bootleg --host ${state.hostname} status")
                }
                tailnet.needsLogin && tailnet.loginURL.isNotEmpty() -> {
                    Muted("This phone isn't on your tailnet yet. Sign in once; the node is remembered.")
                    Button(onClick = { onOpenUrl(tailnet.loginURL) }) { Text("Sign in to Tailscale") }
                    Muted("Development: an auth key in ${state.authKeyPath} registers the node instead (deleted once it's used).")
                }
                state.running -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Muted("Starting the node as ${state.hostname}…")
                    }
                }
                else -> Muted("Starting…")
            }
            if (tailnet.error.isNotEmpty()) {
                Text(
                    tailnet.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            tailnet.health.take(HEALTH_LINES_MAX).forEach { Muted("⚠ $it") }
        }
    }
}

private fun tailnetLabel(state: RemoteUiState): String =
    when {
        !state.running -> "Off"
        state.tailnet.isRunning -> "Online"
        state.tailnet.needsLogin -> "Sign-in needed"
        else -> state.tailnet.backendState
    }

@Composable
private fun PendingCard(
    pending: PendingApproval,
    actions: RemoteActions,
) {
    var allowRw by remember(pending.nodeId) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Devices, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        pending.displayName.ifBlank {
                            pending.nodeId
                        },
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Muted(identityLine(pending.user, pending.tags))
                }
            }
            Muted("Node ${pending.nodeId}")
            Row(Modifier.clickable { allowRw = !allowRw }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = allowRw, onCheckedChange = { allowRw = it })
                Text("Allow writable disks", style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { actions.approve(pending.nodeId, allowRw) }) { Text("Approve") }
                OutlinedButton(onClick = { actions.deny(pending.nodeId) }) { Text("Deny") }
            }
        }
    }
}

@Composable
private fun ControlCard(state: RemoteUiState) {
    val status = state.status
    val holder = status.leaseHolder
    val holderText =
        when (holder) {
            null -> "Nobody has control."
            LeaseHolder.Local -> "This phone's Control tab has priority; remote input waits until you leave it."
            is LeaseHolder.Remote ->
                "${status.sessions
                    .firstOrNull {
                        it.sessionId == holder.sessionId
                    }?.displayName ?: "Session ${holder.sessionId}"} has control."
        }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Controller", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (holder == LeaseHolder.Local) {
                    AssistChip(onClick = {}, label = { Text("Local priority") }, leadingIcon = { Icon(Icons.Outlined.PhoneAndroid, null) })
                }
            }
            Text(holderText, style = MaterialTheme.typography.bodyMedium)
            if (status.releasePending) {
                Text(
                    "A key release hasn't reached the computer yet; input is paused until it does.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            status.runningJob?.let { Muted("Running: ${it.name.lowercase().replace('_', ' ')}") }
            if (state.keepAwake) Muted("Keeping the phone awake while a device is connected.")
        }
    }
}

@Composable
private fun SessionRow(session: SessionStatus) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(session.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Muted(
                listOfNotNull(
                    "session ${session.sessionId}",
                    session.transport,
                    if (session.auth == AuthStatus.PENDING) "waiting for approval" else null,
                ).joinToString(" · "),
            )
        }
        if (session.holdsLease) StatusChip("In control")
    }
}

@Composable
private fun ApprovalRow(
    approval: ApprovalRecord,
    actions: RemoteActions,
) {
    val principal = Principal.Node(approval.nodeId)
    CredentialRow(
        title = approval.displayName.ifBlank { approval.nodeId },
        subtitle = identityLine(approval.user, emptyList()) + " · until ${formatDate(approval.expiresAtMs)}",
        allowRw = approval.allowRw,
        onAllowRw = { actions.setAllowRw(principal, it) },
        onRevoke = { actions.revoke(principal) },
    )
}

@Composable
private fun TokenRow(
    token: TokenRecord,
    actions: RemoteActions,
) {
    val principal = Principal.Token(token.sha256)
    CredentialRow(
        title = token.label.ifBlank { "Paired device" },
        subtitle = "paired ${formatDate(token.createdAtMs)} · until ${formatDate(token.expiresAtMs)}",
        allowRw = token.allowRw,
        onAllowRw = { actions.setAllowRw(principal, it) },
        onRevoke = { actions.revoke(principal) },
    )
}

@Composable
private fun CredentialRow(
    title: String,
    subtitle: String,
    allowRw: Boolean,
    onAllowRw: (Boolean) -> Unit,
    onRevoke: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Muted(subtitle)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Writable disks", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = allowRw, onCheckedChange = onAllowRw)
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onRevoke) { Text("Revoke") }
            }
        }
    }
}

@Composable
private fun HotspotCard(
    state: RemoteUiState,
    actions: RemoteActions,
    nowElapsedMs: Long,
    onStartHotspot: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (val hotspot = state.hotspot) {
                HotspotState.Off, is HotspotState.Failed -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.WifiTethering, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Muted(
                            "No internet or tailnet where you are? The phone can host its own Wi-Fi; a browser or the CLI pairs with a one-time code.",
                        )
                    }
                    if (hotspot is HotspotState.Failed) {
                        Text(
                            hotspot.reason,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(onClick = onStartHotspot, enabled = state.running) { Text("Start offline hotspot") }
                }
                HotspotState.Starting ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Muted("Starting the hotspot…")
                    }
                is HotspotState.On -> HotspotOn(hotspot.info, state, actions, nowElapsedMs)
            }
        }
    }
}

@Composable
private fun HotspotOn(
    info: HotspotInfo,
    state: RemoteUiState,
    actions: RemoteActions,
    nowElapsedMs: Long,
) {
    val pairing = state.status.pairing
    val code = pairing.code
    Text("1. Join this Wi-Fi", style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.CenterVertically) {
        QrCode(info.wifiQrPayload, Modifier.size(148.dp))
        Spacer(Modifier.width(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Muted("Network")
            Text(info.ssid, fontFamily = FontFamily.Monospace)
            if (info.passphrase.isNotEmpty()) {
                Muted("Password")
                Text(info.passphrase, fontFamily = FontFamily.Monospace)
            }
        }
    }
    Text("2. Open ${info.url}", style = MaterialTheme.typography.titleSmall)
    Muted("CLI: bootleg --host ${info.address}:${HotspotInfo.HOTSPOT_PORT} pair <code>")
    Text("3. Pair with a one-time code", style = MaterialTheme.typography.titleSmall)
    when {
        pairing.lockedOut -> {
            Text("Pairing is locked after too many wrong codes.", color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = actions::resetPairingLockout) { Text("Reset lockout") }
        }
        code != null -> {
            val remainingS = (((pairing.codeExpiresAtElapsedMs ?: nowElapsedMs) - nowElapsedMs).coerceAtLeast(0) + 999) / 1000
            Text(
                code.chunked(PAIRING_CODE_GROUP_LENGTH).joinToString(" "),
                style = MaterialTheme.typography.displaySmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
            )
            Muted("Single use, expires in %d:%02d".format(remainingS / 60, remainingS % 60))
            OutlinedButton(onClick = actions::disablePairing) { Text("Stop pairing") }
        }
        else -> Button(onClick = actions::enablePairing) { Text("Show pairing code") }
    }
    if (pairing.attemptsInWindow > 0) {
        Muted("${pairing.attemptsInWindow} of 10 pairing attempts used this hour.")
    }
    HorizontalDivider()
    TextButton(onClick = actions::stopHotspot) { Text("Stop offline hotspot") }
}

/** A QR code drawn from zxing's module matrix (no bitmap). */
@Composable
private fun QrCode(
    payload: String,
    modifier: Modifier = Modifier,
) {
    val matrix = remember(payload) { encodeQr(payload) }
    Canvas(modifier.background(Color.White).padding(6.dp)) {
        val cell = size.minDimension / matrix.width
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell, cell))
            }
        }
    }
}

private fun encodeQr(payload: String): BitMatrix =
    QRCodeWriter().encode(
        payload,
        BarcodeFormat.QR_CODE,
        0,
        0,
        mapOf(
            EncodeHintType.MARGIN to 0,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        ),
    )

@Composable
private fun StatusChip(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier =
            Modifier
                .background(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.shapes.small)
                .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun identityLine(
    user: String,
    tags: List<String>,
): String = user.ifBlank { tags.joinToString(", ").ifBlank { "tagged device" } }

private fun formatDate(wallMs: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(wallMs))

private const val HEALTH_LINES_MAX = 3
private const val PAIRING_CODE_GROUP_LENGTH = 3
