# Regras de Contexto para Agentes de IA — Projeto KShare

Este repositório contém o projeto acadêmico **KShare**, uma aplicação de transferência local de arquivos entre dispositivos Desktop (Linux/Windows) e Android, utilizando um protocolo próprio da camada de aplicação, denominado **FSAP/1.0**, desenvolvido em Kotlin Multiplatform e Compose Multiplatform.

O objetivo do projeto é permitir a descoberta de dispositivos na mesma rede local e a transferência bidirecional de arquivos sem depender de um servidor central na internet.

Este arquivo estabelece as regras gerais e obrigatórias que todos os agentes de IA devem seguir ao analisar, planejar, implementar, testar ou documentar qualquer parte do projeto.

---

# 1. Diretrizes Obrigatórias de Arquitetura e Protocolo

## 1.1. Fonte Única da Verdade do Protocolo — FSAP/1.0

O protocolo FSAP/1.0 é uma especificação formal da camada de aplicação e um dos principais requisitos acadêmicos do projeto.

Antes de implementar ou modificar qualquer código relacionado a rede, comunicação, serialização, mensagens, estados, transferências ou tratamento de erros, leia obrigatoriamente:

- `docs/protocol-fsap.md`
- `docs/state-machine.md`
- `docs/protocol-messages.md`
- `docs/error-codes.md`

Consulte também, quando pertinente:

- `docs/communication-flows.md`
- `docs/discovery.md`
- `docs/transfer-flow.md`
- `docs/security.md`
- `docs/protocol-test-cases.md`
- `docs/architecture.md`
- `docs/testing.md`

Regras:

- A documentação do FSAP/1.0 é a fonte de verdade para o comportamento do protocolo.
- Não invente mensagens, campos, estados, eventos, códigos de erro ou transições sem antes verificar a especificação.
- Não implemente comportamentos incompatíveis com os fluxos documentados.
- Toda alteração no protocolo deve ser refletida na documentação correspondente.
- Se uma implementação exigir uma mudança na especificação, identifique a necessidade, proponha a alteração e atualize os documentos pertinentes antes de considerar a implementação concluída.
- Não introduza alterações incompatíveis silenciosamente.
- Preserve a versão do protocolo e documente qualquer mudança que afete sua compatibilidade.

O requisito acadêmico central é que o protocolo documente os eventos, estados e mensagens trocados entre as partes comunicantes. Portanto, a documentação do protocolo deve permanecer sincronizada com a implementação.

## 1.2. Separação Estrita entre Interface Gráfica e Serviço de Rede

A arquitetura deve manter uma separação clara entre interface gráfica, regras de negócio, protocolo e infraestrutura de comunicação.

- O Servidor Desktop possui um executável independente:
  - Classe principal: `br.edu.fileshare.desktop.server.ServerMainKt`
  - Task Gradle: `runDesktopServer`
- O serviço de rede deve funcionar independentemente da interface gráfica.
- A interface gráfica não deve conter implementação direta de sockets, protocolo ou lógica de transferência.
- A comunicação entre interface, regras de negócio e infraestrutura deve ocorrer por meio de abstrações bem definidas.
- Utilize uma organização arquitetural coerente com Kotlin Multiplatform, priorizando separação de responsabilidades e baixo acoplamento.

Classes nos pacotes:

- `br.edu.fileshare.protocol.*`
- `br.edu.fileshare.network.*`

**Jamais** podem importar:

- `androidx.compose.*`
- `java.awt.*`
- `javax.swing.*`

Não introduza dependências da interface gráfica em componentes responsáveis pelo protocolo ou pela comunicação.

## 1.3. Kotlin Multiplatform e Organização do Código

Utilize Kotlin Multiplatform como base da arquitetura.

Respeite a separação entre os conjuntos de código:

- `commonMain`: regras compartilhadas, modelos, contratos e lógica independente de plataforma.
- `commonTest`: testes de componentes compartilhados.
- `desktopMain`: implementações específicas do Desktop.
- `desktopTest`: testes específicos do Desktop.
- `androidMain`: implementações específicas do Android.
- `androidUnitTest`: testes específicos do Android.

Regras:

- Priorize código compartilhado quando houver comportamento realmente comum às plataformas.
- Utilize `expect/actual` somente quando houver necessidade concreta de implementações específicas.
- Não force abstrações desnecessárias para compartilhar código que possui comportamentos fundamentalmente diferentes.
- Isole APIs específicas de JVM e Android nos respectivos módulos ou source sets.
- Mantenha a arquitetura preparada para Desktop e Android sem comprometer a simplicidade.
- Evite duplicação de regras de negócio e de protocolo.

## 1.4. Streaming Binário e Integridade SHA-256

A transferência de arquivos deve ser realizada por streaming.

Regras obrigatórias:

- Nunca carregue arquivos inteiros na memória RAM.
- O uso de `readBytes()` é proibido para conteúdo de arquivos transferidos.
- Utilize streaming em chunks de `64 KB` (`65536` bytes).
- Calcule o hash SHA-256 incrementalmente durante a leitura ou gravação.
- Receba o conteúdo em um arquivo temporário com extensão `.part`.
- Promova o arquivo temporário para o nome definitivo somente após validar sua integridade.
- Não considere uma transferência concluída antes da validação do hash.
- Trate interrupções, cancelamentos, falhas de escrita e arquivos incompletos.
- Evite alocações desnecessárias de memória durante a transferência.

A implementação deve preservar a integridade dos arquivos e evitar corrupção em transferências interrompidas.

## 1.5. Restrição de Linguagens

Utilize exclusivamente **Kotlin** para o desenvolvimento do projeto.

São permitidas APIs específicas das plataformas quando necessárias, incluindo APIs JVM e Android.

Não crie arquivos ou módulos de implementação em:

- Python;
- Rust;
- Java (`.java`).

Não introduza outra linguagem de programação para implementar funcionalidades do KShare.

Arquivos de configuração, documentação e scripts devem utilizar os formatos apropriados às ferramentas do projeto, sem introduzir uma segunda linguagem para a lógica principal da aplicação.

## 1.6. Comunicação e Descoberta de Dispositivos

A comunicação deve respeitar as decisões arquiteturais e a especificação FSAP/1.0.

Diretrizes:

- Utilize UDP Multicast para descoberta automática de dispositivos na rede local.
- Utilize HTTP sobre TCP para transferência de arquivos e comunicação relacionada ao protocolo.
- Não dependa de um servidor central na internet.
- Disponibilize conexão manual por endereço IP como alternativa quando a descoberta multicast não estiver disponível.
- Considere as particularidades de interfaces de rede, firewalls, permissões e restrições do Android.
- Não presuma que todos os dispositivos da rede permitem multicast.
- Trate timeouts, desconexões, mensagens inválidas e falhas de comunicação de forma explícita.

Parâmetros definidos para descoberta:

- Grupo multicast: `239.255.70.83`
- Porta: `53318`

Mensagens e comportamentos de descoberta devem seguir a documentação do FSAP/1.0.

## 1.7. Segurança

A implementação deve considerar segurança desde a arquitetura, e não apenas como uma etapa final.

Regras:

- Valide todos os dados recebidos pela rede.
- Não confie em nomes de arquivos, caminhos ou metadados enviados por outros dispositivos.
- Proteja contra Path Traversal.
- Utilize mecanismos de autorização e identificação de transferências definidos pelo projeto.
- Implemente limites adequados para tamanho de arquivos, quantidade de transferências concorrentes e volume de dados recebidos.
- Trate mensagens desconhecidas, malformadas ou incompatíveis com a versão do protocolo.
- Não exponha arquivos locais sem autorização explícita do usuário.
- Exija confirmação explícita do destinatário antes de iniciar o recebimento de um arquivo.
- Não considere a descoberta de um dispositivo como autorização para enviar arquivos a ele.

Consulte `docs/security.md` e as skills relacionadas antes de implementar mecanismos de segurança.

---

# 2. Skills Especializadas do Projeto

As skills especializadas estão organizadas no diretório:

`.agents/skills/`

Antes de executar uma task do backlog, identifique o domínio técnico envolvido e consulte a skill correspondente.

Não execute uma implementação ignorando as orientações da skill aplicável.

Skills disponíveis ou previstas:

- `kotlin-multiplatform-architecture`: arquitetura, organização de pacotes, contratos, portas e adaptadores em `commonMain`, `desktopMain` e `androidMain`.
- `fsap-protocol`: DTOs, envelopes JSON, validadores, máquinas de estados, mensagens e códigos de erro do FSAP/1.0.
- `udp-device-discovery`: descoberta automática via UDP Multicast (`239.255.70.83:53318`), mensagens `ANNOUNCE`, `DISCOVER`, `BYE` e gerenciamento do `PeerRegistry`.
- `kotlin-networking`: cliente HTTP Ktor CIO, conexão manual por IP, identificação de dispositivos, seleção de interfaces de rede e timeouts.
- `ktor-http-server`: servidor HTTP Ktor CIO, rotas `/fsap/v1/...`, sessões concorrentes e inicialização do `ServerMainKt`.
- `binary-file-transfer`: streaming em chunks de 64 KB, arquivos `.part`, hashing incremental SHA-256 e gerenciamento do ciclo de vida das transferências.
- `android-file-system`: Storage Access Framework (SAF), `OpenMultipleDocuments`, `ContentResolver`, `MediaStore.Downloads`, `MulticastLock` e `FsapForegroundService`.
- `compose-multiplatform-ui`: interfaces e componentes reativos em Compose Multiplatform para Desktop e Android.
- `network-security`: `FileNameSanitizer`, proteção contra Path Traversal, `TransferTokenManager`, validação de dados e limites anti-DoS.
- `integration-testing`: testes unitários em `commonTest`, testes de integração e suíte de conformidade `TC-FSAP-001` a `TC-FSAP-026`.
- `git-semantic-commits`: fluxo Git simplificado (`develop`, `main`, `ai-workspace`), commits semânticos (*Conventional Commits*) e atomicidade por domínio.

Regras para utilização das skills:

1. Consulte a skill antes de executar uma task relacionada ao seu domínio.
2. Utilize as orientações da skill em conjunto com a documentação oficial do projeto.
3. Se uma skill contradisser a documentação do FSAP/1.0, a especificação documentada prevalece.
4. Não invente skills ou presuma que uma skill existe sem verificar seu conteúdo.
5. Se uma task envolver múltiplos domínios, consulte as skills relevantes de cada domínio.
6. Não execute skills sem relação com a tarefa atual.
7. Não altere as skills sem necessidade. Caso uma orientação esteja desatualizada, identifique a inconsistência e proponha sua atualização.

---

# 3. Diretrizes Obrigatórias de Interface, IHC e Design

Toda implementação de interface gráfica deve seguir obrigatoriamente o documento:

`DESIGN_GUIDELINES.md`

Antes de criar, modificar ou revisar qualquer tela, componente visual ou fluxo de navegação, leia esse documento.

## 3.1. Princípios de Interação Humano-Computador

A interface deve priorizar usabilidade, clareza, simplicidade e previsibilidade.

Considere obrigatoriamente:

- Heurísticas de usabilidade de Nielsen.
- Lei de Fitts.
- Lei de Hick.
- Limites cognitivos relacionados à Lei de Miller.
- Feedback imediato para ações do usuário.
- Affordances e signifiers claros.
- Prevenção de erros e recuperação de falhas.
- Consistência visual e comportamental.
- Acessibilidade e legibilidade.
- WCAG 2.2, quando aplicável.

Não implemente elementos visuais apenas por estética. Todo componente deve ter uma finalidade clara.

## 3.2. Uso dos Designs do Google Stitch

Os designs gerados no Google Stitch são referências visuais e funcionais para o desenvolvimento das interfaces.

Consulte os materiais disponíveis em:

`design/`

Regras:

- Consulte as referências do Stitch antes de implementar ou modificar telas.
- Utilize os designs como orientação, não como especificação imutável.
- Não reproduza elementos cegamente quando isso prejudicar a usabilidade.
- Não adicione componentes apenas porque aparecem no design original.
- Adapte os elementos à arquitetura, às limitações técnicas e às convenções de cada plataforma.
- Preserve a identidade visual e a consistência do Design System.
- Quando identificar problemas de usabilidade nos designs, priorize a solução mais adequada e justifique alterações relevantes.

**A fidelidade ao Stitch nunca deve ser mais importante que a usabilidade.**

## 3.3. Simplicidade e Organização Visual

A interface do KShare deve ser minimalista, funcional e pouco poluída.

Prioridades:

1. Usabilidade e clareza.
2. Acessibilidade.
3. Simplicidade.
4. Consistência visual.
5. Fidelidade às referências do Stitch.

Evite:

- Excesso de cards.
- Bordas e divisórias desnecessárias.
- Sombras decorativas.
- Ícones sem função clara.
- Badges e indicadores em excesso.
- Informações redundantes.
- Textos longos sem necessidade.
- Telas com muitos elementos competindo pela atenção.
- Componentes excessivamente complexos.

Prefira hierarquia visual clara, espaçamento consistente, tipografia legível e ações facilmente identificáveis.

## 3.4. Adaptação entre Desktop e Android

Não trate a interface Desktop como uma simples versão ampliada da interface Android, nem o contrário.

Considere as características de cada plataforma:

**Desktop:**

- Aproveitamento adequado do espaço disponível.
- Interações com mouse e teclado.
- Atalhos quando fizerem sentido.
- Organização de informações em áreas de trabalho.
- Estados de hover, focus e pressed.
- Janelas e diálogos adequados ao ambiente Desktop.

**Android:**

- Interações por toque.
- Áreas clicáveis adequadas.
- Navegação compatível com padrões Android.
- Respeito às áreas seguras e ao espaço disponível.
- Integração com seletores de arquivos e permissões do sistema.
- Adaptação a diferentes tamanhos de tela.
- Estados de carregamento, processamento e execução em segundo plano.

As plataformas devem compartilhar a identidade visual e os princípios de interação, mas podem apresentar soluções diferentes quando isso melhorar a experiência.

## 3.5. Estados e Feedback da Interface

Toda ação relevante deve apresentar feedback adequado.

Considere, conforme o contexto:

- Loading.
- Empty.
- Success.
- Error.
- Disabled.
- Hover.
- Focus.
- Pressed.
- Pending approval.
- Connecting.
- Transferring.
- Verifying.
- Completed.
- Cancelled.
- Failed.

As transferências devem comunicar claramente seu estado atual e, quando possível, o progresso.

A interface não deve apresentar uma operação como concluída antes da confirmação real da camada responsável.

---

# 4. Diretrizes de Documentação e Consistência

A documentação é parte integrante do projeto e deve acompanhar sua implementação.

Regras:

- Leia a documentação existente antes de modificar uma área do projeto.
- Não crie documentos duplicados sem necessidade.
- Respeite a organização existente em `docs/`.
- Atualize a documentação quando uma implementação alterar arquitetura, protocolo, fluxos, estados ou comportamentos.
- Utilize nomenclatura consistente entre código, documentação, testes e protocolo.
- Documente decisões arquiteturais relevantes em `docs/decisions/`, seguindo o padrão existente.
- Utilize diagramas Mermaid quando ajudarem a explicar arquitetura, comunicação, fluxos ou máquinas de estados.
- Não registre como implementado algo que ainda está apenas planejado.
- Não remova decisões documentadas sem justificar a alteração.

A documentação deve representar o comportamento real do sistema, não apenas a intenção inicial de desenvolvimento.

---

# 5. Organização e Execução do Backlog

O backlog principal está localizado em:

`tasks/backlog.md`

Antes de iniciar uma tarefa:

1. Leia a descrição e os critérios de aceitação.
2. Identifique o domínio funcional envolvido.
3. Consulte a documentação relacionada.
4. Consulte as skills aplicáveis.
5. Inspecione o código existente e sua arquitetura.
6. Verifique as dependências e possíveis impactos em outros módulos.
7. Defina uma abordagem compatível com as convenções do projeto.
8. Implemente somente o escopo necessário para atender à task.
9. Execute os testes e validações pertinentes.
10. Atualize a documentação e o status da task, quando aplicável.

## 5.1. Respeito ao Escopo

- Não implemente funcionalidades que não fazem parte da task atual sem necessidade justificada.
- Não altere módulos não relacionados sem uma razão técnica concreta.
- Não antecipe tasks futuras apenas por conveniência.
- Não ignore dependências registradas no backlog.
- Não considere uma task concluída enquanto seus critérios de aceitação não forem atendidos.
- Se identificar uma dependência ausente, inconsistência ou impedimento, documente o problema antes de prosseguir.

## 5.2. Organização das Tasks

As tasks devem seguir a estrutura definida no backlog do projeto.

Cada task deve possuir, conforme o padrão estabelecido:

- ID.
- Título.
- Descrição.
- Objetivo.
- Domínio.
- Prioridade.
- Complexidade.
- Dependências.
- Critérios de aceitação.
- Arquivos envolvidos.
- Testes necessários.
- Status.

Não atribua automaticamente tarefas ou responsabilidades individuais aos integrantes da equipe. A organização do backlog deve permanecer baseada em domínios funcionais e dependências técnicas, salvo quando houver solicitação explícita em contrário.

## 5.3. Paralelismo e Integração

Ao trabalhar em paralelo com outros agentes:

- Respeite os limites dos módulos e domínios.
- Evite modificar simultaneamente os mesmos arquivos sem coordenação.
- Não altere contratos compartilhados unilateralmente.
- Mantenha compatibilidade com interfaces e modelos já definidos.
- Comunique mudanças relevantes em contratos, DTOs, APIs e comportamentos.
- Não presuma que alterações de outro agente já foram integradas ou testadas.

---

# 6. Padrões de Qualidade de Código

Todo código deve priorizar legibilidade, simplicidade, manutenção e consistência.

Regras:

- Utilize nomes claros e descritivos.
- Evite funções excessivamente grandes.
- Evite classes com responsabilidades não relacionadas.
- Prefira soluções simples em vez de abstrações desnecessárias.
- Evite duplicação de lógica.
- Trate erros explicitamente.
- Utilize Kotlin Coroutines de maneira adequada para operações assíncronas.
- Evite bloquear a thread principal da interface.
- Não utilize mecanismos de concorrência sem compreender seus efeitos.
- Evite dependências externas quando a biblioteca padrão ou as dependências existentes forem suficientes.
- Não introduza bibliotecas sem avaliar sua necessidade e compatibilidade com Kotlin Multiplatform.
- Respeite as convenções de formatação e organização já utilizadas no repositório.

Não faça refatorações amplas sem necessidade relacionada à tarefa.

---

# 7. Testes e Validação

Toda implementação deve possuir validação compatível com o tipo de alteração realizada.

Considere:

- Testes unitários.
- Testes de integração.
- Testes de conformidade do FSAP/1.0.
- Testes de comunicação entre dispositivos.
- Testes de transferência de arquivos.
- Testes de integridade SHA-256.
- Testes de cancelamento e interrupção.
- Testes de erros e timeouts.
- Testes específicos de Desktop e Android.
- Testes de interface quando aplicáveis.

Regras:

- Não remova testes existentes apenas para fazer uma implementação passar.
- Não altere expectativas de testes sem compreender a razão.
- Não considere apenas a compilação como comprovação de funcionamento.
- Execute os testes relevantes para o escopo modificado.
- Informe claramente quais testes foram executados e seus resultados.
- Se não for possível executar algum teste, explique o motivo.
- Não afirme que uma funcionalidade está validada quando ela não foi efetivamente testada.

A suíte de conformidade `TC-FSAP-001` a `TC-FSAP-026` deve ser preservada e ampliada quando necessário, mantendo a rastreabilidade com a especificação do protocolo.

---

# 8. Regras de Comunicação e Relato de Trabalho

Ao concluir uma task ou uma etapa de trabalho, apresente um resumo objetivo contendo:

1. O que foi realizado.
2. Quais arquivos foram criados ou modificados.
3. Quais decisões técnicas relevantes foram tomadas.
4. Quais testes foram executados.
5. Quais foram os resultados dos testes.
6. Quais limitações ou pendências permanecem.
7. Se houve alteração na documentação ou no protocolo.

Não oculte erros, limitações ou decisões que possam impactar outras partes do projeto.

Não afirme que:

- Um teste passou sem executá-lo.
- Uma funcionalidade está concluída quando está parcialmente implementada.
- Uma integração funciona sem validação.
- Uma documentação foi atualizada quando isso não ocorreu.

Se houver ambiguidade relevante nos requisitos, consulte a documentação e o contexto existente. Se a ambiguidade não puder ser resolvida, apresente a dúvida antes de tomar uma decisão que possa comprometer a arquitetura ou o protocolo.

---

# 9. Hierarquia de Prioridades

Em caso de conflito entre orientações, siga esta ordem:

1. Requisitos acadêmicos obrigatórios e requisitos funcionais explicitamente definidos.
2. Especificação documentada do FSAP/1.0.
3. Arquitetura e decisões técnicas documentadas do KShare.
4. Regras deste `AGENTS.md`.
5. `DESIGN_GUIDELINES.md`, para decisões de interface e experiência do usuário.
6. Skills especializadas do domínio.
7. Referências visuais do Google Stitch.
8. Preferências pessoais de implementação do agente.

Essa hierarquia não autoriza ignorar requisitos de segurança, acessibilidade ou qualidade. Se houver conflito real entre documentos ou requisitos, identifique-o e proponha uma resolução em vez de escolher silenciosamente uma interpretação.

---

# 10. Princípio Geral

O agente deve agir como um colaborador técnico do projeto, respeitando sua arquitetura, documentação, protocolo, requisitos acadêmicos e decisões já estabelecidas.

**Não implemente apenas para fazer funcionar. Implemente de acordo com a arquitetura e com o protocolo especificados, mantendo o código, a documentação, os testes e a interface consistentes entre si.**

O KShare deve permanecer simples de utilizar, tecnicamente consistente, seguro, testável e compreensível para os integrantes da equipe e para a avaliação acadêmica.