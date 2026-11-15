#!/usr/bin/env bash
set -euo pipefail

# Empacota os artefatos de release (APK Android e JAR do Servidor Desktop) em dist/
# Uso: ./scripts/prepare-release-assets.sh [versao]
# Exemplo: ./scripts/prepare-release-assets.sh v1.0.0

VERSION="${1:-v1.0.0}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

GRADLE_BIN="${GRADLE_BIN:-./gradlew}"
GRADLE_ARGS=("--no-daemon")
if [[ "${FILESHARE_OFFLINE:-0}" == "1" ]]; then
  GRADLE_ARGS+=("--offline")
fi

DIST_DIR="${ROOT_DIR}/dist"
APK_SRC="composeApp/build/outputs/apk/debug/composeApp-debug.apk"
JAR_SRC="composeApp/build/libs/fileshare-server-all.jar"
APK_NAME="kshare-android-${VERSION}.apk"
JAR_NAME="kshare-server-${VERSION}-all.jar"

echo "=== [1/4] Gerando APK Android (${VERSION}) ==="
"${GRADLE_BIN}" :composeApp:assembleDebug "${GRADLE_ARGS[@]}"

echo "=== [2/4] Empacotando JAR do Servidor Desktop (${VERSION}) ==="
"${GRADLE_BIN}" :composeApp:packageDesktopServerJar "${GRADLE_ARGS[@]}"

if [[ ! -f "${APK_SRC}" ]]; then
  echo "[ERRO] APK esperado nao encontrado em ${APK_SRC}" >&2
  exit 1
fi
if [[ ! -f "${JAR_SRC}" ]]; then
  echo "[ERRO] JAR esperado nao encontrado em ${JAR_SRC}" >&2
  exit 1
fi

echo "=== [3/4] Copiando artefatos para ${DIST_DIR} ==="
mkdir -p "${DIST_DIR}"
cp -f "${APK_SRC}" "${DIST_DIR}/${APK_NAME}"
cp -f "${JAR_SRC}" "${DIST_DIR}/${JAR_NAME}"

echo "=== [4/4] Gerando checksums SHA-256 ==="
(
  cd "${DIST_DIR}"
  sha256sum "${APK_NAME}" "${JAR_NAME}" > SHA256SUMS.txt
)

echo "Artefatos de release prontos em ${DIST_DIR}:"
ls -lh "${DIST_DIR}/${APK_NAME}" "${DIST_DIR}/${JAR_NAME}" "${DIST_DIR}/SHA256SUMS.txt"
echo ""
echo "Proximo passo para publicar no GitHub Releases:"
echo "  git tag ${VERSION} && git push origin ${VERSION}"

