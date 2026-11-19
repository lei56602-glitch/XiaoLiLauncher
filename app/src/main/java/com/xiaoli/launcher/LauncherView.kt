package com.xiaoli.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Uri
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
    private var wallpaperBitmap: Bitmap? = null
    var onChooseWallpaper: (() -> Unit)? = null
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
        loadSavedWallpaper()
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

    private fun decodeWallpaper(uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
        }.getOrElse { return null }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        // Keep 4K displays sharp while avoiding full-resolution camera images exhausting TV memory.
        val maxDimension = 4096
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxDimension ||
            bounds.outHeight / sampleSize > maxDimension) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
        }.getOrNull()
    }

    fun setWallpaper(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val bitmap = decodeWallpaper(uri) ?: return
        wallpaperBitmap?.recycle()
        wallpaperBitmap = bitmap
        prefs.edit().putString("wallpaper_uri", uri.toString()).apply()
        invalidate()
    }

    private fun loadSavedWallpaper() {
        val value = prefs.getString("wallpaper_uri", null) ?: return
        val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return
        wallpaperBitmap = decodeWallpaper(uri)
    }

    private fun drawWallpaper(c: Canvas, w: Float, h: Float): Boolean {
        val bitmap = wallpaperBitmap ?: return false
        val scale = maxOf(w / bitmap.width, h / bitmap.height)
        val dw = bitmap.width * scale
        val dh = bitmap.height * scale
        val dst = RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
        paint.color = android.graphics.Color.WHITE
        c.drawBitmap(bitmap, null, dst, paint)
        paint.color = 0x44052F70
        c.drawRect(0f, 0f, w, h, paint)
        return true
    }
    fun closeLauncherSettings() { isSettingsOpen = false; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (!drawWallpaper(canvas, w, h)) {
            paint.shader = LinearGradient(0f, 0f, w, h, intArrayOf(0xFF0878E8.toInt(), 0xFF0754B9.toInt(), 0xFF032D79.toInt()), null, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, paint); paint.shader = null
        }
        drawTop(canvas, w)
        when {
            isSettingsOpen -> drawLauncherSettings(canvas, w, h)
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
        drawQuanLianFeatureCard(c, w, h)
        val count = minOf(5, favorites.size)
        if (count > 0) {
            favorites.take(5).forEachIndexed { i, app ->
                val rect = homeItemRect(i, w, h)
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

    private fun quanLianFeatureRect(w: Float, h: Float) =
        RectF(w * .12f, h * .25f, w * .88f, h * .49f)

    private fun drawQuanLianFeatureCard(c: Canvas, w: Float, h: Float) {
        val card = quanLianFeatureRect(w, h)
        paint.shader = LinearGradient(
            card.left, card.top, card.right, card.bottom,
            intArrayOf(0xEE087CE8.toInt(), 0xE9158FFF.toInt(), 0xDD0754B8.toInt()),
            null, Shader.TileMode.CLAMP
        )
        c.drawRoundRect(card, h * .025f, h * .025f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = h * .002f
        paint.color = 0x887FE3FF.toInt()
        c.drawRoundRect(card, h * .025f, h * .025f, paint)
        paint.style = Paint.Style.FILL

        val mark = RectF(card.left + w * .025f, card.top + h * .045f,
            card.left + w * .115f, card.bottom - h * .045f)
        paint.shader = LinearGradient(mark.left, mark.top, mark.right, mark.bottom,
            intArrayOf(0xFF12C9F2.toInt(), 0xFF0759D9.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(mark, h * .018f, h * .018f, paint)
        paint.shader = null
        text(c, "⇄", mark.left + w * .018f, mark.centerY() + h * .035f,
            h * .075f, -1, true)

        text(c, "小李全联 · 快传", card.left + w * .145f, card.top + h * .085f,
            h * .039f, -1, true)
        text(c, "让手机、电视与电脑轻松互传文件", card.left + w * .145f,
            card.top + h * .135f, h * .025f, 0xFFE5F4FF.toInt())
        text(c, "局域网设备发现已接入", card.left + w * .145f,
            card.top + h * .185f, h * .020f, 0xFFC9E8FF.toInt())

        val action = RectF(card.right - w * .205f, card.top + h * .075f,
            card.right - w * .035f, card.top + h * .165f)
        paint.color = 0x5537C7FF
        c.drawRoundRect(action, h * .025f, h * .025f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = h * .0018f
        paint.color = 0xFF75DFFF.toInt()
        c.drawRoundRect(action, h * .025f, h * .025f, paint)
        paint.style = Paint.Style.FILL
        text(c, "立即打开  ›", action.left + w * .025f,
            action.centerY() + h * .009f, h * .022f, -1, true)
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

    private fun clearWallpaper() {
        wallpaperBitmap?.recycle()
        wallpaperBitmap = null
        prefs.edit().remove("wallpaper_uri").apply()
        invalidate()
    }

    private fun drawWallpaperPreview(c: Canvas, rect: RectF) {
        val bitmap = wallpaperBitmap
        paint.color = 0x553AAFFF
        c.drawRoundRect(rect, 16f, 16f, paint)
        if (bitmap != null) {
            val save = c.save()
            val inner = RectF(rect.left + 5f, rect.top + 5f, rect.right - 5f, rect.bottom - 5f)
            c.clipRect(inner)
            paint.color = android.graphics.Color.WHITE
            val scale = maxOf(inner.width() / bitmap.width, inner.height() / bitmap.height)
            val dw = bitmap.width * scale
            val dh = bitmap.height * scale
            val dst = RectF(inner.centerX() - dw / 2f, inner.centerY() - dh / 2f,
                inner.centerX() + dw / 2f, inner.centerY() + dh / 2f)
            c.drawBitmap(bitmap, null, dst, paint)
            c.restoreToCount(save)
        } else {
            text(c, "当前使用默认蓝色壁纸", rect.left + 12f, rect.centerY() + rect.height() * .08f,
                rect.height() * .20f, 0xFFDDEEFF.toInt())
        }
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

        val wallpaper = RectF(w * .13f, h * .59f, w * .48f, h * .68f)
        paint.color = if (settingsFocus == 3) 0xFF31B9FF.toInt() else 0x5544BFFF
        c.drawRoundRect(wallpaper, 16f, 16f, paint)
        text(c, "更换桌面壁纸", w * .17f, h * .647f, h * .023f, -1, true)

        val preview = RectF(w * .52f, h * .59f, w * .87f, h * .68f)
        drawWallpaperPreview(c, preview)
        if (settingsFocus == 4) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = h * .003f
            paint.color = 0xFF75D7FF.toInt()
            c.drawRoundRect(preview, 16f, 16f, paint)
            paint.style = Paint.Style.FILL
        }

        val card = RectF(w * .13f, h * .70f, w * .87f, h * .84f)
        paint.color = if (settingsFocus == 5) 0xFF167FD8.toInt() else 0x553AAFFF
        c.drawRoundRect(card, 20f, 20f, paint)
        text(c, "⇄  小李全联 · 快传", w * .17f, h * .755f, h * .03f, -1, true)
        text(c, "桌面内置设备互传功能  ·  核心模块接入中", w * .17f, h * .805f, h * .021f, 0xFFDDEEFF.toInt())

        val sys = RectF(w * .13f, h * .87f, w * .48f, h * .95f)
        paint.color = if (settingsFocus == 6) 0xFF31B9FF.toInt() else 0x44FFFFFF
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
                    } else if (event.y in (height * .59f)..(height * .68f) && event.x < width * .5f) {
                        onChooseWallpaper?.invoke()
                    } else if (event.y in (height * .59f)..(height * .68f) && event.x >= width * .5f) {
                        clearWallpaper()
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
                    if (quanLianFeatureRect(width.toFloat(), height.toFloat()).contains(event.x, event.y)) {
                        runCatching {
                            context.startActivity(Intent(context, com.xiaoli.quanlian.QuanLianActivity::class.java))
                        }
                    } else if (index >= 0) {
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
                KeyEvent.KEYCODE_DPAD_RIGHT -> settingsFocus = (settingsFocus + 1).coerceAtMost(6)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (settingsFocus in 0..2) setDrawerColumns(settingsFocus + 3)
                    else if (settingsFocus == 3) onChooseWallpaper?.invoke()
                    else if (settingsFocus == 4) clearWallpaper()
                    else if (settingsFocus == 5) context.startActivity(Intent(context, com.xiaoli.quanlian.QuanLianActivity::class.java))
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
