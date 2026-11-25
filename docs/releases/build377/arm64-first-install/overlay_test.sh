#!/bin/bash
set -eu
root=/mnt/f/DSHA_RESTART
ev=$root/artifacts/build377-local/arm64-first-install
src=$root/source/build377-local/app/build/intermediates/javac
xz=$root/toolchains/current/gradle-user-home/caches/modules-2/files-2.1/org.tukaani/xz/1.12/bb9703ba3753ab8665f65e6a25b3ddc7b09b1caf/xz-1.12.jar
platform=$root/toolchains/current/android-sdk/platforms/android-37.0/android.jar
cp=$src/standardRelease/compileStandardReleaseJavaWithJavac/classes:$src/standardDebugUnitTest/compileStandardDebugUnitTestJavaWithJavac/classes:$xz:$platform
tmp=$(mktemp -d /tmp/dsha377-overlay-classes.XXXXXXXX)
metadata=$ev/overlay-replay-r2.log
exec > "$metadata" 2>&1
javac -encoding UTF-8 -cp "$cp" -d "$tmp" "$ev/ConfiguredOverlayCheck.java"
for build in build376-formal-20261125 build377-local; do
 echo "RUN $build use build377 reader"
 java -cp "$tmp:$cp" com.deepseekharness.app.runtime.ConfiguredOverlayCheck "$root/artifacts/$build/signed/dsha-0.2.2-rc.5.apk"
done
