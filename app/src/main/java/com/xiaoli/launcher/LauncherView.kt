package com.xiaoli.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class LauncherView(context: Context) : View(context) {
    private data class AppEntry(val label: String, val packageName: String, val intent: Intent)
    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var wallpaperBitmap: Bitmap? = null
    private var dynamicWallpaperEnabled = prefs.getBoolean("dynamic_wallpaper", false)
    private var animationStarted = false
    private var weatherCity = prefs.getString("weather_city", "") ?: ""
    private var weatherSummary = prefs.getString("weather_summary", "尚未设置城市") ?: "尚未设置城市"
    private var weatherUpdatedAt = prefs.getLong("weather_updated_at", 0L)
    private var weatherLoading = false
    private var showClock = prefs.getBoolean("show_clock", true)
    private var showDate = prefs.getBoolean("show_date", true)
    private var showLunar = prefs.getBoolean("show_lunar", true)
    private var showWeather = prefs.getBoolean("show_weather", true)
    private var clockX = prefs.getFloat("widget_clock_x", .965f)
    private var clockY = prefs.getFloat("widget_clock_y", .085f)
    private var dateX = prefs.getFloat("widget_date_x", .965f)
    private var dateY = prefs.getFloat("widget_date_y", .125f)
    private var lunarX = prefs.getFloat("widget_lunar_x", .965f)
    private var lunarY = prefs.getFloat("widget_lunar_y", .16f)
    private var weatherX = prefs.getFloat("widget_weather_x", .965f)
    private var weatherY = prefs.getFloat("widget_weather_y", .195f)
    private var clockScale = prefs.getFloat("widget_clock_scale", 1f).coerceIn(.7f, 1.8f)
    private var dateScale = prefs.getFloat("widget_date_scale", 1f).coerceIn(.7f, 1.8f)
    private var lunarScale = prefs.getFloat("widget_lunar_scale", 1f).coerceIn(.7f, 1.8f)
    private var weatherScale = prefs.getFloat("widget_weather_scale", 1f).coerceIn(.7f, 1.8f)
    private var activeWidgetResize = -1
    private var widgetEditMode = false
    private var selectedEditWidget = 0
    private var resizeStartSpan = 0f
    private var resizeStartScale = 1f
    private var activeWidgetDrag = -1
    private var widgetDragStarted = false
    var onChooseWallpaper: (() -> Unit)? = null
    private val apps = mutableListOf<AppEntry>()
    private val favorites = mutableListOf<AppEntry>()
    private var drawerIndex = 0
    private var homeIndex = 0
    private var homeQuickTransferFocused = false
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
        override fun run() {
            invalidate()
            if (weatherCity.isNotBlank() && System.currentTimeMillis() - weatherUpdatedAt > 30 * 60 * 1000L) refreshWeather()
            handler.postDelayed(this, 30_000L)
        }
    }
    private val animationTick = object : Runnable {
        override fun run() {
            if (dynamicWallpaperEnabled) {
                invalidate()
                handler.postDelayed(this, 50L)
            } else {
                animationStarted = false
            }
        }
    }

    private fun updateAnimationLoop() {
        if (dynamicWallpaperEnabled && !animationStarted) {
            animationStarted = true
            handler.post(animationTick)
        } else if (!dynamicWallpaperEnabled) {
            animationStarted = false
            handler.removeCallbacks(animationTick)
        }
    }

    private fun toggleDynamicWallpaper() {
        dynamicWallpaperEnabled = !dynamicWallpaperEnabled
        prefs.edit().putBoolean("dynamic_wallpaper", dynamicWallpaperEnabled).apply()
        updateAnimationLoop()
        invalidate()
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        loadApps()
        loadSavedWallpaper()
        if (weatherCity.isNotBlank() && System.currentTimeMillis() - weatherUpdatedAt > 30 * 60 * 1000L) refreshWeather()
        handler.post(clockTick)
        updateAnimationLoop()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.removeCallbacks(clockTick)
        handler.post(clockTick)
        updateAnimationLoop()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(clockTick)
        handler.removeCallbacks(animationTick)
        animationStarted = false
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

    private fun drawDynamicAurora(c: Canvas, w: Float, h: Float) {
        if (!dynamicWallpaperEnabled) return
        val phase = System.currentTimeMillis() / 5000.0
        val x1 = (0.5 + 0.34 * kotlin.math.sin(phase)).toFloat() * w
        val y1 = (0.42 + 0.18 * kotlin.math.cos(phase * 0.8)).toFloat() * h
        val x2 = (0.5 + 0.38 * kotlin.math.cos(phase * 0.65)).toFloat() * w
        val y2 = (0.55 + 0.2 * kotlin.math.sin(phase * 0.9)).toFloat() * h
        paint.shader = RadialGradient(x1, y1, w * 0.58f,
            intArrayOf(0x5537D9FF, 0x2237A9FF, 0x00075BD8),
            floatArrayOf(0f, 0.52f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = RadialGradient(x2, y2, w * 0.48f,
            intArrayOf(0x442D8BFF, 0x1A25C7FF, 0x00075BD8),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }
    fun closeLauncherSettings() { isSettingsOpen = false; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (!drawWallpaper(canvas, w, h)) {
            paint.shader = LinearGradient(0f, 0f, w, h, intArrayOf(0xFF0878E8.toInt(), 0xFF0754B9.toInt(), 0xFF032D79.toInt()), null, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, paint); paint.shader = null
        }
        drawDynamicAurora(canvas, w, h)
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

    private fun lunarDateLabel(timeMillis: Long): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        return runCatching {
            val lunar = android.icu.util.ChineseCalendar().apply { setTimeInMillis(timeMillis) }
            val months = arrayOf("正月", "二月", "三月", "四月", "五月", "六月",
                "七月", "八月", "九月", "十月", "冬月", "腊月")
            val days = arrayOf("初一", "初二", "初三", "初四", "初五", "初六", "初七",
                "初八", "初九", "初十", "十一", "十二", "十三", "十四", "十五",
                "十六", "十七", "十八", "十九", "二十", "廿一", "廿二", "廿三",
                "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十")
            val month = lunar.get(android.icu.util.Calendar.MONTH).coerceIn(0, 11)
            val day = (lunar.get(android.icu.util.Calendar.DAY_OF_MONTH) - 1).coerceIn(0, 29)
            val leap = lunar.get(android.icu.util.ChineseCalendar.IS_LEAP_MONTH) == 1
            "农历" + (if (leap) "闰" else "") + months[month] + days[day]
        }.getOrNull()
    }

    private fun drawTop(c: Canvas, w: Float) {
        val now = Date()
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        val date = SimpleDateFormat("MM月dd日  EEEE", Locale.CHINA).format(now)
        paint.textAlign = Paint.Align.RIGHT
        if (showClock) text(c, time, w * clockX, height * clockY, height * .052f * clockScale, -1, true)
        if (showDate) text(c, date, w * dateX, height * dateY, height * .021f * dateScale, 0xFFE2F1FF.toInt())
        if (showLunar) lunarDateLabel(now.time)?.let {
            text(c, it, w * lunarX, height * lunarY, height * .019f * lunarScale, 0xFFBDEBFF.toInt())
        }
        if (showWeather) text(c, "天气  $weatherSummary", w * weatherX, height * weatherY, height * .019f * weatherScale,
            if (weatherCity.isBlank()) 0xFFFFE7B0.toInt() else 0xFFD9F5FF.toInt())
        paint.textAlign = Paint.Align.LEFT
        if (widgetEditMode) drawSelectedWidgetOutline(c)
    }

    private fun drawSelectedWidgetOutline(c: Canvas) {
        val visible = listOf(showClock, showDate, showLunar, showWeather)
        if (selectedEditWidget !in visible.indices || !visible[selectedEditWidget]) return
        val positions = listOf(
            Pair(clockX, clockY), Pair(dateX, dateY),
            Pair(lunarX, lunarY), Pair(weatherX, weatherY)
        )
        val p = positions[selectedEditWidget]
        val rect = RectF(width * (p.first - .39f), height * (p.second - .045f),
            width * (p.first + .012f), height * (p.second + .012f))
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (height * .0035f).coerceAtLeast(2f)
        paint.color = 0xFF55D9FF.toInt()
        c.drawRoundRect(rect, 12f, 12f, paint)
        paint.style = Paint.Style.FILL
        paint.color = 0xFF55D9FF.toInt()
        val handle = (height * .009f).coerceAtLeast(6f)
        listOf(
            rect.left to rect.top, rect.right to rect.top,
            rect.left to rect.bottom, rect.right to rect.bottom
        ).forEach { (x, y) -> c.drawCircle(x, y, handle, paint) }
        val widgetName = listOf("时间", "日期", "农历", "天气")[selectedEditWidget]
        paint.color = 0xCC063B78.toInt()
        val hintRect = RectF(rect.left, rect.bottom + height * .012f,
            rect.left + width * .31f, rect.bottom + height * .062f)
        c.drawRoundRect(hintRect, 10f, 10f, paint)
        paint.textAlign = Paint.Align.LEFT
        text(c, "$widgetName  ·  方向键移动  ·  音量键缩放",
            hintRect.left + 12f, hintRect.centerY() + height * .007f,
            height * .017f, -1, true)
        paint.textAlign = Paint.Align.LEFT
    }

    private fun resetSelectedWidget() {
        when (selectedEditWidget) {
            0 -> { clockX = .965f; clockY = .085f; clockScale = 1f }
            1 -> { dateX = .965f; dateY = .125f; dateScale = 1f }
            2 -> { lunarX = .965f; lunarY = .16f; lunarScale = 1f }
            3 -> { weatherX = .965f; weatherY = .195f; weatherScale = 1f }
        }
        saveWidgetPositions()
        invalidate()
    }

    private fun toggleWidgetEditMode() {
        widgetEditMode = !widgetEditMode
        if (!widgetEditMode) saveWidgetPositions()
        selectedEditWidget = selectedEditWidget.coerceIn(0, 3)
        invalidate()
    }

    private fun cycleEditableWidget() {
        repeat(4) {
            selectedEditWidget = (selectedEditWidget + 1) % 4
            val visible = when (selectedEditWidget) {
                0 -> showClock
                1 -> showDate
                2 -> showLunar
                else -> showWeather
            }
            if (visible) return
        }
    }

    private fun adjustSelectedWidgetPosition(dx: Float, dy: Float) {
        val stepX = .008f
        val stepY = .012f
        val x = when (selectedEditWidget) {
            0 -> clockX
            1 -> dateX
            2 -> lunarX
            else -> weatherX
        }
        val y = when (selectedEditWidget) {
            0 -> clockY
            1 -> dateY
            2 -> lunarY
            else -> weatherY
        }
        moveWidget(selectedEditWidget, (x + dx * stepX) * width, (y + dy * stepY) * height)
        saveWidgetPositions()
    }

    private fun adjustSelectedWidgetScale(delta: Float) {
        setWidgetScale(selectedEditWidget, widgetScale(selectedEditWidget) + delta)
        saveWidgetPositions()
    }

    private fun toggleHomeComponent(index: Int) {
        when (index) {
            9 -> { showClock = !showClock; prefs.edit().putBoolean("show_clock", showClock).apply() }
            10 -> { showDate = !showDate; prefs.edit().putBoolean("show_date", showDate).apply() }
            11 -> { showLunar = !showLunar; prefs.edit().putBoolean("show_lunar", showLunar).apply() }
            12 -> { showWeather = !showWeather; prefs.edit().putBoolean("show_weather", showWeather).apply() }
        }
        invalidate()
    }

    private fun openWeatherCityDialog() {
        val field = EditText(context).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            hint = "例如：Singapore"
            setText(weatherCity)
            setSelection(text.length)
        }
        AlertDialog.Builder(context)
            .setTitle("设置天气城市")
            .setMessage("输入城市名称，天气数据将通过网络获取。")
            .setView(field)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                val city = field.text.toString().trim()
                if (city.isNotEmpty()) {
                    weatherCity = city
                    weatherSummary = "正在获取天气…"
                    weatherUpdatedAt = 0L
                    prefs.edit().putString("weather_city", city).putString("weather_summary", weatherSummary).apply()
                    invalidate()
                    refreshWeather()
                }
            }
            .show()
    }

    private fun refreshWeather() {
        if (weatherLoading || weatherCity.isBlank()) return
        weatherLoading = true
        val requestedCity = weatherCity
        thread(name = "launcher-weather") {
            var result = "天气暂不可用"
            try {
                val encoded = URLEncoder.encode(requestedCity, "UTF-8")
                val geo = readWeatherJson("https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=zh&format=json")
                val first = geo.optJSONArray("results")?.optJSONObject(0)
                if (first != null) {
                    val lat = first.getDouble("latitude")
                    val lon = first.getDouble("longitude")
                    val forecast = readWeatherJson("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code&timezone=auto")
                    val current = forecast.getJSONObject("current")
                    val temperature = current.getDouble("temperature_2m")
                    val code = current.getInt("weather_code")
                    result = String.format(Locale.getDefault(), "%s  %.0f°C", weatherDescription(code), temperature)
                } else {
                    result = "找不到该城市"
                }
            } catch (_: Exception) {
                result = "天气获取失败"
            }
            val finalResult = result
            handler.post {
                if (weatherCity == requestedCity) {
                    weatherSummary = finalResult
                    weatherUpdatedAt = System.currentTimeMillis()
                    prefs.edit().putString("weather_summary", weatherSummary)
                        .putLong("weather_updated_at", weatherUpdatedAt).apply()
                    weatherLoading = false
                    invalidate()
                } else {
                    weatherLoading = false
                }
            }
        }
    }

    private fun readWeatherJson(address: String): JSONObject {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        return try {
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        } finally {
            connection.disconnect()
        }
    }

    private fun weatherDescription(code: Int): String = when (code) {
        0 -> "晴"
        1, 2 -> "晴间多云"
        3 -> "阴"
        45, 48 -> "有雾"
        51, 53, 55, 56, 57 -> "毛毛雨"
        61, 63, 65, 66, 67 -> "下雨"
        71, 73, 75, 77 -> "下雪"
        80, 81, 82 -> "阵雨"
        85, 86 -> "阵雪"
        95, 96, 99 -> "雷雨"
        else -> "天气状况未知"
    }

    private fun drawHome(c: Canvas, w: Float, h: Float) {
        val count = minOf(5, favorites.size)
        if (count > 0) {
            favorites.take(5).forEachIndexed { i, app ->
                val rect = homeItemRect(i, w, h)
                val iconSize = (h * .105f).coerceAtMost(rect.width() * .72f).coerceAtLeast(34f)
                val iconRect = RectF(rect.centerX() - iconSize / 2f, rect.top + rect.height() * .04f,
                    rect.centerX() + iconSize / 2f, rect.top + rect.height() * .04f + iconSize)
                drawUnifiedAppIcon(c, app, iconRect, i == homeIndex)
                paint.textAlign = Paint.Align.CENTER
                text(c, app.label.take(8), rect.centerX(), iconRect.bottom + h * .045f, h * .025f, -1, true)
                paint.textAlign = Paint.Align.LEFT
            }
        }
        drawCompactQuanLianShortcut(c,w,h)
        val cm=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val connected=cm.activeNetworkInfo?.isConnected==true
        paint.color=if(connected) 0xFFFFFFFF.toInt() else 0xFF9CB9D8.toInt()
        paint.style=Paint.Style.STROKE
        paint.strokeWidth=(h*.003f).coerceAtLeast(2f)
        val cx=w*.91f;val cy=h*.91f
        c.drawArc(RectF(cx-h*.026f,cy-h*.040f,cx+h*.026f,cy+h*.012f),220f,100f,false,paint)
        c.drawArc(RectF(cx-h*.018f,cy-h*.028f,cx+h*.018f,cy+h*.008f),220f,100f,false,paint)
        c.drawCircle(cx,cy+h*.004f,h*.004f,paint)
        val bx=w*.955f;val by=h*.91f
        val bt=android.graphics.Path().apply{
            moveTo(bx,by-h*.035f);lineTo(bx,by+h*.035f)
            moveTo(bx,by-h*.035f);lineTo(bx+h*.020f,by-h*.018f);lineTo(bx-h*.020f,by+h*.018f)
            moveTo(bx,by+h*.035f);lineTo(bx+h*.020f,by+h*.018f);lineTo(bx-h*.020f,by-h*.018f)
        }
        c.drawPath(bt,paint);paint.style=Paint.Style.FILL;paint.textAlign=Paint.Align.LEFT
    }

    private fun quanLianFeatureRect(w: Float,h: Float)=
        RectF(w*.425f,h*.425f,w*.575f,h*.505f)

    private fun drawCompactQuanLianShortcut(c: Canvas,w: Float,h: Float){
        val rect=quanLianFeatureRect(w,h)
        paint.shader=LinearGradient(rect.left,rect.top,rect.right,rect.bottom,
            intArrayOf(0xF20A8CFA.toInt(),0xE50655BD.toInt()),null,Shader.TileMode.CLAMP)
        if(homeQuickTransferFocused)paint.setShadowLayer(h*.025f,0f,0f,0xCC36C8FF.toInt())
        c.drawRoundRect(rect,h*.035f,h*.035f,paint)
        paint.clearShadowLayer();paint.shader=null
        paint.color=0x337FEAFF
        c.drawRoundRect(RectF(rect.left+2f,rect.top+2f,rect.right-2f,rect.bottom-2f),h*.03f,h*.03f,paint)
        text(c,"⇄",rect.left+rect.width()*.16f,rect.centerY()+h*.015f,h*.043f,-1,true)
        text(c,"快传",rect.left+rect.width()*.43f,rect.centerY()+h*.009f,h*.024f,-1,true)
    }

    private fun drawUnifiedAppIcon(c: Canvas, app: AppEntry, rect: RectF, focused: Boolean) {
        val radius = rect.width() * .23f
        paint.style = Paint.Style.FILL
        paint.color = if (focused) 0xFF087DEB.toInt() else 0xFF0759B8.toInt()
        if (focused) paint.setShadowLayer(rect.width() * .24f, 0f, 0f, 0xAA27BFFF.toInt())
        c.drawRoundRect(rect, radius, radius, paint)
        paint.clearShadowLayer()
        val inner = RectF(rect.left + rect.width()*.17f, rect.top + rect.height()*.17f,
            rect.right - rect.width()*.17f, rect.bottom - rect.height()*.17f)
        val save = c.save()
        val clip = Path().apply { addRoundRect(inner, radius*.55f, radius*.55f, Path.Direction.CW) }
        c.clipPath(clip)
        val drawable = runCatching { context.packageManager.getApplicationIcon(app.packageName) }.getOrNull()
        drawable?.let {
            it.setBounds(inner.left.toInt(), inner.top.toInt(), inner.right.toInt(), inner.bottom.toInt())
            it.draw(c)
        }
        c.restoreToCount(save)
        if (focused) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (rect.width()*.035f).coerceAtLeast(1.5f)
            paint.color = 0xFF83DFFF.toInt()
            c.drawRoundRect(rect, radius, radius, paint)
            paint.style = Paint.Style.FILL
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
            val cell = RectF(x,y,x+cellW*.88f,y+cellH*.92f)
            val iconSize = (cellH * .57f).coerceAtMost(cellW * .58f)
            val iconRect = RectF(cell.centerX() - iconSize/2f, y + cellH*.035f,
                cell.centerX() + iconSize/2f, y + cellH*.035f + iconSize)
            drawUnifiedAppIcon(c, app, iconRect, i == drawerIndex)
            paint.textAlign = Paint.Align.CENTER
            text(c, app.label.take(8), cell.centerX(), iconRect.bottom + cellH*.22f,
                h*.019f, -1, i==drawerIndex)
            paint.textAlign = Paint.Align.LEFT
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
        text(c, "小李桌面", w * .13f, h * .235f, h * .04f, -1, true)
        text(c, "桌面设置", w * .13f, h * .265f, h * .021f, 0xFFDDEEFF.toInt())
        val weatherSetting = RectF(w * .13f, h * .275f, w * .87f, h * .37f)
        paint.color = if (settingsFocus == 8) 0xFF31B9FF.toInt() else 0x5544BFFF
        c.drawRoundRect(weatherSetting, 16f, 16f, paint)
        text(c, "天气城市：${if (weatherCity.isBlank()) "尚未设置" else weatherCity}",
            w * .16f, h * .335f, h * .025f, -1, true)
        text(c, "桌面信息显示", w * .13f, h * .397f, h * .021f, 0xFFDDEEFF.toInt())
        val labels = listOf(if (showClock) "时间：开" else "时间：关",
            if (showDate) "日期：开" else "日期：关",
            if (showLunar) "农历：开" else "农历：关",
            if (showWeather) "天气：开" else "天气：关")
        val componentKeys = listOf(9, 10, 11, 12)
        val componentGap = w * .012f
        val componentW = (w * .74f - componentGap * 3f) / 4f
        for (i in 0..3) {
            val left = w * .13f + i * (componentW + componentGap)
            val rect = RectF(left, h * .415f, left + componentW, h * .465f)
            paint.color = if (settingsFocus == componentKeys[i]) 0xFF31B9FF.toInt() else 0x5544BFFF
            c.drawRoundRect(rect, 12f, 12f, paint)
            paint.textAlign = Paint.Align.CENTER
            text(c, labels[i], rect.centerX(), h * .447f, h * .018f, -1, true)
            paint.textAlign = Paint.Align.LEFT
        }
        text(c, "应用抽屉列数", w * .13f, h * .492f, h * .023f, 0xFFDDEEFF.toInt())
        val gap = w * .025f
        val buttonW = w * .18f
        val start = w * .13f
        for (i in 0..2) {
            val columns = i + 3
            val left = start + i * (buttonW + gap)
            val rect = RectF(left, h * .515f, left + buttonW, h * .575f)
            paint.color = if (drawerColumns() == columns) 0xFF31B9FF.toInt() else 0x44FFFFFF
            c.drawRoundRect(rect, 18f, 18f, paint)
            paint.textAlign = Paint.Align.CENTER
            text(c, "${columns}列", rect.centerX(), h * .553f, h * .024f, -1, true)
            paint.textAlign = Paint.Align.LEFT
        }

        val wallpaper = RectF(w * .13f, h * .59f, w * .35f, h * .68f)
        paint.color = if (settingsFocus == 3) 0xFF31B9FF.toInt() else 0x5544BFFF
        c.drawRoundRect(wallpaper, 16f, 16f, paint)
        text(c, "更换壁纸", w * .155f, h * .647f, h * .022f, -1, true)

        val dynamic = RectF(w * .38f, h * .59f, w * .62f, h * .68f)
        paint.color = if (settingsFocus == 4) 0xFF31B9FF.toInt() else 0x5544BFFF
        c.drawRoundRect(dynamic, 16f, 16f, paint)
        text(c, if (dynamicWallpaperEnabled) "动态背景：开" else "动态背景：关",
            w * .405f, h * .647f, h * .021f, -1, true)

        val preview = RectF(w * .65f, h * .59f, w * .87f, h * .68f)
        drawWallpaperPreview(c, preview)
        if (settingsFocus == 5) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = h * .003f
            paint.color = 0xFF75D7FF.toInt()
            c.drawRoundRect(preview, 16f, 16f, paint)
            paint.style = Paint.Style.FILL
        }

        val card = RectF(w * .13f, h * .70f, w * .87f, h * .84f)
        paint.color = if (settingsFocus == 6) 0xFF167FD8.toInt() else 0x553AAFFF
        c.drawRoundRect(card, 20f, 20f, paint)
        text(c, "⇄  小李全联 · 快传", w * .17f, h * .755f, h * .03f, -1, true)
        text(c, "桌面内置设备互传功能  ·  核心模块接入中", w * .17f, h * .805f, h * .021f, 0xFFDDEEFF.toInt())

        val sys = RectF(w * .13f, h * .87f, w * .48f, h * .95f)
        paint.color = if (settingsFocus == 7) 0xFF31B9FF.toInt() else 0x44FFFFFF
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

    private fun widgetIndexAt(x: Float, y: Float): Int {
        val rows = listOf(
            Triple(showClock, clockX, clockY),
            Triple(showDate, dateX, dateY),
            Triple(showLunar, lunarX, lunarY),
            Triple(showWeather, weatherX, weatherY)
        )
        rows.forEachIndexed { index, item ->
            if (item.first && x in (width * (item.second - .38f))..(width * item.second + width * .015f) &&
                kotlin.math.abs(y - height * item.third) <= height * .035f) return index
        }
        return -1
    }

    private fun moveWidget(index: Int, x: Float, y: Float) {
        val nx = (x / width).coerceIn(.30f, .985f)
        val ny = (y / height).coerceIn(.045f, .29f)
        when (index) {
            0 -> { clockX = nx; clockY = ny }
            1 -> { dateX = nx; dateY = ny }
            2 -> { lunarX = nx; lunarY = ny }
            3 -> { weatherX = nx; weatherY = ny }
        }
        invalidate()
    }

    private fun widgetScale(index: Int): Float = when (index) {
        0 -> clockScale
        1 -> dateScale
        2 -> lunarScale
        3 -> weatherScale
        else -> 1f
    }

    private fun setWidgetScale(index: Int, value: Float) {
        val scale = value.coerceIn(.7f, 1.8f)
        when (index) {
            0 -> clockScale = scale
            1 -> dateScale = scale
            2 -> lunarScale = scale
            3 -> weatherScale = scale
        }
        invalidate()
    }

    private fun saveWidgetPositions() {
        prefs.edit()
            .putFloat("widget_clock_x", clockX).putFloat("widget_clock_y", clockY)
            .putFloat("widget_date_x", dateX).putFloat("widget_date_y", dateY)
            .putFloat("widget_lunar_x", lunarX).putFloat("widget_lunar_y", lunarY)
            .putFloat("widget_weather_x", weatherX).putFloat("widget_weather_y", weatherY)
            .putFloat("widget_clock_scale", clockScale)
            .putFloat("widget_date_scale", dateScale)
            .putFloat("widget_lunar_scale", lunarScale)
            .putFloat("widget_weather_scale", weatherScale)
            .apply()
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
                activeWidgetDrag = if (!isSettingsOpen && !isDrawerOpen) widgetIndexAt(event.x, event.y) else -1
                widgetDragStarted = false
                lastDragIndex = if (activeWidgetDrag < 0) homeItemIndexAt(event.x, event.y) else -1
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (activeWidgetDrag >= 0 && event.pointerCount >= 2) {
                    val x2 = event.getX(1)
                    val y2 = event.getY(1)
                    val nearFirstFinger = kotlin.math.abs(x2 - event.getX(0)) <= width * .22f &&
                        kotlin.math.abs(y2 - event.getY(0)) <= height * .12f
                    if (nearFirstFinger) {
                        activeWidgetResize = activeWidgetDrag
                        val dx = event.getX(1) - event.getX(0)
                        val dy = event.getY(1) - event.getY(0)
                        resizeStartSpan = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                        resizeStartScale = widgetScale(activeWidgetResize)
                        widgetDragStarted = false
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeWidgetResize >= 0 && event.pointerCount >= 2) {
                    val dx = event.getX(1) - event.getX(0)
                    val dy = event.getY(1) - event.getY(0)
                    val span = kotlin.math.sqrt(dx * dx + dy * dy)
                    setWidgetScale(activeWidgetResize, resizeStartScale * span / resizeStartSpan)
                    return true
                }
                val dx = event.x - touchDownX
                val dy = event.y - touchDownY
                if (activeWidgetDrag >= 0 && event.eventTime - event.downTime > android.view.ViewConfiguration.getLongPressTimeout()) {
                    widgetDragStarted = true
                    moveWidget(activeWidgetDrag, event.x, event.y)
                    return true
                }
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
                if (activeWidgetResize >= 0) {
                    saveWidgetPositions()
                    activeWidgetResize = -1
                    activeWidgetDrag = -1
                    performClick()
                    return true
                }
                if (widgetDragStarted) {
                    saveWidgetPositions()
                    activeWidgetDrag = -1
                    widgetDragStarted = false
                    performClick()
                    return true
                }
                activeWidgetDrag = -1
                if (touchDragging) {
                    touchDragging = false
                    performClick()
                    return true
                }
                if (isSettingsOpen) {
                    if (event.y in (height * .275f)..(height * .37f)) {
                        openWeatherCityDialog()
                    } else if (event.y in (height * .515f)..(height * .575f)) {
                        val gap = width * .025f
                        val buttonW = width * .18f
                        val start = width * .13f
                        val selected = ((event.x - start) / (buttonW + gap)).toInt()
                        if (selected in 0..2) setDrawerColumns(selected + 3)
                    } else if (event.y in (height * .59f)..(height * .68f) && event.x < width * .36f) {
                        onChooseWallpaper?.invoke()
                    } else if (event.y in (height * .59f)..(height * .68f) && event.x in (width * .38f)..(width * .62f)) {
                        toggleDynamicWallpaper()
                    } else if (event.y in (height * .59f)..(height * .68f) && event.x > width * .64f) {
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
            MotionEvent.ACTION_POINTER_UP -> {
                if (activeWidgetResize >= 0) {
                    saveWidgetPositions()
                    activeWidgetResize = -1
                    activeWidgetDrag = -1
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                touchDragging = false
                activeWidgetResize = -1
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (widgetEditMode && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_ENTER)) {
            resetSelectedWidget()
            return true
        }
        if (!widgetEditMode && !isSettingsOpen && !isDrawerOpen &&
            keyCode == KeyEvent.KEYCODE_MENU) {
            toggleWidgetEditMode()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (widgetEditMode && !isSettingsOpen && !isDrawerOpen) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    toggleWidgetEditMode()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    cycleEditableWidget()
                    invalidate()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> { adjustSelectedWidgetPosition(-1f, 0f); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { adjustSelectedWidgetPosition(1f, 0f); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { adjustSelectedWidgetPosition(0f, -1f); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { adjustSelectedWidgetPosition(0f, 1f); return true }
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_EQUALS -> {
                    adjustSelectedWidgetScale(.05f); return true
                }
                KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_MINUS -> {
                    adjustSelectedWidgetScale(-.05f); return true
                }
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                    toggleWidgetEditMode()
                    return true
                }
            }
            return true
        }
        if (isSettingsOpen) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> { closeLauncherSettings(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> settingsFocus = (settingsFocus - 1).coerceAtLeast(0)
                KeyEvent.KEYCODE_DPAD_RIGHT -> settingsFocus = (settingsFocus + 1).coerceAtMost(12)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (settingsFocus in 9..12) toggleHomeComponent(settingsFocus)
                    else if (settingsFocus == 8) openWeatherCityDialog()
                    else if (settingsFocus in 0..2) setDrawerColumns(settingsFocus + 3)
                    else if (settingsFocus == 3) onChooseWallpaper?.invoke()
                    else if (settingsFocus == 4) toggleDynamicWallpaper()
                    else if (settingsFocus == 5) clearWallpaper()
                    else if (settingsFocus == 6) context.startActivity(Intent(context, com.xiaoli.quanlian.QuanLianActivity::class.java))
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
            KeyEvent.KEYCODE_MENU -> { openLauncherSettings(); return true }
            KeyEvent.KEYCODE_SETTINGS -> { toggleWidgetEditMode(); return true }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if(homeQuickTransferFocused)homeQuickTransferFocused=false
                else{isDrawerOpen=true;drawerIndex=0}
                invalidate();return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {homeQuickTransferFocused=true;invalidate();return true}
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if(homeQuickTransferFocused)homeQuickTransferFocused=false
                else if(favorites.isNotEmpty())homeIndex=(homeIndex-1).coerceAtLeast(0)
                invalidate();return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if(!homeQuickTransferFocused&&favorites.isNotEmpty()&&homeIndex< favorites.lastIndex)homeIndex++
                else homeQuickTransferFocused=true
                invalidate();return true
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { openLauncherSettings(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if(homeQuickTransferFocused||favorites.isEmpty()){
                    homeQuickTransferFocused=false
                    runCatching{context.startActivity(Intent(context,com.xiaoli.quanlian.QuanLianActivity::class.java))}
                }else favorites.getOrNull(homeIndex)?.let{runCatching{context.startActivity(it.intent)}}
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
