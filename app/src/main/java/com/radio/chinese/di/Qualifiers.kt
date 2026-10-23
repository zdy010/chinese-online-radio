package com.radio.chinese.di

import javax.inject.Qualifier

/** 进程级作用域：跟随 Application 存活，不随页面销毁。索引任务用它，退出页面仍继续爬。 */
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * 云听 broadcastId 映射（stationId → broadcastId）。
 * 由 `assets/epg_stations.json` 解析而来，全进程只读一次。
 */
@Retention(AnnotationRetention.BINARY)
@Qualifier
annotation class EpgStationMapping
