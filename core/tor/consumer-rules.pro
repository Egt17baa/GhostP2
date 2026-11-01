# Reglas de R8 de :core:tor para quien la use (:app). Solo dos avisos silenciados; nada que conservar
# (comprobado con :app:assembleRelease y minify):
# - kmp-process (dependencia de kmp-tor) obtiene el PID en la JVM con java.lang.management, que no existe
#   en Android; en Android usa android.os.Process y esa rama nunca se ejecuta. Solo se silencia el aviso.
# - kmp-tor no trae reglas y no necesita reglas keep: su reflexión es sobre clases del sistema
#   (android.net.LocalSocket, java.net.UnixDomainSocketAddress, android.os.Build) que R8 no renombra.
# - Los geoip se leen como recursos Java con ruta absoluta (/io/matthewnelson/kmp/tor/resource/geoip/...),
#   que R8 conserva aunque renombre la clase que los carga.
# - KmpTorResourceInitializer se declara en el manifiesto (androidx.startup); AAPT genera su regla keep
#   y startup-runtime trae las suyas.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
