package eu.kanade.tachiyomi.extension.update.index

import eu.kanade.tachiyomi.extension.update.model.VersionComparison
import eu.kanade.tachiyomi.extension.update.model.VersionDescriptor
import java.math.BigInteger

/** Compares extension versions without inferring an order for unsupported text formats. */
class VersionComparator {
    fun compare(left: VersionDescriptor, right: VersionDescriptor): VersionComparison {
        val leftCode = left.versionCode
        val rightCode = right.versionCode
        if (leftCode != null && rightCode != null) {
            return leftCode.compareTo(rightCode).toVersionComparison()
        }

        val leftComponents = parseComparableText(left.text) ?: return VersionComparison.Unknown
        val rightComponents = parseComparableText(right.text) ?: return VersionComparison.Unknown
        val componentCount = maxOf(leftComponents.size, rightComponents.size)

        repeat(componentCount) { index ->
            val componentOrder = leftComponents
                .getOrElse(index) { BigInteger.ZERO }
                .compareTo(rightComponents.getOrElse(index) { BigInteger.ZERO })
            if (componentOrder != 0) return componentOrder.toVersionComparison()
        }

        return VersionComparison.Equal
    }

    private fun parseComparableText(text: String): List<BigInteger>? {
        val trimmed = text.trim()
        val numericText = when (trimmed.firstOrNull()) {
            'v', 'V' -> trimmed.drop(1)
            else -> trimmed
        }
        if (!COMPARABLE_VERSION_PATTERN.matches(numericText)) return null

        return numericText.split('.').map(::BigInteger)
    }

    private fun Int.toVersionComparison(): VersionComparison = when {
        this < 0 -> VersionComparison.Lower
        this > 0 -> VersionComparison.Greater
        else -> VersionComparison.Equal
    }

    private companion object {
        val COMPARABLE_VERSION_PATTERN = Regex("[0-9]+(?:\\.[0-9]+)*")
    }
}
