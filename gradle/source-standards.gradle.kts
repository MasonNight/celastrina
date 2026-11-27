import dev.celastrina.build.SourceStandards

buildscript {
    repositories { mavenCentral() }
    dependencies { classpath("dev.celastrina.build:build-logic") }
}

val coreSources = fileTree("core/src/main") { include("**/*.kt", "**/*.java") }
val applicationSources = fileTree("app/src/main") { include("**/*.kt") }
val sourceGlossary = layout.projectDirectory.file("GLOSSARY.md")

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
    inputs.file(sourceGlossary).withPathSensitivity(PathSensitivity.RELATIVE)
    doLast {
        val glossary = sourceGlossary.asFile.readText()
        val violations = (coreSources.files + applicationSources.files).filter { it.extension == "kt" }.sortedBy { it.path }.flatMap { source ->
            val path = source.relativeTo(projectDir).invariantSeparatorsPath
            SourceStandards.glossaryNames(glossary, source.readText(), path).map { violation ->
                "$path:${violation.line}: ${violation.identifier} uses the glossary's Avoid entry '${violation.avoid.joinToString(" ")}'"
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException("Use domain names from GLOSSARY.md:\n${violations.distinct().joinToString("\n")}")
        }
    }
}

tasks.named("check") { dependsOn(checkCloudStreamImports, checkGlossaryNames) }
