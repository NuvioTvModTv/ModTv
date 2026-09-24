# Port nativo de Canais + EPG — em validação

## Bases e escopo confirmado

Android: tag `1.0.0`, commit `9f17e8bf4abc799dc8c832d2894a8b3b166e4353`.
Referência Desktop: `MarechalSp/NuvioTvMod`, commit `82cc79de7d6b4a80bbe2e1d5fb01e73fbf1bda0b`.
Ancestral Desktop compartilhado: `48e1ca3a8eb21708031d6dc9c690d6098e07133a`.
Commits da funcionalidade identificados na investigação: `4edab551`, `f5290c87`, `11d8ba76`, `82cc79de`; ponte PiP nativa em `01f22576`.

O fork obtém canais de catálogos JSON dos addons instalados. Não possui importador de playlists M3U, arquivos locais ou atributos `tvg-id`, `tvg-name`, `tvg-logo`, `group-title`. URLs M3U8/HLS são fontes de reprodução, não listas de canais importadas. Nenhum importador fictício foi acrescentado. Instale/configure o addon IPTV no gerenciador existente e selecione-o em TV → Fontes de canais.

| Comportamento confirmado | Integração Android |
|---|---|
| Múltiplos addons/catálogos, até 15 páginas | AddonApi e mappers existentes; cache por perfil/catálogo |
| Categorias, logos, busca, favoritos | Compose TV, Coil e DataStore por perfil |
| XMLTV remoto, gzip e múltiplas fontes | SAX incremental, SQLite e atualização de 6 horas |
| ID, normalização, aliases e canais regionais | Lógica Kotlin adaptada do TvEpgRepository |
| Programa atual/próximo, progresso e detalhes | Consultas indexadas por canal e intervalo |
| Lista, catálogo de cards e guia | LazyColumn/LazyRow com keys estáveis |
| Preview, tela cheia e troca de canal | Sessão Media3 reutilizada entre canais |
| URL, externalUrl, sources | Resolução no addon de origem |
| Headers do stream | behaviorHints.proxyHeaders.request, incluindo UA/Referer/Cookie |
| Favoritos e configurações | ProfileDataStoreFactory existente |
| Copiar URL / player externo | ClipboardManager / ACTION_VIEW |
| PiP e janelas Windows | API Android PiP quando o dispositivo anuncia suporte |

Não havia histórico dedicado de canais. A sessão ao vivo não grava filmes fictícios no histórico/Trakt. A descrição do canal continua disponível quando não há guia; não é convertida em programa com horários inventados.

## Mapa de dependências da referência

Dentro de `composeApp/src/commonMain/kotlin/com/nuvio/app/features/`:

- `tvchannels/TvChannelsScreen.kt`: estado e coordenação da interface.
- `tvchannels/TvChannelsModels.kt`: modelos, heurísticas e seções de catálogo.
- `tvchannels/TvChannelsRepository.kt`: AddonRepository, fetchCatalogPage e StreamParser.
- `tvchannels/TvChannelsSettingsRepository.kt` e `TvChannelsSettingsStorage.kt`: preferências e favoritos; implementações específicas por plataforma.
- `tvchannels/epg/TvEpgRepository.kt`, `TvEpgModels.kt`, `XmlTvParser.kt`, `EpgPlatform.kt`: fontes, cache, associação e parsing.
- `tvchannels/components/TvAddonsSelectionModal.kt`, `TvEpgManagementModal.kt`: seleção e gerenciamento das fontes.
- `TvChannelsCatalogView.kt`, `TvChannelListItem.kt`, `TvEpgTimelineGrid.kt`, `TvChannelScheduleModal.kt`: modos de navegação e guia.
- `TvChannelPreviewPanel.kt`, `TvChannelVideoPlayer.kt`, `TvChannelFullscreenHud.kt`: reprodução.
- `settings/TvChannelsSettingsPage.kt`: preferências de TV.

`desktopMain/.../EpgPlatform.desktop.kt`, `TvChannelsSettingsStorage.desktop.kt`, `TvModalDialog.desktop.kt` e `features/player/desktop/DesktopPlayerPipWindow.kt` dependem da plataforma Desktop. Não foram incorporados ao APK. Foram substituídos por OkHttp/SQLite/DataStore/Compose/Media3/APIs Android.

## Arquitetura Android

`MainActivity`/drawer → `Screen.LiveTv`/`NuvioNavHost` → `LiveTvScreen` → `LiveTvViewModel` (Hilt, SavedStateHandle, StateFlow).

O ViewModel usa `LiveRepository`, `LiveSettings` e `EpgDatabase`. O repositório reutiliza AddonApi, DTOs, mappers, Moshi e a configuração de transporte existente. Remove interceptadores de logging no cliente das fontes privadas. Downloads e parsing rodam em Dispatchers.IO. As configurações pertencem ao perfil ativo, usando as fábricas de DataStore existentes.

O player utiliza os AARs Media3 já distribuídos no projeto e `PlayerMediaSourceFactory`. Uma sessão ExoPlayer atende às mudanças de canal; não é criada uma implementação WebView nem um player Desktop. Buffer alvo de 12 MiB e limite temporal de 12 segundos reduzem a pressão de memória. O player cede os recursos ocupados pelos trailers e é liberado ao sair da tela.

SQLite WAL guarda o guia. Uma atualização inteira é transacional: XML inválido ou download interrompido preservam o último cache válido. Retenção: 48 horas anteriores e 96 futuras; apenas programas dos canais associados são armazenados. Consultas da UI são limitadas aos canais visíveis e ao canal em reprodução. O guia detalhado usa lista lazy, com limite de 2.000 programas por canal.

Limites de entrada: catálogo 16 MiB/resposta, 20.000 canais, 15 páginas, cache em disco 32 MiB; EPG 64 MiB recebido/256 MiB descomprimido, 30.000 declarações de canais, profundidade XML 32. Metadados de canais e programas têm comprimento limitado; até quatro nomes XMLTV por canal. O cache de resolução guarda somente os dois canais mais recentes por até dois minutos. DTDs são rejeitadas e entidades externas desabilitadas. Esses limites são deliberados para dispositivos com pouca memória e produzem erro recuperável, preservando cache.

Não há mudança no minSdk 24, ABIs nem no updater. O seletor existente usa Build.SUPPORTED_ABIS. Nenhuma biblioteca nativa ou dependência exclusiva do Google Play Services foi adicionada.

## Compilação

Requisitos: JDK 17, SDK Android 36, Build Tools 35, Gradle wrapper 8.13. Configure `sdk.dir` em `local.properties`. As integrações do upstream requerem suas configurações legítimas (incluindo NUVIO_SUPABASE_URL/NUVIO_SUPABASE_ANON_KEY, TMDB, Trakt etc.); não há credenciais de produção neste código.

```sh
./gradlew :app:compileFullDebugKotlin :app:testFullDebugUnitTest
CI_USE_DEBUG_SIGNING=true ./gradlew :app:assembleFullRelease
```

O segundo comando usa o mecanismo já existente para assinar o release com chave de desenvolvimento. Para distribuição durável, configure a chave release própria conforme app/build.gradle.kts e preserve-a. Uma assinatura diferente não atualiza por cima do aplicativo oficial. Não desinstale o aplicativo oficial sem antes preservar seus dados.

Saída esperada: `app/build/outputs/apk/full/release/`, incluindo armeabi-v7a e universal. A existência e os hashes dos APKs serão registrados somente após build bem-sucedido.

```sh
adb connect ENDERECO_DO_FIRE_TV:5555
adb install -r app/build/outputs/apk/full/release/app-full-armeabi-v7a-release.apk
```

Ative a depuração ADB no aparelho e autorize o computador. O APK prioritário do AFTSSS é armeabi-v7a. O universal é alternativa; arm64-v8a não é o APK desse modelo.

## Validação e limites ainda abertos

Estado atual: compilação e testes em andamento. Não considerar o port finalizado.

Foram adicionados testes JVM para datas/timezones, XML malformado, entidades, cancelamento, limites, parsing incremental, matching e URLs. O teste instrumentado usa o parser Android real e verifica rollback do SQLite. Resultados ainda pendentes.

Emulador Android TV API 28, 1 GB, x86, sem aceleração de CPU disponível. Isso não substitui um teste no Fire TV físico com armeabi-v7a, nem comprova desempenho ou ausência de leaks nesse aparelho.

RTMP/MMS e outros esquemas aceitos como candidatos no Desktop dependem de módulos externos ao Media3 atual; falhas são recuperáveis e há abertura em player externo. PiP depende da capacidade anunciada pelo Fire OS. O cache de programas pressupõe a ordem XMLTV padrão (declarações de canais antes dos programas). A seleção de um alias ambíguo ainda segue as heurísticas da referência.

## Build persistente opcional

O workflow manual `.github/workflows/live-tv-validation.yml` compila os releases, os testes instrumentados e executa os testes JVM em GitHub Actions. O job continua marcado como falho se os testes falharem; os relatórios e APKs que chegaram a ser gerados são preservados. Ele não publica um release nem modifica branches. Não foi executado nesta sessão, pois o GitHub ainda não estava conectado.

O arquivo `tools/live-tv/test-environment.init.gradle` configura o runner instrumentado da variante release e carrega explicitamente o agente ByteBuddy para o MockK. Os testes JVM usam timezone UTC para resultados reproduzíveis. As integrações opcionais do upstream podem ser configuradas com o secret `LOCAL_PROPERTIES_BASE64`; não use chaves administrativas de backend no APK.

A lista de programação começa pelo programa atual. O guia mantém consultas separadas
para agora/próximo e para a janela de duas horas selecionada, evitando que a
consulta de horários antigos exclua a programação atual pelo limite de resultados.
