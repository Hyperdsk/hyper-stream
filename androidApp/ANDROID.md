# Hyper Stream for Android phones, tablets and TV

The same Compose app as the Mac version, built for Android. It is a separate app from upstream's
`:app` (the View-based Android app), with its own application id, `com.lagradost.cloudstream4.app`,
so both can be installed side by side.

## Build and install

```bash
./gradlew :androidApp:assembleDebug
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

`assembleRelease` makes a release build signed with the debug key, so it sideloads without a keystore.
One APK runs on phones, tablets and Android TV (it shows in the TV launcher with a banner).

## How it differs from the Mac app

| Area | Android | Mac |
| --- | --- | --- |
| Extensions | `.cs3` files load as they are, in a `DexClassLoader`, like upstream's app (`appCore/src/androidMain/.../plugins/PluginLoader.android.kt`). | Converted to JVM bytecode with dex2jar. |
| App classes extensions use | `Plugin`, `CommonActivity` and `RepositoryManager` with the same names as upstream, in `appCore/src/androidMain/.../cloudstream3`. The activity is an `AppCompatActivity`, since extensions cast to it. | Stand-ins plus generated placeholders. |
| Player | Media3 ExoPlayer (`ExoVideoEngine.kt`), with each link's headers sent through the extensions' OkHttp client. Subtitles render with Media3's `SubtitleView`. | VLC, through a local proxy for headers and parallel connections. |
| Layout | Under 600dp wide (phones in portrait): bottom bar, smaller posters, stacked details. Extensions are under Settings, like upstream's app. Wider screens and TVs get the top bar. | Top bar. |
| Player controls | Touch: tap shows or hides controls, double-tap left or right seeks, hold for 2x, big centre buttons. TV remote: arrows seek while the controls are hidden and move between buttons while shown; OK plays or pauses; media keys work. | Mouse and keyboard. |
| TV home screen | Continue watching appears as a "Hyper Stream" row on Google TV and Android TV, and in Android's Watch Next list (`WatchNext.kt`). Cards show the picture of where you stopped and open the player there. Google TV only shows Watch Next from apps it has verified, so the row is what shows there. | None. |
| Data | The app's private files folder. Downloads go to the app's Movies folder (`Android/data/com.lagradost.cloudstream4.app/files/Movies`). | `~/Library/Application Support/CloudStream`, `~/Movies/CloudStream`. |

## Not done yet

- Seek bar thumbnails (the Mac app uses a second VLC player).
- Parallel connections while streaming: ExoPlayer fetches over one connection.
- Choosing a download folder.
- Extensions that use app classes beyond `Plugin`, `CommonActivity` and `RepositoryManager` fail to load, with the missing class named on the Extensions screen.
- Picture-in-picture, Chromecast, account sync.
