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
./gradlew :appCore:jvmTest
```

To check how many of a folder's extensions load, point the compatibility sweep at it:

```bash
CS_COMPAT_DIR="$HOME/Library/Application Support/CloudStream/Extensions" ./gradlew :appCore:jvmTest --tests '*Compatibility*'
```

To debug, start with a debugger listening on `127.0.0.1:5005`, then attach from IntelliJ with a Remote JVM Debug configuration:

```bash
./gradlew :desktopApp:run -PdebugApp
```

`run` loads classes from the build folder, so rebuilding or running tests while it is open can crash it with `ClassNotFoundException`. To keep a session safe while you work, run a packaged copy instead (with `-PdebugApp` it includes the debugger agent):

```bash
./gradlew :desktopApp:createDistributable -PdebugApp && ./desktopApp/build/compose/binaries/main/app/CloudStream.app/Contents/MacOS/CloudStream
```

App data lives in `~/Library/Application Support/CloudStream`.
Start with `-Dcloudstream.dataDir=/some/folder` to use another folder.

## App icon

`src/desktop-icons/icon.svg` is the Android app's cloud logo (`app/src/main/res/drawable/ic_launcher_foreground.xml`) on Apple's icon grid. To regenerate `icon-mac.icns` after changing it:

```bash
CS_ICON_DIR=/tmp/cs-icon ./gradlew :appCore:jvmTest --tests '*AppIconRender*'
```

Then copy the PNGs into a `CloudStream.iconset` folder with Apple's names (`icon_16x16.png`, `icon_16x16@2x.png` and so on up to `icon_512x512@2x.png`) and run `iconutil -c icns CloudStream.iconset -o desktopApp/src/desktop-icons/icon-mac.icns`.

## How it works

The screens, extension management, storage and downloads are shared with the Android phone and TV app (see `androidApp/ANDROID.md`). They live in `appCore/src/jvmCommonMain`. Mac-only parts (VLC, the stream proxy, dex2jar and the Android stand-ins) are in `appCore/src/jvmMain`, and `desktopApp` only has the window and menu bar. Paths below are under `com/lagradost/` in those folders.

| Area | Where | Notes |
| --- | --- | --- |
| Extensions | `desktop/plugins/PluginLoader.kt` | Unzips a `.cs3`, converts `classes.dex` to a JVM jar with dex2jar, loads it in its own class loader. |
| Android stand-ins | `android/`, `androidx/`, `cloudstream3/plugins/Plugin.kt` | Same class names and signatures as Android, so extensions link without recompiling. `getSharedPreferences` is file-backed. |
| Repositories | `desktop/plugins/ExtensionManager.kt` | Same `repo.json` and `plugins.json` formats, links, `cloudstreamrepo://` and short codes as Android. A link to a list of repositories, such as the community `repos-db.json`, adds every repository in it. |
| Repository API for extensions | `cloudstream3/plugins/RepositoryManager.kt` | Same names and signatures as Android, so extensions that add repositories themselves, such as MegaRepo's MegaProvider, work. |
| Android placeholders | `desktop/plugins/AndroidStubs.kt` | Generates do-nothing versions of the Android classes an extension mentions (settings screens, toasts) and redirects calls to members the desktop stand-ins lack. App class relationships come from the Android sources at build time (`generateAppClassIndex`). |
| Translation fixes | `desktop/plugins/InnerClassFixer.kt` | Undoes a wrong guess dex2jar makes about local classes in shrunk extensions, which otherwise breaks Kotlin reflection and JSON parsing. |
| Player | `desktop/player/VlcPlayer.kt` | vlcj renders frames into Compose. Tracks, subtitles, speed, resume. |
| Stream proxy | `desktop/player/StreamProxy.kt` | Loopback-only HTTP proxy that adds each link's headers and rewrites HLS playlists, since VLC can't send arbitrary headers. |
| Screens | `desktop/ui/` | Home, Search, Details, Player, Library, Extensions, Settings. One immutable state per screen, as `COMPOSE.md` asks. |
| Downloads | `desktop/download/DownloadManager.kt` | Direct files resume with Range requests. HLS uses the library's `M3u8Helper2` like Android, including AES-128. Saved to `~/Movies/CloudStream` by default, with subtitles alongside. Downloaded episodes play offline. |
| Parallel streaming | `desktop/player/ParallelStream.kt`, `StreamProxy.kt` | Direct video files are fetched ahead in 2 MB chunks over up to 16 connections, and HLS segments are prefetched in parallel. Set under Player, Streaming connections. |
| Skip intros | `desktop/player/SkipStamps.kt` | Same free sources as Android: AniSkip, TheIntroDB, IntroDB. Skip button, optional auto-skip, S key, and the fixed 85-second "Skip opening" button. |
| Demo provider | `desktop/plugins/DemoProvider.kt` | Off by default. Open movies from public test streams, for trying the app without extensions. |

## Not done yet

- Chromecast, account sync (AniList, MAL, Simkl) and subtitle search.
- DASH streams and HLS streams with separate audio tracks can't be downloaded, as on Android.
- Extensions that need a WebView (Cloudflare checks) fail, because `WebViewResolver` has no JVM implementation upstream.
- Extension settings screens are Android UI and stay off. The Extensions screen says so for each affected extension.
- Torrent links are skipped.

## Upstreaming

The CloudStream maintainers have said they won't accept AI-written code for the Compose
rewrite. Treat this as a fork, under the project's GPL-3.0 license.
