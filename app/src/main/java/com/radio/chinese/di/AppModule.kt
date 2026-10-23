package com.radio.chinese.di

import android.content.Context
import androidx.room.Room
import com.radio.chinese.data.dao.*
import com.radio.chinese.data.local.AudioLibraryDatabase
import com.radio.chinese.data.local.DownloadedOperaDao
import com.radio.chinese.data.local.EpgDatabase
import com.radio.chinese.data.local.EpgProgramDao
import com.radio.chinese.data.local.EpgStations
import com.radio.chinese.data.local.OperaDatabase
import com.radio.chinese.data.local.RadioDatabase
import com.radio.chinese.data.local.FavoriteDao
import com.radio.chinese.data.remote.EpgRemoteSource
import com.radio.chinese.data.remote.YuntingEpgClient
import com.radio.chinese.data.repository.RoomEpgStore
import com.radio.chinese.domain.browser.BrowserFactory
import com.radio.chinese.domain.epg.EpgStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideRadioDatabase(@ApplicationContext context: Context): RadioDatabase {
        return Room.databaseBuilder(
            context,
            RadioDatabase::class.java,
            "radio_database"
        ).fallbackToDestructiveMigration()
         .build()
    }

    @Provides
    fun provideFavoriteDao(database: RadioDatabase): FavoriteDao {
        return database.favoriteDao()
    }

    @Provides
    @Singleton
    fun provideOperaDatabase(@ApplicationContext context: Context): OperaDatabase {
        return Room.databaseBuilder(
            context,
            OperaDatabase::class.java,
            "opera_database"
        ).fallbackToDestructiveMigration()
         .build()
    }

    @Provides
    fun provideDownloadedOperaDao(database: OperaDatabase): DownloadedOperaDao {
        return database.downloadedOperaDao()
    }

    // ========== Audio Library ==========

    @Provides
    @Singleton
    fun provideAudioLibraryDatabase(@ApplicationContext context: Context): AudioLibraryDatabase {
        return Room.databaseBuilder(
            context,
            AudioLibraryDatabase::class.java,
            "audio_library_database"
        ).fallbackToDestructiveMigration()
            // 这个库里有用户配置的来源与凭证：1→2 必须走显式迁移，
            // 那条 fallback 只该管没写过迁移的升级
            .addMigrations(AudioLibraryDatabase.MIGRATION_1_2)
            .build()
    }

    @Provides
    fun provideAudioSourceDao(db: AudioLibraryDatabase): AudioSourceDao = db.audioSourceDao()

    @Provides
    fun provideAudioCacheDao(db: AudioLibraryDatabase): AudioCacheDao = db.audioCacheDao()

    @Provides
    fun provideAudioFavoriteDao(db: AudioLibraryDatabase): AudioFavoriteDao = db.audioFavoriteDao()

    @Provides
    fun provideAudioRecentDao(db: AudioLibraryDatabase): AudioRecentDao = db.audioRecentDao()

    // ========== 节目单 ==========

    /**
     * 独立成库：`radio_database` 开着 fallbackToDestructiveMigration，
     * 把节目单塞进去会让一次写漏的迁移带走用户的收藏。这里的数据全部可重建。
     */
    @Provides
    @Singleton
    fun provideEpgDatabase(@ApplicationContext context: Context): EpgDatabase {
        return Room.databaseBuilder(
            context,
            EpgDatabase::class.java,
            "epg_database"
        ).fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideEpgProgramDao(db: EpgDatabase): EpgProgramDao = db.epgProgramDao()

    @Provides
    @Singleton
    fun provideEpgStore(db: EpgDatabase, dao: EpgProgramDao): EpgStore = RoomEpgStore(db, dao)

    @Provides
    @Singleton
    fun provideEpgRemoteSource(client: YuntingEpgClient): EpgRemoteSource = client

    /**
     * 映射表只读一次。解析失败就返回空表：节目单功能整体消失，
     * 但 App 照常启动——一份坏资源不该拦在启动路径上。
     */
    @Provides
    @Singleton
    @EpgStationMapping
    fun provideEpgStationMapping(@ApplicationContext context: Context): Map<String, String> {
        return runCatching {
            context.assets.open("epg_stations.json").bufferedReader().use { it.readText() }
        }.map { raw -> EpgStations.parse(raw).associate { it.stationId to it.broadcastId } }
            .getOrDefault(emptyMap())
    }

    // ========== 后台索引 ==========

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default
        )

    /**
     * 索引器只认端口，不认具体实现：写行与时间戳回写都经 Room 适配块，
     * 而浏览器工厂必须每源新建客户端（单例缓存 key 不含源，会跨源串数据）。
     */
    @Provides
    @Singleton
    fun provideIndexCacheWriter(
        adapter: com.radio.chinese.data.repository.IndexDaoAdapter
    ): com.radio.chinese.domain.IndexCacheWriter = adapter

    @Provides
    @Singleton
    fun provideIndexSourceSync(
        adapter: com.radio.chinese.data.repository.IndexDaoAdapter
    ): com.radio.chinese.domain.IndexSourceSync = adapter

    @Provides
    @Singleton
    fun provideBrowserProvider(factory: BrowserFactory): com.radio.chinese.domain.BrowserProvider = factory
}
