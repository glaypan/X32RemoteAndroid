package com.x32remote.core

import android.os.Handler
import android.os.Looper
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

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
        for (i in 1..8) msgs.add(OscMessage(String.format(java.util.Locale.US, "/fxret/%02d/mix/fader", i)))
        for (i in 1..6) msgs.add(OscMessage(String.format(java.util.Locale.US, "/mtx/%02d/mix/fader", i)))

        msgs.add(OscMessage(X32.MAIN_FADER))
        msgs.add(OscMessage(X32.MAIN_ON))

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
    private fun parseFaderOn(addr: String): Pair<String, Int>? {
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

        if (head !in arrayOf("ch", "bus", "auxin", "usb", "fxret", "mtx")) return null
        val idx = p.getOrNull(2)?.toIntOrNull() ?: return null
        if (p.getOrNull(3) != "mix") return null
        return when (p.getOrNull(4)) {
            "fader" -> "${head}_fader" to idx
            "on" -> "${head}_on" to idx
            else -> null
        }
    }

    private fun parseNameAddress(addr: String): Pair<String, Int>? {
        val p = addr.split('/')
        if (p.size < 3) return null
        val head = p[1]
        if (head == "main") return "main" to 0
        val idx = p.getOrNull(2)?.toIntOrNull() ?: return null
        if (head !in arrayOf("ch", "bus", "dca", "auxin", "usb", "fxret", "mtx")) return null
        return head to idx
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
            if (dirty.getAndSet(false)) listener?.onStateDirty()
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
