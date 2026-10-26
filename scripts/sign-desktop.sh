#!/usr/bin/env bash
# Signs desktop release files for the in-app updater (see scripts/SignRelease.java) when
# HS_UPDATE_SIGNING_KEY is set. Once the app checks signatures (UpdateSignature.PUBLIC_KEY is set),
# a release without them would leave desktop users unable to update, so a missing key is an error.
#   scripts/sign-desktop.sh FILE...
set -euo pipefail
cd "$(dirname "$0")/.."
checks=false
grep -q 'const val PUBLIC_KEY = ""' appCore/src/jvmCommonMain/kotlin/com/lagradost/cloudstream4/desktop/update/UpdateSignature.kt || checks=true
if [ -z "${HS_UPDATE_SIGNING_KEY:-}" ]; then
  if $checks; then
    echo "HS_UPDATE_SIGNING_KEY is not set, but the app checks update signatures: refusing to release unsigned files." >&2
    exit 1
  fi
  echo "No HS_UPDATE_SIGNING_KEY: desktop files are not signed (the app doesn't check yet)."
  exit 0
fi
java scripts/SignRelease.java sign "$@"
