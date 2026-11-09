#!/usr/bin/env bash
# Public upstream source rebuild. Requires Go 1.26.10, Android NDK r28 and Linux.
set -euo pipefail
cd "$(dirname "$0")/.."
root="$PWD"
test -n "${ANDROID_NDK_HOME:-}" || { echo 'Set ANDROID_NDK_HOME to Android NDK r28'; exit 1; }
go version | grep -q 'go1.26.10' || { echo 'Go 1.26.10 required'; exit 1; }
mkdir -p .native-build app/libs
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
git clone --quiet https://github.com/SagerNet/sing-box.git "$work/sing-box"
git -C "$work/sing-box" checkout --quiet c005f35300ce76427dc285481ec5695f5b179cd2
(
  cd "$work/sing-box"
  make lib_install
  export PATH="$PATH:$(go env GOPATH)/bin"
  go run ./cmd/internal/build_libbox -target android -platform android/arm64
  test -s libbox.aar
  cp libbox.aar "$root/app/libs/libbox.aar"
)
echo 'Source-built native libbox at app/libs/libbox.aar'
echo 'Build the app with ./gradlew :app:assembleDebug'
echo 'Byte-identical native builds are not guaranteed across environments.'
