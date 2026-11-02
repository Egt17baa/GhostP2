# GhostP2 — Puentes de Tor disfrazados (diseño)

## Intención

El usuario quiere que GhostP2 pueda conectarse a Tor a través de **puentes disfrazados**
(pluggable transports). Así funciona donde Tor está bloqueado y el operador de red no ve que
se usa Tor. Hay dos condiciones: **todo de código abierto y gratis**, y **la máxima privacidad
posible, muy hermético**.

## Objetivos

- Nuevo ajuste **Ajustes › Puentes**, con estas opciones:
  - **No** (por defecto): conexión directa a Tor, como ahora.
  - **obfs4**: con los puentes obfs4 integrados (la lista pública por defecto del Tor Project, la
    misma que trae Tor Browser).
  - **WebTunnel**: con los puentes WebTunnel integrados, si el Tor Project publica una lista por
    defecto. Si no la publica, esta opción se queda solo en «Mis puentes» (ver «Lo que queda al
    constructor»).
  - **Snowflake**: con su configuración integrada y un aviso de privacidad.
  - **Mis puentes**: el usuario pega una o varias líneas `Bridge` (obfs4 o webtunnel) o las
    escanea con QR (bridges.torproject.org entrega QR). Se validan antes de guardarse.
- Cambiar el ajuste con el motor activo reinicia Tor con la nueva configuración. Las
  conversaciones en memoria se pierden igual que con «Desconectar». Se avisa antes de
  confirmar.
- Mientras se conecta por puente, el inicio muestra «Conectando por puente…» con el progreso.

## No objetivos

- Pedir puentes desde la app (Moat / bridges.torproject.org / Telegram / correo). Nunca se
  descargan solos.
- meek, obfs2/obfs3/ScrambleSuit y DNSTT, aunque la librería los traiga.
- Hacer de proxy Snowflake para otros (`SnowflakeProxy`). No se usa.
- Detección automática de censura ni elegir el transporte solo.
- Arreglar los cortes de red de otras apps. Eso va aparte, después; los puentes no lo resuelven.

## Anti-objetivos (fracaso aunque «funcione»)

- **Caída silenciosa a conexión directa.** Si el puente falla, nunca se conecta sin puente.
  Se muestra el error «No se puede conectar por el puente» y el motor queda en error hasta
  que el usuario cambie el ajuste o reintente.
- **Contactar con algo que el usuario no eligió.** Con «No», IPtProxy ni se inicia (cero
  tráfico nuevo). Con obfs4/WebTunnel/Mis puentes solo se contacta con los puentes. Con
  Snowflake, además, con su intermediario y sus servidores STUN, y la lista STUN **no incluye
  servidores de Google**.
- **Registros.** Ni GhostP2 ni IPtProxy escriben logs a disco ni a logcat. Nunca se registra
  qué puente se usa.
- **Dependencias propietarias o de Google.** La comprobación de CI sobre `releaseRuntimeClasspath`
  sigue pasando.
- **Escuchar fuera de 127.0.0.1.** Los puertos de IPtProxy y de Tor solo en local.

## Restricciones

- Librería: **IPtProxy** (`com.netzarchitekten:IPtProxy`, 5.7.0, Maven Central). Mantenida por
  Guardian Project y Tor Project. Licencias: MIT, con Lyrebird en GPL-3.0 y Snowflake en
  BSD-3. Trae Lyrebird (obfs4 y webtunnel) y Snowflake como biblioteca Go (`libgojni.so`).
  Pesa unos 8,4 MB comprimidos por ABI.
- **Licencia:** el código de GhostP2 sigue en Apache-2.0. La APK distribuida incluye código
  GPL-3.0, así que el README y los avisos de licencias lo indican (Apache-2.0 es compatible
  con GPL-3.0 en ese sentido). Hay que añadir las licencias de IPtProxy, Lyrebird y Snowflake
  a la pantalla o archivo de licencias que ya exista.
- La versión de IPtProxy se fija exacta y se verifica su suma (la verificación de dependencias
  del proyecto, si existe).
- Tor sigue con `OnionTrafficOnly`, sin DNS ni salidas. Los puentes solo cambian el primer salto.
- R9–R29 se mantienen. En particular, R22 (bloqueo de UI), R29 (desconectar al salir) y el
  arranque al encender (R20) usan el ajuste guardado.
- Formato de ajustes 2 (R21): campos nuevos con valor por defecto. Los archivos antiguos se leen
  como «No».
- Las líneas de «Mis puentes» se guardan dentro de `ghostp2.bin` (cifrado, como el resto) y se
  borran con «Borrar todo».
- `CancellationException` se relanza siempre. Nada de logs.

## Enfoques

1. **IPtProxy dentro del proceso (elegido).** Al arrancar Tor con puentes:
   - `Controller(stateDir, …)` con logs desactivados.
   - `start(transporte, "")`, que abre un SOCKS local en 127.0.0.1.
   - `port(transporte)` da el puerto.
   - Tor se configura con `UseBridges 1`, las líneas `Bridge` y
     `ClientTransportPlugin <t> socks5 127.0.0.1:<puerto>`.
   - Al parar Tor, `stop(transporte)`.
2. Compilar Lyrebird y Snowflake nosotros como ejecutables y lanzarlos con
   `ClientTransportPlugin exec`. Descartado: mucho más trabajo y compilación reproducible propia,
   sin ganancia de privacidad.
3. Puentes simples sin disfraz. Descartado: no disfrazan nada, no cumple el objetivo.

## Decisiones (del usuario)

- Puentes disfrazados, todo open source y gratis.
- Máxima privacidad, muy hermético.
- Aprobó la propuesta: opciones No / obfs4 / WebTunnel / Snowflake (con aviso) / Mis puentes;
  sin descargas automáticas; cifrado; sin logs; sin caída silenciosa a directo.

## Decisiones del diseñador (revisables)

- **Por defecto «No».** En España no hacen falta y así no cambia nada para quien no los use.
- **Ajustes:** el texto de ayuda recomienda obfs4/WebTunnel, mejor con «Mis puentes», como lo
  más hermético.
- **Aviso de Snowflake**, al elegirlo: «Snowflake contacta con un intermediario a través de una
  red de distribución de contenidos y con servidores STUN públicos. No ven tus mensajes, pero sí
  tu dirección IP. Úsalo solo si los otros no funcionan.»
- **Lista STUN de Snowflake:** la de Tor Browser sin los servidores de Google (se fija con
  `setSnowflakeIceServers`). Intermediario, dominios frontales y AMP cache: los valores por
  defecto de Tor Browser, fijados en el código, no los internos de la librería, para que no
  cambien sin revisión.
- **Lista integrada:** la lista por defecto de puentes viene en el código (constante revisada),
  con la fecha de origen anotada. Se actualiza en futuras versiones.
- **«Mis puentes»:**
  - Validación: cada línea es `obfs4 <ip:puerto> <huella> cert=… iat-mode=…` o
    `webtunnel <ip:puerto> <huella> url=… [ver=…]`. Se acepta con o sin el prefijo `Bridge`.
  - Se rechazan otros transportes, líneas vacías o mal formadas, con un mensaje claro.
  - Máximo 10 líneas.
  - Si se mezclan obfs4 y webtunnel, se inician los dos transportes.
- **El escáner QR reutiliza CameraX y ZXing**, ya en el proyecto. El QR de bridges.torproject.org
  es un array tipo `['obfs4 …', 'obfs4 …']`, que se parsea.
- **Errores:**
  - Si IPtProxy no arranca o Tor no completa el arranque por puente en un plazo razonable,
    el estado es un nuevo `EngineErrorKind.BRIDGE_FAILED`, con el texto «No se puede conectar
    por el puente. Prueba otro tipo de puente o pega puentes nuevos en Ajustes › Puentes.»
  - El motor no reintenta a directo.
- **Cambio de ajuste con motor activo:** diálogo «Para usar los nuevos puentes hay que
  reconectar. Se cerrarán las conversaciones abiertas.» con [Reconectar] [Cancelar]. Si el motor
  está parado, solo se guarda.
- **Notificación y pantalla de inicio:** sin nombre del puente, solo «por puente».
- **R30**, nueva regla en PROTOCOLO §14: «Puentes».

## Lo que queda al constructor

- Opciones exactas de kmp-tor (`TorOption.UseBridges`, `Bridge`, `ClientTransportPlugin`) y en
  qué punto del arranque (`TorDriver` / `applyGhostConfig`) se inyectan. El puerto de IPtProxy debe
  conocerse antes de generar la configuración.
- Comprobar en tor-browser-build (`pt_config.json` o equivalente) la lista vigente de obfs4 y
  de WebTunnel, y los valores de Snowflake. Si no hay WebTunnel integrado, la opción «WebTunnel»
  se quita de la lista y WebTunnel solo se usa vía «Mis puentes». Documentarlo.
- Que IPtProxy no escriba logs ni en `stateDir` ni en logcat (`enableLogging=false`,
  `unsafeLogging=false`). Verificar que no queda `LogFileName` en `stateDir`. `stateDir` va en
  `noBackupFilesDir`.
- Ciclo de vida: un único `Controller` por arranque de Tor, `stop` en cada parada (incluido
  R29, «Desconectar», `onDestroy` y Tor atascado), sin hilos Go huérfanos.
- R8/ProGuard: mantener las clases `go.*` e `IPtProxy.*`.
- ABIs: la app ya genera APK por ABI. Comprobar que `libgojni.so` va en cada una y que el CI
  que cuenta 3 APK sigue bien.
- Pruebas:
  - Generación de configuración pura en JVM: líneas, plugin y «No» sin nada.
  - Parseo y validación de «Mis puentes» y del QR.
  - Ajustes en formato 2 sin el campo, que se leen como «No».
  - Error de puente sin caída a directo (con transporte falso).
  - UI de Ajustes.
- Integración real con Tor por puente: solo prueba manual (PRUEBA-MANUAL), en red normal y, si
  se puede, con un bloqueo simulado.
- README, PROTOCOLO R30 y avisos de licencias.
