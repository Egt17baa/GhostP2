# Prueba manual con dos móviles

Comprueba de punta a punta la interfaz definitiva (plan 3) con dos móviles reales y Tor real:
bienvenida, emparejamiento con QR o texto, chat de texto y fotos, bloqueo, ajustes, borrados y el
APK de versión.

Los textos entre comillas son los que muestra la app (pantallas, botones y notificaciones). Los
iconos de las barras superiores se nombran por su descripción ("Añadir contacto", "Ajustes",
"Desconectar", "Más opciones", "Volver").

## Preparación

- Dos móviles Android 8.0 o superior (API 26+), **A** y **B**, con datos o wifi y sin bloqueo de Tor
  en la red. La mayoría de los móviles actuales son `arm64-v8a`. Los pasos del bloqueo con
  credencial (pasos 53–55) piden, si es posible, un móvil con Android 10 o anterior (API ≤ 29).
- Un ordenador con `adb`, la depuración USB activada en ambos móviles y el APK de depuración (ver
  [DESARROLLO](DESARROLLO.md)):

  ```sh
  ./gradlew :app:assembleDebug
  ls app/build/outputs/apk/debug/
  # app-arm64-v8a-debug.apk  app-armeabi-v7a-debug.apk  app-x86_64-debug.apk
  ```

- Instalar en cada móvil el APK de su ABI (consulta la ABI con
  `adb -s <serie> shell getprop ro.product.cpu.abi`):

  ```sh
  adb devices                                   # números de serie de A y B
  adb -s <serie-A> install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
  adb -s <serie-B> install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
  ```

- Para las fotos: [`exiftool`](https://exiftool.org/) en el ordenador.
- Los pasos con `run-as` solo funcionan con el APK de depuración. El APK de versión se prueba al
  final (pasos 76–79).

## Lista de comprobación

Marca cada paso cuando el resultado coincida. Si algo no coincide, anota el paso, el móvil, la
versión de Android y lo que se vio, sin copiar textos de invitación ni direcciones .onion.

### Bienvenida y arranque

| # | Paso | Resultado esperado |
|---|---|---|
| 1 | Abre GhostP2 en A (instalación nueva). | "Bienvenido a GhostP2" con cinco frases: sin servidores ni cuentas, mensajes solo en la memoria, "Si tu contacto no está conectado, el mensaje no se entrega y se descarta. No hay cola ni reenvío.", los dos tenéis que tener GhostP2 activo y Tor tarda. Botón "Siguiente". |
| 2 | Intenta hacer una captura de pantalla en A y abre la vista de recientes. | El sistema no permite la captura, o sale en negro, y la miniatura de recientes no muestra el contenido (`FLAG_SECURE`). Repítelo más tarde en la lista, en un chat con mensajes y en "Mi código" con el QR. |
| 3 | Pulsa "Siguiente". En Android 13+ aparece "Notificaciones": pulsa "Permitir notificaciones" y acéptalo en el diálogo del sistema. | "Notificaciones permitidas." En Android 12 o anterior este paso no aparece (no hay permiso que pedir). |
| 4 | (Opcional, en otro móvil o reinstalando.) Deniega el permiso dos veces. | "Las notificaciones están bloqueadas para GhostP2. Puedes permitirlas en sus ajustes." con "Abrir ajustes de notificaciones", que abre los ajustes de notificaciones de GhostP2. |
| 5 | Pulsa "Siguiente". Si GhostP2 no está exento de la optimización de batería aparece "Batería": pulsa "Quitar el ahorro de batería" y acepta en el diálogo del sistema. | "El ahorro de batería de Android ya no limita a GhostP2." Debajo, la ayuda del fabricante: en Xiaomi, Samsung, Huawei/Honor y OnePlus/OPPO/realme, la ruta de sus ajustes propios; en el resto, "Si tu móvil tiene su propio ahorro de energía…". Sigue esa ayuda en el móvil. |
| 6 | Pulsa "Activar GhostP2". | Aparece la lista "GhostP2" con "Aún no tienes contactos" y el botón "Añadir contacto". Aparece la notificación fija "GhostP2 activo · cifrado" con la acción "Desconectar". |
| 7 | Espera sin tocar nada (de unos segundos a unos minutos). | Encima de la lista, "Conectando a la red Tor… N %" con N creciente y el botón "Desconectar"; al llegar a en línea el aviso desaparece. En "Ajustes" › "Acerca de": "Versión 0.1.0", "Licencia Apache-2.0", "Mi dirección: todavía no publicada" (aún no hay contactos). |
| 8 | Repite los pasos 1–7 en B. | Lo mismo. |

### Invitación con QR

| # | Paso | Resultado esperado |
|---|---|---|
| 9 | En A, pulsa "Añadir contacto". | Pestañas "Mi código", "Escanear" y "Pegar". En "Mi código", "Preparando la invitación…" y después un código QR, "Caduca en mm:ss" (cuenta atrás desde 10:00), la ayuda "Enséñale este código a tu contacto…", el texto `ghostp2:…` (91 caracteres) y los botones "Copiar" y "Compartir". |
| 10 | Mantén pulsado el texto de la invitación. | No se puede seleccionar (R24): la única forma de copiarlo es "Copiar". |
| 11 | En B, pulsa "Añadir contacto" › "Escanear". La primera vez, acepta el permiso de cámara. | "Apunta la cámara al código QR de tu contacto" y la vista de la cámara. Si deniegas el permiso: "Sin permiso de cámara. Puedes pegar la invitación"; denegado para siempre: "El permiso de cámara está bloqueado…" con "Abrir ajustes". |
| 12 | En B, apunta a un QR que no sea de GhostP2 (una URL cualquiera). | No pasa nada: se ignora y sigue escaneando (no conecta a nada). |
| 13 | En B, escanea el QR de A. | B pasa a la pestaña "Pegar" con "Emparejando…". En unos segundos, A y B pasan a "Contacto nuevo": "Número de seguridad" en 6 grupos de 5 cifras, "Comparadlo por otro canal (en persona o por llamada)", el campo "Nombre", la casilla "Hemos comprobado que coincide", "Guardar" y "Más tarde". |
| 14 | Compara los dos números de seguridad. | Son idénticos. |
| 15 | En A escribe el nombre de B, marca "Hemos comprobado que coincide" y pulsa "Guardar". En B pulsa "Más tarde". | A abre el chat con el nombre elegido. B abre el chat con "Contacto nuevo". Al volver a la lista, A ve el nombre con "verificado" y "en línea" (puede pasar antes por "conectando…" o "desconectado" unos segundos); B ve "Contacto nuevo" sin "verificado". "Mi dirección" en Ajustes muestra una dirección abreviada en los dos. |
| 16 | En B, abre el chat › "Más opciones" › "Ver contacto". Pulsa "Cambiar el nombre", escribe el nombre de A y "Guardar"; después "Marcar como verificado". | Arriba: "en línea · verificado". El botón pasa a "Quitar verificación". En la lista: nombre nuevo y "verificado". |

### Invitación copiada y pegada; fallos

Emparejar otra vez A y B sustituye al contacto que ya tenían (mismo id, §14.2 del
[protocolo](PROTOCOLO.md)): tras el paso 20 vuelve a llamarse "Contacto nuevo" y sin verificar.
Ponle nombre otra vez con "Guardar".

| # | Paso | Resultado esperado |
|---|---|---|
| 17 | En A, "Añadir contacto" › "Mi código" › "Copiar". | "Invitación copiada". En Android 13+ la vista previa del portapapeles no muestra el texto (marcado como sensible). |
| 18 | En A, ve a la pestaña "Pegar", pega el texto en "Invitación" y pulsa "Aceptar". | "No puedes aceptar tu propia invitación". La invitación de "Mi código" sigue esperando. |
| 19 | Pasa el texto a B (otra app de mensajería, una nota, `adb shell input text`…), pero cambia **un carácter del medio del secreto** (la parte que hay tras el último punto) por otra letra de la `a` a la `z`. En B, "Pegar", pégalo y pulsa "Aceptar". | "Emparejando…" y después "No se pudo conectar con la invitación (¿caducada o ya usada?)". A sigue en "Mi código": el intento fallido **no** consume la invitación. |
| 20 | Pega en B el texto correcto, con espacios o un salto de línea al principio y al final, y pulsa "Aceptar". | Se acepta igual (el texto se recorta) y los dos pasan a "Contacto nuevo" con el mismo número de seguridad. |
| 21 | Pega otra vez en B el mismo texto (ya usado) y pulsa "Aceptar". | "No se pudo conectar con la invitación (¿caducada o ya usada?)". |
| 22 | (Opcional, 10 min.) Crea una invitación en A y no la uses. | La cuenta atrás llega a 00:00 y aparece "La invitación ha caducado" con "Crear otra". |
| 23 | En A, sal de "Añadir contacto" con "Volver" mientras espera. | La invitación se cancela: al volver a entrar se crea una nueva (QR y texto distintos). |

### Mensajes

| # | Paso | Resultado esperado |
|---|---|---|
| 24 | En A, abre el chat con B, escribe en "Mensaje" y pulsa "Enviar". | El mensaje aparece con "enviando…" y, en uno o dos segundos, "✓ entregado". |
| 25 | Mira el chat en B. | El mensaje recibido, sin etiqueta de estado. |
| 26 | Responde desde B. | En B, "✓ entregado"; en A, el mensaje recibido. |
| 27 | Con el chat de A abierto y en primer plano, envía otro texto desde B. | A no recibe notificación (el chat abierto no se notifica). |
| 28 | Con A en segundo plano y la pantalla desbloqueada, envía otro texto desde B. | En A, notificación "Nuevo mensaje de <nombre>", **sin el texto del mensaje**. |
| 29 | Bloquea la pantalla de A y envía otro texto desde B. | En la pantalla de bloqueo **nunca** aparece el texto. Si el sistema oculta el contenido sensible en la pantalla de bloqueo, sale solo "Nuevo mensaje"; si lo muestra todo, "Nuevo mensaje de <nombre>". |
| 30 | Escribe en A un texto de más de 7500 caracteres (por ejemplo, pegado). | Aparece el contador "N / 8000"; por encima de 8000 se pone en rojo y "Enviar" se desactiva. |

### Cortes de red y descarte

| # | Paso | Resultado esperado |
|---|---|---|
| 31 | Activa el modo avión en B. Espera hasta 1–2 minutos mirando A. | En A, el contacto pasa a "desconectado" (o "conectando…" mientras lo reintenta): sin tráfico en 60 s la sesión se da por caída. En el chat, "Tu contacto no está conectado: lo que envíes se descartará". |
| 32 | Con B aún en modo avión, envía un texto desde A. | Enseguida, el texto tachado y "No entregado: tu contacto no está conectado. Descartado". No se guarda ni se reenvía después. |
| 33 | Si en el paso 31 enviaste algo **antes** de que A viera "desconectado": | Ese texto termina tachado con "No entregado. Descartado" como mucho a los 30 s (sin acuse). |
| 34 | Quita el modo avión en B y no toques nada más. | En unos minutos los dos vuelven a "en línea" solos. Los mensajes anteriores siguen en el chat de ambos (el historial en memoria sobrevive a los cortes). El texto descartado no llega a B. |
| 35 | Repite los pasos 31 y 34, pero al quitar el modo avión **vuelve a abrir GhostP2** en B. | Abrir la app corta la espera de reconexión: los dos vuelven a "en línea" en segundos. |

### Fotos

| # | Paso | Resultado esperado |
|---|---|---|
| 36 | En el ordenador, añade coordenadas GPS a una foto y pásala a A: `exiftool -GPSLatitude=40.4168 -GPSLatitudeRef=N -GPSLongitude=3.7038 -GPSLongitudeRef=W foto.jpg`, `exiftool -gps:all foto.jpg` (muestra el GPS) y `adb -s <serie-A> push foto.jpg /sdcard/Pictures/`. Si no aparece en el selector, reinicia A o ábrela una vez con la app de archivos. | `foto.jpg` tiene GPS y está en A. |
| 37 | En el chat de A con B, pulsa "Enviar foto" (el `+` junto al campo) y elige la foto en el selector del sistema. | El selector solo muestra imágenes y GhostP2 no pide permiso de galería. No hay opción de cámara (R23). "Preparando foto…" y después la burbuja con la miniatura, una barra de progreso con "enviando…" y al final "✓ entregado". |
| 38 | En B, pulsa la miniatura de la foto recibida. | Se abre el visor "Foto" con el botón "Guardar". |
| 39 | Pulsa "Guardar" y guárdala en Descargas con el nombre propuesto (`GhostP2-AAAAMMDD-HHMMSS.jpg`). | "Foto guardada". Es la única forma de que una foto llegue al disco. |
| 40 | En el ordenador: `adb -s <serie-B> pull /sdcard/Download/GhostP2-<fecha>.jpg` y `exiftool -a -G1 GhostP2-<fecha>.jpg`. | Ni GPS ni bloque EXIF (solo JFIF y los datos de la imagen): el envío la recodificó sin metadatos y "Guardar" quita los segmentos APPn y COM (R27). |
| 41 | En A, elige 4 fotos seguidas sin esperar. | Las 3 primeras se aceptan (una en vuelo y dos esperando: como mucho 3 en total, R25); la cuarta da "Espera a que terminen de enviarse las fotos". Si la primera ya había terminado de enviarse, la cuarta cabe: repite más deprisa. |
| 42 | En "Ajustes" › "Fotos" › "Tamaño máximo", elige "3072 px". Envía fotos grandes de A a B, de 3 en 3, hasta pasar de 64 MiB en total (unas 25 fotos de cámara de 12 Mpx o más). | En A y en B, las fotos más antiguas pasan a "Foto descartada para liberar memoria" (en cursiva), primero las de más atrás. El texto de los mensajes no se pierde. Nunca se ve una imagen corrupta ni se cierra la app. Al pulsar una foto descartada no se abre nada. |
| 43 | Abre el visor en una foto antigua y, con el visor abierto, sigue enviando fotos desde el otro móvil hasta que se descarte. Pulsa "Guardar". | O la foto se guarda entera, o "No se pudo guardar la foto" (sin dejar un archivo vacío en Descargas). Al volver al chat, "Foto descartada para liberar memoria". |
| 44 | (Opcional.) Envía una foto panorámica o de 100 Mpx o más. | Se envía (puede quedar algo por debajo del tamaño máximo: la decodificación se limita a unos 24 Mpx, R26). Una foto que no se pueda leer da "No se pudo leer la foto"; una que no quepa en 3 MiB ni reduciéndola, "La foto es demasiado grande para enviarla". |

### Bloqueo de la app (R22)

Configura antes un bloqueo de pantalla (PIN, patrón o contraseña y, si quieres, huella) en A.

| # | Paso | Resultado esperado |
|---|---|---|
| 45 | (Sin bloqueo de pantalla en el móvil.) En "Ajustes" › "Seguridad", activa "Bloqueo con huella o PIN". | "Configura un bloqueo de pantalla en el móvil" y el interruptor sigue desactivado. |
| 46 | Con el bloqueo de pantalla configurado, activa "Bloqueo con huella o PIN" y elige "Bloquear tras: Inmediatamente". Abre un chat, sal a la pantalla de inicio y vuelve. | "GhostP2 está bloqueado" con "Desbloquear" y el diálogo del sistema "Desbloquear GhostP2" (huella o credencial). **En ningún momento** se ve la lista ni el chat antes de la pantalla de bloqueo: graba la pantalla con otro móvil si hace falta. Al desbloquear vuelve al mismo chat. |
| 47 | En el diálogo del sistema, pulsa cancelar (o atrás). | Sigue "GhostP2 está bloqueado", sin volver a pedirlo en bucle. "Desbloquear" lo pide otra vez; salir y volver, también. |
| 48 | Con el diálogo abierto, gira el móvil. | Un solo diálogo (sin duplicados); al desbloquear se abre la app. |
| 49 | Elige "Bloquear tras: 1 min". Sal 20 s y vuelve; después sal más de 1 min y vuelve. | La primera vez entra sin pedir nada; la segunda, bloqueado. Girar el móvil nunca bloquea. |
| 50 | Con el bloqueo activado, cierra la app del todo (`adb -s <serie-A> shell am force-stop org.ghostp2.app`) y vuelve a abrirla. | Arranque en frío: bloqueada desde el principio. Mientras se leen los ajustes solo se ve la carga, nunca la lista. |
| 51 | Con A bloqueado (pantalla "GhostP2 está bloqueado"), envía un texto desde B. | Llega igual ("Nuevo mensaje de <nombre>"): el bloqueo es solo de la interfaz y no detiene el motor. |
| 52 | Con el bloqueo activado, sal de GhostP2, **quita el bloqueo de pantalla del móvil** en los ajustes del sistema y vuelve a GhostP2. | GhostP2 no queda inaccesible: se abre y muestra "Tu móvil ya no tiene bloqueo de pantalla; se ha desactivado el bloqueo de GhostP2" con "Aceptar". En Ajustes, "Bloqueo con huella o PIN" está desactivado. |

#### Bloqueo en Android 10 o anterior (API ≤ 29)

| # | Paso | Resultado esperado |
|---|---|---|
| 53 | Con el bloqueo en "Inmediatamente", arranca GhostP2 en frío. | Aparece la pantalla del sistema para el PIN, patrón o contraseña. Cancelarla deja "GhostP2 está bloqueado" sin volver a pedirla en bucle; "Desbloquear" la pide otra vez; el PIN correcto desbloquea. Salir y volver la pide de nuevo. |
| 54 | En Android 8.0 u 8.1 (API 26–27) con una huella registrada. | No se ofrece la huella: solo PIN, patrón o contraseña (R28). |
| 55 | Gira el móvil con la pantalla de la credencial abierta. | Una sola pantalla; al introducir el PIN se abre la app. |

### Notificaciones sin nombre

| # | Paso | Resultado esperado |
|---|---|---|
| 56 | Deja en A una notificación "Nuevo mensaje de <nombre>" sin abrir. En "Ajustes" › "Notificaciones", activa "Ocultar el nombre del contacto". | Debajo: "Las notificaciones dirán solo «Nuevo mensaje».". La notificación que tenía el nombre desaparece. |
| 57 | Con A en segundo plano, envía un texto desde B. | En A, solo "Nuevo mensaje", también con el móvil desbloqueado. |
| 58 | Desactiva "Ocultar el nombre del contacto" y repite. | Vuelve "Nuevo mensaje de <nombre>". |

### Error, Reintentar y Desconectar

| # | Paso | Resultado esperado |
|---|---|---|
| 59 | En B, pulsa "Desconectar" (icono de la barra de la lista, o la acción de la notificación). | La notificación fija desaparece. Encima de la lista: "GhostP2 está desactivado" con "Activar". Los chats de B quedan vacíos (el historial se borra al detener). En A, el contacto pasa a "desconectado" en pocos segundos. En el chat de B: "Activa GhostP2 para enviar" y la entrada desactivada. |
| 60 | Con B detenido y en modo avión, pulsa "Activar". Espera hasta unos 3 minutos. | "Conectando a la red Tor… N %" y después "Sin conexión con Tor" con "Reintentar" y "Desconectar". La notificación fija pasa a "GhostP2 sin conexión · error" / "Abre la app para reintentar", con "Desconectar". |
| 61 | Quita el modo avión **sin pulsar nada**. | Al volver la red, GhostP2 se reinicia solo una vez (R18): "Conectando a la red Tor…" y después en línea; la notificación vuelve a "GhostP2 activo · cifrado". Si no ocurre en un par de minutos, pulsa "Reintentar": debe dar el mismo resultado. |
| 62 | Repite el paso 60 y esta vez pulsa "Desconectar". | La notificación desaparece y queda "GhostP2 está desactivado". Quita el modo avión y pulsa "Activar" para seguir. |

### El sistema mata el proceso (R19)

| # | Paso | Resultado esperado |
|---|---|---|
| 63 | Con A activo y exento de la optimización de batería, mata su proceso como lo haría el sistema: `adb -s <serie-A> shell pidof org.ghostp2.app` y luego `adb -s <serie-A> shell run-as org.ghostp2.app kill -9 <pid>`. | La notificación desaparece un momento. En segundos (el sistema decide cuándo) el servicio vuelve solo: "GhostP2 activo · cifrado", Tor arranca y A vuelve a "en línea" con B sin abrir la app. El historial de A empieza vacío (estaba solo en memoria). |
| 64 | (Android 12+.) Repite el paso 63 **sin** la exención de batería. | Lo mismo, o el sistema no deja volver al primer plano: entonces queda la notificación "GhostP2 se ha detenido" / "Abre la app para volver a activarlo". |
| 65 | Pulsa "Desconectar" en A y repite el `kill`. | No vuelve nada: "Desconectar" lo detiene del todo. |

No uses `adb shell am force-stop` para el paso 63: equivale a que el usuario fuerce la detención,
y Android no vuelve a crear el servicio. `adb shell am kill` tampoco sirve: no mata un proceso con
un servicio en primer plano.

### Activar al encender el móvil (R20)

| # | Paso | Resultado esperado |
|---|---|---|
| 66 | Con la opción "Activar GhostP2 al encender el móvil" desactivada (por defecto) y A activo, reinicia A. | Tras el reinicio GhostP2 **no** arranca solo: no hay notificación fija y al abrirlo muestra "GhostP2 está desactivado". |
| 67 | En "Ajustes" › "Segundo plano", activa "Activar GhostP2 al encender el móvil" y reinicia A. Desbloquéalo una vez y espera, sin abrir GhostP2. | Aparece "GhostP2 activo · cifrado" sin abrir la app y, en unos minutos, B ve a A "en línea". Si el sistema no lo deja arrancar, queda "GhostP2 se ha detenido" / "Abre la app para volver a activarlo"; anótalo con el modelo y la versión de Android. |

### Borrar chat y borrar contacto (aviso `Unpair`)

Con A y B activos, emparejados y "en línea".

| # | Paso | Resultado esperado |
|---|---|---|
| 68 | En el chat de A, "Más opciones" › "Borrar chat" y confirma con "Borrar el chat" en "¿Borrar el chat con <nombre>?". | El chat de A queda vacío ("Aún no hay mensajes…"). El de B no cambia. |
| 69 | En A, "Más opciones" › "Ver contacto" › "Borrar contacto". Lee el diálogo y confirma con "Borrar". | "¿Borrar a <nombre>?" con "Se le avisará si está conectado o en los próximos 2 minutos…". En A, en segundos: "<nombre> ha recibido el aviso" con "Aceptar", que vuelve a la lista (sin el contacto). En B: notificación "<nombre> te ha eliminado de sus contactos" (en la pantalla de bloqueo, "Aviso de un contacto") y el contacto desaparece de su lista. |
| 70 | Empareja de nuevo, activa el modo avión en B y borra el contacto en A. Espera 2 minutos. | El detalle muestra "Borrando…" (en la lista, el contacto aparece como "Borrando…" y no se puede abrir) y, a los 2 minutos, "No se pudo avisar a <nombre>; se ha borrado igualmente" con "Aceptar". Al quitar el modo avión, B sigue teniendo el contacto, desconectado para siempre. |
| 71 | Empareja de nuevo y activa el modo avión en B. En A, borra el contacto y, mientras sale "Borrando…", mata el proceso de A (`run-as … kill -9`, como en el paso 63). Cuando A vuelva solo (R19), quita el modo avión en B. | Al volver, la lista de A muestra el contacto otra vez como "Borrando…": el borrado se reanuda (el borrado pendiente está guardado). En cuanto B vuelve a la red recibe "<nombre> te ha eliminado de sus contactos"; si no vuelve en 2 minutos, A lo borra igualmente. Nunca queda el contacto en A como si no se hubiera pedido borrarlo. |
| 72 | Repite el paso 71 matando A con `adb -s <serie-A> shell am force-stop org.ghostp2.app` (el sistema no lo recrea). Abre GhostP2 y pulsa "Activar". | Al arrancar, el contacto vuelve a "Borrando…" y el borrado termina como en el paso 71. |

Antes de seguir, vuelve a emparejar A y B.

### Borrar todo (sustituye a "Empezar de cero" del plan 2)

| # | Paso | Resultado esperado |
|---|---|---|
| 73 | Con A y B "en línea", en A: "Ajustes" › "Datos" › "Borrar todo". Confirma "¿Borrar todo?" con "Borrar" y "¿Seguro?" con "Sí, borrar todo". | "Borrando…" y, en pocos segundos, la bienvenida "Bienvenido a GhostP2". La notificación fija y todas las de contactos desaparecen. Si había una invitación de GhostP2 en el portapapeles, se vacía. En B: "<nombre> te ha eliminado de sus contactos". |
| 74 | Recorre la bienvenida y pulsa "Activar GhostP2". Espera hasta unos 3 minutos. | **Tor vuelve a arrancar** tras borrar su estado: "Conectando a la red Tor… N %" y después en línea; "Aún no tienes contactos" y "Mi dirección: todavía no publicada". Los ajustes vuelven a los de por defecto (bloqueo desactivado, "Bloquear tras: 1 min", "2048 px"). |
| 75 | Empareja de nuevo A y B. | Emparejamiento correcto con una .onion nueva para A y un número de seguridad distinto del anterior. |

### APK de versión

Hace falta un APK de versión **firmado**: el de una Release de GitHub (comprueba antes su SHA-256,
ver el [README](../README.md#instalar)), uno compilado con las cuatro variables de firma (ver
[DESARROLLO](DESARROLLO.md#firma)) o uno sin firmar (`app-<abi>-release-unsigned.apk`) firmado a
mano con una clave de prueba:

```sh
apksigner sign --ks prueba.jks --out app-arm64-v8a-release.apk \
  app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk
```

| # | Paso | Resultado esperado |
|---|---|---|
| 76 | Desinstala el APK de depuración (`adb -s <serie> uninstall org.ghostp2.app`; la firma es distinta y se pierden los datos) e instala el de versión en A y B. | Se instala. `adb shell run-as org.ghostp2.app true` falla: no es depurable. |
| 77 | Repite los pasos 1–16 (bienvenida, QR y emparejamiento). | Igual que con el APK de depuración. |
| 78 | Repite los pasos 24, 32 y 37–40 (texto, descarte y foto guardada sin GPS). | Igual. |
| 79 | Repite los pasos 46, 59 y 73–74 (bloqueo, Desconectar y Borrar todo). | Igual: nada falla por R8 (minificación). |

> **Nota:** mientras una pantalla aparece o desaparece (menos de un segundo), la app ignora los
> toques en "Volver" y en las filas, para que un doble toque no abra pantallas duplicadas.
> Si un toque no hace nada justo al cambiar de pantalla, vuelve a tocar: no es un fallo.
> El gesto de atrás del sistema funciona siempre.

### Desconectar al salir de la app (R29)

Con A y B activos, emparejados y "en línea", y algún mensaje en el chat de A con B.

| # | Paso | Resultado esperado |
|---|---|---|
| 80 | En A: "Ajustes" › "Segundo plano" › "Desconectar al salir de la app": comprueba que está marcado "Nunca" y elige "A los 2 minutos". Sal a la pantalla de inicio 1 minuto y vuelve. | Nada se corta: sigue "GhostP2 activo · cifrado", B sigue "en línea" y el chat conserva los mensajes. Girar el móvil o abrir el selector de fotos y volver tampoco corta nada. |
| 81 | Abre el chat con B y, desde él, sal a la pantalla de inicio 3 minutos, con la pantalla encendida, sin volver. | A los 2 minutos desaparece la notificación fija "GhostP2 activo · cifrado" y, poco después, B ve a A "desconectado". Al volver: la lista de contactos (no el chat) con "GhostP2 está desactivado" y "Activar"; el chat con B está vacío y los contactos siguen ahí. |
| 82 | Pulsa "Activar" y espera a estar "en línea". Envía un mensaje a B. | Funciona igual que tras "Desconectar": Tor arranca, B vuelve a ver a A "en línea" y el mensaje llega. |
| 83 | Sal de la app y apaga la pantalla 5 minutos o más, sin tocar A. Mira B y después enciende A y vuelve a GhostP2. | B ve a A "desconectado" antes de encender A (el corte llega en el primer despertar tras los 2 minutos, sin esperar a volver). Al volver, igual que el paso 81. Si al volver aún estaba la notificación fija, desaparece en ese momento y enseguida se ve "GhostP2 está desactivado". |
| 84 | Con "Activar GhostP2 al encender el móvil" activado y "A los 2 minutos", reinicia A, desbloquéalo y espera 5 minutos **sin abrir** GhostP2. | GhostP2 sigue activo con su notificación fija: sin abrir la app no hay salida que contar (un plazo de antes del reinicio no cuenta). |
| 85 | Abre GhostP2 en A, "Activar" si hace falta, y sal a la pantalla de inicio. Antes de 1 minuto, mata el proceso como en el paso 63 (`run-as … kill -9`). Espera a que el servicio vuelva solo y no abras la app. | Vuelve "GhostP2 activo · cifrado" y, a los 2 minutos de haber salido (no de que volviera el servicio), desaparece la notificación fija y B ve a A "desconectado". |
| 86 | Repite el paso 85, pero mata el proceso a 1 minuto y 55 segundos de haber salido, y no abras la app. | Si el servicio vuelve después de los 2 minutos (el sistema decide cuándo), no aparece ninguna notificación de GhostP2 (ni la fija ni "se ha detenido") y B no ve a A "en línea". Si vuelve antes, corta a los 2 minutos como en el paso 85. Al abrir la app: "GhostP2 está desactivado" con "Activar". Vuelve a dejar "Nunca" al terminar. |

## Lo que esta prueba no cubre

- **Identidad cambiada** ("La identidad de <nombre> ha cambiado"): hace falta que un solo lado
  vuelva a emparejar con la misma identidad sin que el otro se entere, y "Borrar contacto" siempre
  intenta avisar al otro lado. Lo cubren las pruebas automáticas (`ConnectionsTest` y las de la
  interfaz).
- **Datos cifrados ilegibles** ("No se pudieron leer tus datos cifrados", con "Reintentar" y
  "Empezar de cero"): cubierto por pruebas automáticas. Con el APK de depuración se puede provocar
  estropeando el archivo (`run-as org.ghostp2.app sh -c 'printf x > no_backup/ghostp2.bin'` con
  GhostP2 desconectado y después `am force-stop`); "Empezar de cero" vuelve a la bienvenida.
