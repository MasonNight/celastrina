// The Core: pure Kotlin, no Android. It reaches the outside world only through the ports in
// dev.celastrina.core.port; the fakes for those ports live in testFixtures.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testFixturesImplementation(libs.kotlinx.coroutines.core)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(kotlin("test-junit5"))
}

tasks.test { useJUnitPlatform() }
