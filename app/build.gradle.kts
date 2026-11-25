import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import dev.celastrina.build.ReleaseVersion
import java.io.ByteArrayOutputStream
import java.util.zip.ZipFile
import javax.inject.Inject

buildscript { dependencies { classpath(libs.celastrina.build.logic) } }

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

// A release build: the release workflow passes the tag that started it (-PreleaseTag=v1.2.3) and every earlier
// release's tag (-PreleasedTags=v1.2.2,v1.2.1). The tag sets the version, and must be higher than every earlier release.
val releaseVersion: ReleaseVersion? = providers.gradleProperty("releaseTag").orNull?.let { tag ->
    val released = providers.gradleProperty("releasedTags").getOrElse("").split(',').map(String::trim).filter(String::isNotEmpty)
    try {
        ReleaseVersion.nextRelease(tag, released)
    } catch (e: IllegalArgumentException) {
        throw GradleException(e.message!!, e)
    }
}

// The release signing key, which only the release workflow has (docs/release-keystore.md). Without it the release APK
// is left unsigned; the CI gates sign it with a throwaway key.
val releaseKeystore: String? = providers.environmentVariable("CELASTRINA_KEYSTORE").orNull
val releaseKeystoreSecrets = listOf("CELASTRINA_KEYSTORE_PASSWORD", "CELASTRINA_KEY_ALIAS", "CELASTRINA_KEY_PASSWORD")
    .associateWith { providers.environmentVariable(it).orNull }

android {
    namespace = "dev.celastrina.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.celastrina"
        minSdk = 25
        targetSdk = 35
        versionCode = releaseVersion?.code ?: 1
        versionName = releaseVersion?.name ?: "0.0.0"
        manifestPlaceholders["appName"] = "Celastrina"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "TMDB_API_KEY", "\"${tmdbApiKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = releaseKeystoreSecrets["CELASTRINA_KEYSTORE_PASSWORD"]
                keyAlias = releaseKeystoreSecrets["CELASTRINA_KEY_ALIAS"]
                keyPassword = releaseKeystoreSecrets["CELASTRINA_KEY_PASSWORD"]
                // minSdk 25 verifies v2; Fire OS 8 refuses v1-only APKs. v3 lets the key be rotated later.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    // The emulator gate runs the instrumented tests against the minified release APK: -PtestBuildType=release.
    testBuildType = providers.gradleProperty("testBuildType").getOrElse("debug")

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
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

/**
 * The instrumented tests' assets for loading the test Extension: the test Extension Repository Gradle builds, and the
 * Core's recordings of the sites the test Extension reaches.
 */
abstract class ExtensionTestAssets : DefaultTask() {
    @get:InputFiles abstract val testRepository: ConfigurableFileCollection
    @get:InputDirectory abstract val recordings: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @get:Inject abstract val files: FileSystemOperations

    @TaskAction
    fun copy() {
        files.sync {
            into(outputDir)
            from(testRepository) { into("test-repository") }
            from(recordings) {
                include("wikidata/**", "internet-archive/**", "internet-archive-search/**")
                into("recorded")
            }
        }
    }
}

val extensionTestRepository: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

val extensionTestAssets = tasks.register<ExtensionTestAssets>("extensionTestAssets") {
    testRepository.from(extensionTestRepository)
    recordings.set(rootProject.layout.projectDirectory.dir("core/src/testFixtures/resources/recorded"))
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

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
        variant.androidTest?.sources?.assets?.addGeneratedSourceDirectory(extensionTestAssets, ExtensionTestAssets::outputDir)
    }
}

tasks.named("check") { dependsOn("verifyDebugApk") }

// Run by the release workflow with -PreleaseTag (and -PreleasedTags): fails on a tag that isn't the next release, and
// prints the version it names.
tasks.register("printReleaseVersion") {
    description = "Prints the version -PreleaseTag names, after checking it is higher than every -PreleasedTags release."
    val name = checkNotNull(releaseVersion) { "Pass -PreleaseTag=vMAJOR.MINOR.PATCH" }.name
    doLast { println(name) }
}

// A release build can't go without the project TMDB key or the signing key.
val verifyReleaseInputs by tasks.registering {
    description = "Fails a release build (-PreleaseTag) that lacks TMDB_API_KEY or the signing key."
    val missing = buildList {
        if (tmdbApiKey.isBlank()) add("TMDB_API_KEY")
        if (releaseKeystore.isNullOrBlank()) add("CELASTRINA_KEYSTORE")
        releaseKeystoreSecrets.filterValues { it.isNullOrEmpty() }.keys.forEach(::add)
    }
    doLast { if (missing.isNotEmpty()) throw GradleException("A release build needs ${missing.joinToString()}") }
}
if (releaseVersion != null) tasks.named("preBuild") { dependsOn(verifyReleaseInputs) }

dependencies {
    implementation(project(":core"))
    // Bundled for Extensions to link against (ADR 0001); the app's shims extend it.
    implementation(libs.cloudstream.library)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.tv.material)
    implementation(libs.activity.compose)
    implementation(libs.coil.compose)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui)

    extensionTestRepository(project(path = ":test-extension", configuration = "testRepositoryElements"))

    // Seam 2: drives the configuration ContentProvider on a device the way the agent does. Run on the TV with
    // `connectedDebugAndroidTest`. CI's emulator runs ExtensionLoadingTest against the minified release APK.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(kotlin("test-junit"))
    // CloudStream's shared HTTP client, which ExtensionLoadingTest routes to recordings; the app has it at run time.
    androidTestImplementation(libs.nicehttp)
}
