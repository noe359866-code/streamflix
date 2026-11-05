#!/usr/bin/env bash
set -euo pipefail

# Streamflix Reborn2 - Android TV VOD - Build Debug APK
# Ejecuta ./gradlew assembleDebug y genera el APK de pruebas para Android TV (minSdk 24, targetSdk 35)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

echo "============================================"
echo " Streamflix - Compilando APK Debug (TV VOD)"
echo "============================================"
echo "Proyecto: $ROOT_DIR"
echo "Fecha: $(date -u +"%Y-%m-%dT%H:%M:%SZ")"
echo ""

if [ ! -f "./gradlew" ]; then
  echo "ERROR: gradlew no encontrado en $ROOT_DIR" >&2
  exit 1
fi

chmod +x ./gradlew

echo "[1/2] Verificando entorno Gradle..."
./gradlew --version

echo ""
echo "[2/2] Ejecutando ./gradlew assembleDebug --stacktrace..."
./gradlew assembleDebug --stacktrace

echo ""
echo "============================================"
echo " BUILD SUCCESSFUL"
echo "============================================"
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$APK_PATH" ]; then
  echo "APK generado: $APK_PATH"
  ls -lh "$APK_PATH"
else
  echo "APKs generados en app/build/outputs/apk/debug/:"
  find app/build/outputs/apk/debug -name "*.apk" -type f 2>/dev/null | xargs ls -lh 2>/dev/null || true
fi
echo ""
echo "Instalacion en Android TV:"
echo "  adb install -r $APK_PATH"
