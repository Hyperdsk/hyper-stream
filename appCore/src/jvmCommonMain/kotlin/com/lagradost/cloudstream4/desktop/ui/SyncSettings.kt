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
    // Signed out: "link" (or "code" on a TV) or "password"
    var mode by remember { mutableStateOf(if (com.lagradost.cloudstream4.desktop.Platform.isTv) "code" else "link") }
    var settingPassword by remember { mutableStateOf(false) }
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
                    if (!com.lagradost.cloudstream4.desktop.Platform.isTv) {
                        OutlinedButton(onClick = { settingPassword = !settingPassword }) { Text("Set a password") }
                    }
                    OutlinedButton(onClick = { SyncManager.signOut() }) { Text("Sign out") }
                }
                if (settingPassword) {
                    var password by remember { mutableStateOf("") }
                    Text("For signing in on devices without your email, such as a TV.", style = body, color = dim)
                    PasswordField(password, { password = it }, onDone = { SyncManager.setPassword(password) })
                    Button(onClick = { SyncManager.setPassword(password); settingPassword = false }, enabled = password.length >= 6 && !ui.busy) { Text("Save password") }
                }
                ui.notice?.let { Text(it, style = body, color = MaterialTheme.colorScheme.primary) }
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

            mode == "password" -> {
                ModeChips(mode) { mode = it }
                Text("Sign in with your email and password, or create an account. No email is sent.", style = body, color = dim)
                TvTextFieldBox(Modifier.widthIn(max = 480.dp).fillMaxWidth()) { fieldModifier ->
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text("Email") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                        modifier = fieldModifier,
                    )
                }
                PasswordField(code, { code = it }, onDone = { SyncManager.signInWithPassword(email, code, create = false) })
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    val ready = email.contains('@') && code.length >= 6 && !ui.busy
                    Button(onClick = { SyncManager.signInWithPassword(email, code, create = false) }, enabled = ready) {
                        Text(if (ui.busy) "Signing in…" else "Sign in")
                    }
                    OutlinedButton(onClick = { SyncManager.signInWithPassword(email, code, create = true) }, enabled = ready) { Text("Create account") }
                }
            }

            com.lagradost.cloudstream4.desktop.Platform.isTv -> {
                ModeChips(mode) { mode = it }
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
                ModeChips(mode) { mode = it }
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

/** How to sign in: an emailed link (a code from a phone or Mac on TVs), or a password. */
@Composable
private fun ModeChips(mode: String, onMode: (String) -> Unit) {
    val tv = com.lagradost.cloudstream4.desktop.Platform.isTv
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val first = if (tv) "code" else "link"
        androidx.compose.material3.FilterChip(
            selected = mode == first,
            onClick = { onMode(first) },
            label = { Text(if (tv) "Code from phone or Mac" else "Email link") },
        )
        androidx.compose.material3.FilterChip(selected = mode == "password", onClick = { onMode("password") }, label = { Text("Password") })
    }
}

@Composable
private fun PasswordField(value: String, onValue: (String) -> Unit, onDone: () -> Unit) {
    TvTextFieldBox(Modifier.widthIn(max = 480.dp).fillMaxWidth()) { fieldModifier ->
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (value.length >= 6) onDone() }),
            modifier = fieldModifier,
        )
    }
}
