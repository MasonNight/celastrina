// The Core: pure Kotlin, no Android. It reaches the outside world only through the ports in
// dev.celastrina.core.port; the fakes for those ports live in testFixtures.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.animalsniffer)
    `java-test-fixtures`
}

kotlin { jvmToolchain(17) }

// The Core compiles against JDK 17 but runs on Android from API 25, and Android lint doesn't check plain JVM
// modules. `check` runs Animal Sniffer over the shipped code against Android 25's API, so a JDK call Android 25
// lacks (like URLEncoder.encode(String, Charset), API 33) fails the build instead of crashing on the TV.
animalsniffer {
    sourceSets = listOf(project.sourceSets.main.get())
}

dependencies {
    signature(libs.android.signature.api25) { artifact { type = "signature" } }
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.cloudstream.library)

    testFixturesImplementation(libs.kotlinx.coroutines.core)
    testFixturesImplementation(libs.kotlinx.serialization.json)
    testFixturesImplementation(libs.cloudstream.library)
    testFixturesImplementation(libs.nicehttp)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(kotlin("test-junit5"))
}

// The test Extension Repository (repo.json, plugins.json and the test Extension's .cs3), which the Extension tests import.
val testRepository: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies { testRepository(project(path = ":test-extension", configuration = "testRepositoryElements")) }

tasks.test {
    useJUnitPlatform()
    inputs.files(testRepository).withPropertyName("testRepository")
    val repository = testRepository
    doFirst { systemProperty("celastrina.testRepository", repository.singleFile.path) }
}
