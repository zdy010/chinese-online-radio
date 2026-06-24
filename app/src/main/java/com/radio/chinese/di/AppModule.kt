package com.radio.chinese.di

import android.content.Context
import androidx.room.Room
import com.radio.chinese.data.dao.*
import com.radio.chinese.data.local.AudioLibraryDatabase
import com.radio.chinese.data.local.DownloadedOperaDao
import com.radio.chinese.data.local.OperaDatabase
import com.radio.chinese.data.local.RadioDatabase
import com.radio.chinese.data.local.FavoriteDao
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
}
