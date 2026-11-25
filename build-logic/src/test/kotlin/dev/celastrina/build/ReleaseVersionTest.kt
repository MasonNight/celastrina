package dev.celastrina.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The maintainer tagging a release: the tag names the version, and Android's versionCode follows from it. */
class ReleaseVersionTest {

    @Test
    fun `a vMAJOR_MINOR_PATCH tag gives the versionName and versionCode`() {
        val version = ReleaseVersion.fromTag("v1.2.3")

        assertEquals("1.2.3", version.name)
        assertEquals(1_002_003, version.code)
    }

    @Test
    fun `the largest version that fits Android's versionCode`() {
        assertEquals(2_099_999_999, ReleaseVersion.fromTag("v2099.999.999").code)
    }

    @Test
    fun `tags that aren't plain SemVer releases are refused, naming the tag`() {
        listOf(
            "1.2.3", "v1.2", "v1.2.3.4", "v1.2.3-rc.1", "v1.2.3+build", "v01.2.3", "v1.02.3", "v1.2.03", "V1.2.3", "v1.2.x", "",
            "refs/tags/v1.2.3",
        ).forEach { tag ->
            val error = assertFailsWith<IllegalArgumentException>(tag) { ReleaseVersion.fromTag(tag) }
            assertTrue("'$tag'" in error.message!!, error.message)
        }
    }

    @Test
    fun `versions whose parts don't fit the versionCode are refused`() {
        listOf("v1.1000.0", "v1.0.1000", "v2100.0.0", "v99999999999.0.0", "v0.0.0").forEach { tag ->
            assertFailsWith<IllegalArgumentException>(tag) { ReleaseVersion.fromTag(tag) }
        }
    }
}

/** The release workflow refusing a tag that wouldn't install as an update over what the Household already has. */
class NextReleaseTest {

    @Test
    fun `the first release needs no earlier one`() {
        assertEquals(ReleaseVersion(0, 1, 0), ReleaseVersion.nextRelease("v0.1.0", released = emptyList()))
    }

    @Test
    fun `a tag higher than every earlier release is the next release`() {
        val released = listOf("v1.2.3", "v1.10.0", "v0.9.9")

        assertEquals(ReleaseVersion(1, 10, 1), ReleaseVersion.nextRelease("v1.10.1", released))
        assertEquals(ReleaseVersion(2, 0, 0), ReleaseVersion.nextRelease("v2.0.0", released))
    }

    @Test
    fun `a tag that isn't higher than the highest release is refused, naming that release`() {
        val released = listOf("v1.2.3", "v1.10.0", "v0.9.9")

        listOf("v1.10.0", "v1.9.9", "v1.2.4", "v0.1.0").forEach { tag ->
            val error = assertFailsWith<IllegalArgumentException>(tag) { ReleaseVersion.nextRelease(tag, released) }
            assertTrue("v1.10.0" in error.message!!, error.message)
        }
    }

    @Test
    fun `releases tagged before this scheme are ignored`() {
        assertEquals(ReleaseVersion(0, 1, 0), ReleaseVersion.nextRelease("v0.1.0", released = listOf("walking-skeleton", "v9.9.9-rc.1")))
    }

    @Test
    fun `a tag that isn't a release tag is still refused`() {
        assertFailsWith<IllegalArgumentException> { ReleaseVersion.nextRelease("v2.0.0-rc.1", released = listOf("v1.0.0")) }
    }
}
