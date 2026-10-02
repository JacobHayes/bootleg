package dev.jacobhayes.bootleg.ui.catalog

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jacobhayes.bootleg.catalog.DistroEntry
import dev.jacobhayes.bootleg.core.catalog.Arch
import dev.jacobhayes.bootleg.core.catalog.Release
import dev.jacobhayes.bootleg.download.DownloadRecord
import dev.jacobhayes.bootleg.download.DownloadStatus
import dev.jacobhayes.bootleg.download.formatBytes
import dev.jacobhayes.bootleg.ui.common.formatAge

@Composable
fun DistroCard(
    entry: DistroEntry,
    arch: Arch,
    expanded: Boolean,
    records: Map<String, DownloadRecord>,
    onToggle: () -> Unit,
    onDownload: (Release) -> Unit,
) {
    val releases = entry.releases.filter { it.arch == arch }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.clickable(onClick = onToggle).padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(entry.distro.name, style = MaterialTheme.typography.titleMedium)
                DistroStatusLine(entry, releases.size, arch)
            }
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
            )
        }
        AnimatedVisibility(expanded && releases.isNotEmpty()) {
            Column {
                releases.groupBy { it.version }.forEach { (version, variants) ->
                    HorizontalDivider()
                    Text(
                        version + (variants.first().supportedUntil?.let { "  ·  supported until $it" } ?: ""),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp),
                    )
                    variants.forEach { release -> ReleaseRow(release, records[release.fileName], onDownload) }
                }
            }
        }
    }
}

@Composable
private fun DistroStatusLine(
    entry: DistroEntry,
    releaseCount: Int,
    arch: Arch,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val age = entry.fetchedAtMs?.let { formatAge(System.currentTimeMillis() - it) }
    if (entry.error != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                if (age != null) "Refresh failed: ${entry.error} · showing results from $age" else "Couldn't load: ${entry.error}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        return
    }
    val summary =
        when {
            entry.fetchedAtMs == null -> "Not loaded yet"
            releaseCount == 0 -> "No ${archLabel(arch)} images"
            releaseCount == 1 -> "1 image"
            else -> "$releaseCount images"
        }
    Text(listOfNotNull(summary, age?.let { "updated $it" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = muted)
}

@Composable
private fun ReleaseRow(
    release: Release,
    record: DownloadRecord?,
    onDownload: (Release) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(release.variant.ifBlank { release.fileName }, style = MaterialTheme.typography.bodyLarge)
            Text(
                listOfNotNull(release.sizeBytes?.let { formatBytes(it) }, release.fileName).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            record?.status == DownloadStatus.COMPLETED ->
                Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = "Downloaded",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(12.dp),
                )

            record != null && record.isActive ->
                CircularProgressIndicator(
                    progress = { record.progressFraction ?: 0f },
                    modifier = Modifier.padding(12.dp).size(24.dp),
                )

            else ->
                IconButton(onClick = { onDownload(release) }) {
                    Icon(Icons.Outlined.Download, contentDescription = "Download ${release.fileName}")
                }
        }
    }
}
