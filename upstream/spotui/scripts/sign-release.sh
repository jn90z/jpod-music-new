#!/bin/sh
# Signs the release APK so it upgrades installs of BOTH old signing keys:
# key A (1.3, 1.5) and key B (1.4, 1.6+), via APK key rotation (lineage A -> B).
# Keys and lineage live outside the repo in $SPOTUI_SIGNING (default ~/.spotui-signing).
# Usage: ./gradlew :app:assembleRelease && scripts/sign-release.sh
set -e
K="${SPOTUI_SIGNING:-$HOME/.spotui-signing}"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
BT="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
VERSION=$(sed -n 's/.*versionName = "\(.*\)"/\1/p' app/build.gradle.kts)
IN=app/build/outputs/apk/release/app-release.apk
OUT="release/spotui-$VERSION.apk"
mkdir -p release
"$BT/apksigner" sign \
  --ks "$K/keyA.keystore" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android \
  --next-signer --ks "$K/keyB.keystore" --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android \
  --lineage "$K/lineage-A-to-B.bin" --rotation-min-sdk-version 28 \
  --out "$OUT" "$IN"
# Android 8 must see key A, Android 9+ key B with the lineage; fail loudly otherwise.
"$BT/apksigner" verify --print-certs --max-sdk-version 27 "$OUT" | grep -q a91b5a4a3bf3
"$BT/apksigner" verify --print-certs --min-sdk-version 28 "$OUT" | grep -q 62857c6ecd83
echo "Signed $OUT"
