plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.ghostp2.tor"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:engine"))
    implementation(libs.kmp.tor.runtime)
    // Binarios de tor (libtor.so + ejecutable) por ABI; se extraen al instalar (ver Task 10: useLegacyPackaging).
    implementation(libs.kmp.tor.resource.exec.tor)
    // Puentes (R30): Lyrebird (obfs4, webtunnel) y Snowflake en una biblioteca Go (libgojni.so por ABI), en el
    // proceso de la app. Solo se carga con un ajuste de puentes distinto de «No» (ver IptProxyController).
    implementation(libs.iptproxy)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
}
