#!/usr/bin/env bash
# Paleidžia programą emuliatoriuje ir išsaugo vaizdą peržiūrai.
# Naudojimas: tools/capture.sh [shot|video] [išvesties_katalogas]
#   shot  – screens/shot.png, 540 px pločio (numatytasis)
#   video – screens/sheet.png, 6 kadrų lapas (fps=1, tile=6x1, scale=360:-1)
set -euo pipefail

MODE="${1:-shot}"
OUT="${2:-screens}"
PKG="lt.example.gnss"
APK="app/build/outputs/apk/debug/app-debug.apk"
WAIT="${CAPTURE_WAIT:-15}"

mkdir -p "$OUT"
command -v ffmpeg >/dev/null || { sudo apt-get update -qq && sudo apt-get install -y -qq ffmpeg; }

adb install -r -g "$APK"
adb shell settings put secure location_mode 3 || true

case "$MODE" in
  shot)
    adb shell am start -W -n "$PKG/.MainActivity"
    sleep "$WAIT"
    adb exec-out screencap -p > "$OUT/raw.png"
    ffmpeg -y -loglevel error -i "$OUT/raw.png" -vf "scale=540:-1" "$OUT/shot.png"
    rm -f "$OUT/raw.png"
    ;;
  video)
    adb shell screenrecord --time-limit 8 /sdcard/rec.mp4 &
    REC=$!
    sleep 1
    adb shell am start -W -n "$PKG/.MainActivity"
    wait "$REC" || true
    sleep 1
    adb pull /sdcard/rec.mp4 "$OUT/rec.mp4"
    ffmpeg -y -loglevel error -i "$OUT/rec.mp4" \
      -vf "fps=1,scale=360:-1,tile=6x1" -frames:v 1 "$OUT/sheet.png"
    ;;
  *)
    echo "Nežinomas režimas: $MODE (galimi: shot, video)" >&2
    exit 2
    ;;
esac

adb logcat -d > "$OUT/logcat.txt" || true
ls -la "$OUT"
