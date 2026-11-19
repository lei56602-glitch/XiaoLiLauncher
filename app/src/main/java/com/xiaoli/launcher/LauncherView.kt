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
import android.view.MotionEvent
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
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDragging = false
    private var lastDragIndex = -1
    var isDrawerOpen = false
        private set
    private var isSettingsOpen = false
    val isLauncherSettingsOpen: Boolean get() = isSettingsOpen
    private var settingsFocus = 0
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
        favorites.clear()
        if (saved == null) {
            favorites.addAll(apps.take(5))
        } else {
            val byPackage = apps.associateBy { it.packageName }
            saved.split("|").filter { it.isNotBlank() }.forEach { pkg ->
                byPackage[pkg]?.let { if (favorites.size < 5) favorites.add(it) }
            }
        }
        homeIndex = homeIndex.coerceIn(0, (favorites.size - 1).coerceAtLeast(0))
    }

    private var editingFavorites = false

    private fun saveFavorites() {
        prefs.edit().putString("favorites", favorites.joinToString("|") { it.packageName }).apply()
        homeIndex = homeIndex.coerceIn(0, (favorites.size - 1).coerceAtLeast(0))
    }

    private fun toggleFavorite(app: AppEntry) {
        val existing = favorites.indexOfFirst { it.packageName == app.packageName }
        if (existing >= 0) favorites.removeAt(existing)
        else if (favorites.size < 5) favorites.add(app)
        saveFavorites()
    }

    fun closeDrawer() { isDrawerOpen = false; editingFavorites = false; invalidate() }
    fun closeLauncherSettings() { isSettingsOpen = false; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        paint.shader = LinearGradient(0f, 0f, w, h, intArrayOf(0xFF0878E8.toInt(), 0xFF0754B9.toInt(), 0xFF032D79.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, paint); paint.shader = null
        drawTop(canvas, w)
        when {
            isDrawerOpen -> drawDrawer(canvas, w, h)
            else -> drawHome(canvas, w, h)
        }
    }

    private fun text(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false) {
        paint.color = color; paint.textSize = size; paint.typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        canvasText(c, value, x, y)
    }
    private fun canvasText(c: Canvas, value: String, x: Float, y: Float) { c.drawText(value, x, y, paint) }

    private fun drawTop(c: Canvas, w: Float) {
        val h = height.toFloat()
        // Brand lockup, matching the approved TV launcher reference.
        paint.shader = LinearGradient(0f, 0f, 0f, h * .13f,
            intArrayOf(0x5539BFFF, 0x0039BFFF), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h * .14f, paint)
        paint.shader = null
        paint.color = 0xFF16B8FF.toInt()
        c.drawCircle(w * .058f, h * .057f, h * .045f, paint)
        paint.color = 0xFF0878E8.toInt()
        c.drawCircle(w * .058f, h * .057f, h * .038f, paint)
        text(c, "⇄", w * .035f, h * .073f, h * .055f, -1, true)
        text(c, "小李桌面", w * .101f, h * .052f, h * .041f, -1, true)
        text(c, "智能 · 简洁 · 高效", w * .101f, h * .086f, h * .021f, 0xFFD7EAFF.toInt())
        // Top-right status glyphs.
        text(c, "◉", w * .872f, h * .064f, h * .034f, -1, true)
        text(c, "▣", w * .914f, h * .064f, h * .034f, -1, true)
        text(c, "⚙", w * .958f, h * .064f, h * .038f, -1, true)

        val now = Date()
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        val date = SimpleDateFormat("MM月dd日  E", Locale.CHINA).format(now)
        text(c, time, w * .04f, h * .225f, h * .105f, -1, true)
        paint.color = 0xCCFFFFFF.toInt()
        c.drawRect(w * .213f, h * .18f, w * .215f, h * .245f, paint)
        text(c, date, w * .235f, h * .187f, h * .034f, -1, true)
        text(c, "农历 九月三十", w * .235f, h * .225f, h * .031f, 0xFFE5F2FF.toInt())
        paint.color = 0xCCFFFFFF.toInt()
        c.drawRect(w * .397f, h * .18f, w * .399f, h * .245f, paint)
        text(c, "☀", w * .425f, h * .218f, h * .062f, 0xFFFFD84D.toInt(), true)
        text(c, "26°C", w * .492f, h * .198f, h * .047f, -1, true)
        text(c, "多云", w * .492f, h * .232f, h * .027f, 0xFFE5F2FF.toInt())
    }

    private fun drawHome(c: Canvas, w: Float, h: Float) {
        drawScenicBackdrop(c, w, h)
        // Large central QuanLian feature card.
        val card = RectF(w * .11f, h * .325f, w * .67f, h * .725f)
        paint.shader = LinearGradient(card.left, card.top, card.right, card.bottom,
            intArrayOf(0xEE087CE8.toInt(), 0xE9158FFF.toInt(), 0xDD42A9FF.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(card, h * .035f, h * .035f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = h * .0025f
        paint.color = 0x887FE3FF.toInt()
        c.drawRoundRect(card, h * .035f, h * .035f, paint)
        paint.style = Paint.Style.FILL
        // App mark.
        paint.shader = LinearGradient(card.left, card.top, card.left, card.top + h * .18f,
            intArrayOf(0xFF10C5F2.toInt(), 0xFF0759D9.toInt()), null, Shader.TileMode.CLAMP)
        val logo = RectF(w * .138f, h * .38f, w * .228f, h * .55f)
        c.drawRoundRect(logo, h * .025f, h * .025f, paint)
        paint.shader = null
        text(c, "⇄", w * .153f, h * .515f, h * .115f, -1, true)
        text(c, "小李全联 · 快传", w * .245f, h * .438f, h * .044f, -1, true)
        text(c, "一键传输，极速分享", w * .245f, h * .493f, h * .031f, -1, true)
        text(c, "无需安装其他软件，", w * .245f, h * .555f, h * .025f, 0xFFD8F0FF.toInt())
        text(c, "在这里就能快传！", w * .245f, h * .592f, h * .025f, 0xFFD8F0FF.toInt())
        // Connected-device illustration, built from clean TV/phone silhouettes.
        paint.color = 0xFF063E9D.toInt()
        c.drawRoundRect(RectF(w*.455f,h*.425f,w*.635f,h*.60f),h*.012f,h*.012f,paint)
        paint.color = 0xFF061F62.toInt()
        c.drawRoundRect(RectF(w*.465f,h*.438f,w*.625f,h*.575f),h*.008f,h*.008f,paint)
        text(c, "⇄", w*.515f, h*.535f, h*.085f, 0xFF37D8FF.toInt(), true)
        paint.color = 0xFF052F7E.toInt()
        c.drawRoundRect(RectF(w*.435f,h*.50f,w*.475f,h*.635f),h*.009f,h*.009f,paint)
        c.drawRoundRect(RectF(w*.60f,h*.535f,w*.642f,h*.66f),h*.009f,h*.009f,paint)
        text(c, "▣", w*.445f, h*.605f, h*.025f, 0xFF8CEBFF.toInt(), true)
        text(c, "▣", w*.611f, h*.625f, h*.025f, 0xFF8CEBFF.toInt(), true)
        // Call to action.
        val action = RectF(w*.245f,h*.635f,w*.39f,h*.697f)
        paint.color = 0x5537C7FF
        c.drawRoundRect(action,h*.04f,h*.04f,paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = h*.002f; paint.color = 0xFF61D7FF.toInt()
        c.drawRoundRect(action,h*.04f,h*.04f,paint); paint.style = Paint.Style.FILL
        text(c, "▶  立即使用", w*.278f, h*.675f, h*.026f, -1, true)

        // Five centered app shortcuts, with a luminous focus frame.
        val labels = listOf("应用抽屉", "图片管理", "小李全联", "影视播放", "系统设置")
        val symbols = listOf("▦", "◈", "⇄", "▶", "⚙")
        val colors = intArrayOf(0xFF1686F5.toInt(),0xFF08A8E9.toInt(),0xFF0878E8.toInt(),0xFF9851E8.toInt(),0xFF557EBA.toInt())
        val itemW = w * .073f
        val gap = w * .043f
        val total = itemW * 5 + gap * 4
        val start = (w - total) / 2f
        for (i in 0 until 5) {
            val left = start + i * (itemW + gap)
            val top = h * .785f
            val rect = RectF(left, top, left + itemW, top + h * .125f)
            if (i == homeIndex) {
                paint.color = 0x443FE6FF
                c.drawRoundRect(RectF(rect.left-h*.012f,rect.top-h*.022f,rect.right+h*.012f,rect.bottom+h*.05f),h*.03f,h*.03f,paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = h*.004f; paint.color = 0xFF55DFFF.toInt()
                c.drawRoundRect(RectF(rect.left-h*.012f,rect.top-h*.022f,rect.right+h*.012f,rect.bottom+h*.05f),h*.03f,h*.03f,paint)
                paint.style = Paint.Style.FILL
            }
            paint.shader = LinearGradient(left,rect.top,left,rect.bottom,
                intArrayOf(colors[i],0xFF0755C4.toInt()),null,Shader.TileMode.CLAMP)
            c.drawRoundRect(rect,h*.018f,h*.018f,paint)
            paint.shader = null
            paint.textAlign = Paint.Align.CENTER
            text(c,symbols[i],rect.centerX(),rect.top+h*.083f,h*.075f,-1,true)
            text(c,labels[i],rect.centerX(),rect.bottom+h*.043f,h*.027f,-1,true)
            paint.textAlign = Paint.Align.LEFT
        }
        text(c, "⌃  按上键打开应用抽屉", w*.42f, h*.975f, h*.023f, 0xFFE5F2FF.toInt())
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val connected = cm.activeNetworkInfo?.isConnected == true
        paint.textAlign = Paint.Align.RIGHT
        text(c, (if (connected) "◉  已连接" else "○  未连接") + "   │   ▣  小李电视", w*.975f, h*.975f, h*.023f, 0xFFE5F2FF.toInt())
        paint.textAlign = Paint.Align.LEFT
        drawSettingsPanel(c,w,h)
    }

    private fun drawScenicBackdrop(c: Canvas, w: Float, h: Float) {
        paint.shader = LinearGradient(0f,0f,w,h,
            intArrayOf(0xFF0879E8.toInt(),0xFF168FEA.toInt(),0xFF064A9E.toInt()),null,Shader.TileMode.CLAMP)
        c.drawRect(0f,0f,w,h,paint); paint.shader = null
        // Layered mountain ridges and reflective lake to evoke the approved wallpaper.
        val ridge = android.graphics.Path().apply {
            moveTo(0f,h*.53f); lineTo(w*.12f,h*.43f); lineTo(w*.20f,h*.49f)
            lineTo(w*.31f,h*.35f); lineTo(w*.42f,h*.48f); lineTo(w*.55f,h*.31f)
            lineTo(w*.68f,h*.46f); lineTo(w*.78f,h*.34f); lineTo(w*.91f,h*.47f)
            lineTo(w,h*.40f); lineTo(w,h*.68f); lineTo(0f,h*.68f); close()
        }
        paint.color = 0xFFB7DDF7.toInt(); c.drawPath(ridge,paint)
        val peaks = android.graphics.Path().apply {
            moveTo(0f,h*.56f); lineTo(w*.16f,h*.45f); lineTo(w*.25f,h*.54f)
            lineTo(w*.39f,h*.39f); lineTo(w*.51f,h*.54f); lineTo(w*.63f,h*.34f)
            lineTo(w*.76f,h*.53f); lineTo(w*.88f,h*.41f); lineTo(w,h*.52f)
            lineTo(w,h*.70f); lineTo(0f,h*.70f); close()
        }
        paint.color = 0xFF245F9E.toInt(); c.drawPath(peaks,paint)
        paint.shader = LinearGradient(0f,h*.66f,0f,h,
            intArrayOf(0xCC0A6CB6.toInt(),0xEE032F79.toInt()),null,Shader.TileMode.CLAMP)
        c.drawRect(0f,h*.64f,w,h,paint); paint.shader = null
        paint.color = 0x338CE5FF
        for (i in 0..5) c.drawRect(0f,h*(.70f+i*.045f),w,h*(.704f+i*.045f),paint)
    }

    private fun drawSettingsPanel(c: Canvas, w: Float, h: Float) {
        val panel = RectF(w*.715f,h*.095f,w*.99f,h*.905f)
        paint.color = 0xE8103265.toInt()
        c.drawRoundRect(panel,h*.025f,h*.025f,paint)
        paint.color = 0x334D9DDF
        c.drawRect(panel.left+h*.02f,panel.top+h*.11f,panel.right-h*.02f,panel.top+h*.112f,paint)
        text(c,"⚙  设置",panel.left+h*.04f,panel.top+h*.078f,h*.039f,-1,true)
        val rows = listOf("▧  壁纸设置","▧  桌面布局","▦  常用应用管理","ϟ  快传功能","▣  显示设置","◉  网络设置","⚙  高级设置","●  关于我们")
        val rowTop = panel.top+h*.13f
        val rowH = h*.085f
        rows.forEachIndexed { i,label ->
            val top = rowTop+i*rowH
            if (i == 3) {
                val r=RectF(panel.left+h*.008f,top,panel.right-h*.008f,top+rowH*.94f)
                paint.shader=LinearGradient(r.left,r.top,r.right,r.bottom,
                    intArrayOf(0xFF087CF4.toInt(),0xFF155BCB.toInt()),null,Shader.TileMode.CLAMP)
                c.drawRoundRect(r,h*.018f,h*.018f,paint); paint.shader=null
                paint.style=Paint.Style.STROKE; paint.strokeWidth=h*.002f; paint.color=0xFF65DFFF.toInt()
                c.drawRoundRect(r,h*.018f,h*.018f,paint); paint.style=Paint.Style.FILL
            } else {
                paint.color=0x224C8BC8
                c.drawRect(panel.left+h*.025f,top+rowH*.94f,panel.right-h*.025f,top+rowH*.955f,paint)
            }
            text(c,label,panel.left+h*.04f,top+rowH*.59f,h*.026f,-1,i==3)
            text(c,"›",panel.right-h*.045f,top+rowH*.59f,h*.04f,0xFFD4E8FF.toInt(),true)
            if (i==3) {
                text(c,"集成小李全联，支持文件互传",panel.left+h*.04f,top+rowH*1.25f,h*.019f,0xFFD6E8FA.toInt())
                paint.color=0xFF19C957.toInt()
                c.drawRoundRect(RectF(panel.right-h*.105f,top+rowH*1.04f,panel.right-h*.025f,top+rowH*1.38f),h*.018f,h*.018f,paint)
                text(c,"已就绪",panel.right-h*.098f,top+rowH*1.29f,h*.016f,-1,true)
            }
        }
    }

    private fun drawDrawer(c: Canvas, w: Float, h: Float) {
        paint.color = 0xCC052B68.toInt(); c.drawRoundRect(RectF(w*.06f,h*.18f,w*.94f,h*.9f),32f,32f,paint)
        text(c, if (editingFavorites) "选择首页常用应用（最多5个）" else "应用抽屉", w*.09f, h*.25f, h*.04f, -1, true)
        paint.textAlign = Paint.Align.RIGHT
        text(c, if (editingFavorites) "完成管理" else "管理首页应用", w*.9f, h*.25f, h*.025f, 0xFFBDEBFF.toInt(), true)
        paint.textAlign = Paint.Align.LEFT
        val cols = drawerColumns()
        val cellW = w * .8f / cols
        val cellH = if (w < h) h * .105f else h * .15f
        apps.forEachIndexed { i, app ->
            val row = i / cols; val col = i % cols
            val x = w*.1f + col*cellW; val y = h*.32f + row*cellH
            val rect = RectF(x,y,x+cellW*.88f,y+cellH*.78f)
            val selected = favorites.any { it.packageName == app.packageName }
            paint.color = when {
                i == drawerIndex -> 0xFF31B9FF.toInt()
                selected -> 0x6675D7FF
                else -> 0x44FFFFFF
            }
            c.drawRoundRect(rect,20f,20f,paint)
            text(c, (if (selected) "✓ " else "") + app.label.take(8), x+12f, y+cellH*.48f, h*.022f, -1, i==drawerIndex)
        }
        if (apps.isEmpty()) text(c, "暂未发现可启动的应用", w*.38f,h*.55f,h*.03f,-1)
        val hint = if (editingFavorites) "触摸点按添加/移除  ·  遥控器确认键操作  ·  最多5个"
            else "点按应用启动  ·  上滑打开抽屉  ·  点右上角管理首页应用"
        text(c, hint, w*.25f,h*.86f,h*.022f,0xFFDDEEFF.toInt())
    }


    private fun isCompactLayout(): Boolean = width < height

    private fun drawerColumns(): Int =
        prefs.getInt("drawer_columns", 5).coerceIn(3, 5)

    private fun openLauncherSettings() {
        isDrawerOpen = false
        isSettingsOpen = true
        settingsFocus = (drawerColumns() - 3).coerceIn(0, 2)
        invalidate()
    }

    private fun setDrawerColumns(columns: Int) {
        prefs.edit().putInt("drawer_columns", columns.coerceIn(3, 5)).apply()
        settingsFocus = columns - 3
        invalidate()
    }

    private fun drawLauncherSettings(c: Canvas, w: Float, h: Float) {
        paint.color = 0xE6052B68.toInt()
        c.drawRoundRect(RectF(w * .08f, h * .16f, w * .92f, h * .86f), 30f, 30f, paint)
        text(c, "小李桌面设置", w * .13f, h * .27f, h * .045f, -1, true)
        text(c, "应用抽屉列数", w * .13f, h * .39f, h * .03f, 0xFFDDEEFF.toInt())
        val gap = w * .025f
        val buttonW = w * .18f
        val start = w * .13f
        for (i in 0..2) {
            val columns = i + 3
            val left = start + i * (buttonW + gap)
            val rect = RectF(left, h * .44f, left + buttonW, h * .57f)
            paint.color = if (drawerColumns() == columns) 0xFF31B9FF.toInt() else 0x44FFFFFF
            c.drawRoundRect(rect, 18f, 18f, paint)
            paint.textAlign = Paint.Align.CENTER
            text(c, "${columns}列", rect.centerX(), h * .52f, h * .032f, -1, true)
            paint.textAlign = Paint.Align.LEFT
        }
        text(c, "默认五列，可根据习惯选择三列、四列或五列。", w * .13f, h * .66f, h * .024f, 0xFFDDEEFF.toInt())

        val card = RectF(w * .13f, h * .70f, w * .87f, h * .84f)
        paint.color = if (settingsFocus == 3) 0xFF167FD8.toInt() else 0x553AAFFF
        c.drawRoundRect(card, 20f, 20f, paint)
        text(c, "⇄  小李全联 · 快传", w * .17f, h * .755f, h * .03f, -1, true)
        text(c, "桌面内置设备互传功能  ·  核心模块接入中", w * .17f, h * .805f, h * .021f, 0xFFDDEEFF.toInt())

        val sys = RectF(w * .13f, h * .87f, w * .48f, h * .95f)
        paint.color = if (settingsFocus == 4) 0xFF31B9FF.toInt() else 0x44FFFFFF
        c.drawRoundRect(sys, 16f, 16f, paint)
        text(c, "打开系统设置", sys.centerX() - w * .11f, h * .92f, h * .024f, -1, true)
        text(c, "返回桌面", w * .72f, h * .92f, h * .024f, -1, true)
    }

    private fun homeItemRect(index: Int, w: Float, h: Float): RectF {
        if (w >= h) {
            val gap = w * .035f
            val itemW = w * .13f
            val total = favorites.size * itemW + (favorites.size - 1).coerceAtLeast(0) * gap
            val left = (w - total) / 2f + index * (itemW + gap)
            return RectF(left, h * .57f, left + itemW, h * .73f)
        }
        val cols = 3
        val itemW = w * .27f
        val itemH = h * .14f
        val gapX = w * .045f
        val gapY = h * .035f
        val total = cols * itemW + (cols - 1) * gapX
        val startX = (w - total) / 2f
        val rows = (favorites.size + cols - 1) / cols
        val totalH = rows * itemH + (rows - 1).coerceAtLeast(0) * gapY
        val startY = (h - totalH) / 2f + h * .04f
        val left = startX + (index % cols) * (itemW + gapX)
        val top = startY + (index / cols) * (itemH + gapY)
        return RectF(left, top, left + itemW, top + itemH)
    }

    private fun homeItemIndexAt(x: Float, y: Float): Int {
        favorites.indices.forEach { i ->
            if (homeItemRect(i, width.toFloat(), height.toFloat()).contains(x, y)) return i
        }
        return -1
    }

    private fun drawerItemIndexAt(x: Float, y: Float): Int {
        if (y < height * .32f || y > height * .82f) return -1
        val cols = drawerColumns()
        val cellW = width * .8f / cols
        val cellH = if (width < height) height * .105f else height * .15f
        val col = ((x - width * .1f) / cellW).toInt()
        val row = ((y - height * .32f) / cellH).toInt()
        if (col !in 0 until cols || row < 0) return -1
        val index = row * cols + col
        val cellLeft = width * .1f + col * cellW
        val cellTop = height * .32f + row * cellH
        return if (x <= cellLeft + cellW * .88f &&
            y <= cellTop + cellH * .78f && index in apps.indices) index else -1
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                touchDragging = false
                lastDragIndex = homeItemIndexAt(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - touchDownX
                val dy = event.y - touchDownY
                if (!isDrawerOpen && lastDragIndex >= 0 &&
                    event.eventTime - event.downTime > android.view.ViewConfiguration.getLongPressTimeout() &&
                    kotlin.math.abs(dx) > width * .015f && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                    touchDragging = true
                    val target = homeItemIndexAt(event.x, event.y)
                    if (target >= 0 && target != lastDragIndex && target in favorites.indices) {
                        val item = favorites.removeAt(lastDragIndex)
                        favorites.add(target, item)
                        homeIndex = target
                        lastDragIndex = target
                        saveFavorites()
                        invalidate()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - touchDownX
                val dy = event.y - touchDownY
                if (touchDragging) {
                    touchDragging = false
                    performClick()
                    return true
                }
                if (isSettingsOpen) {
                    if (event.y in (height * .44f)..(height * .57f)) {
                        val gap = width * .025f
                        val buttonW = width * .18f
                        val start = width * .13f
                        val selected = ((event.x - start) / (buttonW + gap)).toInt()
                        if (selected in 0..2) setDrawerColumns(selected + 3)
                    } else if (event.y in (height * .70f)..(height * .84f)) {
                        context.startActivity(Intent(context, com.xiaoli.quanlian.QuanLianActivity::class.java))
                    } else if (event.y in (height * .87f)..(height * .95f) && event.x < width * .5f) {
                        runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    } else {
                        isSettingsOpen = false
                    }
                } else if (isDrawerOpen) {
                    if (event.y in (height * .19f)..(height * .29f) && event.x > width * .68f) {
                        editingFavorites = !editingFavorites
                    } else if (event.y in (height * .82f)..(height * .9f) && event.x > width * .62f) {
                        editingFavorites = !editingFavorites
                    } else {
                        val index = drawerItemIndexAt(event.x, event.y)
                        if (index >= 0) {
                            drawerIndex = index
                            val app = apps[index]
                            if (editingFavorites) {
                                val exists = favorites.any { it.packageName == app.packageName }
                                if (exists || favorites.size < 5) toggleFavorite(app)
                            } else {
                                runCatching { context.startActivity(app.intent) }
                            }
                        } else if (event.x < width * .06f || event.x > width * .94f ||
                            event.y < height * .18f || event.y > height * .9f) {
                            closeDrawer()
                        }
                    }
                } else if (dy < -height * .06f) {
                    isDrawerOpen = true
                    drawerIndex = 0
                } else if (kotlin.math.abs(dx) > width * .08f && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                    if (favorites.isNotEmpty()) {
                        homeIndex = if (dx < 0) (homeIndex + 1).coerceAtMost(favorites.lastIndex)
                            else (homeIndex - 1).coerceAtLeast(0)
                    }
                } else {
                    val index = homeItemIndexAt(event.x, event.y)
                    if (index >= 0) {
                        homeIndex = index
                        favorites.getOrNull(index)?.let { runCatching { context.startActivity(it.intent) } }
                    } else if (event.y > height * .78f && event.x < width * .42f) {
                        isDrawerOpen = true
                        drawerIndex = 0
                    } else if (event.y < height * .16f && event.x < width * .28f) {
                        openLauncherSettings()
                    }
                }
                invalidate()
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                touchDragging = false
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isSettingsOpen) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> { closeLauncherSettings(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> settingsFocus = (settingsFocus - 1).coerceAtLeast(0)
                KeyEvent.KEYCODE_DPAD_RIGHT -> settingsFocus = (settingsFocus + 1).coerceAtMost(4)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (settingsFocus in 0..2) setDrawerColumns(settingsFocus + 3)
                    else if (settingsFocus == 3) context.startActivity(Intent(context, com.xiaoli.quanlian.QuanLianActivity::class.java))
                    else runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
            invalidate(); return true
        }
        if (isDrawerOpen) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> { closeDrawer(); return true }
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> editingFavorites = !editingFavorites
                KeyEvent.KEYCODE_DPAD_LEFT -> drawerIndex = (drawerIndex - 1).coerceAtLeast(0)
                KeyEvent.KEYCODE_DPAD_RIGHT -> drawerIndex = (drawerIndex + 1).coerceAtMost(apps.lastIndex.coerceAtLeast(0))
                KeyEvent.KEYCODE_DPAD_UP -> drawerIndex = (drawerIndex - drawerColumns()).coerceAtLeast(0)
                KeyEvent.KEYCODE_DPAD_DOWN -> drawerIndex = (drawerIndex + drawerColumns()).coerceAtMost(apps.lastIndex.coerceAtLeast(0))
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    apps.getOrNull(drawerIndex)?.let { app ->
                        if (editingFavorites) {
                            val exists = favorites.any { it.packageName == app.packageName }
                            if (exists || favorites.size < 5) toggleFavorite(app)
                        } else {
                            runCatching { context.startActivity(app.intent) }
                        }
                    }
                }
            }
            invalidate(); return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { isDrawerOpen = true; drawerIndex = 0; invalidate(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { if (favorites.isNotEmpty()) homeIndex = (homeIndex - 1).coerceAtLeast(0); invalidate(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { if (favorites.isNotEmpty()) homeIndex = (homeIndex + 1).coerceAtMost(favorites.lastIndex); invalidate(); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { openLauncherSettings(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                favorites.getOrNull(homeIndex)?.let { runCatching { context.startActivity(it.intent) } }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
