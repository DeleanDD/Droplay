# DROPLAY

DROPLAY é um player público e independente para Android TV. Ele reproduz fontes que o próprio usuário possui autorização para acessar e **não inclui, vende, revende ou ativa conteúdo**.

## Recursos

- Xtream Codes como fonte principal e recomendada (`servidor`, `usuário`, `senha`);
- carregamento pelas APIs Xtream separadas de canais, filmes, séries, detalhes e EPG;
- episódios Xtream obtidos sob demanda somente ao abrir uma série, sem baixar uma M3U completa;
- leitura incremental das respostas Xtream, adequada a aparelhos Android TV com pouca memória;
- M3U como compatibilidade secundária, preferindo automaticamente M3U Plus em links `get.php`;
- canais ao vivo, filmes, séries e episódios;
- EPG XMLTV nativo (inclusive `.gz`), quando informado pela fonte;
- múltiplas faixas de áudio, legendas internas atualizadas dinamicamente e legendas externas Xtream/M3U carregadas somente quando escolhidas;
- busca paginada de legendas na API REST oficial do OpenSubtitles por TMDB ID, com fallback identificado por título/ano;
- cache local de legendas, prioridade para português do Brasil, ajuste de atraso, tamanho, cor, fundo e contorno;
- avanço/retrocesso por D-pad, barra rápida e teclas de mídia;
- favoritos, histórico e retomada local;
- catálogo por capas, páginas de detalhes, temporadas e episódios;
- subcategorias em abas e player integrado com fallback de codecs;
- interface 10-foot premium, criada para controle remoto;
- HLS, DASH, RTSP, mídia progressiva e fallback entre codecs do aparelho via AndroidX Media3;
- redirecionamentos HTTP/HTTPS e cabeçalhos compatíveis com servidores Xtream de VOD;
- H.265/HEVC, H.264, VP8, VP9 e AV1 quando suportados pelo hardware/Android da TV;
- cache binário local aberto antes de qualquer atualização, com migração automática do cache antigo e atualização semanal padrão;
- canais, filmes e séries Xtream baixados em paralelo para reduzir o tempo da primeira importação;
- cache incremental e catálogo pré-organizado para navegação rápida mesmo em listas muito grandes;
- sincronização Xtream adiada após a abertura e EPG carregado somente ao entrar em TV ao vivo;
- proteção +18 por senha, ocultação opcional de filmes `CINEMA` e ordenação por ano, A-Z ou mais assistidos;
- áreas Infantil e Nacional, categorias consolidadas (incluindo serviços equivalentes, doramas e novelas), busca integrada e Minha Lista organizada por tipo;
- carrosséis navegáveis por controle remoto no Início, Infantil e Nacional;
- canais ao vivo em lista, com favoritos próprios e categorias priorizadas para futebol, emissoras abertas, filmes e séries, esportes e documentários;
- leitura sob demanda e incremental de temporadas Xtream para evitar picos de memória;
- release otimizado com R8 e remoção de recursos não usados.

## Gerar o APK

Requisitos: JDK 17 e Android SDK 36.

```bash
./gradlew assembleDebug
```

O APK será criado em `app/build/outputs/apk/debug/app-debug.apk`.

## Configurar o OpenSubtitles

O DROPLAY não contém chave, token, usuário ou senha do OpenSubtitles no código ou no repositório. Para habilitar as legendas automáticas:

1. Crie uma conta em `opensubtitles.com`, consulte a [documentação oficial da API](https://opensubtitles.stoplight.io/docs/opensubtitles-api/e3750fd63a100-getting-started) e crie uma aplicação/API Key no perfil de desenvolvedor.
2. No DROPLAY, abra **Configurações → Legendas automáticas • OpenSubtitles**.
3. Informe a API Key, o User-Agent registrado, o usuário e a senha.
4. Selecione **Salvar configuração** e depois **Entrar no OpenSubtitles**.
5. Durante um filme ou episódio, abra o ícone **CC**. A busca usa primeiro o TMDB ID recebido do Xtream; quando ele não existe, a interface avisa que a correspondência por título/ano é aproximada.

A API Key, o usuário e o token temporário ficam cifrados com uma chave não exportável do Android Keystore. A senha é enviada diretamente ao endpoint oficial `/login` e nunca é armazenada. O download oficial consome a cota da conta. Para um aplicativo público, não é seguro embutir uma chave compartilhada no APK; cada instalação deve usar credenciais autorizadas.

As legendas baixadas são normalizadas para UTF-8 e guardadas por `file_id` por até 30 dias, com limite total de 50 MB. O cache aceita SRT e WebVTT e também trata arquivos antigos em Windows-1252. Atrasos são aplicados localmente, sem consumir um novo download.

Arquivos locais como `.env`, `opensubtitles.properties` e `secrets.properties` estão ignorados pelo Git. Eles não são necessários no fluxo recomendado pela interface e nunca devem ser enviados ao repositório.

### Teste rápido no PC

Crie uma única vez um dispositivo **Android TV** no Device Manager do Android Studio. Com o emulador aberto, arraste o APK para a tela ou execute:

```powershell
.\scripts\install-on-emulator.ps1
```

O script reinstala e abre o DROPLAY, preservando os dados para acelerar testes sucessivos.

Para uma versão de distribuição assinada, configure uma keystore local (nunca envie as senhas ao GitHub), adicione a configuração de assinatura ao Gradle e execute `./gradlew assembleRelease`.

## Download pelo GitHub

1. Crie um repositório e envie este projeto.
2. A Action **Android CI** apenas testa o projeto; builds de debug não são distribuídos porque sua assinatura não permite atualizações públicas confiáveis.
3. Antes da primeira publicação, configure os Secrets do repositório: `DROPLAY_KEYSTORE_B64` (keystore codificada em Base64), `DROPLAY_STORE_PASSWORD`, `DROPLAY_KEY_ALIAS` e `DROPLAY_KEY_PASSWORD`.
4. Crie uma tag, por exemplo `git tag v1.0.0 && git push origin v1.0.0`. O workflow publicará uma Release com o APK otimizado e assinado automaticamente.
5. Para instalar ou atualizar o DROPLAY, baixe o APK exclusivamente em **Releases**. Todas as versões de produção usam a mesma chave permanente.

Exemplo de endereço final: `https://github.com/SEU_USUARIO/Droplay/releases/latest`.

## Privacidade e uso responsável

Credenciais e preferências ficam no armazenamento privado do aplicativo. O DROPLAY apenas conecta o aparelho diretamente à fonte indicada. Use somente listas e serviços que você tenha direito de acessar; a disponibilidade e a legalidade do conteúdo são responsabilidade do provedor e do usuário.

## Licença

MIT — veja [LICENSE](LICENSE).
