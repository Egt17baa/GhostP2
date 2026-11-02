# Avisos de terceros

El código de GhostP2 está bajo **Apache-2.0** ([LICENSE](LICENSE)). La APK incluye además los componentes de
terceros de esta lista, cada uno con su licencia. Para los puentes de Tor (R30) incluye **IPtProxy 5.7.0**
(MIT), que lleva **Lyrebird** (obfs4 y WebTunnel, **GPL-3.0**) y **Snowflake** (BSD-3-Clause) en su biblioteca
nativa `libgojni.so`. Por Lyrebird, **la APK en conjunto se distribuye bajo GPL-3.0**; Apache-2.0 es compatible
con GPL-3.0 en ese sentido y el código de GhostP2 sigue en Apache-2.0.

Los textos completos están en [`LICENSES/`](LICENSES) y, dentro de la APK, en `assets/licenses/` (este
archivo y `LICENSES/`). La app los muestra sin red en Ajustes › Acerca de › «Licencias de terceros».

| Archivo | Contenido |
|---|---|
| `LICENSES/GPL-3.0.txt` | GNU General Public License v3.0 (Lyrebird y, por ella, la APK) |
| `LICENSES/IPtProxy-MIT.txt` | Licencia MIT de IPtProxy 5.7.0 |
| `LICENSES/Snowflake-BSD-3-Clause.txt` | Licencia BSD-3-Clause de Snowflake |
| `LICENSES/Go-modules.txt` | Texto de licencia (y NOTICE) de cada módulo Go de `libgojni.so` |
| `LICENSES/Tor-bundle.txt` | Licencias de tor, libevent, zlib y liblzma (y la nota de los datos geoip) |
| `LICENSES/Java-MIT.txt` | Licencias MIT de Bouncy Castle y Checker Framework qualifiers |
| `LICENSES/Apache-2.0.txt` | Apache License 2.0 (GhostP2 y la mayoría de las bibliotecas Java/Kotlin, OpenSSL) |

## Código fuente (GPL-3.0)

La GPL-3.0 exige que el código fuente correspondiente de la parte GPL esté disponible. Es este:

- **IPtProxy 5.7.0**: etiqueta `5.7.0` de <https://github.com/tladesignz/IPtProxy>, commit
  `46552b8422a200e3e69aa3686b6278be945138a5`
  (<https://github.com/tladesignz/IPtProxy/tree/46552b8422a200e3e69aa3686b6278be945138a5>). Su submódulo
  `dnstt` apunta a `f1b9b97a269f83bad41d2ceef291b4d2c161cd11` de <https://github.com/tladesignz/dnstt>. La
  AAR es `com.netzarchitekten:IPtProxy:5.7.0` de Maven Central (SHA-256
  `567130d6760c64f823c62150d9da7978d6f8d8ef8766e2a5b4f8eb5cdaba5b51`, ver `docs/DESARROLLO.md`).
- **Lyrebird**: la versión que fija `IPtProxy.go/go.mod` de esa etiqueta,
  `v0.0.0-20261008205628-362a60bb4e3f`, es el commit `362a60bb4e3fa434130ef81a9990d83d6d8eaea5` de
  <https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird>
  (<https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird/-/tree/362a60bb4e3fa434130ef81a9990d83d6d8eaea5>).
- **Snowflake** `v2.15.1`: etiqueta `v2.15.1` (commit `4f0256ed4f948d1c90fef465466c6b1e252cb3c4`) de
  <https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/snowflake>.
- **Los demás módulos Go** de `libgojni.so` (tabla de abajo): cada `módulo@versión` está en
  <https://proxy.golang.org> con la suma `h1:` que registra la propia biblioteca (`go version -m libgojni.so`).
  La compilación usó Go 1.27.1 (`-buildmode=c-shared -tags=netcgo -trimpath`, gomobile).
- **GhostP2**: su código es público, en <https://github.com/Egt17baa/GhostP2>; cada versión publicada sale
  de una etiqueta `v*` de ese repositorio (CI la compila con `./gradlew :app:assembleRelease`).

## Biblioteca nativa de los puentes: IPtProxy 5.7.0 (`libgojni.so`)

Versiones tomadas de la información de compilación de Go que lleva `libgojni.so` (igual en arm64-v8a,
armeabi-v7a y x86_64).

| Componente | Versión | Licencia |
|---|---|---|
| IPtProxy (Guardian Project) | `5.7.0` | MIT (`LICENSES/IPtProxy-MIT.txt`) |
| Lyrebird (The Tor Project, Yawning Angel) | `v0.0.0-20261008205628-362a60bb4e3f` | BSD-2-Clause y GPL-3.0: el binario, GPL-3.0 (`LICENSES/GPL-3.0.txt`) |
| Snowflake (Serene Han, Arlo Breault, The Tor Project) | `v2.15.1` | BSD-3-Clause (`LICENSES/Snowflake-BSD-3-Clause.txt`) |
| Biblioteca estándar y runtime de Go (The Go Authors) | `go1.27.1` | BSD-3-Clause |

Todos los módulos Go de `libgojni.so` (los textos, en `LICENSES/Go-modules.txt`):

| Módulo | Versión | Licencia |
|---|---|---|
| `filippo.io/edwards25519` | `v1.2.0` | BSD-3-Clause |
| `github.com/andybalholm/brotli` | `v1.2.2` | MIT |
| `github.com/aws/aws-sdk-go-v2` | `v1.43.4` | Apache-2.0 (con NOTICE) |
| `github.com/aws/aws-sdk-go-v2/config` | `v1.32.35` | Apache-2.0 |
| `github.com/aws/aws-sdk-go-v2/credentials` | `v1.19.34` | Apache-2.0 |
| `github.com/aws/aws-sdk-go-v2/feature/ec2/imds` | `v1.18.35` | Apache-2.0 |
| `github.com/aws/aws-sdk-go-v2/internal/configsources` | `v1.4.35` | Apache-2.0 |
| `github.com/aws/aws-sdk-go-v2/internal/endpoints/v2` | `v2.7.35` | Apache-2.0 |
| `github.com/aws/aws-sdk-go-v2/internal/v4a` | `v1.4.36` | Apache-2.0 |
| `github.com/aws/aws-sdk-go-v2/service/internal/accept-encoding` | `v1.13.15` | Apache-2.0 |
| `github.com/aws/aws-sdk-go-v2/service/internal/presigned-url` | `v1.13.35` | Apache-2.0 |
| `github.com/aws/aws-sdk-go-v2/service/signin` | `v1.5.4` | Apache-2.0 |
| `github.com/aws/aws-sdk-go-v2/service/sqs` | `v1.46.4` | Apache-2.0 |
| `github.com/aws/aws-sdk-go-v2/service/sso` | `v1.33.4` | Apache-2.0 |
| `github.com/aws/aws-sdk-go-v2/service/ssooidc` | `v1.38.4` | Apache-2.0 |
| `github.com/aws/aws-sdk-go-v2/service/sts` | `v1.45.4` | Apache-2.0 |
| `github.com/aws/smithy-go` | `v1.27.6` | Apache-2.0 (con NOTICE) |
| `github.com/beorn7/perks` | `v1.0.1` | MIT |
| `github.com/cespare/xxhash/v2` | `v2.3.0` | MIT |
| `github.com/dchest/siphash` | `v1.2.3` | CC0-1.0 |
| `github.com/flynn/noise` | `v1.1.0` | BSD-3-Clause |
| `github.com/golang/mock` | `v1.6.0` | Apache-2.0 |
| `github.com/google/uuid` | `v1.6.0` | BSD-3-Clause |
| `github.com/gorilla/websocket` | `v1.5.3` | BSD-2-Clause |
| `github.com/klauspost/compress` | `v1.19.2` | BSD-3-Clause, Apache-2.0 y MIT (según el directorio) |
| `github.com/klauspost/cpuid/v2` | `v2.4.0` | MIT |
| `github.com/klauspost/reedsolomon` | `v1.14.1` | MIT |
| `github.com/miekg/dns` | `v1.1.72` | BSD-3-Clause |
| `github.com/munnerz/goautoneg` | `v0.0.0-20191010083416-a7dc8b61c822` | BSD-3-Clause |
| `github.com/patrickmn/go-cache` | `v2.1.0+incompatible` | MIT |
| `github.com/pion/datachannel` | `v1.6.2` | MIT |
| `github.com/pion/dtls/v3` | `v3.1.5` | MIT |
| `github.com/pion/ice/v4` | `v4.4.1` | MIT |
| `github.com/pion/interceptor` | `v0.1.47` | MIT |
| `github.com/pion/logging` | `v0.2.4` | MIT |
| `github.com/pion/mdns/v2` | `v2.1.0` | MIT |
| `github.com/pion/randutil` | `v0.1.0` | MIT |
| `github.com/pion/rtcp` | `v1.2.17` | MIT |
| `github.com/pion/rtp` | `v1.10.5` | MIT |
| `github.com/pion/sctp` | `v1.11.1` | MIT |
| `github.com/pion/sdp/v3` | `v3.0.19` | MIT |
| `github.com/pion/srtp/v3` | `v3.0.13` | MIT |
| `github.com/pion/stun/v3` | `v3.1.6` | MIT |
| `github.com/pion/transport/v4` | `v4.1.0` | MIT |
| `github.com/pion/turn/v5` | `v5.0.12` | MIT |
| `github.com/pion/webrtc/v4` | `v4.2.18` | MIT |
| `github.com/pkg/errors` | `v0.9.1` | BSD-2-Clause |
| `github.com/prometheus/client_golang` | `v1.24.1` | Apache-2.0 (con NOTICE) |
| `github.com/prometheus/client_model` | `v0.6.2` | Apache-2.0 (con NOTICE) |
| `github.com/prometheus/common` | `v0.70.1` | Apache-2.0 (con NOTICE) |
| `github.com/prometheus/procfs` | `v0.21.1` | Apache-2.0 (con NOTICE) |
| `github.com/realclientip/realclientip-go` | `v1.0.0` | 0BSD |
| `github.com/refraction-networking/utls` | `v1.8.2` | BSD-3-Clause |
| `github.com/theodorsm/covert-dtls` | `v1.5.1` | MIT |
| `github.com/tjfoc/gmsm` | `v1.4.1` | Apache-2.0 |
| `github.com/tladesignz/IPtProxy.git` | `v0.0.0-00010101000000-000000000000` | MIT |
| `github.com/txthinking/runnergroup` | `v0.0.0-20250224021307-5864ffeb65ae` | MIT |
| `github.com/txthinking/socks5` | `v0.0.0-20260601051520-339b044ab0eb` | MIT |
| `github.com/wlynxg/anet` | `v0.0.5` | BSD-3-Clause |
| `github.com/xtaci/kcp-go/v5` | `v5.6.72` | MIT |
| `github.com/xtaci/smux` | `v1.5.57` | MIT |
| `gitlab.com/yawning/edwards25519-extra` | `v0.0.0-20231005122941-2149dcafc266` | BSD-3-Clause |
| `gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/goptlib` | `v1.6.0` | CC0-1.0 |
| `gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird` | `v0.0.0-20261008205628-362a60bb4e3f` | BSD-2-Clause y GPL-3.0 (`internal/x25519ell2`, `transports/meeklite`): el binario, GPL-3.0 |
| `gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/ptutil` | `v0.0.0-20250815012447-418f76dcf315` | BSD-3-Clause |
| `gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/snowflake/v2` | `v2.15.1` | BSD-3-Clause |
| `gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/webtunnel` | `v0.0.7` | MIT |
| `golang.org/x/crypto` | `v0.57.0` | BSD-3-Clause |
| `golang.org/x/mobile` | `v0.0.0-20260908204917-8b95e45f8d3e` | BSD-3-Clause |
| `golang.org/x/net` | `v0.60.0` | BSD-3-Clause |
| `golang.org/x/sys` | `v0.48.0` | BSD-3-Clause |
| `golang.org/x/text` | `v0.42.0` | BSD-3-Clause |
| `golang.org/x/time` | `v0.15.0` | BSD-3-Clause |
| `google.golang.org/protobuf` | `v1.36.11` | BSD-3-Clause |
| `www.bamsoftware.com/git/dnstt.git` | `v1.20260501.0` | CC0-1.0 |

## Tor (`libtor.so`, kmp-tor-resource 409.5.0)

kmp-tor-resource compila tor **sin** `--enable-gpl`. Textos en `LICENSES/Tor-bundle.txt` y
`LICENSES/Apache-2.0.txt`.

| Componente | Versión | Licencia |
|---|---|---|
| tor (Roger Dingledine, Nick Mathewson, The Tor Project) | `0.4.9.5` | BSD-3-Clause (y las partes que lista su LICENSE) |
| OpenSSL | `3.5.5` | Apache-2.0 |
| libevent | `2.1.12-stable` | BSD-3-Clause |
| zlib (Jean-loup Gailly, Mark Adler) | `1.3.1` | Zlib |
| liblzma (XZ Utils) | `5.8.2` | 0BSD |
| Datos geoip y geoip6 (IPFire Location, convertidos con `geoip-db-tool` de tor) | 2026-02-12 | CC BY-SA 4.0 (<https://creativecommons.org/licenses/by-sa/4.0/>) |
| kmp-tor-resource (Matthew Nelson) | `409.5.0` | Apache-2.0 |

## Bibliotecas Java y Kotlin

Todas las de `releaseRuntimeClasspath` (`./gradlew :app:dependencies --configuration
releaseRuntimeClasspath`, sin las BOM). Entre ellas: kmp-tor (runtime 2.6.0), Bouncy Castle, Tink, ZXing,
AndroidX y Jetpack Compose, CameraX, Kotlin y kotlinx. Las Apache-2.0 usan el texto de
`LICENSES/Apache-2.0.txt`; las MIT, el de `LICENSES/Java-MIT.txt`.

| Grupo | Artefactos y versiones | Licencia |
|---|---|---|
| `androidx.activity` | activity 1.13.0, activity-compose 1.13.0, activity-ktx 1.13.0 | Apache-2.0 |
| `androidx.annotation` | annotation 1.9.1, annotation-experimental 1.4.1, annotation-jvm 1.9.1 | Apache-2.0 |
| `androidx.appcompat` | appcompat 1.7.1, appcompat-resources 1.7.1 | Apache-2.0 |
| `androidx.arch.core` | core-common 2.2.0, core-runtime 2.2.0 | Apache-2.0 |
| `androidx.autofill` | autofill 1.0.0 | Apache-2.0 |
| `androidx.biometric` | biometric 1.1.0 | Apache-2.0 |
| `androidx.camera` | camera-camera2 1.6.2, camera-camera2-pipe 1.6.2, camera-core 1.6.2, camera-lifecycle 1.6.2, camera-video 1.6.2, camera-view 1.6.2 | Apache-2.0 |
| `androidx.camera.featurecombinationquery` | featurecombinationquery 1.6.2 | Apache-2.0 |
| `androidx.camera.viewfinder` | viewfinder-core 1.5.1 | Apache-2.0 |
| `androidx.collection` | collection 1.5.0, collection-jvm 1.5.0, collection-ktx 1.5.0 | Apache-2.0 |
| `androidx.compose.animation` | animation 1.11.4, animation-android 1.11.4, animation-core 1.11.4, animation-core-android 1.11.4 | Apache-2.0 |
| `androidx.compose.foundation` | foundation 1.11.4, foundation-android 1.11.4, foundation-layout 1.11.4, foundation-layout-android 1.11.4 | Apache-2.0 |
| `androidx.compose.material` | material-icons-core 1.7.8, material-icons-core-android 1.7.8, material-ripple 1.11.4, material-ripple-android 1.11.4 | Apache-2.0 |
| `androidx.compose.material3` | material3 1.4.0, material3-android 1.4.0 | Apache-2.0 |
| `androidx.compose.runtime` | runtime 1.11.4, runtime-android 1.11.4, runtime-annotation 1.11.4, runtime-annotation-android 1.11.4, runtime-retain 1.11.4, runtime-retain-android 1.11.4, runtime-saveable 1.11.4, runtime-saveable-android 1.11.4 | Apache-2.0 |
| `androidx.compose.ui` | ui 1.11.4, ui-android 1.11.4, ui-geometry 1.11.4, ui-geometry-android 1.11.4, ui-graphics 1.11.4, ui-graphics-android 1.11.4, ui-text 1.11.4, ui-text-android 1.11.4, ui-unit 1.11.4, ui-unit-android 1.11.4, ui-util 1.11.4, ui-util-android 1.11.4 | Apache-2.0 |
| `androidx.concurrent` | concurrent-futures 1.1.0, concurrent-futures-ktx 1.1.0 | Apache-2.0 |
| `androidx.core` | core 1.18.0, core-backported-fixes 1.0.0, core-ktx 1.18.0, core-viewtree 1.0.0 | Apache-2.0 |
| `androidx.cursoradapter` | cursoradapter 1.0.0 | Apache-2.0 |
| `androidx.customview` | customview 1.0.0, customview-poolingcontainer 1.0.0 | Apache-2.0 |
| `androidx.documentfile` | documentfile 1.0.0 | Apache-2.0 |
| `androidx.drawerlayout` | drawerlayout 1.0.0 | Apache-2.0 |
| `androidx.dynamicanimation` | dynamicanimation 1.0.0 | Apache-2.0 |
| `androidx.emoji2` | emoji2 1.4.0, emoji2-views-helper 1.4.0 | Apache-2.0 |
| `androidx.exifinterface` | exifinterface 1.4.2 | Apache-2.0 |
| `androidx.fragment` | fragment 1.8.9 | Apache-2.0 |
| `androidx.graphics` | graphics-path 1.0.1 | Apache-2.0 |
| `androidx.interpolator` | interpolator 1.0.0 | Apache-2.0 |
| `androidx.legacy` | legacy-support-core-utils 1.0.0 | Apache-2.0 |
| `androidx.lifecycle` | lifecycle-common 2.10.0, lifecycle-common-java8 2.10.0, lifecycle-common-jvm 2.10.0, lifecycle-livedata 2.10.0, lifecycle-livedata-core 2.10.0, lifecycle-livedata-core-ktx 2.10.0, lifecycle-process 2.10.0, lifecycle-runtime 2.10.0, lifecycle-runtime-android 2.10.0, lifecycle-runtime-compose 2.10.0, lifecycle-runtime-compose-android 2.10.0, lifecycle-runtime-ktx 2.10.0, lifecycle-runtime-ktx-android 2.10.0, lifecycle-viewmodel 2.10.0, lifecycle-viewmodel-android 2.10.0, lifecycle-viewmodel-compose 2.10.0, lifecycle-viewmodel-compose-android 2.10.0, lifecycle-viewmodel-ktx 2.10.0, lifecycle-viewmodel-savedstate 2.10.0, lifecycle-viewmodel-savedstate-android 2.10.0 | Apache-2.0 |
| `androidx.loader` | loader 1.0.0 | Apache-2.0 |
| `androidx.localbroadcastmanager` | localbroadcastmanager 1.0.0 | Apache-2.0 |
| `androidx.media3` | media3-common 1.9.0, media3-container 1.9.0, media3-muxer 1.9.0 | Apache-2.0 |
| `androidx.navigation` | navigation-common 2.9.8, navigation-common-android 2.9.8, navigation-compose 2.9.8, navigation-compose-android 2.9.8, navigation-runtime 2.9.8, navigation-runtime-android 2.9.8 | Apache-2.0 |
| `androidx.navigationevent` | navigationevent 1.0.0, navigationevent-android 1.0.0, navigationevent-compose 1.0.0, navigationevent-compose-android 1.0.0 | Apache-2.0 |
| `androidx.print` | print 1.0.0 | Apache-2.0 |
| `androidx.profileinstaller` | profileinstaller 1.4.0 | Apache-2.0 |
| `androidx.resourceinspection` | resourceinspection-annotation 1.0.1 | Apache-2.0 |
| `androidx.savedstate` | savedstate 1.4.0, savedstate-android 1.4.0, savedstate-compose 1.4.0, savedstate-compose-android 1.4.0, savedstate-ktx 1.4.0 | Apache-2.0 |
| `androidx.startup` | startup-runtime 1.1.1 | Apache-2.0 |
| `androidx.tracing` | tracing 1.3.0, tracing-android 1.3.0, tracing-ktx 1.3.0 | Apache-2.0 |
| `androidx.transition` | transition 1.6.0 | Apache-2.0 |
| `androidx.vectordrawable` | vectordrawable 1.1.0, vectordrawable-animated 1.1.0 | Apache-2.0 |
| `androidx.versionedparcelable` | versionedparcelable 1.1.1 | Apache-2.0 |
| `androidx.viewpager` | viewpager 1.0.0 | Apache-2.0 |
| `androidx.window` | window 1.5.0, window-core 1.5.0, window-core-android 1.5.0 | Apache-2.0 |
| `com.google.auto.value` | auto-value-annotations 1.6.3 | Apache-2.0 |
| `com.google.code.findbugs` | jsr305 3.0.2 | Apache-2.0 |
| `com.google.code.gson` | gson 2.13.2 | Apache-2.0 |
| `com.google.crypto.tink` | tink-android 1.24.0 | Apache-2.0 |
| `com.google.dagger` | dagger 2.59 | Apache-2.0 |
| `com.google.errorprone` | error_prone_annotations 2.41.0 | Apache-2.0 |
| `com.google.guava` | failureaccess 1.0.2, guava 33.3.1-android, listenablefuture 9999.0-empty-to-avoid-conflict-with-guava | Apache-2.0 |
| `com.google.j2objc` | j2objc-annotations 3.0.0 | Apache-2.0 |
| `com.google.zxing` | core 3.5.4 | Apache-2.0 |
| `com.netzarchitekten` | IPtProxy 5.7.0 | Apache-2.0 |
| `io.matthewnelson.encoding` | base16 2.6.0, base16-jvm 2.6.0, base32 2.6.0, base32-jvm 2.6.0, base64 2.6.0, base64-jvm 2.6.0, core 2.6.0, core-jvm 2.6.0, utf8 2.6.0, utf8-jvm 2.6.0 | Apache-2.0 |
| `io.matthewnelson.immutable` | collections 0.3.0, collections-jvm 0.3.0 | Apache-2.0 |
| `io.matthewnelson.kmp-file` | async 0.6.0, async-jvm 0.6.0, file 0.6.0, file-jvm 0.6.0 | Apache-2.0 |
| `io.matthewnelson.kmp-process` | process 0.5.0, process-jvm 0.5.0 | Apache-2.0 |
| `io.matthewnelson.kmp-tor` | common-api 2.4.2, common-api-jvm 2.4.2, common-core 2.4.2, common-core-jvm 2.4.2, resource-compilation-exec-tor 409.5.0, resource-compilation-exec-tor-android 409.5.0, resource-compilation-lib-tor 409.5.0, resource-compilation-lib-tor-android 409.5.0, resource-exec-tor 409.5.0, resource-exec-tor-android 409.5.0, resource-geoip 409.5.0, resource-geoip-jvm 409.5.0, resource-lib-tor 409.5.0, resource-lib-tor-android 409.5.0, runtime 2.6.0, runtime-core 2.6.0, runtime-core-jvm 2.6.0, runtime-ctrl 2.6.0, runtime-ctrl-jvm 2.6.0, runtime-jvm 2.6.0 | Apache-2.0 |
| `jakarta.inject` | jakarta.inject-api 2.0.1 | Apache-2.0 |
| `javax.inject` | javax.inject 1 | Apache-2.0 |
| `org.bouncycastle` | bcprov-jdk18on 1.86 | MIT (Java-MIT.txt) |
| `org.checkerframework` | checker-qual 3.43.0 | MIT (Java-MIT.txt) |
| `org.jetbrains` | annotations 23.0.0 | Apache-2.0 |
| `org.jetbrains.kotlin` | kotlin-stdlib 2.4.21, kotlin-stdlib-common 2.4.21 | Apache-2.0 |
| `org.jetbrains.kotlinx` | atomicfu 0.28.0, atomicfu-jvm 0.28.0, kotlinx-coroutines-android 1.11.0, kotlinx-coroutines-core 1.11.0, kotlinx-coroutines-core-jvm 1.11.0, kotlinx-serialization-cbor 1.12.0, kotlinx-serialization-cbor-jvm 1.12.0, kotlinx-serialization-core 1.12.0, kotlinx-serialization-core-jvm 1.12.0 | Apache-2.0 |
| `org.jspecify` | jspecify 1.0.0 | Apache-2.0 |
| `org.kotlincrypto` | error 0.4.0, error-jvm 0.4.0 | Apache-2.0 |
| `org.kotlincrypto.bitops` | bits 0.3.0, bits-jvm 0.3.0, endian 0.3.0, endian-jvm 0.3.0 | Apache-2.0 |
| `org.kotlincrypto.core` | core 0.8.0, core-jvm 0.8.0, digest 0.8.0, digest-jvm 0.8.0, xof 0.8.0, xof-jvm 0.8.0 | Apache-2.0 |
| `org.kotlincrypto.hash` | sha2 0.8.0, sha2-jvm 0.8.0, sha3 0.8.0, sha3-jvm 0.8.0 | Apache-2.0 |
| `org.kotlincrypto.random` | crypto-rand 0.6.0, crypto-rand-jvm 0.6.0 | Apache-2.0 |
| `org.kotlincrypto.sponges` | keccak 0.5.0, keccak-jvm 0.5.0 | Apache-2.0 |
