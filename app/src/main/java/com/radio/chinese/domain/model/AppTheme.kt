package com.radio.chinese.domain.model

/**
 * 主题的明暗属性。套餐式的关键：深浅是主题自带的属性，
 * 不再是和「配色」正交的一个开关——老人不需要理解两个开关的组合。
 */
enum class ThemeTone { LIGHT, DARK, SYSTEM }

/** 设置页对话框的分组标题，每组三项。 */
enum class AppThemeGroup(val label: String) {
    CLASSIC("经典"),
    NIGHT("夜间护眼"),
    READABLE("易读与个性"),
}

/**
 * 内置主题。
 *
 * 持久化用 [key] 字符串而不是枚举序号：以后增删或调整顺序时，
 * 存过旧值的用户会被序号静默换掉主题，用 key 最多多一行 fromKey 兜底。
 * [label] / [hint] 是给中老年看的场景说法，不写 AMOLED、tone 之类的技术名。
 */
enum class AppTheme(
    val key: String,
    val label: String,
    val hint: String,
    val group: AppThemeGroup,
    val tone: ThemeTone,
) {
    SYSTEM("system", "跟随系统", "不想操心", AppThemeGroup.CLASSIC, ThemeTone.SYSTEM),
    CLASSIC_LIGHT("classic_light", "经典蓝·日间", "白天正常使用", AppThemeGroup.CLASSIC, ThemeTone.LIGHT),
    CLASSIC_DARK("classic_dark", "经典蓝·夜间", "习惯旧版夜间", AppThemeGroup.CLASSIC, ThemeTone.DARK),

    SOFT_DARK("soft_dark", "深灰柔和", "夜间长时间收听", AppThemeGroup.NIGHT, ThemeTone.DARK),
    PURE_BLACK("pure_black", "纯黑·夜高对比", "OLED 屏夜间行车", AppThemeGroup.NIGHT, ThemeTone.DARK),
    AMBER("amber", "琥珀暖光", "深夜砍掉蓝光", AppThemeGroup.NIGHT, ThemeTone.DARK),

    HIGH_CONTRAST("high_contrast", "亮白·强对比", "阳光直射屏幕", AppThemeGroup.READABLE, ThemeTone.LIGHT),
    CHINA_RED("china_red", "中国红", "喜欢喜庆配色", AppThemeGroup.READABLE, ThemeTone.LIGHT),
    INK_GREEN("ink_green", "墨绿", "喜欢安静配色", AppThemeGroup.READABLE, ThemeTone.LIGHT),
    ;

    /**
     * 全树唯一的深浅结论。页面里不许再读一次 isSystemInDarkTheme() 自己算，
     * 否则同一棵树会出现两套深浅（配色与系统栏打架就是这么来的）。
     */
    fun isDarkConsidering(systemDark: Boolean): Boolean = when (tone) {
        ThemeTone.LIGHT -> false
        ThemeTone.DARK -> true
        ThemeTone.SYSTEM -> systemDark
    }

    companion object {
        fun fromKey(key: String?): AppTheme =
            entries.firstOrNull { it.key == key } ?: SYSTEM

        /** 旧 theme_mode(Int) 的唯一映射表。除本任务测试外不得出现第二处映射。 */
        fun fromLegacyMode(mode: Int): AppTheme = when (mode) {
            1 -> CLASSIC_LIGHT
            2 -> CLASSIC_DARK
            else -> SYSTEM
        }

        /**
         * 读取侧的一次性合并：有 theme_key 用 theme_key，没有才用旧 theme_mode。
         */
        fun fromStoredValues(storedKey: String?, legacyMode: Int): AppTheme =
            if (storedKey == null) fromLegacyMode(legacyMode) else fromKey(storedKey)

        /** 设置页分组渲染用，保持枚举声明顺序。 */
        fun groupedForPicker(): List<Pair<AppThemeGroup, List<AppTheme>>> =
            entries.groupBy { it.group }.map { (group, list) -> group to list }
    }
}
