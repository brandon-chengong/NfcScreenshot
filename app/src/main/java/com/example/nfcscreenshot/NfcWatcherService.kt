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
    private var screenshotPending = false

    // 黑名单：这些包名绝对不触发（相册、截图工具、我们自己的App）
    private val excludedPackages = setOf(
        "com.example.nfcscreenshot",   // 本App
        "com.miui.gallery",            // MIUI相册
        "com.android.gallery3d",       // 原生相册
        "com.miui.screenshot",         // MIUI截图
        "com.miui.systemui",           // 系统UI某些情况
        "com.android.launcher",        // 桌面
        "com.miui.home"                // MIUI桌面
    )

    // 刷卡关键词
    private val nfcKeywords = listOf(
        "已刷卡", "刷卡成功", "已读取", "卡片已读"
    )

    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            // 监听所有事件类型，确保能捕获 MIUI NFC 悬浮通知
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

        // 第一层：黑名单排除
        if (excludedPackages.any { pkg.contains(it) }) return

        // 提取事件文字
        val text = buildString {
            event.text?.forEach { append(it) }
            event.contentDescription?.let { append(it) }
        }

        if (text.isEmpty()) return

        // 第二层：必须包含刷卡关键词
        if (nfcKeywords.none { text.contains(it) }) return

        // 防抖：3 秒内只截一次，且上一次必须完成
        val now = System.currentTimeMillis()
        if (now - lastScreenshotTime < 3000 || screenshotPending) return

        lastScreenshotTime = now
        screenshotPending = true

        // 延迟 500ms 等通知完全展开
        mainHandler.postDelayed({ doScreenshot() }, 500)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun doScreenshot() {
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

                        softBitmap?.let {
                            saveBitmap(it)
                            // 截图成功后发一条通知告知用户
                            showSuccessNotification()
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        screenshotPending = false
                        executor.shutdown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    screenshotPending = false
                    executor.shutdown()
                }
            }
        )
    }

    private fun saveBitmap(bitmap: Bitmap) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "NFC_$timestamp.png")
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
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            bitmap.recycle()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "nfc_screenshot",
            "NFC刷卡截图",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun showSuccessNotification() {
        val notification = NotificationCompat.Builder(this, "nfc_screenshot")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("刷卡截图已保存")
            .setContentText("截图已保存至相册 NfcScreenshots 文件夹")
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(System.currentTimeMillis().toInt(), notification)
    }

    override fun onInterrupt() {}
}
