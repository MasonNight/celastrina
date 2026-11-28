package dev.celastrina.build

import org.jetbrains.kotlin.lexer.KotlinLexer
import org.jetbrains.kotlin.lexer.KtTokens

object SourceStandards {
    private val words = Regex("""\p{Lu}+(?=\p{Lu}\p{Ll}|[^\p{L}]|$)|\p{Lu}?\p{Ll}+|\p{L}+|\d+""")

    private fun splitWords(value: String): List<String> = words.findAll(value).map { it.value.lowercase() }.toList()

    fun cloudStreamImports(relativePath: String, source: String): List<Int> {
        val directives = tokens(source, java = relativePath.endsWith(".java")).filter { it.restOfLine.isNotEmpty() }.toList()
        val packageName = directives.firstOrNull { it.text == "package" }?.restOfLine?.joinToString("")
        if (packageName == "dev.celastrina.core.extension") return emptyList()
        return directives.filter { it.text == "import" }.mapNotNull { directive ->
            val imported = directive.restOfLine.dropWhile { it == "static" }.joinToString("") { it.removeSurrounding("`") }
            if (!imported.startsWith("com.lagradost.")) return@mapNotNull null
            // ADR 0001 passes BasePlugin through this port to load in-process Extensions.
            val extensionContract = relativePath == "port/ExtensionLoader.kt" &&
                packageName == "dev.celastrina.core.port" && imported == "com.lagradost.cloudstream3.plugins.BasePlugin"
            directive.line.takeUnless { extensionContract }
        }.toList()
    }

    data class NameViolation(val line: Int, val identifier: String, val avoid: List<String>)

    fun glossaryNames(
        glossary: String,
        source: String,
        fileExceptions: Set<String> = emptySet(),
    ): List<NameViolation> {
        val avoided = glossary.lineSequence().map(String::trim)
            .filter { it.startsWith("_Avoid_:") }
            .flatMap { it.substringAfter(':').split(',') }
            .map { splitWords(it.substringBefore('(')) }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedByDescending { it.size }
            .toList()
        val preferred = glossary.lineSequence().map(String::trim)
            .filter { it.startsWith("**") && it.endsWith("**:") }
            .map { splitWords(it.removePrefix("**").removeSuffix("**:")) }
            .filter { it.isNotEmpty() }
            .toList()
        val tokens = tokens(source).toList()
        val externalImports = tokens.asSequence().filter { it.text == "import" }
            .map { it.restOfLine.takeWhile { token -> token != "as" } }
            .filter { it.firstOrNull() in externalPackages || it.take(3) == listOf("com", ".", "lagradost") }
            .mapNotNull { it.lastOrNull() }
            .toSet()

        return tokens.asSequence().filter { it.identifier }
            .filter { it.text !in fileExceptions && it.text !in focusHelpers && it.text !in kotlinAndAndroidNames }
            .filter { it.declared || (it.text !in externalImports && it.text !in composeAndCoilNames) }
            .mapNotNull { token ->
                val segments = splitWords(token.text)
                val matchingPhrases = avoided.filter { segments.windowed(it.size).contains(it) }
                if (matchingPhrases.isEmpty()) return@mapNotNull null
                // Search and Lookup are each preferred for their own domain concept.
                val preferredSegments = preferred.flatMap { phrase ->
                    segments.windowed(phrase.size).mapIndexedNotNull { index, window ->
                        (index until index + phrase.size).takeIf { window == phrase }
                    }
                }
                matchingPhrases.firstOrNull { phrase ->
                    segments.windowed(phrase.size).withIndex().any { (index, window) ->
                        window == phrase && preferredSegments.none { index in it && index + phrase.lastIndex in it }
                    }
                }?.let { NameViolation(token.line, token.text, it) }
            }
            .toList()
    }

    fun unvisitedScreens(
        applicationSources: Collection<String>,
        smokeSources: Collection<String>,
        exceptions: Set<String>,
    ): List<String> {
        val screens = applicationSources.flatMap { source ->
            val lexer = KotlinLexer().apply { start(source.replace("\r\n", "\n")) }
            var depth = 0
            var composable = false
            var function = false
            val names = mutableListOf<String>()
            while (lexer.tokenType != null) {
                val kind = lexer.tokenType
                val text = lexer.tokenText
                if (depth == 0) {
                    if (text == "Composable") composable = true
                    if (kind == KtTokens.FUN_KEYWORD) function = composable
                    if (function && kind == KtTokens.IDENTIFIER) {
                        if (text.endsWith("Screen")) names += text
                        function = false
                        composable = false
                    }
                }
                if (kind == KtTokens.LBRACE) { depth++; composable = false }
                if (kind == KtTokens.RBRACE) depth--
                lexer.advance()
            }
            names
        }
        val visits = smokeSources.flatMap { source ->
            tokens(source).filter { it.identifier }.flatMap { it.text.split('_') }.toList()
        }.toSet()
        return (screens.toSet() - visits - exceptions).sorted()
    }

    private val externalPackages = setOf("android", "androidx", "coil", "java", "javax", "kotlin", "kotlinx", "okhttp3")

    private data class Token(
        val text: String,
        val line: Int,
        val identifier: Boolean,
        val restOfLine: List<String> = emptyList(),
        val declared: Boolean = false,
    )

    private fun tokens(rawSource: String, java: Boolean = false): Sequence<Token> = sequence {
        val source = rawSource.replace("\r\n", "\n")
        val lexer = KotlinLexer().apply { start(source) }
        var line = 1
        var atStatementStart = true
        var previous = ""
        var functionHeader = false
        var directive: Token? = null
        val directiveTokens = mutableListOf<String>()
        while (lexer.tokenType != null) {
            val kind = lexer.tokenType
            val text = source.substring(lexer.tokenStart, lexer.tokenEnd)
            when {
                directive != null && (kind == KtTokens.SEMICOLON || (!java && kind == KtTokens.WHITE_SPACE && '\n' in text)) -> {
                    yield(directive.copy(restOfLine = directiveTokens.toList()))
                    directive = null
                    directiveTokens.clear()
                }
                atStatementStart && (kind == KtTokens.IMPORT_KEYWORD || kind == KtTokens.PACKAGE_KEYWORD ||
                    (kind == KtTokens.IDENTIFIER && text == "import")) -> {
                    directive = Token(text, line, identifier = false)
                }
                kind in KtTokens.WHITE_SPACE_OR_COMMENT_BIT_SET -> Unit
                directive != null -> {
                    if (directiveTokens.lastOrNull() == "as" && kind == KtTokens.IDENTIFIER) {
                        yield(Token(text.removeSurrounding("`"), line, identifier = true))
                    }
                    directiveTokens += text
                }
                kind == KtTokens.IDENTIFIER -> {
                    val tail = source.substring(lexer.tokenEnd).trimStart()
                    val declared = previous in setOf("val", "var", "class", "interface", "object", "typealias") ||
                        (functionHeader && !tail.startsWith(".")) || (tail.startsWith(":") && !tail.startsWith("::"))
                    yield(Token(text.removeSurrounding("`"), line, identifier = true, declared = declared))
                }
            }
            if (kind !in KtTokens.WHITE_SPACE_OR_COMMENT_BIT_SET) {
                if (kind == KtTokens.FUN_KEYWORD) functionHeader = true
                if (kind == KtTokens.LPAR) functionHeader = false
                previous = text
            }
            val newlines = text.count { it == '\n' }
            line += newlines
            atStatementStart = kind == KtTokens.SEMICOLON || (kind in KtTokens.WHITE_SPACE_OR_COMMENT_BIT_SET &&
                (atStatementStart || newlines != 0))
            lexer.advance()
        }
        if (directive != null) yield(directive.copy(restOfLine = directiveTokens.toList()))
    }

    // These focus operations deliberately describe UI behavior rather than a domain Title.
    private val focusHelpers = setOf("requestFocusOnceShown", "requestFocusUntil")

    // API names chosen by Compose and Coil, permitted at uses, never at declarations.
    private val composeAndCoilNames = setOf(
        "AsyncImage", "contentDescription", "contentScale", "contentPadding", "contentAlignment", "contentColor",
        "focusedContentColor", "headlineContent", "supportingContent", "imageUrl", "wrapContentSize",
    )

    private val kotlinAndAndroidNames = setOf(
        // Standard collection types and operations available without explicit imports.
        "ArrayList", "List", "asList", "buildList", "emptyList", "listOf", "listOfNotNull", "mutableListOf",
        "toList", "toMutableList", "indexOf", "indexOfFirst", "lastIndex", "lastIndexOf", "find",
        // Kotlin's error function, JVM linkage errors, and Android's error callback.
        "error", "NoClassDefFoundError", "NoSuchMethodError", "onError",
    )
}
