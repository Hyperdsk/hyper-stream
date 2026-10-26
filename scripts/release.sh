#!/usr/bin/env bash
# Builds Hyper Stream on this Mac and publishes the builds as a GitHub release. The version commit
# says [skip ci]; the tag does start .github/workflows/release-all.yml, which waits for the files
# this script uploads (listed in the tag's "Built locally:" line), then builds and adds only the
# platforms that are missing (Windows, Linux, and Intel Mac when this Mac can't build it). It never
# replaces these files and never publishes the release: a --draft stays a draft.
#
#   scripts/release.sh            next patch version (1.1.1 -> 1.1.2)
#   scripts/release.sh 1.2.0      a given version
#   scripts/release.sh --dry-run  build and collect the files only: no commit, tag or release
#   --skip-tests                  don't run the desktop tests first
#   --draft                       publish the release as a draft, to check before it goes out
#   --all-platforms               (no longer needed: the tag's workflow adds the other platforms)
#   --add-platforms v1.2.0        have GitHub Actions add the missing platforms to an existing release
#
# Android-only releases are made by .github/workflows/android-release.yml from a tag such as
# v1.2.1-android. This script still makes the combined desktop and Android releases.
#
# What a Mac can build:
#   macOS Apple silicon (.dmg and a portable .zip) and Android/Android TV (.apk), always.
#   macOS Intel (.dmg and .zip) too, when an Intel (x86_64) JDK 17 is installed; it runs under Rosetta.
#   Windows and Linux installers can only be built on those systems: --all-platforms gets them from
#   GitHub Actions, otherwise local releases leave them out.
#
# Needs: JDK 17, the Android SDK, gh signed in, and the Android signing key in ~/.gradle/gradle.properties
# (see androidApp/ANDROID.md), so the APK updates earlier installs. HS_UPDATE_SIGNING_KEY in the
# environment signs the Mac files for the desktop updater (see scripts/SignRelease.java).
set -euo pipefail

cd "$(dirname "$0")/.."
REPO="KamranAllahYar/hyper-stream"
# The Hyper Stream signing key's certificate; an APK signed with any other key can't update installs
SIGNING_SHA256="9bce46dbbffe5c62cc818473c28a6025c353a47847ea3a195d75394e943a36dd"

dry_run=false
skip_tests=false
draft=false
all_platforms=false
add_to=""
version=""
args=("$@")
for ((i = 0; i < ${#args[@]}; i++)); do
  arg="${args[$i]}"
  case "$arg" in
    --all-platforms) all_platforms=true ;;
    --add-platforms) i=$((i + 1)); add_to="${args[$i]:-}"; [ -n "$add_to" ] || { echo "--add-platforms needs a tag, like v1.2.0" >&2; exit 2; } ;;
    --dry-run) dry_run=true ;;
    --skip-tests) skip_tests=true ;;
    --draft) draft=true ;;
    -h|--help) awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 0 ;;
    [0-9]*.[0-9]*.[0-9]*) version="$arg" ;;
    *) echo "Unknown option: $arg (see --help)" >&2; exit 2 ;;
  esac
done

step() { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
fail() { printf '\033[1;31mError: %s\033[0m\n' "$*" >&2; exit 1; }

# Has GitHub Actions build a release's Windows, Linux and Intel Mac apps and add them to it
other_platforms() {
  local tag="$1"
  gh release view "$tag" -R "$REPO" >/dev/null 2>&1 || fail "There's no release $tag on GitHub."
  step "Asking GitHub Actions for the Windows, Linux and Intel Mac builds of $tag"
  gh workflow run desktop.yml -R "$REPO" --ref master -f release_tag="$tag"
  sleep 5
  gh run list -R "$REPO" --workflow desktop.yml --limit 1 --json url --jq '.[0].url'
  echo "They're added to the release as each finishes, in about 15 minutes."
}

if [ -n "$add_to" ]; then
  other_platforms "$add_to"
  exit 0
fi

# ---------- Checks ----------

step "Checking the repository"
[ "$(git rev-parse --abbrev-ref HEAD)" = "master" ] || fail "Switch to master first."
if ! $dry_run; then
  [ -z "$(git status --porcelain)" ] || fail "Commit or stash your changes first; the release is built from master as committed."
  git fetch -q origin master --tags
  [ "$(git rev-list --count HEAD..origin/master)" = "0" ] || fail "master is behind origin/master: pull first."
  gh auth status >/dev/null 2>&1 || fail "Sign in to GitHub with: gh auth login"
fi

last=$(git tag --list 'v*' --sort=-v:refname | grep -E '^v[0-9]+\.[0-9]+\.[0-9]+' | head -1)
if [ -z "$version" ]; then
  last_version="${last#v}"
  # An Android-only release tag is v1.2.1-android; use the same version for the next full release.
  IFS=. read -r major minor patch <<<"${last_version%%-*}"
  version="$major.$minor.$((patch + 1))"
fi
tag="v$version"
IFS=. read -r major minor patch <<<"$version"
(( major > 0 )) || fail "Installers need a version of 1.0.0 or later."
# Each release's version code is higher than the last one's, so Android installs it as an update.
# Room for 1000 minor and patch versions (the old major*10000 + minor*100 scheme ran out at 100,
# and every code from it is below these). GitHub's test builds use their run number, far below.
(( minor < 1000 && patch < 1000 )) || fail "Minor and patch versions must be below 1000."
code=$((major * 1000000 + minor * 1000 + patch))
git rev-parse -q --verify "refs/tags/$tag" >/dev/null && fail "$tag already exists."
[ -z "$(git tag --list "$tag-*")" ] || fail "$version is already used by another tag (Android-only release)."
echo "Releasing $tag (Android version code $code), last release ${last:-none}"

grep -q '^hyperstream.keystorePassword=' "$HOME/.gradle/gradle.properties" 2>/dev/null \
  || fail "The Android signing key isn't set up in ~/.gradle/gradle.properties (see androidApp/ANDROID.md)."

# ---------- Build ----------

props=(-PappVersion="$version" -PappVersionCode="$code")
if ! $skip_tests; then
  step "Running the desktop tests"
  ./gradlew :appCore:jvmTest -q
fi

step "Building the Android app"
./gradlew :androidApp:assembleRelease "${props[@]}" -q

step "Building the macOS app (Apple silicon)"
./gradlew :desktopApp:packageDmg :desktopApp:createDistributable "${props[@]}" -q

dist="dist/$version"
rm -rf "$dist"
mkdir -p "$dist"
out=desktopApp/build/compose/binaries/main

collect_mac() {
  local arch="$1"
  cp "$out"/dmg/*.dmg "$dist/HyperStream-$version-macos-$arch.dmg"
  # A portable copy that needs no installer
  ditto -c -k --keepParent "$out/app/HyperStream.app" "$dist/HyperStream-$version-macos-$arch.zip"
}
collect_mac apple-silicon
cp androidApp/build/outputs/apk/release/androidApp-release.apk "$dist/HyperStream-$version-android-and-tv.apk"

# An installed JDK 17 built for Intel. (java_home's -a flag is ignored here, so read each JDK's own release file.)
intel_jdk=""
for home in /Library/Java/JavaVirtualMachines/*/Contents/Home "$HOME"/Library/Java/JavaVirtualMachines/*/Contents/Home; do
  [ -f "$home/release" ] || continue
  if grep -q '^OS_ARCH="x86_64"' "$home/release" && grep -q '^JAVA_VERSION="17' "$home/release"; then
    intel_jdk="$home"
    break
  fi
done
if [ -n "$intel_jdk" ] && /usr/bin/pgrep -q oahd; then
  step "Building the macOS app (Intel, under Rosetta)"
  # Its own Gradle daemon on the Intel JDK, so jpackage makes an Intel app. --rerun-tasks, or the
  # Apple silicon build's outputs would count as up to date
  JAVA_HOME="$intel_jdk" arch -x86_64 ./gradlew --no-daemon :desktopApp:createRuntimeImage :desktopApp:packageDmg :desktopApp:createDistributable \
    "${props[@]}" --rerun-tasks -q
  file "$out/app/HyperStream.app/Contents/MacOS/HyperStream" | grep -q x86_64 || fail "The Intel build came out for another architecture."
  collect_mac intel
  # Leave build/ with the Apple silicon app again, for running locally
  ./gradlew :desktopApp:createDistributable "${props[@]}" -q
else
  echo "No Intel JDK 17 (or no Rosetta): skipping the Intel Mac build."
fi

step "Signing the Mac files for the desktop updater"
scripts/sign-desktop.sh "$dist"/*.dmg "$dist"/*.zip

step "Checking the APK's signature"
apksigner=$(ls -d "${ANDROID_HOME:-$HOME/Library/Android/sdk}"/build-tools/*/ | sort -V | tail -1)apksigner
cert=$("$apksigner" verify --print-certs "$dist/HyperStream-$version-android-and-tv.apk" | sed -n 's/.*SHA-256 digest: //p' | head -1)
[ "$cert" = "$SIGNING_SHA256" ] || fail "The APK is signed with another key ($cert); it wouldn't update existing installs."

echo
ls -lh "$dist"

if $dry_run; then
  step "Dry run: built $tag into $dist, nothing committed or published"
  exit 0
fi

# ---------- Publish ----------

step "Committing the version and tagging $tag"
sed -i '' -E "s/(findProperty\(\"appVersion\"\) as String\?\) \?: )\"[0-9.]+\"/\1\"$version\"/" \
  desktopApp/build.gradle.kts androidApp/build.gradle.kts appCore/build.gradle.kts
git add desktopApp/build.gradle.kts androidApp/build.gradle.kts appCore/build.gradle.kts
# [skip ci]: GitHub doesn't start workflows for this push; the builds are already made
git commit -q -m "Hyper Stream $version [skip ci]"
# The tag's workflow reads this line: it waits for these files, and builds only the other platforms
built=$(cd "$dist" && ls | paste -sd, -)
git tag -a "$tag" -m "Hyper Stream $version" -m "Built locally: $built"
git push -q origin master "$tag"

step "Publishing the release"
notes=$(mktemp)
{
  echo "## Download"
  echo
  echo "- **macOS:** \`-macos-apple-silicon.dmg\` for M-series Macs$( [ -f "$dist/HyperStream-$version-macos-intel.dmg" ] && echo ", \`-macos-intel.dmg\` for Intel Macs"). Needs [VLC](https://www.videolan.org/vlc/). The app isn't notarized: the first time, right-click it and choose Open. The \`.zip\` is the same app without an installer."
  echo "- **Android phones, tablets and TV:** \`-android-and-tv.apk\` (allow installs from unknown sources). It installs over earlier releases from 1.1.1 on."
  echo "- **Windows:** the \`.msi\` installer, or the portable \`.zip\`. **Linux:** the \`.deb\` package, or the portable \`.tar.gz\`. Both need VLC. These are added by GitHub Actions about 20 minutes after the release."
  echo
  echo "## Changes"
  echo
  git log --no-merges --format='- %s' "${last:+$last..}HEAD~1" | grep -v '\[skip ci\]' || true
} >"$notes"
gh release create "$tag" "$dist"/* -R "$REPO" --title "Hyper Stream $version" --notes-file "$notes" $($draft && echo --draft)
rm -f "$notes"
gh release view "$tag" -R "$REPO" --json url --jq .url
echo "GitHub Actions now adds the missing platforms (Windows, Linux$( [ -f "$dist/HyperStream-$version-macos-intel.dmg" ] || echo ", Intel Mac")): see the release-all workflow."
