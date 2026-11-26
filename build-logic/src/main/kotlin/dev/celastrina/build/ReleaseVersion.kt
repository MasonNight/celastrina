package dev.celastrina.build

/**
 * A Celastrina release's version, which its `vMAJOR.MINOR.PATCH` tag gives. Releases have no prerelease or build
 * suffixes. Android's versionCode is `MAJOR*1_000_000 + MINOR*1_000 + PATCH`, so a higher version always has a higher
 * versionCode, and MINOR and PATCH stay below 1000.
 */
data class ReleaseVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ReleaseVersion> {
    val name: String get() = "$major.$minor.$patch"
    val code: Int get() = major * 1_000_000 + minor * 1_000 + patch

    override fun compareTo(other: ReleaseVersion) = code.compareTo(other.code)

    companion object {
        private val TAG = Regex("""v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)""")

        /** Android's largest versionCode is 2_100_000_000. */
        private const val MAX_MAJOR = 2099

        /** The version [tag] names. Throws [IllegalArgumentException] for a tag that isn't one. */
        fun fromTag(tag: String): ReleaseVersion {
            val parts = requireNotNull(TAG.matchEntire(tag)) { "'$tag' isn't a release tag: expected vMAJOR.MINOR.PATCH, like v1.2.3" }
                .groupValues.drop(1).map { it.toIntOrNull() ?: Int.MAX_VALUE }
            val (major, minor, patch) = parts
            require(major <= MAX_MAJOR && minor <= 999 && patch <= 999) {
                "'$tag' doesn't fit Android's versionCode: MAJOR is at most $MAX_MAJOR, MINOR and PATCH at most 999"
            }
            return ReleaseVersion(major, minor, patch).also { require(it.code > 0) { "'$tag' isn't a release: versionCode must be above 0" } }
        }

        /**
         * The version [tag] names, when it is higher than every earlier release in [released] (their tags; ones that
         * aren't release tags are ignored). Throws [IllegalArgumentException] otherwise.
         */
        fun nextRelease(tag: String, released: List<String>): ReleaseVersion {
            val version = fromTag(tag)
            val (latestTag, latest) = released.mapNotNull { t -> runCatching { t to fromTag(t) }.getOrNull() }.maxByOrNull { it.second }
                ?: return version
            require(version > latest) { "'$tag' isn't higher than the latest release, $latestTag" }
            return version
        }
    }
}
