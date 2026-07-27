package eu.kanade.tachiyomi.extension.update.index

import eu.kanade.tachiyomi.extension.update.model.VersionComparison
import eu.kanade.tachiyomi.extension.update.model.VersionDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals

class VersionComparatorTest {
    private val comparator = VersionComparator()

    @Test
    fun `version codes take precedence when both are present`() {
        assertEquals(
            VersionComparison.Lower,
            comparator.compare(version("release", 1), version("preview", 2)),
        )
        assertEquals(
            VersionComparison.Equal,
            comparator.compare(version("999.0", 7), version("1.0", 7)),
        )
        assertEquals(
            VersionComparison.Greater,
            comparator.compare(version("old", Long.MAX_VALUE), version("new", 0)),
        )
    }

    @Test
    fun `missing version code falls back to numeric text on both sides`() {
        assertEquals(
            VersionComparison.Lower,
            comparator.compare(version("1.2", 500), version("1.10")),
        )
        assertEquals(
            VersionComparison.Greater,
            comparator.compare(version("2.0"), version("1.999", 999)),
        )
    }

    @Test
    fun `numeric components use arbitrary precision`() {
        assertEquals(
            VersionComparison.Greater,
            comparator.compare(
                version("1.999999999999999999999999999999999999999999"),
                version("1.9223372036854775808"),
            ),
        )
    }

    @Test
    fun `optional prefix surrounding whitespace and trailing zeroes preserve equality`() {
        assertEquals(
            VersionComparison.Equal,
            comparator.compare(version("  V001.002.000  "), version("1.2")),
        )
        assertEquals(
            VersionComparison.Equal,
            comparator.compare(version("v1.2.0.0"), version("1.2")),
        )
    }

    @Test
    fun `numeric components are compared from left to right`() {
        assertEquals(
            VersionComparison.Greater,
            comparator.compare(version("1.2.1"), version("1.2")),
        )
        assertEquals(
            VersionComparison.Lower,
            comparator.compare(version("1.09.999"), version("1.10.0")),
        )
    }

    @Test
    fun `unsupported text returns unknown without lexical fallback`() {
        val unsupportedTexts = listOf(
            "1.0-alpha",
            "1..0",
            "1.",
            "vV1",
            "v",
            "+1.0",
            "1. 0",
        )

        unsupportedTexts.forEach { text ->
            assertEquals(
                VersionComparison.Unknown,
                comparator.compare(version(text), version(text)),
                "Expected '$text' to be non-comparable even with itself",
            )
            assertEquals(
                VersionComparison.Unknown,
                comparator.compare(version(text), version("1.0")),
                "Expected '$text' not to be ordered lexicographically",
            )
        }
    }

    private fun version(text: String, versionCode: Long? = null) =
        VersionDescriptor(text = text, versionCode = versionCode)
}
