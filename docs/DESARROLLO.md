# Desarrollo

## Requisitos

- JDK 17 o superior (Gradle usa un toolchain 17).
- Android SDK (no se versiona).

## Instalar el Android SDK (Linux)

```bash
mkdir -p $HOME/android-sdk/cmdline-tools
curl -O https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip
unzip commandlinetools-linux-16111833_latest.zip -d tmp
mv tmp/cmdline-tools $HOME/android-sdk/cmdline-tools/latest
yes | $HOME/android-sdk/cmdline-tools/latest/bin/sdkmanager --licenses
$HOME/android-sdk/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.1.0"
echo "sdk.dir=$HOME/android-sdk" > local.properties   # ya ignorado por git
```

## Compilar y probar

```bash
./gradlew :app:assembleDebug test
```

Los APK se generan por ABI en `app/build/outputs/apk/debug/`
(por ejemplo `app-arm64-v8a-debug.apk`).
