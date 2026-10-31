package com.x32remote.core

import android.os.Handler
import android.os.Looper
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

/** 供演出引擎回调的发送能力，避免与 X32Client 形成构造期循环依赖 */
interface OscSender {
    fun send(msg: OscMessage)
    fun sendAll(msgs: List<OscMessage>)

    /**
     * 带来源标记的统一写入。
     * source: manual / action / dca / sync —— 决定是否触发冲突检测与 DCA 级联。
     */
    fun setValue(kind: String, idx: Int, value: Float, source: String)
}

/**
 * OSC over UDP 客户端。
 *
 * 职责边界：
 *  - **单一 socket 收发**：发送与接收必须共用同一 socket，否则真台的回包打不回来。
 *  - **状态同步**：把回包写进 [MixerState]；100ms 节流后通知 UI，避免回包风暴下疯狂重绘。
 *  - **保活与订阅**：/xremote 每 5 秒；/subscribe 开启台面推送；/meters/1 每 500ms 续订。
 *  - **写入来源标记**：manual / action / dca / sync，驱动冲突检测与回声抑制。
 *
 * 协议细节见《共享协议规范.md》。
 */
class X32Client(val state: MixerState = MixerState()) : OscSender {

    interface Listener {
        /** 状态有变化，UI 应重绘（已做 100ms 节流） */
        fun onStateDirty()
        /** 需要提示给用户的事件（如冲突警告） */
        fun onEvent(text: String)
        /** 链路状态变化 */
        fun onLinkChange(connected: Boolean, message: String)
    }

    @Volatile var listener: Listener? = null

    @Volatile var targetIp: String = ""
        private set
    @Volatile var targetPort: Int = X32.DEFAULT_PORT
        private set
    @Volatile var connected: Boolean = false
        private set

    /** 最近一次 /meters/1 解析出的电平（dB） */
    @Volatile var meters: FloatArray = FloatArray(0)
        private set

    /** 演出进度出口，UI 可订阅（渐变与 wait 期间状态不变，靠它驱动进度条） */
    @Volatile var showProgressSink: ((ShowProgress) -> Unit)? = null

    /**
     * 第二个状态出口：listener 是单值且已被主界面占用，
     * 效果器详情页这类二级页面订阅这里（与 listener 同一节奏、同一线程）。
     */
    @Volatile var extraDirtySink: (() -> Unit)? = null

    val show: ShowEngine = ShowEngine(state, this).also { engine ->
        engine.eventSink = { text -> ui.post { listener?.onEvent(text) } }
        engine.progressSink = { p -> ui.post { showProgressSink?.invoke(p) } }
    }

    private val ui = Handler(Looper.getMainLooper())
    private val dirty = AtomicBoolean(false)
    private val forwarded = HashMap<String, Pair<Float, Long>>() // 回声抑制
    private val fwdLock = Any()

    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var rxThread: Thread? = null

    // ------------------------------------------------------------ 连接生命周期

    fun connect(ip: String, port: Int = X32.DEFAULT_PORT) {
        disconnect()
        targetIp = ip
        targetPort = port
        try {
            val s = DatagramSocket()
            s.soTimeout = 0
            socket = s
            running = true

            rxThread = Thread({ rxLoop(s) }, "x32-rx").apply {
                isDaemon = true
                start()
            }

            // 开启台面变更推送（真台面板上的操作会实时回传，是冲突检测的信息来源）
            send(OscMessage(X32.SUBSCRIBE, listOf(1)))
            requestFullState()

            connected = true
            ui.post { listener?.onLinkChange(true, "$ip:$port") }

            ui.removeCallbacks(keepaliveTask)
            ui.removeCallbacks(meterTask)
            ui.removeCallbacks(flushTask)
            ui.post(keepaliveTask)
            ui.post(meterTask)
            ui.post(flushTask)
        } catch (e: Exception) {
            running = false
            connected = false
            ui.post { listener?.onLinkChange(false, "连接失败：${e.message}") }
        }
    }

    fun disconnect() {
        running = false
        connected = false
        ui.removeCallbacks(keepaliveTask)
        ui.removeCallbacks(meterTask)
        ui.removeCallbacks(flushTask)
        show.stopAll()
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
        rxThread = null
        meters = FloatArray(0)
    }

    // ------------------------------------------------------------ 发送

    override fun send(msg: OscMessage) {
        val s = socket ?: return
        try {
            val bytes = OscCodec.encodeMessage(msg)
            s.send(DatagramPacket(bytes, bytes.size, java.net.InetAddress.getByName(targetIp), targetPort))
        } catch (_: Exception) {
        }
    }

    override fun sendAll(msgs: List<OscMessage>) {
        if (msgs.isEmpty()) return
        val s = socket ?: return
        try {
            val bytes = OscCodec.encodeBundle(msgs)
            s.send(DatagramPacket(bytes, bytes.size, java.net.InetAddress.getByName(targetIp), targetPort))
        } catch (_: Exception) {
            // Bundle 失败则退化为逐条发送
            msgs.forEach { send(it) }
        }
    }

    // ------------------------------------------------------------ 写入（带来源）

    /**
     * 统一写入入口。
     *
     * @param source manual=用户手动（触发冲突检测与 DCA 级联）/ action=卡片 / dca=级联自身 / sync=回读对齐
     */
    override fun setValue(kind: String, idx: Int, value: Float, source: String) {
        val isFader = kind.endsWith("_fader")

        if (source == "manual") {
            show.checkManualTakeover(kind, idx)
        }

        // DCA 手动移动 → 记录级联基准
        var cascadeFrom: Float? = null
        if (kind == "dca_fader" && source == "manual") {
            cascadeFrom = state.fader(kind, idx)
        }

        if (isFader) state.setFader(kind, idx, value) else state.setOn(kind, idx, if (value != 0f) 1 else 0)

        val arg: Any = if (isFader) value.coerceIn(0f, 1f) else if (value != 0f) 1 else 0
        rememberForward(kind, idx, if (isFader) value else (if (value != 0f) 1f else 0f))
        send(OscMessage(X32.addressOf(kind, idx), listOf(arg)))

        if (cascadeFrom != null) {
            show.dcaCascade(idx, cascadeFrom, value)
        }
        markDirty()
    }

    fun setFader(kind: String, idx: Int, v: Float, source: String = "manual") =
        setValue(kind, idx, v, source)

    fun setOn(kind: String, idx: Int, on: Boolean, source: String = "manual") =
        setValue(kind, idx, if (on) 1f else 0f, source)

    /** 场景调用（瞬时，无状态可对齐） */
    fun recallScene(scene: Int, source: String = "manual") {
        send(OscMessage(X32.sceneLoad(scene)))
        state.setScene(scene)
        markDirty()
    }

    /** 改名（真台会持久化） */
    fun setName(group: String, idx: Int, name: String) {
        state.setName(group, idx, name)
        send(OscMessage(X32.nameAddress(group, idx), listOf(name.trim().take(12))))
        markDirty()
    }

    /** 设置通道的 DCA 归属（位掩码，bit0=DCA1） */
    fun setChannelDcaMask(ch: Int, mask: Int) {
        state.setDcaMask(ch, mask)
        send(OscMessage(X32.channelDcaMask(ch), listOf(mask and 0xFF)))
        markDirty()
    }

    // ------------------------------------------------------------ FX 机架 (v1.6)

    /**
     * 查一个效果槽的详情: 算法/开关/混合比/槽名 + FX 返回推子静音 +
     * 按标定表个数的参数。参数个数随算法变 (HALL 12, GEQ2 多达 64) ——
     * 查不到的算法保守问前 8 个。
     */
    fun queryFxDetail(fxId: Int) {
        val lib = FxCalibration.lib
        val n = lib?.algo(state.fxSlot(fxId).algoType)?.n ?: 8
        val msgs = ArrayList<OscMessage>(n + 6)
        msgs.add(OscMessage(X32.fxType(fxId)))
        msgs.add(OscMessage(X32.fxOn(fxId)))
        msgs.add(OscMessage(X32.fxMix(fxId)))
        msgs.add(OscMessage(X32.fxName(fxId)))
        msgs.add(OscMessage(X32.fxrtnFader(fxId)))
        msgs.add(OscMessage(X32.fxrtnOn(fxId)))
        for (p in 1..n) msgs.add(OscMessage(X32.fxParam(fxId, p)))
        sendAll(msgs)
    }

    /** 切换算法 (真台枚举名, 如 "GEQ")。heavy 算法只能进 FX1-4 —— 本地先拦一道。 */
    fun setFxType(fxId: Int, algo: String) {
        val lib = FxCalibration.lib ?: return
        if (algo.isNotEmpty() && !lib.slotAllows(fxId, algo)) return
        state.setFxAlgo(fxId, algo)          // 同时清空旧参数 (换算法旧值作废)
        send(OscMessage(X32.fxType(fxId), listOf(algo)))
        queryFxDetail(fxId)                  // 参数全变, 按新算法重问
        markDirty()
    }

    fun setFxEnabled(fxId: Int, on: Boolean) {
        state.setFxEnabled(fxId, if (on) 1 else 0)
        send(OscMessage(X32.fxOn(fxId), listOf(if (on) 1 else 0)))
        markDirty()
    }

    fun setFxMix(fxId: Int, mix: Double) {
        val v = mix.coerceIn(0.0, 1.0)
        state.setFxMix(fxId, v)
        send(OscMessage(X32.fxMix(fxId), listOf(v.toFloat())))
        markDirty()
    }

    /** FX 返回推子 (效果器槽页的推子; 与卡片走 setValue 的 manual 源分开) */
    fun setFxReturnFader(fxId: Int, v: Float) {
        val f = v.coerceIn(0f, 1f)
        state.setFxFader(fxId, f)
        send(OscMessage(X32.fxrtnFader(fxId), listOf(f)))
        markDirty()
    }

    /** FX 返回静音 (true = 静音) */
    fun setFxReturnMuted(fxId: Int, muted: Boolean) {
        val on = if (muted) 0 else 1
        state.setFxReturnOn(fxId, on)
        send(OscMessage(X32.fxrtnOn(fxId), listOf(on)))
        markDirty()
    }

    /**
     * 写一个算法参数。field 走标定表解析 (算法 field / parNN / 语义别名),
     * 枚举参数线上发 int, 数值发 float —— 与真台一致。
     * 本地乐观更新: 模拟器对写入不回包, 真台靠订阅推送, 两种都不能让滑杆弹回。
     */
    fun setFxParam(fxId: Int, field: String, value: Double) {
        val lib = FxCalibration.lib ?: return
        val algo = state.fxSlot(fxId).algoType
        if (algo.isEmpty()) return
        val spec = lib.paramSpec(algo, field)
        if (spec == null) {
            // 未知算法 (真台装了本库没有的效果): 没有标定就没有量程, 按通用 0-1 写,
            // 至少保留旧版通用滑杆的能力; 越界值真台自己会钳。
            val digits = if (field.startsWith("par")) field.drop(3) else field
            val n = digits.toIntOrNull() ?: return
            if (n !in 1..64) return
            val v = (if (value.isFinite()) value else 0.0).coerceIn(0.0, 1.0)
            send(OscMessage(X32.fxParam(fxId, n), listOf(v.toFloat())))
            state.setFxParam(fxId, String.format(java.util.Locale.US, "%02d", n), v)
            markDirty()
            return
        }
        val pnum = lib.fieldToPar(algo, field) ?: return
        val v = spec.clamp(value)
        val arg: Any = if (spec.isEnum) v.roundToInt() else v.toFloat()
        send(OscMessage(X32.fxParam(fxId, pnum), listOf(arg)))
        state.setFxParam(fxId, String.format(java.util.Locale.US, "%02d", pnum), v)
        markDirty()
    }

    // ------------------------------------------------------------ 全量查询

    /** 拉取一轮全量状态（推子/静音/名称/DCA 归属），按 64 条一批用 Bundle 发送。 */
    fun requestFullState() {
        val msgs = ArrayList<OscMessage>(256)

        for (i in 1..32) {
            msgs.add(OscMessage(X32.channelFader(i)))
            msgs.add(OscMessage(X32.channelOn(i)))
        }
        for (i in 1..16) {
            msgs.add(OscMessage(X32.busFader(i)))
            msgs.add(OscMessage(X32.busOn(i)))
        }
        for (i in 1..8) {
            msgs.add(OscMessage(X32.dcaFader(i)))
            msgs.add(OscMessage(X32.dcaOn(i)))
        }
        for (i in 1..6) msgs.add(OscMessage(String.format(java.util.Locale.US, "/auxin/%02d/mix/fader", i)))
        for (i in 1..2) msgs.add(OscMessage(String.format(java.util.Locale.US, "/usb/%02d/mix/fader", i)))
        // ⚠️ FX 返回真台拼写 fxrtn (两位补零); fxret 是旧拼写只收不发
        for (i in 1..8) msgs.add(OscMessage(X32.fxrtnFader(i)))
        for (i in 1..6) msgs.add(OscMessage(String.format(java.util.Locale.US, "/mtx/%02d/mix/fader", i)))

        msgs.add(OscMessage(X32.MAIN_FADER))
        msgs.add(OscMessage(X32.MAIN_ON))

        // FX 机架 8 个槽: 算法/开关/混合比/槽名 (参数按算法个数 4~64 不等,
        // 全量问会把连接时的包量撑爆, 由效果器页按需查 —— queryFxDetail)
        for (i in 1..8) {
            msgs.add(OscMessage(X32.fxType(i)))
            msgs.add(OscMessage(X32.fxOn(i)))
            msgs.add(OscMessage(X32.fxMix(i)))
            msgs.add(OscMessage(X32.fxName(i)))
        }

        for (i in 1..32) msgs.add(OscMessage(X32.channelDcaMask(i)))
        for (i in 1..32) msgs.add(OscMessage(X32.nameAddress("ch", i)))
        for (i in 1..16) msgs.add(OscMessage(X32.nameAddress("bus", i)))
        for (i in 1..8) msgs.add(OscMessage(X32.nameAddress("dca", i)))

        msgs.chunked(64).forEach { sendAll(it) }
    }

    // ------------------------------------------------------------ 接收

    private fun rxLoop(s: DatagramSocket) {
        val buf = ByteArray(16384)
        while (running) {
            try {
                val pkt = DatagramPacket(buf, buf.size)
                s.receive(pkt)
                val data = pkt.data.copyOfRange(pkt.offset, pkt.offset + pkt.length)
                for (m in OscCodec.decode(data)) {
                    handleIncoming(m)
                }
                markDirty()
            } catch (e: Exception) {
                if (!running) break
                // socket 超时/瞬时错误：继续等待
            }
        }
    }

    private fun handleIncoming(m: OscMessage) {
        val addr = m.address
        val raw = m.args.firstOrNull()

        // FX 返回 (/fxrtn/NN/mix/fader|on|config/name): 喂两个状态轨 ——
        //   1) fxret 组 cell: 卡片 fade_all 的渐变起点与冲突检测读它
        //   2) FxSlotState:   效果器槽页的推子/静音显示
        // ⚠️ fxrtn 是真台拼写; fxret 是旧拼写 (旧模拟器/旧固件), 都收。
        //    放在通用名称处理器**之前**: /fxrtn/NN/config/name 也归这里管。
        if (addr.startsWith("/fxrtn/") || addr.startsWith("/fxret/")) {
            val p = addr.split('/')
            val i = p.getOrNull(2)?.toIntOrNull() ?: return
            if (i !in 1..8) return
            when {
                addr.endsWith("/fader") -> {
                    val f = OscCodec.argAsFloat(raw) ?: return
                    applyRemoteFader("fxret_fader", i, f)   // 含回声抑制 + 冲突检测
                    state.setFxFader(i, f)
                }
                addr.endsWith("/on") -> {
                    val on = OscCodec.argAsInt(raw) ?: return
                    state.setOn("fxret_on", i, on)
                    state.setFxReturnOn(i, on)
                }
                addr.endsWith("/config/name") -> {
                    val s = OscCodec.argAsString(raw) ?: return
                    state.setName("fxret", i, s)
                    state.setFxName(i, s)
                }
            }
            return
        }

        // FX 机架 (/fx/N/type|on|mix|par/NN|config/name): 槽号不补零, 参数号补零。
        // v1.6 效果库全量标定后, 算法/开关/混合比/参数全部可读可写。
        // ⚠️ 必须排除 /fxrtn/ (上面已处理) —— "/fx/".hasPrefix 不会误中 fxrtn,
        //    但 /fx/N/config/name 会被通用名称处理器漏掉 (NAME_COUNTS 无 fx),
        //    所以这段放在名称处理器之前, 自己吃掉 config/name。
        if (addr.startsWith("/fx/")) {
            val p = addr.split('/')
            val i = p.getOrNull(1)?.toIntOrNull() ?: return
            if (i !in 1..8) return
            when {
                addr.endsWith("/type") -> {
                    // 空串 = 空槽; setFxAlgo 会清空参数 (换算法旧值作废)
                    val s = OscCodec.argAsString(raw) ?: return
                    state.setFxAlgo(i, s)
                }
                addr.endsWith("/on") -> {
                    val n = OscCodec.argAsInt(raw) ?: return
                    state.setFxEnabled(i, n)
                }
                addr.endsWith("/mix") -> {
                    val n = OscCodec.argAsFloat(raw) ?: return
                    state.setFxMix(i, n.toDouble())
                }
                addr.endsWith("/config/name") -> {
                    val s = OscCodec.argAsString(raw) ?: return
                    state.setFxName(i, s)
                }
                p.size >= 4 && p[2] == "par" -> {
                    val n = OscCodec.argAsFloat(raw) ?: return
                    state.setFxParam(i, p[3], n.toDouble())   // 键 = OSC 原始段 "01".."NN"
                }
            }
            return
        }

        // 名称
        if (addr.endsWith("/config/name")) {
            val parsed = parseNameAddress(addr) ?: return
            val s = OscCodec.argAsString(raw) ?: return
            state.setName(parsed.first, parsed.second, s)
            return
        }

        // DCA 归属位掩码
        if (addr.startsWith("/ch/") && addr.endsWith("/mix/dca")) {
            val ch = addr.split('/').getOrNull(2)?.toIntOrNull() ?: return
            val mask = OscCodec.argAsInt(raw) ?: return
            state.setDcaMask(ch, mask)
            return
        }

        // 场景回读：/scene/02 之类，或 /scene 本身
        if (addr == "/scene" || addr.startsWith("/scene/")) {
            val n = OscCodec.argAsInt(raw)
            if (n != null && n > 0) state.setScene(n)
            return
        }

        // 电平表
        if (addr == X32.METERS) {
            val blob = raw as? ByteArray ?: return
            meters = X32.meterFloats(blob)
            return
        }

        // 推子 / 静音
        val target = parseFaderOn(addr) ?: return
        val (kind, idx) = target

        if (kind.endsWith("_fader")) {
            val f = OscCodec.argAsFloat(raw) ?: return
            applyRemoteFader(kind, idx, f)
        } else {
            val on = OscCodec.argAsInt(raw) ?: return
            state.setOn(kind, idx, on)
        }
    }

    /** 真台回读：先判回声抑制，再交冲突检测（仅当该路正被卡片驱动时才会判定为干预） */
    private fun applyRemoteFader(kind: String, idx: Int, f: Float) {
        val rec = synchronized(fwdLock) { forwarded["$kind:$idx"] }
        val now = System.currentTimeMillis()
        if (rec != null && now - rec.second < 2000 && abs(rec.first - f) < 0.02f) {
            // 自己刚写下去的回声，只对齐本地
            state.setFader(kind, idx, f)
            return
        }
        show.onRemoteChange(kind, idx, f)
        state.setFader(kind, idx, f)
    }

    // ------------------------------------------------------------ 地址解析

    /** 例：/ch/05/mix/fader -> ("ch_fader", 5)；/main/st/mix/on -> ("main_on", 0) */
    // internal 而非 private: JVM 单测要覆盖"三种 FX 返回拼写归一"这条真回归
    internal fun parseFaderOn(addr: String): Pair<String, Int>? {
        val p = addr.split('/')
        if (p.size < 3) return null
        val head = p[1]

        if (head == "main") {
            if (p.getOrNull(3) != "mix") return null
            return when (p.getOrNull(4)) {
                "fader" -> "main_fader" to 0
                "on" -> "main_on" to 0
                else -> null
            }
        }

        if (head == "dca") {
            val idx = p.getOrNull(2)?.toIntOrNull() ?: return null
            return when (p.getOrNull(3)) {
                "fader" -> "dca_fader" to idx
                "on" -> "dca_on" to idx
                else -> null
            }
        }

        // ⚠️ fxrtn 是真台拼写 (Maillot: /fxrtn/[01..08]/mix/fader)，
        //    fxret 是旧拼写 —— 两者归一到内部组名 fxret (卡片配置存的是 kind)
        val grp = when (head) {
            "ch", "bus", "auxin", "usb", "mtx" -> head
            else -> X32.fxReturnGroupOf(head) ?: return null
        }
        val idx = p.getOrNull(2)?.toIntOrNull() ?: return null
        if (p.getOrNull(3) != "mix") return null
        return when (p.getOrNull(4)) {
            "fader" -> "${grp}_fader" to idx
            "on" -> "${grp}_on" to idx
            else -> null
        }
    }

    private fun parseNameAddress(addr: String): Pair<String, Int>? {
        val p = addr.split('/')
        if (p.size < 3) return null
        val head = p[1]
        if (head == "main") return "main" to 0
        val idx = p.getOrNull(2)?.toIntOrNull() ?: return null
        if (head !in arrayOf("ch", "bus", "dca", "auxin", "usb", "fxret", "fxrtn", "mtx")) return null
        // fxrtn 是真台拼写, 归一到内部组名 fxret
        return (if (head == "fxrtn") "fxret" else head) to idx
    }

    // ------------------------------------------------------------ 定时任务

    private val keepaliveTask = object : Runnable {
        override fun run() {
            if (!running) return
            send(OscMessage(X32.KEEPALIVE))
            ui.postDelayed(this, 5000)
        }
    }

    private val meterTask = object : Runnable {
        override fun run() {
            if (!running) return
            send(OscMessage(X32.METERS))
            ui.postDelayed(this, 500)
        }
    }

    private val flushTask = object : Runnable {
        override fun run() {
            if (!running) return
            if (dirty.getAndSet(false)) {
                listener?.onStateDirty()
                extraDirtySink?.invoke()
            }
            ui.postDelayed(this, 100)
        }
    }

    // ------------------------------------------------------------ 内部

    private fun rememberForward(kind: String, idx: Int, value: Float) {
        synchronized(fwdLock) {
            forwarded["$kind:$idx"] = value to System.currentTimeMillis()
        }
    }

    private fun markDirty() {
        dirty.set(true)
    }
}
