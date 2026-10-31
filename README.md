# GhostP2

Mensajería privada **de persona a persona** para Android, de código abierto: dos móviles hablan
directamente a través de **Tor** (cada uno es un servicio oculto .onion), con todo **cifrado de
extremo a extremo**, **sin servidores** que alojar y **sin guardar mensajes**. Si tu contacto no
está conectado, el mensaje no se guarda ni se reenvía: se descarta y lo ves como "no enviado".

- Emparejamiento una sola vez con un código (texto; QR en el plan 3) y número de seguridad para
  verificar.
- Sesiones Noise `KKpsk2` con claves efímeras nuevas en cada conexión y ratchet con una clave por
  mensaje, relleno y contador estricto.
- Texto y fotos (troceadas, con acuse) en el motor. Nada de mensajes ni claves de sesión en disco.

## Estado

| Parte | Estado |
|---|---|
| Núcleo (`:core:crypto`, `:core:protocol`): Noise, ratchet, invitaciones, emparejamiento, sesión, texto y fotos | **Listo y probado** (plan 1) |
| Motor y app Android (`:core:engine`, `:core:data`, `:core:tor`, `:app`): Tor con kmp-tor, servicios ocultos con autorización de cliente, invitaciones, presencia y reconexión, historial en memoria, borrado con aviso, almacenamiento cifrado con Android Keystore, servicio en primer plano y notificaciones | **Listo** (plan 2) |
| Interfaz definitiva (Compose): contactos, chat con fotos, QR, detalle del contacto, ajustes, bloqueo | Pendiente (plan 3) |

La app actual muestra una **pantalla de diagnóstico temporal** ("GhostP2 · diagnóstico"):
activar y desconectar, estado de Tor, crear y aceptar invitaciones (texto), lista de contactos con
su presencia y un chat mínimo de texto. Sirve para la [prueba manual](docs/PRUEBA-MANUAL.md); no
permite todavía borrar ni renombrar contactos, borrar conversaciones ni enviar fotos (el motor ya
lo hace; falta la interfaz).

## Compilar y probar

Requisitos: **JDK 17** y **Android SDK 36** (plataforma `android-36`, build-tools 36.1.0, con
`sdk.dir` en `local.properties`). Instrucciones de instalación del SDK en
[`docs/DESARROLLO.md`](docs/DESARROLLO.md).

```sh
./gradlew test                 # todas las pruebas (JVM y Robolectric)
./gradlew :app:assembleDebug   # APK de depuración
```

Se genera un APK por ABI en `app/build/outputs/apk/debug/`: `app-arm64-v8a-debug.apk` (la
mayoría de móviles), `app-armeabi-v7a-debug.apk` y `app-x86_64-debug.apk` (emuladores). Android
8.0 (API 26) o superior.

```sh
adb install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
```

Prueba con dos móviles reales: [`docs/PRUEBA-MANUAL.md`](docs/PRUEBA-MANUAL.md).

## Modelo de seguridad

- **Sin servidores.** Cada móvil publica su propia .onion v3 y solo cuando tiene al menos un
  contacto, **siempre** con autorización de cliente (una clave por contacto): quien conozca la
  dirección sin la clave no puede ni conectar ni saber si estás en línea.
- **Emparejamiento** con una invitación de 10 minutos y un solo uso servida desde una .onion
  temporal; el número de seguridad permite comprobar que nadie se ha interpuesto.
- **Cada sesión** autentica a ambos lados por su identidad y el secreto del emparejamiento, con
  claves efímeras nuevas y una clave por mensaje que se borra al usarla.
- **En disco solo** identidad y contactos (`ghostp2.bin`), cifrados con una clave envuelta por
  Android Keystore (StrongBox si existe), y el estado interno de Tor. Sin copias de seguridad
  (`allowBackup="false"` y reglas que excluyen todo). Mensajes, fotos y claves de sesión solo en
  memoria; sin registros (logs) de contenido.
- **Notificaciones sin contenido**: "Nuevo mensaje de <nombre>" (y solo "Nuevo mensaje" en la
  pantalla de bloqueo si el sistema oculta el contenido sensible). `FLAG_SECURE` impide capturas.

## Limitaciones

Lo que GhostP2 **no** protege, en resumen (detalle en
[PROTOCOLO §13 y §15](docs/PROTOCOLO.md#15-limitaciones-del-nivel-de-aplicación)):

- **Solo en memoria quiere decir que se pierde**: si el contacto no está conectado el mensaje se
  descarta; el historial desaparece al desconectar, si Tor falla o si el sistema mata la app.
- **Dispositivo comprometido** (malware, root, depurador): fuera de alcance. Las claves están en
  memoria mientras la app funciona, y algunas no se pueden borrar de ella: kmp-tor pasa las
  claves de los servicios ocultos y de autorización a Tor como `String` por el puerto de control;
  Tor corre en otro proceso.
- **Android Keystore obligatorio**: si no funciona, la app se niega a usar el almacenamiento (no
  cae a guardar la clave en claro) y no arranca.
- **Metadatos**: la red ve que usas Tor; tus contactos ven cuándo estás en línea; los horarios
  y el volumen del tráfico (latido cada 20 s, fotos) pueden correlacionarse por un adversario que
  observe gran parte de la red. El estado de Tor (guardas, consenso) está en disco.
- **Sin puentes** (obfs4, Snowflake): en redes que bloquean Tor no funciona.
- Sin arranque automático tras reiniciar el móvil; con ahorro de batería agresivo la conexión
  puede cortarse.

## Documentación

- [`docs/PROTOCOLO.md`](docs/PROTOCOLO.md): especificación del protocolo tal como está
  implementado (Noise, derivaciones, formato de tramas, mensajes CBOR, tiempos y límites), las
  reglas del nivel de aplicación, el modelo de amenazas y las limitaciones.
- [`docs/PRUEBA-MANUAL.md`](docs/PRUEBA-MANUAL.md): lista de comprobación con dos móviles.
- [`docs/DESARROLLO.md`](docs/DESARROLLO.md): instalar el Android SDK y compilar.
- [`docs/superpowers/specs/2026-10-31-ghostp2-android-design.md`](docs/superpowers/specs/2026-10-31-ghostp2-android-design.md):
  diseño de la app Android.

## Licencia

[Apache-2.0](LICENSE).
