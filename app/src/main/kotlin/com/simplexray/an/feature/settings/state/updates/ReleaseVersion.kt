package com.simplexray.an.feature.settings.state.updates

internal fun compareReleaseVersions(latest: String, current: String): Int? {
    val latestVersion = ReleaseVersion.parse(latest) ?: return null
    val currentVersion = ReleaseVersion.parse(current) ?: return null
    return latestVersion.compareTo(currentVersion)
}

private data class ReleaseVersion(val core: List<String>, val prerelease: List<String>) :
    Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        core.indices.forEach { index ->
            val comparison = compareNumeric(core[index], other.core[index])
            if (comparison != 0) return comparison
        }
        if (prerelease.isEmpty() || other.prerelease.isEmpty()) {
            return when {
                prerelease.isEmpty() && other.prerelease.isEmpty() -> 0
                prerelease.isEmpty() -> 1
                else -> -1
            }
        }
        for (index in 0 until minOf(prerelease.size, other.prerelease.size)) {
            val left = prerelease[index]
            val right = other.prerelease[index]
            val leftNumeric = left.all { it in '0'..'9' }
            val rightNumeric = right.all { it in '0'..'9' }
            val comparison =
                when {
                    leftNumeric && rightNumeric -> compareNumeric(left, right)
                    leftNumeric -> -1
                    rightNumeric -> 1
                    else -> left.compareTo(right)
                }
            if (comparison != 0) return comparison
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    companion object {
        private val pattern =
            Regex(
                "v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)" +
                    "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?" +
                    "(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
            )

        fun parse(tag: String): ReleaseVersion? {
            val match = pattern.matchEntire(tag) ?: return null
            val prerelease = match.groups[4]?.value?.split('.') ?: emptyList()
            if (
                prerelease.any { identifier ->
                    identifier.length > 1 &&
                        identifier.startsWith('0') &&
                        identifier.all { it in '0'..'9' }
                }
            ) {
                return null
            }
            return ReleaseVersion(match.groupValues.slice(1..3), prerelease)
        }

        private fun compareNumeric(left: String, right: String): Int =
            left.length.compareTo(right.length).takeIf { it != 0 } ?: left.compareTo(right)
    }
}
