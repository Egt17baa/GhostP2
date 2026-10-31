# Prueba manual con dos móviles

Comprueba de punta a punta la app del plan 2 con dos móviles reales y Tor real. La pantalla que
se usa es la **pantalla de diagnóstico temporal** ("GhostP2 · diagnóstico"): solo texto, sin QR
y sin fotos. El plan 3 la sustituye por la interfaz definitiva.

Los textos entre comillas son los que muestra la app (pantalla, botones y notificaciones).

## Preparación

- Dos móviles Android 8.0 o superior (API 26+), **A** y **B**, con datos o wifi y sin
  bloqueo de Tor en la red. La mayoría de los móviles actuales son `arm64-v8a`.
- Un ordenador con `adb`, la depuración USB activada en ambos móviles y el APK compilado (ver
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

- Para pasar el texto de la invitación de un móvil a otro sirve cualquier canal (otra app de
  mensajería, una nota compartida, `adb shell input text`…). La pantalla de diagnóstico no
  tiene QR.

Todos los contactos se guardan con el nombre "Contacto nuevo": la pantalla de diagnóstico no
permite renombrarlos.

## Lista de comprobación

Marca cada paso cuando el resultado coincida. Si algo no coincide, anota el paso, el móvil, la
versión de Android y lo que se vio, sin copiar textos de invitación ni direcciones .onion.

### Arranque

| # | Paso | Resultado esperado |
|---|---|---|
| 1 | Abre GhostP2 en A y en B. | "GhostP2 · diagnóstico", "Estado: detenido", botón "Activar", "Mi .onion: sin publicar", "Contactos: 0 (en línea: 0)". |
| 2 | Intenta hacer una captura de pantalla en A. | El sistema no la permite, o sale en negro (`FLAG_SECURE`). En recientes no se ve el contenido. |
| 3 | En A, pulsa "Activar". En Android 13+ el sistema pide permiso de notificaciones: acéptalo. Repite en B. | Aparece la notificación fija "GhostP2 activo · cifrado" con la acción "Desconectar". El botón de la pantalla pasa a "Desconectar". |
| 4 | Espera sin tocar nada (de unos segundos a unos minutos). | "Estado: arrancando Tor (N %)" con N creciente hasta 100, y después "Estado: en línea". "Mi .onion: sin publicar" (aún no hay contactos). |

### Optimización de batería

| # | Paso | Resultado esperado |
|---|---|---|
| 5 | Observa la línea de batería en A. | Si no está exenta: "Optimización de batería: activa (puede cortar la conexión)" y el botón "Permitir en segundo plano". |
| 6 | Pulsa "Permitir en segundo plano" y acepta en el diálogo del sistema. Repite en B. | La línea pasa a "Optimización de batería: desactivada para GhostP2" y el botón desaparece. |

La app no lo pide sola en el primer uso: solo ofrece este botón. En móviles con ahorro de
energía propio del fabricante (Xiaomi, Samsung, Huawei…) puede hacer falta, además, permitir
la ejecución en segundo plano en los ajustes del fabricante.

### Invitación y emparejamiento

| # | Paso | Resultado esperado |
|---|---|---|
| 7 | En A, pulsa "Crear invitación". | "Preparando la invitación…" y después "Comparte este texto (caduca en 10 min):" con un texto `ghostp2:…` de 91 caracteres y los botones "Copiar" y "Cancelar". "Mi .onion" pasa a mostrar una dirección abreviada (regla R10: se publica, con autorización de cliente, mientras dura la invitación). |
| 8 | En A, pulsa "Cancelar". | "Invitación cancelada". Al poco, "Mi .onion: sin publicar" otra vez (no hay contactos). |
| 9 | En A, crea otra invitación, cópiala y pégala en el propio A, en "Texto de la invitación" ("Pegar invitación"). Pulsa "Aceptar invitación". | "No se pudo: No puedes aceptar tu propia invitación". La invitación sigue esperando. |
| 10 | Pasa el texto a B, pero cambia **un carácter del medio del secreto** (la parte que hay tras el último punto) por otra letra de la `a` a la `z`. Pégalo en B y pulsa "Aceptar invitación". | "Emparejando…" y después "No se pudo: No se pudo conectar con la invitación (¿caducada o ya usada?)". A sigue en "Comparte este texto…": el intento fallido **no** consume la invitación. |
| 11 | Pega en B el texto correcto y pulsa "Aceptar invitación". | En B: "Emparejando…" y después "Emparejado. Número de seguridad: …" (30 dígitos en 6 grupos). En A: "Emparejado. Número de seguridad: …". |
| 12 | Compara los dos números de seguridad. | Son idénticos. |
| 13 | Mira la lista de contactos en ambos. | "Contactos: 1 (en línea: 1)" y "Contacto nuevo · en línea" (puede pasar antes por "conectando" o "desconectado" unos segundos). "Mi .onion" muestra una dirección abreviada en los dos. |
| 14 | Pega otra vez en B el mismo texto (ya usado) y pulsa "Aceptar invitación". | "No se pudo: No se pudo conectar con la invitación (¿caducada o ya usada?)". La invitación solo sirve para un emparejamiento. |
| 15 | (Opcional, 10 min.) Crea una invitación en A y no la uses. | A los 10 minutos: "La invitación ha caducado". |

### Mensajes

| # | Paso | Resultado esperado |
|---|---|---|
| 16 | En A, toca "Contacto nuevo · en línea". Escribe un texto en "Mensaje" y pulsa "Enviar". | Aparece "Chat con Contacto nuevo". En A: `→ <texto> (enviando…)` y, en uno o dos segundos, `→ <texto> (entregado)`. |
| 17 | En B, abre el chat con el contacto. | `← <texto>`. |
| 18 | Responde desde B. | En B: `→ <respuesta> (entregado)`. En A: `← <respuesta>`. |
| 19 | Con B en segundo plano y la pantalla desbloqueada, envía otro texto desde A. | En B, notificación "Nuevo mensaje de Contacto nuevo", **sin el texto del mensaje**. |
| 20 | Bloquea la pantalla de B y envía otro texto desde A. | En la pantalla de bloqueo **nunca** aparece el texto. Si el ajuste del sistema oculta el contenido sensible en la pantalla de bloqueo, sale solo "Nuevo mensaje" (ni siquiera el nombre). Si el sistema muestra todo el contenido, sale "Nuevo mensaje de Contacto nuevo". |

### Cortes de red y descarte

| # | Paso | Resultado esperado |
|---|---|---|
| 21 | Activa el modo avión en B. Espera hasta 1–2 minutos mirando A. | En A, el contacto pasa a "desconectado" (o "conectando" mientras lo vuelve a intentar) y el contador a "en línea: 0" (sin tráfico en 60 s la sesión se da por caída). |
| 22 | Con B aún en modo avión, envía un texto desde A. | `→ <texto> (no enviado)` enseguida: sin sesión el mensaje se descarta. No se guarda ni se reenvía después. |
| 23 | Si en el paso 21 enviaste algo **antes** de que A viera "desconectado": | Ese texto termina como `(no enviado)` como mucho a los 30 s (sin acuse). |
| 24 | Quita el modo avión en B y no toques nada más. | En unos minutos los dos vuelven a "Contacto nuevo · en línea" solos. Los mensajes anteriores siguen en el chat de ambos (el historial en memoria sobrevive a los cortes). El texto descartado del paso 22 no llega a B. |
| 25 | Envía un texto de A a B. | `(entregado)` otra vez. |

### Error, Reintentar y Desconectar

| # | Paso | Resultado esperado |
|---|---|---|
| 26 | En B, pulsa "Desconectar" (en la pantalla o en la notificación). | La notificación fija desaparece. "Estado: detenido" y botón "Activar". El chat de B queda vacío (el historial se borra al detener). En A, el contacto pasa a "desconectado" en pocos segundos. |
| 27 | Con B detenido y en modo avión, pulsa "Activar". Espera hasta unos 3 minutos. | "Estado: arrancando Tor (N %)" y después "Estado: error · <motivo>" (p. ej. "Tor no terminó de arrancar"). La notificación fija pasa a "GhostP2 sin conexión · error" / "Abre la app para reintentar", con "Desconectar". En la pantalla, los botones "Reintentar" y "Desconectar". |
| 28 | Quita el modo avión y pulsa "Reintentar". | Vuelve a "arrancando Tor" y a "Estado: en línea". La notificación vuelve a "GhostP2 activo · cifrado". El contacto vuelve a "en línea" en ambos. |
| 29 | Repite el paso 27 y esta vez pulsa "Desconectar". | La notificación desaparece y queda "Estado: detenido". Quita el modo avión y pulsa "Activar" para seguir. |

Si el almacenamiento cifrado no se puede leer (Android Keystore no disponible), la pantalla
muestra "No se pudo acceder a tus datos cifrados" con "Reintentar" y no arranca nada (regla
R16). No hay forma sencilla de provocarlo a mano: está cubierto por pruebas.

### El sistema mata el proceso (regla R19)

| # | Paso | Resultado esperado |
|---|---|---|
| 30 | Con A "en línea" y exento de optimización de batería (paso 6), mata su proceso como lo haría el sistema: `adb -s <serie-A> shell pidof org.ghostp2.app` y luego `adb -s <serie-A> shell run-as org.ghostp2.app kill -9 <pid>` (funciona porque el APK es de depuración). | La notificación desaparece un momento. En segundos (el sistema decide cuándo) el servicio vuelve solo: "GhostP2 activo · cifrado", Tor arranca y A vuelve a "en línea" con B sin abrir la app. El historial de A empieza vacío (estaba solo en memoria). |
| 31 | (Android 12+.) Repite el paso 30 **sin** la exención de batería (quítala en los ajustes del sistema). | Puede ocurrir lo mismo que en el paso 30, o el sistema puede no permitir que el servicio vuelva al primer plano. En ese caso se detiene y deja la notificación "GhostP2 se ha detenido" / "Abre la app para volver a activarlo". |
| 32 | Pulsa "Desconectar" en A y repite el `kill` del paso 30 (o, sin `adb`, espera a que el sistema cierre la app). | No vuelve nada: "Desconectar" lo detiene del todo. |
| 33 | Con A activo, reinicia el móvil. | Tras el reinicio GhostP2 **no** arranca solo: no hay notificación fija y al abrirlo muestra "Estado: detenido". |

No uses `adb shell am force-stop` para el paso 30: equivale a que el usuario fuerce la
detención, y Android no vuelve a crear el servicio.

### Fin

| # | Paso | Resultado esperado |
|---|---|---|
| 34 | Pulsa "Desconectar" en ambos. | La notificación fija desaparece en los dos y queda "Estado: detenido". |

### Reinstalación (opcional, regla R14)

| # | Paso | Resultado esperado |
|---|---|---|
| 35 | Con A y B activos y emparejados, desinstala GhostP2 de B, vuelve a instalarlo y actívalo. | B empieza de cero: "Contactos: 0", identidad y .onion nuevas. En A, el contacto antiguo queda "desconectado" para siempre (con ratos en "conectando" mientras lo reintenta: su .onion ya no existe). A no recibe ningún aviso. |
| 36 | Empareja de nuevo (pasos 7, 11 y 12). | Emparejamiento correcto con un número de seguridad **distinto** del anterior. En A aparece un segundo "Contacto nuevo" (el antiguo sigue, desconectado: la pantalla de diagnóstico no permite borrarlo). |

## Lo que esta pantalla no permite probar

- **Borrar un contacto y el aviso al otro lado** ("<nombre> te ha eliminado de sus
  contactos"). El motor lo implementa (`MessengerEngine.deleteContact`, con `Unpair` y hasta
  2 min de intentos de entrega) y lo cubren las pruebas `UnpairTest`, pero la pantalla de
  diagnóstico no tiene botón para borrar contactos. Queda para el plan 3.
- **"Borrar" la conversación**, renombrar, marcar como verificado y "Borrar todo". Están en el
  motor y no en la pantalla. "Empezar de cero" solo aparece si el almacenamiento falla.
- **Identidad cambiada** ("La identidad de <nombre> ha cambiado"): hace falta que un solo lado
  vuelva a emparejar con la misma identidad, y para eso hay que borrar un contacto. Lo cubren
  las pruebas de `ConnectionsTest`.
- **Fotos**: la pantalla solo muestra "[foto]" para las recibidas y no permite enviarlas.
