package com.radio.chinese.service

import com.radio.chinese.domain.model.YunPanDownloadResponse
import com.radio.chinese.domain.model.YunPanListResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * 123云盘分享链接 API (v3 b/api)
 * https://www.123pan.com/b/api/share/get
 * https://www.123pan.com/b/api/share/download/info
 */
interface YunPanApi {

    @GET("https://www.123pan.com/b/api/share/get")
    suspend fun listFiles(
        @Query("shareKey") shareKey: String,
        @Query("SharePwd") sharePassword: String,
        @Query("parentFileId") parentId: Long = 0,
        @Query("Page") page: Int = 1,
        @Query("limit") limit: Int = 100,
        @Query("next") next: String = "0",
        @Query("orderBy") orderBy: String = "file_id",
        @Query("orderDirection") orderDirection: String = "desc"
    ): YunPanListResponse

    @POST("https://www.123pan.com/b/api/share/download/info")
    suspend fun getDownloadInfo(
        @Body body: Map<String, @JvmSuppressWildcards Any>
    ): YunPanDownloadResponse
}
