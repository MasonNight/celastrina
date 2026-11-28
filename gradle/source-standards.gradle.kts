import dev.celastrina.build.SourceStandards

buildscript {
    repositories { mavenCentral() }
    dependencies { classpath("dev.celastrina.build:build-logic") }
}

val coreSources = fileTree("core/src/main") { include("**/*.kt", "**/*.java") }
val applicationSources = fileTree("app/src/main") { include("**/*.kt") }
val sourceGlossary = layout.projectDirectory.file("GLOSSARY.md")
val glossaryExceptions = layout.projectDirectory.file("gradle/glossary-exceptions.txt")

val checkCloudStreamImports by tasks.registering {
    group = "verification"
    description = "Checks that CloudStream imports stay inside the Core Extension adapter."
    inputs.files(coreSources).withPathSensitivity(PathSensitivity.RELATIVE)
    doLast {
        val sourceRoot = file("core/src/main")
        val violations = coreSources.sortedBy { it.path }.flatMap { source ->
            val corePath = source.relativeTo(sourceRoot).invariantSeparatorsPath
                .removePrefix("kotlin/dev/celastrina/core/")
            SourceStandards.cloudStreamImports(corePath, source.readText())
                .map { line -> "${source.relativeTo(projectDir).invariantSeparatorsPath}:$line" }
        }
        if (violations.isNotEmpty()) {
            throw GradleException("CloudStream imports belong in the Extension adapter:\n${violations.joinToString("\n")}")
        }
    }
}

val checkGlossaryNames by tasks.registering {
    group = "verification"
    description = "Checks Kotlin identifiers against the glossary's Avoid entries."
    inputs.files(coreSources, applicationSources).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(sourceGlossary, glossaryExceptions).withPathSensitivity(PathSensitivity.RELATIVE)
    doLast {
        val glossary = sourceGlossary.asFile.readText()
        val exceptionsByFile = glossaryExceptions.asFile.readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line ->
                val fields = line.split(' ')
                fields.first() to fields.drop(1).toSet()
            }
        val violations = (coreSources.files + applicationSources.files).filter { it.extension == "kt" }.sortedBy { it.path }.flatMap { source ->
            val path = source.relativeTo(projectDir).invariantSeparatorsPath
            SourceStandards.glossaryNames(glossary, source.readText(), exceptionsByFile[path].orEmpty()).map { violation ->
                "$path:${violation.line}: ${violation.identifier} uses the glossary's Avoid entry '${violation.avoid.joinToString(" ")}'"
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException("Use domain names from GLOSSARY.md:\n${violations.distinct().joinToString("\n")}")
        }
    }
}

tasks.named("check") { dependsOn(checkCloudStreamImports, checkGlossaryNames) }

val smokeExceptions = layout.projectDirectory.file("gradle/screen-smoke-exceptions.txt")
val smokeSources = fileTree("app/src/androidTest") { include("**/*.kt") }
val checkScreenSmokeVisits by tasks.registering {
    group = "verification"
    description = "Checks every top-level composable Screen has a ScreenSmokeTest visit."
    inputs.files(applicationSources, smokeSources, smokeExceptions).withPathSensitivity(PathSensitivity.RELATIVE)
    doLast {
        val exceptions = smokeExceptions.asFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
            val fields = line.split(" # ", limit = 2)
            require(fields.size == 2 && fields[1].isNotBlank()) { "Screen exception needs a reason: $line" }
            fields[0].trim()
        }.toSet()
        val reachable = SourceStandards.reachableSmokeSources(smokeSources.associate {
            it.relativeTo(projectDir).invariantSeparatorsPath to it.readText()
        })
        val missing = SourceStandards.unvisitedScreens(
            applicationSources.map { it.readText() }, reachable, exceptions,
        )
        if (missing.isNotEmpty()) throw GradleException("Missing ScreenSmokeTest visits: ${missing.joinToString()}")
    }
}
tasks.named("check") { dependsOn(checkScreenSmokeVisits) }
