package eu.kanade.tachiyomi.extension.update

import io.kotest.property.PropTestConfig

/**
 * Shared convention for the correctness properties in this feature.
 *
 * Property test functions must use the name
 * `Feature: actualizacion-segura-extensiones, Property N: <property description>`.
 */
internal object PropertyTestConvention {
    const val MIN_ITERATIONS = 100
    const val NAME_TEMPLATE = "Feature: actualizacion-segura-extensiones, Property N: ..."

    val config = PropTestConfig(iterations = MIN_ITERATIONS)
}
