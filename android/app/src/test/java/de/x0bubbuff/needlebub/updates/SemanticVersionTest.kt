package de.x0bubbuff.needlebub.updates

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticVersionTest {
    @Test
    fun `orders prereleases numerically and below the final release`() {
        assertTrue(SemanticVersion.parse("1.0.0-alpha.2") > SemanticVersion.parse("1.0.0-alpha.1"))
        assertTrue(SemanticVersion.parse("1.0.0-alpha.10") > SemanticVersion.parse("1.0.0-alpha.2"))
        assertTrue(SemanticVersion.parse("1.0.0") > SemanticVersion.parse("1.0.0-alpha.10"))
        assertEquals(SemanticVersion.parse("1.0.0+build.2"), SemanticVersion.parse("1.0.0+build.9"))
    }

    @Test
    fun `rejects malformed semver identifiers`() {
        assertThrows(IllegalArgumentException::class.java) { SemanticVersion.parse("1.0.0-alpha..2") }
        assertThrows(IllegalArgumentException::class.java) { SemanticVersion.parse("1.0.0-alpha.02") }
    }
}
