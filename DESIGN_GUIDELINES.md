# KShare — Diretrizes permanentes de UI/UX e Interação Humano-Computador

Este documento define as diretrizes obrigatórias para qualquer desenvolvimento ou alteração de interface no projeto KShare.

**Estas instruções devem ser consultadas e respeitadas sempre que você trabalhar em qualquer componente visual, tela, fluxo de navegação ou interação do aplicativo.**

A referência visual produzida pelo Google Stitch estará disponível nos arquivos principais do projeto. Utilize-a como referência de identidade visual, organização, componentes e experiência desejada.

Entretanto, **não reproduza cegamente os designs do Stitch**. Eles são referências, não especificações definitivas.

Sua responsabilidade é transformar essas referências em uma interface funcional, simples, acessível, consistente e tecnicamente adequada ao Compose Multiplatform.

---

# 1. Princípio fundamental: simplicidade acima de tudo

O KShare é uma aplicação de transferência de arquivos. Sua interface deve facilitar essa tarefa, não competir com ela.

Sempre priorize:

- Simplicidade.
- Clareza.
- Funcionalidade.
- Consistência.
- Previsibilidade.
- Acessibilidade.
- Baixa carga cognitiva.
- Facilidade de aprendizado.

**Menos elementos visuais, menos decisões desnecessárias e menos etapas para realizar uma ação.**

Evite criar elementos apenas para preencher espaços ou deixar a interface visualmente mais elaborada.

Cada componente deve possuir uma finalidade clara.

Antes de adicionar qualquer elemento, pergunte:

1. Ele realmente ajuda o usuário?
2. Ele facilita a compreensão ou execução de uma tarefa?
3. A informação apresentada é necessária naquele contexto?
4. Existe uma maneira mais simples de apresentar essa informação?

Se um elemento não agregar valor real, não o adicione.

Não confunda uma interface minimalista com uma interface incompleta. Todas as informações necessárias devem continuar acessíveis.

---

# 2. Interação Humano-Computador é obrigatória

Todas as decisões de interface e interação devem respeitar princípios de IHC.

Utilize as dez heurísticas de usabilidade de Jakob Nielsen como referência obrigatória:

1. Visibilidade do estado do sistema.
2. Correspondência entre o sistema e o mundo real.
3. Controle e liberdade do usuário.
4. Consistência e padrões.
5. Prevenção de erros.
6. Reconhecimento em vez de memorização.
7. Flexibilidade e eficiência de uso.
8. Design estético e minimalista.
9. Reconhecimento, diagnóstico e recuperação de erros.
10. Ajuda e documentação.

Também considere:

### Lei de Fitts
- Elementos importantes devem possuir áreas de interação confortáveis.
- Botões principais devem ser facilmente acessíveis.
- No Android, considere o alcance do polegar.
- No Desktop, considere mouse e teclado.

### Lei de Hick
- Evite excesso de opções simultâneas.
- Apresente ações conforme sua importância.
- Reduza decisões desnecessárias.

### Lei de Miller
- Agrupe informações relacionadas.
- Evite sobrecarregar a memória de trabalho.
- Apresente informações de maneira organizada.

### Feedback
- Toda ação importante deve produzir feedback compreensível.
- Indique carregamento, sucesso, falha e indisponibilidade.
- Nunca deixe o usuário sem saber o que está acontecendo.

### Acessibilidade
Considere WCAG 2.2 como referência.

- Contraste adequado.
- Textos legíveis.
- Áreas de interação apropriadas.
- Compatibilidade com leitores de tela.
- Estados que não dependam exclusivamente de cores.
- Navegação por teclado no Desktop.
- Respeito às configurações de acessibilidade do Android.

Não trate acessibilidade como um recurso opcional ou uma etapa posterior.

---

# 3. Como utilizar as referências do Google Stitch

Consulte os designs do Stitch disponíveis no repositório antes de desenvolver ou modificar interfaces.

Utilize-os para compreender:

- Identidade visual.
- Paleta de cores.
- Tipografia.
- Hierarquia visual.
- Organização das telas.
- Componentes.
- Fluxos de navegação.
- Comportamentos esperados.

Entretanto, siga estas regras:

1. Não reproduza elementos desnecessários apenas porque aparecem no Stitch.
2. Não mantenha componentes que prejudiquem a usabilidade.
3. Não adicione complexidade para imitar exatamente um layout.
4. Não force o mesmo layout no Desktop e no Android.
5. Simplifique interfaces visualmente poluídas.
6. Corrija inconsistências de navegação.
7. Adapte os componentes às capacidades reais do Compose Multiplatform.
8. Preserve a identidade visual geral do KShare.

Se identificar problemas nas referências, priorize os princípios de IHC e registre a justificativa da alteração.

**A usabilidade tem prioridade sobre a fidelidade visual absoluta ao Stitch.**

---

# 4. Identidade visual e consistência

Mantenha uma identidade visual única para o KShare.

Utilize os arquivos de referência para identificar:

- Cores primárias e secundárias.
- Backgrounds.
- Tipografia.
- Espaçamentos.
- Bordas.
- Raios.
- Ícones.
- Componentes.
- Estados visuais.

Centralize os elementos do Design System sempre que possível.

Evite:
- Criar cores isoladas sem necessidade.
- Utilizar diferentes estilos de botões para a mesma finalidade.
- Misturar famílias tipográficas sem justificativa.
- Criar espaçamentos aleatórios.
- Utilizar sombras excessivas.
- Introduzir gradientes decorativos sem função.
- Misturar estilos de ícones.

Não crie um novo padrão visual para cada tela.

A interface deve parecer parte de um único produto.

---

# 5. Simplicidade e hierarquia visual

Cada tela deve possuir uma hierarquia visual clara.

Priorize:
1. A informação mais importante.
2. A ação principal.
3. As informações complementares.
4. As ações secundárias.

Não dê o mesmo destaque visual a todas as informações.

Evite:
- Muitos cards dentro de outros cards.
- Bordas em todos os elementos.
- Divisores desnecessários.
- Ícones decorativos.
- Textos explicativos redundantes.
- Múltiplos botões primários na mesma região.
- Excesso de badges e indicadores.
- Grandes espaços ocupados por informações irrelevantes.

Utilize espaços em branco para organizar o conteúdo.

Não tente preencher todos os espaços disponíveis.

Uma tela pode ser simples e possuir poucos elementos. Isso não significa que esteja mal aproveitada.

---

# 6. Experiência Desktop e Android

O KShare possui duas plataformas principais:

- Desktop: Windows e Linux.
- Android: smartphones e tablets.

As plataformas devem compartilhar a identidade visual, mas não necessariamente o mesmo layout.

## Desktop

Considere:
- Mouse.
- Teclado.
- Hover.
- Focus.
- Atalhos.
- Redimensionamento da janela.
- Diferentes resoluções.
- Densidade de informações apropriada.

Utilize layouts que aproveitem adequadamente o espaço disponível.

Não transforme o Desktop em uma interface mobile ampliada.

## Android

Considere:
- Interação por toque.
- Uso com uma mão.
- Alcance do polegar.
- Navegação por gestos.
- Material Design 3.
- Barras do sistema.
- Teclado virtual.
- Diferentes tamanhos de tela.
- Orientação retrato e paisagem.

Não transforme o Android em uma interface Desktop comprimida.

Respeite os padrões de interação esperados em cada plataforma.

---

# 7. Regras específicas para o KShare

O objetivo principal da aplicação é transferir arquivos entre dispositivos na mesma rede local.

Portanto, todas as decisões de interface devem facilitar esse fluxo.

O usuário deve conseguir:

1. Abrir o aplicativo.
2. Identificar seu dispositivo.
3. Visualizar dispositivos disponíveis.
4. Escolher um destinatário.
5. Selecionar arquivos.
6. Confirmar o envio.
7. Acompanhar a transferência.
8. Visualizar o resultado.

O recebimento também deve ser simples:

1. Receber uma solicitação.
2. Identificar o remetente.
3. Visualizar os arquivos.
4. Aceitar ou recusar.
5. Acompanhar o recebimento.
6. Visualizar a conclusão.

Não introduza etapas desnecessárias nesses fluxos.

Não exiba informações técnicas de rede para usuários comuns, como IP, portas e detalhes de sockets, salvo quando forem necessárias para uma configuração manual ou diagnóstico.

---

# 8. Estados e feedback obrigatórios

Nunca desenvolva uma tela considerando apenas seu estado ideal.

Considere os estados aplicáveis a cada componente:

- Inicial.
- Carregando.
- Vazio.
- Disponível.
- Indisponível.
- Em andamento.
- Concluído.
- Cancelado.
- Falha.
- Desabilitado.

Para o fluxo de descoberta:
- Procurando dispositivos.
- Dispositivos encontrados.
- Nenhum dispositivo encontrado.
- Falha na descoberta.

Para transferências:
- Aguardando aprovação.
- Aprovada.
- Recusada.
- Em andamento.
- Cancelando.
- Concluída.
- Falhou.

As informações devem corresponder ao estado real do sistema.

Não simule progresso, velocidade, tempo restante ou disponibilidade.

Não apresente funcionalidades visuais que ainda não possuem comportamento implementado como se fossem operacionais.

Mensagens de erro devem utilizar linguagem compreensível e indicar, quando possível, como resolver o problema.

---

# 9. Componentização e implementação

Ao implementar interfaces em Kotlin e Compose Multiplatform:

- Prefira componentes pequenos e reutilizáveis.
- Evite duplicação de código.
- Mantenha separação entre UI e lógica de negócio.
- Utilize estados declarativos.
- Evite componentes excessivamente complexos.
- Centralize tokens e estilos.
- Respeite a arquitetura existente.
- Utilize componentes específicos de plataforma quando necessário.

Não crie abstrações desnecessárias.

Não transforme cada elemento visual em um componente independente sem necessidade.

O objetivo é obter uma arquitetura de UI organizada, mas simples de manter.

Não sacrifique a clareza do código em nome de uma componentização excessiva.

---

# 10. Regras de comportamento da IA

Sempre que receber uma solicitação relacionada à interface:

1. Consulte este documento.
2. Consulte os designs do Stitch relevantes.
3. Analise a tela ou o fluxo existente.
4. Identifique os requisitos funcionais.
5. Considere os princípios de IHC.
6. Planeje a solução mais simples.
7. Implemente respeitando o Design System.
8. Verifique os estados da interface.
9. Revise acessibilidade e responsividade.
10. Verifique se não introduziu elementos desnecessários.

Não modifique telas não relacionadas à tarefa sem necessidade.

Não introduza novas dependências apenas para resolver problemas simples de UI.

Se a solicitação entrar em conflito com princípios de IHC, apresente uma alternativa mais adequada e explique brevemente a decisão.

Se houver dúvida sobre o comportamento esperado, consulte a arquitetura e os documentos funcionais do projeto antes de inventar comportamentos.

---

# 11. Checklist obrigatório de revisão

Antes de considerar uma interface concluída, verifique:

### Usabilidade
- [ ] A finalidade da tela é clara?
- [ ] A ação principal é facilmente identificável?
- [ ] A navegação é previsível?
- [ ] A quantidade de informações é adequada?
- [ ] O usuário consegue realizar a tarefa sem esforço desnecessário?

### IHC
- [ ] O estado do sistema está visível?
- [ ] Existe feedback para as ações?
- [ ] Erros são prevenidos quando possível?
- [ ] O usuário consegue cancelar ou voltar quando apropriado?
- [ ] As informações são apresentadas de forma reconhecível?

### Visual
- [ ] A identidade visual está consistente?
- [ ] A hierarquia visual está clara?
- [ ] Existem elementos decorativos desnecessários?
- [ ] O espaçamento está consistente?
- [ ] A interface está visualmente limpa?

### Acessibilidade
- [ ] O contraste é adequado?
- [ ] Os textos são legíveis?
- [ ] Os elementos interativos possuem tamanho apropriado?
- [ ] Os estados não dependem somente de cores?
- [ ] A navegação por teclado ou leitor de tela é considerada quando aplicável?

### Multiplataforma
- [ ] A interface está adaptada à plataforma?
- [ ] O Desktop respeita mouse e teclado?
- [ ] O Android respeita toque e ergonomia?
- [ ] O layout funciona em diferentes dimensões?

---

# 12. Diretriz final

O KShare deve possuir uma interface moderna, mas não precisa parecer complexa para parecer profissional.

**Simplicidade é uma decisão de design, não uma ausência de design.**

Sempre escolha a solução mais simples que atenda aos requisitos funcionais e aos princípios de IHC.

Não adicione elementos para impressionar visualmente. Não complique fluxos que podem ser diretos. Não sacrifique usabilidade para reproduzir fielmente uma referência visual.

A prioridade absoluta é que qualquer usuário consiga compreender e utilizar o KShare com facilidade, segurança e confiança.

Em caso de conflito entre estética, fidelidade ao Stitch e usabilidade, priorize nesta ordem:

1. Usabilidade e princípios de IHC.
2. Acessibilidade.
3. Clareza e simplicidade.
4. Consistência do Design System.
5. Fidelidade às referências visuais.

Estas diretrizes são permanentes e devem orientar todo o desenvolvimento da interface do KShare.