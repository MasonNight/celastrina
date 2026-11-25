// R8 newer than AGP 8.7's own, which can't read Kotlin 2.3's metadata. The release build keeps that metadata for
// Extensions; AGP takes the newest R8 on the build classpath.
buildscript { dependencies { classpath(libs.r8) } }

plugins {
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.animalsniffer) apply false
}

// The build logic's own tests.
tasks.check { dependsOn(gradle.includedBuild("build-logic").task(":check")) }
