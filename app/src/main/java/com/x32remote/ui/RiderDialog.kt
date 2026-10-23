package com.x32remote.ui

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.x32remote.core.RiderApi

/**
 * 自动增益 / 自动推子（Vocal Rider 式）的配置面板。
 *
 * 只做配置与显示：控制律在电脑端服务里跑（要读电平表、50ms 一拍），
 * 手机通过 HTTP `/api/rider` 改参数。所以这个面板要求电脑端服务在跑。
 */
fun showRiderDialog(activity: Activity, host: String, defaultPort: Int, onPortSaved: (Int) -> Unit) {
    val d = activity.resources.displayMetrics.density
    fun dp(v: Int) = (v * d).toInt()
    val ui = Handler(Looper.getMainLooper())

    var port = defaultPort
    var alive = true
    var lastKey = ""

    val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(4), dp(16), 0)
    }
    val tip = TextView(activity).apply {
        text = "需要电脑端服务在跑（同一 WiFi）。推子模式会真的动推子，增益模式动数字增益。"
        textSize = 12f
        setTextColor(0xFF8A94A6.toInt())
    }
    val portRow = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    val portEt = EditText(activity).apply {
        hint = "服务端口"
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(defaultPort.toString())
        textSize = 14f
    }
    val btnAdd = Button(activity).apply { text = "➕ 通道" }
    val btnAll = Button(activity).apply { text = "总开关" }
    val state = TextView(activity).apply {
        text = "连接中…"
        textSize = 12f
        setTextColor(0xFF8A94A6.toInt())
        setPadding(0, dp(6), 0, dp(4))
    }
    val list = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    val scroll = ScrollView(activity).apply {
        addView(list, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    portRow.addView(portEt, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    portRow.addView(btnAdd)
    portRow.addView(btnAll)
    root.addView(tip)
    root.addView(portRow)
    root.addView(state)
    root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)))

    fun base(): String = RiderApi.baseUrl(host, port)

    fun toast(s: String) = Toast.makeText(activity, s, Toast.LENGTH_SHORT).show()

    // 先声明后赋值：Kotlin 的局部函数必须先定义再调用，而 post() 需要回调它
    var refresh: () -> Unit = {}

    fun post(body: org.json.JSONObject) {
        Thread {
            try {
                RiderApi.post(base(), body)
                refresh()
            } catch (e: Exception) {
                ui.post { state.text = "写入失败：${e.message}" }
            }
        }.start()
    }

    // ---- 单通道一行 ----
    class Row(val ch: Int) {
        lateinit var box: LinearLayout
        lateinit var cb: CheckBox
        lateinit var mode: TextView
        lateinit var speed: TextView
        lateinit var target: EditText
        lateinit var live: TextView
        var suppress = false
    }

    val rows = LinkedHashMap<Int, Row>()

    fun chip(ctx: Context) = TextView(ctx).apply {
        textSize = 12f
        setTextColor(0xFFC7D0DC.toInt())
        setBackgroundResource(com.x32remote.R.drawable.bg_chip)
        setPadding(dp(10), dp(6), dp(10), dp(6))
        isClickable = true
    }

    fun buildRow(c: RiderApi.Chan) {
        val r = Row(c.ch)
        r.box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(8))
        }
        val line1 = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.cb = CheckBox(activity).apply {
            text = "CH%02d".format(c.ch)
            isChecked = c.on
            setTextColor(0xFFE8EDF5.toInt())
            setOnCheckedChangeListener { _, v ->
                if (!r.suppress) post(RiderApi.onBody(c.ch, v))
            }
        }
        val del = chip(activity).apply {
            text = "删除"
            setOnClickListener { post(RiderApi.removeBody(c.ch)) }
        }
        line1.addView(r.cb, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        line1.addView(del)

        val line2 = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.mode = chip(activity).apply {
            text = if (c.mode == "gain") "增益" else "推子"
            setOnClickListener {
                val next = if ((it as TextView).text == "推子") "gain" else "fader"
                post(RiderApi.modeBody(c.ch, next))
            }
        }
        r.speed = chip(activity).apply {
            text = when (c.speed) { "slow" -> "慢"; "fast" -> "快"; else -> "中" }
            setOnClickListener {
                val cur = (it as TextView).text.toString()
                val next = when (cur) { "慢" -> "medium"; "中" -> "fast"; else -> "slow" }
                post(RiderApi.speedBody(c.ch, next))
            }
        }
        r.target = EditText(activity).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            setText(c.target.toString())
            textSize = 13f
            isEnabled = !c.curve
            if (c.curve) hint = "曲线控制中"
            setOnEditorActionListener { _, _, _ ->
                text.toString().toDoubleOrNull()?.let { post(RiderApi.targetBody(c.ch, it)) }
                true
            }
            setOnFocusChangeListener { _, has ->
                if (!has) text.toString().toDoubleOrNull()?.let { post(RiderApi.targetBody(c.ch, it)) }
            }
        }
        val unit = TextView(activity).apply { text = "dBFS"; textSize = 12f; setTextColor(0xFF8A94A6.toInt()) }
        line2.addView(r.mode)
        line2.addView(r.speed)
        line2.addView(r.target, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        line2.addView(unit)

        r.live = TextView(activity).apply {
            textSize = 12f
            setTextColor(0xFF8A94A6.toInt())
        }
        r.box.addView(line1)
        r.box.addView(line2)
        r.box.addView(r.live)
        list.addView(r.box)
        rows[c.ch] = r
    }

    fun render(s: RiderApi.Snapshot) {
        val key = s.channels.joinToString(",") { "${it.ch}:${it.mode}:${it.speed}:${it.curve}" }
        if (key != lastKey) {
            lastKey = key
            list.removeAllViews()
            rows.clear()
            s.channels.forEach { buildRow(it) }
            if (s.channels.isEmpty()) {
                list.addView(TextView(activity).apply {
                    text = "还没有通道。点右上「➕ 通道」添加（1-32）。"
                    textSize = 13f
                    setTextColor(0xFF8A94A6.toInt())
                    setPadding(0, dp(10), 0, 0)
                })
            }
        }
        s.channels.forEach { c ->
            val r = rows[c.ch] ?: return@forEach
            r.suppress = true
            r.cb.isChecked = c.on
            r.suppress = false
            r.mode.text = if (c.mode == "gain") "增益" else "推子"
            r.speed.text = when (c.speed) { "slow" -> "慢"; "fast" -> "快"; else -> "中" }
            if (!r.target.hasFocus()) r.target.setText(c.target.toString())
            val lv = c.level
            r.live.text = if (lv == null) {
                "无信号（冻结）"
            } else {
                val app = c.applied?.let { "%+.1f".format(it) } ?: "—"
                "电平 %.1f dBFS · 修正 %s dB · 目标 %.1f%s%s".format(
                    lv, app, c.targetNow,
                    if (c.curve) "（曲线）" else "",
                    if (c.paused) " · 人工接管中" else ""
                )
            }
        }
        state.text = if (s.enabled) "服务已连接 · ${s.channels.count { it.on }} 路启用"
                     else "总开关已关"
        state.setTextColor(0xFF8A94A6.toInt())
    }

    refresh = {
        Thread {
            try {
                val s = RiderApi.fetch(base())
                ui.post { if (alive) render(s) }
            } catch (e: Exception) {
                ui.post { if (alive) { state.text = "连不上 ${host}:${port}（${e.message}）"; state.setTextColor(0xFFFF6B6B.toInt()) } }
            }
        }.start()
    }

    btnAdd.setOnClickListener {
        val et = EditText(activity).apply { hint = "通道 1-32"; inputType = InputType.TYPE_CLASS_NUMBER }
        AlertDialog.Builder(activity)
            .setTitle("添加通道")
            .setView(et)
            .setPositiveButton("添加") { _, _ ->
                val n = et.text.toString().toIntOrNull()
                if (n == null || n !in 1..32) toast("通道要 1-32")
                else post(org.json.JSONObject().put("channel", n).put("on", true))
            }
            .setNegativeButton("取消", null)
            .show()
    }
    btnAll.setOnClickListener {
        post(org.json.JSONObject().put("enabled", true))
    }
    portEt.setOnFocusChangeListener { _, has ->
        if (!has) {
            portEt.text.toString().toIntOrNull()?.let {
                port = it
                onPortSaved(it)
                lastKey = ""
                refresh()
            }
        }
    }

    val dialog = AlertDialog.Builder(activity)
        .setTitle("自动增益 / 自动推子")
        .setView(root)
        .setNegativeButton("关闭", null)
        .create()
    dialog.setOnDismissListener { alive = false }
    dialog.show()
    refresh()

    // 轮询：0.8s 一次，只在弹窗打开时
    val tick = object : Runnable {
        override fun run() {
            if (!alive) return
            refresh()
            ui.postDelayed(this, 800)
        }
    }
    ui.postDelayed(tick, 800)
}
