package com.x32remote.core

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 一条卡片动作。所有字段都带默认值，实际生效的字段由 kind 决定（见 [ShowEngine.cleanAction]）。
 * `at >= 0` 表示时间轴偏移（秒），该动作与其它动作并行。
 */
data class CardAction(
    val kind: String,
    val chs: List<Int> = emptyList(),
    val buses: List<Int> = emptyList(),
    val dcas: List<Int> = emptyList(),
    val scene: Int = 1,
    val to: Float = 0.8f,
    val frm: Float = 0f,
    val oddTo: Float = 0.8f,
    val evenTo: Float = 0f,
    val duration: Float = 3f,
    val curve: String = "linear",
    val deltaDb: Float = 3f,
    val group: String = "ch",
    val at: Float = -1f,
    val singleCh: Int = 1,
    val singleBus: Int = 1
)

/** 演出卡片 */
data class ShowCard(
    val id: String,
    val name: String,
    val desc: String = "",
    val actions: List<CardAction> = emptyList(),
    val pinned: Boolean = false,
    /** 链式接续：本卡片结束后自动启动的下一张卡片 id */
    val next: String? = null
)

data class ShowProgress(
    val runningId: String? = null,
    val name: String = "",
    val progress: Float = 0f,
    val step: String = ""
) {
    val isRunning: Boolean get() = runningId != null
}

/**
 * 演出卡片引擎。
 *
 * 与网页端逐项对齐：
 *  - 12 种动作白名单 + 参数钳制
 *  - 4 条曲线，时间轴调度（50ms tick，`at` 偏移并行）
 *  - 渐变起点在**触发瞬间**取当前值
 *  - `next` 链式接续（防环，最大深度 16）
 *  - **冲突策略**：手动干预只停止被干预的那一路，其余动作与通道继续执行
 */
class ShowEngine(
    private val state: MixerState,
    private val sender: OscSender,
    /**
     * 单调毫秒时钟。默认用 Android 的 `SystemClock.elapsedRealtime()`；
     * 单测里 `android.os.SystemClock` 是抛异常的空桩，必须注入纯 JVM 实现。
     */
    private val clockMs: () -> Long = { SystemClock.elapsedRealtime() }
) {

    companion object {
        val ACTION_KINDS = listOf(
            "mute", "mute_bus", "scene", "unmute_all", "wait",
            "fade_all", "fade_odd_even", "fade_ch", "fade_bus",
            "fade_from", "fade_from_bus", "rel_db"
        )

        private const val TICK_MS = 50L
        private const val MAX_CHAIN = 16
        private const val FADE_DEFAULT_MIN = 0.5f

        /** 卡片链接续之间的喘息（与服务端 time.sleep(0.4) 一致） */
        private const val CHAIN_GAP_MS = 400L
    }

    /** 事件出口（冲突警告等），由宿主设置 */
    var eventSink: ((String) -> Unit)? = null

    /** 进度出口，由宿主设置 */
    var progressSink: ((ShowProgress) -> Unit)? = null

    // 懒加载：纯逻辑单测（JVM）下不会触碰 Android API
    private val ui by lazy { Handler(Looper.getMainLooper()) }
    private val lock = Any()

    @Volatile var cards: List<ShowCard> = defaultCards()
        private set

    @Volatile var progress: ShowProgress = ShowProgress()
        private set

    /** "kind:idx" -> 正在驱动该路的卡片名 */
    private val fadeZone = HashMap<String, String>()

    /** 本轮渐变中被手动接管的通道 */
    private val interrupts = HashSet<String>()

    @Volatile private var runner: Thread? = null
    @Volatile private var stopFlag = false
    @Volatile private var stopAfterChain = false

    // ============================================================ 冲突策略

    /**
     * 判定手动干预。**只冻结被干预的那一路**，卡片其余动作继续跑。
     * 由 [X32Client] 在任何 source="manual" 的写入前调用。
     */
    fun checkManualTakeover(kind: String, idx: Int) {
        val key = key(kind, idx)
        val text: String?
        synchronized(lock) {
            val zone = fadeZone[key] ?: return
            if (!interrupts.add(key)) return
            text = "⚠️ ${label(kind, idx)} 被手动干预 — 卡片「$zone」停止驱动该通道，其余动作继续"
        }
        emit(text!!)
    }

    /** 真台侧发生变化时的入口（真台面板被人动了推子） */
    fun onRemoteChange(kind: String, idx: Int, value: Float) {
        checkManualTakeover(kind, idx)
    }

    private fun isInterrupted(kind: String, idx: Int): Boolean =
        synchronized(lock) { interrupts.contains(key(kind, idx)) }

    private fun registerFade(kind: String, idx: Int, cardName: String) {
        synchronized(lock) {
            val k = key(kind, idx)
            interrupts.remove(k)
            fadeZone[k] = cardName
        }
    }

    private fun clearFadeZone() {
        synchronized(lock) {
            fadeZone.clear()
            interrupts.clear()
        }
    }

    /** 整轮渐变的进度（0..1），供 UI 显示被驱动的通道 */
    fun isDriven(kind: String, idx: Int): Boolean =
        synchronized(lock) { fadeZone.containsKey(key(kind, idx)) && !interrupts.contains(key(kind, idx)) }

    // ============================================================ DCA 级联

    /** 手动移动 DCA → 成员按 dB 差量级联（高低端手感一致） */
    fun dcaCascade(dcaIdx: Int, fromValue: Float, toValue: Float) {
        val delta = X32.faderToDb(toValue) - X32.faderToDb(fromValue)
        for (ch in state.dcaMembers(dcaIdx)) {
            val cur = state.fader("ch_fader", ch)
            val target = X32.dbToFader(X32.faderToDb(cur) + delta)
            sender.setValue("ch_fader", ch, target, "dca")
        }
    }

    // ============================================================ 执行

    /** 启动卡片。若已有卡片在跑则先停掉。chainDepth 用于防环。 */
    fun run(cardId: String, chainDepth: Int = 0) {
        val card = cards.firstOrNull { it.id == cardId } ?: return
        if (chainDepth > MAX_CHAIN) {
            emit("⚠️ 卡片链超过 $MAX_CHAIN 层，已中止（疑似循环）")
            return
        }
        stopAll()
        stopFlag = false
        stopAfterChain = false

        val t = Thread({ runCard(card, chainDepth) }, "x32-show")
        t.isDaemon = true
        runner = t
        t.start()
    }

    fun stop() {
        stopFlag = true
    }

    fun stopAll() {
        stopFlag = true
        stopAfterChain = true
        clearFadeZone()
        updateProgress(ShowProgress())
    }

    private fun runCard(card: ShowCard, chainDepth: Int) {
        val entries = buildTimeline(card)
        if (entries.isEmpty()) {
            // 无动作卡片（如纯占位）：直接接续下一张
            finishChain(card, chainDepth)
            return
        }

        // 登记渐变区
        for (e in entries) {
            if (e.isFade) {
                for (t in e.targets) registerFade(t.kind, t.idx, card.name)
            }
        }

        val total = entries.maxOf { it.end }.coerceAtLeast(0.05f)
        val t0 = clockMs()
        val fired = HashSet<Int>()

        try {
            while (!stopFlag) {
                val elapsed = (clockMs() - t0) / 1000f

                for ((i, e) in entries.withIndex()) {
                    if (elapsed < e.start) continue

                    if (!e.isFade) {
                        if (fired.add(i)) {
                            stepNow(card, i, entries.size, e)
                            execInstant(e.action)
                        }
                        continue
                    }

                    // 渐变：起点在首次激活时取当前值
                    if (e.startValues == null) {
                        // ⚠️ fade_from / fade_from_bus 必须**先把推子置到 frm**，
                        // 再快照起点，否则渐变会从推子当前值出发、frm 形同虚设。
                        // 与服务端 _show_worker 一致（先 set_value(frm) 再读 get_value）。
                        if (e.action.kind == "fade_from" || e.action.kind == "fade_from_bus") {
                            val fk = if (e.action.kind == "fade_from") "ch_fader" else "bus_fader"
                            val idx = if (e.action.kind == "fade_from") e.action.singleCh else e.action.singleBus
                            sender.setValue(fk, idx, e.action.frm, "action")
                        }
                        e.startValues = e.targets.associate { "${it.kind}:${it.idx}" to fadeStartValue(it, e.action) }
                    }
                    stepNow(card, i, entries.size, e)

                    val span = (e.end - e.start).coerceAtLeast(0.001f)
                    val p = ((elapsed - e.start) / span).coerceIn(0f, 1f)
                    val cp = X32.applyCurve(p, e.action.curve)
                    for (tg in e.targets) {
                        if (isInterrupted(tg.kind, tg.idx)) continue
                        val sv = e.startValues?.get("${tg.kind}:${tg.idx}") ?: continue
                        val v = sv + (tg.to - sv) * cp
                        sender.setValue(tg.kind, tg.idx, v, "action")
                    }
                }

                updateProgress(
                    ShowProgress(
                        runningId = card.id,
                        name = card.name,
                        progress = (elapsed / total).coerceIn(0f, 1f),
                        step = ""
                    )
                )

                if (elapsed >= total) break
                Thread.sleep(TICK_MS)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        if (stopFlag) {
            clearFadeZone()
            updateProgress(ShowProgress())
            return
        }
        // 收工：本张卡片的渐变区清掉，再决定是否接续（接续时由下一张自己登记）
        clearFadeZone()
        finishChain(card, chainDepth)
    }

    /**
     * 卡片链接续。
     *
     * ⚠️ 不能在这里调 `run()` —— `run()` 内部会 `stopAll()`（置 stopFlag 并清 fadeZone），
     * 既会打断当前线程的收尾，也让本方法失去意义。改为**就地接续**：
     * 先给 0.4 秒喘息（与服务端 `time.sleep(0.4)` 一致），再直接跑下一张。
     */
    private fun finishChain(card: ShowCard, chainDepth: Int) {
        val nxt = card.next
        if (stopAfterChain || nxt.isNullOrEmpty() || nxt == card.id) {
            updateProgress(ShowProgress())
            return
        }
        val nextCard = cards.firstOrNull { it.id == nxt }
        if (nextCard == null) {
            updateProgress(ShowProgress())
            return
        }
        if (chainDepth + 1 > MAX_CHAIN) {
            emit("⚠️ 卡片链超过 $MAX_CHAIN 层，已中止（疑似循环）")
            updateProgress(ShowProgress())
            return
        }
        updateProgress(ShowProgress(runningId = card.id, name = card.name, progress = 1f))
        // 卡片链之间的喘息：让台面与界面来得及看清切换
        try {
            Thread.sleep(CHAIN_GAP_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            updateProgress(ShowProgress())
            return
        }
        if (stopAfterChain) {
            updateProgress(ShowProgress())
            return
        }
        runCard(nextCard, chainDepth + 1)
    }

    private fun stepNow(card: ShowCard, index: Int, total: Int, e: TimelineEntry) {
        val text = "${index + 1}/$total · ${actionLabel(e.action)}"
        val done = progress.progress.coerceIn(0f, 1f)
        updateProgress(ShowProgress(card.id, card.name, done, text))
    }

    // ============================================================ 时间轴

    private class Target(val kind: String, val idx: Int, val to: Float)

    private class TimelineEntry(val action: CardAction) {
        var start: Float = 0f
        var end: Float = 0f
        var targets: List<Target> = emptyList()
        var startValues: Map<String, Float>? = null
        val isFade: Boolean get() = targets.isNotEmpty()
    }

    private fun buildTimeline(card: ShowCard): List<TimelineEntry> {
        val out = ArrayList<TimelineEntry>(card.actions.size)
        var cursor = 0f

        for (a in card.actions) {
            val e = TimelineEntry(a)
            e.start = if (a.at >= 0f) a.at else cursor
            e.targets = fadeTargets(a)
            e.end = e.start + durationOf(a)
            if (a.at < 0f) cursor = e.end
            out.add(e)
        }
        return out
    }

    /** 渐变动作占用 time 的秒数；瞬时动作与 wait 之外的为 0 */
    private fun durationOf(a: CardAction): Float = when (a.kind) {
        "wait" -> a.duration.coerceIn(0.5f, 60f)
        "fade_all", "fade_odd_even", "fade_ch", "fade_bus",
        "fade_from", "fade_from_bus", "rel_db" -> a.duration.coerceAtLeast(FADE_DEFAULT_MIN)
        else -> 0f
    }

    /**
     * 取某个渐变目标的起始值。
     *
     * 常规情况就是推子当前值；但 `fade_from` / `fade_from_bus` 已在调用前
     * 把推子置到 `frm`，此处读回即为 `frm`（与服务端先 set 再 get 一致）。
     */
    private fun fadeStartValue(t: Target, a: CardAction): Float =
        if ((a.kind == "fade_from" && t.kind == "ch_fader" && t.idx == a.singleCh) ||
            (a.kind == "fade_from_bus" && t.kind == "bus_fader" && t.idx == a.singleBus)
        ) a.frm
        else state.fader(t.kind, t.idx)

    /** 渐变动作 → 目标列表；非渐变返回空 */
    private fun fadeTargets(a: CardAction): List<Target> = when (a.kind) {
        // 全体推子：32 通道 + 16 Bus + 8 DCA + Aux/USB/FX/Matrix + 主输出
        "fade_all" -> buildList {
            for (i in 1..32) add(Target("ch_fader", i, a.to))
            for (i in 1..16) add(Target("bus_fader", i, a.to))
            for (i in 1..8) add(Target("dca_fader", i, a.to))
            for (i in 1..6) add(Target("auxin_fader", i, a.to))
            for (i in 1..2) add(Target("usb_fader", i, a.to))
            for (i in 1..8) add(Target("fxret_fader", i, a.to))
            for (i in 1..6) add(Target("mtx_fader", i, a.to))
            add(Target("main_fader", 0, a.to))
        }

        "fade_odd_even" ->
            (1..32).map { Target("ch_fader", it, if (it % 2 == 1) a.oddTo else a.evenTo) }

        "fade_ch" -> a.chs.map { Target("ch_fader", it, a.to) }
        "fade_bus" -> a.buses.map { Target("bus_fader", it, a.to) }
        "fade_from" -> listOf(Target("ch_fader", a.singleCh, a.to))
        "fade_from_bus" -> listOf(Target("bus_fader", a.singleBus, a.to))

        // 相对 dB：执行瞬间按当前推子位置换算
        "rel_db" -> {
            val fk = when (a.group) {
                "bus" -> "bus_fader"
                "dca" -> "dca_fader"
                else -> "ch_fader"
            }
            val ids = when (a.group) {
                "bus" -> a.buses
                "dca" -> a.dcas
                else -> a.chs
            }
            ids.map { i ->
                val curDb = X32.faderToDb(state.fader(fk, i))
                Target(fk, i, X32.dbToFader((curDb + a.deltaDb).coerceIn(-90f, 10f)))
            }
        }

        else -> emptyList()
    }

    // ============================================================ 瞬时动作

    /**
     * 瞬时动作：静音 / 静音Bus / 切场景 / 全部取消静音 / wait。
     *
     * ⚠️ `wait` 是**真 no-op**：不写 OSC、不 sleep。等待效果完全由 [buildTimeline]
     * 的 cursor 偏移提供（wait 的 end 成为下一个顺序动作的 start）。
     * 与服务端 `_exec_instant` 保持一致 —— 那里根本没有 wait 分支。
     * 曾经这里写 `Thread.sleep(duration)`，会造成**双重计时**（总时长翻倍），
     * 而且 Thread.sleep 会阻塞整个引擎线程，把并行动作与正在跑的渐变一起卡停。
     */
    private fun execInstant(a: CardAction) {
        when (a.kind) {
            "mute" -> a.chs.forEach { sender.setValue("ch_on", it, 0f, "action") }
            "mute_bus" -> a.buses.forEach { sender.setValue("bus_on", it, 0f, "action") }
            "unmute_all" -> {
                // 与服务端一致：ch 32 + bus 16 + dca 8 + auxin 6 + usb 2 + fxret 8
                // + mtx 6 + main 1，共 79 路
                for (i in 1..32) sender.setValue("ch_on", i, 1f, "action")
                for (i in 1..16) sender.setValue("bus_on", i, 1f, "action")
                for (i in 1..8) sender.setValue("dca_on", i, 1f, "action")
                for (i in 1..6) sender.setValue("auxin_on", i, 1f, "action")
                for (i in 1..2) sender.setValue("usb_on", i, 1f, "action")
                for (i in 1..8) sender.setValue("fxret_on", i, 1f, "action")
                for (i in 1..6) sender.setValue("mtx_on", i, 1f, "action")
                sender.setValue("main_on", 0, 1f, "action")
            }
            "scene" -> sender.send(OscMessage(X32.sceneLoad(a.scene)))
            "wait" -> Unit  // 故意什么都不做，见上方注释
        }
    }

    // ============================================================ 动作标签

    fun actionLabel(a: CardAction): String = when (a.kind) {
        "mute" -> "静音 " + a.chs.joinToString("、") { label("ch_on", it) }
        "mute_bus" -> "静音 " + a.buses.joinToString("、") { label("bus_on", it) }
        "scene" -> "切场景 ${a.scene}"
        "unmute_all" -> "全部取消静音"
        "wait" -> String.format(Locale.US, "等待 %.0f 秒", a.duration)
        "fade_all" -> String.format(Locale.US, "全体推子 →%.0f%%", a.to * 100)
        "fade_odd_even" -> "奇偶过渡"
        "fade_ch" -> a.chs.joinToString("、") { label("ch_fader", it) } +
            String.format(Locale.US, " →%.0f%%", a.to * 100)
        "fade_bus" -> a.buses.joinToString("、") { label("bus_fader", it) } +
            String.format(Locale.US, " →%.0f%%", a.to * 100)
        "fade_from" -> String.format(
            Locale.US, "%s %.0f→%.0f%%",
            label("ch_fader", a.singleCh), a.frm * 100, a.to * 100
        )
        "fade_from_bus" -> String.format(
            Locale.US, "%s %.0f→%.0f%%",
            label("bus_fader", a.singleBus), a.frm * 100, a.to * 100
        )
        "rel_db" -> {
            val grp = a.group
            val ids = when (grp) {
                "bus" -> a.buses
                "dca" -> a.dcas
                else -> a.chs
            }
            val sign = if (a.deltaDb >= 0) "+" else "−"
            ids.joinToString("、") { label("${grp}_on", it) } +
                String.format(Locale.US, " %s%.1f dB", sign, kotlin.math.abs(a.deltaDb))
        }
        else -> a.kind
    }

    private fun label(kind: String, idx: Int): String {
        val group = X32.kindGroup(kind)
        val n = state.name(group, idx)
        val gl = X32.GROUP_LABEL[group] ?: group
        return if (n.isNotEmpty()) "$n($gl${"%02d".format(Locale.US, idx)})" else "$gl ${"%02d".format(Locale.US, idx)}"
    }

    private fun key(kind: String, idx: Int) = "$kind:$idx"

    private fun emit(text: String) {
        val sink = eventSink ?: return
        ui.post { sink(text) }
    }

    private fun updateProgress(p: ShowProgress) {
        progress = p
        val sink = progressSink ?: return
        ui.post { sink(p) }
    }

    // ============================================================ 卡片管理

    fun byId(id: String): ShowCard? = cards.firstOrNull { it.id == id }

    fun addOrUpdate(card: ShowCard) {
        val list = cards.toMutableList()
        val i = list.indexOfFirst { it.id == card.id }
        if (i >= 0) list[i] = card else list.add(card)
        cards = list
    }

    fun remove(id: String) {
        cards = cards.filterNot { it.id == id }
    }

    fun togglePin(id: String) {
        cards = cards.map { if (it.id == id) it.copy(pinned = !it.pinned) else it }
    }

    // ============================================================ 校验

    /**
     * 校验外部提交的动作，只放行白名单类型与合法数值。
     * 返回 null 表示该动作非法，应被丢弃。
     */
    fun cleanAction(o: JSONObject): CardAction? {
        val kind = o.optString("kind")
        if (kind !in ACTION_KINDS) return null

        fun num(k: String, d: Float, lo: Float, hi: Float): Float {
            val v = o.optDouble(k, d.toDouble()).toFloat()
            return if (v.isNaN()) d else v.coerceIn(lo, hi)
        }

        fun ids(listKey: String, singleKey: String, lo: Int, hi: Int): List<Int> {
            val out = LinkedHashSet<Int>()
            val arr = o.optJSONArray(listKey)
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val d = arr.optDouble(i, Double.NaN)
                    if (d.isNaN()) continue
                    out.add(d.toInt().coerceIn(lo, hi))
                }
            } else if (o.has(singleKey)) {
                out.add(o.optInt(singleKey, lo).coerceIn(lo, hi))
            }
            return if (out.isEmpty()) listOf(lo) else out.sorted()
        }

        val curve = o.optString("curve").let { if (it in X32.CURVES) it else "linear" }
        val at = if (o.has("at")) num("at", 0f, 0f, 600f) else -1f

        return when (kind) {
            "mute" -> CardAction(kind, chs = ids("chs", "ch", 1, 32), at = at)
            "mute_bus" -> CardAction(kind, buses = ids("buses", "bus", 1, 16), at = at)
            "scene" -> CardAction(kind, scene = num("scene", 1f, 1f, 99f).toInt(), at = at)
            "unmute_all" -> CardAction(kind, at = at)
            "wait" -> CardAction(kind, duration = num("duration", 1f, 0.5f, 60f), at = at)
            "fade_all" -> CardAction(
                kind, to = num("to", 0f, 0f, 1f),
                duration = num("duration", 3f, 0.5f, 30f), curve = curve, at = at
            )
            "fade_odd_even" -> CardAction(
                kind, oddTo = num("odd_to", 0.8f, 0f, 1f), evenTo = num("even_to", 0f, 0f, 1f),
                duration = num("duration", 4f, 0.5f, 30f), curve = curve, at = at
            )
            "fade_ch" -> CardAction(
                kind, chs = ids("chs", "ch", 1, 32), to = num("to", 0.8f, 0f, 1f),
                duration = num("duration", 3f, 0.5f, 30f), curve = curve, at = at
            )
            "fade_bus" -> CardAction(
                kind, buses = ids("buses", "bus", 1, 16), to = num("to", 0.8f, 0f, 1f),
                duration = num("duration", 3f, 0.5f, 30f), curve = curve, at = at
            )
            "fade_from" -> CardAction(
                kind, singleCh = num("ch", 1f, 1f, 32f).toInt(),
                frm = num("frm", 0f, 0f, 1f), to = num("to", 0.9f, 0f, 1f),
                duration = num("duration", 5f, 0.5f, 30f), curve = curve, at = at
            )
            "fade_from_bus" -> CardAction(
                kind, singleBus = num("bus", 1f, 1f, 16f).toInt(),
                frm = num("frm", 0f, 0f, 1f), to = num("to", 0.9f, 0f, 1f),
                duration = num("duration", 5f, 0.5f, 30f), curve = curve, at = at
            )
            "rel_db" -> {
                val grp = o.optString("group").let { if (it in listOf("ch", "bus", "dca")) it else "ch" }
                val cap = when (grp) {
                    "bus" -> 16
                    "dca" -> 8
                    else -> 32
                }
                when (grp) {
                    "bus" -> CardAction(
                        kind, group = grp, buses = ids("buses", "bus", 1, cap),
                        deltaDb = num("delta_db", 3f, -30f, 30f),
                        duration = num("duration", 2f, 0.5f, 30f), curve = curve, at = at
                    )
                    "dca" -> CardAction(
                        kind, group = grp, dcas = ids("dcas", "dca", 1, cap),
                        deltaDb = num("delta_db", 3f, -30f, 30f),
                        duration = num("duration", 2f, 0.5f, 30f), curve = curve, at = at
                    )
                    else -> CardAction(
                        kind, group = grp, chs = ids("chs", "ch", 1, cap),
                        deltaDb = num("delta_db", 3f, -30f, 30f),
                        duration = num("duration", 2f, 0.5f, 30f), curve = curve, at = at
                    )
                }
            }
            else -> null
        }
    }

    // ============================================================ 导入导出

    fun cardsToJson(): JSONArray {
        val arr = JSONArray()
        for (c in cards) {
            val o = JSONObject()
            o.put("id", c.id)
            o.put("name", c.name)
            o.put("desc", c.desc)
            o.put("pinned", c.pinned)
            c.next?.let { o.put("next", it) }
            val acts = JSONArray()
            for (a in c.actions) acts.put(actionToJson(a))
            o.put("actions", acts)
            arr.put(o)
        }
        return arr
    }

    private fun actionToJson(a: CardAction): JSONObject {
        val o = JSONObject()
        o.put("kind", a.kind)
        if (a.chs.isNotEmpty()) o.put("chs", JSONArray(a.chs))
        if (a.buses.isNotEmpty()) o.put("buses", JSONArray(a.buses))
        if (a.dcas.isNotEmpty()) o.put("dcas", JSONArray(a.dcas))
        when (a.kind) {
            "scene" -> o.put("scene", a.scene)
            "wait" -> o.put("duration", a.duration.toDouble())
            "fade_all" -> {
                o.put("to", a.to.toDouble()); o.put("duration", a.duration.toDouble()); o.put("curve", a.curve)
            }
            "fade_odd_even" -> {
                o.put("odd_to", a.oddTo.toDouble()); o.put("even_to", a.evenTo.toDouble())
                o.put("duration", a.duration.toDouble()); o.put("curve", a.curve)
            }
            "fade_ch", "fade_bus" -> {
                o.put("to", a.to.toDouble()); o.put("duration", a.duration.toDouble()); o.put("curve", a.curve)
            }
            "fade_from" -> {
                o.put("ch", a.singleCh); o.put("frm", a.frm.toDouble()); o.put("to", a.to.toDouble())
                o.put("duration", a.duration.toDouble()); o.put("curve", a.curve)
            }
            "fade_from_bus" -> {
                o.put("bus", a.singleBus); o.put("frm", a.frm.toDouble()); o.put("to", a.to.toDouble())
                o.put("duration", a.duration.toDouble()); o.put("curve", a.curve)
            }
            "rel_db" -> {
                o.put("group", a.group); o.put("delta_db", a.deltaDb.toDouble())
                o.put("duration", a.duration.toDouble()); o.put("curve", a.curve)
            }
        }
        if (a.at >= 0f) o.put("at", a.at.toDouble())
        return o
    }

    /** @return 成功导入的卡片数 */
    fun importCards(arr: JSONArray): Int {
        val list = ArrayList<ShowCard>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id").ifEmpty { "card_${System.currentTimeMillis()}_$i" }
            val name = o.optString("name").ifEmpty { "未命名" }
            val acts = ArrayList<CardAction>()
            o.optJSONArray("actions")?.let { ja ->
                for (j in 0 until ja.length()) {
                    ja.optJSONObject(j)?.let { jo -> cleanAction(jo)?.let { acts.add(it) } }
                }
            }
            list.add(
                ShowCard(
                    id = id,
                    name = name,
                    desc = o.optString("desc"),
                    actions = acts,
                    pinned = o.optBoolean("pinned", false),
                    next = o.optString("next").ifEmpty { null }
                )
            )
        }
        if (list.isEmpty()) return 0
        cards = cards + list
        return list.size
    }

    // ============================================================ 出厂卡片

    private fun defaultCards(): List<ShowCard> = listOf(
        ShowCard(
            id = "dinner", name = "🍽 晚宴模式",
            desc = "静音话筒 01/02 → 音乐(15) 10 秒渐弱到 30%",
            actions = listOf(
                CardAction("mute", chs = listOf(1)),
                CardAction("mute", chs = listOf(2)),
                CardAction("fade_ch", chs = listOf(15), to = 0.30f, duration = 10f, curve = "linear")
            )
        ),
        ShowCard(
            id = "speech", name = "🎤 演讲模式",
            desc = "切场景 2 → 音乐静音 → 话筒(01) 3 秒渐强到 90%",
            actions = listOf(
                CardAction("scene", scene = 2),
                CardAction("mute", chs = listOf(15)),
                CardAction("fade_ch", chs = listOf(1), to = 0.90f, duration = 3f, curve = "linear")
            )
        ),
        ShowCard(
            id = "leave", name = "🚪 散场模式",
            desc = "全部取消静音 → 音乐(15) 5 秒渐强到 90%",
            actions = listOf(
                CardAction("unmute_all"),
                CardAction("fade_from", singleCh = 15, frm = 0f, to = 0.90f, duration = 5f, curve = "linear")
            )
        ),
        ShowCard(
            id = "alllive", name = "🎵 All Live 演示",
            desc = "前 8 路推子随节奏自动起伏（多段接力）",
            actions = listOf(
                CardAction("fade_ch", chs = (1..8).toList(), to = 0.9f, duration = 1.5f, curve = "smooth"),
                CardAction("fade_ch", chs = (1..8).toList(), to = 0.45f, duration = 1.5f, curve = "smooth"),
                CardAction("fade_ch", chs = (1..8).toList(), to = 0.85f, duration = 1.5f, curve = "smooth"),
                CardAction("fade_ch", chs = (1..8).toList(), to = 0.5f, duration = 1.5f, curve = "smooth")
            )
        ),
        ShowCard(
            id = "sync_down", name = "⏬ 全体推下 (3秒)",
            desc = "全部推子 3 秒同步拉到底，先快后慢收尾",
            actions = listOf(CardAction("fade_all", to = 0f, duration = 3f, curve = "ease_out"))
        ),
        ShowCard(
            id = "sync_up", name = "⏫ 全体拉回 (3秒)",
            desc = "全部推子 3 秒平滑拉回 0 dB，S 形缓入缓出",
            actions = listOf(CardAction("fade_all", to = 0.75f, duration = 3f, curve = "smooth"))
        ),
        ShowCard(
            id = "odd_even", name = "🎸 歌曲过渡 (基数进偶数出)",
            desc = "奇数通道渐入到 80%，偶数通道同时渐出",
            actions = listOf(CardAction("fade_odd_even", oddTo = 0.8f, evenTo = 0f, duration = 4f))
        ),
        ShowCard(
            id = "odd_even_back", name = "🎹 休息过渡 (偶数进基数出)",
            desc = "偶数通道渐入到 80%，奇数通道同时渐出",
            actions = listOf(CardAction("fade_odd_even", oddTo = 0f, evenTo = 0.8f, duration = 4f))
        )
    )
}
