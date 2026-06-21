package com.radio.chinese.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName

// ===== 123云盘 API 响应模型 (v3 b/api) =====

data class YunPanListResponse(
    val code: Int,
    val message: String?,
    val data: YunPanListData?
)

data class YunPanListData(
    @SerializedName("InfoList")
    val list: List<YunPanFile>?,
    @SerializedName("Next")
    val next: String? = null
)

data class YunPanFile(
    @SerializedName("FileId")
    val fileId: Long,
    @SerializedName("FileName")
    val fileName: String,
    @SerializedName("Type")
    val type: Int,          // 0=文件, 1=文件夹
    @SerializedName("Size")
    val fileSize: Long = 0,
    @SerializedName("ParentId")
    val parentId: Long = 0,
    @SerializedName("Etag")
    val etag: String? = null,
    @SerializedName("S3KeyFlag")
    val s3KeyFlag: String? = null
) {
    val isFolder: Boolean get() = type == 1
}

data class YunPanDownloadResponse(
    val code: Int,
    val message: String?,
    val data: YunPanDownloadData?
)

data class YunPanDownloadData(
    @SerializedName("DownloadUrl")
    val downloadUrl: String?,
    @SerializedName("FileName")
    val fileName: String?
)

// ===== 戏曲业务模型 =====

data class OperaCategory(
    val name: String,
    val folderId: Long,
    val path: String = "",  // WebDAV路径，用于导航
    val itemCount: Int = 0
)

data class OperaItem(
    val name: String,
    val folderId: Long,
    val path: String = ""  // WebDAV路径，用于导航
)

data class OperaAudioFile(
    val fileId: Long,
    val name: String,
    val size: Long,
    val categoryName: String,
    val operaName: String,
    val downloadUrl: String? = null,
    val etag: String? = null,
    val s3KeyFlag: String? = null
)

// ===== 下载模型 =====

@Entity(tableName = "downloaded_operas")
data class DownloadedOpera(
    @PrimaryKey val fileId: Long,
    val categoryName: String,
    val operaName: String,
    val fileName: String,
    val localPath: String,
    val fileSize: Long,
    val downloadTime: Long
)

enum class DownloadStatus {
    PENDING, DOWNLOADING, COMPLETED, FAILED
}

data class DownloadProgress(
    val fileId: Long,
    val fileName: String,
    val progress: Int,
    val status: DownloadStatus
)
