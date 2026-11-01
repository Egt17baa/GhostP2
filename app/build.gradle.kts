import com.android.build.api.variant.HostTestBuilder

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.ghostp2.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.ghostp2.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // Un APK por ABI (sin APK universal)
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Los binarios de tor (libtor.so y el ejecutable) deben extraerse al instalar para poder ejecutarse.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(17)
}

// Pruebas locales (Robolectric) solo en debug: el manifiesto de release no declara la actividad de
// ui-test-manifest que necesita createComposeRule(), y el código probado es el mismo en ambas variantes.
androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        variant.hostTests[HostTestBuilder.UNIT_TEST_TYPE]?.enable = false
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:tor"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    implementation(libs.androidx.exifinterface)
    // Actividad vacía para createComposeRule() en las pruebas de Robolectric; nunca entra en el APK de release.
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit4)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    // FakeTor y repositorios en memoria del motor, inyectados mediante AppContainer.
    testImplementation(testFixtures(project(":core:engine")))
}
