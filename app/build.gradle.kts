import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import java.io.ByteArrayOutputStream
import java.util.zip.ZipFile

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

    lint {
        warningsAsErrors = true
        // Lint fails the build on any warning. "A newer version exists" is news, not a defect, and would turn CI red
        // whenever a library ships.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
}

/**
 * Checks a built APK against the platform rules every Celastrina build must keep: no native code, minSdk 25,
 * a LEANBACK_LAUNCHER activity, and leanback and touchscreen declared not required. `check` runs it on the
 * debug APK; the release pipeline runs `verifyReleaseApk`.
 */
abstract class VerifyApk : DefaultTask() {
    @get:InputFiles abstract val apkFolder: DirectoryProperty
    @get:Internal abstract val artifactsLoader: Property<BuiltArtifactsLoader>
    @get:InputFile abstract val aapt2: RegularFileProperty

    @TaskAction
    fun verify() {
        val apks = checkNotNull(artifactsLoader.get().load(apkFolder.get())) { "No APK in ${apkFolder.get()}" }
            .elements.map { File(it.outputFile) }
        val problems = apks.flatMap { apk -> problemsIn(apk).map { "${apk.name}: $it" } }
        if (problems.isNotEmpty()) throw GradleException("APK breaks Celastrina's platform rules:\n" + problems.joinToString("\n"))
    }

    private fun problemsIn(apk: File): List<String> {
        val nativeCode = ZipFile(apk).use { zip ->
            zip.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") }.toList()
        }
        val badging = ByteArrayOutputStream().also { out ->
            val process = ProcessBuilder(aapt2.get().asFile.path, "dump", "badging", apk.path).redirectErrorStream(true).start()
            process.inputStream.copyTo(out)
            check(process.waitFor() == 0) { "aapt2 failed on $apk: $out" }
        }.toString()
        return buildList {
            nativeCode.forEach { add("contains native code $it (Celastrina ships none; see the packaging block)") }
            // aapt2 34 prints sdkVersion:'25', aapt2 35 prints minSdkVersion:'25'.
            val minSdk = Regex("""^(?:min)?[sS]dkVersion:'(\d+)'""", RegexOption.MULTILINE).find(badging)?.groupValues?.get(1)
            if (minSdk != "25") add("minSdk is $minSdk, not 25")
            if ("leanback-launchable-activity:" !in badging) add("no LEANBACK_LAUNCHER activity")
            if ("uses-feature-not-required: name='android.software.leanback'" !in badging) add("leanback is not declared not-required")
            if ("uses-feature-not-required: name='android.hardware.touchscreen'" !in badging) add("touchscreen is not declared not-required")
        }
    }
}

androidComponents {
    onVariants { variant ->
        tasks.register<VerifyApk>("verify${variant.name.replaceFirstChar(Char::uppercase)}Apk") {
            group = "verification"
            description = "Checks the ${variant.name} APK against Celastrina's platform rules."
            apkFolder.set(variant.artifacts.get(SingleArtifact.APK))
            artifactsLoader.set(variant.artifacts.getBuiltArtifactsLoader())
            aapt2.set(
                androidComponents.sdkComponents.sdkDirectory.map {
                    it.file("build-tools/${android.buildToolsVersion}/aapt2")
                },
            )
        }
    }
}

tasks.named("check") { dependsOn("verifyDebugApk") }

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
