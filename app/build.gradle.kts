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

    // Firma de la versión de lanzamiento: solo si existen las cuatro variables (en CI, desde los
    // secretos de GitHub). Si falta alguna, assembleRelease genera APK sin firmar.
    val firma = listOf(
        "GHOSTP2_KEYSTORE_FILE",
        "GHOSTP2_KEYSTORE_PASSWORD",
        "GHOSTP2_KEY_ALIAS",
        "GHOSTP2_KEY_PASSWORD",
    ).associateWith { providers.environmentVariable(it).orNull }
    val hayFirma = firma.values.all { !it.isNullOrEmpty() }

    signingConfigs {
        if (hayFirma) {
            create("release") {
                storeFile = file(firma.getValue("GHOSTP2_KEYSTORE_FILE")!!)
                storePassword = firma.getValue("GHOSTP2_KEYSTORE_PASSWORD")
                keyAlias = firma.getValue("GHOSTP2_KEY_ALIAS")
                keyPassword = firma.getValue("GHOSTP2_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            if (hayFirma) signingConfig = signingConfigs.getByName("release")
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

    // F-Droid: sin el bloque de dependencias cifrado por Google en el APK ni en el AAB.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

kotlin {
    jvmToolchain(17)
}

/**
 * Avisos de terceros en la APK (`assets/licenses/`): `THIRD_PARTY_NOTICES.md` y los textos de `LICENSES/` de la
 * raíz del repositorio, tal cual. Ajustes › Acerca de › «Licencias de terceros» los lee de ahí, sin red.
 */
abstract class LicenseAssets : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val notices: RegularFileProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val licenses: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:javax.inject.Inject
    abstract val files: FileSystemOperations

    @TaskAction
    fun copy() {
        files.sync {
            into(outputDir)
            from(notices) { into("licenses") }
            from(licenses) { into("licenses/LICENSES") }
        }
    }
}

val licenseAssets = tasks.register<LicenseAssets>("licenseAssets") {
    notices.set(rootProject.layout.projectDirectory.file("THIRD_PARTY_NOTICES.md"))
    licenses.set(rootProject.layout.projectDirectory.dir("LICENSES"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(licenseAssets, LicenseAssets::outputDir)
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:tor"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.biometric)
    // El diálogo de huella de biometric 1.1.0 (API 26-28) es un AlertDialog de AppCompat: el tema de la
    // app tiene que ser AppCompat (Theme.GhostP2).
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.zxing.core)
    implementation(libs.androidx.exifinterface)

    testImplementation(libs.junit4)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.coroutines.test)
    // FakeTor y repositorios en memoria del motor, inyectados mediante AppContainer.
    testImplementation(testFixtures(project(":core:engine")))
}
