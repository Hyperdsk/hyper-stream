# CloudStream for macOS

A native desktop build of CloudStream, made with Compose Multiplatform on top of the
cross-platform `library` module. It loads the same extensions as the Android app.

## Requirements

- JDK 17 or newer
- VLC installed in `/Applications` (the player uses its libVLC)
- The Android SDK, only because the Gradle build also configures the Android targets

## Run and build

```bash
./gradlew :desktopApp:run
```

```bash
./gradlew :desktopApp:createDistributable   # CloudStream.app in desktopApp/build/compose/binaries/main/app
./gradlew :desktopApp:packageDmg            # installer .dmg
```

```bash
./gradlew :desktopApp:jvmTest
```

To debug, start with a debugger listening on `127.0.0.1:5005`, then attach from IntelliJ with a Remote JVM Debug configuration:

```bash
./gradlew :desktopApp:run -PdebugApp
```

App data lives in `~/Library/Application Support/CloudStream`.
Start with `-Dcloudstream.dataDir=/some/folder` to use another folder.

## How it works

| Area | Where | Notes |
| --- | --- | --- |
| Extensions | `desktop/plugins/PluginLoader.kt` | Unzips a `.cs3`, converts `classes.dex` to a JVM jar with dex2jar, loads it in its own class loader. |
| Android stand-ins | `android/`, `androidx/`, `cloudstream3/plugins/Plugin.kt` | Same class names and signatures as Android, so extensions link without recompiling. `getSharedPreferences` is file-backed. |
| Repositories | `desktop/plugins/ExtensionManager.kt` | Same `repo.json` and `plugins.json` formats, links, `cloudstreamrepo://` and short codes as Android. A link to a list of repositories, such as the community `repos-db.json`, adds every repository in it. |
| Repository API for extensions | `cloudstream3/plugins/RepositoryManager.kt` | Same names and signatures as Android, so extensions that add repositories themselves, such as MegaRepo's MegaProvider, work. |
| Translation fixes | `desktop/plugins/InnerClassFixer.kt` | Undoes a wrong guess dex2jar makes about local classes in shrunk extensions, which otherwise breaks Kotlin reflection and JSON parsing. |
| Player | `desktop/player/VlcPlayer.kt` | vlcj renders frames into Compose. Tracks, subtitles, speed, resume. |
| Stream proxy | `desktop/player/StreamProxy.kt` | Loopback-only HTTP proxy that adds each link's headers and rewrites HLS playlists, since VLC can't send arbitrary headers. |
| Screens | `desktop/ui/` | Home, Search, Details, Player, Library, Extensions, Settings. One immutable state per screen, as `COMPOSE.md` asks. |
| Downloads | `desktop/download/DownloadManager.kt` | Direct files resume with Range requests. HLS uses the library's `M3u8Helper2` like Android, including AES-128. Saved to `~/Movies/CloudStream` by default, with subtitles alongside. Downloaded episodes play offline. |
| Demo provider | `desktop/plugins/DemoProvider.kt` | Off by default. Open movies from public test streams, for trying the app without extensions. |

## Not done yet

- Chromecast, account sync (AniList, MAL, Simkl) and subtitle search.
- DASH streams and HLS streams with separate audio tracks can't be downloaded, as on Android.
- Extensions that need a WebView (Cloudflare checks) fail, because `WebViewResolver` has no JVM implementation upstream.
- Extensions that use Android UI or other app-only classes fail to load, with the missing class shown on the Extensions screen.
- Torrent links are skipped.
- Extension settings screens are Android-only and are not shown.

## Upstreaming

The CloudStream maintainers have said they won't accept AI-written code for the Compose
rewrite. Treat this as a fork, under the project's GPL-3.0 license.
