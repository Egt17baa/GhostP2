# GhostP2

Mensajería privada **de persona a persona** para Android, de código abierto: dos móviles hablan
directamente a través de **Tor** (cada uno es un servicio oculto .onion), con todo **cifrado de
extremo a extremo**, **sin servidores** que alojar y **sin guardar mensajes**. Si tu contacto no
está conectado, el mensaje no se guarda ni se reenvía: se descarta y lo ves como "no entregado".

- Emparejamiento una sola vez con un código (QR o texto) y número de seguridad para verificar.
- Sesiones Noise `KKpsk2` con claves efímeras nuevas en cada conexión y ratchet con una clave por
  mensaje, relleno y contador estricto.
- Texto y fotos (troceadas, con acuse). Nada de mensajes ni claves de sesión en disco.

## Estado

| Parte | Estado |
|---|---|
| Núcleo (`:core:crypto`, `:core:protocol`): Noise, ratchet, invitaciones, emparejamiento, sesión, texto y fotos | **Listo y probado** (plan 1) |
| App Android con Tor y servicios ocultos | Plan 2 |
| Interfaz (Compose) | Plan 3 |

Todavía no hay APK: el núcleo es una biblioteca Kotlin/JVM sin dependencias de Android.

## Compilar y probar

Requisitos: JDK 17.

```sh
./gradlew test
```

Ejecuta todas las pruebas JVM de ambos módulos: vectores oficiales (RFC 7748, 5869, 4231,
8439 y los de Noise de cacophony), vectores propios del canal y del número de seguridad, y una
prueba de extremo a extremo con sockets TCP reales de bucle local.

## Documentación

- [`docs/PROTOCOLO.md`](docs/PROTOCOLO.md): especificación del protocolo tal como está
  implementado (Noise, derivaciones, formato de tramas, mensajes CBOR, tiempos y límites,
  modelo de amenazas y limitaciones).
- [`docs/superpowers/specs/2026-10-31-ghostp2-android-design.md`](docs/superpowers/specs/2026-10-31-ghostp2-android-design.md):
  diseño de la app Android.

## Licencia

[Apache-2.0](LICENSE).
