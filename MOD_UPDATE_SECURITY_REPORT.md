# MOD 1.1 — Atualizações e auditoria estática

Base upstream: NuvioTV 1.0.0. Versão pública do MOD: 1.1.
Repositório autorizado: https://github.com/NuvioTvModTv/ModTv

## Atualizador

O workflow main.yml é o único caminho de publicação do MOD. Push em main/master ou workflow_dispatch gera uma Release pública. Não publica por pull_request. A execução é serializada entre branches, sem cancelar publicação em andamento. O versionCode usa o maior entre segundos desde 2020-01-01 UTC e o maior build nas tags das Releases + 1. O valor local permanece 1062; o cálculo temporal na auditoria estava acima de 212 milhões e abaixo de 2.100.000.000. Uma API GitHub indisponível impede gerar/publicar versão potencialmente conflitante. A tag é mod-v1.1-b<versionCode>. O versionName é lido do Gradle e permanece 1.1.

O CI usa JDK 17, um worker, sem paralelismo e JVM de 3 GB. A tarefa assembleFullRelease gera os splits ARM e o Universal com ambas as ABIs ARM; não usa a propriedade que restringiria também o conteúdo do Universal a 32 bits. Somente os assets armeabi-v7a e Universal são publicados, além do manifesto. Nenhum APK é modificado depois da assinatura.

Antes de publicar, a rotina valida assinatura com apksigner; igualdade do certificado entre APKs; applicationId com.nuvio.tv; versionCode e versionName reais; minSdk <= 28; ABIs; ausência de testOnly/debuggable. A falha de qualquer etapa interrompe publicação. A CLI gh envia os assets antes de concluir a publicação. Notas são geradas pelo GitHub, acrescidas de versão, build e base upstream.

Assets públicos separados:
- NuvioTV-armeabi-v7a.apk
- NuvioTV-universal.apk
- update.json

Manifesto: https://github.com/NuvioTvModTv/ModTv/releases/latest/download/update.json
Downloads: mesmo prefixo releases/latest/download/ e os nomes acima. Nenhum ID de Artifact/Release/run faz parte do protocolo.

O updater existente foi reaproveitado. A consulta limitada a 64 KiB ocorre em IO e fica cacheada por sessão, inclusive falhas. Consulta manual e recuperação de falha de download podem revalidar. Não há polling. Compara exclusivamente remote.versionCode > BuildConfig.VERSION_CODE. O banner existente pode ser dispensado. Build.SUPPORTED_ABIS escolhe armeabi-v7a se compatível, Universal caso contrário.

O APK é escrito em .part, com buffer de 32 KiB e SHA-256 incremental. Progresso é limitado a quatro atualizações por segundo. Hash, package e versionCode são verificados antes de disponibilizar Instalar. Downloads inválidos são apagados. Corridas com uma nova Release em /latest são detectadas pelo hash; após falha o manifesto é revalidado para a próxima tentativa. O instalador só abre por ação do usuário. O FileProvider, permissão REQUEST_INSTALL_PACKAGES e retorno de autorização de fontes desconhecidas existentes foram mantidos. Falhas ao abrir configurações/instalador são tratadas.

Assinatura: quatro Secrets NUVIO_KEYSTORE_BASE64, NUVIO_KEYSTORE_PASSWORD, NUVIO_KEY_ALIAS, NUVIO_KEY_PASSWORD. Somente referências existem no workflow. A keystore é restaurada com permissão restrita em RUNNER_TEMP; as quatro variáveis NUVIO_RELEASE_* são passadas por env ao Gradle; limpeza sempre é tentada. Não existe CI_USE_DEBUG_SIGNING=true no workflow de publicação. Não foram adicionados sdkmanager/setup-android. Nenhum PAT é necessário; GH_TOKEN vem do token temporário do GitHub.

A primeira instalação que contém este updater precisa ser distribuída/instalada por você. Depois, builds com mesma chave, mesmo applicationId e versionCode maior poderão ser instalados pelo banner. O ZIP atual não prova que Secrets externos estão corretos; isso será verificado no seu CI. Não houve execução de build, APK, emulador ou Actions nesta auditoria.

## Auditoria — escopo e limites

Varredura estática do ZIP fornecido: 1.190 arquivos de texto na varredura inicial, além de 84 arquivos binários e 2.616 membros de arquivos compactados. Revisados candidatos de Kotlin/Java, Gradle, XML/JSON/properties, YAML, scripts, documentação, testes, configurações locais e bibliotecas. Os testes usam credenciais fictícias e servidores locais, não contas reais. A varredura final de padrões de alta confiança não encontrou payload privado remanescente. Isso não equivale a auditoria do backend, do histórico Git remoto, de todos os comportamentos dinâmicos ou a prova criptográfica de ausência de segredos desconhecidos. Não houve acesso aos seus Secrets do GitHub, nem reescrita de histórico.

### 1. Senhas de assinatura fixas
TIPO: dois fallbacks de senha de keystore/chave, hardcoded.
ARQUIVO: app/build.gradle.kts.
FINALIDADE APARENTE: permitir assinatura local sem configuração externa.
POR QUE NÃO PODIA SER PÚBLICO: senha de assinatura não pode ser distribuída como padrão de produção.
AÇÃO TOMADA: removidos ambos os valores e o alias padrão; preservada leitura por ambiente/local.properties. Nenhum valor foi movido ao APK. Busca adicional confirmou que os valores removidos não permaneciam em outros arquivos do ZIP.
ROTACIONAR/REVOGAR: SIM — alterar senhas das keystores que ainda utilizem esses fallbacks. Isso não exige substituir a chave/certificado de assinatura definitivo. Não foi encontrada a chave privada correspondente. Remover do source não remove cópias/histórico já publicados.

### 2. Estado local do Android Studio
TIPO: caminho pessoal do ambiente de desenvolvimento.
ARQUIVO: .idea/deploymentTargetSelector.xml.
FINALIDADE APARENTE: seleção local de dispositivo/alvo de execução.
POR QUE NÃO PODIA SER PÚBLICO: expunha diretório de usuário sem utilidade para compilar o projeto público.
AÇÃO TOMADA: arquivo removido do ZIP e protegido no .gitignore.
ROTACIONAR/REVOGAR: NÃO.

### 3. Logs e estado transitório do compilador
TIPO: logs locais com caminhos de desenvolvimento e arquivo de sessão.
ARQUIVOS:
- .kotlin/errors/errors-1771150020848.log
- .kotlin/errors/errors-1771097357562.log
- .kotlin/errors/errors-1771097175710.log
- .kotlin/errors/errors-1771015105164.log
- .kotlin/errors/errors-1771150015391.log
- .kotlin/errors/errors-1771100377496.log
- .kotlin/errors/errors-1771021522904.log
- .kotlin/errors/errors-1769668942713.log
- .kotlin/sessions/kotlin-compiler-6121534536708270396.salive
FINALIDADE APARENTE: diagnóstico local de compilações anteriores.
POR QUE NÃO PODIA SER PÚBLICO: logs não são source e podiam identificar a máquina/diretórios do desenvolvedor.
AÇÃO TOMADA: removida a pasta .kotlin do pacote; regra de exclusão preservada.
ROTACIONAR/REVOGAR: NÃO. Não foi identificada credencial de autenticação nesses logs.

### 4. Caminhos pessoais em bibliotecas nativas
TIPO: nome de usuário Windows em caminhos de compilação Rust/Cargo, 13 ocorrências em cada arquivo.
ARQUIVOS:
- DV7/libdovi/android-arm64/lib/libdovi.a
- DV7/libdovi/android-armeabi-v7a/lib/libdovi.a
- DV7/libdovi/android-x86/lib/libdovi.a
- DV7/libdovi/android-x86_64/lib/libdovi.a
FINALIDADE APARENTE: caminhos embutidos em mensagens de diagnóstico de dependências Rust.
POR QUE NÃO PODIA SER PÚBLICO: identificador pessoal da máquina de compilação sem necessidade funcional.
AÇÃO TOMADA: somente os segmentos de usuário foram substituídos por texto neutro de comprimento idêntico. Validado que todas as alterações se limitam a dados de seções ELF sem flag executável; tamanhos, offsets e bytes de código executável preservados. Bibliotecas não foram recompiladas e nenhuma assinatura de APK foi alterada.
ROTACIONAR/REVOGAR: NÃO.

### 5. Possível vazamento em logs de runtime
TIPO: funções de diagnóstico que retornavam dados sem mascaramento e logs Android legados.
ARQUIVOS: app/src/main/java/com/nuvio/tv/core/logging/LogDiagnostics.kt; app/proguard-rules.pro.
FINALIDADE APARENTE: diagnóstico de login, URLs, respostas e exceções.
POR QUE NÃO PODIA SER PÚBLICO: logs poderiam conter códigos/nonce de login e URLs autenticadas fornecidos durante o uso.
AÇÃO TOMADA: helpers agora ocultam valores, URLs e corpos; resumo de exceções mantém somente classes. Logs android.util.Log são eliminados no FullRelease otimizado. Não foi alterada a autenticação nem o tráfego real.
ROTACIONAR/REVOGAR: NÃO para o source analisado, pois não foi encontrada credencial concreta nesses helpers. Se logs antigos de runtime foram divulgados, revogue as sessões/tokens expostos. Builds debug ou releases deliberadamente sem R8 não têm a eliminação global de logs; não publique seus logs brutos.

### 6. Workflows legados de assinatura/configuração
TIPO: caminhos alternativos de CI com assinatura de debug, configuração local Base64 e publicação legada.
ARQUIVOS: .github/workflows/android-release.yml; .github/workflows/live-tv-validation.yml; .github/workflows/pr-full-debug-build.yml.
FINALIDADE APARENTE: builds/validação do upstream e etapas anteriores do port.
POR QUE NÃO PODIA PERMANECER NO FLUXO PÚBLICO ATUAL: podia gerar APKs com outra assinatura e injetar propriedades arbitrárias/privadas no cliente; não continha valores reais de Secret.
AÇÃO TOMADA: removidos os três workflows redundantes; distribuição centralizada em main.yml. Os Secrets externos não foram modificados.
ROTACIONAR/REVOGAR: NÃO — eram referências, não credenciais expostas. Caso LOCAL_PROPERTIES_BASE64 tenha incorporado uma credencial realmente privada em APK antigo, essa credencial deve ser revogada e sua função movida ao backend.

### 7. Documentação e exclusões
TIPO: modelo de caminho Windows e instruções antigas de assinatura de debug (não eram dados pessoais reais).
ARQUIVOS: BUILD_WINDOWS.md; .gitignore.
FINALIDADE APARENTE: orientar builds locais e excluir arquivos transitórios.
POR QUE AJUSTAR: evitar reutilizar assinatura de desenvolvimento e commits acidentais de material privado.
AÇÃO TOMADA: caminho genérico C:/Android/Sdk; instruções com assinatura externa; exclusão de jks/keystore/p12/pfx/key/pem, envs, propriedades privadas e TXT de keystore/Base64. Modelos .example/.template sem valores reais continuam permitidos.
ROTACIONAR/REVOGAR: NÃO.

### 8. Chave privada autossinada de teste TLS
TIPO: arquivo PKCS12 com chave privada exclusiva de testes localhost; não era assinatura do aplicativo nem credencial de produção.
ARQUIVO: app/src/test/resources/subtitle-redirect-test.p12.
FINALIDADE APARENTE: simular HTTPS nos testes de proteção de credenciais de legendas.
POR QUE REMOVER: evitar distribuir material de chave privada fixo no source público, conforme solicitado.
AÇÃO TOMADA: removido o arquivo; SubtitleCredentialScopeTest.kt gera um certificado efêmero com o keytool do JDK somente quando o teste é executado, senha aleatória via ambiente do subprocesso e limpeza em finally. Nenhuma chave ou teste foi gerado/executado nesta execução.
ROTACIONAR/REVOGAR: NÃO — fixture autossinada de localhost, sem uso de produção identificado.

## Configurações públicas e falsos positivos mantidos

- config/nuvio-official-client.properties: URL Supabase, chave JWT com role=anon e URL pública dos avatares são configuração de cliente, não service-role. Mantidas para preservar login/QR/avatares. As políticas RLS do servidor não foram auditadas.
- InAppYouTubeExtractor.kt: chave de cliente web/fallback para extração pública; não é chave administrativa. Mantida.
- local.example.properties: placeholders, identificadores públicos e URLs de cliente; nenhum valor privado real.
- Testes: tokens sintéticos, IPs localhost/reservados e fixtures sem chaves privadas. Não são credenciais de produção.
- tools/live-tv/bootstrap.py: senha padrão pública de truststore Java, não a senha da keystore de assinatura. Script legado não é chamado pelo workflow; não foi executado e não foi reintroduzido no CI.
- nextlib-mediainfo-local.aar: strings BEGIN/END PRIVATE KEY pertencem a código de parsing criptográfico; não havia payload de chave privada associado. A biblioteca foi preservada.
- Baseline profiles: caminhos de classes como ui/screens/home não são diretórios pessoais. Preservados.
- Licenças/autoria e identificadores públicos do upstream preservados.

## Pontos externos que exigem atenção

O source mantém integração legada opcional TRAKT_CLIENT_SECRET lendo local.properties, mas nenhum valor foi encontrado no ZIP e o workflow novo não injeta essa propriedade. Um segredo verdadeiramente confidencial não pode ficar seguro em BuildConfig/APK; se for necessário como credencial privada, deve ser atendido por backend. Não configure esse tipo de segredo em propriedades que sejam compiladas no APK. Não foi feita uma migração de autenticação/Trakt nesta execução.

Não foi incluída keystore, Base64 real de keystore, senha dos seus GitHub Secrets, PAT ou service-role. Dados pessoais locais identificados foram removidos/anonimizados. Nenhuma funcionalidade Live TV foi modificada. O schema do EPG, cache, Media3, headers e assinatura definitiva foram preservados.

## Arquivos alterados
- BUILD_WINDOWS.md
- .gitignore
- app/proguard-rules.pro
- app/build.gradle.kts
- DV7/libdovi/android-arm64/lib/libdovi.a
- DV7/libdovi/android-x86_64/lib/libdovi.a
- DV7/libdovi/android-x86/lib/libdovi.a
- DV7/libdovi/android-armeabi-v7a/lib/libdovi.a
- app/src/full/java/com/nuvio/tv/updater/ApkDownloader.kt
- app/src/full/java/com/nuvio/tv/updater/UpdateRepository.kt
- app/src/full/java/com/nuvio/tv/updater/UpdateViewModel.kt
- app/src/full/java/com/nuvio/tv/updater/model/AppUpdate.kt
- app/src/full/java/com/nuvio/tv/updater/ui/UpdateBanner.kt
- app/src/main/java/com/nuvio/tv/core/logging/LogDiagnostics.kt
- .github/workflows/main.yml
- app/src/test/java/com/nuvio/tv/ui/screens/player/SubtitleCredentialScopeTest.kt

Novos arquivos:
- app/src/main/res/values/mod_update.xml
- app/src/main/res/values-pt/mod_update.xml
- app/src/main/res/values-pt-rBR/mod_update.xml
- tools/release/prepare.py
- MOD_UPDATE_SECURITY_REPORT.md (este relatório)

## Validação

Somente estática: sintaxe Python/AST, YAML, bash -n, estrutura Kotlin/XML, consistência de URLs/versões/assinatura, varredura de credenciais e comparação do pacote com o ZIP original. Nenhum APK, build, R8, Actions, SDK/NDK, emulador ou instalação foi executado. A publicação, instalação, D-pad do banner e atualização no Fire TV precisam de validação no seu próximo build real.

Referências técnicas consultadas:
- https://cli.github.com/manual/gh_release_create
- https://developer.android.com/studio/publish/versioning
