// Build logic the app's build script uses, with its own tests. Included by the root build; `./gradlew check` runs them.
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "build-logic"
