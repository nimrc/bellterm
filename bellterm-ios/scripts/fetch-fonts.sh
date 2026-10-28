#!/usr/bin/env bash
# Gathers the terminal fonts the iOS app bundles into bellterm-ios/Generated/fonts:
# the Android app's bundled fonts, plus the nerd-fonts v3.5.1 files below,
# each kept only when its SHA-256 matches. Bundling them means users never
# download fonts; only this build step needs the network.
set -euo pipefail

root=$(cd "$(dirname "$0")/../.." && pwd)
out="$root/bellterm-ios/Generated/fonts"
base=https://raw.githubusercontent.com/ryanoasis/nerd-fonts/v3.5.1/patched-fonts

# path under patched-fonts, then its SHA-256
files=(
  "JetBrainsMono/Ligatures/JetBrainsMonoNerdFontMono-Italic.ttf 31efd6ead98746f5b0afa1ee6dba60267ad48db36428360bee327bec10621f97"
  "JetBrainsMono/Ligatures/JetBrainsMonoNerdFontMono-BoldItalic.ttf 9dba502e00e35209f6ed2a151c7376c051657b067cdebbc6e52d06cb9002cf31"
  "SourceCodePro/SauceCodeProNerdFontMono-Regular.ttf cbfe6a91123b9d78a1b593cb2efa10a806a704490e3cea6b894078b86d67c0b1"
  "SourceCodePro/SauceCodeProNerdFontMono-Bold.ttf fedd1dcdfc4228621075dc8a61190474aace7617aff61d1faca877d340a40ff6"
  "SourceCodePro/SauceCodeProNerdFontMono-Italic.ttf a7f108988ed9c38001ba6e2554c537744d75ed326b7a64be1eacc0cb695969f2"
  "SourceCodePro/SauceCodeProNerdFontMono-BoldItalic.ttf bc723ce781926ee93fbfb7585bd13e151f755ee2cdc08310e0e3200a753db8f3"
  "SourceCodePro/LICENSE.txt 37f1d07d235509973aeaff5d6f22378d8d812eb998a14d321d6562e5a4cb6a89"
  "UbuntuMono/UbuntuMonoNerdFontMono-Regular.ttf 6ac1e06816ef535f4d28f564a855032783784731ff8c7c8ec62955a08bb83932"
  "UbuntuMono/UbuntuMonoNerdFontMono-Bold.ttf 087dba61f187fc34ff7317de72b305f6c591311ab06f2c8009ad0d590d7d9ef6"
  "UbuntuMono/UbuntuMonoNerdFontMono-Italic.ttf e58637b8f4473e031eecdff18f494b727fc5b1544f3b5d53359572f6969f7f43"
  "UbuntuMono/UbuntuMonoNerdFontMono-BoldItalic.ttf b186eaaf05c6a6e065c112a1111f8e52b68d5e96cfbc4d146cc7e8b876b5da8a"
  "UbuntuMono/LICENCE.txt 2f0015108d68627bd788d313f529c21ff4da2c2c42a5e1f3883acc83480f9002"
)

mkdir -p "$out"
cp "$root"/app/src/main/assets/fonts/* "$out/"

for entry in "${files[@]}"; do
  path=${entry% *}
  sha=${entry#* }
  # Licence files share names across fonts, so prefix them with the family.
  name=$(basename "$path")
  case "$name" in *.txt) name="$(dirname "$path" | cut -d/ -f1)-$name" ;; esac
  target="$out/$name"
  if [ -f "$target" ] && [ "$(shasum -a 256 "$target" | cut -d' ' -f1)" = "$sha" ]; then
    continue
  fi
  curl -fsSL --retry 3 "$base/$path" -o "$target.part"
  actual=$(shasum -a 256 "$target.part" | cut -d' ' -f1)
  if [ "$actual" != "$sha" ]; then
    rm -f "$target.part"
    echo "checksum mismatch for $path: $actual" >&2
    exit 1
  fi
  mv "$target.part" "$target"
done
