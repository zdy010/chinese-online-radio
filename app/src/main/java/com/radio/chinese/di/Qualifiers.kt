package com.radio.chinese.di

import javax.inject.Qualifier

/** 进程级作用域：跟随 Application 存活，不随页面销毁。索引任务用它，退出页面仍继续爬。 */
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
