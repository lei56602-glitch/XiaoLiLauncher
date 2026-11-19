package com.xiaoli.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DrawerGridMetricsTest {
    @Test
    fun fewerRowsProduceLargerTvIcons() {
        val twoRows = DrawerGridMetrics(1920f, 1080f, 5, 2)
        val fourRows = DrawerGridMetrics(1920f, 1080f, 5, 4)
        assertTrue(twoRows.iconSize(twoRows.cellWidth) > fourRows.iconSize(fourRows.cellWidth))
    }

    @Test
    fun iconScaleAdaptsToDisplayResolution() {
        val fullHd = DrawerGridMetrics(1920f, 1080f, 5, 2)
        val fourK = DrawerGridMetrics(3840f, 2160f, 5, 2)
        assertTrue(fourK.iconSize(fourK.cellWidth) > fullHd.iconSize(fullHd.cellWidth))
    }

    @Test
    fun hitTestingUsesVisibleRowAndScrollOffset() {
        val metrics = DrawerGridMetrics(1920f, 1080f, 5, 2)
        val x = metrics.gridLeft + metrics.cellWidth * .5f
        val firstRowY = metrics.contentTop + metrics.cellHeight * .5f
        val secondVisibleRowY = metrics.contentTop + metrics.cellHeight * 1.5f

        assertEquals(0, metrics.indexAt(x, firstRowY, 0, 20))
        assertEquals(5, metrics.indexAt(x, secondVisibleRowY, 0, 20))
        assertEquals(15, metrics.indexAt(x, firstRowY, 3, 20))
    }

    @Test
    fun hitTestingRejectsGapsAndItemsOutsideTheList() {
        val metrics = DrawerGridMetrics(1920f, 1080f, 5, 2)
        val gapX = metrics.gridLeft + metrics.cellWidth * .01f
        val centerY = metrics.contentTop + metrics.cellHeight * .5f
        val lastCellX = metrics.gridRight - metrics.cellWidth * .5f

        assertEquals(-1, metrics.indexAt(gapX, centerY, 0, 20))
        assertEquals(-1, metrics.indexAt(lastCellX, centerY, 0, 4))
    }
    @Test
    fun horizontalFocusDoesNotWrapAcrossGridRows() {
        val metrics = DrawerGridMetrics(1920f, 1080f, 5, 2)
        assertEquals(4, metrics.moveFocus(4, 1, 20))
        assertEquals(5, metrics.moveFocus(5, -1, 20))
        assertEquals(3, metrics.moveFocus(4, -1, 20))
        assertEquals(9, metrics.moveFocus(8, 1, 12))
    }

    @Test
    fun verticalFocusDoesNotJumpIntoMissingCellsOfIncompleteRows() {
        val metrics = DrawerGridMetrics(1920f, 1080f, 5, 2)
        assertEquals(8, metrics.moveFocus(3, 2, 9))
        assertEquals(8, metrics.moveFocus(8, 2, 9))
        assertEquals(8, metrics.moveFocus(8, 1, 9))
        assertEquals(3, metrics.moveFocus(8, -2, 9))
    }
}

