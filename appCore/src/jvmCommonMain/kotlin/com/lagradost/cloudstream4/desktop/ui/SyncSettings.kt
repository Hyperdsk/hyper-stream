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

/** Settings' Sync section: sign in with an emailed code, then see when it last synced. */
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
                // Refreshes "2 min ago" while the screen is open
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
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
                    OutlinedButton(onClick = { SyncManager.signOut() }) { Text("Sign out") }
                }
            }

            ui.codeSentTo != null -> {
                Text(
                    "We emailed ${ui.codeSentTo}. Enter the code from it, or copy the sign-in link from the email and paste it here.",
                    style = body, color = dim,
                )
                TvTextFieldBox(Modifier.widthIn(max = 480.dp).fillMaxWidth()) { fieldModifier ->
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        label = { Text("Code or link") },
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
                        keyboardActions = KeyboardActions(onSend = { if (email.contains('@')) SyncManager.sendCode(email) }),
                        modifier = fieldModifier,
                    )
                }
                Button(onClick = { SyncManager.sendCode(email) }, enabled = email.contains('@') && !ui.busy) {
                    Text(if (ui.busy) "Sending…" else "Email me a sign-in code")
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
