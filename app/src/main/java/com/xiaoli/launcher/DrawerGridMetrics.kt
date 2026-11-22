package com.xiaoli.launcher

/**
 * One source of truth for TV app-drawer geometry. Rendering, hit testing and
 * remote-control scrolling all use these same dimensions.
 */
internal data class DrawerGridMetrics(
    val width: Float,
    val height: Float,
    val columns: Int,
    val rows: Int
) {
    val gridLeft: Float = width * .065f
    val gridRight: Float = width * .935f
    val contentTop: Float = height * .265f
    val contentBottom: Float = height * .845f
    val cellWidth: Float = (gridRight - gridLeft) / columns.coerceAtLeast(1)
    val cellHeight: Float = height * .58f / rows.coerceAtLeast(1)

    fun totalRows(itemCount: Int): Int =
        (itemCount.coerceAtLeast(0) + columns - 1) / columns

    fun maxScroll(itemCount: Int): Int =
        (totalRows(itemCount) - rows).coerceAtLeast(0)

    fun iconSize(availableCellWidth: Float): Float =
        minOf(cellHeight * .76f, availableCellWidth * .72f)

    fun iconTop(cellTop: Float): Float = cellTop + cellHeight * .045f

    /**
     * Move focus within the current grid row/column. Never wrap at row edges
     * or jump to an unrelated item when the last row is incomplete.
     */
    fun moveFocus(index: Int, direction: Int, itemCount: Int): Int {
        if (itemCount <= 0) return 0
        val current = index.coerceIn(0, itemCount - 1)
        val currentRow = current / columns
        val targets = (0 until itemCount)
            .filter { candidate ->
                direction != -1 && direction != 1 || candidate / columns == currentRow
            }
            .map { candidate ->
                FocusTarget(
                    id = candidate,
                    centerX = candidate % columns + .5f,
                    centerY = candidate / columns + .5f
                )
            }
        val spatialDirection = when (direction) {
            -1 -> SpatialFocusNavigator.LEFT
            1 -> SpatialFocusNavigator.RIGHT
            -columns -> SpatialFocusNavigator.UP
            columns -> SpatialFocusNavigator.DOWN
            else -> return current
        }
        return SpatialFocusNavigator.next(current, spatialDirection, targets)
    }

    /** Backward-compatible whole-row hit testing for existing callers/tests. */
    fun indexAt(x: Float, y: Float, scrollRow: Int, itemCount: Int): Int =
        indexAt(x, y, scrollRow.toFloat(), itemCount)

    fun indexAt(x: Float, y: Float, scrollVisualRow: Float, itemCount: Int): Int {
        if (x < gridLeft || x >= gridRight || y < contentTop || y >= contentBottom ||
            cellWidth <= 0f || cellHeight <= 0f) return -1
        val column = ((x - gridLeft) / cellWidth).toInt()
        val contentRow = (scrollVisualRow.coerceAtLeast(0f) + (y - contentTop) / cellHeight)
        val row = contentRow.toInt()
        if (column !in 0 until columns || row < 0) return -1
        val rowFraction = contentRow - row
        val cellX = (x - gridLeft) / cellWidth - column
        if (cellX < .035f || cellX > .965f || rowFraction < .025f || rowFraction > .965f) return -1
        val index = row * columns + column
        return if (index in 0 until itemCount) index else -1
    }
}
