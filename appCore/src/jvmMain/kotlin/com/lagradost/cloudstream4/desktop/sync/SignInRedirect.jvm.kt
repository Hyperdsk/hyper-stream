package com.lagradost.cloudstream4.desktop.sync

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Mac: the link returns to a page this app serves on the computer itself. The session is in the
 * part of the address after "#", which browsers keep to the page, so the page hands it to the app
 * and says the sign-in worked.
 */
actual object SignInRedirect {
    private const val PORT = 53682
    private var server: HttpServer? = null

    actual val url: String? get() = if (server != null) "http://127.0.0.1:$PORT/auth" else null

    @Synchronized
    actual fun prepare() {
        if (server != null) return
        server = runCatching {
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 0).apply {
                createContext("/auth") { ex -> ex.send(PAGE, "text/html") }
                createContext("/auth/done") { ex ->
                    val result = runCatching { runBlocking { SyncManager.completeRedirect("http://127.0.0.1/#" + (ex.requestURI.rawQuery ?: "")) } }
                    ex.send(if (result.isSuccess) "ok" else "failed", "text/plain")
                }
                start()
            }
        }.getOrNull()
    }

    private fun HttpExchange.send(text: String, type: String) {
        val bytes = text.toByteArray()
        responseHeaders.set("Content-Type", "$type; charset=utf-8")
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private val PAGE = """
        <!doctype html><html><head><meta charset="utf-8"><title>Hyper Stream</title>
        <style>
          body { font: 16px -apple-system, system-ui, sans-serif; background: #111; color: #eee; display: grid; place-items: center; height: 100vh; margin: 0; }
          div { text-align: center; max-width: 420px; padding: 24px; }
          h1 { font-size: 22px; margin: 0 0 8px; }
          p { color: #aaa; margin: 0; }
          .mark { width: 56px; height: 56px; border-radius: 12px; background: #0A47F8; color: #fff; font-weight: 700; font-size: 22px; display: grid; place-items: center; margin: 0 auto 16px; }
        </style></head>
        <body><div><div class="mark">HS</div><h1 id="t">Signing in to Hyper Stream…</h1><p id="m"></p></div>
        <script>
          const hash = location.hash.slice(1);
          const t = document.getElementById('t'), m = document.getElementById('m');
          const p = new URLSearchParams(hash);
          if (p.get('error_description')) {
            t.textContent = "Sign-in didn't work";
            m.textContent = p.get('error_description') + '. Ask Hyper Stream for a new link.';
          } else {
            fetch('/auth/done?' + hash).then(r => r.text()).then(r => {
              if (r === 'ok') { t.textContent = "You're signed in"; m.textContent = 'Go back to Hyper Stream. You can close this tab.'; }
              else { t.textContent = "Sign-in didn't work"; m.textContent = 'Ask Hyper Stream for a new link.'; }
            }).catch(() => { t.textContent = "Hyper Stream isn't open"; m.textContent = 'Open the app and ask for a new link.'; });
          }
          history.replaceState(null, '', '/auth');
        </script></body></html>
    """.trimIndent()
}
