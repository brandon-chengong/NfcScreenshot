package com.example.nfcscreenshot

import android.content.ContentUris
import android.content.Context
import android.net.Uri
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
    val records: List<CheckInRecord>  // 按时间升序
) {
    val firstTime: String = records.first().timeDisplay
    val lastTime: String = records.last().timeDisplay
    val tapCount: Int = records.size
    val durationText: String get() {
        if (records.size < 2) return "仅1次打卡"
        val minutes = (records.last().timestamp - records.first().timestamp) / 60000
        val hours = minutes / 60
        val mins = minutes % 60
        return if (hours > 0) "${hours}小时${mins}分钟" else "${mins}分钟"
    }
}

object CheckInRepository {

    private const val PREF_NAME = "checkin_data"
    private const val KEY_TIMESTAMPS = "timestamps"

    fun saveRecord(context: Context, timestamp: Long) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_TIMESTAMPS, "[]") ?: "[]"
        val array = JSONArray(existing)
        array.put(timestamp)
        prefs.edit().putString(KEY_TIMESTAMPS, array.toString()).apply()
    }

    fun getDayRecords(context: Context): List<DayRecord> {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_TIMESTAMPS, "[]") ?: "[]"
        val array = JSONArray(json)

        val all = mutableListOf<CheckInRecord>()
        for (i in 0 until array.length()) {
            all.add(CheckInRecord(array.getLong(i)))
        }

        return all
            .groupBy { it.dateKey }
            .map { (dateKey, recs) ->
                val sorted = recs.sortedBy { it.timestamp }
                DayRecord(
                    dateDisplay = sorted.first().dateDisplay,
                    weekDay = sorted.first().weekDay,
                    dateKey = dateKey,
                    records = sorted
                )
            }
            .sortedByDescending { it.dateKey }
    }

    /** 查询 MediaStore 中指定日期的 NFC 截图，dateKey 格式为 "yyyyMMdd" */
    fun getScreenshotsForDate(context: Context, dateKey: String): List<Uri> {
        val projection = arrayOf(MediaStore.Images.Media._ID)
        val selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? " +
                "AND ${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?"
        val selectionArgs = arrayOf("%NfcScreenshots%", "NFC_${dateKey}_%")

        val uris = mutableListOf<Uri>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, selection, selectionArgs,
            "${MediaStore.Images.Media.DATE_ADDED} ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                uris.add(ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                ))
            }
        }
        return uris
    }
}
