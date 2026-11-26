#!/usr/bin/env bash
set -euo pipefail
if [[ $# != 8 ]]; then
    echo "Usage: $0 android.jar build-tools-dir ecj.jar base.apk directory.json android-ndk-dir wireguard-tunnel.aar output.apk"
    exit 2
fi
platform="$(realpath "$1")"
tools_dir="$(realpath "$2")"
ecj="$(realpath "$3")"
base="$(realpath "$4")"
directory="$(realpath "$5")"
ndk="$(realpath "$6")"
wireguard="$(realpath "$7")"
output="$(realpath -m "$8")"
cd -- "$(dirname -- "$0")"
expected_base=6d2d6dc8b84bd3c6628427d8dee5139eb48bd729d4a97442b441187e9899f698
printf '%s  %s\n' "$expected_base" "$base" | sha256sum -c
build="$(mktemp -d)"
trap 'rm -rf -- "$build"' EXIT
printf '%s  %s\n' 2b9c16db026496123e4db695d26d03d1958a201096c7c4c89b21077dc70f3119 "$wireguard" | sha256sum -c
unzip -p "$wireguard" classes.jar > "$build/wireguard.jar"
mkdir -p "$build/classes" "$build/dex"
mapfile -t sources < <(find ../main/src/main/java/com/paperline/vpn stubs -name '*.java' -print | sort)
java -jar "$ecj" -8 -proc:none -nowarn -classpath "$platform:$build/wireguard.jar" -d "$build/classes" "${sources[@]}"
mapfile -t classes < <(find "$build/classes/com" -name '*.class' -print | sort)
"$tools_dir/d8" --min-api 26 --lib "$platform" --classpath "$build/classes" --output "$build/dex" "${classes[@]}" "$build/wireguard.jar"
"$tools_dir/aapt2" link --manifest AndroidManifest.xml -I "$platform" -o "$build/shell.apk"
"$tools_dir/aapt2" compile --dir icon/res -o "$build/icon-res.zip"
"$tools_dir/aapt2" link --manifest icon/AndroidManifest.xml -I "$platform" -o "$build/icon.apk" "$build/icon-res.zip"
"$tools_dir/aapt2" dump xmltree --file AndroidManifest.xml "$build/shell.apk" > "$build/tree.txt"
python3 - "$build" <<'PY'
from pathlib import Path
import re,sys
p=Path(sys.argv[1])
tree=(p/'tree.txt').read_text()
icon=re.search(r':icon\(.*?\)=@(0x[0-9a-f]+)',tree).group(1)
round_icon=re.search(r':roundIcon\(.*?\)=@(0x[0-9a-f]+)',tree).group(1)
dialog=re.search(r':theme\(.*?\)=@(0x[0-9a-f]+)\n.*CredentialsPopup',tree).group(1)
(p/'framework-refs.txt').write_text(f'{icon} {round_icon} {dialog}')
PY
bash ../rust-core/build-android.sh "$ndk" "$build/jni"
python3 package_apk.py "$base" "$build/shell.apk" "$build/dex" "$directory" "$build/icon.apk" "$build/jni" "$wireguard" "$build/unsigned.apk"
"$tools_dir/zipalign" -P 16 -f 4 "$build/unsigned.apk" "$build/aligned.apk"
if [[ -z "${PAPERLINE_KEYSTORE:-}" ]]; then
    cp "$build/aligned.apk" "$output"
    echo "Built unsigned APK at $output"
    exit 0
fi
"$tools_dir/apksigner" sign --ks "$PAPERLINE_KEYSTORE" --ks-key-alias "${PAPERLINE_KEY_ALIAS:-paperline}" \
    --ks-pass env:PAPERLINE_KEYSTORE_PASSWORD --key-pass env:PAPERLINE_KEYSTORE_PASSWORD \
    --v4-signing-enabled false --out "$output" "$build/aligned.apk"
"$tools_dir/apksigner" verify --verbose "$output"
