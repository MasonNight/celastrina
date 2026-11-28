package dev.celastrina.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceStandardsTest {
    @Test
    fun `smoke visits can live in top level shared helpers but not unrelated tests`() {
        val sources = SourceStandards.reachableSmokeSources(linkedMapOf(
            "ScreenSmokeTest.kt" to "class ScreenSmokeTest { fun opens() { visitHome() } }",
            "SmokeVisits.kt" to "fun visitHome() { HomeScreen() }",
            "UnrelatedTest.kt" to "class UnrelatedTest { fun opens() { NewScreen() } }",
        ))
        assertEquals(listOf("NewScreen"), SourceStandards.unvisitedScreens(
            listOf("@Composable fun HomeScreen() {}\n@Composable fun NewScreen() {}"), sources, emptySet(),
        ))
    }

    @Test
    fun `importing Composable does not turn ordinary functions into screens and generic screens count`() {
        assertEquals(listOf("GenericScreen"), SourceStandards.unvisitedScreens(
            listOf("import androidx.compose.runtime.Composable\nfun PlainScreen() {}\n@Composable fun <T> GenericScreen(value: T) {}"),
            emptyList(), emptySet(),
        ))
    }

    @Test
    fun `library names declared by destructuring and lambda parameters are checked`() {
        assertEquals(listOf("contentDescription", "contentScale"), SourceStandards.glossaryNames(
            "_Avoid_: Content", "fun demo() { val (contentDescription, other) = pair; rows.forEach { contentScale -> print(contentScale) } }",
        ).map { it.identifier })
    }

    @Test
    fun `every top level composable screen needs a smoke visit or a reasoned exception`() {
        val source = "@Composable fun HomeScreen() {}\n@Composable fun NewScreen() {}"
        assertEquals(listOf("NewScreen"), SourceStandards.unvisitedScreens(
            listOf(source), listOf("fun HomeScreen_opens() {}"), emptySet(),
        ))
        assertTrue(SourceStandards.unvisitedScreens(
            listOf(source), listOf("fun HomeScreen_opens() {}"), setOf("NewScreen"),
        ).isEmpty())
        assertTrue(SourceStandards.unvisitedScreens(
            listOf("// @Composable fun FakeScreen() {}\nclass Nested { @Composable fun NestedScreen() {} }"),
            emptyList(), emptySet(),
        ).isEmpty())
    }

    @Test
    fun `library arguments and modifiers are allowed but declarations remain domain names`() {
        val glossary = "_Avoid_: Content, Image, Show"
        assertTrue(SourceStandards.glossaryNames(glossary,
            "import androidx.compose.ui.layout.ContentScale\nAsyncImage(contentDescription = null, contentScale = ContentScale.Fit); Modifier.wrapContentSize()",
        ).isEmpty())
        assertEquals(listOf("contentUrl", "contentDescription", "contentScale", "wrapContentSize"),
            SourceStandards.glossaryNames(glossary,
                "val contentUrl = 0; val contentDescription = 0; fun screen(contentScale: Int) {}; fun wrapContentSize() {}",
            ).map { it.identifier })
        assertTrue(SourceStandards.glossaryNames(glossary,
            "focus.requestFocusOnceShown(); focus.requestFocusUntil { true }",
        ).isEmpty())
    }

    @Test
    fun `Windows line endings preserve import boundaries`() {
        val source = "package dev.celastrina.core.extension\r\nimport com.lagradost.cloudstream3.plugins.BasePlugin\r\n"
        assertTrue(SourceStandards.cloudStreamImports("extension/ExtensionHost.kt", source).isEmpty())
    }

    @Test
    fun `Windows line endings preserve external glossary names`() {
        assertTrue(SourceStandards.glossaryNames(
            "_Avoid_: Content", "import androidx.compose.ui.layout.ContentScale\r\nval scale = ContentScale.Fit\r\n",
        ).isEmpty())
    }

    @Test
    fun `only the Extension adapter and the ExtensionLoader contract may import CloudStream`() {
        val import = "import com.lagradost.cloudstream3.plugins.BasePlugin"
        assertEquals(listOf(1), SourceStandards.cloudStreamImports("source/ConfigurationService.kt", import))
        assertTrue(SourceStandards.cloudStreamImports(
            "extension/ExtensionHost.kt", "package dev.celastrina.core.extension\n$import",
        ).isEmpty())
        val port = "package dev.celastrina.core.port\n$import"
        assertTrue(SourceStandards.cloudStreamImports("port/ExtensionLoader.kt", port).isEmpty())
        assertEquals(listOf(2), SourceStandards.cloudStreamImports("port/ExtensionLoader.kt", "$port as Foundation"))
        assertEquals(listOf(1), SourceStandards.cloudStreamImports("extension/helper/Other.kt", import))
        assertEquals(listOf(1), SourceStandards.cloudStreamImports("source/Service.kt", "import com . lagradost.*"))
        assertEquals(listOf(1), SourceStandards.cloudStreamImports(
            "java/dev/celastrina/core/Service.java",
            "import com /* comment\n continues */ .lagradost.cloudstream3.plugins.BasePlugin;",
        ))
        assertTrue(SourceStandards.cloudStreamImports("source/Service.kt", "/*\n$import\n*/").isEmpty())
    }

    @Test
    fun `glossary words match whole Kotlin identifier segments and exclude comments and literal text`() {
        val glossary = """
            _Avoid_: Provider, Not found, Series (for one part)
            _Avoid_: Error
        """.trimIndent()
        val source = """
            // Provider Error
            val note = "Error"
            val x = 1
            val providerCount = 1
            val notFound = x
            val VALIDATION_ERROR_CODE = x
            val providerlike = x
            val onError = { }
            val `previously not found` = x
            val count = "${'$'}providerCount"
        """.trimIndent()
        assertEquals(
            listOf("4: providerCount", "5: notFound", "6: VALIDATION_ERROR_CODE", "9: previously not found", "10: providerCount"),
            SourceStandards.glossaryNames(glossary, source).map { "${it.line}: ${it.identifier}" },
        )
    }

    @Test
    fun `preferred glossary terms protect only their own identifier segments`() {
        val glossary = """
            **Media server**:
            _Avoid_: Library server
            **Title**:
            _Avoid_: Media
        """.trimIndent()
        assertEquals(
            listOf("mediaServerMedia", "otherMedia"),
            SourceStandards.glossaryNames(glossary, "val mediaServer = 0; val mediaServerMedia = otherMedia")
                .map { it.identifier },
        )
    }

    @Test
    fun `comments and apostrophes in glossary entries do not become prohibited phrases`() {
        val glossary = "_Avoid_: Metadata (that's the Catalogue's), quality info"
        assertEquals(
            listOf("metadata", "quality info"),
            SourceStandards.glossaryNames(glossary, "val streamMetadata = qualityInfo").map { it.avoid.joinToString(" ") },
        )
    }

    @Test
    fun `import aliases are names chosen by Celastrina`() {
        val source = "import kotlin.collections.Map as Provider"
        assertEquals(listOf("Provider"), SourceStandards.glossaryNames("_Avoid_: Provider", source).map { it.identifier })
    }

    @Test
    fun `legacy names need an explicit exception in the file that uses them`() {
        val glossary = "_Avoid_: User, Kind, Metadata"
        val source = "class User; enum class Kind { Title }; val metadata = Unit"

        assertEquals(
            listOf("User", "Kind", "metadata"),
            SourceStandards.glossaryNames(glossary, source).map { it.identifier },
        )
        assertEquals(
            listOf("Kind", "metadata"),
            SourceStandards.glossaryNames(glossary, source, fileExceptions = setOf("User")).map { it.identifier },
        )
    }
}
