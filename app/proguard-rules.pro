# Reglas de ProGuard/R8 de GhostP2. Solo las mínimas: cada biblioteca trae las suyas (ver los
# consumer-rules.pro de :core:data y :core:tor).

# kmp-process (dependencia de kmp-tor) obtiene el PID en la JVM con java.lang.management, que no existe
# en Android; en Android usa android.os.Process y esa rama nunca se ejecuta. Solo se silencia el aviso.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
