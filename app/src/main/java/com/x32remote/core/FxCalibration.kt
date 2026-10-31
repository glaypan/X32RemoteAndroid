package com.x32remote.core

import android.content.Context
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 标定表全局访问点: App 启动时 init 一次, 之后零成本。解码失败 lib 为 null (界面降级)。 */
object FxCalibration {
    @Volatile
    private var cached: FxLib? = null

    @Volatile
    var initFailed: String? = null
        private set

    /** 从 assets 读标定表并解码一次。重复调用直接返回缓存。 */
    fun init(context: Context) {
        if (cached != null) return
        synchronized(this) {
            if (cached != null) return
            try {
                val text = context.assets.open("fx_calibration.json")
                    .use { it.readBytes().toString(Charsets.UTF_8) }
                cached = FxLib.parse(text)
                initFailed = null
            } catch (e: Exception) {
                initFailed = e.message ?: e.javaClass.simpleName
            }
        }
    }

    val lib: FxLib? get() = cached
}

/**
 * 一个效果参数的标定规格 (来自 X32Simulator/x32sim/fxlib.py, 随 App 分发)。
 *
 * OSC 协议本身不带这些元数据 —— 真台只回数值, 中文名/量程/枚举只能本地带
 * (assets/fx_calibration.json, 由 tools/export_fx_calibration.py 生成, 禁止手改)。
 *
 * 与 iOS `FxParamSpec`、服务端 `fx_par_clamp` 逐字段同语义。
 */
data class FxParamSpec(
    val p: Int,                 // par 号 (OSC 地址 /fx/<槽>/par/<两位补零>)
    val field: String,          // 稳定标识 (API/写入用)
    val label: String,          // 中文名 (界面显示)
    val unit: String,           // 单位 (ms/s/Hz/dB, 可能为空)
    val kind: String,           // "f" 数值 / "i" 枚举
    val lo: Double,
    val hi: Double,
    val opts: List<String>?     // kind == "i" 时: 选项中文名, 下标 = 真台 enum 索引
) {
    val isEnum: Boolean get() = kind == "i"

    /** 把任意值钳进量程 (枚举四舍五入到整数索引) —— 与服务端 fx_par_clamp 同语义。
     *  NaN/±inf 一律先落 0 再钳: 非有限值是脏数据, 语义是回中性别 extrapolate,
     *  三端 (服务端/网页/iOS/本端) 必须同一个答案。 */
    fun clamp(v: Double): Double {
        val c = min(max(if (v.isFinite()) v else 0.0, lo), hi)
        return if (isEnum) c.roundToInt().toDouble() else c
    }

    /** 显示用小数位: 步长感知, 大量程 (频率 1000–20000) 不显示小数 */
    val decimals: Int
        get() {
            val span = abs(hi - lo)
            return when {
                span >= 100 -> 0
                span >= 10 -> 1
                else -> 2
            }
        }

    /** 显示文本: 数值带单位, 枚举按下标取选项 */
    fun display(v: Double): String {
        if (isEnum && opts != null) {
            val i = v.roundToInt()
            return if (i in opts.indices) opts[i] else "$i"
        }
        return String.format(Locale.US, "%.${decimals}f", v) +
            (if (unit.isEmpty()) "" else " $unit")
    }
}

/** 一种效果算法的标定 (61 种之一) */
data class FxAlgoSpec(
    val label: String,          // 中文名
    val cat: String,            // 分类 (reverb/delay/combo/mod/pitch/eq/dyn/enh/amp)
    val heavy: Boolean,         // true = 仅 FX 1-4 (真台 DSP 限制)
    val n: Int,                 // 参数个数
    val params: List<FxParamSpec>,
    val time: Int?,             // 语义别名 → par 号 (混响时间, Tap/同步组用)
    val predelay: Int?,
    val damp: Int?,
    val tmin: Double?,          // 时间参数的量程 (Tap 换算钳制区间)
    val tmax: Double?,
    val tunit: String?          // "s" 或 "ms" (GATE/RVRS 是毫秒)
)

/** 整套效果库 (assets JSON 的解析结果) */
class FxLib private constructor(
    val version: Int,
    val source: String,
    val algos: Map<String, FxAlgoSpec>,
    val enum58: List<String>,       // FX 5-8 可装的轻量子集
    val catLabel: Map<String, String>,
    val alias: Map<String, String>  // 旧枚举名 → 新名 (PLATE→PLAT 等)
) {

    fun algo(name: String): FxAlgoSpec? = algos[name]

    /** 槽位能否装这个算法 (heavy 仅 FX 1-4) */
    fun slotAllows(slot: Int, name: String): Boolean {
        val a = algos[name] ?: return false
        if (a.heavy && slot !in 1..4) return false
        return true
    }

    fun paramSpec(algo: String, field: String): FxParamSpec? =
        algo(algo)?.params?.firstOrNull { it.field == field }

    /** field → par 号。三条路径与服务端 fx_field_par 一致:
     *  算法 field / "parNN" 直址 / 语义别名 time·predelay·damp */
    fun fieldToPar(algo: String, field: String): Int? {
        val a = algo(algo) ?: return null
        if (field.startsWith("par")) {
            val n = field.drop(3).toIntOrNull()
            if (n != null && a.params.any { it.p == n }) return n
        }
        a.params.firstOrNull { it.field == field }?.let { return it.p }
        return when (field) {
            "time" -> a.time
            "predelay" -> a.predelay
            "damp" -> a.damp
            else -> null
        }
    }

    /** 按分类分组的算法 (下拉分组渲染用; 顺序 = 分类表顺序, 组内按名字排序) */
    fun algosByCategory(): List<Triple<String, String, List<String>>> {
        val groups = HashMap<String, MutableList<String>>()
        for ((name, a) in algos) groups.getOrPut(a.cat) { mutableListOf() }.add(name)
        return catLabel.keys.mapNotNull { cat ->
            val names = groups[cat]?.sorted()
            if (names.isNullOrEmpty()) null else Triple(cat, catLabel[cat] ?: cat, names)
        }
    }

    companion object {
        /** 解析 JSON (单测直接走这里, 不起 Android 运行时) */
        fun parse(json: String): FxLib {
            val root = JSONObject(json)
            val algosObj = root.getJSONObject("algos")
            val algos = HashMap<String, FxAlgoSpec>(algosObj.length() * 2)
            for (name in algosObj.keys()) {
                val a = algosObj.getJSONObject(name)
                val paramsArr = a.getJSONArray("params")
                val params = ArrayList<FxParamSpec>(paramsArr.length())
                for (i in 0 until paramsArr.length()) {
                    val p = paramsArr.getJSONObject(i)
                    params.add(
                        FxParamSpec(
                            p = p.getInt("p"),
                            field = p.getString("field"),
                            label = p.getString("label"),
                            unit = p.optString("unit", ""),
                            kind = p.getString("kind"),
                            lo = p.getDouble("lo"),
                            hi = p.getDouble("hi"),
                            opts = if (p.isNull("opts")) null
                            else p.getJSONArray("opts").let { arr ->
                                (0 until arr.length()).map { arr.getString(it) }
                            }
                        )
                    )
                }
                algos[name] = FxAlgoSpec(
                    label = a.getString("label"),
                    cat = a.getString("cat"),
                    heavy = a.optBoolean("heavy", false),
                    n = a.getInt("n"),
                    params = params,
                    time = if (a.isNull("time")) null else a.getInt("time"),
                    predelay = if (a.isNull("predelay")) null else a.getInt("predelay"),
                    damp = if (a.isNull("damp")) null else a.getInt("damp"),
                    tmin = if (a.isNull("tmin")) null else a.getDouble("tmin"),
                    tmax = if (a.isNull("tmax")) null else a.getDouble("tmax"),
                    tunit = if (a.isNull("tunit")) null else a.getString("tunit")
                )
            }
            val catObj = root.getJSONObject("catLabel")
            val catLabel = LinkedHashMap<String, String>(catObj.length() * 2)
            for (k in catObj.keys()) catLabel[k] = catObj.getString(k)
            val aliasObj = root.getJSONObject("alias")
            val alias = HashMap<String, String>(aliasObj.length() * 2)
            for (k in aliasObj.keys()) alias[k] = aliasObj.getString(k)
            return FxLib(
                version = root.optInt("version", 1),
                source = root.optString("source", ""),
                algos = algos,
                enum58 = root.getJSONArray("enum58")
                    .let { arr -> (0 until arr.length()).map { arr.getString(it) } },
                catLabel = catLabel,
                alias = alias
            )
        }
    }
}
