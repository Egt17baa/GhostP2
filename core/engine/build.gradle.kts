plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-test-fixtures`
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":core:protocol"))
    api(libs.coroutines.core)
    // BouncyCastle explícito en los dobles de prueba: claves ed25519 y SHA3 para las .onion falsas (R9).
    testFixturesImplementation(libs.bouncycastle)
    testFixturesApi(libs.coroutines.core)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.coroutines.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
