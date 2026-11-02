# Reglas de R8 de :core:tor para quien la use (:app). Dos avisos silenciados y, para los puentes (R30), lo que se
# usa por JNI o por reflexión (al final). Comprobado con :app:assembleRelease y minify:
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

# Puentes (R30), IPtProxy (gomobile): el código Go llama por JNI a clases y métodos de go.* e IPtProxy.* por su
# nombre, así que no se pueden renombrar ni quitar. El AAR de IPtProxy 5.7.0 ya trae estas dos reglas en su
# proguard.txt; se repiten aquí para no depender de ello.
-keep class go.** { *; }
-keep class IPtProxy.** { *; }

# Puentes (R30), kmp-tor: KmpTorSupport.kt (torSetting) crea los TorSetting de UseBridges, Bridge y
# ClientTransportPlugin por reflexión con estas funciones internas de runtime-core-jvm 2.6.0 (kmp-tor no tiene
# API pública para ellas). R8 debe conservar el campo y los dos métodos con su nombre.
-keepclassmembers class io.matthewnelson.kmp.tor.runtime.core.config.TorSetting$LineItem {
    public static final io.matthewnelson.kmp.tor.runtime.core.config.TorSetting$LineItem$Companion Companion;
}
-keep class io.matthewnelson.kmp.tor.runtime.core.config.TorSetting$LineItem$Companion {
    public final io.matthewnelson.kmp.tor.runtime.core.config.TorSetting$LineItem toLineItem$io_matthewnelson_kmp_tor_runtime_core_jvm(io.matthewnelson.kmp.tor.runtime.core.config.TorOption, java.lang.String, java.util.Set);
}
-keep class io.matthewnelson.kmp.tor.runtime.core.config.TorSetting$Companion {
    public final io.matthewnelson.kmp.tor.runtime.core.config.TorSetting toSetting$io_matthewnelson_kmp_tor_runtime_core_jvm(io.matthewnelson.kmp.tor.runtime.core.config.TorSetting$LineItem, java.util.Map);
}
