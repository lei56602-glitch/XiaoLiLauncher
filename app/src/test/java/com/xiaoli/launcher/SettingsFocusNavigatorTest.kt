package com.xiaoli.launcher

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsFocusNavigatorTest {
    @Test
    fun everyRenderedSettingsControlIsReachable() {
        assertEquals(
            setOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 13, 14, 15, 16, 17, 18, 19, 21, 22, 23),
            SettingsFocusNavigator.supportedFocusIds()
        )
    }

    @Test
    fun horizontalMovementStaysInsideCurrentRow() {
        assertEquals(10, SettingsFocusNavigator.next(9, 1))
        assertEquals(9, SettingsFocusNavigator.next(10, -1))
        assertEquals(9, SettingsFocusNavigator.next(9, -1))
        assertEquals(16, SettingsFocusNavigator.next(16, 1))
    }

    @Test
    fun verticalMovementChoosesNearestColumn() {
        assertEquals(10, SettingsFocusNavigator.next(8, 2))
        assertEquals(4, SettingsFocusNavigator.next(2, 2))
        assertEquals(6, SettingsFocusNavigator.next(3, 2))
        assertEquals(7, SettingsFocusNavigator.next(6, 2))
        assertEquals(6, SettingsFocusNavigator.next(7, -2))
    }
}
