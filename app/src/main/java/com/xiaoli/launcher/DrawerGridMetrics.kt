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
     * Spatial D-pad navigation for the app grid.
     *
     * Horizontal moves stay on the current row and never wrap. Vertical moves
     * enter the adjacent row and choose the item whose horizontal centre is
     * nearest to the current item's centre. This is important for an incomplete
     * last row: moving UP must still find the nearest app instead of getting
     * stuck merely because the same column is empty.
     *
     * The caller owns the separate header target and scrolls the grid after
     * focus changes, keeping focus and viewport movement in lockstep.
     */
    fun moveFocus(index: Int, direction: Int, itemCount: Int): Int {
        if (itemCount <= 0 || columns <= 0) return 0

        val current = index.coerceIn(0, itemCount - 1)
        val row = current / columns
        val column = current % columns

        return when (direction) {
            -1 -> if (column > 0) current - 1 else current
            1 -> if (column < columns - 1 && current + 1 < itemCount) current + 1 else current
            -columns, columns -> {
                val targetRow = row + if (direction < 0) -1 else 1
                if (targetRow !in 0 until totalRows(itemCount)) {
                    current
                } else {
                    val firstIndex = targetRow * columns
                    val endExclusive = minOf(firstIndex + columns, itemCount)
                    (firstIndex until endExclusive).minByOrNull { candidate ->
                        kotlin.math.abs((candidate % columns) - column)
                    } ?: current
                }
            }
            else -> current
        }
    }

    fun indexAt(x: Float, y: Float, scrollRow: Int, itemCount: Int): Int {
        if (x < gridLeft || x >= gridRight || y < contentTop || y >= contentBottom ||
            cellWidth <= 0f || cellHeight <= 0f) return -1
        val column = ((x - gridLeft) / cellWidth).toInt()
        val visibleRow = ((y - contentTop) / cellHeight).toInt()
        if (column !in 0 until columns || visibleRow !in 0 until rows) return -1
        val cellLeft = gridLeft + column * cellWidth
        val cellTop = contentTop + visibleRow * cellHeight
        if (x < cellLeft + cellWidth * .035f || x > cellLeft + cellWidth * .965f ||
            y < cellTop + cellHeight * .025f || y > cellTop + cellHeight * .965f) return -1
        val index = (visibleRow + scrollRow.coerceAtLeast(0)) * columns + column
        return if (index in 0 until itemCount) index else -1
    }
}
