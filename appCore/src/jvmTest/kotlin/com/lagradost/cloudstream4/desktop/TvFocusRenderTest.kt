package com.lagradost.cloudstream4.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream4.desktop.ui.HyperStreamTheme
import com.lagradost.cloudstream4.desktop.ui.TvRipple
import com.lagradost.cloudstream4.theme.CloudStreamPrimaryColor
import com.lagradost.cloudstream4.theme.CloudStreamThemeMode
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** Material controls focused with the TV ring, one per row, in both themes. Opt-in: CS_TVFOCUS_OUT. */
class TvFocusRenderTest {
    @OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.InternalComposeUiApi::class)
    @Test
    fun focusedControlsShowARing() {
        val out = System.getenv("CS_TVFOCUS_OUT")?.let(::File) ?: return
        out.mkdirs()
        for (mode in listOf(CloudStreamThemeMode.Dark, CloudStreamThemeMode.Light)) {
            val scene = ImageComposeScene(760, 200, Density(1.5f)) {
                HyperStreamTheme(mode, CloudStreamPrimaryColor.NORMAL) {
                    CompositionLocalProvider(LocalRippleConfiguration provides TvRipple, androidx.compose.material3.LocalRippleThemeConfiguration provides com.lagradost.cloudstream4.desktop.ui.TvRippleShape) {
                        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                            val focus = remember { List(4) { FocusRequester() } }
                            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                FilterChip(selected = false, onClick = {}, label = { Text("Movies") }, modifier = Modifier.focusRequester(focus[0]))
                                OutlinedButton(onClick = {}, modifier = Modifier.focusRequester(focus[1])) { Text("Sync now") }
                                Button(onClick = {}, modifier = Modifier.focusRequester(focus[2])) { Text("Sign in") }
                                Switch(checked = true, onCheckedChange = {}, modifier = Modifier.focusRequester(focus[3]))
                            }
                            // Focus each in turn; the last one rendered keeps its ring, so show them one by one
                            LaunchedEffect(Unit) { focus[1].requestFocus() }
                        }
                    }
                }
            }
            repeat(10) { scene.render(it * 50_000_000L) }
            // A key moves focus, as a remote does: Material draws focus for keys, not for touch
            scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(androidx.compose.ui.input.key.Key.Tab, androidx.compose.ui.input.key.KeyEventType.KeyDown))
            scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(androidx.compose.ui.input.key.Key.Tab, androidx.compose.ui.input.key.KeyEventType.KeyUp))
            repeat(20) { scene.render(600_000_000L + it * 50_000_000L) }
            File(out, "tvfocus-${mode.name}.png").writeBytes(scene.render(2_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
        }
    }
}
