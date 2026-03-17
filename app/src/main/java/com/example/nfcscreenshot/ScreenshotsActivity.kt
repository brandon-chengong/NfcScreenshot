package com.example.nfcscreenshot

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Size
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ScreenshotsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dateKey = intent.getStringExtra("date_key") ?: return
        val dateDisplay = intent.getStringExtra("date_display") ?: dateKey

        supportActionBar?.title = dateDisplay
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val dp = resources.displayMetrics.density
        val p16 = (16 * dp).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p16, p16, p16, p16)
        }
        val scrollView = ScrollView(this)
        scrollView.addView(container)
        setContentView(scrollView)

        // 先显示"加载中"
        val loadingText = TextView(this).apply {
            text = "加载中..."
            textSize = 14f
            setTextColor(Color.parseColor("#888888"))
            gravity = Gravity.CENTER
            setPadding(0, (60 * dp).toInt(), 0, 0)
        }
        container.addView(loadingText)

        // 后台查询 MediaStore
        Thread {
            val uris = CheckInRepository.getScreenshotsForDate(this, dateKey)
            runOnUiThread {
                container.removeAllViews()
                if (uris.isEmpty()) {
                    container.addView(TextView(this).apply {
                        text = "当天没有找到截图文件"
                        textSize = 14f
                        setTextColor(Color.parseColor("#888888"))
                        gravity = Gravity.CENTER
                        setPadding(0, (60 * dp).toInt(), 0, 0)
                    })
                } else {
                    container.addView(TextView(this).apply {
                        text = "共 ${uris.size} 张截图，点击可放大查看"
                        textSize = 12f
                        setTextColor(Color.parseColor("#AAAAAA"))
                        setPadding(0, 0, 0, (8 * dp).toInt())
                    })
                    uris.forEach { uri -> addImageCard(container, uri, dp) }
                }
            }
        }.start()
    }

    private fun addImageCard(container: LinearLayout, uri: Uri, dp: Float) {
        val p8 = (8 * dp).toInt()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_background)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, p8, 0, 0)
            layoutParams = params
        }

        val imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (220 * dp).toInt()
            )
        }
        card.addView(imageView)
        container.addView(card)

        // 后台加载缩略图
        Thread {
            try {
                val bitmap = contentResolver.loadThumbnail(uri, Size(900, 450), null)
                runOnUiThread { imageView.setImageBitmap(bitmap) }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()

        // 点击用系统图库打开原图
        card.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "image/png")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
