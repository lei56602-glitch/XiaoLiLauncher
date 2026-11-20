package com.xiaoli.launcher

/**
 * Directional focus graph for the TV settings screen.
 * IDs match the controls rendered by LauncherView.
 */
internal object SettingsFocusNavigator {
    private val rows = listOf(
        listOf(8, 22),
        listOf(9, 10, 15, 16),
        listOf(0, 1, 2, 17, 18, 19),
        listOf(3, 4, 5),
        listOf(6, 21),
        listOf(7, 14, 13)
    )

    private val horizontalPosition = mapOf(
        8 to .38f, 22 to .77f,
        9 to .24f, 10 to .42f, 15 to .60f, 16 to .78f,
        0 to .18f, 1 to .28f, 2 to .38f, 17 to .62f, 18 to .72f, 19 to .82f,
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
