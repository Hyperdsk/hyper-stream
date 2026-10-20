package com.lagradost.cloudstream4.desktop.player

import kotlinx.coroutines.flow.StateFlow

/** Desktop: VLC can't send headers, so links go through the local [StreamProxy]. */
actual object StreamGateway {
    actual val stats: StateFlow<StreamStats> get() = StreamProxy.stats

    actual fun prepare(url: String, referer: String?, headers: Map<String, String>, parallel: Boolean): StreamSource =
        StreamSource(StreamProxy.proxiedUrl(url, referer, headers, parallel))

    actual fun clear() = StreamProxy.clear()

    // "?preview" marks the seek bar player's requests as low priority in the proxy
    actual fun previewUrl(url: String): String = if (url.startsWith("http://127.0.0.1")) "$url?preview" else url
}
