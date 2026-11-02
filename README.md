# GhostP2

Mensajería privada **de persona a persona** para Android, de código abierto: dos móviles hablan
directamente a través de **Tor** (cada uno es un servicio oculto .onion), con todo **cifrado de
extremo a extremo**, **sin servidores** que alojar y **sin guardar mensajes**. Si tu contacto no
está conectado, el mensaje no se guarda ni se reenvía: se descarta y lo ves como "No entregado: tu
contacto no está conectado. Descartado".

- Emparejamiento una sola vez con una invitación (código QR o texto) y número de seguridad para
  verificar.
- Sesiones Noise `KKpsk2` con claves efímeras nuevas en cada conexión y ratchet con una clave por
  mensaje, relleno y contador estricto.
- Texto y fotos. Nada de mensajes, fotos ni claves de sesión en disco.
- Puentes de Tor disfrazados (obfs4, Snowflake o tus propios obfs4/WebTunnel) para redes que bloquean
  Tor; nunca se conecta sin puente si lo has elegido.

## Estado

| Parte | Estado |
|---|---|
| Núcleo (`:core:crypto`, `:core:protocol`): Noise, ratchet, invitaciones, emparejamiento, sesión, texto y fotos | **Listo y probado** (plan 1) |
| Motor y app Android (`:core:engine`, `:core:data`, `:core:tor`, `:app`): Tor con kmp-tor, servicios ocultos con autorización de cliente, presencia y reconexión, historial en memoria, borrado con aviso, almacenamiento cifrado con Android Keystore, servicio en primer plano y notificaciones | **Listo** (plan 2) |
| Interfaz definitiva (Compose): bienvenida, contactos, añadir contacto con QR, detalle con número de seguridad, chat de texto y fotos, ajustes, bloqueo; APK de versión desde CI | **Listo** (plan 3) |

## Instalar

Android 8.0 (API 26) o superior. Los APK firmados se publican en las
[Releases de GitHub](../../releases), uno por ABI, con un archivo `SHA256SUMS.txt`:

- `app-arm64-v8a-release.apk`: la mayoría de los móviles.
- `app-armeabi-v7a-release.apk`: móviles antiguos de 32 bits.
- `app-x86_64-release.apk`: emuladores.

Consulta la ABI de tu móvil con `adb shell getprop ro.product.cpu.abi` (o con una app de
información del sistema).

### Comprobar el SHA-256

Descarga el APK y `SHA256SUMS.txt` de la misma Release a una carpeta y, en ella:

```sh
sha256sum -c --ignore-missing SHA256SUMS.txt      # Linux
shasum -a 256 -c --ignore-missing SHA256SUMS.txt  # macOS
```

Debe decir `app-arm64-v8a-release.apk: OK` (o el de tu ABI). En Windows,
`certutil -hashfile app-arm64-v8a-release.apk SHA256` y compara a mano con la línea de
`SHA256SUMS.txt`. Si no coincide, no lo instales.

Las sumas están en la misma Release que los APK: detectan una descarga dañada o alterada por el
camino, no una Release alterada en origen. Para eso, comprueba también el certificado con
`apksigner verify --print-certs app-arm64-v8a-release.apk` y que sea el mismo en cada versión
(Android ya rechaza instalar encima una actualización firmada con otra clave).

Instálalo abriendo el APK en el móvil (permite "instalar apps desconocidas" para el navegador o el
gestor de archivos) o con `adb install app-arm64-v8a-release.apk`.

## Uso

1. **Bienvenida**: explica qué hace GhostP2 y pide el permiso de notificaciones (Android 13+) y
   quitar el ahorro de batería, con la ruta del ahorro propio de tu fabricante si lo tiene.
   "Activar GhostP2" arranca Tor: "Conectando a la red Tor… N %" puede tardar un minuto o más.
2. **Emparejar**: "Añadir contacto". Uno muestra "Mi código" (QR y texto, caduca en 10 minutos y
   sirve para una sola persona) y el otro lo escanea en "Escanear" o pega el texto en "Pegar". El
   texto solo se copia con "Copiar" (marcado como sensible) o se envía con "Compartir" por un canal
   de confianza.
3. **Verificar**: los dos ven el mismo **número de seguridad** (6 grupos de 5 cifras). Comparadlo
   en persona o por llamada; si coincide, marca "Hemos comprobado que coincide" (o, más tarde,
   "Marcar como verificado" en el detalle del contacto). Si no coincide, alguien se ha interpuesto:
   borra el contacto.
4. **Hablar**: los dos tenéis que tener GhostP2 activo. Cada mensaje pasa por "enviando…" y
   "✓ entregado" (recibido por su app, no leído). Las fotos se eligen con el selector de fotos del
   sistema; "Guardar" en el visor es la única forma de que una foto llegue al disco.
5. **Ajustes**: bloqueo con huella o PIN, ocultar el nombre en las notificaciones, tamaño de las
   fotos, activar al encender el móvil, "Desconectar al salir de la app" ("Nunca" por defecto; a
   los 2, 5 o 15 minutos fuera de la app cierra Tor y borra las conversaciones, R29) y "Puentes"
   (abajo).
6. **Puentes** (Ajustes › Puentes, R30): "No" por defecto (Tor directo, como siempre). Si tu red
   bloquea Tor o no quieres que se note que lo usas:
   - **obfs4**: los puentes obfs4 que trae Tor Browser.
   - **Snowflake**: con un aviso antes de elegirlo. Contacta con un intermediario a través de una red
     de distribución de contenidos, con servidores STUN públicos (ninguno de Google) y con voluntarios
     que hacen de puente; ven tu dirección IP, no tus mensajes. Úsalo solo si los otros no funcionan.
   - **Mis puentes**: lo más discreto. Pide puentes obfs4 o WebTunnel en
     [bridges.torproject.org](https://bridges.torproject.org/) (desde el navegador; GhostP2 nunca los
     descarga solo) y pégalos o escanea su código QR; como mucho 10. WebTunnel solo está aquí.

   Si el puente no funciona, sale "No se puede conectar por el puente…" y GhostP2 **nunca** se conecta
   sin él. Cambiar el ajuste con GhostP2 en marcha pide reconectar (se cierran las conversaciones
   abiertas). Con un puente, el estado de Tor se borra cada vez que se para, así que cada conexión
   arranca en frío (unos 30–60 s más).

## Compilar y probar

Requisitos: **JDK 17** y **Android SDK 36** (plataforma `android-36`, build-tools 36.1.0, con
`sdk.dir` en `local.properties`). Instrucciones en [`docs/DESARROLLO.md`](docs/DESARROLLO.md).

```sh
./gradlew test                   # todas las pruebas (JVM y Robolectric)
./gradlew :app:assembleDebug     # APK de depuración
./gradlew :app:assembleRelease   # APK de versión (R8)
```

Se genera un APK por ABI:

- Depuración, en `app/build/outputs/apk/debug/`: `app-arm64-v8a-debug.apk`,
  `app-armeabi-v7a-debug.apk` y `app-x86_64-debug.apk`.
- Versión, en `app/build/outputs/apk/release/`: `app-<abi>-release.apk` si están las cuatro
  variables de firma, o `app-<abi>-release-unsigned.apk` (no se puede instalar sin firmarlo) si
  falta alguna.

```sh
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

Prueba con dos móviles reales: [`docs/PRUEBA-MANUAL.md`](docs/PRUEBA-MANUAL.md).

### Firma y publicación

La firma usa cuatro variables de entorno en local y cuatro secretos de GitHub Actions en CI. En
este repositorio solo están sus nombres; los valores nunca se versionan:

| Local (`assembleRelease`) | Secreto de GitHub (CI) |
|---|---|
| `GHOSTP2_KEYSTORE_FILE` (ruta al `.jks`) | `GHOSTP2_KEYSTORE_BASE64` (el `.jks` en base64) |
| `GHOSTP2_KEYSTORE_PASSWORD` | `GHOSTP2_KEYSTORE_PASSWORD` |
| `GHOSTP2_KEY_ALIAS` | `GHOSTP2_KEY_ALIAS` |
| `GHOSTP2_KEY_PASSWORD` | `GHOSTP2_KEY_PASSWORD` |

Cómo crear la clave y guardar los secretos (en *Settings → Secrets and variables → Actions*, o
con `gh secret set`): [`docs/DESARROLLO.md`](docs/DESARROLLO.md#firma).

CI (`.github/workflows/ci.yml`) ejecuta en cada push y pull request las pruebas, Android Lint, los
APK de depuración y de versión, y comprueba que no hay Google Play Services, Firebase, ML Kit ni
Play Core en las dependencias de la versión, y que el manifiesto de cada APK de versión no es
depurable ni declara Google Play Services, Firebase o el inicializador de emoji2. Con una etiqueta `v*` (por ejemplo
`git tag v0.1.0 && git push origin v0.1.0`):

- con el secreto `GHOSTP2_KEYSTORE_BASE64`, firma los tres APK, los verifica con `apksigner`,
  calcula `SHA256SUMS.txt` y crea la Release de la etiqueta con todo ello;
- sin él, compila los APK sin firmar y los sube con su `SHA256SUMS.txt` como artefacto
  `apk-sin-firmar` del workflow, con un aviso, y **no** crea Release.

## Modelo de seguridad

- **Sin servidores.** Cada móvil publica su propia .onion v3 y solo cuando tiene al menos un
  contacto, **siempre** con autorización de cliente (una clave por contacto): quien conozca la
  dirección sin la clave no puede ni conectar ni saber si estás en línea.
- **Emparejamiento** con una invitación de 10 minutos y un solo uso servida desde una .onion
  temporal; el número de seguridad permite comprobar que nadie se ha interpuesto.
- **Cada sesión** autentica a ambos lados por su identidad y el secreto del emparejamiento, con
  claves efímeras nuevas y una clave por mensaje que se borra al usarla.
- **En disco solo** identidad, contactos, ajustes y borrados de contacto pendientes
  (`ghostp2.bin`), cifrados con una clave envuelta por Android Keystore (StrongBox si existe), y
  el estado interno de Tor. Sin copias de seguridad (`allowBackup="false"` y reglas que excluyen
  todo). Mensajes, fotos, borradores y claves de sesión solo en memoria; sin registros (logs) de
  contenido; nada de contenido en el estado que Android guarda al cerrar la actividad.
- **Fotos sin metadatos**: al enviar se recodifican a JPEG sin EXIF (lado mayor 2048 px por
  defecto, como mucho 3 MiB); "Guardar" vuelve a quitar los segmentos de metadatos de lo recibido.
- **Bloqueo de la app** (opcional, "Bloqueo con huella o PIN"): pide la huella o la credencial del
  móvil al abrir GhostP2 o al volver tras el tiempo elegido. Es una puerta de la interfaz (R22): no
  cifra nada con la huella.
- **Notificaciones sin contenido**: "Nuevo mensaje de <nombre>"; con "Ocultar el nombre del
  contacto", solo "Nuevo mensaje". En la pantalla de bloqueo, si el sistema oculta el contenido
  sensible, tampoco sale el nombre. `FLAG_SECURE` impide capturas de pantalla y oculta la miniatura
  en recientes.

## Limitaciones

Lo que GhostP2 **no** protege ni hace, en resumen (detalle en
[PROTOCOLO §13 y §15](docs/PROTOCOLO.md#15-limitaciones-del-nivel-de-aplicación)):

- **Sin contacto conectado no hay entrega**: el mensaje o la foto se descarta. No hay cola ni
  reenvío posterior. "✓ entregado" significa recibido por su app, no leído.
- **Solo en memoria quiere decir que se pierde**: los mensajes viven solo en la memoria del
  proceso. El historial desaparece al desconectar, si Tor falla, si el sistema mata la app o al
  reiniciar el móvil. Las fotos ocupan como mucho 64 MiB en total: por encima, las más antiguas
  pasan a "Foto descartada para liberar memoria".
- **El bloqueo es una puerta de la interfaz** (R22): la clave de Keystore no exige autenticación,
  así que el bloqueo no protege los datos frente a quien ejecute código como la app (root,
  depurador). Mientras está bloqueada, GhostP2 sigue recibiendo y notificando. En Android 8.0–8.1
  solo admite PIN, patrón o contraseña, no la huella (R28).
- **Claves en memoria que no se pueden borrar**: kmp-tor pasa las claves de los servicios ocultos
  y de autorización a Tor como `String` de Java por el puerto de control, y un `String` no se puede
  rellenar con ceros; Tor (otro proceso) las tiene en su memoria. El motor solo rellena con ceros
  algunas de sus propias copias. **Dispositivo comprometido** (malware, root, depurador): fuera de
  alcance.
- **Metadatos visibles para Tor y la red**: la red ve que usas Tor; tus contactos ven cuándo estás
  en línea; los horarios y el volumen del tráfico (latido cada 20 s, fotos) pueden correlacionarse
  por un adversario que observe gran parte de la red. El estado de Tor (guardas, consenso) está en
  disco.
- **Sin cámara para fotos** (R23): solo se envían fotos ya guardadas, elegidas con el selector del
  sistema. La cámara solo se usa para escanear el QR de una invitación.
- **Android Keystore obligatorio**: si no funciona, la app se niega a usar el almacenamiento (no
  cae a guardar la clave en claro) y no arranca.
- **Puentes** (R30): sin ellos, en redes que bloquean Tor no funciona. Con ellos, los transportes
  resuelven fuera de Tor, con el DNS del móvil, el host de WebTunnel y los frentes y STUN de Snowflake;
  un puente caído se detecta a los 3 minutos del arranque; sus puertos locales no tienen
  autenticación (como en Orbot); y si vuelves a una versión de GhostP2 que no conoce el modo guardado,
  se lee como "No" (directo). Detalle en [PROTOCOLO §14.11 y §15](docs/PROTOCOLO.md#1411-puentes-regla-r30).
- **Segundo plano**: con el ahorro de batería de Android o del fabricante la conexión puede
  cortarse. "Activar GhostP2 al encender el móvil" está desactivado por defecto (R20), y aun
  activado el sistema puede no dejarlo arrancar. Si Tor falla, al volver la red se reintenta una
  sola vez (como mucho una cada 30 s); si sigue fallando, queda "Reintentar".
- **"Compartir" la invitación** la entrega a otra app, que puede guardarla: úsalo solo con un canal
  de confianza (caduca a los 10 minutos y sirve para una sola persona).

## Documentación

- [`docs/PROTOCOLO.md`](docs/PROTOCOLO.md): especificación del protocolo tal como está
  implementado (Noise, derivaciones, formato de tramas, mensajes CBOR, tiempos y límites), las
  reglas del nivel de aplicación, el modelo de amenazas y las limitaciones.
- [`docs/PRUEBA-MANUAL.md`](docs/PRUEBA-MANUAL.md): lista de comprobación con dos móviles.
- [`docs/DESARROLLO.md`](docs/DESARROLLO.md): instalar el Android SDK, compilar, firmar y CI.
- [`docs/superpowers/specs/2026-10-31-ghostp2-android-design.md`](docs/superpowers/specs/2026-10-31-ghostp2-android-design.md):
  diseño de la app Android.

## Licencia

El código de GhostP2 es [Apache-2.0](LICENSE).

Para los puentes, la APK incluye [IPtProxy](https://github.com/tladesignz/IPtProxy) 5.7.0 (MIT), que
lleva [Lyrebird](https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird)
(obfs4 y WebTunnel, **GPL-3.0**) y
[Snowflake](https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/snowflake)
(BSD-3-Clause) como biblioteca nativa (`libgojni.so`). Por eso **la APK en conjunto se distribuye bajo
GPL-3.0** (Apache-2.0 es compatible con GPL-3.0 en ese sentido); el código de GhostP2 sigue en
Apache-2.0. El código fuente correspondiente de esa biblioteca es el de la etiqueta `5.7.0` de IPtProxy
y las versiones de Lyrebird y Snowflake que fija. Ajustes › Acerca de lo resume.
