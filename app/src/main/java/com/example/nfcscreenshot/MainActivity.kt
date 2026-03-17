package com.example.nfcscreenshot

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btn_enable).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus() {
        val enabled = isAccessibilityServiceEnabled()
        val statusText = findViewById<TextView>(R.id.tv_status)
        val btnEnable = findViewById<Button>(R.id.btn_enable)

        if (enabled) {
            statusText.text = "✅ 无障碍服务已开启\n\n每次刷门禁后会自动截图\n截图保存在：相册 → NFC刷卡记录"
            btnEnable.text = "重新检查 / 前往设置"
        } else {
            statusText.text = "❌ 无障碍服务未开启\n\n请点击下方按钮，找到「NFC刷卡截图」并开启"
            btnEnable.text = "前往开启无障碍权限"
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(packageName)
    }
}
