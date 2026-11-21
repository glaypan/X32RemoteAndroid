package com.x32remote.core

import android.content.Context
import org.json.JSONObject

/**
 * 通道参数**登记表**（随包携带的元数据）：中文名 / 量程 / 枚举 / 页签 / OSC 地址模板。
 *
 * 数据来源是模拟器仓的 `x32sim/params.py`，由 `tools/export_param_specs.py` 生成为
 * `assets/param_specs.json`。**禁止在这里手写参数** —— 手写一份就等于多一份真相，
 * 网页能调、App 调到别的值这类 Bug 就是这么来的。
 *
 * 为什么必须随包携带：App 直连真台只走 OSC，真台没有任何元信息接口，
 * 参数叫什么、范围多少、属于哪个页签，全都只能 App 自己知道。
 */
object ParamSpecs {

    /** 一个可寻址参数 */
    data class Row(
        val kind: String,
        val field: String,
        /** OSC 地址模板；main 系列没有 %02d（只有一路） */
        val address: String,
        val label: String,
        /** float | int | bool */
        val ty: String,
        val lo: Double,
        val hi: Double,
        val step: Double,
        val dec: Int,
        val unit: String,
        val logSteps: Int,
        val logDesc: Boolean,
        val labels: List<String>?,
        val enumValues: List<Double>?,
        /** value=线上传字面值 / index=线上传下标（压缩比） */
        val enumWire: String,
        val default: Double,
        val unverified: Boolean,
        /** pre | gate | dyn | eq（服务端 spec_section 下发，三端共用） */
        val section: String
    ) {
        val isBool: Boolean get() = ty == "bool"

        /** 具体一路的 OSC 地址 */
        fun address(idx: Int): String =
            if (!address.contains('%')) address else String.format(address, idx)

        fun clamp(v: Double): Double {
            if (v.isNaN() || v.isInfinite()) return default
            return v.coerceIn(lo, hi)
        }

        /** 写值 → OSC 参数。`enumWire == "index"` 时必须换算成下标（压缩比 4.0 → 3） */
        fun wire(v: Double): Any {
            if (isBool) return if (v != 0.0) 1 else 0
            val ev = enumValues
            if (ev != null && enumWire == "index") {
                var best = 0
                var bestD = Double.MAX_VALUE
                ev.forEachIndexed { i, x ->
                    val d = kotlin.math.abs(x - v)
                    if (d < bestD) { best = i; bestD = d }
                }
                return best
            }
            if (ty == "int") return v.toInt()
            return v.toFloat()
        }

        /** 界面显示值（枚举优先显示中文/真台名，声像显示 L50/C/R50） */
        fun display(v: Double): String {
            if (isBool) return if (v != 0.0) "开" else "关"
            if (field == "pan") {
                val n = Math.round(v).toInt()
                return if (n == 0) "C" else if (n < 0) "L${-n}" else "R$n"
            }
            labels?.let { lb ->
                val i = Math.round(v).toInt()
                if (i in lb.indices) return lb[i]
            }
            val s = String.format("%.${dec}f", v)
            return if (unit.isEmpty()) s else "$s $unit"
        }
    }

    /** 一类通道的能力（由登记表反推：没有该参数就是真台没有这个功能） */
    data class Caps(
        val label: String,
        val short: String,
        val size: Int,
        val pre: Int,
        val gate: Int,
        val dyn: Int,
        val ins: Int,
        /** EQ 段数（0 = 无 EQ） */
        val eq: Int,
        val pan: Int,
        /** 发送目标数（0 = 无发送） */
        val send: Int,
        /** 是否有发送取点（只有 ch/auxin/fxret 有） */
        val tap: Int,
        val sendBase: Int,
        val sendMax: Int,
        /** OSC 前缀（真台拼写：fxret → /fxrtn；main → /main/st） */
        val osc: String
    ) {
        val hasPre get() = pre != 0
        val hasGate get() = gate != 0
        val hasDyn get() = dyn != 0
        val hasIns get() = ins != 0
        val hasPan get() = pan != 0
        val hasSend get() = send > 0
        val hasTap get() = tap != 0

        /** 本组一路的前缀（main 没有路号） */
        fun itemPrefix(idx: Int): String =
            if (osc == "/main/st") osc else "$osc/%02d".format(idx)
    }

    /** 面板页签顺序 = 通道类型顺序 */
    val groupOrder = listOf("ch", "bus", "auxin", "fxret", "mtx", "main")

    private var caps: Map<String, Caps> = emptyMap()
    private var specs: Map<String, List<Row>> = emptyMap()

    @Volatile
    private var reverse: Map<String, Triple<String, Int, String>>? = null

    @Volatile
    private var sendReverse: Map<String, String>? = null

    /** 初始化（在 Application/Activity 启动时调一次即可，重复调用无副作用） */
    @Synchronized
    fun init(context: Context) {
        if (specs.isNotEmpty()) return
        val text = context.assets.open("param_specs.json").bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        val gObj = root.getJSONObject("groups")
        val c = LinkedHashMap<String, Caps>()
        for (g in groupOrder) {
            if (!gObj.has(g)) continue
            val o = gObj.getJSONObject(g)
            c[g] = Caps(
                label = o.optString("label", g),
                short = o.optString("short", g),
                size = o.optInt("size", 0),
                pre = o.optInt("pre", 0),
                gate = o.optInt("gate", 0),
                dyn = o.optInt("dyn", 0),
                ins = o.optInt("ins", 0),
                eq = o.optInt("eq", 0),
                pan = o.optInt("pan", 0),
                send = o.optInt("send", 0),
                tap = o.optInt("tap", 0),
                sendBase = o.optInt("sendBase", 0),
                sendMax = o.optInt("sendMax", 0),
                osc = o.optString("osc", "/$g")
            )
        }
        val sObj = root.getJSONObject("specs")
        val s = LinkedHashMap<String, List<Row>>()
        for (g in groupOrder) {
            if (!sObj.has(g)) continue
            val arr = sObj.getJSONArray(g)
            val list = ArrayList<Row>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    Row(
                        kind = o.optString("k", ""),
                        field = o.optString("f", ""),
                        address = o.optString("a", ""),
                        label = o.optString("l", ""),
                        ty = o.optString("t", "float"),
                        lo = o.optDouble("lo", 0.0),
                        hi = o.optDouble("hi", 1.0),
                        step = o.optDouble("st", 0.0),
                        dec = o.optInt("dec", 2),
                        unit = o.optString("u", ""),
                        logSteps = o.optInt("lg", 0),
                        logDesc = o.optInt("ld", 0) != 0,
                        labels = o.optJSONArray("lb")?.let { a -> (0 until a.length()).map { a.getString(it) } },
                        enumValues = o.optJSONArray("en")?.let { a -> (0 until a.length()).map { a.getDouble(it) } },
                        enumWire = o.optString("ew", "value"),
                        default = o.optDouble("dv", 0.0),
                        unverified = o.optInt("un", 0) != 0,
                        section = o.optString("sec", "pre")
                    )
                )
            }
            s[g] = list
        }
        caps = c
        specs = s
        reverse = null
        sendReverse = null
    }

    fun isReady(): Boolean = specs.isNotEmpty()

    fun caps(group: String): Caps? = caps[group]
    fun rows(group: String): List<Row> = specs[group] ?: emptyList()
    fun row(group: String, field: String): Row? = rows(group).firstOrNull { it.field == field }
    fun groupLabel(group: String): String = caps[group]?.label ?: group

    /** 列表里的标签（Ch 01 / Bus 03 / Main） */
    fun itemLabel(group: String, idx: Int): String {
        val c = caps[group] ?: return "$group $idx"
        return if (group == "main") c.label else String.format("%s %02d", c.short, idx)
    }

    // ------------------------------------------------------------ 发送

    /**
     * 发送地址：`/<前缀>/<源>/mix/<目标>/level|on|type`
     *
     * 取点 (`type`) 只对奇数目标定义（真台按 1&2 成对共享）：查偶数一律落回奇数，
     * 否则同一份真相有两个入口，写偶数会静默丢一半。
     */
    fun sendAddress(group: String, src: Int, bus: Int, field: String): String? {
        val c = caps[group] ?: return null
        if (!c.hasSend || bus < 1 || bus > c.send) return null
        val target = if (field == "tap" && bus % 2 == 0) bus - 1 else bus
        val seg = if (field == "tap") "type" else field
        return if (group == "main") String.format("%s/mix/%02d/%s", c.osc, target, seg)
        else String.format("%s/%02d/mix/%02d/%s", c.osc, src, target, seg)
    }

    /** 发送值的状态键（统一走这里，别手拼） */
    fun sendKey(group: String, src: Int, bus: Int, field: String): String =
        String.format("send/%s/%02d/%02d/%s", group, src, bus, field)

    /** 一组的发送参数（不在行参数表里，单独构造） */
    fun sendFields(group: String): List<String> {
        val c = caps[group] ?: return emptyList()
        return if (c.hasTap) listOf("level", "on", "tap") else listOf("level", "on")
    }

    private fun rev(): Map<String, Triple<String, Int, String>> {
        reverse?.let { return it }
        val m = HashMap<String, Triple<String, Int, String>>()
        for (g in groupOrder) {
            val size = caps[g]?.size ?: 0
            for (r in rows(g)) {
                for (i in 1..maxOf(size, 1)) m[r.address(i)] = Triple(g, i, r.field)
            }
        }
        reverse = m
        return m
    }

    private fun sendRev(): Map<String, String> {
        sendReverse?.let { return it }
        val m = HashMap<String, String>()
        for (g in groupOrder) {
            val c = caps[g] ?: continue
            if (!c.hasSend) continue
            for (src in 1..maxOf(c.sendMax, 1)) {
                for (bus in 1..c.send) {
                    for (f in sendFields(g)) {
                        val a = sendAddress(g, src, bus, f) ?: continue
                        m[a] = sendKey(g, src, bus, f)
                    }
                }
            }
        }
        sendReverse = m
        return m
    }

    /** OSC 回读地址 → (组, 路号, 字段) */
    fun parse(address: String): Triple<String, Int, String>? = rev()[address]

    /** 发送回读地址 → 状态键 */
    fun parseSend(address: String): String? = sendRev()[address]
}
