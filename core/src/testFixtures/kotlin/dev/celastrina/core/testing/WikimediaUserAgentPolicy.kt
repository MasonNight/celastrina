package dev.celastrina.core.testing

/**
 * Wikimedia's User-Agent policy, as the Wikidata Query Service enforces it: a client must say who it is and how to reach
 * whoever runs it (`Name/version (https://… or an email address)`). Anything else, like a HTTP library's default
 * `okhttp/4.12.0`, gets HTTP 403 and an HTML "Wikimedia Error" page. Hand-written; the fakes apply it.
 */
internal object WikimediaUserAgentPolicy {
    private val DESCRIPTIVE = Regex("""^[\w.-]+(/[\w.-]+)? \(.*(https?://|@).*\)""")

    fun allows(userAgent: String?): Boolean = userAgent != null && DESCRIPTIVE.containsMatchIn(userAgent)

    val REFUSAL = """<!DOCTYPE html><html lang="en"><head><title>Wikimedia Error</title></head><body>
        |<p>Please set a user-agent and respect our robot policy https://w.wiki/4wJS.</p></body></html>""".trimMargin()
}
