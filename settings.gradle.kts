pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // CloudStream's library and its NiceHttp and NewPipeExtractor dependencies are published only on JitPack.
        maven("https://jitpack.io") {
            content {
                includeGroup("com.github.recloudstream.cloudstream")
                includeGroup("com.github.Blatzar")
                includeGroupByRegex("com\\.github\\..*")
            }
        }
    }
}
rootProject.name = "celastrina"
include(":core", ":app", ":test-extension")
