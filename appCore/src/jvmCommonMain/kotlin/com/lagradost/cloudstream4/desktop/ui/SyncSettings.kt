package com.lagradost.cloudstream4.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.desktop.sync.SyncManager
import kotlinx.coroutines.delay

/** Settings' Sync section: sign in with an emailed link (or, on a TV, a code from a signed-in phone or Mac), then see when it last synced. */
@Composable
fun SyncSettings() {
    val ui by SyncManager.ui.collectAsState()
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val body = MaterialTheme.typography.bodySmall
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            ui.signedIn -> {
                Text("Signed in as ${ui.email}", style = MaterialTheme.typography.bodyMedium)
                // Refreshes "2 min ago" and the TV code's time left while the screen is open
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) { while (true) { delay(1_000); now = System.currentTimeMillis() } }
                Text(
                    when {
                        ui.syncing -> "Syncing…"
                        ui.lastSyncedAt != null -> "Synced ${ago(now - ui.lastSyncedAt!!)}. Progress, library, settings and extensions stay the same on your devices."
                        else -> "Not synced yet."
                    },
                    style = body, color = dim,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = SyncManager::requestSync, enabled = !ui.syncing) { Text("Sync now") }
                    if (!com.lagradost.cloudstream4.desktop.Platform.isTv) {
                        OutlinedButton(onClick = { SyncManager.showTvCode() }, enabled = !ui.busy) { Text("Sign in a TV") }
                    }
                    OutlinedButton(onClick = { SyncManager.signOut() }) { Text("Sign out") }
                }
                val code = ui.tvCode
                val expires = ui.tvCodeExpiresAt
                if (code != null && expires != null) {
                    val left = ((expires - now) / 1000).coerceAtLeast(0)
                    if (left == 0L) {
                        Text("The TV code expired.", style = body, color = dim)
                        OutlinedButton(onClick = { SyncManager.showTvCode() }) { Text("New code") }
                    } else {
                        Text("On your TV, open Hyper Stream, Settings, Sync, and enter:", style = body, color = dim, modifier = Modifier.padding(top = 8.dp))
                        Text(
                            code.chunked(4).joinToString(" "),
                            style = MaterialTheme.typography.displaySmall,
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text("Works once, for ${left / 60}:${"%02d".format(left % 60)} more.", style = body, color = dim)
                        TextButton(onClick = SyncManager::hideTvCode) { Text("Done") }
                    }
                }
            }

            com.lagradost.cloudstream4.desktop.Platform.isTv -> {
                // TVs can't open an email, so a signed-in phone or Mac gives them a code
                Text(
                    "Sign in with your phone or Mac: open Hyper Stream there, go to Settings, Sync, and choose Sign in a TV. " +
                        "Then enter the code it shows here.",
                    style = body, color = dim,
                )
                TvTextFieldBox(Modifier.widthIn(max = 360.dp).fillMaxWidth()) { fieldModifier ->
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.filter { c -> c.isDigit() }.take(8) },
                        label = { Text("8-digit code") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (code.length == 8) SyncManager.signInWithTvCode(code) }),
                        modifier = fieldModifier,
                    )
                }
                Button(onClick = { SyncManager.signInWithTvCode(code) }, enabled = code.length == 8 && !ui.busy) {
                    Text(if (ui.busy) "Signing in…" else "Sign in")
                }
            }

            ui.codeSentTo != null -> {
                Text(
                    "We emailed a sign-in link to ${ui.codeSentTo}. Open it on this device and Hyper Stream signs in by itself.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Opened it somewhere else? Copy the link, or the address it opened, and paste it here.", style = body, color = dim)
                TvTextFieldBox(Modifier.widthIn(max = 480.dp).fillMaxWidth()) { fieldModifier ->
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("Link") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (code.isNotBlank()) SyncManager.verify(code) }),
                        modifier = fieldModifier,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { SyncManager.verify(code) }, enabled = code.isNotBlank() && !ui.busy) {
                        Text(if (ui.busy) "Signing in…" else "Sign in")
                    }
                    TextButton(onClick = { SyncManager.sendLink(ui.codeSentTo!!) }, enabled = !ui.busy) { Text("Send again") }
                    TextButton(onClick = { code = ""; SyncManager.cancelCode() }) { Text("Use another email") }
                }
            }

            else -> {
                Text(
                    "Keep watch progress, your library, settings and extensions the same on your Mac, phone and TV. " +
                        "Optional: everything works without it.",
                    style = body, color = dim,
                )
                TvTextFieldBox(Modifier.widthIn(max = 480.dp).fillMaxWidth()) { fieldModifier ->
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (email.contains('@')) SyncManager.sendLink(email) }),
                        modifier = fieldModifier,
                    )
                }
                Button(onClick = { SyncManager.sendLink(email) }, enabled = email.contains('@') && !ui.busy) {
                    Text(if (ui.busy) "Sending…" else "Email me a sign-in link")
                }
            }
        }
        ui.error?.let { Text(it, style = body, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }
    }
}

private fun ago(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} days ago"
    }
}
