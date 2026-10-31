#!/usr/bin/env bash
# Builds the probe IME APK with the platform tools only (no Gradle, no network).
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
bt="$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)"
platform="$(ls -d "$SDK"/platforms/android-* | sort -V | tail -1)"
java_home="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.home = //p')"
ks="${HOME}/.android/debug.keystore"

[ -x "$bt/aapt2" ] || { echo "aapt2 not found under $bt" >&2; exit 1; }
[ -f "$platform/android.jar" ] || { echo "android.jar not found under $platform" >&2; exit 1; }

out="$here/build"
rm -rf "$out"
mkdir -p "$out/gen" "$out/classes" "$out/dex"

"$bt/aapt2" compile --dir "$here/res" -o "$out/res.zip"
"$bt/aapt2" link -o "$out/unsigned.apk" -I "$platform/android.jar" \
  --manifest "$here/AndroidManifest.xml" -R "$out/res.zip" \
  --java "$out/gen" --min-sdk-version 24 --target-sdk-version 35 \
  --version-code 1 --version-name 1

javac --release 11 -cp "$platform/android.jar" -d "$out/classes" \
  "$here/src/dev/probe/ime/ProbeIME.java" "$out/gen/dev/probe/ime/R.java"

"$bt/d8" --release --lib "$platform/android.jar" --min-api 24 \
  --output "$out/dex" "$out"/classes/dev/probe/ime/*.class
(cd "$out/dex" && zip -q ../unsigned.apk classes.dex)

if [ ! -f "$ks" ]; then
  keytool -genkeypair -keystore "$ks" -storepass android -keypass android \
    -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" \
    -keyalg RSA -keysize 2048 -validity 10000
fi
"$bt/apksigner" sign --ks "$ks" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey --out "$out/probe.apk" "$out/unsigned.apk"
"$bt/apksigner" verify "$out/probe.apk"

echo "built: $out/probe.apk"
echo "install: adb install --no-incremental -r -t $out/probe.apk"
echo "enable:  adb shell ime enable dev.probe.ime/.ProbeIME && adb shell ime set dev.probe.ime/.ProbeIME"
