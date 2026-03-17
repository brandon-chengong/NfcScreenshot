package com.example.nfcscreenshot

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
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

    private const val FILE_NAME = "checkin_records.json"
    private val RELATIVE_PATH = Environment.DIRECTORY_PICTURES + "/NfcScreenshots"

    // ── 写入一条新记录 ──────────────────────────────────────────────────────────
    fun saveRecord(context: Context, timestamp: Long) {
        val list = loadTimestamps(context).toMutableList()
        list.add(timestamp)
        writeJson(context, list)
    }

    // ── 按月 → 周 → 天分组，供 RecordsActivity 使用 ───────────────────────────
    fun getMonthRecords(context: Context): List<MonthRecord> {
        val dayRecords = getDayRecords(context)

        // 每一天 → 找所在周的周一 key
        val byMonday = dayRecords.groupBy { getMondayKey(it.dateKey) }

        // 构建 WeekRecord 列表
        val weekRecords = byMonday.entries.map { (mondayKey, days) ->
            WeekRecord(
                weekLabel = buildWeekLabel(mondayKey),
                mondayKey = mondayKey,
                days = days.sortedByDescending { it.dateKey }
            )
        }

        // 按周一所在的月份分组 → MonthRecord
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

    // ── 内部：按天汇总 ─────────────────────────────────────────────────────────
    fun getDayRecords(context: Context): List<DayRecord> {
        return loadTimestamps(context)
            .map { CheckInRecord(it) }
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

    // ── 某天属于哪周的周一（返回 "yyyyMMdd"）──────────────────────────────────
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

    // ── 构建"第 N 周（M/d - M/d）"标签 ─────────────────────────────────────────
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

    // ── 从 MediaStore JSON 文件读取时间戳列表 ──────────────────────────────────
    private fun loadTimestamps(context: Context): List<Long> {
        val uri = findJsonUri(context) ?: return emptyList()
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val json = JSONArray(input.bufferedReader().readText())
                (0 until json.length()).map { json.getLong(it) }
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ── 写入时间戳列表到 MediaStore JSON 文件 ─────────────────────────────────
    private fun writeJson(context: Context, timestamps: List<Long>) {
        val json = JSONArray(timestamps).toString()
        val resolver = context.contentResolver

        val existing = findJsonUri(context)
        if (existing != null) {
            // 文件已存在：覆盖写入
            resolver.openOutputStream(existing, "wt")?.use { it.write(json.toByteArray()) }
            return
        }

        // 文件不存在：新建
        val values = ContentValues().apply {
            put(MediaStore.Files.FileColumns.DISPLAY_NAME, FILE_NAME)
            put(MediaStore.Files.FileColumns.MIME_TYPE, "application/json")
            put(MediaStore.Files.FileColumns.RELATIVE_PATH, RELATIVE_PATH)
        }
        val newUri = resolver.insert(MediaStore.Files.getContentUri("external"), values) ?: return
        resolver.openOutputStream(newUri)?.use { it.write(json.toByteArray()) }
    }

    // ── 在 MediaStore 中查找 checkin_records.json 的 Uri ──────────────────────
    private fun findJsonUri(context: Context): Uri? {
        val projection = arrayOf(MediaStore.Files.FileColumns._ID)
        val selection = "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ? AND " +
                "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ?"
        val args = arrayOf("%NfcScreenshots%", FILE_NAME)

        context.contentResolver.query(
            MediaStore.Files.getContentUri("external"),
            projection, selection, args, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID))
                return ContentUris.withAppendedId(MediaStore.Files.getContentUri("external"), id)
            }
        }
        return null
    }

    // ── 查询某天的截图 Uri 列表（ScreenshotsActivity 用）────────────────────────
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
