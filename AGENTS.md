# Regras de Contexto para Agentes de IA — Projeto KShare

Este arquivo orienta agentes de IA e colaboradores que atuam nas branches `develop` e `main`.
Para manter estas branches enxutas, as skills especializadas, o catálogo de tasks, os protótipos do Google Stitch e os guias detalhados ficam arquivados na branch `ai-workspace`.

---

## 1. Leitura Obrigatória na Branch `ai-workspace` (Antes de Alterar Código)

Consulte os artefatos da branch `ai-workspace` sem precisar trocar de branch usando `git show`:

- **Regras completas de arquitetura e protocolo:**
  ```bash
  git show ai-workspace:AGENTS.md
  ```
- **Diretrizes permanentes de UI/UX e IHC (Nielsen, Fitts, Hick, Miller, WCAG 2.2 e regras para uso do Stitch):**
  ```bash
  git show ai-workspace:DESIGN_GUIDELINES.md
  ```
- **Skills especializadas do projeto (`ai-workspace:.agent/skills/`):**
  - Protocolo FSAP/1.0: `git show ai-workspace:.agent/skills/fsap-protocol/SKILL.md`
  - Arquitetura Kotlin Multiplatform: `git show ai-workspace:.agent/skills/kotlin-multiplatform-architecture/SKILL.md`
  - Interface Compose Multiplatform: `git show ai-workspace:.agent/skills/compose-multiplatform-ui/SKILL.md`
  - Sistema de arquivos e ciclo de vida Android (SAF, MediaStore, ForegroundService): `git show ai-workspace:.agent/skills/android-file-system/SKILL.md`
  - Transferência binária em streaming `64 KB` + SHA-256: `git show ai-workspace:.agent/skills/binary-file-transfer/SKILL.md`
  - Rede em Kotlin e sockets multiplataforma: `git show ai-workspace:.agent/skills/kotlin-networking/SKILL.md`
  - Servidor HTTP/TCP Ktor CIO: `git show ai-workspace:.agent/skills/ktor-http-server/SKILL.md`
  - Descoberta UDP Multicast (`239.255.70.83:53318`): `git show ai-workspace:.agent/skills/udp-device-discovery/SKILL.md`
  - Segurança de rede, TLS/TOFU e proteções anti-DoS: `git show ai-workspace:.agent/skills/network-security/SKILL.md`
  - Testes de integração e conformidade: `git show ai-workspace:.agent/skills/integration-testing/SKILL.md`
  - Fluxo Git, branches e commits semânticos: `git show ai-workspace:.agent/skills/git-semantic-commits/SKILL.md`
- **Protótipos visuais (Google Stitch) e histórico de tasks:**
  ```bash
  git ls-tree -r --name-only ai-workspace design/ tasks/
  ```

---

## 2. Invariantes Obrigatórias do Projeto KShare (`FSAP/1.0`)

1. **Fonte única da verdade do protocolo (`FSAP/1.0`):**
   - Antes de alterar rede, serialização, estados, transferências ou erros, consulte `docs/protocol-fsap.md`, `docs/protocol-messages.md`, `docs/state-machine.md` e `docs/error-codes.md`.
   - Não crie mensagens, campos JSON, cabeçalhos `X-FSAP-*`, códigos `FSAP-ERR-*` ou transições incompatíveis sem atualizar a documentação correspondente.
2. **Separação estrita entre UI e serviço de rede:**
   - Pacotes `br.edu.fileshare.protocol.*` e `br.edu.fileshare.network.*` **jamais** podem importar `androidx.compose.*`, `java.awt.*` ou `javax.swing.*`.
   - O servidor Desktop headless (`br.edu.fileshare.desktop.server.ServerMainKt` / `./gradlew :composeApp:runDesktopServer`) deve permanecer independente da interface gráfica.
3. **Streaming binário `O(1)` e integridade SHA-256:**
   - Leia e grave arquivos transferidos em blocos fixos de **64 KB (`65536` bytes)**; é proibido carregar payloads inteiros em memória (`readBytes()`).
   - Toda gravação ocorre em arquivo temporário `.part` com remoção garantida em `finally` em falhas/cancelamentos e promoção atômica somente após validar tamanho e SHA-256.
4. **Especificidades da plataforma Android:**
   - Seleção via Storage Access Framework (`OpenMultipleDocuments`), gravação via `MediaStore.Downloads` (`Downloads/FileShare`), execução em segundo plano via `FsapForegroundService` (`dataSync`) e `WifiManager.MulticastLock` com `setReferenceCounted(false)`.
   - Operações bloqueantes, sockets UDP/TCP e geração de certificados TLS devem executar em `Dispatchers.IO` (nunca na thread principal da UI).
   - Para apenas gerar o APK sem aparelho conectado, use `./gradlew :composeApp:assembleDebug`; `:composeApp:installDebug` requer um dispositivo ou emulador visível em `adb devices -l`.
5. **Validação obrigatória após alterações:**
   ```bash
   ./scripts/check-structure.sh
   ./gradlew :composeApp:desktopTest
   ./gradlew :composeApp:testDebugUnitTest :composeApp:assembleDebug
   ```
6. **Fluxo Git e Commits Semânticos (Conventional Commits):**
   - Consulte `git-semantic-commits` e [`CONTRIBUTING.md`](CONTRIBUTING.md) antes de versionar alterações: utilize branches curtas a partir de `develop` (`feature/...`, `fix/...`, `docs/...`, `build/...`), commits atômicos no formato `<tipo>(<escopo>): <descrição curta no imperativo>` e integração via `git merge --no-ff`.
