package com.example.nfcscreenshot

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class RecordsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.title = "打卡记录"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        buildLayout()
    }

    override fun onResume() {
        super.onResume()
        buildLayout()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun buildLayout() {
        val dp = resources.displayMetrics.density
        val p16 = (16 * dp).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(p16, p16, p16, p16)
        }

        val scrollView = ScrollView(this)
        scrollView.addView(container)
        setContentView(scrollView)

        val dayRecords = CheckInRepository.getDayRecords(this)

        if (dayRecords.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "暂无打卡记录\n刷门禁后自动记录"
                textSize = 16f
                setTextColor(Color.parseColor("#888888"))
                gravity = Gravity.CENTER
                setPadding(0, (80 * dp).toInt(), 0, 0)
            })
            return
        }

        for (day in dayRecords) {
            container.addView(buildDayCard(day, dp))
        }
    }

    private fun buildDayCard(day: DayRecord, dp: Float): LinearLayout {
        val p16 = (16 * dp).toInt()
        val p8 = (8 * dp).toInt()
        val p6 = (6 * dp).toInt()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_background)
            setPadding(p16, p16, p16, p16)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, p8, 0, 0)
            layoutParams = params
        }

        // 第一行：日期 + 次数
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(TextView(this).apply {
            text = "${day.dateDisplay}  ${day.weekDay}"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A1A1A"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row1.addView(TextView(this).apply {
            text = "共${day.tapCount}次  ›"
            textSize = 13f
            setTextColor(Color.parseColor("#1976D2"))
        })
        card.addView(row1)

        // 第二行：最早/最晚打卡时间
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, p8, 0, 0)
        }
        row2.addView(TextView(this).apply {
            text = "▶ 进入  ${day.firstTime}"
            textSize = 14f
            setTextColor(Color.parseColor("#2E7D32"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row2.addView(TextView(this).apply {
            text = "■ 离开  ${day.lastTime}"
            textSize = 14f
            setTextColor(Color.parseColor("#C62828"))
        })
        card.addView(row2)

        // 第三行：在岗时长
        card.addView(TextView(this).apply {
            text = "在岗时长：${day.durationText}"
            textSize = 13f
            setTextColor(Color.parseColor("#555555"))
            setPadding(0, p6, 0, 0)
        })

        // 第四行：所有打卡时间点（超过2次才显示）
        if (day.tapCount > 2) {
            card.addView(TextView(this).apply {
                text = "所有记录：" + day.records.joinToString("  |  ") { it.timeDisplay }
                textSize = 11f
                setTextColor(Color.parseColor("#AAAAAA"))
                setPadding(0, (4 * dp).toInt(), 0, 0)
            })
        }

        // 点击跳转到截图列表
        card.setOnClickListener {
            startActivity(Intent(this, ScreenshotsActivity::class.java).apply {
                putExtra("date_key", day.dateKey)
                putExtra("date_display", "${day.dateDisplay} ${day.weekDay}")
            })
        }

        return card
    }
}
