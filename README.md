# DROPLAY

DROPLAY é um player público e independente para Android TV. Ele reproduz fontes que o próprio usuário possui autorização para acessar e **não inclui, vende, revende ou ativa conteúdo**.

## Recursos

- login por Xtream Codes (`servidor`, `usuário`, `senha`);
- carregamento separado de canais, filmes e séries para reduzir memória e tempo de abertura;
- episódios obtidos sob demanda somente ao abrir uma série, sem baixar uma M3U completa;
- leitura incremental das respostas Xtream, adequada a aparelhos Android TV com pouca memória;
- canais ao vivo, filmes, séries e episódios;
- EPG XMLTV nativo (inclusive `.gz`), quando informado pela fonte;
- múltiplas faixas de áudio e legendas expostas pelo stream;
- avanço/retrocesso por D-pad, barra rápida e teclas de mídia;
- favoritos, histórico e retomada local;
- catálogo por capas, páginas de detalhes, temporadas e episódios;
- subcategorias em abas e player integrado com fallback de codecs;
- interface 10-foot premium, criada para controle remoto;
- HLS, DASH, RTSP, mídia progressiva e fallback entre codecs do aparelho via AndroidX Media3;
- H.265/HEVC, H.264, VP8, VP9 e AV1 quando suportados pelo hardware/Android da TV;
- cache local com atualização diária padrão, semanal, mensal ou em toda abertura;
- cache incremental e catálogo pré-organizado para navegação rápida mesmo em listas muito grandes;
- proteção +18 por senha, ocultação opcional de filmes `CINEMA` e ordenação por ano, A-Z ou mais assistidos;
- áreas Infantil e Nacional, categorias consolidadas, busca integrada e Minha Lista organizada por tipo;
- release otimizado com R8 e remoção de recursos não usados.

## Gerar o APK

Requisitos: JDK 17 e Android SDK 36.

```bash
./gradlew assembleDebug
```

O APK será criado em `app/build/outputs/apk/debug/app-debug.apk`.

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
