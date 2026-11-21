package com.x32remote.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayout
import com.x32remote.core.AppClient
import com.x32remote.core.ParamSpecs
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 通道参数面板：均衡 / 噪声门 / 压缩 / 插入 / 增益 / 声像 / 发送。
 *
 * 控件**不在 XML 里、也不在代码里写死**：全部按 `assets/param_specs.json`
 * （服务端登记表导出的元数据）动态生成。原因是各组能力差得远 ——
 * 输入通道 4 段 EQ + 门 + 压缩 + 插入，Bus 是 6 段 EQ 无门，FX 返回连 EQ 都没有；
 * 写死一份 UI 就等于把"哪组有哪些参数"抄了第二遍，必然和台面漂移。
 *
 * 页签的归属（pre/gate/dyn/eq）也由服务端下发（sec 字段），三端同一份规则。
 */
class ChannelDetailActivity : AppCompatActivity() {

    private val client = AppClient.client

    private var group = "ch"
    private var idx = 1

    private lateinit var tvTitle: TextView
    private lateinit var tabs: TabLayout
    private lateinit var content: LinearLayout
    private lateinit var faderBar: SeekBar
    private lateinit var swMute: Switch
    private lateinit var tvFader: TextView

    private var tab = "pre"
    private var lastSendAt = 0L
    private var suppress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ParamSpecs.init(applicationContext)
        group = intent.getStringExtra(EXTRA_GROUP) ?: "ch"
        idx = intent.getIntExtra(EXTRA_IDX, 1)

        setContentView(buildRoot())
        buildTabs()
        render()
    }

    override fun onResume() {
        super.onResume()
        client.extraDirtySink = { if (!isFinishing) renderValues() }
        query()
    }

    override fun onPause() {
        client.extraDirtySink = null
        super.onPause()
    }

    private fun query() {
        client.queryParams(group, idx)
        if (ParamSpecs.caps(group)?.hasSend == true) client.querySends(group, idx)
    }

    // ============================================================ 骨架

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

    private fun buildRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_BG)
        }

        // 顶栏
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
        }
        val back = TextView(this).apply {
            text = "← 返回"
            setTextColor(Color.WHITE)
            setPadding(0, dp(6f), dp(12f), dp(6f))
            setOnClickListener { finish() }
        }
        tvTitle = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
        }
        val reload = TextView(this).apply {
            text = "重新读取"
            setTextColor(COLOR_ACCENT)
            setPadding(dp(12f), dp(6f), 0, dp(6f))
            setOnClickListener { query(); renderValues() }
        }
        bar.addView(back)
        bar.addView(tvTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(reload)
        root.addView(bar)

        // 推子 + 静音（任何通道类型都有）
        val faderRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), 0, dp(12f), dp(8f))
        }
        faderBar = SeekBar(this).apply {
            max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser || suppress) return
                    tvFader.text = "${(progress / 10f).roundToInt()}%"
                    sendThrottled {
                        client.setValue(faderKind(), stateIdx(), progress / 1000f, "manual")
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    client.setValue(faderKind(), stateIdx(), (sb?.progress ?: 0) / 1000f, "manual")
                }
            })
        }
        tvFader = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.END
            width = dp(46f)
        }
        swMute = Switch(this).apply {
            text = "静音"
            setTextColor(Color.WHITE)
            setOnCheckedChangeListener { _, checked ->
                if (suppress) return@setOnCheckedChangeListener
                val kind = onKind()
                client.setValue(kind, stateIdx(), if (checked) 0f else 1f, "manual")
            }
        }
        faderRow.addView(faderBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        faderRow.addView(tvFader)
        faderRow.addView(swMute)
        root.addView(faderRow)

        tabs = TabLayout(this).apply {
            tabMode = TabLayout.MODE_SCROLLABLE
            setSelectedTabIndicatorColor(COLOR_ACCENT)
            setTabTextColors(Color.parseColor("#9AA0A6"), Color.WHITE)
            setBackgroundColor(COLOR_BG)
        }
        root.addView(tabs)

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(8f), dp(12f), dp(24f))
        }
        val sc = ScrollView(this)
        sc.addView(content)
        root.addView(sc, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        return root
    }

    private fun faderKind(): String = if (group == "main") "main_fader" else "${group}_fader"
    private fun onKind(): String = if (group == "main") "main_on" else "${group}_on"

    /** 主输出在 MixerState 里固定用 idx=0（历史约定），参数表用 1 */
    private fun stateIdx(): Int = if (group == "main") 0 else idx

    // ============================================================ 页签

    private fun tabsFor(): List<Pair<String, String>> {
        val c = ParamSpecs.caps(group) ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        if (c.hasPre || c.hasIns || c.hasPan) out.add("pre" to "前置")
        if (c.hasGate) out.add("gate" to "门")
        if (c.hasDyn) out.add("dyn" to "动态")
        if (c.eq > 0) out.add("eq" to "均衡")
        if (c.hasSend) out.add("send" to "发送")
        return out
    }

    private fun buildTabs() {
        val list = tabsFor()
        tabs.removeAllTabs()
        list.forEach { (key, label) ->
            val t = tabs.newTab().setText(label).setTag(key)
            tabs.addTab(t)
        }
        if (list.isNotEmpty()) tab = list.first().first
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(t: TabLayout.Tab) {
                tab = (t.tag as? String) ?: "pre"
                render()
            }
            override fun onTabUnselected(t: TabLayout.Tab) {}
            override fun onTabReselected(t: TabLayout.Tab) {}
        })
    }

    // ============================================================ 渲染

    private fun render() {
        val c = ParamSpecs.caps(group) ?: run {
            tvTitle.text = "参数表未加载"
            return
        }
        tvTitle.text = ParamSpecs.itemLabel(group, idx)
        renderValues()
        content.removeAllViews()
        if (tab == "send") renderSends(c) else renderParams(c)
    }

    /** 只刷新数值（真台回读时 100ms 一次，不重建控件） */
    private fun renderValues() {
        val fader = client.state.fader(faderKind(), stateIdx())
        suppress = true
        faderBar.progress = (fader * 1000).roundToInt()
        tvFader.text = "${(fader * 100).roundToInt()}%"
        swMute.isChecked = client.state.on(onKind(), stateIdx()) == 0
        suppress = false
        // 参数行的数值标签
        for (i in 0 until content.childCount) {
            val row = content.getChildAt(i).tag as? RowTag ?: continue
            row.value.text = row.spec.display(client.paramValue(group, idx, row.spec))
            (row.control as? SeekBar)?.let { sb ->
                if (!sb.isPressed) {
                    suppress = true
                    sb.progress = posOf(row.spec, client.paramValue(group, idx, row.spec))
                    suppress = false
                }
            }
        }
    }

    private class RowTag(val spec: ParamSpecs.Row, val value: TextView, val control: View)

    private fun renderParams(c: ParamSpecs.Caps) {
        val rows = ParamSpecs.rows(group).filter { it.section == tab }
        if (rows.isEmpty()) {
            content.addView(hint("本通道类型没有这项处理"))
            return
        }
        if (tab != "eq") {
            rows.forEach { content.addView(paramRow(it)) }
            return
        }
        // EQ 按段分组（ch/auxin 4 段, bus/mtx/main 6 段）
        for (band in 1..maxOf(c.eq, 1)) {
            val bandRows = rows.filter { it.field.startsWith("eq${band}_") }
            if (bandRows.isEmpty()) continue
            content.addView(sectionTitle("EQ $band"))
            bandRows.forEach { content.addView(paramRow(it)) }
        }
    }

    private fun sectionTitle(t: String): View = TextView(this).apply {
        text = t
        setTextColor(COLOR_ACCENT)
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(10f), 0, dp(2f))
    }

    private fun hint(t: String): View = TextView(this).apply {
        text = t
        setTextColor(Color.parseColor("#9AA0A6"))
        textSize = 13f
        setPadding(0, dp(16f), 0, 0)
    }

    private fun paramRow(spec: ParamSpecs.Row): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6f), 0, dp(2f))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(this).apply {
            text = spec.label + if (spec.unverified) " ⚠" else ""
            setTextColor(Color.parseColor("#C7CBD1"))
            textSize = 13f
        }
        val value = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.END
            text = spec.display(client.paramValue(group, idx, spec))
        }
        head.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(value)
        wrap.addView(head)

        val control: View = when {
            spec.isBool -> Switch(this).apply {
                isChecked = client.paramValue(group, idx, spec) != 0.0
                setOnCheckedChangeListener { _, checked ->
                    if (suppress) return@setOnCheckedChangeListener
                    client.setParam(group, idx, spec, if (checked) 1.0 else 0.0)
                    value.text = spec.display(if (checked) 1.0 else 0.0)
                }
            }
            spec.enumValues != null && spec.enumValues.size <= 8 -> enumSpinner(spec, value)
            spec.logSteps > 1 || spec.hi - spec.lo > 200 -> seekBar(spec, value, log = true)
            else -> seekBar(spec, value, log = false)
        }
        wrap.addView(control)
        wrap.tag = RowTag(spec, value, control)
        return wrap
    }

    /** 枚举：Spinner 显示中文/真台名，写值走下标语义由 Row.wire 处理 */
    private fun enumSpinner(spec: ParamSpecs.Row, value: TextView): Spinner {
        val items = spec.enumValues ?: emptyList()
        val sp = Spinner(this)
        val names = items.map { spec.display(it) }
        sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        val cur = client.paramValue(group, idx, spec)
        sp.setSelection(items.indices.minByOrNull { abs(items[it] - cur) } ?: 0, false)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (suppress) return
                val target = items.getOrNull(position) ?: return
                client.setParam(group, idx, spec, target)
                value.text = spec.display(target)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        return sp
    }

    /** 数值滑杆：0..1000 的位置 ↔ 真实值（对数参数按 log 映射，否则低频挤在一起） */
    private fun seekBar(spec: ParamSpecs.Row, value: TextView, log: Boolean): SeekBar {
        val sb = SeekBar(this)
        sb.max = 1000
        sb.progress = posOf(spec, client.paramValue(group, idx, spec))
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(p0: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || suppress) return
                val v = valueOf(spec, progress, log)
                value.text = spec.display(v)
                sendThrottled { client.setParam(group, idx, spec, v) }
            }

            override fun onStartTrackingTouch(p0: SeekBar?) {}
            override fun onStopTrackingTouch(p0: SeekBar?) {
                client.setParam(group, idx, spec, valueOf(spec, p0?.progress ?: 0, log))
            }
        })
        return sb
    }

    private fun posOf(spec: ParamSpecs.Row, v: Double): Int {
        val lo = spec.lo
        val hi = spec.hi
        if (hi <= lo) return 0
        val x = v.coerceIn(lo, hi)
        return if (spec.logSteps > 1 || hi - lo > 200) {
            val l = ln(maxOf(x, 1e-6) / maxOf(lo, 1e-6)) / ln(maxOf(hi, 1e-6) / maxOf(lo, 1e-6))
            (l.coerceIn(0.0, 1.0) * 1000).roundToInt()
        } else {
            (((x - lo) / (hi - lo)) * 1000).roundToInt()
        }
    }

    private fun valueOf(spec: ParamSpecs.Row, pos: Int, log: Boolean): Double {
        val p = pos.coerceIn(0, 1000) / 1000.0
        if (log) {
            val lo = maxOf(spec.lo, 1e-6)
            val hi = maxOf(spec.hi, lo * 1.001)
            return lo * (hi / lo).pow(p)
        }
        return spec.lo + (spec.hi - spec.lo) * p
    }

    // ============================================================ 发送

    private fun renderSends(c: ParamSpecs.Caps) {
        content.addView(hint("发送到 ${if (group == "bus" || group == "main") "Matrix" else "Bus"} 1–${c.send}"))
        for (bus in 1..c.send) {
            val wrap = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8f), 0, dp(2f))
            }
            val head = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val on = (client.sendValue(group, idx, bus, "on") ?: 0.0) != 0.0
            val name = TextView(this).apply {
                text = String.format("%s %02d", if (group == "bus" || group == "main") "Matrix" else "Bus", bus)
                setTextColor(if (on) Color.WHITE else Color.parseColor("#9AA0A6"))
                textSize = 13f
            }
            val swOn = Switch(this).apply {
                text = "ON"
                setTextColor(Color.WHITE)
                isChecked = on
                setOnCheckedChangeListener { _, checked ->
                    if (suppress) return@setOnCheckedChangeListener
                    client.setSend(group, idx, bus, "on", if (checked) 1.0 else 0.0)
                }
            }
            head.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            // 取点只有奇数目标定义（真台成对共享）—— 偶数目标不给开关
            if (c.hasTap && bus % 2 == 1) {
                val tapNames = listOf("<-EQ", "EQ->", "PRE", "POST", "GRP")
                val sp = Spinner(this)
                sp.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, tapNames)
                sp.setSelection(((client.sendValue(group, idx, bus, "tap") ?: 3.0).toInt()).coerceIn(0, 4), false)
                sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                        if (suppress) return
                        client.setSend(group, idx, bus, "tap", position.toDouble())
                    }
                    override fun onNothingSelected(p: AdapterView<*>?) {}
                }
                val lp = LinearLayout.LayoutParams(dp(96f), ViewGroup.LayoutParams.WRAP_CONTENT)
                head.addView(sp, lp)
            }
            head.addView(swOn)
            wrap.addView(head)

            val sb = SeekBar(this).apply {
                max = 1000
                progress = (((client.sendValue(group, idx, bus, "level") ?: 0.0) * 1000).roundToInt())
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(p0: SeekBar?, progress: Int, fromUser: Boolean) {
                        if (!fromUser || suppress) return
                        sendThrottled {
                            client.setSend(group, idx, bus, "level", progress / 1000.0)
                        }
                    }

                    override fun onStartTrackingTouch(p0: SeekBar?) {}
                    override fun onStopTrackingTouch(p0: SeekBar?) {
                        client.setSend(group, idx, bus, "level", (p0?.progress ?: 0) / 1000.0)
                    }
                })
            }
            wrap.addView(sb)
            content.addView(wrap)
        }
    }

    /** 拖动节流：40ms 一次，避免手指一划打出上百个 UDP 包 */
    private fun sendThrottled(block: () -> Unit) {
        val now = System.currentTimeMillis()
        if (now - lastSendAt < 40) return
        lastSendAt = now
        block()
    }

    companion object {
        private const val EXTRA_GROUP = "group"
        private const val EXTRA_IDX = "idx"

        private val COLOR_BG = Color.parseColor("#14161A")
        private val COLOR_ACCENT = Color.parseColor("#4C9AFF")

        fun intent(context: Context, group: String, idx: Int): Intent =
            Intent(context, ChannelDetailActivity::class.java)
                .putExtra(EXTRA_GROUP, group)
                .putExtra(EXTRA_IDX, idx)
    }
}
