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
WAIT="${CAPTURE_WAIT:-25}"          # minimalus laukimas po paleidimo, s
MAX_WAIT="${CAPTURE_MAX_WAIT:-180}" # ilgiausias laukimas, kol geoidas įsikraus, s

mkdir -p "$OUT"

# Laukia, kol ekrane nebeliks „Geoidas kraunamas...“ (tikrinama per uiautomator,
# MainActivity.kt nekeičiama). Rezultatas – $OUT/geoid.txt ir eilutė žurnale.
wait_geoid() {
  local t0=$1 now status="" first=""
  while :; do
    now=$(date +%s)
    if adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 \
       && adb exec-out cat /sdcard/ui.xml > "$OUT/ui.xml" 2>/dev/null; then
      if grep -q "Geoido unduliacija" "$OUT/ui.xml"; then status="įkrautas"
      elif grep -q "Klaida: nepavyko įkelti" "$OUT/ui.xml"; then status="klaida"
      elif grep -q "už pasirinkto geoido ribų" "$OUT/ui.xml"; then status="įkrautas (taškas už ribų)"
      fi
      [ -n "$status" ] && [ -z "$first" ] && first=$((now - t0))
    fi
    if [ -n "$status" ] && [ $((now - t0)) -ge "$WAIT" ]; then break; fi
    if [ $((now - t0)) -ge "$MAX_WAIT" ]; then status="${status:-vis dar kraunamas}"; break; fi
    sleep 5
  done
  echo "Geoid: $status (pirmą kartą matyta po ${first:--} s, nuotrauka po $((now - t0)) s)" | tee "$OUT/geoid.txt"
  grep -o 'text="[^"]*\(Platuma\|Ilguma\|Geoid\|unduliacija\|Aukštis virš\|Klaida\)[^"]*"' "$OUT/ui.xml" 2>/dev/null \
    | sed 's/&#10;/\n/g' | tee -a "$OUT/geoid.txt" || true
}
command -v ffmpeg >/dev/null || { sudo apt-get update -qq && sudo apt-get install -y -qq ffmpeg; }

adb install -r -g "$APK"
adb shell settings put secure location_mode 3 || true
# Vieta – Vilnius (adb emu geo fix <ilguma> <platuma>)
adb emu geo fix 25.2545 54.7400

case "$MODE" in
  shot)
    T0=$(date +%s)
    adb shell am start -W -n "$PKG/.MainActivity"
    wait_geoid "$T0"
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
grep -E " (I|W|E) (Geoid|GnssApp|Filament) *:" "$OUT/logcat.txt" | tee -a "$OUT/geoid.txt" || true
ls -la "$OUT"
grep -E "FATAL EXCEPTION|AndroidRuntime: |Filament|filament" "$OUT/logcat.txt" | grep -E " $(adb shell pidof lt.example.gnss 2>/dev/null | tr -d '\r') |FATAL|AndroidRuntime" | head -40 || true
