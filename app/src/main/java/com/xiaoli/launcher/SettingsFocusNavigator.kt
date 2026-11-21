package com.xiaoli.launcher

/**
 * Directional focus graph for the TV settings screen.
 * Each category has its own graph because only that category's controls are visible.
 */
internal object SettingsFocusNavigator {
    private val rowsByCategory = mapOf(
        0 to listOf(listOf(8), listOf(0, 1, 2), listOf(17, 18, 19), listOf(23)),
        1 to listOf(listOf(3, 4, 5)),
        2 to listOf(listOf(21)),
        3 to listOf(listOf(9, 10, 15, 16), listOf(22)),
        4 to listOf(listOf(14, 13)),
        5 to listOf(listOf(6)),
        6 to listOf(listOf(7))
    )

    private val categoryByFocus = mapOf(
        8 to 0, 0 to 0, 1 to 0, 2 to 0, 17 to 0, 18 to 0, 19 to 0, 23 to 0,
        3 to 1, 4 to 1, 5 to 1,
        21 to 2,
        9 to 3, 10 to 3, 15 to 3, 16 to 3, 22 to 3,
        14 to 4, 13 to 4,
        6 to 5,
        7 to 6
    )

    private val horizontalPosition = mapOf(
        8 to .38f, 22 to .77f, 23 to .38f,
        9 to .24f, 10 to .42f, 15 to .60f, 16 to .78f,
        0 to .18f, 1 to .28f, 2 to .38f, 17 to .18f, 18 to .28f, 19 to .38f,
        3 to .24f, 4 to .50f, 5 to .76f,
        6 to .31f, 21 to .70f,
        7 to .22f, 14 to .52f, 13 to .82f
    )

    fun next(currentFocus: Int, direction: Int): Int {
        val category = categoryByFocus[currentFocus] ?: return currentFocus
        val rows = rowsByCategory[category] ?: return currentFocus
        val rowIndex = rows.indexOfFirst { currentFocus in it }
        if (rowIndex < 0) return currentFocus

        val row = rows[rowIndex]
        return if (direction == -1 || direction == 1) {
            val index = row.indexOf(currentFocus)
            row[(index + direction).coerceIn(0, row.lastIndex)]
        } else {
            val targetRow = (rowIndex + if (direction < 0) -1 else 1)
                .coerceIn(0, rows.lastIndex)
            val currentX = horizontalPosition[currentFocus] ?: .5f
            rows[targetRow].minByOrNull {
                kotlin.math.abs((horizontalPosition[it] ?: .5f) - currentX)
            } ?: currentFocus
        }
    }

    fun supportedFocusIds(): Set<Int> = categoryByFocus.keys
}
