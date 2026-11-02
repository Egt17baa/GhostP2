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

## IPtProxy (puentes de Tor, R30)

Los puentes usan `com.netzarchitekten:IPtProxy:5.7.0` de Maven Central, con la versión fija en
`gradle/libs.versions.toml` (`iptproxy`). El proyecto no usa `verification-metadata.xml` de Gradle, así
que la suma de referencia está aquí; compruébala al instalar el entorno y en cada cambio de versión:

| Archivo | SHA-256 |
|---|---|
| `IPtProxy-5.7.0.aar` | `567130d6760c64f823c62150d9da7978d6f8d8ef8766e2a5b4f8eb5cdaba5b51` |
| `IPtProxy-5.7.0.pom` | `f929c8ce3b3a571908730aa0e1e6b33b60380bdc91af215461b913e00fc0722e` |

```bash
# El que ha descargado Gradle (tras compilar una vez):
find ~/.gradle/caches/modules-2/files-2.1/com.netzarchitekten/IPtProxy/5.7.0 -name '*.aar' -exec sha256sum {} \;
# O directamente de Maven Central:
curl -sSLO https://repo1.maven.org/maven2/com/netzarchitekten/IPtProxy/5.7.0/IPtProxy-5.7.0.aar
echo "567130d6760c64f823c62150d9da7978d6f8d8ef8766e2a5b4f8eb5cdaba5b51  IPtProxy-5.7.0.aar" | sha256sum -c
```

Debe decir `IPtProxy-5.7.0.aar: OK`. Si no coincide, no compiles con ella. Al actualizar IPtProxy (o
kmp-tor, por la reflexión de la configuración de puentes, PROTOCOLO §14.11):

- anota aquí las sumas nuevas;
- revisa que `BridgeConfigTest` e `IptProxyControllerTest` siguen pasando;
- comprueba en el APK de versión que cada ABI lleva `lib/<abi>/libgojni.so` y que el dex conserva
  `go.*`, `IPtProxy.*` y los miembros de kmp-tor que usa la reflexión (`toLineItem$…`, `toSetting$…`);
- vuelve a hacer los pasos de puentes de [PRUEBA-MANUAL](PRUEBA-MANUAL.md#puentes-r30);
- actualiza [`THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md) y `LICENSES/Go-modules.txt` con los módulos
  de `go version -m` sobre el `libgojni.so` nuevo (versiones, licencias y etiquetas o commits del código
  fuente de la parte GPL-3.0); al cambiar otras dependencias, la tabla de `releaseRuntimeClasspath`.

## APK de versión

```bash
./gradlew :app:assembleRelease
```

Con R8 (minify) y un APK por ABI en `app/build/outputs/apk/release/`. Sin el bloque de dependencias
de Google en el APK ni en el AAB (`dependenciesInfo`, para F-Droid).

### Firma

La versión se firma solo si existen las cuatro variables de entorno:

| Variable | Contenido |
|---|---|
| `GHOSTP2_KEYSTORE_FILE` | Ruta al almacén de claves (`.jks`) |
| `GHOSTP2_KEYSTORE_PASSWORD` | Contraseña del almacén |
| `GHOSTP2_KEY_ALIAS` | Alias de la clave |
| `GHOSTP2_KEY_PASSWORD` | Contraseña de la clave |

Si falta alguna, `assembleRelease` genera `app-<abi>-release-unsigned.apk`. Nunca se versiona un
almacén de claves ni una contraseña.

Crear la clave (una vez; guárdala fuera del repositorio y con copia de seguridad: sin ella no se
pueden publicar actualizaciones):

```bash
keytool -genkeypair -keystore ghostp2-release.jks -alias ghostp2 \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=GhostP2"
```

### Secretos de GitHub

En *Settings → Secrets and variables → Actions → New repository secret*:

| Secreto | Valor |
|---|---|
| `GHOSTP2_KEYSTORE_BASE64` | `base64 -w0 ghostp2-release.jks` |
| `GHOSTP2_KEYSTORE_PASSWORD` | Contraseña del almacén |
| `GHOSTP2_KEY_ALIAS` | Alias de la clave |
| `GHOSTP2_KEY_PASSWORD` | Contraseña de la clave |

O con `gh`: `base64 -w0 ghostp2-release.jks | gh secret set GHOSTP2_KEYSTORE_BASE64` y
`gh secret set GHOSTP2_KEYSTORE_PASSWORD` (pide el valor), igual para los otros dos.

Opcional, como **variable** (no secreto) en *Settings → Secrets and variables → Actions → Variables*:
`GHOSTP2_CERT_SHA256`, el SHA-256 del certificado de firma (`keytool -list -v -keystore
ghostp2-release.jks`, con o sin dos puntos). Si existe, el job `release` falla cuando algún APK firmado
no lleva ese certificado; si no, solo deja un aviso. O con `gh variable set GHOSTP2_CERT_SHA256`.

## CI

`.github/workflows/ci.yml`:

- **Job `test`** (cada push y pull request): `./gradlew test lintDebug lintRelease :app:assembleDebug
  :app:assembleRelease` (lint falla con cualquier error; `checkReleaseBuilds` en `:app`, `:core:data` y
  `:core:tor`). Después comprueba que en `releaseRuntimeClasspath` de `:app` no hay Google Play Services,
  Firebase, ML Kit, Play Core, `datatransport`, UMP, `com.google.android.libraries`, Install Referrer
  ni Play Billing; que el manifiesto de cada APK de versión no declara el inicializador de emoji2
  (buscaría fuentes en Google Play Services) ni es depurable, y que su dex no contiene clases
  `Lcom/google/android/gms/` ni `Lcom/google/firebase/`.
- **Job `release`** (solo etiquetas `v*`, después de `test`):
  - Con el secreto `GHOSTP2_KEYSTORE_BASE64`: lo decodifica en un archivo temporal del runner (se borra
    al acabar el paso), compila y firma los APK, los verifica con `apksigner`, calcula `SHA256SUMS.txt`
    y crea la Release de GitHub de la etiqueta con los APK y las sumas.
  - Sin él: compila los APK sin firmar y los sube como artefacto `apk-sin-firmar` con su
    `SHA256SUMS.txt`, con un aviso; **no** crea Release.
  - Endurecido: sus acciones van fijadas por SHA de commit (la etiqueta exacta en un comentario; para
    actualizarlas, `git ls-remote https://github.com/<dueño>/<acción> refs/tags/<etiqueta>`, y en una
    etiqueta anotada el SHA de la línea `^{}`), `persist-credentials: false` en el checkout (también en
    `test`) y la caché de Gradle solo de lectura (`cache-read-only: true`). Las acciones de `test` van
    fijadas a los mismos commits.
  - Con la variable `GHOSTP2_CERT_SHA256`, compara el certificado de cada APK firmado (`apksigner
    verify --print-certs`) y falla si no coincide.

Publicar una versión: `git tag v0.1.0 && git push origin v0.1.0`.
