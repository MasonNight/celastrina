// Plain Kotlin on Gradle's own Kotlin, so build scripts can load it.
plugins { `embedded-kotlin` }

group = "dev.celastrina.build"

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(kotlin("test-junit5"))
}

tasks.test { useJUnitPlatform() }
