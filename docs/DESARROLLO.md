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
