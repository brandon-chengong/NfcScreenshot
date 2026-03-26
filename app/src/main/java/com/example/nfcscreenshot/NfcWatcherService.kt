package com.example.nfcscreenshot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class NfcWatcherService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastScreenshotTime = 0L
    @Volatile private var screenshotPending = false

    // 黑名单：这些包名绝对不触发
    private val excludedPackages = setOf(
        "com.example.nfcscreenshot",
        "com.miui.gallery",
        "com.android.gallery3d",
        "com.miui.screenshot",
        "com.android.launcher",
        "com.miui.home"
    )

    private val nfcKeywords = listOf(
        "已刷卡", "刷卡成功", "已读取", "卡片已读"
    )

    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        createNotificationChannel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (excludedPackages.any { pkg.contains(it) }) return

        val text = buildString {
            event.text?.forEach { append(it) }
            event.contentDescription?.let { append(it) }
        }
        if (text.isEmpty()) return
        if (nfcKeywords.none { text.contains(it) }) return

        val now = System.currentTimeMillis()
        if (now - lastScreenshotTime < 3000 || screenshotPending) return

        lastScreenshotTime = now
        screenshotPending = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            mainHandler.postDelayed({ doScreenshot(now, retryCount = 0) }, 500)
        } else {
            screenshotPending = false
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun doScreenshot(eventTimestamp: Long, retryCount: Int) {
        val executor = Executors.newSingleThreadExecutor()
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            executor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshotResult: ScreenshotResult) {
                    try {
                        val hardwareBuffer = screenshotResult.hardwareBuffer
                        val colorSpace = screenshotResult.colorSpace
                        val hwBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                        hardwareBuffer.close()
                        val softBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        hwBitmap?.recycle()
                        softBitmap?.let { saveBitmap(it, eventTimestamp) }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        screenshotPending = false
                        executor.shutdown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    executor.shutdown()
                    if (retryCount < 3) {
                        // 延迟重试：2s / 4s / 6s
                        val delayMs = (retryCount + 1) * 2000L
                        mainHandler.postDelayed(
                            { doScreenshot(eventTimestamp, retryCount + 1) },
                            delayMs
                        )
                    } else {
                        screenshotPending = false
                        showFailureNotification(errorCode)
                    }
                }
            }
        )
    }

    private fun saveBitmap(bitmap: Bitmap, timestamp: Long) {
        try {
            val timeStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(timestamp))

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "NFC_$timeStr.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/NfcScreenshots"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)

            uri?.let {
                resolver.openOutputStream(it)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(it, values, null, null)

                // 截图保存成功 → 发通知（打卡记录直接从文件名解析，无需单独存储）
                showSuccessNotification()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            bitmap.recycle()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "nfc_screenshot", "打卡记录", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun showSuccessNotification() {
        val notification = NotificationCompat.Builder(this, "nfc_screenshot")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("刷卡截图已保存")
            .setContentText("截图已保存至相册 NfcScreenshots 文件夹")
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun showFailureNotification(errorCode: Int) {
        val notification = NotificationCompat.Builder(this, "nfc_screenshot")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("刷卡截图失败")
            .setContentText("截图重试3次仍失败（错误码 $errorCode），请手动截图")
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(System.currentTimeMillis().toInt(), notification)
    }

    override fun onInterrupt() {}
}
