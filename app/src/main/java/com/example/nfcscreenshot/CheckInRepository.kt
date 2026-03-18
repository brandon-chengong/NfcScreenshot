package com.example.nfcscreenshot

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.*

data class CheckInRecord(val timestamp: Long) {
    val dateKey: String = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(timestamp))
    val dateDisplay: String = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))
    val timeDisplay: String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    val weekDay: String = SimpleDateFormat("EEEE", Locale.CHINESE).format(Date(timestamp))
}

data class DayRecord(
    val dateDisplay: String,
    val weekDay: String,
    val dateKey: String,
    val records: List<CheckInRecord>
) {
    val firstTime: String = records.first().timeDisplay
    val lastTime: String = records.last().timeDisplay
    val tapCount: Int = records.size
    val durationText: String
        get() {
            if (records.size < 2) return "仅1次打卡"
            val minutes = (records.last().timestamp - records.first().timestamp) / 60000
            val hours = minutes / 60
            val mins = minutes % 60
            return if (hours > 0) "${hours}小时${mins}分钟" else "${mins}分钟"
        }
}

data class WeekRecord(
    val weekLabel: String,
    val mondayKey: String,
    val days: List<DayRecord>
)

data class MonthRecord(
    val monthLabel: String,
    val monthKey: String,
    val weeks: List<WeekRecord>
)

object CheckInRepository {

    // ── 按月 → 周 → 天 分组 ────────────────────────────────────────────────────
    fun getMonthRecords(context: Context): List<MonthRecord> {
        val dayRecords = getDayRecords(context)
        val byMonday = dayRecords.groupBy { getMondayKey(it.dateKey) }

        val weekRecords = byMonday.entries.map { (mondayKey, days) ->
            WeekRecord(
                weekLabel = buildWeekLabel(mondayKey),
                mondayKey = mondayKey,
                days = days.sortedByDescending { it.dateKey }
            )
        }

        val byMonth = weekRecords.groupBy { it.mondayKey.substring(0, 6) }

        return byMonth.entries
            .sortedByDescending { it.key }
            .map { (monthKey, weeks) ->
                val year = monthKey.substring(0, 4)
                val month = monthKey.substring(4, 6).trimStart('0')
                MonthRecord(
                    monthLabel = "${year}年${month}月",
                    monthKey = monthKey,
                    weeks = weeks.sortedByDescending { it.mondayKey }
                )
            }
    }

    // ── 按天汇总（直接从截图文件名解析时间戳，不依赖任何 JSON 文件）──────────
    fun getDayRecords(context: Context): List<DayRecord> {
        return scanScreenshots(context)
            .groupBy { it.dateKey }
            .map { (_, recs) ->
                val sorted = recs.sortedBy { it.timestamp }
                DayRecord(
                    dateDisplay = sorted.first().dateDisplay,
                    weekDay = sorted.first().weekDay,
                    dateKey = sorted.first().dateKey,
                    records = sorted
                )
            }
            .sortedByDescending { it.dateKey }
    }

    // ── 扫描 NfcScreenshots 下所有截图，从文件名提取时间戳 ────────────────────
    private fun scanScreenshots(context: Context): List<CheckInRecord> {
        val projection = arrayOf(MediaStore.Images.Media.DISPLAY_NAME)
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? " +
                "AND ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%NfcScreenshots%", "NFC_%")

        val records = mutableListOf<CheckInRecord>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, selection, args, null
        )?.use { cursor ->
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val ts = parseFilenameTimestamp(cursor.getString(nameCol))
                if (ts > 0) records.add(CheckInRecord(ts))
            }
        }
        return records
    }

    // ── "NFC_20260318_091532.png" → 时间戳 ──────────────────────────────────────
    private fun parseFilenameTimestamp(filename: String): Long {
        return try {
            val dateStr = filename.removePrefix("NFC_").removeSuffix(".png")
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    // ── 某天属于哪周的周一 ──────────────────────────────────────────────────────
    private fun getMondayKey(dateKey: String): String {
        val cal = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            set(dateKey.substring(0, 4).toInt(),
                dateKey.substring(4, 6).toInt() - 1,
                dateKey.substring(6, 8).toInt())
        }
        val dow = cal.get(Calendar.DAY_OF_WEEK)
        val offset = if (dow == Calendar.SUNDAY) 6 else dow - Calendar.MONDAY
        cal.add(Calendar.DAY_OF_YEAR, -offset)
        return SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(cal.time)
    }

    // ── "第N周（M/d - M/d）" ────────────────────────────────────────────────────
    private fun buildWeekLabel(mondayKey: String): String {
        val cal = Calendar.getInstance().apply {
            firstDayOfWeek = Calendar.MONDAY
            minimalDaysInFirstWeek = 4
            set(mondayKey.substring(0, 4).toInt(),
                mondayKey.substring(4, 6).toInt() - 1,
                mondayKey.substring(6, 8).toInt())
        }
        val weekNo = cal.get(Calendar.WEEK_OF_YEAR)
        val m1 = cal.get(Calendar.MONTH) + 1
        val d1 = cal.get(Calendar.DAY_OF_MONTH)
        cal.add(Calendar.DAY_OF_YEAR, 6)
        val m2 = cal.get(Calendar.MONTH) + 1
        val d2 = cal.get(Calendar.DAY_OF_MONTH)
        return "第${weekNo}周（${m1}/${d1} - ${m2}/${d2}）"
    }

    // ── 查询某天的截图 Uri 列表 ─────────────────────────────────────────────────
    fun getScreenshotsForDate(context: Context, dateKey: String): List<Uri> {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? " +
                "AND ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("%NfcScreenshots%", "NFC_${dateKey}_%")

        val uris = mutableListOf<Uri>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, selection, args,
            "${MediaStore.Images.Media.DATE_ADDED} ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                uris.add(ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    cursor.getLong(idCol)
                ))
            }
        }
        return uris
    }
}
