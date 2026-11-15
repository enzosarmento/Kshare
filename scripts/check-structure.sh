#!/usr/bin/env bash
set -euo pipefail

# Script de validacao estrutural do monorepo FileShare (Milestone M0)
# Verifica se todos os artefatos de documentacao, especificacao FSAP,
# backlog, ADRs, skills e configuracao Gradle estao presentes.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

REQUIRED_FILES=(
    "README.md"
    "CONTRIBUTING.md"
    "settings.gradle.kts"
    "build.gradle.kts"
    "gradle.properties"
    "gradle/libs.versions.toml"
    "composeApp/build.gradle.kts"
    "docs/academic-foundation.md"
    "docs/architecture.md"
    "docs/protocol-fsap.md"
    "docs/protocol-messages.md"
    "docs/discovery.md"
    "docs/transfer-flow.md"
    "docs/communication-flows.md"
    "docs/state-machine.md"
    "docs/security.md"
    "docs/error-codes.md"
    "docs/protocol-test-cases.md"
    "docs/testing.md"
    "docs/decisions/README.md"
    "docs/decisions/ADR-001-adocao-kmp-e-compose-multiplatform.md"
    "docs/decisions/ADR-002-separacao-cliente-gui-e-servidor-desktop.md"
    "docs/decisions/ADR-003-arquitetura-hibrida-udp-multicast-e-http-tcp.md"
    "docs/decisions/ADR-004-escolha-ktor-vs-java-net-sockets.md"
    "docs/decisions/ADR-005-estrategia-streaming-e-integridade-sha256.md"
    "docs/decisions/ADR-006-armazenamento-e-ciclo-de-vida-android.md"
)

MISSING=0
echo "=== Validando estrutura da fundacao do projeto FileShare ==="
for rel_path in "${REQUIRED_FILES[@]}"; do
    if [[ ! -f "${ROOT_DIR}/${rel_path}" ]]; then
        echo "[ERRO] Arquivo obrigatorio ausente: ${rel_path}"
        MISSING=$((MISSING + 1))
    else
        echo "[OK]   ${rel_path}"
    fi
done

if [[ "${MISSING}" -gt 0 ]]; then
    echo "=== Falha: ${MISSING} arquivo(s) obrigatorio(s) ausente(s). ==="
    exit 1
fi

echo "=== Sucesso: Todos os ${#REQUIRED_FILES[@]} artefatos fundamentais estao presentes! ==="

