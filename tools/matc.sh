#!/usr/bin/env bash
# Kompiliuoja materials/*.mat į app/src/main/assets/*.filamat su matc
# (Filament versija – gradle.properties: filamentVersion; turi sutapti su priklausomybe).
set -euo pipefail
V=$(grep -E '^filamentVersion=' gradle.properties | cut -d= -f2)
DIR="${RUNNER_TEMP:-/tmp}/filament-$V"
if [ ! -x "$DIR/matc" ]; then
  mkdir -p "$DIR"
  for i in 1 2 3; do
    curl -fsSL -o "$DIR/f.tgz" "https://github.com/google/filament/releases/download/v$V/filament-v$V-linux.tgz" && break
    sleep $((i * 5))
  done
  tar -xzf "$DIR/f.tgz" -C "$DIR"
  cp "$(find "$DIR" -type f -name matc | head -1)" "$DIR/matc"
  chmod +x "$DIR/matc"
fi
mkdir -p app/src/main/assets
for m in materials/*.mat; do
  out="app/src/main/assets/$(basename "${m%.mat}").filamat"
  "$DIR/matc" -p mobile -a opengl -o "$out" "$m"
  echo "matc: $m -> $out ($(stat -c %s "$out") B)"
done
