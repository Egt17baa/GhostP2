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

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
}
