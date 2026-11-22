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
     * Three drawer choices arranged as two cards plus one centered card.
     * row 0 = drawer columns; row 1 = drawer rows. Drawing and touch share bounds.
     */
    fun drawerGridOptionBounds(index: Int, row: Int, width: Float, height: Float): RectF {
        val safeIndex = index.coerceIn(0, 2)
        val visualRow = safeIndex / 2
        // Two choices share the first row; the final odd choice is centered,
        // avoiding the visually unbalanced left-aligned orphan card.
        val left = when (safeIndex) {
            0 -> .13f
            1 -> .515f
            else -> .3225f
        }
        val cardWidth = .355f
        val top = (if (row == 0) .50f else .705f) + visualRow * .085f
        return RectF(width * left, height * top, width * (left + cardWidth), height * (top + .075f))
    }
}
