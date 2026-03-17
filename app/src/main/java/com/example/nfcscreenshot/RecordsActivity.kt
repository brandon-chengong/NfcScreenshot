package com.example.nfcscreenshot

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
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

    // ── 顶层：构建整个页面 ────────────────────────────────────────────────────
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

        val monthRecords = CheckInRepository.getMonthRecords(this)

        if (monthRecords.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "暂无打卡记录\n刷门禁后自动记录"
                textSize = 16f
                setTextColor(Color.parseColor("#888888"))
                gravity = Gravity.CENTER
                setPadding(0, (80 * dp).toInt(), 0, 0)
            })
            return
        }

        for (month in monthRecords) {
            container.addView(buildMonthSection(month, dp))
        }
    }

    // ── 月份区块：蓝色标题 + 可折叠的周列表 ──────────────────────────────────
    private fun buildMonthSection(month: MonthRecord, dp: Float): LinearLayout {
        val p8 = (8 * dp).toInt()
        val p12 = (12 * dp).toInt()
        val p16 = (16 * dp).toInt()

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, p8, 0, 0) }
        }

        val totalDays = month.weeks.sumOf { it.days.size }

        val weeksContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        for (week in month.weeks) {
            weeksContainer.addView(buildWeekSection(week, dp))
        }

        val header = TextView(this).apply {
            text = "▼  ${month.monthLabel}  ·  ${totalDays}天"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1565C0"))
            setPadding(p16, p12, p16, p12)
            tag = true  // true = 展开
        }

        header.setOnClickListener {
            val expanded = header.tag as Boolean
            weeksContainer.visibility = if (expanded) View.GONE else View.VISIBLE
            header.text = "${if (expanded) "▶" else "▼"}  ${month.monthLabel}  ·  ${totalDays}天"
            header.tag = !expanded
        }

        outer.addView(header)
        outer.addView(weeksContainer)
        return outer
    }

    // ── 周区块：浅蓝标题 + 可折叠的日卡片列表 ────────────────────────────────
    private fun buildWeekSection(week: WeekRecord, dp: Float): LinearLayout {
        val p8 = (8 * dp).toInt()
        val p16 = (16 * dp).toInt()

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val daysContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((8 * dp).toInt(), 0, 0, 0)
        }
        for (day in week.days) {
            daysContainer.addView(buildDayCard(day, dp))
        }

        val header = TextView(this).apply {
            text = "  ▼  ${week.weekLabel}  ·  ${week.days.size}天"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#1A1A1A"))
            setBackgroundColor(Color.parseColor("#E3F2FD"))
            setPadding(p16, p8, p16, p8)
            tag = true  // true = 展开
        }

        header.setOnClickListener {
            val expanded = header.tag as Boolean
            daysContainer.visibility = if (expanded) View.GONE else View.VISIBLE
            header.text = "  ${if (expanded) "▶" else "▼"}  ${week.weekLabel}  ·  ${week.days.size}天"
            header.tag = !expanded
        }

        outer.addView(header)
        outer.addView(daysContainer)
        return outer
    }

    // ── 日卡片：与原版保持一致，点击进入截图页 ───────────────────────────────
    private fun buildDayCard(day: DayRecord, dp: Float): LinearLayout {
        val p16 = (16 * dp).toInt()
        val p8 = (8 * dp).toInt()
        val p6 = (6 * dp).toInt()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.card_background)
            setPadding(p16, p16, p16, p16)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).also { it.setMargins(0, p8, 0, 0) }
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

        // 第四行：全部打卡时间（超过2次才显示）
        if (day.tapCount > 2) {
            card.addView(TextView(this).apply {
                text = "所有记录：" + day.records.joinToString("  |  ") { it.timeDisplay }
                textSize = 11f
                setTextColor(Color.parseColor("#AAAAAA"))
                setPadding(0, (4 * dp).toInt(), 0, 0)
            })
        }

        card.setOnClickListener {
            startActivity(Intent(this, ScreenshotsActivity::class.java).apply {
                putExtra("date_key", day.dateKey)
                putExtra("date_display", "${day.dateDisplay} ${day.weekDay}")
            })
        }

        return card
    }
}
