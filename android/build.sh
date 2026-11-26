#!/usr/bin/env bash
set -euo pipefail

project=$(cd "$(dirname "$0")" && pwd)
: "${ANDROID_JAR:?Set ANDROID_JAR to Android API 29 android.jar}"
: "${ANDROID_BUILD_TOOLS:?Set ANDROID_BUILD_TOOLS to the SDK build-tools directory}"
: "${PAB_SIGNING_KEY:?Set PAB_SIGNING_KEY to a test keystore outside the repository}"
export PAB_TEST_KEY_PASSWORD="${PAB_TEST_KEY_PASSWORD:-android}"
build="$project/build"
output="$project/../out"
mkdir -p "$build/classes" "$build/dex" "$output"
find "$build/classes" -name '*.class' -delete
aapt="$ANDROID_BUILD_TOOLS/aapt2"
"$aapt" compile --dir "$project/src/main/res" -o "$build/resources.zip"
"$aapt" link -I "$ANDROID_JAR" --manifest "$project/src/main/AndroidManifest.xml" \
    -o "$build/resources.apk" "$build/resources.zip"
mapfile -t sources < <(find "$project/src/main/java" -name '*.java' -print)
if [[ -n "${ECJ_JAR:-}" ]]; then
    java -jar "$ECJ_JAR" -warn:none -source 8 -target 8 -encoding UTF-8 \
        -classpath "$ANDROID_JAR" -d "$build/classes" "${sources[@]}"
else
    javac --release 8 -encoding UTF-8 -classpath "$ANDROID_JAR" -d "$build/classes" "${sources[@]}"
fi
mapfile -t classes < <(find "$build/classes" -name '*.class' -print)
"$ANDROID_BUILD_TOOLS/d8" --lib "$ANDROID_JAR" --min-api 29 --output "$build/dex" "${classes[@]}"
cp "$build/resources.apk" "$build/unaligned.apk"
(cd "$build/dex" && zip -q "$build/unaligned.apk" classes.dex)
"$ANDROID_BUILD_TOOLS/zipalign" -f 4 "$build/unaligned.apk" "$build/aligned.apk"
if [[ ! -e "$PAB_SIGNING_KEY" ]]; then
    keytool -genkeypair -keystore "$PAB_SIGNING_KEY" -storepass:env PAB_TEST_KEY_PASSWORD \
      -alias pab-test -keyalg RSA -keysize 2048 -validity 3650 -dname 'CN=Pixel Auto Backup Test'
fi
"$ANDROID_BUILD_TOOLS/apksigner" sign --ks "$PAB_SIGNING_KEY" --ks-key-alias pab-test \
    --ks-pass env:PAB_TEST_KEY_PASSWORD --out "$output/pixel-auto-backup-android.apk" "$build/aligned.apk"
"$ANDROID_BUILD_TOOLS/apksigner" verify --min-sdk-version 29 "$output/pixel-auto-backup-android.apk"
rm -f "$output/pixel-auto-backup-android.apk.idsig"
