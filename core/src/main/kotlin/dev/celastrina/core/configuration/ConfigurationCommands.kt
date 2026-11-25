package dev.celastrina.core.configuration

/**
 * The commands the agent's ContentProvider takes (ADR 0002) in protocol_version [ConfigurationService.PROTOCOL_VERSION],
 * each with the fields its `--arg` JSON may carry. The provider refuses any other command or field, and the Install
 * Runbook's jobs are checked against this list, so the two can't drift apart.
 */
object ConfigurationCommands {
    val fields: Map<String, Set<String>> = linkedMapOf(
        "status" to setOf(),
        "list" to setOf(),
        "add" to setOf("kind", "name", "url", "username", "password"),
        "update" to setOf("id", "name", "url", "username", "password"),
        "set-order" to setOf("ids"),
        "enable" to setOf("id"),
        "disable" to setOf("id"),
        "remove" to setOf("id"),
        "set-tmdb-key" to setOf("key"),
        "clear-tmdb-key" to setOf(),
        "import-repository" to setOf("url", "warning_acknowledged"),
        "install-extension" to setOf("repository", "name"),
        "extensions" to setOf(),
        "check-updates" to setOf(),
        "update-extension" to setOf("name"),
        "update-extensions" to setOf(),
        "remove-extension" to setOf("name"),
        "remove-repository" to setOf("url"),
    )
}
