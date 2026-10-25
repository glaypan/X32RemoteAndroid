package com.x32remote

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.x32remote.core.ConsoleInfo
import com.x32remote.core.Discovery
import com.x32remote.core.ShowProgress
import com.x32remote.core.X32
import com.x32remote.core.PairCode
import com.x32remote.core.X32Client
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.x32remote.databinding.ActivityMainBinding
import com.x32remote.ui.CardAdapter
import com.x32remote.ui.ChannelAdapter
import com.x32remote.ui.showRiderDialog
import org.json.JSONArray
import java.util.Locale

/**
 * 主界面：顶部状态栏 + 标签栏 + 列表区。
 *
 * 标签：通道 / Bus / DCA / AuxIn / USB / FX / Matrix / Main / 演出
 */
class MainActivity : AppCompatActivity(), X32Client.Listener {

    private lateinit var b: ActivityMainBinding
    private val client = X32Client()

    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var cardAdapter: CardAdapter

    private val prefs by lazy { getSharedPreferences("x32", Context.MODE_PRIVATE) }

    // ---------- 扫码连接（电脑端二维码） ----------
    // 相机权限自己先要：ZXing 的 CaptureActivity 在不同版本上对权限的处理不一致，
    // 交给它会出现"黑屏但不报错"。这里拿到权限再进扫码页，拒绝就明确提示。
    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents
        if (!text.isNullOrBlank()) handlePairText(text)
    }
    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchScanner()
            else toast("没有相机权限：系统设置 → 应用 → X32 Remote → 权限 里打开，或手填 IP")
        }

    private val tabs = listOf(
        "ch" to "通道",
        "bus" to "Bus",
        "dca" to "DCA",
        "auxin" to "AuxIn",
        "usb" to "USB",
        "fxret" to "FX",
        "mtx" to "Matrix",
        "main" to "Main",
        "cards" to "演出"
    )
    private var currentTab = 0
    private val tabViews = ArrayList<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        setupTabs()
        setupLists()
        setupTopBar()

        client.listener = this
        client.showProgressSink = { p -> runOnUiThread { onShowProgress(p) } }

        // 自动重连上次使用的地址
        val lastIp = prefs.getString("last_ip", "") ?: ""
        if (lastIp.isNotEmpty()) {
            connect(lastIp, prefs.getInt("last_port", X32.DEFAULT_PORT))
        } else {
            updateTopBar()
        }
    }

    override fun onDestroy() {
        client.disconnect()
        super.onDestroy()
    }

    // ============================================================ 初始化

    private fun setupTabs() {
        tabViews.clear()
        b.tabBar.removeAllViews()
        tabs.forEachIndexed { i, (_, label) ->
            val tv = TextView(this, null, 0, R.style.Tab)
            tv.text = label
            tv.setOnClickListener { selectTab(i) }
            b.tabBar.addView(tv)
            tabViews.add(tv)
        }
        selectTab(0)
    }

    private fun setupLists() {
        channelAdapter = ChannelAdapter(client) { kind, idx -> promptRename(kind, idx) }
        b.rvList.layoutManager = LinearLayoutManager(this)
        b.rvList.adapter = channelAdapter

        cardAdapter = CardAdapter(
            onRun = { card -> client.show.run(card.id) },
            onPin = { card ->
                client.show.togglePin(card.id)
                cardAdapter.submit(client.show.cards)
            }
        )
        b.rvCards.layoutManager = LinearLayoutManager(this)
        b.rvCards.adapter = cardAdapter
    }

    private fun setupTopBar() {
        b.btnScan.setOnClickListener { doScan() }
        b.btnConnect.setOnClickListener { promptConnect() }
        b.btnStopShow.setOnClickListener {
            client.show.stopAll()
            toast("已停止演出")
        }
        b.btnMenu.setOnClickListener { showMenu() }
        updateTopBar()
    }

    private fun selectTab(i: Int) {
        currentTab = i
        val key = tabs[i].first
        val isCards = key == "cards"

        b.rvList.visibility = if (isCards) View.GONE else View.VISIBLE
        b.rvCards.visibility = if (isCards) View.VISIBLE else View.GONE

        if (isCards) {
            cardAdapter.submit(client.show.cards)
        } else {
            channelAdapter.group = key
        }
        refreshTabs()
    }

    private fun refreshTabs() {
        tabViews.forEachIndexed { i, tv ->
            val on = i == currentTab
            tv.setBackgroundResource(if (on) R.drawable.bg_chip_accent else R.drawable.bg_chip)
            tv.setTextColor(if (on) 0xFF06251C.toInt() else 0xFF9AA5B6.toInt())
        }
    }

    // ============================================================ 客户端回调

    override fun onStateDirty() {
        if (b.rvList.visibility == View.VISIBLE) channelAdapter.refresh()
        updateTopBar()
    }

    override fun onEvent(text: String) {
        toast(text)
    }

    override fun onLinkChange(connected: Boolean, message: String) {
        updateTopBar()
        toast(if (connected) "已连接 $message" else message)
    }

    private fun onShowProgress(p: ShowProgress) {
        cardAdapter.submitProgress(p)
        b.tvShowState.text = if (p.isRunning) {
            String.format(Locale.US, "%s %.0f%%", p.name, p.progress * 100)
        } else {
            ""
        }
    }

    private fun updateTopBar() {
        val ok = client.connected
        b.dotStatus.backgroundTintList = ColorStateList.valueOf(
            if (ok) 0xFF22D3A8.toInt() else 0xFFE5484D.toInt()
        )
        b.tvStatus.text = if (ok) "已连接" else "未连接"
        b.tvTarget.text = if (ok) "${client.targetIp}:${client.targetPort}" else "点「连接」或「扫描」"
    }

    // ============================================================ 连接

    private fun connect(ip: String, port: Int) {
        prefs.edit().putString("last_ip", ip).putInt("last_port", port).apply()
        client.connect(ip, port)
        updateTopBar()
    }

    private fun promptConnect() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val etIp = EditText(this).apply {
            hint = "调音台 IP，例如 192.168.1.100"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(client.targetIp.ifEmpty { prefs.getString("last_ip", "") ?: "" })
        }
        val etPort = EditText(this).apply {
            hint = "端口"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(
                (if (client.targetPort > 0) client.targetPort else X32.DEFAULT_PORT).toString()
            )
        }
        box.addView(etIp)
        box.addView(etPort)

        AlertDialog.Builder(this)
            .setTitle("连接调音台")
            .setView(box)
            .setPositiveButton("连接") { _, _ ->
                val ip = etIp.text.toString().trim()
                val port = etPort.text.toString().trim().toIntOrNull() ?: X32.DEFAULT_PORT
                if (ip.isEmpty()) {
                    toast("请填写 IP")
                } else {
                    connect(ip, port)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun doScan() {
        toast("正在扫描局域网…")
        Thread {
            val list = Discovery.scan(applicationContext, 1800)
            runOnUiThread { showScanResult(list) }
        }.start()
    }

    private fun showScanResult(list: List<ConsoleInfo>) {
        if (list.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("未发现调音台")
                .setMessage(
                    "没有收到 /xinfo 应答。请确认：\n" +
                        "· 手机与调音台在同一局域网\n" +
                        "· 路由器未开启 AP 隔离\n" +
                        "· 也可直接手动输入 IP"
                )
                .setPositiveButton("手动输入") { _, _ -> promptConnect() }
                .setNegativeButton("知道了", null)
                .show()
            return
        }

        val labels = list.map { it.display }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("发现 ${list.size} 台设备")
            .setItems(labels) { _, which ->
                connect(list[which].ip, X32.DEFAULT_PORT)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ============================================================ 改名

    private fun promptRename(kind: String, idx: Int) {
        val group = X32.kindGroup(kind)
        val et = EditText(this).apply {
            hint = "名称（最多 12 字）"
            setText(client.state.name(group, idx))
        }
        AlertDialog.Builder(this)
            .setTitle("重命名 ${X32.GROUP_LABEL[group] ?: group} ${"%02d".format(idx)}")
            .setView(et)
            .setPositiveButton("保存") { _, _ ->
                client.setName(group, idx, et.text.toString())
                channelAdapter.refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ============================================================ 菜单

    private fun showMenu() {
        val items = arrayOf(
            "扫码连接（电脑端二维码）",
            "导出卡片配置到剪贴板",
            "从剪贴板导入卡片",
            "重新拉取台面状态",
            "自动增益 / 自动推子（需电脑端服务）",
            "断开连接",
            "关于"
        )
        AlertDialog.Builder(this)
            .setTitle("菜单")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startScan()
                    1 -> exportCards()
                    2 -> importCards()
                    3 -> {
                        client.requestFullState()
                        toast("已请求全量状态")
                    }
                    4 -> showRider()
                    5 -> {
                        client.disconnect()
                        updateTopBar()
                        toast("已断开")
                    }
                    6 -> AlertDialog.Builder(this)
                        .setTitle("X32 Remote")
                        .setMessage(
                            "原生 Android 遥控端\n" +
                                "OSC over UDP · 端口 ${X32.DEFAULT_PORT}\n\n" +
                                "支持 32 通道 / 16 Bus / 8 DCA / AuxIn / USB / FX / Matrix / Main，\n" +
                                "含演出卡片引擎（时间轴、曲线、冲突检测）。"
                        )
                        .setPositiveButton("好", null)
                        .show()
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    // ============================================================ 扫码连接

    private fun startScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            launchScanner()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchScanner() {
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)   // 只认二维码，别抓错条形码
            .setPrompt("对准电脑端「手机连接」里的二维码")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
        scanLauncher.launch(options)
    }

    /** 扫到即连；识别失败不动当前地址（半截地址比不连更让人困惑） */
    private fun handlePairText(raw: String) {
        val code = PairCode.parse(raw)
        if (code == null) {
            toast("这不是 X32 的连接码，请对准电脑端「手机连接」里的二维码")
            return
        }
        // 自动增益/推子走电脑端 HTTP 接口：配对码里带了网页端口，顺手记下来
        prefs.edit().putInt("rider_port", code.web ?: 8080).apply()
        connect(code.host, code.osc)
        toast("扫码连接 ${code.summary}")
    }

    private fun showRider() {
        // 自动增益跑在电脑端服务里：用调音台同一个 IP + 网页端口（默认 8080）
        val host = client.targetIp.ifEmpty { prefs.getString("last_ip", "") ?: "" }
        if (host.isEmpty()) {
            toast("先连接一次或在菜单里填过 IP")
            return
        }
        showRiderDialog(
            this, host, prefs.getInt("rider_port", 8080)
        ) { p -> prefs.edit().putInt("rider_port", p).apply() }
    }

    private fun exportCards() {
        val json = client.show.cardsToJson().toString()
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("x32cards", json))
        toast("已复制 ${client.show.cards.size} 张卡片到剪贴板")
    }

    private fun importCards() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
        if (text.isBlank()) {
            toast("剪贴板为空")
            return
        }
        try {
            val arr = JSONArray(text)
            val n = client.show.importCards(arr)
            cardAdapter.submit(client.show.cards)
            toast("已导入 $n 张卡片")
        } catch (e: Exception) {
            toast("解析失败：${e.message}")
        }
    }

    // ============================================================ 工具

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
