#!/bin/bash
#
# Gradle-free build. No network, no dependencies -- just the SDK build-tools.
# Produces build/CarrierPreloadCheck.apk, signed with the debug keystore.
#
#   ./build.sh          build only
#   ./build.sh install  build, then install over adb

set -euo pipefail

SDK="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-34/android.jar"
KS="${KS:-$HOME/.android/debug.keystore}"

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="$HERE/build"
APK="$OUT/CarrierPreloadCheck.apk"

for t in aapt2 d8 zipalign apksigner; do
  [ -x "$BT/$t" ] || { echo "missing build tool: $BT/$t" >&2; exit 1; }
done
[ -f "$PLATFORM" ] || { echo "missing android.jar: $PLATFORM" >&2; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT/compiled" "$OUT/classes" "$OUT/gen"

echo "==> aapt2 compile"
"$BT/aapt2" compile --dir "$HERE/res" -o "$OUT/compiled/res.zip"

echo "==> aapt2 link"
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$PLATFORM" \
  --manifest "$HERE/AndroidManifest.xml" \
  --java "$OUT/gen" \
  --min-sdk-version 29 \
  --target-sdk-version 34 \
  "$OUT/compiled/res.zip"

echo "==> javac"
# --release 17 keeps the class file version inside d8's supported range; JDK 23
# would otherwise emit v67 bytecode that d8 rejects.
find "$HERE/src" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac --release 17 -nowarn \
  -classpath "$PLATFORM" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt"

echo "==> d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --lib "$PLATFORM" --min-api 29 --output "$OUT" @"$OUT/classes.txt"

echo "==> package dex"
(cd "$OUT" && zip -q -u base.apk classes.dex)

echo "==> zipalign + sign"
"$BT/zipalign" -f -p 4 "$OUT/base.apk" "$OUT/aligned.apk"
"$BT/apksigner" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey \
  --out "$APK" "$OUT/aligned.apk"

"$BT/apksigner" verify --print-certs "$APK" | head -2
echo "==> built $APK"

if [ "${1:-}" = "install" ]; then
  echo "==> installing"
  adb install -r "$APK"
fi
