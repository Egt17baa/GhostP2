# :core:tor no necesita reglas propias de R8 (comprobado con :app:assembleRelease y minify):
# - kmp-tor no trae reglas y no las necesita: su reflexión es sobre clases del sistema
#   (android.net.LocalSocket, java.net.UnixDomainSocketAddress, android.os.Build) que R8 no renombra.
# - Los geoip se leen como recursos Java con ruta absoluta (/io/matthewnelson/kmp/tor/resource/geoip/...),
#   que R8 conserva aunque renombre la clase que los carga.
# - KmpTorResourceInitializer se declara en el manifiesto (androidx.startup); AAPT genera su regla keep
#   y startup-runtime trae las suyas.
