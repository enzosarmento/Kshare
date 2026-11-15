# FileShare — Transferência de Arquivos Multiplataforma em Rede Local (FSAP/1.0)

> **Status Atual do Projeto:** **Milestone M6 — Concluída (Projeto Finalizado)** (`CURRENT_MILESTONE = "M6 - Concluida"`).  
> **Progresso do Backlog:** **`47 / 47 tasks concluídas` (`DONE`)** — Todas as **Milestones M0, M1, M2, M3, M4, M5 e M6** implementadas e validadas ([`tasks/backlog.md`](tasks/backlog.md) e [`docs/academic-foundation.md`](docs/academic-foundation.md)).

---

## 1. Sobre o Projeto

O **FileShare** é uma aplicação multiplataforma inspirada no *LocalSend* para transferência direta de arquivos entre computadores **Desktop (Linux e Windows)** e dispositivos móveis **Android (smartphones e tablets)** conectados à mesma rede local (LAN/WLAN), sem depender de servidores centrais na internet ou armazenamento em nuvem.

### Objetivos Acadêmicos (Redes de Computadores)
- Projeto e especificação formal de um protocolo próprio da camada de aplicação: **FSAP/1.0 (FileShare Application Protocol)**;
- Uso complementar de **UDP Multicast** (`239.255.70.83:53318`) para descoberta automática e anúncio periódico na LAN, e **HTTP/1.1 sobre TCP** (porta padrão `53317`) para controle confiável de sessão e streaming de dados binários;
- Transferência em blocos de **64 KB** (memória constante $O(1)$) com escrita temporária em arquivos `.part` e promoção atômica somente após verificação criptográfica de integridade via **SHA-256**;
- Suporte a conexões simultâneas, confirmação explícita do destinatário, cancelamento bilateral e tratamento padronizado de erros (`FSAP-ERR-*`);
- Separação estrita entre a interface gráfica Compose e o serviço de rede, incluindo um **Servidor Desktop executável de forma independente (Headless/CLI)**.

---

## 2. Estado de Implementação por Milestone

| Milestone | Escopo Principal | Status | Tasks |
| :--- | :--- | :---: | :---: |
| **M0** | Planejamento, Arquitetura, Especificação `FSAP/1.0`, ADRs e Estrutura KMP | **Concluída** | `3 / 3` |
| **M1** | Núcleo `FSAP/1.0` (`commonMain`), Sanitização, `ServerMainKt` e Handshake `IDENTIFY` | **Concluída** | `11 / 11` |
| **M2** | Transferência Desktop ↔ Desktop (Ktor HTTP, streaming 64 KB, `.part`, SHA-256, UI Desktop, `TC-FSAP-006`..`025`) | **Concluída** | `16 / 16` |
| **M3** | Descoberta Automática via UDP Multicast (`ANNOUNCE`, `DISCOVER`, `BYE`), `PeerRegistry` com TTL `18 s` e `TC-FSAP-001`..`005` | **Concluída** | `6 / 6` |
| **M4** | Integração Android (`MulticastLock`, SAF / `MediaStore.Downloads`, `FsapForegroundService`) | **Concluída** | `3 / 3` |
| **M5** | Transferência Desktop ↔ Android (UI Compose Android e interoperabilidade em LAN real) | **Concluída** | `3 / 3` |
| **M6** | Hardening (timeout de stream `15 s`, limites anti-DoS, TLS opcional), estresse (`TC-FSAP-026`) e relatório final | **Concluída** | `5 / 5` |

### O que está funcional (M0 a M6)
- **Descoberta Automática e Conexão Manual:** anúncio periódico (`ANNOUNCE`), busca ativa (`DISCOVER`), despedida graciosa (`BYE`), filtro de interfaces loopback/virtuais, gerenciamento de pares `ONLINE`/`STALE`/expiração TTL (`18 s`), `WifiManager.MulticastLock` no Android e conexão manual por IP:Porta (`IDENTIFY`);
- **Transferência Multiplataforma nas 4 Direções (Desktop ↔ Android, Desktop ↔ Desktop e Android ↔ Android):** GUI Compose Multiplatform no Desktop e Android + Servidor Desktop Headless/CLI (`ServerMainKt`), seleção nativa de arquivos (Desktop e Android SAF `OpenMultipleDocuments`), `FsapForegroundService` (`dataSync`) no Android, gravação segura em `Downloads` (`DesktopNioFileStorage` / `MediaStore.Downloads` + cache privado), diálogo de aprovação explícita (`60 s`), progresso em tempo real e cancelamento bilateral (incluindo ação pela notificação Android);
- **Integridade, Resiliência e Endurecimento Anti-DoS:** streaming constante em chunks de `64 KB`, hash incremental `SHA-256`, `.part` atômico com não-sobrescrita, `FileNameSanitizer` anti-*Path Traversal*, `transferToken` efêmero, timeout de estagnação de stream (`15 s`), recuperação de desconexão TCP abrupta e encerramento gracioso (`2000 ms`), limite de payload JSON (`256 KB`), reserva concorrente de espaço em disco (`ENOSPC` → `HTTP 507`) e controle de sessões simultâneas (`BUSY` acima da cota);
- **Transporte TLS Opcional (`--tls`) e Verificação TOFU:** certificado autoassinado X.509 RSA-2048 efêmero por dispositivo (`PlatformTlsProvider`), troca e conferência de `fingerprint` SHA-256 (*Trust On First Use* + sufixo visual de 8 caracteres hex na UI de aprovação e conexão manual);
- **Conformidade Completa (`TC-FSAP-001` a `TC-FSAP-026`):** cobertura integral automatizada em `commonTest`, `desktopTest` e `androidUnitTest`, além de scripts consolidados em `scripts/`.

---

## 3. Stack Tecnológica

- **Linguagem:** 100% **Kotlin** (`2.1.21`, target JVM `17`)
- **Arquitetura Multiplataforma:** **Kotlin Multiplatform (KMP)** com source sets `commonMain`, `jvmCommonMain`, `desktopMain` e `androidMain`
- **Interface Gráfica:** **Compose Multiplatform (`1.7.3`)** (Material 3)
- **Concorrência:** **Kotlin Coroutines (`1.10.1`)** (`StateFlow`, `Mutex`, `SupervisorJob`)
- **Rede e Transporte:** **Ktor Server & Client `3.0.3` (`CIO`)** para HTTP/TCP + `java.net.MulticastSocket` / `NetworkInterface` para UDP Multicast
- **Serialização e Logs:** **Kotlinx Serialization (`1.8.0` JSON)** + **Logback (`1.5.16`)** no Desktop
- **Android & Build:** **AGP `8.7.3`** (`minSdk 26`, `targetSdk 35`, `compileSdk 35`) e **Gradle (Kotlin DSL)** com Version Catalog ([`gradle/libs.versions.toml`](gradle/libs.versions.toml))

---

## 4. Mapa da Documentação (`docs/`)

| Documento | Descrição e Conteúdo |
| :--- | :--- |
| [**`docs/academic-foundation.md`**](docs/academic-foundation.md) | Fundamentação acadêmica: problema, objetivos, comparativo TCP vs. UDP, requisitos funcionais (`RF-01` a `RF-16`) e não funcionais (`RNF-01` a `RNF-10`). |
| [**`docs/architecture.md`**](docs/architecture.md) | Arquitetura em camadas, separação entre Cliente GUI Desktop e Servidor Desktop Independente, arquitetura Android e organização de pacotes. |
| [**`docs/protocol-fsap.md`**](docs/protocol-fsap.md) | **Especificação Oficial do Protocolo FSAP/1.0:** transportes, identificação (`DeviceIdentity`), temporizadores normativos, eventos e rastreabilidade. |
| [**`docs/state-machine.md`**](docs/state-machine.md) | **Máquinas de Estados Formais:** diagramas Mermaid e tabelas de transição para `LocalNodeState`, `PeerConnectionState` e `TransferState`. |
| [**`docs/protocol-messages.md`**](docs/protocol-messages.md) | **Catálogo de Mensagens do FSAP/1.0:** contratos campo a campo e payloads válidos/inválidos das 12 mensagens do protocolo. |
| [**`docs/communication-flows.md`**](docs/communication-flows.md) | **Fluxos de Comunicação:** diagramas de sequência Mermaid cobrindo descoberta, transferência, recusa, cancelamento, erros e encerramento gracioso. |
| [**`docs/discovery.md`**](docs/discovery.md) | Funcionamento detalhado do UDP Multicast (`239.255.70.83:53318`), filtragem de interfaces, `PeerRegistry`, TTL (`18 s`) e fallback `IDENTIFY`. |
| [**`docs/transfer-flow.md`**](docs/transfer-flow.md) | Engenharia de streaming binário em chunks de `64 KB`, cálculo incremental de SHA-256, arquivos temporários `.part` e controle de concorrência. |
| [**`docs/security.md`**](docs/security.md) | Modelo de segurança na LAN, prevenção de *Path Traversal* (`FileNameSanitizer`), autorização por `transferToken` e proteção contra exaustão de recursos. |
| [**`docs/error-codes.md`**](docs/error-codes.md) | Catálogo padronizado dos 17 códigos de erro `FSAP-ERR-*`, status HTTP equivalentes e ações de recuperação. |
| [**`docs/protocol-test-cases.md`**](docs/protocol-test-cases.md) | Especificação dos 26 casos de teste formais de conformidade (`TC-FSAP-001` a `TC-FSAP-026`). |
| [**`docs/testing.md`**](docs/testing.md) | Estratégia de testes em 3 níveis (`commonTest`, `desktopTest` e interoperabilidade em LAN). |
| [**`docs/decisions/`**](docs/decisions/README.md) | Architecture Decision Records (`ADR-001` a `ADR-006`). |

---

## 5. Planejamento, Backlog e Colaboração (`tasks/` e `CONTRIBUTING.md`)

- [**`tasks/README.md`**](tasks/README.md): Visão geral da organização do backlog e distribuição por domínio.
- [**`tasks/backlog.md`**](tasks/backlog.md): Especificação detalhada das **47 tasks** em 10 domínios funcionais (atualmente **36 `DONE`**, **11 `TODO`**).
- [**`tasks/milestones.md`**](tasks/milestones.md): Planejamento das Milestones **M0 a M6** e critérios objetivos de conclusão.
- [**`tasks/dependency-graph.md`**](tasks/dependency-graph.md): Grafo de dependências em Mermaid, caminho crítico e trilhas paralelas de desenvolvimento.
- [**`CONTRIBUTING.md`**](CONTRIBUTING.md): Convenções de código, estratégia de branches (`main`, `develop`, `feature/*`), padrão de commits, revisão e *Definition of Done*.
- [**`AGENTS.md`**](AGENTS.md): Diretrizes arquiteturais e de protocolo para agentes de desenvolvimento assistido.

---

## 6. Estrutura do Repositório

```text
fileshare/
├── README.md
├── CONTRIBUTING.md
├── AGENTS.md
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradlew / gradlew.bat
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
├── docs/                                # Especificação FSAP/1.0, arquitetura, fluxos, testes e ADRs
├── tasks/                               # Backlog (47 tasks), milestones (M0-M6) e grafo de dependências
├── scripts/
│   ├── check-structure.sh               # Validação dos artefatos documentais e de configuração
│   ├── run-desktop-server.sh            # Inicia o Servidor Desktop Independente (Gradle ou JAR)
│   └── run-desktop-ui.sh                # Inicia a interface gráfica Desktop Compose
├── composeApp/
│   ├── build.gradle.kts
│   └── src/
│       ├── commonMain/kotlin/br/edu/fileshare/
│       │   ├── ProjectInfo.kt
│       │   ├── core/                    # DI, logging, relógio, UUID e locks multiplataforma
│       │   ├── protocol/                # Contratos FSAP/1.0, validadores, erros e máquinas de estado
│       │   ├── network/                 # Descoberta UDP, cliente/servidor HTTP Ktor, streaming e transferência
│       │   ├── security/tls/            # Contrato TLS multiplataforma e capacidades de fingerprint SHA-256
│       │   ├── storage/                 # Portas FileSource/FileSink, cota de disco e FileNameSanitizer
│       │   └── ui/                      # App Compose, tema, ViewModels e telas/diálogos reativos
│       ├── jvmCommonMain/kotlin/br/edu/fileshare/
│       │   ├── core/                    # Implementações JVM (Clock, UUID, PlatformLock)
│       │   ├── network/                 # MulticastSocket, NetworkInterface, SHA-256 e tokens JVM
│       │   └── security/tls/            # PlatformTlsProvider JVM/Android (certificado X.509 RSA-2048)
│       ├── desktopMain/
│       │   ├── kotlin/br/edu/fileshare/desktop/
│       │   │   ├── Main.kt              # Entrypoint da GUI Desktop (Compose)
│       │   │   ├── runtime/             # Runtime desacoplado do cliente/servidor Desktop
│       │   │   ├── server/              # Entrypoint CLI ServerMainKt, parser CLI (--tls) e aprovação em console
│       │   │   ├── storage/             # Seletor nativo e armazenamento java.nio (DesktopNioFileStorage)
│       │   │   ├── di/                  # Container de dependências do servidor headless
│       │   │   └── logging/             # Slf4jFsapLogger
│       │   └── resources/logback.xml
│       ├── androidMain/
│       │   ├── AndroidManifest.xml
│       │   └── kotlin/br/edu/fileshare/android/
│       │       ├── MainActivity.kt      # Entrypoint da UI Compose e seletores SAF / permissões
│       │       ├── di/                  # AndroidAppContainer (runtime, logger e Storage)
│       │       ├── network/             # AndroidMulticastLockManager (WifiManager.MulticastLock)
│       │       ├── runtime/             # AndroidFileShareRuntime integrado ao ForegroundService
│       │       ├── service/             # FsapForegroundService (dataSync, notificações e cancelamento)
│       │       └── storage/             # AndroidSafFilePicker / FileSink (cacheDir + MediaStore.Downloads)
│       ├── commonTest/                  # Testes unitários de protocolo, estados, UI e conformidade UDP (TC-FSAP-001..005)
│       ├── desktopTest/                 # Rede, servidor/cliente, anti-DoS, TLS e conformidade TC-FSAP-006..026
│       └── androidUnitTest/             # Testes Android (SAF, MediaStore, MulticastLock, Service e E2E)
└── .agent/skills/                       # Skills especializadas do projeto FileShare
```

---

## 7. Como Executar, Empacotar e Testar

### Pré-requisitos
- **JDK 17+** configurada (`JAVA_HOME` apontando para JDK 17 ou superior);
- **Android SDK (`compileSdk 35`)** configurado via `ANDROID_HOME` ou `local.properties` apenas para compilar/testar os alvos Android.

### Comandos Principais

1. **Validar a estrutura documental e do repositório:**
   ```bash
   ./scripts/check-structure.sh
   ```

2. **Executar o Servidor Desktop Independente (Headless/CLI):**
   ```bash
   ./gradlew :composeApp:runDesktopServer --args="--port 53317 --alias Servidor-LAN"
   # ou via script auxiliar:
   ./scripts/run-desktop-server.sh --port 53317 --alias Servidor-LAN
   ```
   Para gerar e executar o JAR executável independente (`composeApp/build/libs/fileshare-server-all.jar`):
   ```bash
   ./scripts/run-desktop-server.sh --jar --port 53317
   # equivalente manual:
   ./gradlew :composeApp:packageDesktopServerJar
   java -jar composeApp/build/libs/fileshare-server-all.jar --help
   ```
   Opções CLI suportadas pelo `ServerMainKt`: `--port <1..65535>`, `--alias <nome>`, `--download-dir <caminho>`, `--tls`, `--auto-accept-test`, `-h` / `--help`.

3. **Executar o Aplicativo Desktop (Compose Multiplatform GUI):**
   ```bash
   ./gradlew :composeApp:run
   # ou via script auxiliar:
   ./scripts/run-desktop-ui.sh
   ```

4. **Executar os testes automatizados Desktop e de conformidade FSAP (`commonTest` + `desktopTest`):**
   ```bash
   ./gradlew :composeApp:desktopTest
   ```
   Inclui os testes unitários do núcleo, limites anti-DoS, transporte TLS e toda a suíte de conformidade **`TC-FSAP-001` a `TC-FSAP-026`** (`UdpDiscoveryConformanceTest`, `HttpTcpM2ConformanceTest`, `DisconnectAndTimeoutRecoveryTest` e `ConcurrentTransfersTest`).

5. **Executar os testes unitários e gerar o APK de debug Android:**
   ```bash
   ./gradlew :composeApp:testDebugUnitTest :composeApp:assembleDebug
   ```

6. **Executar a verificação unificada do projeto (estrutura + Desktop + Android):**
   ```bash
   ./scripts/run-interop-checks.sh
   ```

---

## 8. Guia Passo a Passo de Demonstração e Avaliação em Bancada

Para reproduzir todos os cenários acadêmicos da disciplina de Redes de Computadores em ambiente local ou LAN Wi-Fi:

1. **Validação Estrutural e Suíte Automatizada Completa:**
   ```bash
   ./scripts/check-structure.sh
   ./scripts/run-interop-checks.sh
   ```
2. **Subir o Servidor Desktop Independente (Headless/CLI):**
   ```bash
   ./scripts/run-desktop-server.sh --port 53319 --alias Servidor-Lab --download-dir ./tmp-recebidos
   # Opcional com criptografia TLS e impressão de fingerprint SHA-256:
   ./scripts/run-desktop-server.sh --port 53319 --alias Servidor-TLS --download-dir ./tmp-recebidos --tls
   ```
3. **Subir a Aplicação Gráfica Desktop (Compose Multiplatform):**
   ```bash
   ./scripts/run-desktop-ui.sh
   ```
4. **Instalar e Executar no Smartphone ou Emulador Android:**
   ```bash
   ./gradlew :composeApp:assembleDebug
   adb install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
   ```
5. **Executar a Demonstração Ponta a Ponta:**
   - Confirme que Desktop GUI, Servidor Headless e Android aparecem automaticamente na lista de pares via UDP Multicast (`239.255.70.83:53318`);
   - Caso a rede Wi-Fi bloqueie multicast (*AP Isolation*), utilize **Conectar por IP** (`IDENTIFY`) informando `IP:53319` ou `IP:53317`;
   - Realize transferências nas direções **Desktop ↔ Desktop** e **Desktop ↔ Android**, valide a aprovação explícita com sufixo do *fingerprint* SHA-256 (no modo TLS), acompanhe a barra de progresso em chunks de `64 KB`, teste um cancelamento em voo (verificando ausência de arquivos `.part`) e inspecione opcionalmente o tráfego no Wireshark com o filtro `udp.port == 53318 or tcp.port == 53317 or tcp.port == 53319`.
   - Consulte os resultados consolidados e a fundamentação completa no relatório final em [`docs/academic-foundation.md`](docs/academic-foundation.md) e no roteiro de interoperabilidade em [`docs/testing.md`](docs/testing.md).
