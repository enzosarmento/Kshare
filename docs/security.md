# Arquitetura e Política de Segurança — KShare (`FSAP/1.0`)

> **Escopo:** Modelo de ameaças em rede local (LAN), controles defensivos implementados no protocolo **FSAP/1.0**, sanitização de sistema de arquivos, proteção anti-DoS, transporte TLS opcional e processo de reporte de vulnerabilidades para o projeto **KShare**.

---

## 1. Modelo de Ameaças em Rede Local (LAN)

O **KShare** opera ponto a ponto em redes locais sem servidor central (redes domésticas, escritórios ou laboratórios universitários). Nesse cenário, qualquer host conectado à mesma sub-rede pode enviar datagramas UDP para a porta `53318` ou abrir conexões TCP para a porta HTTP/HTTPS (`53317` por padrão).

As ameaças consideradas no projeto e os respectivos controles implementados são:

| Ameaça | Vetor de Ataque | Controle Implementado | Código de Erro / Resposta |
| :--- | :--- | :--- | :--- |
| **Escrita Arbitrária (*Path Traversal*)** | Cliente envia `fileName` como `"../../.bashrc"`, `"..\\cmd.exe"` ou nomes reservados Windows. | Validação estrita em `FileNameSanitizer` (`commonMain`) antes de notificar o usuário e confinamento no diretório de destino / cache privado. | `HTTP 400` (`FSAP-ERR-400-INVALID_FILENAME`) |
| **Upload Não Solicitado / Sequestro de Sessão** | Terceiro tenta enviar bytes para `/fsap/v1/transfers/{id}/files/{fileId}/content` sem autorização. | Confirmação humana explícita (`PENDING_APPROVAL`, `60 s`), validação de `X-FSAP-Sender-Id` e exigência de `X-FSAP-Transfer-Token` efêmero comparado em tempo constante. | `HTTP 401` (`FSAP-ERR-401-UNAUTHORIZED_TRANSFER`) |
| **Corrupção ou Adulteração de Dados** | Bit-flip na rede ou modificação de conteúdo em trânsito mantendo o tamanho declarado. | Cálculo incremental **SHA-256** sobre 100% dos bytes gravados em `.part`; promoção atômica apenas quando o digest coincide exatamente. | `HTTP 422` (`FSAP-ERR-422-HASH_MISMATCH`) + exclusão imediata de `.part` |
| **Exaustão de Memória (*JSON Bomb* / Datagrama Gigante)** | Payload JSON massivo em rotas HTTP ou datagrama UDP acima do buffer. | Limite de `1024 bytes` (`MAX_UDP_DATAGRAM_BYTES`) no socket UDP e corte estrito de `256 KB` (`MAX_JSON_BODY_BYTES`) em leituras JSON (via `Content-Length` e *chunked*). | `HTTP 413` (`FSAP-ERR-413-FILE_TOO_LARGE`) / descarte silencioso UDP |
| **Exaustão de Disco e *Overflow* de Stream** | Cliente declara `sizeBytes` pequeno e tenta enviar gigabytes, ou múltiplos envios concorrentes esgotam o disco. | Corte imediato se `bytesReceived > expectedSizeBytes`, reserva concorrente de espaço (`StorageExhaustionClassifier`) e mapeamento de `ENOSPC`/`EDQUOT`. | `HTTP 400` (`FSAP-ERR-400-SIZE_MISMATCH`) ou `HTTP 507` (`FSAP-ERR-507-INSUFFICIENT_STORAGE`) |
| **Conexões Lentas / Trava de Recursos (*Slowloris*)** | Cliente congela envio de chunks ou retém sessões/tokens ociosos. | Timeouts estritos (`5 s` conexão TCP, `60 s` aprovação, `30 s` token ocioso, `15 s` estagnação de stream) e teto de sessões simultâneas. | `HTTP 408` (`FSAP-ERR-408-STREAM_TIMEOUT` / `APPROVAL_TIMEOUT`) ou rejeição `BUSY` |
| **Interceptação Ativa na LAN (*MITM / ARP Spoofing*)** | Atacante na LAN tenta interceptar tráfego HTTP em texto claro ou forjar identidade de um par. | Modo **TLS opcional (`--tls`)** com certificado X.509 RSA-2048 efêmero, divulgação de `TLS_SHA256:<hex>` e validação *Trust On First Use* (TOFU). | `FSAP-ERR-401-UNAUTHORIZED_TRANSFER` (divergência de fingerprint) |

---

## 2. Sanitização de Arquivos e Isolamento de Escrita (`FileNameSanitizer`)

Todo `fileName` recebido no manifesto de `TRANSFER_REQUEST` passa obrigatoriamente por [`FileNameSanitizer`](../composeApp/src/commonMain/kotlin/br/edu/fileshare/storage/FileNameSanitizer.kt) antes de abrir sessão de aprovação ou criar arquivos no disco:

1. **Comprimento e Não-Vazio:** rejeita nomes vazios/em branco e exige tamanho entre `1` e `255` bytes em UTF-8 (`MAX_FILENAME_UTF8_BYTES`).
2. **Proibição de Navegação Relativa:** bloqueia `"."`, `".."` e qualquer ocorrência da sequência `".."`.
3. **Proibição de Separadores e Caracteres Perigosos:** rejeita `<`, `>`, `:`, `"`, `/`, `\`, `|`, `?`, `*`, caracteres de controle (`0x00..0x1F`, `0x7F`) e sufixo terminado em ponto (`.`) ou espaço (`' '`).
4. **Nomes Reservados do Sistema Operacional:** bloqueia (case-insensitive, considerando o nome base antes do primeiro `.`) `CON`, `PRN`, `AUX`, `NUL`, `COM1`..`COM9` e `LPT1`..`LPT9`.
5. **Política de Não-Sobrescrita (`resolveNonOverwritingName`):** caso o destino já contenha um arquivo com o mesmo nome, gera sufixo incremental seguro (`documento (1).pdf`, `documento (2).pdf`) preservando o teto de `255` bytes UTF-8 (`truncateUtf8`).
6. **Isolamento em `.part` e Limpeza Determinística:** durante o recebimento, bytes são gravados exclusivamente em staging isolado (`.fsap-staging/<transferId>_<fileId>.part` no Desktop; `cacheDir/incoming` no Android antes da promoção para `MediaStore.Downloads` ou SAF). Em qualquer falha, divergência de hash, recusa ou cancelamento, os arquivos `.part` são removidos em bloco `finally` com `NonCancellable`.

---

## 3. Autorização Explícita e Tokens Efêmeros (`TransferTokenManager`)

O acesso ao endpoint binário `PUT /fsap/v1/transfers/{transferId}/files/{fileId}/content` e às rotas de controle exige duas camadas de autorização gerenciadas em [`TransferTokenManager`](../composeApp/src/commonMain/kotlin/br/edu/fileshare/network/server/TransferTokenManager.kt):

1. **Aprovação Humana Prévia:** nenhuma sessão entra no estado `ACCEPTED` sem decisão explícita do destinatário (`PENDING_APPROVAL`, limitado a `T_APPROVAL_TIMEOUT_MS = 60000 ms`).
2. **Emissão Criptográfica:** após aprovação, `PlatformTokenCrypto.generateUrlSafeToken(32)` gera um segredo aleatório de **256 bits** (`32` bytes codificados em Base64URL via `SecureRandom`) vinculado apenas àquela `transferId` e ao conjunto exato de `allowedFileIds` do manifesto aprovado.
3. **Comparação em Tempo Constante:** a validação do cabeçalho `X-FSAP-Transfer-Token` utiliza `PlatformTokenCrypto.constantTimeEquals` (`MessageDigest.isEqual` na JVM/Android), mitigando ataques de temporização (*timing attacks*).
4. **Vinculação de Remetente e Arquivo:**
   - Se informado `X-FSAP-Sender-Id` no upload, ele deve coincidir exatamente com o `senderId` que iniciou a sessão (`FSAP-ERR-401-UNAUTHORIZED_TRANSFER`).
   - Uploads para um `fileId` fora do manifesto aprovado são negados com `FSAP-ERR-404-FILE_NOT_FOUND`.
5. **Expiração por Inatividade (`30 s`):** cada token possui TTL ocioso `T_TOKEN_IDLE_TTL_MS = 30000 ms`, renovado apenas enquanto há progresso ativo de transferência; após encerramento ou expiração, a credencial é revogada da memória.

---

## 4. Proteção Anti-DoS, Limites de Carga e Cota de Disco

Os parâmetros normativos em [`FsapConstants`](../composeApp/src/commonMain/kotlin/br/edu/fileshare/protocol/FsapConstants.kt) e os guardas em servidor/armazenamento protegem os dispositivos contra esgotamento de recursos:

| Mecanismo de Proteção | Limite / Valor | Comportamento Defensivo |
| :--- | :---: | :--- |
| **Datagramas UDP Multicast** | `1024` bytes (`MAX_UDP_DATAGRAM_BYTES`) | Pacotes acima do limite ou JSONs inválidos são descartados silenciosamente sem gerar tráfego de resposta. |
| **Corpo JSON HTTP** | `256 KB` (`262144` bytes — `MAX_JSON_BODY_BYTES`) | Aplicado em rotas JSON tanto por `Content-Length` quanto durante leitura *chunked* no servidor Ktor (`FsapHttpServer`). |
| **Tamanho de Arquivo e Lote** | `64 GiB` por arquivo / `100` arquivos por lote | Validados sintática e semanticamente no `TRANSFER_REQUEST` (`FsapMessageValidator`). |
| **Streaming Constante em RAM** | Chunks de `64 KB` (`65536` bytes) | Uso proibido de `readBytes()` em código de produção; memória constante `O(1)` independente do tamanho do arquivo. |
| **Corte de *Overflow* no Stream** | `bytesReceived > expectedSizeBytes` | O canal (`IncrementalSha256Channel`) e o `FileSink` abortam imediatamente antes de gravar excedente (`FSAP-ERR-400-SIZE_MISMATCH`). |
| **Reserva Concorrente de Disco** | Soma saturada (`StorageExhaustionClassifier`) | Considera os bytes restantes de todas as sessões ativas antes de aceitar ou gravar (`ENOSPC`/`EDQUOT` mapeado para `HTTP 507`). |
| **Sessões Concorrentes de Recepção** | Padrão `8` (`MAX_CONCURRENT_RECEIVE_SESSIONS`, configurável) | Novas solicitações além da capacidade configurada em `ServerTransferSessionManager` são rejeitadas com `BUSY`. |
| **Temporizadores Defensivos** | Conexão `5 s`, Aprovação `60 s`, Token `30 s`, Stream `15 s`, Shutdown `2 s` | Evita retenção indefinida de sockets, file descriptors e sessões presas em estado intermediário. |

---

## 5. Transporte TLS Opcional e Verificação TOFU (`PlatformTlsProvider`)

Embora o protocolo `FSAP/1.0` opere por padrão em HTTP sobre TCP na rede local (facilitando interoperabilidade e inspeção didática), o **KShare** suporta transporte cifrado via **TLS opcional**:

- **Ativação:**
  - **CLI Desktop (`ServerMainKt`):** flag `--tls`;
  - **UIs Desktop e Android:** seletor de transporte seguro nas configurações do nó local e no diálogo de conexão manual por IP.
- **Certificado Efêmero e Capacidades:** [`PlatformTlsProvider`](../composeApp/src/jvmCommonMain/kotlin/br/edu/fileshare/security/tls/PlatformTlsProvider.jvm.kt) gera em memória um par de chaves RSA-2048 com certificado X.509 autoassinado efêmero para a sessão do servidor e anuncia as capacidades `TLS` e `TLS_SHA256:<hex>` (`FsapTlsCapabilities`) nas mensagens de identidade (`IDENTIFY` e descoberta).
- **Modelo *Trust On First Use* (TOFU) e Conferência Visual:**
  - Em cada conexão TLS, o cliente compara o digest SHA-256 do certificado apresentado no handshake TLS com o `fingerprint` esperado ou anunciado pelo par;
  - Qualquer divergência interrompe o handshake/transferência imediatamente com `FSAP-ERR-401-UNAUTHORIZED_TRANSFER`, bloqueando ataques *Man-in-the-Middle* (MITM);
  - As telas de **Aprovação de Transferência** e **Conexão Manual por IP** exibem o sufixo curto de **8 caracteres hexadecimais** (`FsapTlsCapabilities.shortSuffix`) para conferência visual entre os usuários.

---

## 6. Verificação Automatizada de Segurança

Todos os controles de segurança possuem cobertura automatizada nas suítes do repositório:

| Suíte de Teste | Localização | Escopo Coberto |
| :--- | :--- | :--- |
| `FileNameSanitizerTest` | `commonTest` | *Path Traversal*, bytes nulos, nomes reservados Windows, limites UTF-8 e sufixo incremental sem sobrescrita. |
| `TransferTokenManagerTest` | `commonTest` | Emissão de tokens 256 bits, tempo constante, escopo por `transferId`/`fileId` e expiração por inatividade (`30 s`). |
| `ResourceExhaustionSecurityTest` | `desktopTest` | Bloqueio de payload JSON `> 256 KB` (`Content-Length` e *chunked*), overflow em stream (`SIZE_MISMATCH`), reserva concorrente de espaço e `ENOSPC -> HTTP 507`. |
| `TlsTransportIntegrationTest` | `desktopTest` | Transferência E2E com `--tls`, rejeição de certificado MITM divergente, TOFU em `ManualPeerConnector` e sufixo curto de 8 caracteres hex na UI de aprovação. |

Execução das suítes de segurança e conformidade:
```bash
./gradlew :composeApp:desktopTest
./gradlew :composeApp:testDebugUnitTest
```

---

## 7. Versões Suportadas e Reporte de Vulnerabilidades

| Versão | Status de Suporte | Observações |
| :--- | :---: | :--- |
| `1.0.x` | Suportada | Recebe correções de segurança, hardening e atualizações de protocolo. |
| `< 1.0` | Não suportada | Versões intermediárias anteriores à conclusão da Milestone M6. |

Caso identifique uma falha de segurança (como bypass de sanitização, vazamento de token, falha na limpeza de `.part`, bypass de verificação TLS/TOFU ou vetor de DoS):
- Evite expor detalhes exploráveis publicamente antes de haver correção;
- Consulte [`SECURITY.md`](../SECURITY.md) na raiz do repositório para instruções de reporte responsável.
