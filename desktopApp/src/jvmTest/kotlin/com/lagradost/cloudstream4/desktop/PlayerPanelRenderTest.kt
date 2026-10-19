package com.lagradost.cloudstream4.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream4.desktop.player.PlaybackState
import com.lagradost.cloudstream4.desktop.ui.PanelTab
import com.lagradost.cloudstream4.desktop.ui.PlayRequest
import com.lagradost.cloudstream4.desktop.ui.PlayableEpisode
import com.lagradost.cloudstream4.desktop.ui.PlayerModel
import com.lagradost.cloudstream4.desktop.ui.PlayerPanel
import com.lagradost.cloudstream4.desktop.ui.PlayerUiState
import com.lagradost.cloudstream4.theme.CloudStreamTheme
import com.lagradost.cloudstream4.theme.CloudStreamThemeMode
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/** Renders the player's source panel to a PNG for a visual check. Runs only when CS_RENDER_DIR is set. */
class PlayerPanelRenderTest {
    @Test
    fun rendersSourcesPanel() = runBlocking {
        val dir = System.getenv("CS_RENDER_DIR")?.let(::File) ?: return@runBlocking
        val names = listOf(
            "GDFlix[Direct] Street-Fighter.2026.720p.HEVC.HDTS.Hindi-Telugu.CLEAN-English.ORG.x265-MoviesDrives.BID.mkv[765.74MB | Views : 0]" to 720,
            "GDFlix[Direct] Street-Fighter.2026.1080p.HEVC.HDTS.Hindi-Telugu.CLEAN-English.ORG.x265-MoviesDrives.BID.mkv[1.71GB | Views : 0]" to 1080,
            "Hub-Cloud[FSLv2 Server] Street-Fighter.2026.1080p.HDTS.Hindi-Telugu.CLEAN-English.ORG.x264-MoviesDrives.BID.mkv[2.51 GB]" to 1080,
            "Hub-Cloud[FSL Server] Street-Fighter.2026.1080p.HDTS.Hindi-Telugu.CLEAN-English.ORG.x264-MoviesDrives.BID.mkv[2.51 GB]" to 1080,
            "Hub-Cloud[FSL Server] Street-Fighter.2026.HQ.1080p.HDTS.Hindi-Telugu.CLEAN-English.ORG.x264-MoviesDrives.BID.mkv[15.22 GB]" to 1080,
            "Hub-Cloud[Pixeldrain] Street-Fighter.2026.1080p.HDTS.Hindi-Telugu.CLEAN-English.ORG.x264-MoviesDrives.BID.mkv[2.51 GB]" to 1080,
            "Hub-Cloud[FSL Server] Street-Fighter.2026.720p.HEVC.HDTS.Hindi-Telugu.CLEAN-English.ORG.x265-MoviesDrives.BID.mkv[765.74 MB]" to 720,
            "Hub-Cloud[FSLv2 Server] Street-Fighter.2026.720p.HDTS.Hindi-Telugu.CLEAN-English.ORG.x264-MoviesDrives.BID.mkv[1.18 GB]" to 720,
            "Vidplay" to 1080,
            "StreamWish 480p" to 480,
        )
        val links = names.map { (n, q) -> newExtractorLink(n.substringBefore('['), n, "https://example.com/v.mkv", ExtractorLinkType.VIDEO) { quality = q } }
        val ui = PlayerUiState(index = 0, links = links.toPersistentList(), loadingLinks = true, current = 2, failed = persistentSetOf(1))
        val model = PlayerModel(PlayRequest("none", "u", "Street Fighter", null, null, listOf(PlayableEpisode("d", null, null, null)), 0), null)
        val scene = ImageComposeScene(1400, 900, Density(1.5f)) {
            CloudStreamTheme(mode = CloudStreamThemeMode.Dark) {
                Box(Modifier.fillMaxSize().background(Color(0xFF3A5A50))) {
                    PlayerPanel(PanelTab.Sources, {}, ui, PlaybackState(), model, {})
                }
            }
        }
        repeat(3) { scene.render() }
        dir.mkdirs()
        File(dir, "sources-panel.png").writeBytes(scene.render().encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }
}
