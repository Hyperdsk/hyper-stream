package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream4.desktop.ui.providerByName
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertNull

/** Looking a provider up while extensions are still adding theirs, as on a cold start. */
class ProviderLookupTest {
    private class Api(override var name: String) : MainAPI() {
        init { sourcePlugin = "test-lookup" }
    }

    @Test
    fun lookupsWhileProvidersAreAddedDontThrow() {
        var failure: Throwable? = null
        val adder = thread {
            repeat(20_000) { APIHolder.allProviders.add(Api("Lookup $it")) }
        }
        val reader = thread {
            try {
                while (adder.isAlive) providerByName("Not there")
            } catch (t: Throwable) {
                failure = t
            }
        }
        adder.join()
        reader.join()
        APIHolder.allProviders.withLock { APIHolder.allProviders.removeAll { it.sourcePlugin == "test-lookup" } }
        assertNull(failure, "a lookup failed while providers were added: $failure")
    }
}
