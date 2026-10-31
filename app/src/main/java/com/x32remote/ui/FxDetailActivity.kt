package com.x32remote.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.x32remote.core.AppClient
import com.x32remote.core.FxAlgoSpec
import com.x32remote.core.FxCalibration
import com.x32remote.core.FxParamSpec
import com.x32remote.core.X32
import com.x32remote.databinding.ActivityFxDetailBinding
import java.util.Locale

/**
 * 效果槽详情页：算法选择 + 开关/混合比 + FX 返回 + 算法参数。
 *
 * 参数行的数量与量程随算法变（HALL 12 个、GEQ2 多达 64 个），无法写死在 XML，
 * 一律由 [FxParamSpec] 动态生成 —— 数值走 SeekBar（映射 lo..hi），枚举走 Spinner。
 *
 * 参数键约定：OSC 原始段（两位补零 "01".."NN"），与 X32Client 收到回包时的存法一致。
 */
class FxDetailActivity : AppCompatActivity() {

    private lateinit var b: ActivityFxDetailBinding
    private val client = AppClient.client

    private var slot = 1
    private var algoNames: List<String> = emptyList()
    private var suppressSpinner = false

    /** 拖动节流：40ms 一次，避免手指一划打出上百个 UDP 包 */
    private var lastSendAt = 0L

    /** 手指正按在滑杆上 —— 期间拒绝外部刷新重建行 */
    private var dragging = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityFxDetailBinding.inflate(layoutInflater)
        setContentView(b.root)

        slot = intent.getIntExtra(EXTRA_SLOT, 1).coerceIn(1, 8)

        b.btnBack.setOnClickListener { finish() }
        b.btnReload.setOnClickListener {
            client.queryFxDetail(slot)
            toast("已重新读取 FX $slot")
            render()
        }

        setupAlgoSpinner()
        render()
    }

    override fun onResume() {
        super.onResume()
        // 真台面板被别人动过时，靠这个出口跟上（与 MainActivity 的 listener 同一节奏）
        client.extraDirtySink = { if (!dragging) render() }
        client.queryFxDetail(slot)
        render()
    }

    override fun onPause() {
        client.extraDirtySink = null
        super.onPause()
    }

    // ============================================================ 算法选择

    private fun setupAlgoSpinner() {
        val lib = FxCalibration.lib
        if (lib == null) {
            b.spAlgo.isEnabled = false
            b.tvAlgoInfo.text = "效果库未加载：${FxCalibration.initFailed ?: "未知错误"}"
            return
        }

        // Android Spinner 没有 optgroup，把分类并进条目文本："混响 · 大厅混响 (HALL)"
        val entries = ArrayList<String>()
        val names = ArrayList<String>()
        entries.add("（空槽 — 不装效果）")
        names.add("")
        for ((cat, catLabel, catNames) in lib.algosByCategory()) {
            for (n in catNames) {
                val a = lib.algo(n) ?: continue
                names.add(n)
                entries.add(
                    buildString {
                        append(catLabel)
                        append(" · ")
                        append(a.label)
                        append(" (")
                        append(n)
                        append(")")
                        if (a.heavy) append(" ·仅FX1-4")
                    }
                )
            }
        }
        algoNames = names

        b.spAlgo.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, entries)
        b.spAlgo.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long
            ) {
                if (suppressSpinner) return
                val target = algoNames.getOrElse(pos) { "" }
                val cur = client.state.fxSlot(slot).algoType
                if (target == cur) return
                val lib2 = FxCalibration.lib
                if (target.isNotEmpty() && lib2?.slotAllows(slot, target) != true) {
                    toast("该算法只能装在 FX 1-4（真台 DSP 限制）")
                    selectAlgoInSpinner(cur)
                    return
                }
                client.setFxType(slot, target)
                toast(if (target.isEmpty()) "已清空 FX $slot" else "FX $slot → ${lib2?.algo(target)?.label ?: target}")
                render()
            }

            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
    }

    private fun selectAlgoInSpinner(algo: String) {
        val i = algoNames.indexOf(algo)
        if (i < 0) return
        suppressSpinner = true
        b.spAlgo.setSelection(i, false)
        suppressSpinner = false
    }

    // ============================================================ 渲染

    private fun render() {
        val st = client.state.fxSlot(slot)
        val lib = FxCalibration.lib
        val algo = lib?.algo(st.algoType)

        b.tvTitle.text = buildString {
            append("FX ")
            append(slot)
            if (algo != null) {
                append(" · ")
                append(algo.label)
            }
        }

        selectAlgoInSpinner(st.algoType)

        // ---- 算法信息
        b.tvAlgoInfo.text = if (algo == null) {
            if (st.algoType.isEmpty()) "空槽：选一个算法" else "未标定的算法 ${st.algoType}"
        } else {
            buildString {
                append(lib?.catLabel?.get(algo.cat) ?: algo.cat)
                append(" · ")
                append(algo.n)
                append(" 参数")
                if (algo.heavy) append(" · 仅 FX 1-4")
            }
        }

        // ---- 效果开关
        b.swOn.setOnCheckedChangeListener(null)
        b.swOn.isChecked = st.fxOn != 0
        b.swOn.setOnCheckedChangeListener { _, on ->
            client.setFxEnabled(slot, on)
        }

        // ---- FX 返回：推子 + 静音
        b.returnBox.removeAllViews()
        b.returnBox.addView(
            sliderRow(
                title = "返回电平",
                lo = 0.0, hi = 1.0,
                value = st.fader.toDouble(),
                set = { client.setFxReturnFader(slot, it.toFloat()) },
                fmt = { X32.formatDb(it.toFloat()) }
            )
        )
        b.returnBox.addView(
            toggleRow("返回静音", st.on == 0) { muted ->
                client.setFxReturnMuted(slot, muted)
            }
        )

        // ---- 参数
        b.paramBox.removeAllViews()
        if (algo == null) {
            b.tvParamHint.text = if (st.algoType.isEmpty()) "" else "未标定，按通用 0-1 处理"
            // 未标定算法：把已收到的参数按通用 0-1 列出来（保底可改）
            if (st.params.isNotEmpty()) {
                for (key in st.params.keys.sorted()) {
                    val v = st.params[key] ?: continue
                    b.paramBox.addView(
                        sliderRow(
                            title = "参数 $key",
                            lo = 0.0, hi = 1.0,
                            value = v,
                            set = { client.setFxParam(slot, key, it) },
                            fmt = { String.format(Locale.US, "%.3f", it) }
                        )
                    )
                }
            }
            return
        }

        val unset = ArrayList<String>()
        for (spec in algo.params) {
            val key = String.format(Locale.US, "%02d", spec.p)
            val raw = st.params[key]
            if (raw == null) unset.add(key)
            b.paramBox.addView(paramRow(slot, spec, raw))
        }

        b.tvParamHint.text = when {
            unset.isEmpty() -> "共 ${algo.params.size} 项"
            else -> "共 ${algo.params.size} 项 · ${unset.size} 项真台未回值（显示“未设置”）"
        }
    }

    /** 一个算法参数：枚举走 Spinner，数值走 SeekBar */
    private fun paramRow(slot: Int, spec: FxParamSpec, raw: Double?): android.view.View {
        val key = String.format(Locale.US, "%02d", spec.p)
        if (spec.isEnum) {
            return enumRow(spec.label, spec.opts, raw) { idx ->
                client.setFxParam(slot, spec.field, idx.toDouble())
            }
        }
        return sliderRow(
            title = spec.label,
            lo = spec.lo, hi = spec.hi,
            value = raw,                       // null = 真台没回值 → 落 lo 并标"未设置"
            set = { client.setFxParam(slot, spec.field, it) },
            fmt = { spec.display(it) }
        )
    }

    // ============================================================ 行控件

    /** 数值行：标题 + 当前值 + SeekBar + 量程刻度 */
    private fun sliderRow(
        title: String,
        lo: Double,
        hi: Double,
        value: Double?,                        // null = 未设置
        set: (Double) -> Unit,
        fmt: (Double) -> String
    ): android.view.View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(8))
        }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tvName = TextView(this).apply {
            text = title
            setTextColor(0xFFC7D0DC.toInt())
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val tvVal = TextView(this).apply {
            text = if (value == null) "未设置" else fmt(value)
            setTextColor(if (value == null) 0xFFF0A03C.toInt() else 0xFFE8EDF5.toInt())
            textSize = 12f
        }
        head.addView(tvName)
        head.addView(tvVal)
        box.addView(head)

        val max = 1000
        val sb = SeekBar(this).apply { this.max = max }
        val span = hi - lo
        // 未设置时滑杆落在量程下限：真台还没告诉我们值，不能骗用户一个 0 dB 之类的假数
        val cur = value ?: lo
        sb.progress = if (span <= 0) 0 else ((cur - lo) / span * max).toInt().coerceIn(0, max)

        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, progress: Int, fromUser: Boolean) {
                val v = lo + span * progress / max
                tvVal.text = fmt(v)
                tvVal.setTextColor(0xFFE8EDF5.toInt())
                if (!fromUser) return
                val now = System.currentTimeMillis()
                if (now - lastSendAt < THROTTLE_MS) return
                lastSendAt = now
                set(v)
            }

            override fun onStartTrackingTouch(s: SeekBar) {
                // 拖动期间拒绝外部刷新重建行 —— 否则真台回包一刷就把手指下的滑杆抢走
                dragging = true
            }

            override fun onStopTrackingTouch(s: SeekBar) {
                // 停手时补最后一发：节流可能吞掉了最终值
                lastSendAt = 0
                set(lo + span * s.progress / max)
                dragging = false
            }
        })
        box.addView(sb)

        // 量程刻度：左 lo 右 hi（weight 撑开，hi 靠右）
        val scale = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        scale.addView(TextView(this).apply {
            text = fmt(lo)
            setTextColor(0xFF6B7480.toInt())
            textSize = 10f
        })
        scale.addView(TextView(this).apply {
            text = fmt(hi)
            setTextColor(0xFF6B7480.toInt())
            textSize = 10f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { gravity = Gravity.END }
        })
        box.addView(scale)

        return box
    }

    /** 枚举行：标题 + 下拉（下标 = 真台 enum 索引） */
    private fun enumRow(
        title: String,
        opts: List<String>?,
        value: Double?,
        set: (Int) -> Unit
    ): android.view.View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), 0, dp(8))
        }
        box.addView(TextView(this).apply {
            text = if (value == null) "$title（未设置）" else title
            setTextColor(if (value == null) 0xFFF0A03C.toInt() else 0xFFC7D0DC.toInt())
            textSize = 12f
        })

        val items = opts ?: emptyList()
        if (items.isEmpty()) {
            box.addView(TextView(this).apply {
                text = "（标定表缺选项）"
                setTextColor(0xFF8A94A6.toInt())
                textSize = 11f
            })
            return box
        }

        val sp = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@FxDetailActivity,
                android.R.layout.simple_spinner_dropdown_item, items
            )
        }
        // Spinner 初始化就会回调一次 onItemSelected —— 用"值是否真的变了"挡掉，
        // 比布尔抑制标志可靠（回调时机在不同 API 版本上不一致）。
        // 未设置时记 -1，这样用户主动选第 0 项也能发出去。
        var lastIdx = if (value == null) -1 else (value.toInt().coerceIn(0, items.size - 1))
        sp.setSelection(if (lastIdx < 0) 0 else lastIdx, false)
        sp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long
            ) {
                if (pos == lastIdx) return
                lastIdx = pos
                set(pos)
            }

            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        box.addView(sp)
        return box
    }

    /** 开关行（FX 返回静音） */
    private fun toggleRow(title: String, checked: Boolean, set: (Boolean) -> Unit): android.view.View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(TextView(this).apply {
            text = title
            setTextColor(0xFFC7D0DC.toInt())
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        val sw = com.google.android.material.switchmaterial.SwitchMaterial(this)
        sw.isChecked = checked
        sw.setOnCheckedChangeListener { _, on -> set(on) }
        row.addView(sw)
        return row
    }

    // ============================================================ 工具

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_SLOT = "slot"
        private const val THROTTLE_MS = 40L
    }
}
