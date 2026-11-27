package dev.celastrina.build

import org.jetbrains.kotlin.lexer.KotlinLexer
import org.jetbrains.kotlin.lexer.KtTokens

object SourceStandards {
    private val words = Regex("""\p{Lu}+(?=\p{Lu}\p{Ll}|[^\p{L}]|$)|\p{Lu}?\p{Ll}+|\p{L}+|\d+""")

    private fun splitWords(value: String): List<String> = words.findAll(value).map { it.value.lowercase() }.toList()

    fun cloudStreamImports(relativePath: String, source: String): List<Int> {
        val directives = tokens(source).filter { it.restOfLine.isNotEmpty() }.toList()
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

    fun glossaryNames(glossary: String, source: String, path: String = ""): List<NameViolation> {
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
            .filter { it.text !in externalImports && it.text !in implementationNames && it.text !in domainExceptions }
            .filter { !path.startsWith("app/src/main/") || it.text !in appImplementationNames }
            .filter { token -> scopedExceptions[token.text]?.let { path.endsWith(it) } != true }
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

    private val externalPackages = setOf("android", "androidx", "coil", "java", "javax", "kotlin", "kotlinx", "okhttp3")

    private data class Token(
        val text: String,
        val line: Int,
        val identifier: Boolean,
        val restOfLine: List<String> = emptyList(),
    )

    private fun tokens(source: String): Sequence<Token> = sequence {
        val lexer = KotlinLexer().apply { start(source) }
        var line = 1
        var atStatementStart = true
        var directive: Token? = null
        val directiveTokens = mutableListOf<String>()
        while (lexer.tokenType != null) {
            val kind = lexer.tokenType
            val text = source.substring(lexer.tokenStart, lexer.tokenEnd)
            when {
                directive != null && ('\n' in text || kind == KtTokens.SEMICOLON) -> {
                    yield(directive.copy(restOfLine = directiveTokens.toList()))
                    directive = null
                    directiveTokens.clear()
                }
                atStatementStart && (kind == KtTokens.IMPORT_KEYWORD || kind == KtTokens.PACKAGE_KEYWORD ||
                    (kind == KtTokens.IDENTIFIER && text == "import")) -> {
                    directive = Token(text, line, identifier = false)
                }
                kind in KtTokens.WHITE_SPACE_OR_COMMENT_BIT_SET -> Unit
                directive != null -> directiveTokens += text
                kind == KtTokens.IDENTIFIER -> yield(Token(text.removeSurrounding("`"), line, identifier = true))
            }
            val newlines = text.count { it == '\n' }
            line += newlines
            atStatementStart = kind == KtTokens.SEMICOLON || (kind in KtTokens.WHITE_SPACE_OR_COMMENT_BIT_SET &&
                (atStatementStart || newlines != 0))
            lexer.advance()
        }
        if (directive != null) yield(directive.copy(restOfLine = directiveTokens.toList()))
    }

    private val scopedExceptions = mapOf(
        // CloudStream's registered MainAPI and Jellyfin's IDs as named on their wire formats.
        "provider" to "/extension/ExtensionSource.kt",
        "providerIds" to "/jellyfin/MediaServer.kt",
        // Coil's AsyncImagePainter state is an externally defined nested type.
        "Error" to "/app/TitleLogo.kt",
        // The catalogue uses RowList for one paging cursor behind a Home Row.
        "RowList" to "/catalogue/HomeRows.kt",
    )

    private val implementationNames = setOf(
        // Platform, Kotlin and library names that are implicitly imported, nested, or passed as named arguments.
        "ArrayList", "List", "asList", "buildList", "emptyList", "listOf", "listOfNotNull", "mutableListOf",
        "toList", "toMutableList", "indexOf", "indexOfFirst", "lastIndex", "lastIndexOf", "find", "error",
        "NoClassDefFoundError", "NoSuchMethodError", "onError", "PlaybackException", "isFile", "listFiles", "resolve",
        "content", "contentAlignment", "contentColor", "contentDescription", "contentPadding", "contentScale",
        "ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES", "ERROR_CODE_DECODING_FORMAT_UNSUPPORTED",
        "ERROR_CODE_IO_BAD_HTTP_STATUS", "errorCode", "errorStream", "getType", "keyboardType", "focusTarget",
        "requestFocus", "setDefaultRequestProperties", "setRequestProperty", "setShowBuffering", "setMediaItem",
        "setMediaSourceFactory", "setMimeType", "SHOW_BUFFERING_WHEN_PLAYING", "BasePlugin", "Plugin", "getLoadUrl",

        // Names for transport, filesystem and persistence, outside the glossary's domain senses.
        "BadUrl", "EnterUrl", "HttpClient", "JdkHttpClient", "FileStorage", "ConfigurationStore", "StoreAccess",
        "database", "file", "fileFor", "fileHash", "fileIdx", "filePath", "INDEX_KEY", "index", "saveIndex",
        "list", "pageUrl", "url", "apiBaseUrl", "addressCheckClient", "badUrl", "downloadBaseUrl", "externalUrl",
        "newUrl", "repositoryUrl", "takesUrl", "contentType", "format", "type", "port", "os",
        "apiKey", "TMDB_API_KEY", "query", "queryString", "rawQuery", "request", "requestMethod",
        "token", "accessToken", "usesBearerToken", "user", "User", "userId", "USER_AGENT", "userAgent", "store",

        // External services' protocols, schemas and adapter implementation details.
        "AddonSource", "AddonStream", "AddonSubtitle", "StremioAddon", "StremioAddonKind", "StremioAddonSource",
        "addon", "testAddon", "ArchiveFile", "ArchiveItem", "toArchiveFile", "ANIMATION_QUERY", "WikidataFilm",
        "ImageRequest", "IMAGE_BASE_URL", "imageUrl", "backdropUrl", "logoUrl", "posterUrl", "listUrl",
        "stillUrl", "nzbUrl", "dataUrl", "manifestUrl", "manifestUrlOf", "metadataBaseUrl", "wikidataSparqlUrl",
        "TmdbAlternativeTitle", "TmdbAlternativeTitles", "TmdbSeriesAlternativeTitles", "alternativeTitles",
        "TmdbListItem", "TmdbListPage", "MediaSource", "MediaStream", "mediaSources", "mediaType",
        "subtitleMimeType", "videoMimeType", "mimeType", "TvType", "PluginJson", "plugin", "pluginClassName",
        "pluginLists", "sourcePlugin", "RepoJson", "link", "linkData", "ListSource", "ListPage", "listPage",
        "RequestFailed", "wrongType", "UnsupportedFormat",

        // Presentation, indexing and internal sequencing.
        "item", "firstItem", "loadedIndex", "seasonIndex", "listAskedAt", "adapter", "show", "showNotice", "showHero",
        "metadata", "metadataLine", "target",
    )

    private val appImplementationNames = setOf(
        // Android app infrastructure, Compose widgets and focus state.
        "ConfigurationProvider", "Media3FailoverEvents", "MediaCodecDecoders", "toMediaItem", "onPlayerError",
        "ListItem", "ExtensionList", "SourceList", "focusedContentColor", "headlineContent",
        "supportingContent", "listState", "targetFocused", "targetRequester", "requestFocusOnceShown",
        "requestFocusUntil", "selectedTabIndex", "PER_USER_RANGE",
    )

    private val domainExceptions = setOf(
        // Typed failures for HTTP, lookup and address checks have separate meanings from Failover.
        "AddressCheckHttpError", "AddressCheckUnavailable", "ServiceError", "SourceError", "sourceError",
        // These identify or select Source Kinds and the player’s request and candidate list.
        "Kind", "ConfiguredKind", "EXTENSION_KIND", "KindChoice", "UnknownKind", "kind", "kindHint", "kindName",
        "kindOf", "PlayRequest", "STREAM_LIST_FRESH_FOR", "Item", "StreamItem",
    )
}
