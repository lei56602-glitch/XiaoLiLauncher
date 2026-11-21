package com.xiaoli.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class SpatialFocusNavigatorTest {
    private val grid = listOf(
        FocusTarget(0, 0f, 0f), FocusTarget(1, 1f, 0f), FocusTarget(2, 2f, 0f),
        FocusTarget(3, 0f, 1f), FocusTarget(4, 1.1f, 1f),
        FocusTarget(5, 1f, 2f)
    )

    @Test fun horizontalMovementPrefersSameVisualRow() {
        assertEquals(1, SpatialFocusNavigator.next(0, SpatialFocusNavigator.RIGHT, grid))
        assertEquals(1, SpatialFocusNavigator.next(2, SpatialFocusNavigator.LEFT, grid))
    }

    @Test fun verticalMovementChoosesNearestHorizontalPosition() {
        assertEquals(4, SpatialFocusNavigator.next(1, SpatialFocusNavigator.DOWN, grid))
        assertEquals(1, SpatialFocusNavigator.next(4, SpatialFocusNavigator.UP, grid))
    }

    @Test fun missingDirectionalNeighborKeepsCurrentFocus() {
        assertEquals(2, SpatialFocusNavigator.next(2, SpatialFocusNavigator.RIGHT, grid))
        assertEquals(0, SpatialFocusNavigator.next(0, SpatialFocusNavigator.LEFT, grid))
    }

    @Test fun diagonalFallbackWorksWhenNoAlignedTargetExists() {
        assertEquals(5, SpatialFocusNavigator.next(3, SpatialFocusNavigator.DOWN, grid))
    }

    @Test fun normalizedScreenCoordinatesStillMoveToAdjacentTargets() {
        val screen = listOf(
            FocusTarget(10, .15f, .40f),
            FocusTarget(11, .31f, .40f),
            FocusTarget(12, .31f, .68f)
        )
        assertEquals(11, SpatialFocusNavigator.next(10, SpatialFocusNavigator.RIGHT, screen))
        assertEquals(12, SpatialFocusNavigator.next(11, SpatialFocusNavigator.DOWN, screen))
    }

    @Test fun unknownDirectionAndUnknownFocusAreSafe() {
        assertEquals(0, SpatialFocusNavigator.next(0, 99, grid))
        assertEquals(99, SpatialFocusNavigator.next(99, SpatialFocusNavigator.RIGHT, grid))
    }
}
