# Compilar no Windows (PowerShell)

Abra a pasta NuvioTV no Android Studio. Use JDK 17, SDK Platform 36, Build Tools 35.0.0 e NDK (Side by side) 29.0.14206865. O wrapper usa Gradle 8.13. Instale esses componentes no SDK Manager e aceite as licenças. Configure o Gradle JDK como 17. Não é necessário converter o projeto.

Na raiz NuvioTV, crie local.properties, por exemplo:

```properties
sdk.dir=C:/Android/Sdk
```

As integrações de backend exigem suas próprias configurações legítimas; o ZIP não inclui credenciais de produção. Este arquivo é local e não deve ser publicado com segredos.

## Release normal, otimizado

```powershell
$env:JAVA_HOME = 'C:\CAMINHO\jdk-17'
# Configure NUVIO_RELEASE_STORE_FILE, NUVIO_RELEASE_KEY_ALIAS,
# NUVIO_RELEASE_STORE_PASSWORD e NUVIO_RELEASE_KEY_PASSWORD no ambiente local.
.\gradlew.bat --no-daemon --max-workers=1 '-Pkotlin.compiler.execution.strategy=in-process' '-Dorg.gradle.jvmargs=-Xmx5120m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8' :app:assembleFullRelease
```

A assinatura de distribuição usa exclusivamente a sua chave configurada externamente. Nunca publique valores dessas variáveis.

A tarefa exata tanto para armeabi-v7a quanto para Universal é `:app:assembleFullRelease`: os dois são saídas ABI da mesma variante, não flavors com tarefas independentes. Também é gerado arm64-v8a.

Saídas:

- app/build/outputs/apk/full/release/app-full-armeabi-v7a-release.apk
- app/build/outputs/apk/full/release/app-full-universal-release.apk

## Fallback sem R8/minificação

```powershell
# Configure NUVIO_RELEASE_STORE_FILE, NUVIO_RELEASE_KEY_ALIAS,
# NUVIO_RELEASE_STORE_PASSWORD e NUVIO_RELEASE_KEY_PASSWORD no ambiente local.
.\gradlew.bat -I tools/live-tv/unminified-release.init.gradle --no-daemon --max-workers=1 '-Pkotlin.compiler.execution.strategy=in-process' '-Dorg.gradle.jvmargs=-Xmx5120m -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8' :app:assembleFullRelease
```

Essa opção mantém a variante release, porém desliga minificação e redução de recursos somente nessa execução. Os nomes/caminhos são os mesmos; identifique-os como releases não minificados. Não altera código funcional nem a configuração normal do projeto.

## Testes existentes

```powershell
.\gradlew.bat -I tools/live-tv/test-environment.init.gradle :app:testFullReleaseUnitTest
```

## Android Studio

Depois do Gradle Sync, escolha a variante fullRelease e execute `app > Tasks > build > assembleFullRelease` na janela Gradle (ou execute o comando acima no Terminal do Android Studio). A mesma tarefa produz os dois APKs. Para assinatura própria, use Build > Generate Signed App Bundle or APK > APK, sua chave e a variante fullRelease.

## Instalar no Fire TV

```powershell
adb connect IP_DO_FIRE_TV:5555
adb install -r .\app\build\outputs\apk\full\release\app-full-armeabi-v7a-release.apk
```

Autorize a depuração no Fire TV. minSdk do projeto: 24, compatível com API 28. O AFTSSS usa armeabi-v7a. Uma assinatura diferente não atualiza o APK oficial por cima; preserve seus dados antes de qualquer desinstalação.
