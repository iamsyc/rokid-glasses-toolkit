#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
sdk_dir=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [ -z "$sdk_dir" ]; then
    echo 'Set ANDROID_HOME or ANDROID_SDK_ROOT to an installed Android SDK.' >&2
    exit 1
fi
tools_version=${ANDROID_BUILD_TOOLS_VERSION:-35.0.0}
build_tools="$sdk_dir/build-tools/$tools_version"
platform_jar="$sdk_dir/platforms/android-35/android.jar"
build_dir="$project_dir/build"
keystore=${ROKID_KEYSTORE:-$project_dir/local-signing.p12}
key_alias=${ROKID_KEY_ALIAS:-local}
# Development key only. Keep the keystore outside Git and preserve it for upgrades.
ROKID_KEYSTORE_PASSWORD=${ROKID_KEYSTORE_PASSWORD:-localbuild}
export ROKID_KEYSTORE_PASSWORD
for tool in aapt d8 zipalign apksigner; do
    test -x "$build_tools/$tool" || { echo "Missing Android build tool: $tool" >&2; exit 1; }
done
test -f "$platform_jar" || { echo 'Install platforms;android-35.' >&2; exit 1; }
app_version=$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$project_dir/AndroidManifest.xml")
rm -rf "$build_dir/classes" "$build_dir/dex"
mkdir -p "$build_dir/classes" "$build_dir/dex" "$project_dir/dist"
javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$platform_jar:$build_tools/core-lambda-stubs.jar" -d "$build_dir/classes" "$project_dir"/src/local/syc/rokidadb/*.java
"$build_tools/d8" --min-api 25 --lib "$platform_jar" --output "$build_dir/dex" "$build_dir"/classes/local/syc/rokidadb/*.class
"$build_tools/aapt" package -f -M "$project_dir/AndroidManifest.xml" -I "$platform_jar" -F "$build_dir/unsigned.apk"
(cd "$build_dir/dex" && "$build_tools/aapt" add "$build_dir/unsigned.apk" classes.dex)
"$build_tools/zipalign" -f 4 "$build_dir/unsigned.apk" "$build_dir/aligned.apk"
if [ ! -f "$keystore" ]; then
    (umask 077; keytool -genkeypair -keystore "$keystore" -storetype PKCS12 \
        -storepass:env ROKID_KEYSTORE_PASSWORD -keypass:env ROKID_KEYSTORE_PASSWORD \
        -alias "$key_alias" -keyalg RSA -keysize 2048 -validity 3650 \
        -dname 'CN=Rokid Helper Local Development')
fi
"$build_tools/apksigner" sign --ks "$keystore" --ks-key-alias "$key_alias" \
    --ks-pass env:ROKID_KEYSTORE_PASSWORD --key-pass env:ROKID_KEYSTORE_PASSWORD \
    --out "$project_dir/dist/RokidAdbHelper-v$app_version.apk" "$build_dir/aligned.apk"
"$build_tools/apksigner" verify --verbose --print-certs "$project_dir/dist/RokidAdbHelper-v$app_version.apk"
"$build_tools/aapt" dump badging "$project_dir/dist/RokidAdbHelper-v$app_version.apk"
