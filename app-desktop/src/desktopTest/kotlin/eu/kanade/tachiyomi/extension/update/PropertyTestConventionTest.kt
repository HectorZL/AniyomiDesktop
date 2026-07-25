package eu.kanade.tachiyomi.extension.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PropertyTestConventionTest {
    @Test
    fun `property tests use at least one hundred iterations`() {
        assertTrue(PropertyTestConvention.MIN_ITERATIONS >= 100)
        assertEquals(PropertyTestConvention.MIN_ITERATIONS, PropertyTestConvention.config.iterations)
    }

    @Test
    fun `property tests expose the required feature name template`() {
        assertEquals(
            "Feature: actualizacion-segura-extensiones, Property N: ...",
            PropertyTestConvention.NAME_TEMPLATE,
        )
    }
}
