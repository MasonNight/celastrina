import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest

// The test Extension: a CloudStream `.cs3` targeting the Internet Archive, and an Extension Repository listing it. Used
// only by automated tests; never released, recommended or imported by the runbook.
//
// The `.cs3` holds what CloudStream's own Gradle plugin puts in one (`manifest.json` and `classes.dex`, which Android
// loads) plus the same classes as JVM `.class` files, so the Core's tests can load it on the JVM too.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Plugins are built for old Androids, as CloudStream's plugin template builds them.
kotlin {
    jvmToolchain(17)
    compilerOptions { jvmTarget.set(JvmTarget.JVM_1_8) }
}
java { targetCompatibility = JavaVersion.VERSION_1_8 }

val d8: Configuration by configurations.creating

dependencies {
    // Provided by Celastrina at run time, as the CloudStream app provides them to real plugins.
    compileOnly(libs.cloudstream.library)
    compileOnly(libs.nicehttp)
    compileOnly(libs.jackson.module.kotlin)
    d8(libs.r8)
}

val pluginName = "InternetArchive"
val manifest = """{"name":"$pluginName","pluginClassName":"dev.celastrina.testextension.InternetArchivePlugin","requiresResources":false,"version":1}"""

val dex by tasks.registering(JavaExec::class) {
    description = "Dexes the test Extension for Android, at Celastrina's minSdk."
    val jar = tasks.jar.flatMap { it.archiveFile }
    val output = layout.buildDirectory.dir("dex")
    inputs.file(jar)
    outputs.dir(output)
    classpath = d8
    mainClass.set("com.android.tools.r8.D8")
    argumentProviders += CommandLineArgumentProvider {
        listOf("--release", "--min-api", "25", "--output", output.get().asFile.path, "--lib", javaLauncher.get().metadata.installationPath.asFile.path) +
            configurations.compileClasspath.get().files.flatMap { listOf("--classpath", it.path) } +
            jar.get().asFile.path
    }
    doFirst { output.get().asFile.mkdirs() }
}

val cs3 by tasks.registering(Zip::class) {
    description = "Packs the test Extension as a CloudStream .cs3."
    archiveFileName.set("$pluginName.cs3")
    destinationDirectory.set(layout.buildDirectory.dir("cs3"))
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(sourceSets.main.map { it.output.classesDirs })
    from(dex)
    from(resources.text.fromString(manifest)) { rename { "manifest.json" } }
}

val testRepository by tasks.registering {
    description = "Writes the test Extension Repository: repo.json, plugins.json and the .cs3, with relative URLs."
    val file = cs3.flatMap { it.archiveFile }
    val output = layout.buildDirectory.dir("repository")
    inputs.file(file)
    outputs.dir(output)
    doLast {
        val dir = output.get().asFile.apply { deleteRecursively(); mkdirs() }
        val bytes = file.get().asFile.readBytes()
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        dir.resolve("$pluginName.cs3").writeBytes(bytes)
        dir.resolve("repo.json").writeText(
            """{"name":"Celastrina test Extensions","description":"Only for Celastrina's automated tests.","manifestVersion":1,"pluginLists":["plugins.json"]}""",
        )
        dir.resolve("plugins.json").writeText(
            """[{"url":"$pluginName.cs3","status":1,"version":1,"apiVersion":1,"name":"Internet Archive (test)",""" +
                """"internalName":"$pluginName","authors":["Celastrina"],"description":"Public-domain and CC-licensed films from the Internet Archive.",""" +
                """"repositoryUrl":"https://github.com/MasonNight/celastrina","tvTypes":["Movie","TvSeries"],"language":"en",""" +
                """"iconUrl":null,"fileSize":${bytes.size},"fileHash":"sha256-$sha256"}]""",
        )
    }
}

// The Core's tests read the test Extension Repository from here.
val testRepositoryElements: Configuration by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts.add(testRepositoryElements.name, testRepository.map { it.outputs.files.singleFile }) { builtBy(testRepository) }
