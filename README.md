<p align="center"><img src="branding/hyperstream-icon.svg" alt="Hyper Stream" width="112"/></p>

<h1 align="center">Hyper Stream</h1>

<p align="center">A media center for Mac, Windows, Linux, Android phones and tablets, and Android TV, built on <a href="https://github.com/recloudstream/cloudstream">CloudStream</a>.</p>

**⚠️ Hyper Stream doesn't come with any video sources. You add them by installing CloudStream extensions.**

Hyper Stream is one Compose Multiplatform app that runs on desktop and Android. It loads the same
`.cs3` extensions as CloudStream's Android app, with a new interface made for the mouse, touch and
TV remotes.

## Download

Get the latest builds from [Releases](https://github.com/KamranAllahYar/hyper-stream/releases/latest):

| Platform | File | Notes |
| --- | --- | --- |
| macOS, Apple silicon | `-macos-apple-silicon.dmg` | Needs [VLC](https://www.videolan.org/vlc/). The app isn't notarized: the first time, right-click it and choose Open. |
| macOS, Intel | `-macos-intel.dmg` | Same as above. |
| Windows | `.msi`, or the portable `.zip` | Needs VLC. |
| Linux | `.deb`, or the portable `.tar.gz` | Needs VLC. |
| Android phones, tablets and TV | `-android-and-tv.apk` | One APK for all of them. Allow installs from unknown sources. |

## Features

- **No ads, no analytics.** Some features look titles up online; see [Online services](#online-services).
- **Extensions:** add repositories and install extensions as in CloudStream. On Android they load
  as they are; on desktop they're converted to run on the JVM.
- **Player:** VLC on desktop, ExoPlayer on Android, with subtitles, audio tracks, quality and
  source choice, playback speed, and aspect ratio, crop and zoom.
- **Touch and TV controls:** double-tap to seek, hold for 2x, swipe for brightness and volume on
  phones; full remote navigation on TVs.
- **Downloads** for watching offline.
- **In-app updates:** Settings → Updates checks GitHub for the release built for your platform, downloads it and opens its installer. Automatic checks are on by default.
- **Library and progress:** bookmarks, watch status, Continue watching, and the source each title
  last played from.
- **Google TV home screen:** Continue watching shows as a Hyper Stream row, and in Android's Watch
  Next list.
- **Optional sync:** sign in to keep progress, library, settings and extensions the same on every
  device, through a small Supabase project ([backend/supabase](backend/supabase/README.md)).
  Repositories added on another device are only added here once you agree, since extensions
  run code.
  Sign in with an email link, a password, or (on a TV) a code shown on your phone or Mac.
  Everything works without it.

## Online services

Besides the extensions you install (which talk to their own sites), Hyper Stream contacts:

| Service | What it gets | When | Turn off |
| --- | --- | --- | --- |
| AniSkip, IntroDB, TheIntroDB, and AniList or Kitsu to find an anime's id | The title's name or IMDb/MyAnimeList id, season and episode number | Playing an episode, to find intro, recap and credits times | Settings, Player, Show skip intro buttons |
| IMDb | The title's name or IMDb id | Opening a title's page, for ratings and reviews | Not separately |
| GitHub | Your app version | Checking for updates, about every six hours | Settings, Updates |
| The sync server (Supabase) | Your email, and your progress, library, settings, searches and repositories | Only when signed in to sync | Sign out |

None of them get anything that identifies you, beyond your IP address, unless you sign in to sync.

## Build from source

Needs JDK 17 and the Android SDK.

```bash
./gradlew :desktopApp:run
```

```bash
./gradlew :androidApp:assembleRelease
```

```bash
./gradlew :appCore:jvmTest
```

More detail:
- [Mac and other desktops](desktopApp/MACOS.md): packaging, debugging, how extensions are converted.
- [Android, tablets and TV](androidApp/ANDROID.md): what differs from the desktop app, and what isn't done yet.
- [Sync backend](backend/supabase/README.md): setting up your own Supabase project.

Pushes to `master` build every platform on GitHub Actions, as downloadable artifacts. The jobs run on
[Blacksmith](https://www.blacksmith.sh) runners, so the Blacksmith GitHub App must be installed on the repository; the Intel Mac
app is built on an Apple silicon runner with an Intel JDK under Rosetta. Releases are
built on a Mac and published with `scripts/release.sh` (see `scripts/release.sh --help`), without
GitHub Actions: it makes the macOS apps and the Android APK, bumps the version, tags it and
publishes the release. Windows and Linux builds come from GitHub Actions only: the tag's workflow
adds whatever platforms the Mac didn't build, after the tests pass.

**Signing desktop updates.** The in-app updater can refuse desktop files that aren't signed with
the project's own key. To turn that on, once:
1. `java scripts/SignRelease.java generate` prints a key pair.
2. Save the private key as the GitHub secret `HS_UPDATE_SIGNING_KEY`, and set the same
   environment variable on the Mac that runs `release.sh`. Keep it nowhere else.
3. Put the public key in `UpdateSignature.PUBLIC_KEY` (`appCore/.../update/UpdateSignature.kt`).

From then on releases are signed, a release without the key fails instead of going out
unsigned, and apps with the public key install only signed updates.

### Project layout

| Module | What it is |
| --- | --- |
| `appCore` | The shared app: Compose UI, player, extensions, library, downloads and sync. `jvmMain` is desktop, `androidMain` is Android. |
| `desktopApp` | The desktop entry point and installers. |
| `androidApp` | The Android and Android TV app (`com.lagradost.cloudstream4.app`). |
| `library` | CloudStream's extension API, shared with upstream. |
| `app` | CloudStream's original Android app, kept from upstream. |
| `backend/supabase` | The optional sync database and sign-in function. |

## Built on CloudStream

Hyper Stream wouldn't exist without [**CloudStream**](https://github.com/recloudstream/cloudstream)
and the [reCloudStream](https://github.com/recloudstream) team and contributors. This repository is
a fork of their work: the extension API in `library`, the extension format, the providers' code
and much of the app's logic are theirs, and the many extensions Hyper Stream plays from are written
by CloudStream's extension developers. Hyper Stream adds the cross-platform app on top.

Hyper Stream is an independent project, not made or endorsed by the CloudStream team. Please don't
report Hyper Stream problems to them; open an issue here instead.

If you find Hyper Stream useful, please support the original project too:
- ⭐ Star [CloudStream on GitHub](https://github.com/recloudstream/cloudstream).
- Contribute code, or help translate it on [Weblate](https://hosted.weblate.org/engage/cloudstream/).
- Write extensions, following the [extension developer guide](https://recloudstream.github.io/csdocs/devs/gettingstarted/).
- Read the [CloudStream documentation](https://recloudstream.github.io/csdocs/) and join their
  [Discord](https://discord.gg/5Hus6fM).

CloudStream's own Android app is at
[recloudstream/cloudstream](https://github.com/recloudstream/cloudstream/releases).

## Copyright and content

Hyper Stream, like CloudStream, hosts no media. **Please don't make illegal extensions, or use any
that host copyrighted media.** Extensions and repositories are made by third parties and aren't
checked or endorsed here. For CloudStream's stance on the DMCA and EUCD, see
[reCloudStream](https://github.com/recloudstream).

## License

Hyper Stream is free software under the [GNU General Public License v3.0](LICENSE), the same
license as CloudStream. You can use, change and share it under the same terms, and the git
history keeps the CloudStream contributors' authorship of their code.
