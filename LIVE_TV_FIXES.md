# Correções limitadas: login, catálogos e reprodução

## Login

O release anterior gerava SUPABASE_URL/ANON_KEY/FALLBACK_URL vazios sem local.properties. Os três valores públicos foram confirmados no código DEX do APK oficial NuvioTV 1.0.0 (arquivo config/nuvio-official-client.properties registra origem e SHA256). A chave tem papel anon, não service_role. O Gradle usa essa configuração como padrão do release; local.properties não precisa conter as configurações públicas e continua podendo sobrescrevê-las.

Verificação real: POST /rest/v1/rpc/start_tv_login_session no backend oficial com os mesmos cabeçalhos do AuthManager retornou HTTP 200, código e web_url. Código/nonce temporários não foram registrados no relatório.

## Catálogos

O fork possui isTvCatalog(type,id,name), porém a iteração de carregamento também inclui todos os catálogos selecionados. O port agora aplica o predicado do fork a cada catálogo. Movie/series sem indicação de TV são excluídos; movie/series usados para canais continuam aceitos quando ID/nome indica TV/live/canais. Nenhuma interface foi alterada.

## Reprodução

O fluxo anterior consultava somente /stream e não fazia fallback inline/meta, não conferia suporte do manifesto e não fazia probe de MIME para URLs HLS sem extensão/redirecionadas. Agora usa o manifesto do addon de origem, supportsStreamResource, AddonApi e os mappers existentes; se necessário lê streams do meta ou vídeo correspondente. Preserva url/externalUrl/sources e proxyHeaders (User-Agent, Referer, Cookie).

O mesmo Media3/PlayerMediaSourceFactory passa a receber filename, headers de resposta e MIME obtido pelo probe existente. A preparação assíncrona é cancelada ao trocar canal/sair. Logs LiveTV identificam stage=stream_resolution, meta_fallback, addon_resolution, player_prepare ou media3, com tipo de exceção/código HTTP/erro Media3, sem URLs, tokens, cookies ou mensagens brutas de exceção.

Sem amostra de addon/canal do aparelho nem log anterior do Media3, não é possível afirmar que essas eram todas as causas dos canais específicos do usuário. Foram corrigidas as falhas identificáveis no fluxo. Reprodução real no Fire TV continua exigindo teste no aparelho; nenhum emulador foi configurado nesta correção.

## Arquivos da correção

- app/build.gradle.kts
- config/nuvio-official-client.properties (novo, somente configuração pública cliente)
- app/src/main/java/com/nuvio/tv/data/livetv/LiveRepository.kt
- app/src/main/java/com/nuvio/tv/data/remote/dto/MetaResponseDto.kt
- app/src/main/java/com/nuvio/tv/ui/screens/livetv/LiveTvViewModel.kt
- app/src/test/java/com/nuvio/tv/data/livetv/LiveTvFixesTest.kt (duas verificações focadas)
- app/src/androidTest/java/com/nuvio/tv/data/livetv/LiveRepositoryTest.kt (manifesto do fixture declara recursos utilizados)
- LIVE_TV_FIXES.md (este registro)

Build: mesmo fallback tools/live-tv/unminified-release.init.gradle, fullRelease, minSdk 24. Sem refazer EPG, interface ou workflow. Resultado final da compilação/testes consta da entrega.

Empacotamento: restaurados sem alterações da tag 1.0.0 os arquivos app/src/full/java/com/nuvio/tv/core/build/AppFeaturePolicy.kt, app/src/playstore/java/com/nuvio/tv/core/build/AppFeaturePolicy.kt e app/src/main/java/com/nuvio/tv/core/build/TrailerPlaybackMode.kt. O filtro do ZIP anterior havia excluído incorretamente diretórios fonte chamados build. O ZIP corrigido exclui apenas saídas Gradle dos módulos, preservando esses fontes.
