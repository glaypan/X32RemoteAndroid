package com.x32remote.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.x32remote.core.X32

/**
 * 一行通道条：名称 / 推子 / dB / 静音 / 电平，全部自绘。
 *
 * 不自建子 View、不做布局嵌套，48 路滚动交给 RecyclerView 复用，
 * 保证在长列表下依然跟手。
 */
@SuppressLint("ViewConstructor")
class ChannelStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Callback {
        /** 拖动推子（已在 View 内做 32ms 节流） */
        fun onFader(kind: String, idx: Int, v: Float)

        /** 点击静音按钮 */
        fun onMute(kind: String, idx: Int)

        /** 点击名称区域，请求改名 */
        fun onName(kind: String, idx: Int)
    }

    var callback: Callback? = null

    // ------------------------------------------------------------ 数据

    private var kind = "ch_fader"
    private var idx = 1
    private var group = "ch"
    private var name = ""
    private var fader = 0.75f
    private var muted = false
    private var level = 0f
    private var driven = false // 卡片正在驱动该路
    private var alone = false // 该路处于"已停手"的择出状态（可选高亮）
    private var rowBg = COLOR_ROW_A

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    // ------------------------------------------------------------ 画笔

    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    init {
        pText.textAlign = Paint.Align.LEFT
    }

    // ------------------------------------------------------------ 对外接口

    fun bind(
        kind: String,
        idx: Int,
        name: String,
        fader: Float,
        muted: Boolean,
        level: Float,
        driven: Boolean,
        rowIndex: Int
    ) {
        this.kind = kind
        this.idx = idx
        this.group = X32.kindGroup(kind)
        this.name = name
        this.fader = fader
        this.muted = muted
        this.level = level
        this.driven = driven
        this.rowBg = if (rowIndex % 2 == 0) COLOR_ROW_A else COLOR_ROW_B
        invalidate()
    }

    /** 只刷新电平（高频调用，不做重排） */
    fun updateLevel(l: Float) {
        if (level == l) return
        level = l
        invalidate()
    }

    /** 外部状态变了就同步回来（拖动中不覆盖，避免跳手） */
    fun syncFader(v: Float) {
        if (dragging) return
        if (fader == v) return
        fader = v
        invalidate()
    }

    fun syncMute(m: Boolean) {
        if (muted == m) return
        muted = m
        invalidate()
    }

    fun syncName(n: String) {
        if (name == n) return
        name = n
        invalidate()
    }

    fun syncDriven(dr: Boolean) {
        if (driven == dr) return
        driven = dr
        invalidate()
    }

    /** 该 View 当前是否正在处理这个 kind/idx（RecyclerView 复用后要靠它校验） */
    fun matches(k: String, i: Int) = kind == k && idx == i

    // ------------------------------------------------------------ 布局几何

    private val pad get() = dp(8f)
    private val nameW get() = dp(94f)
    private val muteW get() = dp(40f)
    private val dbW get() = dp(52f)

    private fun faderLeft() = pad + nameW + dp(6f)

    private fun faderRight() = width - pad - muteW - dbW - dp(10f)

    private fun trackY() = height / 2f - dp(3f)

    private fun levelY() = height / 2f + dp(9f)

    private fun muteRect(): RectF {
        val w = muteW - dp(10f)
        val h = dp(26f)
        val right = (width - pad - dbW).coerceAtLeast(dp(10f))
        return RectF(right - w, height / 2f - h / 2f, right, height / 2f + h / 2f)
    }

    // ------------------------------------------------------------ 绘制

    override fun onDraw(canvas: Canvas) {
        // 行背景
        pFill.color = rowBg
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), pFill)

        // 被卡片驱动时左侧亮条
        if (driven) {
            pFill.color = COLOR_DRIVEN
            canvas.drawRect(0f, dp(4f), dp(3f), height - dp(4f), pFill)
        }

        val cy = height / 2f

        // ---- 名称区
        pText.textAlign = Paint.Align.LEFT
        pText.color = if (driven) COLOR_DRIVEN else COLOR_TEXT
        pText.textSize = dp(13f)
        val numText = "%02d".format(idx)
        canvas.drawText(numText, pad, cy + dp(4.5f), pText)

        val numW = pText.measureText(numText) + dp(6f)
        pText.color = if (name.isEmpty()) COLOR_DIM else COLOR_TEXT
        pText.textSize = dp(12.5f)
        val nm = if (name.isEmpty()) (X32.GROUP_LABEL[group] ?: group) else name
        canvas.drawText(ellipsize(nm, nameW - numW - dp(6f)), pad + numW, cy + dp(4.5f), pText)

        // ---- 推子轨道
        val fl = faderLeft()
        val fr = faderRight()
        pFill.color = COLOR_TRACK
        r.set(fl, trackY(), fr, trackY() + dp(6f))
        canvas.drawRoundRect(r, dp(3f), dp(3f), pFill)

        // 填充
        val fw = (fr - fl) * fader.coerceIn(0f, 1f)
        if (fw > 0f) {
            pFill.color = if (muted) COLOR_MUTED_FILL else COLOR_FILL
            r.set(fl, trackY(), fl + fw, trackY() + dp(6f))
            canvas.drawRoundRect(r, dp(3f), dp(3f), pFill)
        }

        // 手柄
        val hx = fl + fw
        pFill.color = if (muted) COLOR_MUTED_FILL else COLOR_HANDLE
        canvas.drawCircle(hx, cy - dp(1f), dp(if (driven) 9f else 7.5f), pFill)
        if (driven) {
            pStroke.color = COLOR_DRIVEN
            pStroke.strokeWidth = dp(1.5f)
            canvas.drawCircle(hx, cy - dp(1f), dp(11f), pStroke)
        }

        // ---- 电平条
        val lv = level.coerceIn(0f, 1f)
        if (lv > 0.001f) {
            pFill.color = levelColor(lv)
            r.set(fl, levelY(), fl + (fr - fl) * lv, levelY() + dp(4f))
            canvas.drawRoundRect(r, dp(2f), dp(2f), pFill)
        }

        // ---- dB 文本
        pText.textAlign = Paint.Align.RIGHT
        pText.color = if (muted) COLOR_DIM else COLOR_SUB
        pText.textSize = dp(12f)
        val dbText = X32.formatDb(fader)
        canvas.drawText(dbText, width - pad - muteW, cy + dp(4.5f), pText)

        // ---- 静音按钮
        val mr = muteRect()
        pFill.color = if (muted) COLOR_MUTED else COLOR_ON
        canvas.drawRoundRect(mr, dp(6f), dp(6f), pFill)
        pText.textAlign = Paint.Align.CENTER
        pText.color = if (muted) Color.WHITE else Color.BLACK
        pText.textSize = dp(12f)
        pText.isFakeBoldText = true
        canvas.drawText("M", mr.centerX(), mr.centerY() + dp(4.2f), pText)
        pText.isFakeBoldText = false

        pText.textAlign = Paint.Align.LEFT
    }

    private fun ellipsize(s: String, maxW: Float): String {
        if (pText.measureText(s) <= maxW) return s
        var t = s
        while (t.isNotEmpty() && pText.measureText("$t…") > maxW) {
            t = t.substring(0, t.length - 1)
        }
        return "$t…"
    }

    private fun levelColor(v: Float): Int = when {
        v > 0.92f -> COLOR_CLIP
        v > 0.78f -> COLOR_WARN
        else -> COLOR_LEVEL
    }

    // ------------------------------------------------------------ 触摸

    private var dragging = false
    private var lastEmit = 0L

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (muteRect().contains(event.x, event.y)) {
                    return true
                }
                if (event.x < pad + nameW) {
                    callback?.onName(kind, idx)
                    return true
                }
                val fl = faderLeft()
                val fr = faderRight()
                if (event.x >= fl - dp(12f) && event.x <= fr + dp(12f)) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    applyDrag(event.x)
                    return true
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragging) {
                    applyDrag(event.x)
                    return true
                }
            }

            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    dragging = false
                    applyDrag(event.x, force = true)
                    return true
                }
                if (muteRect().contains(event.x, event.y)) {
                    callback?.onMute(kind, idx)
                    return true
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                dragging = false
            }
        }
        return super.onTouchEvent(event)
    }

    private fun applyDrag(x: Float, force: Boolean = false) {
        val fl = faderLeft()
        val fr = faderRight()
        if (fr <= fl) return
        val v = ((x - fl) / (fr - fl)).coerceIn(0f, 1f)
        fader = v
        invalidate()

        val now = System.currentTimeMillis()
        if (force || now - lastEmit >= 32) {
            lastEmit = now
            callback?.onFader(kind, idx, v)
        }
    }

    companion object {
        private const val COLOR_ROW_A = 0xFF12141A.toInt()
        private const val COLOR_ROW_B = 0xFF171A21.toInt()
        private const val COLOR_TEXT = 0xFFE8EDF5.toInt()
        private const val COLOR_SUB = 0xFF9AA5B6.toInt()
        private const val COLOR_DIM = 0xFF5C6675.toInt()
        private const val COLOR_TRACK = 0xFF232833.toInt()
        private const val COLOR_FILL = 0xFF22D3A8.toInt()
        private const val COLOR_HANDLE = 0xFFEAF6F2.toInt()
        private const val COLOR_MUTED = 0xFFE5484D.toInt()
        private const val COLOR_MUTED_FILL = 0xFF7A2B2E.toInt()
        private const val COLOR_ON = 0xFF3A4353.toInt()
        private const val COLOR_LEVEL = 0xFF3DDC97.toInt()
        private const val COLOR_WARN = 0xFFFFC53D.toInt()
        private const val COLOR_CLIP = 0xFFFF5C5C.toInt()
        private const val COLOR_DRIVEN = 0xFF4AA8FF.toInt()
    }
}
