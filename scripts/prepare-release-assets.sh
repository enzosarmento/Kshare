#!/usr/bin/env bash
set -euo pipefail

# Empacota os artefatos de release do KShare em dist/ com nomes canonicos para download direto:
# - Linux: kshare-android.apk, kshare-linux-amd64.deb e kshare-server-all.jar
# - Windows (Git Bash/MSYS): kshare-windows-x64.msi, kshare-windows-x64-portable.zip e kshare-server-all.jar
#
# Uso: ./scripts/prepare-release-assets.sh [versao] [modo: auto|linux-and-android|windows-gui|windows-all]
# Exemplo: ./scripts/prepare-release-assets.sh v1.1.0

VERSION="${1:-v1.1.0}"
MODE="${2:-auto}"

RAW_PKG_VERSION="${VERSION#v}"
RAW_PKG_VERSION="${RAW_PKG_VERSION%%-*}"
if [[ "${RAW_PKG_VERSION}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  PKG_VERSION="${RAW_PKG_VERSION}"
elif [[ "${RAW_PKG_VERSION}" =~ ^[0-9]+\.[0-9]+$ ]]; then
  PKG_VERSION="${RAW_PKG_VERSION}.0"
else
  PKG_VERSION="1.1.0"
fi

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

GRADLE_BIN="${GRADLE_BIN:-./gradlew}"
GRADLE_ARGS=("--no-daemon" "-PkshareVersion=${PKG_VERSION}")
if [[ "${FILESHARE_OFFLINE:-0}" == "1" ]]; then
  GRADLE_ARGS+=("--offline")
fi

if [[ "${MODE}" == "auto" ]]; then
  case "$(uname -s)" in
    Linux*)
      MODE="linux-and-android"
      ;;
    MINGW*|MSYS*|CYGWIN*)
      MODE="windows-all"
      ;;
    *)
      echo "[ERRO] SO nao suportado no modo auto: $(uname -s). Informe linux-and-android, windows-gui ou windows-all." >&2
      exit 1
      ;;
  esac
fi

DIST_DIR="${ROOT_DIR}/dist"
mkdir -p "${DIST_DIR}"
GENERATED_FILES=()

package_android_and_server_jar() {
  local apk_src="composeApp/build/outputs/apk/debug/composeApp-debug.apk"
  local jar_src="composeApp/build/libs/fileshare-server-all.jar"

  echo "=== Gerando APK Android (${PKG_VERSION}) ==="
  "${GRADLE_BIN}" :composeApp:assembleDebug "${GRADLE_ARGS[@]}"

  echo "=== Empacotando JAR do Servidor Desktop (${PKG_VERSION}) ==="
  "${GRADLE_BIN}" :composeApp:packageDesktopServerJar "${GRADLE_ARGS[@]}"

  if [[ ! -f "${apk_src}" ]]; then
    echo "[ERRO] APK esperado nao encontrado em ${apk_src}" >&2
    exit 1
  fi
  if [[ ! -f "${jar_src}" ]]; then
    echo "[ERRO] JAR esperado nao encontrado em ${jar_src}" >&2
    exit 1
  fi

  cp -f "${apk_src}" "${DIST_DIR}/kshare-android.apk"
  cp -f "${jar_src}" "${DIST_DIR}/kshare-server-all.jar"
  GENERATED_FILES+=("kshare-android.apk" "kshare-server-all.jar")
}

package_linux_deb() {
  local deb_dir="composeApp/build/compose/binaries/main/deb"
  rm -rf "${deb_dir}"

  echo "=== Empacotando instalador Desktop Linux .deb (${PKG_VERSION}) ==="
  "${GRADLE_BIN}" :composeApp:packageDeb "${GRADLE_ARGS[@]}"

  local deb_src=""
  deb_src="$(find "${deb_dir}" -maxdepth 1 -type f -name "*.deb" | sort | tail -n 1)"
  if [[ -z "${deb_src}" || ! -f "${deb_src}" ]]; then
    echo "[ERRO] Pacote .deb nao encontrado em ${deb_dir}" >&2
    exit 1
  fi

  cp -f "${deb_src}" "${DIST_DIR}/kshare-linux-amd64.deb"
  GENERATED_FILES+=("kshare-linux-amd64.deb")
}

package_windows_desktop() {
  local msi_dir="composeApp/build/compose/binaries/main/msi"
  local app_dir="composeApp/build/compose/binaries/main/app/KShare"
  rm -rf "${msi_dir}" "${app_dir}"

  echo "=== Empacotando instalador Desktop Windows .msi e versao portavel (${PKG_VERSION}) ==="
  "${GRADLE_BIN}" :composeApp:packageMsi :composeApp:createDistributable "${GRADLE_ARGS[@]}"

  local msi_src=""
  msi_src="$(find "${msi_dir}" -maxdepth 1 -type f -name "*.msi" | sort | tail -n 1)"
  if [[ -z "${msi_src}" || ! -f "${msi_src}" ]]; then
    echo "[ERRO] Instalador .msi nao encontrado em ${msi_dir}" >&2
    exit 1
  fi
  if [[ ! -d "${app_dir}" ]]; then
    echo "[ERRO] Diretorio portavel do app Windows nao encontrado em ${app_dir}" >&2
    exit 1
  fi

  cp -f "${msi_src}" "${DIST_DIR}/kshare-windows-x64.msi"
  rm -f "${DIST_DIR}/kshare-windows-x64-portable.zip"
  powershell.exe -NoProfile -Command \
    "Compress-Archive -Path 'composeApp\\build\\compose\\binaries\\main\\app\\KShare' -DestinationPath 'dist\\kshare-windows-x64-portable.zip' -Force"

  if [[ ! -f "${DIST_DIR}/kshare-windows-x64-portable.zip" ]]; then
    echo "[ERRO] Falha ao compactar versao portavel Windows" >&2
    exit 1
  fi

  GENERATED_FILES+=("kshare-windows-x64.msi" "kshare-windows-x64-portable.zip")
}

case "${MODE}" in
  linux-and-android)
    rm -f "${DIST_DIR}/kshare-android.apk" "${DIST_DIR}/kshare-linux-amd64.deb" "${DIST_DIR}/kshare-server-all.jar" "${DIST_DIR}/SHA256SUMS.txt"
    package_android_and_server_jar
    package_linux_deb
    ;;
  windows-gui)
    rm -f "${DIST_DIR}/kshare-windows-x64.msi" "${DIST_DIR}/kshare-windows-x64-portable.zip" "${DIST_DIR}/SHA256SUMS.txt"
    package_windows_desktop
    ;;
  windows-all)
    rm -f "${DIST_DIR}/kshare-windows-x64.msi" "${DIST_DIR}/kshare-windows-x64-portable.zip" "${DIST_DIR}/kshare-server-all.jar" "${DIST_DIR}/SHA256SUMS.txt"
    package_windows_desktop
    echo "=== Empacotando JAR do Servidor Desktop (${PKG_VERSION}) ==="
    "${GRADLE_BIN}" :composeApp:packageDesktopServerJar "${GRADLE_ARGS[@]}"
    cp -f "composeApp/build/libs/fileshare-server-all.jar" "${DIST_DIR}/kshare-server-all.jar"
    GENERATED_FILES+=("kshare-server-all.jar")
    ;;
  *)
    echo "[ERRO] Modo invalido: ${MODE}" >&2
    exit 1
    ;;
esac

echo "=== Gerando checksums SHA-256 ==="
(
  cd "${DIST_DIR}"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "${GENERATED_FILES[@]}" > SHA256SUMS.txt
  else
    shasum -a 256 "${GENERATED_FILES[@]}" > SHA256SUMS.txt
  fi
)

echo "Artefatos prontos em ${DIST_DIR}:"
ls -lh "${DIST_DIR}"
echo ""
echo "Proximo passo para publicar todos os binarios (Android + Linux .deb + Windows .msi/.zip) via GitHub Releases:"
echo "  git tag ${VERSION} && git push origin ${VERSION}"
