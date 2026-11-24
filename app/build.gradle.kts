plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// The project TMDB key is a build input, never committed: -PTMDB_API_KEY=..., a line in
// ~/.gradle/gradle.properties, or the TMDB_API_KEY environment variable (how CI passes it).
val tmdbApiKey: String = providers.gradleProperty("TMDB_API_KEY")
    .orElse(providers.environmentVariable("TMDB_API_KEY"))
    .getOrElse("")

android {
    namespace = "dev.celastrina.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.celastrina"
        minSdk = 25
        targetSdk = 35
        versionCode = 1
        versionName = "0.0.0"
        manifestPlaceholders["appName"] = "Celastrina"
        buildConfigField("String", "TMDB_API_KEY", "\"${tmdbApiKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appName"] = "Celastrina Dev"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // Celastrina ships no native code. Compose's ui-graphics pulls in androidx.graphics:graphics-path,
        // whose .so backs Path.iterator() below API 34 and is loaded only when that is called. Nothing in
        // Celastrina iterates Paths; if a future dependency does, it fails loudly with UnsatisfiedLinkError.
        jniLibs.excludes += "lib/*/libandroidx.graphics.path.so"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.core)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.tv.material)
    implementation(libs.activity.compose)
    implementation(libs.coil.compose)
}
