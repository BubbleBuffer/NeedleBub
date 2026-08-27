package de.x0bubbuff.needlebub.updates

class SemanticVersion private constructor(
    private val major: Long,
    private val minor: Long,
    private val patch: Long,
    private val prerelease: List<String>,
) : Comparable<SemanticVersion> {
    override fun compareTo(other: SemanticVersion): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
            return when {
                prerelease.isEmpty() && other.prerelease.isEmpty() -> 0
                prerelease.isEmpty() -> 1
                else -> -1
            }
        }
        val length = maxOf(prerelease.size, other.prerelease.size)
        for (index in 0 until length) {
            val left = prerelease.getOrNull(index) ?: return -1
            val right = other.prerelease.getOrNull(index) ?: return 1
            val leftNumber = left.toLongOrNull()
            val rightNumber = right.toLongOrNull()
            val comparison = when {
                leftNumber != null && rightNumber != null -> compareValues(leftNumber, rightNumber)
                leftNumber != null -> -1
                rightNumber != null -> 1
                else -> left.compareTo(right)
            }
            if (comparison != 0) return comparison
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is SemanticVersion && compareTo(other) == 0
    override fun hashCode(): Int = listOf(major, minor, patch, prerelease).hashCode()

    companion object {
        private val pattern = Regex(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-([0-9A-Za-z.-]+))?(?:\\+[0-9A-Za-z.-]+)?$",
        )

        fun parse(raw: String): SemanticVersion {
            val match = pattern.matchEntire(raw) ?: throw IllegalArgumentException("Invalid semantic version")
            val prerelease = match.groupValues[4].takeIf(String::isNotEmpty)?.split('.').orEmpty()
            if (prerelease.any { it.isEmpty() || (it.all(Char::isDigit) && it.length > 1 && it.startsWith('0')) }) {
                throw IllegalArgumentException("Invalid semantic version")
            }
            return SemanticVersion(
                match.groupValues[1].toLong(),
                match.groupValues[2].toLong(),
                match.groupValues[3].toLong(),
                prerelease,
            )
        }
    }
}
