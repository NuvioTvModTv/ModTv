# Arquivos do port

Base: NuvioTV 1.0.0, 9f17e8bf4abc799dc8c832d2894a8b3b166e4353.

## Modificados

- `app/src/main/AndroidManifest.xml`
- `app/src/main/java/com/nuvio/tv/MainActivity.kt`
- `app/src/main/java/com/nuvio/tv/ui/navigation/NuvioNavHost.kt`
- `app/src/main/java/com/nuvio/tv/ui/navigation/Screen.kt`

## Criados

- `.github/workflows/live-tv-validation.yml`
- `LIVE_TV_FILES.md`
- `LIVE_TV_PORT.md`
- `app/src/androidTest/java/com/nuvio/tv/data/livetv/EpgDatabaseTest.kt`
- `app/src/androidTest/java/com/nuvio/tv/data/livetv/LiveRepositoryTest.kt`
- `app/src/main/java/com/nuvio/tv/data/livetv/EpgDatabase.kt`
- `app/src/main/java/com/nuvio/tv/data/livetv/EpgMatcher.kt`
- `app/src/main/java/com/nuvio/tv/data/livetv/LiveRepository.kt`
- `app/src/main/java/com/nuvio/tv/data/livetv/LiveSections.kt`
- `app/src/main/java/com/nuvio/tv/data/livetv/LiveSettings.kt`
- `app/src/main/java/com/nuvio/tv/data/livetv/XmlTvReader.kt`
- `app/src/main/java/com/nuvio/tv/domain/model/livetv/LiveModels.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/livetv/LiveTvScreen.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/livetv/LiveTvViewModel.kt`
- `app/src/main/res/values-pt-rBR/live_tv.xml`
- `app/src/main/res/values-pt/live_tv.xml`
- `app/src/main/res/values/live_tv.xml`
- `app/src/test/java/com/nuvio/tv/data/livetv/LiveTvTest.kt`
- `tools/live-tv/bootstrap.py`
- `tools/live-tv/checkpoint.py`
- `tools/live-tv/create-fixture.py`
- `tools/live-tv/emulator-worker.py`
- `tools/live-tv/run-gradle.py`
- `tools/live-tv/test-environment.init.gradle`

## Empacotamento final

- `BUILD_WINDOWS.md`
- `FINAL_STATUS.md`
- `tools/live-tv/unminified-release.init.gradle`
