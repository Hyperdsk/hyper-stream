package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream4.desktop.data.AppDirs
import com.lagradost.cloudstream4.desktop.plugins.ProviderNames
import com.lagradost.cloudstream4.desktop.ui.providerByName
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** Two extensions with a provider of the same name: each must still be found as itself. */
class ProviderNamesTest {
    private class Api(override var name: String, plugin: String) : MainAPI() {
        init { sourcePlugin = plugin }
    }

    private val created = mutableListOf<String>()

    private fun plugin(repo: String, file: String) =
        File(File(AppDirs.extensions, repo), file).absolutePath.also { created += it }

    private fun load(api: Api) {
        APIHolder.allProviders.add(api)
        APIHolder.addPluginMapping(api)
        ProviderNames.makeUnique(api.sourcePlugin!!)
    }

    @AfterTest
    fun unload() {
        APIHolder.allProviders.withLock { APIHolder.allProviders.removeAll { it.sourcePlugin in created } }
    }

    @Test
    fun theSecondExtensionGetsItsOwnName() {
        val cnc = Api("Test Prime", plugin("raw.githubusercontent.com_NivinCNC_CNCVerse_builds", "CNC Verse.cs3"))
        val allOtt = Api("Test Prime", plugin("raw.githubusercontent.com_RVRBEAST76_allforu_builds", "AllOtt.cs3"))
        load(cnc)
        load(allOtt)
        assertEquals("Test Prime", cnc.name)
        assertEquals("Test Prime (AllOtt)", allOtt.name)
        assertSame(cnc, providerByName("Test Prime"))
        assertSame(allOtt, providerByName("Test Prime (AllOtt)"))
    }

    @Test
    fun theOwnerKeepsTheNameWhenLoadedInAnotherOrder() {
        // First run: A, then B
        val a = Api("Test Order", plugin("raw.githubusercontent.com_Alice_repo", "OrderA.cs3"))
        val b = Api("Test Order", plugin("raw.githubusercontent.com_Bob_repo", "OrderB.cs3"))
        load(a)
        load(b)
        assertEquals("Test Order (OrderB)", b.name)
        unload()

        // Next run (A was updated, so it loads last): A still keeps the plain name
        val b2 = Api("Test Order", b.sourcePlugin!!)
        val a2 = Api("Test Order", a.sourcePlugin!!)
        load(b2)
        assertEquals("Test Order", b2.name, "alone, it has the plain name for now")
        load(a2)
        assertEquals("Test Order", a2.name)
        assertEquals("Test Order (OrderB)", b2.name)
        assertSame(a2, providerByName("Test Order"))
    }

    @Test
    fun sameFileNamesFromTwoRepositoriesUseTheRepositoryOwner() {
        val one = Api("Test Drive", plugin("raw.githubusercontent.com_SaurabhKaperwan_CSX_builds", "Test Drive.cs3"))
        val two = Api("Test Drive", plugin("raw.githubusercontent.com_Faisal0786_Desi_main", "Test Drive.cs3"))
        load(one)
        load(two)
        assertEquals("Test Drive", one.name)
        assertEquals("Test Drive (Faisal0786)", two.name)
    }
}
