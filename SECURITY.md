# Política de Segurança — KShare

## Versões Suportadas

| Versão | Status de Suporte |
| :--- | :--- |
| `1.0.x` | Suportada |
| `< 1.0` | Não suportada |

## Garantias de Segurança do Projeto (`FSAP/1.0`)

O **KShare** foi projetado para transferência direta de arquivos em redes locais (LAN/Wi-Fi) sem servidor central, aplicando defesa em profundidade em todas as camadas:

- **Sanitização e Confinamento de Arquivos (`FileNameSanitizer`):** bloqueio de *Path Traversal* (`../`, `..\`), caracteres de controle, nomes reservados do Windows e gravação isolada em `.part` promovida somente após verificação **SHA-256**, sem sobrescrever arquivos existentes.
- **Autorização Explícita e Tokens Efêmeros (`TransferTokenManager`):** nenhuma gravação ocorre sem confirmação manual do usuário (`60 s` de timeout); tokens de 256 bits (`SecureRandom`) são comparados em tempo constante e expiram após `30 s` de inatividade.
- **Proteção Anti-DoS e Cota de Disco:** limite de `1024 bytes` em UDP, teto de `256 KB` para payloads JSON HTTP, corte imediato de *overflow* no stream (`SIZE_MISMATCH`), reserva concorrente de espaço em disco (`HTTP 507`), timeout de estagnação de `15 s` e teto de sessões simultâneas (`BUSY`).
- **Transporte TLS Opcional (`--tls`) e TOFU:** certificado X.509 RSA-2048 efêmero por sessão com verificação de **fingerprint SHA-256** (*Trust On First Use*) e sufixo curto de 8 dígitos hexadecimais visível na interface de aprovação e no terminal.

Para a especificação técnica completa de ameaças, constantes e testes automatizados, consulte [`docs/security.md`](docs/security.md).

## Como Reportar uma Vulnerabilidade

Se você descobrir uma vulnerabilidade de segurança neste repositório (por exemplo, bypass de validação de nomes de arquivo, falha na verificação TOFU/SHA-256, exposição de credenciais ou negação de serviço):

1. **Não publique** provas de conceito ou detalhes exploráveis diretamente em issues públicas antes de o problema ser corrigido.
2. Abra um aviso privado via **GitHub Security Advisories** na aba **Security** do repositório (`https://github.com/enzosarmento/file-share/security/advisories/new`) ou envie as informações diretamente aos mantenedores com:
   - Descrição do componente afetado (ex.: `FileNameSanitizer`, `TransferTokenManager`, `FsapHttpServer`, `PlatformTlsProvider`, `AndroidFileSink`);
   - Passos mínimos ou caso de teste para reprodução;
   - Impacto estimado e versão/commit analisado.
3. Assim que a falha for confirmada, a correção será integrada na branch `develop` com teste de regressão dedicado e publicada em uma nova release com checksums atualizados.
