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

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastScreenshotTime = 0L
    private var screenshotPending = false

    // 只允许这些包名触发截图（小米 NFC 相关 + 系统通知）
    private val nfcPackages = setOf(
        "com.miui.tsmclient",
        "com.xiaomi.payment",
        "com.mipay.wallet",
        "com.miui.nfc",
        "com.android.nfc",
        "com.android.systemui"
    )

    // 刷卡成功关键词
    private val nfcKeywords = listOf(
        "已刷卡", "刷卡成功", "已读取", "卡片已读"
    )

    override fun onServiceConnected() {
        serviceInfo = AccessibilityServiceInfo().apply {
            // 关键：只监听【通知】和【窗口弹出】，不监听窗口内容变化
            // TYPE_WINDOW_CONTENT_CHANGED 会在看截图、浏览相册时误触发
            eventTypes = (
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            )
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return

        val pkg = event.packageName?.toString() ?: return

        // 第一层过滤：包名必须在白名单中
        if (nfcPackages.none { pkg.contains(it) }) return

        // 提取事件文字
        val text = buildString {
            event.text?.forEach { append(it) }
            event.contentDescription?.let { append(it) }
        }

        // 第二层过滤：文字必须包含刷卡关键词
        if (nfcKeywords.none { text.contains(it) }) return

        // 防抖：3 秒内只截一次，且上一次截图必须完成
        val now = System.currentTimeMillis()
        if (now - lastScreenshotTime < 3000 || screenshotPending) return

        lastScreenshotTime = now
        screenshotPending = true

        // 延迟 500ms 等通知完全展开后截图
        mainHandler.postDelayed({ doScreenshot() }, 500)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun doScreenshot() {
        // 每次创建新的线程执行截图，避免上次阻塞影响下次
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

                        softBitmap?.let { saveBitmap(it) }
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

    override fun onInterrupt() {}
}
