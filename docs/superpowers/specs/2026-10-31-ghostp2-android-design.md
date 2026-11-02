# GhostP2 para Android — Diseño

Fecha: 2026-10-31 · Estado: pendiente de aprobación final del usuario

## 1. Intención

GhostP2 es una app de mensajería **Android nativa**, de **código abierto**, en la que dos
personas hablan **directamente** entre sus móviles, con todo **cifrado de extremo a extremo**,
**sin ningún servidor propio ni de terceros que haya que alojar** y **sin guardar mensajes**.
Si el destinatario no está conectado, el mensaje **no se guarda ni se reenvía: se descarta** y
quien lo envía lo ve como "no entregado".

El usuario quiere algo "totalmente hermético": nadie en medio debe poder leer, guardar ni
reconstruir las conversaciones, y la app no debe depender de infraestructura de nadie en
particular.

## 2. Decisiones del usuario

| Tema | Decisión |
|---|---|
| Plataforma | Android nativa (no web) |
| Funcionamiento | En segundo plano, con notificación fija mientras está activa |
| Contactos | Lista de contactos guardados; emparejamiento una sola vez con un código |
| Contenido | Texto y fotos |
| Infraestructura | No alojar nada; solo redes públicas existentes |
| Transporte | **Tor**: cada móvil es un servicio oculto (.onion) — enfoque C |

## 3. Objetivos, no objetivos y anti-objetivos

**Objetivos**
- Chat 1 a 1 de texto y fotos entre contactos guardados, sobre Tor, cifrado E2E con secreto
  hacia adelante.
- Emparejamiento por código (QR o texto) una sola vez; después la app conecta sola cuando
  ambos están activos y muestra quién está en línea.
- Mensajes y fotos solo en memoria; descarte inmediato si el contacto no está conectado.
- Funcionamiento en segundo plano mediante servicio en primer plano con notificación fija.

**No objetivos (por ahora)**
- Grupos, llamadas, notas de voz, vídeos, archivos genéricos.
- Varios dispositivos por persona, copia de seguridad o migración de identidad.
- iOS, versión web, escritorio.
- Publicación en Google Play (ver §11).

**Anti-objetivos (sería un fracaso aunque "funcione")**
- Que cualquier mensaje o foto se escriba en disco (BD, caché, logs, galería) sin acción
  explícita del usuario.
- Que exista un servidor propio, una cuenta, un número de teléfono o cualquier identificador
  central.
- Que un tercero (incluido quien conozca una dirección .onion) pueda conectarse, enumerar
  contactos o suplantar a un contacto.
- Mensajes "en cola" que se entreguen más tarde.
- Dependencias propietarias o de servicios de Google (Firebase, ML Kit, Play Services).

## 4. Restricciones

- Código abierto, licencia Apache-2.0 (la del repositorio).
- Kotlin + Jetpack Compose + Material 3, arquitectura oficial (UI / dominio / datos), MVVM
  con `StateFlow`.
- minSdk 26 (Android 8.0); targetSdk el más reciente exigido en la fecha de construcción
  (verificar).
- Sin Google Play Services ni librerías propietarias (para poder publicarse en F-Droid).
- El repositorio contiene hoy un prototipo web (WebRTC + servidor Node). **No se reutiliza**
  salvo como referencia del protocolo de mensajes (ratchet, relleno, contadores).

## 5. Enfoques considerados

| Enfoque | Resumen | Por qué sí / por qué no |
|---|---|---|
| A. Relés públicos de Nostr + WebRTC | Señalización cifrada por relés públicos, después conexión directa | Rápido y con poca batería, pero los relés ven la IP y en redes móviles con CGNAT la conexión directa falla a menudo (≈10–20 %) sin TURN, y no hay TURN público fiable |
| B. Brokers MQTT públicos + WebRTC | Igual que A con brokers MQTT | Brokers "de prueba" de pocas empresas, sin garantías; peor que A sin ventaja |
| **C. Tor: cada móvil es un servicio oculto** | Sin señalización: el contacto conecta a tu .onion | **Elegido por el usuario.** Nadie ve las IPs (ni el propio contacto), atraviesa cualquier NAT/operadora, cero infraestructura propia. Coste: latencia de 1–3 s, fotos más lentas, APK ~25 MB mayor, más batería |

## 6. Arquitectura

### 6.1 Módulos Gradle

- `:core:crypto` — Kotlin/JVM puro. Identidad, emparejamiento, handshake, ratchet, tramas.
  Sin dependencias de Android → probado con JUnit en la JVM.
- `:core:protocol` — Kotlin/JVM puro. Formato de tramas, mensajes de aplicación (texto, foto
  troceada, acuses, ping, bye), máquina de estados de sesión. Probado en JVM con transportes
  simulados.
- `:core:tor` — Android. Arranque de Tor, servicios ocultos, SOCKS, puerto de control.
- `:core:data` — Android. Almacenamiento cifrado de identidad, contactos y ajustes.
- `:app` — Android. Servicio en primer plano, UI Compose, navegación, notificaciones.

### 6.2 Tor

- Tor embebido con una librería mantenida que exponga el puerto de control
  (`ADD_ONION`/`DEL_ONION` con `ClientAuthV3`) y un proxy SOCKS5. Candidatas:
  `kmp-tor` o `tor-android` (Guardian Project) + `jtorctl`. **Elección del constructor**
  tras comprobar mantenimiento y soporte de autorización de cliente v3.
- Al activar GhostP2 se arranca Tor (estado "Conectando a la red Tor…", puede tardar
  10–60 s) y se publica el **servicio oculto permanente** del usuario:
  - Clave ED25519-V3 persistente (guardada cifrada, ver §6.6), de modo que la dirección
    .onion no cambia.
  - **Autorización de cliente v3 obligatoria**: solo los contactos emparejados (cuyas claves
    públicas x25519 de autorización se registran en el servicio) pueden siquiera obtener el
    descriptor. Para cualquier otro, la .onion es inalcanzable.
  - El puerto virtual del .onion apunta a un `ServerSocket` ligado a `127.0.0.1` en un puerto
    aleatorio.
- Conexiones salientes: a la .onion del contacto vía SOCKS5 de Tor, aportando la clave
  privada de autorización de cliente correspondiente (`ONION_CLIENT_AUTH_ADD`).

### 6.3 Identidad y claves (por usuario)

Generadas en el primer arranque, nunca salen del móvil:
- `onionKey` — ED25519 del servicio oculto (define la dirección .onion).
- `identityKey` — par X25519 estático para el handshake E2E.
- Por cada contacto: un par x25519 de **autorización de cliente** que él usa para entrar en
  mi .onion (yo guardo la pública y le doy la privada durante el emparejamiento), y un
  `pairSecret` de 32 bytes compartido con él.

### 6.4 Emparejamiento (una sola vez por contacto)

1. A pulsa "Añadir contacto". La app crea un **servicio oculto temporal** (clave efímera, sin
   autorización de cliente, válido 10 minutos y de un solo uso) y genera un secreto `s` de
   16 bytes.
2. Invitación = `ghostp2:` + dirección .onion temporal + `s` (base32). Se muestra como **QR**
   (escaneable con la cámara, librería ZXing) y como texto copiable/compartible.
3. B escanea o pega la invitación y conecta por Tor a la .onion temporal.
4. Handshake de emparejamiento autenticado por `s` (PSK): ambos se intercambian, cifrado,
   `identityKey` pública, dirección .onion permanente, clave privada de autorización de
   cliente para entrar en el servicio del otro, y derivan `pairSecret`.
   - La dirección .onion temporal autentica a A (solo quien tiene su clave puede servirla).
   - `s` autentica a B (solo quien vio la invitación lo conoce).
   - Por tanto no hay intermediario posible salvo que la invitación se filtre.
5. Ambos muestran el **número de seguridad** (derivado de las dos `identityKey`) y piden un
   nombre para el contacto. Se puede marcar "verificado" tras compararlo por otro canal.
6. A elimina el servicio temporal. La invitación queda invalidada.

### 6.5 Sesión entre contactos emparejados

- **Presencia:** mientras GhostP2 está activo, para cada contacto sin sesión abierta la app
  intenta conectar a su .onion con reintentos y espera creciente (p. ej. 15 s → 5 min, con
  variación aleatoria; se reinicia al cambiar la red o al abrir la app). Si ambos conectan a
  la vez, se conserva la conexión iniciada por la `identityKey` menor y se cierra la otra.
- **Handshake** (autenticación mutua + secreto hacia adelante), propiedades obligatorias:
  - Claves efímeras X25519 nuevas en cada sesión.
  - Autenticación mutua con las `identityKey` estáticas ya conocidas (patrón tipo
    Noise `KK` con PSK) y mezcla de `pairSecret`.
  - Se rechaza si la `identityKey` presentada no es la guardada para ese contacto: la sesión
    se bloquea y se avisa de "identidad cambiada, volved a emparejar".
  - Recomendación: implementar **Noise_KKpsk2_25519_ChaChaPoly_SHA256** siguiendo la
    especificación de Noise, o la construcción triple-DH + HKDF del prototipo; **elección del
    constructor**, documentada en `docs/PROTOCOLO.md` y con vectores de prueba.
- **Mensajes:** ratchet simétrico por mensaje (una clave por mensaje, claves usadas
  borradas), AEAD, contador estrictamente secuencial (repetición/reordenación/manipulación ⇒
  se corta la sesión), relleno a bloques de 256 bytes. Mismo diseño que el prototipo web.
- **Tramas:** sobre el flujo TCP de Tor, prefijo de longitud de 4 bytes, tamaño máximo de
  trama 64 KiB.
- **Latido:** ping cifrado cada ~20 s; sin tráfico durante ~60 s ⇒ contacto desconectado.

### 6.6 Almacenamiento (solo esto toca el disco)

- Identidad (§6.3), contactos (nombre, `identityKey` pública, .onion, claves de autorización,
  `pairSecret`, verificado sí/no) y ajustes.
- Cifrado en reposo con una clave AES-256-GCM envuelta por **Android Keystore** (StrongBox si
  existe). Implementación con DataStore + AEAD (p. ej. Tink con `AndroidKeysetManager`).
  **Elección del constructor** la librería concreta.
- `android:allowBackup="false"` y reglas de extracción de datos que excluyen todo.
- **Nunca** en disco: mensajes, fotos recibidas, claves de sesión.
- Los datos de estado de Tor (caché de consenso) sí van a disco: no contienen nada del
  usuario.

### 6.7 Envío y descarte

- Si el contacto no tiene sesión abierta ⇒ el mensaje se muestra tachado: "No entregado: tu
  contacto no está conectado. Descartado". No se reintenta jamás.
- Si hay sesión: se envía y queda "enviando…" hasta el acuse cifrado ⇒ "✓ entregado".
- Si la sesión cae antes del acuse, o no llega en 30 s ⇒ "No entregado. Descartado".
- **Historial en memoria (decisión del usuario):** los mensajes y fotos ya enviados o
  recibidos viven en memoria dentro del servicio (no ligados a la sesión) y **sobreviven a
  los cortes breves de conexión**. Solo se eliminan al pulsar "Borrar" en la conversación,
  al borrar el contacto, o al detener GhostP2. Nunca se escriben en disco.
- Lo que se intente enviar mientras no hay sesión se sigue descartando (no hay cola).

### 6.7 bis Borrar un contacto (decisión del usuario: avisarle)

- Al borrar un contacto, si hay sesión abierta se le envía un mensaje cifrado `unpair`; si
  no la hay, la app intenta conectar durante un breve plazo (p. ej. 2 min mientras GhostP2
  esté activo) solo para entregarlo, y si no lo logra lo borra igualmente.
- Al recibir `unpair` (autenticado por la sesión), la app del contacto muestra "<nombre> te
  ha eliminado de sus contactos" y lo borra también (claves, .onion, autorización).
- En ambos lados se revoca la autorización de cliente del servicio oculto permanente
  (requiere volver a publicarlo con `DEL_ONION`/`ADD_ONION`; corte de unos segundos para el
  resto de contactos, aceptable).
- Se puede volver a emparejar con una invitación nueva.
- "Borrar todo" envía `unpair` a todos los contactos con sesión abierta (mejor esfuerzo, sin
  esperas largas) antes de borrar.

### 6.8 Fotos

- Selección con el **Photo Picker** del sistema (sin permiso de galería) o la cámara (intent
  del sistema, sin permiso propio).
- Se decodifica y se **recodifica a JPEG** (lo que elimina EXIF/GPS/modelo), con lado mayor
  máximo configurable (por defecto 2048 px) y objetivo de ≤ 3 MB.
- Se envía troceada (trozos de ~32 KiB en tramas cifradas normales), con un único acuse al
  final. Barra de progreso. Si la sesión cae a mitad ⇒ se descarta entera en ambos lados.
- El receptor la muestra desde memoria. Botón opcional "Guardar" que la escribe en la
  galería solo por acción explícita del usuario.

### 6.9 Servicio en primer plano y segundo plano

- Al "Activar GhostP2": servicio en primer plano con notificación fija "GhostP2 activo ·
  cifrado" y acción "Desconectar". Tipo de servicio en primer plano compatible con Android
  14+ (probablemente `specialUse` con justificación; **verificar** reglas vigentes).
- Notificación de mensaje entrante **sin contenido**: "Nuevo mensaje de <nombre>" (opción en
  ajustes para ocultar también el nombre).
- Primer uso: pedir exención de optimización de batería y mostrar ayuda específica para
  fabricantes agresivos (Xiaomi, Samsung, Huawei…).
- Reanudación tras reinicio del móvil: **no** automática por defecto (opción en ajustes).

### 6.10 Interfaz (Compose)

Pantallas: Bienvenida/activación · Lista de contactos (en línea / desconectado / verificado)
· Chat · Añadir contacto (mostrar QR / escanear / pegar) · Detalle de contacto (número de
seguridad, verificar, renombrar, borrar) · Ajustes (bloqueo, notificaciones, tamaño de foto,
borrar todo) · Bloqueo.

- `FLAG_SECURE` en toda la app (sin capturas ni vista previa en recientes).
- Bloqueo opcional con huella/PIN del sistema (`BiometricPrompt` con credencial del
  dispositivo) al abrir y tras X minutos en segundo plano.
- "Borrar todo": elimina identidad, contactos, claves y estado; la app vuelve a estado de
  fábrica.
- Idioma: español (cadenas en recursos para poder traducir).

## 7. Seguridad: resumen del modelo de amenazas

| Adversario | Qué puede | Qué no puede |
|---|---|---|
| Red / operadora / wifi | Ver que usas Tor | Ver con quién hablas ni qué |
| Nodos de Tor | Ver tráfico cifrado | Leerlo; saber IP y destino a la vez |
| Quien conozca tu .onion | Nada (autorización de cliente) | Conectar, sondear presencia |
| Quien robe una invitación antes de usarla | Emparejarse en tu lugar | — (mitigado: 10 min, un uso, número de seguridad) |
| Robo del móvil bloqueado | — | Leer mensajes (no existen en disco); contactos cifrados con Keystore |
| Contacto malicioso | Ver lo que le envías | Ver tu IP |

Riesgos aceptados: el dispositivo comprometido (malware/root) queda fuera de alcance; el
uso de Tor puede ser visible o estar bloqueado en algunas redes (puentes: fuera de alcance de
la v1, ver §10).

## 8. Pruebas y calidad

- `:core:crypto` y `:core:protocol`: pruebas unitarias JVM (handshake, identidad cambiada,
  repetición, manipulación, relleno, troceado de fotos, descarte al cortar la sesión),
  vectores de prueba documentados.
- Integración en JVM: dos instancias del protocolo sobre sockets locales (sin Tor).
- Android: pruebas de UI Compose de los flujos principales; prueba instrumentada de arranque
  de Tor y publicación del servicio oculto.
- Prueba manual documentada con dos móviles reales.
- detekt + ktlint + Android Lint en CI.

## 9. Entrega

- GitHub Actions: compila, pasa pruebas y genera APK firmado en cada etiqueta de versión;
  se publica en GitHub Releases con su hash SHA-256. Clave de firma solo en secretos de
  GitHub.
- APK separados por ABI (arm64-v8a, armeabi-v7a, x86_64) para reducir tamaño.
- Builds reproducibles como objetivo (preparar para F-Droid).
- README en español: qué es, instalación del APK, cómo emparejar, modelo de seguridad.

## 10. Decisiones tomadas por el diseñador (revisables)

- Conversaciones solo 1 a 1.
- Bloqueo de capturas de pantalla en toda la app.
- Recodificación de fotos para eliminar metadatos; botón "Guardar" opcional.
- Notificaciones sin contenido.
- Identidad cambiada ⇒ bloqueo hasta nuevo emparejamiento.
- Invitación válida 10 min y de un solo uso, mediante servicio oculto temporal.
- Sin puentes Tor (obfs4/Snowflake) en la v1; se pueden añadir después.
- Distribución por GitHub Releases, no Google Play.
- El prototipo web y el servidor Node se eliminan de la rama principal (quedan en el
  historial de git).

## 11. Lo que queda en manos del constructor

- Librería concreta de Tor, de cripto (Tink / BouncyCastle / libsodium) y de QR (ZXing u
  otra libre).
- Patrón exacto de handshake (Noise KKpsk2 o triple-DH), documentado con vectores.
- Valores exactos de reintentos, tiempos de espera y tamaños dentro de los rangos dados.
- Inyección de dependencias (Hilt o manual), estructura interna de paquetes.
- Diseño visual dentro de Material 3.
