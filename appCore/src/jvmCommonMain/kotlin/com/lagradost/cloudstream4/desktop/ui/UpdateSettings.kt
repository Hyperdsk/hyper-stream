package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.desktop.DesktopToasts
import com.lagradost.cloudstream4.desktop.Platform
import com.lagradost.cloudstream4.desktop.UpdateTarget
import com.lagradost.cloudstream4.desktop.data.SettingsStore
import com.lagradost.cloudstream4.desktop.update.UpdateChecker
import com.lagradost.cloudstream4.desktop.update.UpdateState
import kotlinx.coroutines.launch

/** Settings > Updates: checks GitHub and installs the release for this platform. */
@Composable
internal fun UpdateSettings() {
    val settings by SettingsStore.state.collectAsState()
    val state by UpdateChecker.state.collectAsState()
    val scope = rememberCoroutineScope()
    val download by UpdateChecker.download.collectAsState()
    val downloading = download?.running == true
    val progress = download?.progress
    val installError = download?.error

    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Check for updates automatically")
            Text(
                "Looks at GitHub for a newer release about every six hours.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = settings.checkForUpdates,
            onCheckedChange = { enabled -> SettingsStore.update { it.copy(checkForUpdates = enabled) } },
        )
    }

    Text(
        "Installed version ${UpdateChecker.currentVersion}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )

    when (val current = state) {
        UpdateState.Idle -> Text(
            "Check for a new release to update without leaving the app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )

        UpdateState.Checking -> Row(
            Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text("Checking GitHub…", style = MaterialTheme.typography.bodySmall)
        }

        is UpdateState.UpToDate -> Text(
            "You're on the latest version.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )

        is UpdateState.Failed -> Text(
            current.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 6.dp),
        )

        is UpdateState.Available -> {
            val release = current.release
            Text(
                "Version ${release.version} is available.",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 10.dp),
            )
            if (release.notes.isNotBlank()) {
                Text(
                    release.notes.take(700),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Row(
                Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    enabled = !downloading,
                    onClick = { UpdateChecker.startDownload(release) },
                ) {
                    Text(if (Platform.updateTarget == UpdateTarget.Android) "Download and install" else "Download update")
                }
                if (release.pageUrl.isNotBlank()) {
                    OutlinedButton(onClick = {
                        if (!Platform.openUrl(release.pageUrl)) {
                            DesktopToasts.show("Couldn't open the release page in a browser.")
                        }
                    }) { Text("Release notes") }
                }
            }
            if (downloading) {
                val value = progress
                if (value == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
                } else {
                    LinearProgressIndicator(
                        progress = { value },
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                    )
                }
                Text(
                    "Downloading the update…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            installError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }

    OutlinedButton(
        onClick = { scope.launch { UpdateChecker.check() } },
        enabled = state != UpdateState.Checking && !downloading,
        modifier = Modifier.padding(top = 12.dp),
    ) {
        Text("Check for updates")
    }
}
