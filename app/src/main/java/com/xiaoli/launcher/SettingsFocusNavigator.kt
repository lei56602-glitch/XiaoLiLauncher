package com.xiaoli.launcher

/**
 * Directional focus graph for the TV settings screen.
 * Direction values match the existing View handler: -1/1 horizontal, -2/2 vertical.
 */
internal object SettingsFocusNavigator {
    private val rows = listOf(
        listOf(8),
        listOf(9, 10, 11, 12, 15, 16),
        listOf(0, 1, 2, 17, 18, 19),
        listOf(3, 4, 5),
        listOf(6, 21),
        listOf(7, 14, 13)
    )

    private val horizontalPosition = mapOf(
        8 to .50f,
        9 to .18f, 10 to .31f, 11 to .44f, 12 to .57f, 15 to .70f, 16 to .83f,
        0 to .16f, 1 to .25f, 2 to .34f, 17 to .63f, 18 to .73f, 19 to .83f,
        3 to .24f, 4 to .50f, 5 to .76f,
        6 to .31f, 21 to .70f,
        7 to .22f, 14 to .52f, 13 to .82f
    )

    fun next(currentFocus: Int, direction: Int): Int {
        val rowIndex = rows.indexOfFirst { currentFocus in it }.coerceAtLeast(0)
        val row = rows[rowIndex]
        return if (direction == -1 || direction == 1) {
            val index = row.indexOf(currentFocus).coerceAtLeast(0)
            row[(index + direction).coerceIn(0, row.lastIndex)]
        } else {
            val targetRow = (rowIndex + direction / 2).coerceIn(0, rows.lastIndex)
            val currentX = horizontalPosition[currentFocus] ?: .5f
            rows[targetRow].minByOrNull {
                kotlin.math.abs((horizontalPosition[it] ?: .5f) - currentX)
            } ?: currentFocus
        }
    }

    fun supportedFocusIds(): Set<Int> = rows.flatten().toSet()
}
