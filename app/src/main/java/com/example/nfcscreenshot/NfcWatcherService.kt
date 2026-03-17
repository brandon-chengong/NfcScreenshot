package com.example.nfcscreenshot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
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
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class NfcWatcherService : AccessibilityService() {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    // 防止 1 秒内重复触发
    private var lastScreenshotTime = 0L

    // 小米钱包 NFC 相关包名
    private val nfcPackages = setOf(
        "com.miui.tsmclient",
        "com.xiaomi.payment",
        "com.mipay.wallet",
        "com.miui.nfc",
        "com.android.nfc"
    )

    // 刷卡成功的关键词（MIUI 常见提示文字）
    private val nfcKeywords = listOf(
        "已刷卡", "刷卡成功", "已读取", "交通卡", "门禁",
        "公交卡", "地铁", "NFC读取", "卡片已读"
    )

    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = (
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED or
                AccessibilityEvent.TYPE_ANNOUNCEMENT or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            )
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val pkg = event.packageName?.toString() ?: ""
        val text = buildString {
            event.text?.forEach { append(it) }
            event.contentDescription?.let { append(it) }
        }

        if (isNfcTapEvent(pkg, text)) {
            val now = System.currentTimeMillis()
            if (now - lastScreenshotTime > 1000) {
                lastScreenshotTime = now
                // 延迟 400ms，等提示完全显示后再截图
                mainHandler.postDelayed({ doScreenshot() }, 400)
            }
        }
    }

    private fun isNfcTapEvent(pkg: String, text: String): Boolean {
        val fromNfcApp = nfcPackages.any { pkg.contains(it) }
        val hasKeyword = nfcKeywords.any { text.contains(it) }
        return fromNfcApp || hasKeyword
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun doScreenshot() {
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

                        // 硬件 Bitmap 无法直接压缩，复制为软件 Bitmap
                        val softBitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                        hwBitmap?.recycle()

                        softBitmap?.let { saveBitmap(it) }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    // 截图失败，无操作（常见原因：屏幕熄灭）
                }
            }
        )
    }

    private fun saveBitmap(bitmap: Bitmap) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "刷卡_$timestamp.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/NFC刷卡记录"
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

    override fun onInterrupt() {}
}
