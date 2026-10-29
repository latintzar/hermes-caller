#!/usr/bin/env bash
set -euo pipefail
mkdir -p capture/frames
adb shell wm size 1080x1920
adb shell wm density 480
adb install -g android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n dev.latintzar.hermescaller/.MainActivity
sleep 10
adb shell pidof dev.latintzar.hermescaller
adb exec-out screencap -p > capture/setup.png
adb shell input tap 665 1200
sleep 2
adb exec-out screencap -p > capture/dialer.png
adb shell uiautomator dump /sdcard/window.xml >/dev/null
adb pull /sdcard/window.xml capture/window.xml
for i in $(seq 0 15); do
  case "$i" in
   3) adb shell input tap 200 885;;
   5) adb shell input tap 540 885;;
   7) adb shell input tap 880 885;;
   10|11|12) adb shell input tap 130 1600;;
  esac
  printf -v frame '%03d' "$i"
  adb exec-out screencap -p > "capture/frames/$frame.png"
  sleep 0.3
done
ffmpeg -y -hide_banner -loglevel error -framerate 2 -i capture/frames/%03d.png -c:v libx264 -preset veryfast -crf 23 -pix_fmt yuv420p -movflags +faststart capture/dialer-loop.mp4
