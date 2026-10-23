# Correções 1.2.25

- Ao vivo abria em Favoritos mesmo sem favoritos, exibindo uma lista vazia. Agora abre em Todos nesse caso e mantém Favoritos como primeira aba. Reprodução ao vivo também tenta HLS (.m3u8) quando a URL TS falha.
- Episódios representados como vídeos eram enviados ao endpoint `/movie/`. Agora usam `/series/`, inclusive quando recuperados do banco.
- Episódios sem imagem usam a capa da série; uma imagem de episódio que falha ao carregar também recebe essa alternativa.
- Nacional reúne filmes, séries, novelas brasileiras e canais de notícias identificados pelo nome (GloboNews, CNN, SBT News, BandNews, Record News, Jovem Pan, TV Brasil e AgroMais), respeitando os filtros do catálogo. A lista de canais tem altura limitada para evitar medição infinita dentro da tela rolável.
- Sufixos de categorias como Ação² e Ação 2 são unificados. Anos como 2026 são preservados.
- Filmes abrem com foco em Assistir/Continuar. Séries abrem em Assistir ao primeiro episódio ou Continuar ao último assistido, após a carga dos episódios. Temporada ausente é tratada como temporada 1.
- Player: eventos de controle remoto são registrados antes do alvo de foco; barra de tempo tem foco explícito e nome acessível. Cima vai à barra; baixo aos controles; na barra, esquerda/direita avançam ou retrocedem e o botão central alterna reprodução. Cima da barra acessa Voltar. Teclas dedicadas de mídia funcionam em qualquer controle.
- Ao ocultar os controles, o foco retorna à reprodução. Fechar áudio/legendas também restaura esse foco. Controles desaparecem após inatividade durante a reprodução, mesmo quando um botão estava focado.
- Buffer inicial reduzido de 1 segundo para 500 ms e retomada após rebuffer de 2 segundos para 1,5 segundo. Extratores padrão são tentados antes do adaptador MP4, evitando análise MP4 adicional em outros formatos. A velocidade real depende do servidor, conexão e arquivo.

## Validação

`testDebugUnitTest connectedDebugAndroidTest`: 50 testes unitários e 12 testes instrumentados aprovados no emulador Television_1080p (Android TV API 36).

Testes incluem URL de episódio, agrupamento de categorias, notícias nacionais, canais visíveis sem favoritos, foco inicial em filmes/séries, navegação por temporada/episódio e reprodução Media3 real com arquivo PCM local. O teste do player verifica botão central após controles ocultos, acesso à barra, avanço real da posição e play/pause sem sair da barra.

Não foi utilizada conta de provedor nesta validação; disponibilidade e comportamento HTTP dos canais de uma conta específica dependem de teste com essa conta.
