package com.radio.chinese.di

import android.content.Context
import androidx.room.Room
import com.radio.chinese.data.local.DownloadedOperaDao
import com.radio.chinese.data.local.OperaDatabase
import com.radio.chinese.data.local.RadioDatabase
import com.radio.chinese.data.local.FavoriteDao
import com.radio.chinese.service.YunPanApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
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

    @Provides
    @Singleton
    fun provideOperaDatabase(@ApplicationContext context: Context): OperaDatabase {
        return Room.databaseBuilder(
            context,
            OperaDatabase::class.java,
            "opera_database"
        ).build()
    }

    @Provides
    fun provideDownloadedOperaDao(database: OperaDatabase): DownloadedOperaDao {
        return database.downloadedOperaDao()
    }

    @Provides
    @Singleton
    fun provideYunPanApi(): YunPanApi {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    .header("Origin", "https://www.123pan.com")
                    .header("Referer", "https://www.123pan.com/")
                    .header("Platform", "web")
                    .header("App-Version", "3")
                    .build()
                chain.proceed(request)
            }
            .build()
        return Retrofit.Builder()
            .baseUrl("https://www.123pan.com/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(YunPanApi::class.java)
    }
}
