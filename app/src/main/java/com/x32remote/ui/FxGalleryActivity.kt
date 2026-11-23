package com.x32remote.ui

import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.x32remote.R
import com.x32remote.core.FxGallery
import com.x32remote.databinding.ActivityFxGalleryBinding

/**
 * 效果器图鉴（v1.8）：真机界面截图 + 名称对照。
 *
 * 三端展示同一份 fxlib.json，JSON 里没有的算法这里也不会出现——
 * 采集到多少就显示多少，不给「示意图」凑数。
 */
class FxGalleryActivity : AppCompatActivity() {

    private lateinit var b: ActivityFxGalleryBinding
    private val adapter = FxGalleryAdapter()
    private val tabViews = ArrayList<TextView>()
    private var tab = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityFxGalleryBinding.inflate(layoutInflater)
        setContentView(b.root)

        FxGallery.init(this)
        b.btnBack.setOnClickListener { finish() }

        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        b.rvGallery.layoutManager = GridLayoutManager(this, if (landscape) 3 else 2)
        b.rvGallery.adapter = adapter

        FxGallery.groups.forEachIndexed { i, g ->
            val tv = TextView(this).apply {
                text = g.title
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(11), dp(7), dp(11), dp(7))
                isClickable = true
                setOnClickListener { select(i) }
            }
            tabViews += tv
            b.tabBar.addView(tv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(6) })
        }

        if (FxGallery.groups.isEmpty()) {
            b.tvGroupLabel.text = "图鉴数据未随包携带"
        } else {
            select(0)
        }
    }

    private fun select(i: Int) {
        tab = i
        val g = FxGallery.groups.getOrNull(i) ?: return
        tabViews.forEachIndexed { k, tv ->
            val on = k == i
            tv.setBackgroundResource(if (on) R.drawable.bg_chip_accent else R.drawable.bg_chip)
            tv.setTextColor(if (on) 0xFF06251C.toInt() else 0xFF9AA5B6.toInt())
            tv.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
        }
        b.tvGroupLabel.text = g.label
        b.tvCount.text = "${g.items.size} 种"
        adapter.submit(g.items)
        b.rvGallery.scrollToPosition(0)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
