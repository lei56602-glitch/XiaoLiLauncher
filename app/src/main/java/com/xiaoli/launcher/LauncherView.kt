package com.xiaoli.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LauncherView(context: Context) : View(context) {
    private data class AppEntry(val label: String, val packageName: String, val intent: Intent)
    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val apps = mutableListOf<AppEntry>()
    private val favorites = mutableListOf<AppEntry>()
    private var drawerIndex = 0
    private var homeIndex = 0
    var isDrawerOpen = false
        private set
    private val handler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() { invalidate(); handler.postDelayed(this, 30_000L) }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        loadApps()
        handler.post(clockTick)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(clockTick)
        super.onDetachedFromWindow()
    }

    private fun loadApps() {
        val pm = context.packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(query, PackageManager.MATCH_ALL)
            .mapNotNull { info ->
                val pkg = info.activityInfo.packageName
                if (pkg == context.packageName) null else AppEntry(
                    info.loadLabel(pm).toString(), pkg,
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        .setClassName(pkg, info.activityInfo.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.sortedBy { it.label.lowercase(Locale.getDefault()) }
        apps.clear(); apps.addAll(resolved)
        val saved = prefs.getString("favorites", null)
        val chosen = saved?.split("|")?.filter { it.isNotBlank() }?.toSet()
        favorites.clear()
        if (chosen != null) favorites.addAll(apps.filter { it.packageName in chosen })
        if (favorites.isEmpty()) favorites.addAll(apps.take(5))
    }

    fun closeDrawer() { isDrawerOpen = false; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        paint.shader = LinearGradient(0f, 0f, w, h, intArrayOf(0xFF0878E8.toInt(), 0xFF0754B9.toInt(), 0xFF032D79.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint); paint.shader = null
        drawTop(canvas, w)
        if (isDrawerOpen) drawDrawer(canvas, w, h) else drawHome(canvas, w, h)
    }

    private fun text(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false) {
        paint.color = color; paint.textSize = size; paint.typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        canvasText(c, value, x, y)
    }
    private fun canvasText(c: Canvas, value: String, x: Float, y: Float) { c.drawText(value, x, y, paint) }

    private fun drawTop(c: Canvas, w: Float) {
        text(c, "◉  小李桌面", w * .035f, height * .09f, height * .045f, -1, true)
        val now = Date()
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        val date = SimpleDateFormat("MM月dd日  EEEE", Locale.CHINA).format(now)
        paint.textAlign = Paint.Align.RIGHT
        text(c, time, w * .965f, height * .085f, height * .052f, -1, true)
        text(c, date, w * .965f, height * .125f, height * .021f, 0xFFE2F1FF.toInt())
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawHome(c: Canvas, w: Float, h: Float) {
        val count = minOf(5, favorites.size)
        if (count > 0) {
            val gap = w * .035f
            val itemW = w * .13f
            val total = count * itemW + (count - 1) * gap
            val start = (w - total) / 2f
            favorites.take(5).forEachIndexed { i, app ->
                val left = start + i * (itemW + gap)
                val top = h * .57f
                val rect = RectF(left, top, left + itemW, top + h * .16f)
                paint.color = if (i == homeIndex) 0xFFFFFFFF.toInt() else 0x66FFFFFF
                c.drawRoundRect(rect, 28f, 28f, paint)
                if (i == homeIndex) {
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f; paint.color = 0xFF75D7FF.toInt()
                    c.drawRoundRect(RectF(rect.left-3f, rect.top-3f, rect.right+3f, rect.bottom+3f), 30f, 30f, paint)
                    paint.style = Paint.Style.FILL
                }
                val icon = runCatching { context.packageManager.getApplicationIcon(app.packageName) }.getOrNull()
                icon?.let { drawable ->
                    val size = (h * .075f).toInt().coerceAtLeast(32)
                    val cx = rect.centerX().toInt(); val cy = (rect.top + rect.height()*.43f).toInt()
                    drawable.setBounds(cx-size/2, cy-size/2, cx+size/2, cy+size/2)
                    drawable.draw(c)
                }
                paint.textAlign = Paint.Align.CENTER
                text(c, app.label.take(8), rect.centerX(), rect.bottom + h * .045f, h * .025f, -1, true)
                paint.textAlign = Paint.Align.LEFT
            }
        } else {
            text(c, "按上键打开应用抽屉，选择常用应用", w * .34f, h * .68f, h * .03f, -1)
        }
        text(c, "↑  按上键打开应用抽屉", w * .035f, h * .94f, h * .026f, -1)
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val connected = cm.activeNetworkInfo?.isConnected == true
        paint.textAlign = Paint.Align.RIGHT
        text(c, (if (connected) "● 网络已连接" else "○ 网络未连接") + "    小李设备", w * .965f, h * .94f, h * .024f, -1)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawDrawer(c: Canvas, w: Float, h: Float) {
        paint.color = 0xCC052B68.toInt(); c.drawRoundRect(RectF(w*.06f,h*.18f,w*.94f,h*.9f),32f,32f,paint)
        text(c, "应用抽屉", w*.09f, h*.25f, h*.04f, -1, true)
        val cols = 5; val cellW = w*.16f; val cellH = h*.15f
        apps.forEachIndexed { i, app ->
            val row = i / cols; val col = i % cols
            val x = w*.1f + col*cellW; val y = h*.32f + row*cellH
            val rect = RectF(x,y,x+cellW*.78f,y+cellH*.72f)
            paint.color = if (i == drawerIndex) 0xFF31B9FF.toInt() else 0x44FFFFFF
            c.drawRoundRect(rect,20f,20f,paint)
            text(c, app.label.take(10), x+12f, y+cellH*.48f, h*.022f, -1, i==drawerIndex)
        }
        if (apps.isEmpty()) text(c, "暂未发现可启动的应用", w*.38f,h*.55f,h*.03f,-1)
        text(c, "方向键选择  ·  确认键启动  ·  返回键关闭", w*.3f,h*.86f,h*.022f,0xFFDDEEFF.toInt())
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isDrawerOpen) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> { closeDrawer(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> drawerIndex = (drawerIndex - 1).coerceAtLeast(0)
                KeyEvent.KEYCODE_DPAD_RIGHT -> drawerIndex = (drawerIndex + 1).coerceAtMost(apps.lastIndex.coerceAtLeast(0))
                KeyEvent.KEYCODE_DPAD_UP -> drawerIndex = (drawerIndex - 5).coerceAtLeast(0)
                KeyEvent.KEYCODE_DPAD_DOWN -> drawerIndex = (drawerIndex + 5).coerceAtMost(apps.lastIndex.coerceAtLeast(0))
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    apps.getOrNull(drawerIndex)?.let { runCatching { context.startActivity(it.intent) } }
                }
            }
            invalidate(); return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { isDrawerOpen = true; drawerIndex = 0; invalidate(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (favorites.isNotEmpty()) homeIndex = (homeIndex - 1).coerceAtLeast(0); invalidate(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (favorites.isNotEmpty()) homeIndex = (homeIndex + 1).coerceAtMost(favorites.lastIndex); invalidate(); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                favorites.getOrNull(homeIndex)?.let { runCatching { context.startActivity(it.intent) } }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
