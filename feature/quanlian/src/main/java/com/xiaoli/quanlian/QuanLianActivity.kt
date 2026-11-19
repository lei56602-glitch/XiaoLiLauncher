package com.xiaoli.quanlian

import android.app.Activity
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.xiaoli.quanlian.discovery.AndroidLanDiscovery
import com.xiaoli.transfer.api.DeviceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class QuanLianActivity : Activity() {
    private lateinit var screen: QuanLianScreen
    private val discovery = AndroidLanDiscovery()
    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var discoveryJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        screen = QuanLianScreen()
        setContentView(screen)
    }

    override fun onStart() {
        super.onStart()
        discovery.start()
        discoveryJob = screenScope.launch {
            discovery.devices.collect { devices -> screen.updateDevices(devices) }
        }
    }

    override fun onStop() {
        discoveryJob?.cancel()
        discoveryJob = null
        discovery.stop()
        super.onStop()
    }

    override fun onDestroy() {
        screenScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            finish()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) screen.moveFocus(-1)
        else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) screen.moveFocus(1)
        else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) screen.activateFocused()
        else return super.onKeyDown(keyCode, event)
        return true
    }

    private inner class QuanLianScreen : View(this@QuanLianActivity) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private var focus = 0
        private var selected = -1
        private var devices: List<DeviceInfo> = emptyList()
        private val titles = listOf("发送文件", "接收文件", "附近设备", "传输记录")
        private val descriptions = listOf(
            "选择文件并发送到附近设备",
            "等待其他设备发起文件传输",
            "正在搜索局域网设备",
            "查看已完成和进行中的传输"
        )

        init { isFocusable = true; isFocusableInTouchMode = true }

        fun updateDevices(value: List<DeviceInfo>) {
            devices = value
            invalidate()
        }

        fun moveFocus(delta: Int) {
            focus = (focus + delta).coerceIn(0, 3)
            invalidate()
        }

        fun activateFocused() {
            selected = focus
            invalidate()
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val w = width.toFloat()
            val h = height.toFloat()
            p.shader = LinearGradient(0f, 0f, w, h,
                intArrayOf(0xFF0878E8.toInt(), 0xFF0754B9.toInt(), 0xFF032D79.toInt()),
                null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p)
            p.shader = null

            drawText(c, "‹  返回桌面", w * .045f, h * .075f, h * .025f, 0xFFDDEEFF.toInt())
            drawText(c, "小李全联", w * .07f, h * .19f, h * .055f, -1, true)
            drawText(c, "跨设备文件互传", w * .07f, h * .245f, h * .026f, 0xFFDDEEFF.toInt())

            val cols = if (w >= h) 4 else 2
            val gap = w * if (cols == 4) .018f else .035f
            val left = w * .07f
            val top = h * .34f
            val totalW = w * .86f
            val cardW = (totalW - gap * (cols - 1)) / cols
            val cardH = if (cols == 4) h * .27f else h * .19f

            titles.indices.forEach { i ->
                val col = i % cols
                val row = i / cols
                val x = left + col * (cardW + gap)
                val y = top + row * (cardH + h * .025f)
                val rect = RectF(x, y, x + cardW, y + cardH)
                p.color = if (focus == i) 0xFF168CE8.toInt() else 0x553A9FEA
                c.drawRoundRect(rect, 24f, 24f, p)
                p.color = if (focus == i) 0xFF9CE5FF.toInt() else 0x667CCBFF
                p.style = Paint.Style.STROKE
                p.strokeWidth = if (focus == i) 4f else 1.5f
                c.drawRoundRect(rect, 24f, 24f, p)
                p.style = Paint.Style.FILL
                drawText(c, listOf("↑", "↓", "⌖", "◷")[i], rect.left + cardW * .12f,
                    rect.top + cardH * .43f, cardH * .25f, -1, true)
                drawText(c, titles[i], rect.left + cardW * .12f,
                    rect.top + cardH * .69f, cardH * .12f, -1, true)
                val detail = if (i == 2) "已发现 ${devices.size} 台设备" else descriptions[i]
                drawText(c, detail, rect.left + cardW * .12f,
                    rect.top + cardH * .86f, cardH * .075f, 0xFFDDEEFF.toInt())
            }

            val noticeTop = if (cols == 4) h * .68f else h * .80f
            val notice = RectF(w * .07f, noticeTop, w * .93f, noticeTop + h * .16f)
            p.color = 0xCC052B68.toInt()
            c.drawRoundRect(notice, 22f, 22f, p)
            val headline = if (selected == 2) {
                if (devices.isEmpty()) "暂未发现设备，请确认双方处于同一局域网"
                else "附近设备：${devices.take(3).joinToString("、") { it.name }}"
            } else {
                "局域网发现运行中 · 已发现 ${devices.size} 台设备"
            }
            drawText(c, headline, w * .10f, noticeTop + h * .065f, h * .025f, -1, true)
            drawText(c, "文件发送、接收及安全传输服务正在接入。", w * .10f,
                noticeTop + h * .115f, h * .021f, 0xFFDDEEFF.toInt())
            drawText(c, "按返回键回到小李桌面", w * .07f, h * .95f, h * .021f, 0xFFDDEEFF.toInt())
        }

        private fun drawText(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false) {
            p.color = color
            p.textSize = size
            p.typeface = if (bold) android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                else android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
            p.textAlign = Paint.Align.LEFT
            c.drawText(value, x, y, p)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val w = width.toFloat()
                val h = height.toFloat()
                val cols = if (w >= h) 4 else 2
                val gap = w * if (cols == 4) .018f else .035f
                val cardW = (w * .86f - gap * (cols - 1)) / cols
                val left = w * .07f
                val top = h * .34f
                val cardH = if (cols == 4) h * .27f else h * .19f
                val rowGap = h * .025f
                titles.indices.forEach { i ->
                    val col = i % cols
                    val row = i / cols
                    val rect = RectF(left + col * (cardW + gap), top + row * (cardH + rowGap),
                        left + col * (cardW + gap) + cardW, top + row * (cardH + rowGap) + cardH)
                    if (rect.contains(event.x, event.y)) {
                        focus = i
                        selected = i
                        invalidate()
                        return true
                    }
                }
                if (event.y < h * .12f && event.x < w * .25f) finish()
                return true
            }
            return true
        }
    }
}
