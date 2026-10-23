package com.radio.chinese.data.entity

import androidx.room.Entity
import androidx.room.Index
import com.radio.chinese.domain.model.EpgProgram

/**
 * 节目单缓存。主键是「台 + 日期 + 开始分钟」：同一档节目在同一天只可能有一条。
 *
 * 单独的 [EpgFetchEntity] 记「这台这天拉过没有」——某台当天确实没有节目单时，
 * 接口会回空数组，光看节目行没法区分「拉过且为空」与「从没拉过」，
 * 会把空结果的台变成每次进页面都重拉。
 */
@Entity(
    tableName = "epg_program",
    primaryKeys = ["stationId", "date", "startMin"],
    indices = [Index("date")],
)
data class EpgProgramEntity(
    val stationId: String,
    val date: String,
    val startMin: Int,
    val endMin: Int,
    val title: String,
) {
    fun toDomain(): EpgProgram = EpgProgram(
        stationId = stationId,
        date = date,
        startMin = startMin,
        endMin = endMin,
        title = title,
    )

    companion object {
        fun from(program: EpgProgram): EpgProgramEntity = EpgProgramEntity(
            stationId = program.stationId,
            date = program.date,
            startMin = program.startMin,
            endMin = program.endMin,
            title = program.title,
        )
    }
}
