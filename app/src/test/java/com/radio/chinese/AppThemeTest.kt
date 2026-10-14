package com.radio.chinese

import com.radio.chinese.ui.theme.AppTheme
import com.radio.chinese.ui.theme.AppThemeGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppThemeTest {

    @Test
    fun keysAreUniqueAndGroupsAreComplete() {
        val keys = AppTheme.entries.map { it.key }
        assertEquals("key 必须唯一，否则 fromKey 会命中第一个", keys.size, keys.toSet().size)
        // 三组各三项，设置页每组正好一屏一组（spec §2）
        AppTheme.entries.groupBy { it.group }.forEach { (group, list) ->
            assertEquals("${group.label} 应恰好 3 项", 3, list.size)
        }
        assertEquals(9, AppTheme.entries.size)
    }

    @Test
    fun fromKeyFallsBackToSystem() {
        assertEquals(AppTheme.AMBER, AppTheme.fromKey("amber"))
        // 未知键、空串、null 都不能让 App 变成未知配色
        assertEquals(AppTheme.SYSTEM, AppTheme.fromKey("bogus"))
        assertEquals(AppTheme.SYSTEM, AppTheme.fromKey(""))
        assertEquals(AppTheme.SYSTEM, AppTheme.fromKey(null))
    }

    @Test
    fun legacyModeMapsOneWay() {
        assertEquals(AppTheme.SYSTEM, AppTheme.fromLegacyMode(0))
        assertEquals(AppTheme.CLASSIC_LIGHT, AppTheme.fromLegacyMode(1))
        assertEquals(AppTheme.CLASSIC_DARK, AppTheme.fromLegacyMode(2))
        // 脏数据（旧版本写入以外的值）一律回落跟随系统，不猜
        assertEquals(AppTheme.SYSTEM, AppTheme.fromLegacyMode(7))
        assertEquals(AppTheme.SYSTEM, AppTheme.fromLegacyMode(-1))
    }

    @Test
    fun storedKeyWinsOverLegacyModeButGarbageKeyDoesNotMaskIt() {
        // 全新安装：两个键都没有 → system，且首帧就是这个结论（Review Focus 1）
        assertEquals(AppTheme.SYSTEM, AppTheme.fromStoredValues(null, 0))
        // 只有旧键：升级用户的唯一迁移路径
        assertEquals(AppTheme.CLASSIC_DARK, AppTheme.fromStoredValues(null, 2))
        // 新键有效：旧键此后不再参与
        assertEquals(AppTheme.AMBER, AppTheme.fromStoredValues("amber", 2))
        // 新键是脏值：回落 system，而不是拿旧键猜一个——用户既然存过 theme_key，
        // 旧 theme_mode 就是上一代结论，用它会让用户「切了主题却变成别的主题」
        assertEquals(AppTheme.SYSTEM, AppTheme.fromStoredValues("bogus", 2))
    }

    @Test
    fun isDarkConsideringIsSingleSourceOfTruth() {
        assertTrue(AppTheme.SOFT_DARK.isDarkConsidering(systemDark = false))
        assertTrue(AppTheme.PURE_BLACK.isDarkConsidering(systemDark = false))
        assertTrue(AppTheme.AMBER.isDarkConsidering(systemDark = false))
        assertFalse(AppTheme.HIGH_CONTRAST.isDarkConsidering(systemDark = true))
        assertFalse(AppTheme.CLASSIC_LIGHT.isDarkConsidering(systemDark = true))
        // 只有 system 一项跟随入参
        assertTrue(AppTheme.SYSTEM.isDarkConsidering(systemDark = true))
        assertFalse(AppTheme.SYSTEM.isDarkConsidering(systemDark = false))
    }

    @Test
    fun everyThemeHasUsableCopy() {
        // 面向中老年的名称与场景说明不能空，也不能长到挤掉色块
        AppTheme.entries.forEach { theme ->
            assertTrue("${theme.key} 缺名称", theme.label.isNotBlank())
            assertTrue("${theme.key} 缺场景说明", theme.hint.isNotBlank())
            assertTrue("${theme.key} 名称过长", theme.label.length <= 10)
            assertTrue("${theme.key} 场景说明过长", theme.hint.length <= 12)
        }
    }

    @Test
    fun groupedForPickerKeepsDeclaredOrder() {
        val groups = AppTheme.groupedForPicker()
        assertEquals(
            listOf(AppThemeGroup.CLASSIC, AppThemeGroup.NIGHT, AppThemeGroup.READABLE),
            groups.map { it.first }
        )
        assertEquals("amber", groups[1].second[2].key)
    }
}
