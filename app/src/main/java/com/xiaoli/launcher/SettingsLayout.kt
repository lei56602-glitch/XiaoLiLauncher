package com.xiaoli.launcher

import android.graphics.RectF

/**
 * Shared normalized geometry for the settings center.
 * Drawing and touch handling use these same rectangles to prevent visual/hitbox drift.
 */
internal object SettingsLayout {
    fun categoryBounds(index: Int, width: Float, height: Float, rail: RectF? = null): RectF {
        val left = rail?.left?.plus(width * .014f) ?: width * .059f
        val right = rail?.right?.minus(width * .014f) ?: width * .211f
        val top = height * (.285f + index.coerceIn(0, 6) * .087f)
        return RectF(left, top, right, top + height * .066f)
    }

    /**
     * Three evenly distributed choices across the full settings content width.
     * row 0 = drawer columns; row 1 = drawer rows.
     */
    fun drawerGridOptionBounds(index: Int, row: Int, width: Float, height: Float): RectF {
        val safeIndex = index.coerceIn(0, 2)
        val col = safeIndex % 2
        val visualRow = safeIndex / 2
        val left = .13f + col * .385f
        val cardWidth = .355f
        val top = (if (row == 0) .50f else .705f) + visualRow * .085f
        return RectF(width * left, height * top, width * (left + cardWidth), height * (top + .075f))
    }
}
