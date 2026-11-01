# GhostP2 — Desconectar al salir de la app (diseño corto)

## Intención

El usuario quiere que, al salir de GhostP2, todo se cierre solo pasados unos minutos si no vuelve:
Tor, las conversaciones en memoria y la notificación. Motivos: privacidad (nada en memoria
cuando no se usa) y comprobar si los cortes de red que nota en otras apps (YouTube Music, Maps)
vienen de GhostP2.

## Objetivos

- Nueva opción en **Ajustes**: «Desconectar al salir de la app», con los valores **Nunca**
  (por defecto), **2 min**, **5 min** y **15 min**.
- Al cumplirse el plazo sin volver a la app: se para el motor (cierra sesiones y Tor), se borran
  las conversaciones de la memoria (ya lo hace `stop()`), se vacía la cola de fotos y se quita la
  notificación fija (se para el servicio en primer plano).
- Al volver a la app después del corte: la pantalla de inicio con «Activar», como tras
  «Desconectar».

## No objetivos

- No cambia el comportamiento con «Nunca» (el de siempre).
- No es un temporizador de inactividad dentro de la app: solo cuenta mientras la app está en
  segundo plano.
- No borra contactos ni identidad (eso es «Borrar todo»).

## Anti-objetivos

- Que se corte sin haber salido de la app (rotar el móvil, abrir el selector de fotos o el de
  «Guardar», el diálogo de permisos o el de la huella no cuentan como salir de forma
  definitiva: el plazo se cancela al volver).
- Que mate un arranque al encender el móvil (R20) en el que el usuario no ha abierto la app.

## Enfoques

1. **Temporizador dentro de la app (elegido por el usuario).** Al pasar MainActivity a segundo
   plano (`onStop` sin cambio de configuración) se arma un temporizador en el ámbito del
   contenedor; al volver (`onStart`) se cancela. Al volver también se comprueba con
   `elapsedRealtime` si el plazo ya venció (por si Android retrasó el temporizador en reposo) y,
   si es así, se desconecta antes de mostrar nada. Sin permisos nuevos.
2. Alarma exacta del sistema (`AlarmManager.setExactAndAllowWhileIdle`): precisa al segundo, pero
   exige el permiso de alarmas exactas en Android 12+ y más complejidad. Descartada.

## Decisiones (del usuario)

- Es una opción de Ajustes, no un comportamiento fijo.
- Enfoque 1.

## Decisiones del diseñador (revisables)

- Valor por defecto **Nunca**.
- Solo se arma si el usuario ha abierto la app en esta ejecución (si el servicio arrancó al
  encender o por reinicio STICKY sin abrir la app, no hay `onStop` y no se arma).
- Se reutiliza el mismo seguimiento de `onStart`/`onStop` que ya usa el bloqueo (R22) y la misma
  regla de ignorar `isChangingConfigurations`.
- La desconexión usa el camino existente de «Desconectar» (`AppContainer.stop()` + parar
  `GhostService`), no uno nuevo.
- Si el motor ya está parado o en error cuando vence el plazo, solo se asegura que el servicio esté
  parado.
- El ajuste se guarda en `AppSettings` (formato 2, campo nuevo con valor por defecto; los
  archivos antiguos se siguen leyendo, R21). Valores permitidos: 0 (Nunca), 2, 5, 15; cualquier
  otro se sanea a 0.
- Regla nueva **R29** en PROTOCOLO §14: «Desconectar al salir».

## Lo que queda al constructor

- Nombres concretos, dónde vive el temporizador (probablemente junto a `AppLock` en el
  contenedor) y las pruebas (Robolectric con reloj inyectable: vence, se cancela al volver,
  vence en reposo y se detecta al volver, no se arma tras un arranque al encender, «Nunca» no
  hace nada, cambio de configuración no cuenta).
- Actualizar PRUEBA-MANUAL y README.
