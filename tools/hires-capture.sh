#!/usr/bin/env bash
set -euo pipefail
mkdir -p capture
adb shell wm size 1080x2400
adb shell wm density 480
adb install -g android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.latintzar.hermescaller/.MainActivity
sleep 10
adb shell pidof dev.latintzar.hermescaller
adb exec-out screencap -p > capture/setup.png
# The app's native bottom navigation opens its dialer; screen coordinates are for this viewport.
adb shell input tap 270 2280
sleep 2
adb exec-out screencap -p > capture/dialer.png
adb shell uiautomator dump /sdcard/window.xml >/dev/null
adb pull /sdcard/window.xml capture/window.xml
