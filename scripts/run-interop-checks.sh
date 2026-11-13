#!/usr/bin/env bash
set -euo pipefail

# Executor consolidado da suite de conformidade e interoperabilidade Desktop <-> Android (TASK-TEST-005)
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

GRADLE_BIN="${GRADLE_BIN:-./gradlew}"
GRADLE_ARGS=("--no-daemon")
if [[ "${FILESHARE_OFFLINE:-0}" == "1" ]]; then
  GRADLE_ARGS+=("--offline")
fi

echo "=== [1/4] Verificacao estrutural do repositorio ==="
./scripts/check-structure.sh

echo "=== [2/4] Testes de protocolo FSAP, Desktop GUI Runtime e Servidor Headless ==="
"${GRADLE_BIN}" :composeApp:desktopTest "${GRADLE_ARGS[@]}"

echo "=== [3/4] Testes Android (SAF, MediaStore, ForegroundService, E2E) e APK Debug ==="
"${GRADLE_BIN}" :composeApp:testDebugUnitTest :composeApp:assembleDebug "${GRADLE_ARGS[@]}"

echo "=== [4/4] Resumo para execucao em LAN fisica (ADB + Servidor Desktop Independente) ==="
APK_PATH="composeApp/build/outputs/apk/debug/composeApp-debug.apk"
if [[ -f "${APK_PATH}" ]]; then
  echo "[OK] APK Debug gerado em: ${APK_PATH}"
fi
echo "Comandos sugeridos para bancada fisica em Wi-Fi:"
echo "  1) Instalar APK Android via ADB:  adb install -r ${APK_PATH}"
echo "  2) Iniciar Servidor Desktop:      ./scripts/run-desktop-server.sh --port 53319 --alias Server-Lab"
echo "  3) Iniciar Cliente Desktop UI:    ./scripts/run-desktop-ui.sh"
echo "  4) Captura opcional Wireshark:    tcp port 53317 or tcp port 53319 or udp port 53318"

