#!/usr/bin/env bash
# Prepares a signed release APK for the download server: copies it to
# <out-dir>/bellterm-<version>.apk and writes <out-dir>/latest.json, the
# manifest the app's update check reads (see UpdateManifest in Updates.kt).
#
# usage: scripts/release-manifest.sh <apk> <out-dir> [notes-en.txt] [notes-zh.txt]
#
# Needs jq and the Android build tools (aapt2, apksigner) under $ANDROID_HOME.
set -euo pipefail

if [[ $# -lt 2 ]]; then
    echo "usage: $0 <apk> <out-dir> [notes-en.txt] [notes-zh.txt]" >&2
    exit 2
fi
apk=$1
out=$2
notes_en=${3:-}
notes_zh=${4:-}
base_url=https://dl.bellterm.app

sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [[ -z $sdk ]]; then
    sdk=$(sed -n 's/^sdk\.dir=//p' "$(dirname "$0")/../local.properties" 2>/dev/null || true)
fi
tools=$(find "$sdk/build-tools" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -n 1)
if [[ -z $tools ]]; then
    echo "no Android build tools found; set ANDROID_HOME" >&2
    exit 1
fi

# Every update must carry the release key: Android refuses to install one
# signed differently over the existing app, so never publish a debug build.
if "$tools/apksigner" verify --print-certs "$apk" | grep -q 'CN=Android Debug'; then
    echo "$apk is signed with the debug key; set BELLTERM_KEYSTORE and rebuild" >&2
    exit 1
fi

badging=$("$tools/aapt2" dump badging "$apk")
version_code=$(sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" <<<"$badging" | head -n 1)
version_name=$(sed -n "s/.*versionName='\([^']*\)'.*/\1/p" <<<"$badging" | head -n 1)
min_sdk=$(sed -n "s/^minSdkVersion:'\([0-9]*\)'.*/\1/p" <<<"$badging" | head -n 1)
if [[ -z $version_code || -z $version_name || -z $min_sdk ]]; then
    echo "could not read the version from $apk" >&2
    exit 1
fi

mkdir -p "$out"
name=bellterm-$version_name.apk
cp "$apk" "$out/$name"
sha256=$(sha256sum "$out/$name" | cut -d' ' -f1)
size=$(stat -c %s "$out/$name")

read_notes() { [[ -n $1 ]] && cat "$1" || true; }
jq -n \
    --argjson versionCode "$version_code" \
    --arg versionName "$version_name" \
    --arg url "$base_url/$name" \
    --arg sha256 "$sha256" \
    --argjson size "$size" \
    --argjson minSdk "$min_sdk" \
    --arg en "$(read_notes "$notes_en")" \
    --arg zh "$(read_notes "$notes_zh")" \
    '{versionCode: $versionCode, versionName: $versionName, url: $url, sha256: $sha256,
      size: $size, minSdk: $minSdk,
      notes: ({en: $en, zh: $zh} | with_entries(select(.value != "")))}' \
    >"$out/latest.json"

echo "$out/$name"
echo "$out/latest.json"
