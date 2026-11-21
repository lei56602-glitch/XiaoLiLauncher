package com.xiaoli.launcher

import kotlin.math.abs

/**
 * Shared spatial D-pad engine for the launcher's custom-drawn TV interfaces.
 * Coordinates are expressed in a common logical space for the active surface.
 */
internal data class FocusTarget(
    val id: Int,
    val centerX: Float,
    val centerY: Float
)

internal object SpatialFocusNavigator {
    const val LEFT = -1
    const val RIGHT = 1
    const val UP = -2
    const val DOWN = 2

    /**
     * Selects the nearest target in the requested half-plane. The primary-axis
     * distance dominates; perpendicular distance breaks ties. Targets behind
     * the current focus are never selected.
     */
    fun next(currentId: Int, direction: Int, targets: List<FocusTarget>): Int {
        val current = targets.firstOrNull { it.id == currentId } ?: return currentId
        if (direction !in setOf(LEFT, RIGHT, UP, DOWN)) return currentId
        val horizontal = direction == LEFT || direction == RIGHT
        val sign = if (direction == LEFT || direction == UP) -1f else 1f

        return targets.asSequence()
            .filter { it.id != currentId }
            .mapNotNull { candidate ->
                val dx = candidate.centerX - current.centerX
                val dy = candidate.centerY - current.centerY
                val primary = if (horizontal) dx * sign else dy * sign
                if (primary <= 0.5f) return@mapNotNull null
                val secondary = if (horizontal) abs(dy) else abs(dx)
                // Prefer targets in the same visual lane, while still allowing
                // diagonal movement when a row/column has no target.
                val score = primary + secondary * 1.75f
                Triple(candidate.id, score, secondary)
            }
            .minWithOrNull(compareBy<Triple<Int, Float, Float>> { it.second }.thenBy { it.third })
            ?.first ?: currentId
    }
}
