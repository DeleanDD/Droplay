# Correções de biblioteca, busca e atualização — 1.2.24

Revisão de 4 de outubro de 2026, sobre a versão publicada 1.2.23.

## Falhas identificadas

- **Reclassificação sem fim:** os três loops consultavam sempre os primeiros 500 registros, sem avançar e sem excluir os já classificados. O mutex compartilhado permanecia ocupado, impedindo novas sincronizações e consumindo CPU/banco continuamente.
- **Identificação inconsistente dos filmes:** a persistência usava `MOVIE` e a política de atualização procurava `VOD`. Isso fazia filmes parecerem sempre vencidos. Datas globais em preferências também não distinguiam contas.
- **Abas desatualizadas:** o Compose memorizava categorias e resultados apenas pela lista de itens. Quando o índice inicial era substituído pelo completo com os mesmos itens, abas e listas podiam permanecer vazias.
- **Busca e ordenação caras:** a consulta era normalizada novamente para cada título; a ordenação executava normalização e regex dentro de comparadores, na thread da interface. Resultados por categoria podiam ocupar o limite antes de um título correspondente.
- **Trabalho duplicado:** cada seção baixada iniciava outra organização completa, além da organização final. Tarefas antigas podiam publicar índices depois de uma atualização mais recente. O banco também era relido desnecessariamente após a sincronização.
- **Novidades fora de ordem:** a aba “Últimos adicionados” era reordenada pela preferência geral (ano de lançamento), perdendo a ordem de inclusão.

## Correções

Reclassificar somente registros com versão antiga, em lotes limitados, preservando a proteção adulta. Ler as datas de sucesso por conta e seção no Room, aceitando o identificador legado `MOVIE`. Atualizar as abas quando o catálogo preparado muda. Normalizar cada consulta uma vez, permitir palavras separadas e pontuação, priorizar títulos e cancelar buscas substituídas. Ordenar fora da thread de interface e reutilizar regexes e categorias já normalizadas.

Preparar os índices uma vez por publicação, cancelando tarefas substituídas. Manter as abas atuais durante uma atualização. Verificar atualizações vencidas também enquanto o aplicativo permanece aberto e incorporar atualizações salvas pelo WorkManager. A verificação local é feita a cada minuto; as listagens Xtream mantêm os prazos de 30 minutos (canais) e 6 horas (filmes/séries). O botão “Atualizar agora” solicita todas as seções sem cobrir a biblioteca com a tela de carregamento.

O Xtream utilizado ainda retorna uma listagem completa para cada seção vencida; não foi inventado um protocolo de download incremental de itens que o servidor não oferece. Seções ainda válidas não são solicitadas. Falhas preservam o catálogo salvo e ficam visíveis nas configurações.

## Validação

- 47 testes unitários, incluindo acentos, pontuação, palavras separadas, prioridade de títulos, cancelamento e datas legadas.
- 8 testes instrumentados no emulador Android TV API 36: migrações 1→2 e 2→3, persistência transacional, reclassificação de 3.003 registros, sincronização HTTP controlada, busca pela tela e atualização de abas.
- No servidor de teste: 7 requisições na carga inicial; nenhuma requisição adicional com cache válido; somente 3 adicionais ao vencer VOD (autenticação, categorias VOD e listagem VOD). Inclusões, alterações e remoções verificadas no cache resultante.
- Biblioteca sintética de 50.000 títulos em APK debug: preparação inicial 95–132 ms, índices completos 2.040–2.100 ms, busca 136–149 ms, ordenação 37–61 ms. Índices e ordenação são preparados em segundo plano.

As medições não incluem download, capas ou a lista real do provedor. Não representam um tempo garantido em todo aparelho.
