package dev.celastrina.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceStandardsTest {
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
