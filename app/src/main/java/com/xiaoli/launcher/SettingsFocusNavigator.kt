package com.xiaoli.launcher

/**
 * Settings page adapter for the shared spatial D-pad engine.
 * Only controls visible in the selected category are registered as targets.
 */
internal object SettingsFocusNavigator {
    private val rowsByCategory = mapOf(
        0 to listOf(listOf(8), listOf(0, 1, 2), listOf(17, 18, 19), listOf(23)),
        1 to listOf(listOf(3, 4, 5), listOf(24)),
        2 to listOf(listOf(21)),
        3 to listOf(listOf(9, 10, 15, 16), listOf(22)),
        4 to listOf(listOf(14, 13)),
        5 to listOf(listOf(6)),
        6 to listOf(listOf(7))
    )

    private val categoryByFocus = mapOf(
        8 to 0, 0 to 0, 1 to 0, 2 to 0, 17 to 0, 18 to 0, 19 to 0, 23 to 0,
        3 to 1, 4 to 1, 5 to 1, 24 to 1,
        21 to 2,
        9 to 3, 10 to 3, 15 to 3, 16 to 3, 22 to 3,
        14 to 4, 13 to 4,
        6 to 5,
        7 to 6
    )

    private val horizontalPosition = mapOf(
        8 to .38f, 22 to .77f, 23 to .38f,
        9 to .24f, 10 to .42f, 15 to .60f, 16 to .78f,
        0 to .246f, 1 to .498f, 2 to .750f, 17 to .246f, 18 to .498f, 19 to .750f,
        3 to .24f, 4 to .50f, 5 to .76f, 24 to .38f,
        6 to .31f, 21 to .70f,
        7 to .22f, 14 to .52f, 13 to .82f
    )

    private fun targets(category: Int): List<FocusTarget> {
        val rows = rowsByCategory[category] ?: return emptyList()
        return rows.flatMapIndexed { rowIndex, row ->
            row.map { id ->
                FocusTarget(id, horizontalPosition[id] ?: .5f, rowIndex.toFloat())
            }
        }
    }

    fun next(currentFocus: Int, direction: Int): Int {
        val category = categoryByFocus[currentFocus] ?: return currentFocus
        return SpatialFocusNavigator.next(currentFocus, direction, targets(category))
    }

    fun supportedFocusIds(): Set<Int> = categoryByFocus.keys
}
