#!/bin/bash
# Fetch static qemu-user loaders for foreign-arch proot rootfses (#325).
#
# proot's --qemu option needs a HOST-runnable qemu-user binary; Android app
# storage is noexec and targetSdk 29+ forbids exec of downloaded files, so the
# loader must ship inside the APK as a jniLib (extracted to nativeLibraryDir,
# the one path our uid may exec from). Debian's qemu-user builds are
# static-pie — they run on Android with no linker — so we take those instead
# of carrying a qemu-against-bionic patch stack.
#
# Each APK carries only the loader(s) foreign to its own ABI:
#   arm64-v8a  gets qemu-x86_64  (run x86_64 rootfses on ARM phones)
#   x86_64     gets qemu-aarch64 (run aarch64 rootfses on x86 hosts/emulators)
#   armeabi-v7a gets none (no loader shipped; foreign-arch stays unavailable)
#
# The debs are version-pinned with sha256s. When Debian point-releases retire
# this version from the pool, the fetch fails LOUDLY — bump QEMU_DEB_VERSION
# and the sha256s together. Skip entirely (e.g. an offline / F-Droid build,
# where the APK then simply lacks foreign-arch support) with
# ./gradlew -PskipQemuLoaders or SKIP_QEMU_LOADERS=1.

set -euo pipefail
cd "$(dirname "$0")"

OUT="${QEMU_LOADER_OUTPUT:-src/main/jniLibs}"
MIRROR="${QEMU_DEB_MIRROR:-https://deb.debian.org/debian}"
VERSION="10.0.11+ds-0+deb13u1+b1"

# abi | deb arch | qemu target | deb sha256
LOADERS=(
  "arm64-v8a|arm64|x86_64|6c9483063bf60f37fe181ead911251deabe40c893ea5829bc34b0b7b88913b6a"
  "x86_64|amd64|aarch64|a52540a6b5e5af82555393d14c7c6f029c5f6c3b21c038138c0e7e64dc755e28"
)

if [ "${SKIP_QEMU_LOADERS:-0}" = "1" ]; then
    echo "fetch-qemu-loaders: SKIP_QEMU_LOADERS=1 — APK will lack foreign-arch (#325) support"
    exit 0
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

for spec in "${LOADERS[@]}"; do
    IFS='|' read -r abi debarch target sha <<< "$spec"
    dest="$OUT/$abi/libqemu_${target}.so"
    if [ -s "$dest" ]; then
        echo "fetch-qemu-loaders: $dest present — skipping"
        continue
    fi
    deb="qemu-user_${VERSION}_${debarch}.deb"
    url="$MIRROR/pool/main/q/qemu/$deb"
    echo "fetch-qemu-loaders: $url"
    curl -fsSL --retry 3 -o "$TMP/$deb" "$url" || {
        echo "fetch-qemu-loaders: FAILED to fetch $url" >&2
        echo "  If Debian retired this version, bump VERSION + sha256s here." >&2
        echo "  To build without foreign-arch support: SKIP_QEMU_LOADERS=1 or -PskipQemuLoaders." >&2
        exit 1
    }
    echo "$sha  $TMP/$deb" | sha256sum -c - >/dev/null
    dpkg-deb --fsys-tarfile "$TMP/$deb" | tar -xOf - "./usr/bin/qemu-${target}" > "$TMP/qemu-$target"
    [ -s "$TMP/qemu-$target" ] || { echo "fetch-qemu-loaders: empty qemu-$target from $deb" >&2; exit 1; }
    mkdir -p "$OUT/$abi"
    install -m 0755 "$TMP/qemu-$target" "$dest"
    echo "fetch-qemu-loaders: installed $dest ($(stat -c %s "$dest") bytes)"
done
