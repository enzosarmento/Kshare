---
name: git-semantic-commits
description: >-
  Use esta skill ao preparar commits, criar branches ou versionar alteracoes no
  repositorio KShare seguindo Conventional Commits, atomicidade por dominio,
  Git Flow simplificado (develop, main) e separacao de artefatos na branch ai-workspace.
---

# Skill: Git Flow & Conventional Commits (`git-semantic-commits`)

## 1. Nome
`git-semantic-commits`

## 2. Descrição
Guia normativo para versionamento no monorepo KShare, padronizando mensagens semânticas (*Conventional Commits*), atomicidade dos commits, fluxo de branches curtas integradas em `develop`, promoção para `main` e manutenção dos artefatos de IA e design na branch `ai-workspace`.

## 3. Objetivo
Manter o histórico Git do projeto legível, rastreável por domínio técnico, livre de commits monolíticos que misturam código/UI/build/documentação e alinhado às convenções de revisão em equipe definidas em `CONTRIBUTING.md`.

## 4. Conhecimentos Necessários
- **Conventional Commits** (`<tipo>(<escopo>): <descrição curta no imperativo>`).
- **Git Flow Simplificado** (`main`, `develop`, branches temáticas curtas e `git merge --no-ff`).
- Separação de responsabilidades entre código de produção (`develop`/`main`) e acervo de IA/design (`ai-workspace`).

## 5. Contexto Técnico
- Referências obrigatórias:
  - [CONTRIBUTING.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/CONTRIBUTING.md)
  - [AGENTS.md](file:///home/enzosarmento/Documents/2026.2/redes/fileshare/AGENTS.md)

### Formato de Commit Semântico Obrigatório
```text
<tipo>(<escopo>): <descrição curta no imperativo>
```
- **Tipos permitidos:**
  - `feat`: Nova funcionalidade de protocolo, rede, plataforma ou UI.
  - `fix`: Correção de bug, vazamento de recurso, corrida de threads ou falha de estado.
  - `docs`: Alterações em `docs/`, `README.md`, `CONTRIBUTING.md` ou `AGENTS.md`.
  - `test`: Criação ou atualização de testes em `commonTest`, `desktopTest` ou `androidUnitTest`.
  - `refactor`: Melhoria interna sem mudança funcional nem quebra do protocolo FSAP/1.0.
  - `build`: Ajustes em `build.gradle.kts`, `libs.versions.toml`, empacotamento nativo ou scripts de build.
  - `chore`: Tarefas auxiliares de manutenção, limpeza ou atualização do acervo em `ai-workspace`.

### Escopos Padronizados no KShare

| Escopo | Quando Usar |
| :--- | :--- |
| `protocol` | DTOs, envelopes JSON, validadores e máquinas de estado (`br.edu.fileshare.protocol.*`) |
| `discovery` | UDP Multicast `239.255.70.83:53318`, `PeerRegistry` e conexão manual |
| `server` | Rotas Ktor CIO `/fsap/v1/...`, `FsapHttpServer` e `ServerMainKt` |
| `client` | `FsapHttpClient` e negociação de sessão do remetente |
| `transfer` | Streaming binário `64 KB`, `.part`, `SHA-256` e `TransferCoordinator` |
| `security` | `FileNameSanitizer`, `TransferTokenManager`, TLS/TOFU e limites anti-DoS |
| `android` | SAF, `MediaStore`, `MulticastLock`, `FsapForegroundService` e manifest/recursos Android |
| `desktop` | Empacotamento nativo, `Main.kt` e adaptadores exclusivos de Desktop |
| `ui` | Telas Compose, Design System, ViewModels e ícones visuais compartilhados |
| `conformance` | Casos de teste `TC-FSAP-001` a `TC-FSAP-026` |
| `guide` | Guias operacionais de uso/instalação (`README.md`, `CONTRIBUTING.md`, `AGENTS.md`) |
| `ai` | Skills `.agent/skills/`, protótipos `design/`, `tasks/` e `DESIGN_GUIDELINES.md` na branch `ai-workspace` |

### Prefixos de Branches Curtas
- Sempre crie branches curtas a partir de `develop`:
  - `feature/<escopo-curto>` ou `feat/<escopo-curto>`
  - `fix/<escopo-curto>`
  - `docs/<escopo-curto>`
  - `test/<escopo-curto>`
  - `build/<escopo-curto>`

## 6. Escopo de Atuação
- Separação lógica de alterações pendentes no working tree por domínio/escopo.
- Criação de branches temáticas, commits atômicos, merges `--no-ff` em `develop` e fast-forward/integração em `main`.
- Arquivamento de skills, protótipos e assets brutos de design em `ai-workspace`.

## 7. Arquivos e Módulos que Pode Modificar
- Qualquer arquivo sob controle Git, respeitando estritamente o mapeamento de branch:
  - **`develop` e `main`:** código-fonte (`composeApp/`), build (`gradle/`, `build.gradle.kts`, `scripts/`), documentação técnica (`docs/`, `README.md`, `CONTRIBUTING.md`, `SECURITY.md`, `LICENSE`) e o arquivo guia enxuto `AGENTS.md`.
  - **`ai-workspace`:** `.agent/skills/*/SKILL.md`, `AGENTS.md` completo, `DESIGN_GUIDELINES.md`, `design/` e `tasks/`.

## 8. Restrições
1. **Atomicidade Obrigatória:** Nunca misture múltiplos domínios desconexos no mesmo commit (ex.: correção de rede Android + alteração de Gradle packaging + atualização de README devem gerar 3 commits distintos em suas respectivas branches).
2. **Isolamento entre `ai-workspace` e `develop`/`main`:**
   - Nunca faça merge do commit de limpeza de artefatos (`ec240d5`) em `ai-workspace`.
   - Nunca versione assets brutos de design (`design/`), backlog (`tasks/`) ou `.agent/skills/` em `develop` ou `main`.
3. **Sem Push Implícito:** Nunca execute `git push` sem solicitação explícita do usuário. Ao concluir os commits locais, apenas informe o comando `git push` exato para revisão e envio manual.
4. **Proibido Pular Validações:** Não commite alterações de código sem validar antes a compilação/testes do escopo alterado e `./scripts/check-structure.sh`.
5. **Descrição em Inglês ou Português Consistente:** Siga o estilo imperativo curto na linha de título (máx. ~72 caracteres), sem ponto final.

## 9. Dependências
- Conhecimento das regras de arquitetura em `AGENTS.md` e `CONTRIBUTING.md`.
- Execução prévia de `./scripts/check-structure.sh` e dos testes relevantes ao escopo modificado.

## 10. Critérios de Conclusão
1. Working tree limpa (`git status` sem modificações ou arquivos untracked residuais) em todas as branches trabalhadas.
2. Cada alteração está agrupada em seu próprio commit semântico e branch temática integrada em `develop` via `git merge --no-ff`.
3. Branch `main` sincronizada com a entrega consolidada de `develop` (quando aplicável à entrega).
4. Branch `ai-workspace` atualizada com novas skills ou assets de design sem perder `.agent/skills/`, `DESIGN_GUIDELINES.md`, `design/` ou `tasks/`.
5. Comando `git push` sugerido explicitamente ao usuário sem ter sido executado automaticamente.
