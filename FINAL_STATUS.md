# Entrega do port Live TV

Base: NuvioTV 1.0.0, commit 9f17e8bf4abc799dc8c832d2894a8b3b166e4353. Referência funcional NuvioTvMod 82cc79de7d6b4a80bbe2e1d5fb01e73fbf1bda0b. Sem conversão Desktop/WebView.

## Implementado

Seção nativa Kotlin/Compose integrada à navegação; canais via catálogos de addons; categorias, busca, favoritos por perfil; fontes/streams e headers; múltiplos XMLTV/gzip; associação por ID, nomes e aliases; agora/próximo, progresso e guia; cache SQLite incremental e processamento IO; sessão Media3 reutilizada; preview/tela cheia, ocultação do menu lateral, foco/scroll restauráveis, controles D-pad, lifecycle e keepScreenOn. A lista de programação começa no programa atual; consultas separadas preservam agora/próximo ao navegar pelo guia. minSdk 24 e ABIs originais preservados.

A referência não tem importador M3U bruto/local: os canais vêm de addons. M3U8 é formato de stream. Não se afirma suporte a importação de listas que o fork não implementa.

## Evidência anterior preservada

- Compilação Kotlin release: passou antes das duas últimas alterações de consulta do guia. O build final precisa confirmar o conjunto completo.
- Java/Hilt release: passou na execução anterior.
- Lint vital: passou sem bloqueios na execução anterior.
- JVM: execução anterior de 1276 testes, 265 falhos, 1 ignorado. A inicialização do EpgMatcher causou falha dos 13 testes novos e foi corrigida; várias falhas MockK eram de autoanexação ByteBuddy e receberam configuração de agente. Não há resultado posterior completo preservado que comprove a correção. As demais falhas não foram classificadas como regressões/preexistentes sem comparação comprovada.
- Instrumentados: não executados no dispositivo.
- R8 normal: primeiro processo encerrado por OOM; segunda tentativa falhou em ZIP de recursos intermediário corrompido. O reinício posterior do ambiente apagou os intermediários e relatórios brutos. Não há release otimizado validado.
- O emulador API 28/1 GB chegou a iniciar, mas isso não valida o aplicativo. Instalação, abertura, D-pad, reprodução, Voltar e foco ainda não têm resultado de execução confirmado.

## Limitações reais

Não há teste no Fire TV físico, medição representativa de desempenho/1 GB, análise dinâmica de leaks ou contagem de recomposições. Não declarar que os critérios de execução estão aprovados.

Configurações próprias de Supabase/TMDB/Trakt e outras integrações do upstream não acompanham o projeto; essas integrações dependem de configuração legítima. A inicialização sem configurações de produção ainda requer teste.

XMLTV com DTD é rejeitado; espera-se a ordem padrão de canais antes dos programas. Matching ambíguo segue heurísticas. Há limites de tamanho/quantidade e janela de retenção descritos em LIVE_TV_PORT.md. RTMP/MMS não têm módulo nativo adicionado; abertura em player externo é alternativa. PiP depende do suporte do dispositivo. O guia não foi validado visualmente no aparelho.

## Compilação e instalação

Consulte BUILD_WINDOWS.md para comandos exatos no Windows/Android Studio, assinatura, caminhos e ADB. O fallback unminified-release.init.gradle desliga somente otimização/redução de recursos na execução; o build normal permanece inalterado. A assinatura de desenvolvimento não substitui por atualização a assinatura oficial.

Resultado da tentativa final e metadados de APKs serão registrados no relatório de entrega, sem substituir pendências por suposições.

## Resultado final confirmado

BUILD SUCCESSFUL, 18m34s, 55 tarefas executadas. Kotlin e Java/Hilt do código final passaram. Gerados os cinco APKs fullRelease (armeabi-v7a, Universal, arm64-v8a, x86 e x86_64), sem minificação/redução de recursos, assinados com a configuração de desenvolvimento. R8 foi desativado explicitamente no fallback. Lint vital não foi repetido nesta tentativa; havia passado na execução anterior. Testes JVM/instrumentados não foram repetidos. Não houve instalação/abertura confirmada.

Warnings relevantes: perfil de startup contém referências ausentes e D8 distribuiu classes entre vários DEX; isso não impediu o build. Há avisos upstream de deprecação Kotlin/Compose/Moshi e extractNativeLibs. Não foram feitas refatorações para silenciar avisos.

Metadados dos APKs e hashes constam no relatório entregue. Os binários entregues não são releases otimizados nem uma assinatura oficial do Nuvio.
