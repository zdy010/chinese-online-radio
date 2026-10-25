package com.radio.chinese.ui.theme

/**
 * AppTheme 已下移到 domain/model 以消除 data→ui 违例。
 * 此处保留 typealias 使既有 ui.* 引用无需全量修改。
 */
typealias AppTheme = com.radio.chinese.domain.model.AppTheme
typealias AppThemeGroup = com.radio.chinese.domain.model.AppThemeGroup
typealias ThemeTone = com.radio.chinese.domain.model.ThemeTone
