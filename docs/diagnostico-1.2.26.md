# Correções 1.2.26

- Remove o marcador `2 ` no início de categorias como `2 Terror` e `2 Copmedia`, inclusive depois de `Filmes`. Preserva números normais no final (`Ação 2`) e anos. Mantém a limpeza anterior de sobrescritos decorativos.
- A prateleira da página inicial chama-se **Assistidos recentemente** e inclui títulos concluídos e interrompidos, ordenados pela última reprodução salva.
- Corrige a exclusão de episódios Xtream do histórico: a URL não é persistida por segurança, mas pode ser reconstruída pelo ID. O histórico agora preserva também o ID do stream e a extensão para reconstruir corretamente episódios MKV e outros formatos.
- Progresso é salvo a cada dez segundos de reprodução, ao pausar, quando o aplicativo vai para segundo plano e ao sair do player.
- Ao reproduzir um título interrompido a partir de dois segundos, oferece **Continuar** ou **Reiniciar**, com a posição salva. Títulos concluídos permanecem no histórico e iniciam do começo.
- Histórico antigo é mantido; os novos campos são opcionais e não requerem recriar a biblioteca.

Validação: testes de limpeza de categoria, histórico Xtream com episódio interrompido e filme concluído sem URLs salvas, reconstrução de URL MKV e regressões do catálogo/controle remoto no Android TV.
