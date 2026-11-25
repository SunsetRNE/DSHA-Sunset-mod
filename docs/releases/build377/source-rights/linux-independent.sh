#!/bin/bash
set -eu
destination=$1
work=$(mktemp -d /tmp/dsha-proot-377-rebuild.XXXXXXXX)
python3 -m zipfile -e "$destination/dsha-proot-talloc-source.zip" "$work/src"
cd "$work/src"
python3 -B build.py --ndk /root/.dsha-nov19/android-ndk-r26d --output "$work/rebuilt"
mkdir -p "$destination/linux-independent"
cp "$work"/rebuilt/*.so "$destination/linux-independent/"
cd "$work/rebuilt"
sha256sum -c "$work/src/SHA256SUMS"
