package com.lagradost.cloudstream4.desktop.player

import com.lagradost.cloudstream3.USER_AGENT
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Android: ExoPlayer sends each link's headers itself, so links are played as they are. */
actual object StreamGateway {
    /** Counts what the player downloads, for the speed shown while buffering. */
    internal object Meter {
        val bytes = AtomicLong()
        val connections = AtomicInteger()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _stats = MutableStateFlow(StreamStats())

    actual val stats: StateFlow<StreamStats> by lazy {
        scope.launch {
            var lastBytes = Meter.bytes.get()
            var lastTime = System.nanoTime()
            var speed = 0.0
            while (true) {
                delay(500)
                val now = System.nanoTime()
                val bytes = Meter.bytes.get()
                val instant = (bytes - lastBytes) * 1e9 / (now - lastTime).coerceAtLeast(1)
                speed = if (speed == 0.0) instant else speed * 0.6 + instant * 0.4
                lastBytes = bytes
                lastTime = now
                _stats.value = StreamStats(
                    bytesPerSecond = speed.toLong(),
                    connections = Meter.connections.get().coerceAtLeast(0),
                    totalBytes = bytes,
                )
            }
        }
        _stats.asStateFlow()
    }

    actual fun prepare(url: String, referer: String?, headers: Map<String, String>, parallel: Boolean): StreamSource {
        val all = buildMap {
            put("User-Agent", USER_AGENT)
            if (!referer.isNullOrBlank()) put("Referer", referer)
            headers.forEach { (k, v) ->
                keys.firstOrNull { it.equals(k, ignoreCase = true) }?.let { remove(it) }
                put(k, v)
            }
        }
        return StreamSource(url, all)
    }

    actual fun clear() {
        Meter.bytes.set(0)
        _stats.value = StreamStats()
    }

    actual fun previewUrl(url: String): String = url
}
