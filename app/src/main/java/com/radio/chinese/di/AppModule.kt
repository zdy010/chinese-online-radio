package com.radio.chinese.di

import android.content.Context
import androidx.room.Room
import com.radio.chinese.data.local.FavoriteDao
import com.radio.chinese.data.local.RadioDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideRadioDatabase(@ApplicationContext context: Context): RadioDatabase {
        return Room.databaseBuilder(
            context,
            RadioDatabase::class.java,
            "radio_database"
        ).build()
    }

    @Provides
    fun provideFavoriteDao(database: RadioDatabase): FavoriteDao {
        return database.favoriteDao()
    }
}
