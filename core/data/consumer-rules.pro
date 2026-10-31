# :core:data no necesita reglas propias de R8:
# - kotlinx-serialization trae las suyas (META-INF/com.android.tools/r8) y el plugin genera los
#   serializadores de forma estática (StoredData.serializer()), sin reflexión.
# - tink-android trae META-INF/proguard/protobuf.pro y gson trae META-INF/proguard/gson.pro.
