package com.xiaoli.launcher

import android.content.Context
import android.app.role.RoleManager
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
import android.net.wifi.WifiManager
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
    private var fallbackGradient: LinearGradient? = null
    private var fallbackGradientWidth = -1f
    private var fallbackGradientHeight = -1f

    private fun fallbackGradientFor(w: Float, h: Float): LinearGradient {
        val cached = fallbackGradient
        if (cached != null && fallbackGradientWidth == w && fallbackGradientHeight == h) return cached
        return LinearGradient(
            0f, 0f, w, h,
            intArrayOf(0xFF0878E8.toInt(), 0xFF0754B9.toInt(), 0xFF032D79.toInt()),
            null, Shader.TileMode.CLAMP
        ).also {
            fallbackGradient = it
            fallbackGradientWidth = w
            fallbackGradientHeight = h
        }
    }
    private var wallpaperBitmap: Bitmap? = null
    private var dynamicWallpaperEnabled = prefs.getBoolean("dynamic_wallpaper", false)
    private var animationStarted = false
    private var weatherCity = prefs.getString("weather_city", "") ?: ""
    private var weatherSummary = prefs.getString("weather_summary", "尚未设置城市") ?: "尚未设置城市"
    private var weatherUpdatedAt = prefs.getLong("weather_updated_at", 0L)
    private var weatherLoading = false
    private var showClock = prefs.getBoolean("show_clock", true)
    private var showDate = prefs.getBoolean("show_date", true)
    private var showLunar = false
    private var showWeather = false
    private var use24HourClock = prefs.getBoolean("clock_24_hour", true)
    private var showClockSeconds = prefs.getBoolean("clock_seconds", false)
    private var clockX = prefs.getFloat("widget_clock_x", .055f)
    private var clockY = prefs.getFloat("widget_clock_y", .085f)
    private var dateX = prefs.getFloat("widget_date_x", .055f)
    private var dateY = prefs.getFloat("widget_date_y", .135f)
    private var lunarX = prefs.getFloat("widget_lunar_x", .965f)
    private var lunarY = prefs.getFloat("widget_lunar_y", .16f)
    private var weatherX = prefs.getFloat("widget_weather_x", .965f)
    private var weatherY = prefs.getFloat("widget_weather_y", .195f)
    private var clockScale = prefs.getFloat("widget_clock_scale", 1f).coerceIn(.55f, 2.4f)
    private var dateScale = prefs.getFloat("widget_date_scale", 1f).coerceIn(.55f, 2.4f)
    private var lunarScale = prefs.getFloat("widget_lunar_scale", 1f).coerceIn(.55f, 2.4f)
    private var weatherScale = prefs.getFloat("widget_weather_scale", 1f).coerceIn(.55f, 2.4f)
    private var activeWidgetResize = -1
    private var widgetEditMode = false
    private var selectedEditWidget = 0
    private var resizeStartSpan = 0f
    private var resizeStartScale = 1f
    private var activeWidgetDrag = -1
    private var widgetDragStarted = false
    var onChooseWallpaper: (() -> Unit)? = null
    var onRequestDefaultHome: (() -> Unit)? = null
    private val apps = mutableListOf<AppEntry>()
    private val favorites = mutableListOf<AppEntry>()
    private val appIconCache = mutableMapOf<String, android.graphics.drawable.Drawable>()
    private var drawerIndex = 0
    private var drawerScrollRow = 0
    private var homeIndex = 0
    private var homeIconScale = prefs.getFloat("home_icon_scale", 1f).coerceIn(.65f, 1.6f)
    private var resizingHomeIcons = false
    private var homeIconResizeStartSpan = 0f
    private var homeIconResizeStartScale = 1f
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
            if (System.currentTimeMillis() - weatherUpdatedAt > 30 * 60 * 1000L) refreshWeather()
            handler.postDelayed(this, if (showClockSeconds) 1_000L else 30_000L)
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

    private fun updateClockTick() {
        handler.removeCallbacks(clockTick)
        handler.post(clockTick)
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
        // One-time migration moves legacy right-aligned clock/date widgets to the approved upper-left layout.
        if (!prefs.getBoolean("home_layout_v2_migrated", false)) {
            clockX = .055f
            clockY = .085f
            dateX = .055f
            dateY = .135f
            showLunar = false
            showWeather = false
            prefs.edit()
                .putFloat("widget_clock_x", clockX).putFloat("widget_clock_y", clockY)
                .putFloat("widget_date_x", dateX).putFloat("widget_date_y", dateY)
                .putBoolean("show_lunar", false).putBoolean("show_weather", false)
                .putBoolean("home_layout_v2_migrated", true)
                .apply()
        }
        isFocusable = true
        isFocusableInTouchMode = true
        loadApps()
        loadSavedWallpaper()
        if (System.currentTimeMillis() - weatherUpdatedAt > 30 * 60 * 1000L) refreshWeather()
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
            paint.shader = fallbackGradientFor(w, h)
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
        val timePattern = when {
            use24HourClock && showClockSeconds -> "HH:mm:ss"
            use24HourClock -> "HH:mm"
            showClockSeconds -> "hh:mm:ss a"
            else -> "hh:mm a"
        }
        val time = SimpleDateFormat(timePattern, Locale.getDefault()).format(now)
        val date = SimpleDateFormat("MM月dd日  EEEE", Locale.CHINA).format(now)
        paint.textAlign = Paint.Align.LEFT
        // Clock and date stay in the upper-left; weather and lunar date are removed from the home screen.
        if (showClock) text(c, time, w * clockX, height * clockY, height * .052f * clockScale, -1, true)
        if (showDate) text(c, date, w * dateX, height * dateY, height * .021f * dateScale, 0xFFE2F1FF.toInt())
        drawWifiShortcut(c, w, height.toFloat())
        drawSettingsShortcut(c, w, height.toFloat())
        paint.textAlign = Paint.Align.LEFT
        if (widgetEditMode) drawSelectedWidgetOutline(c)
    }

    private fun drawWifiShortcut(c: Canvas, w: Float, h: Float) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val enabled = runCatching { wifi?.isWifiEnabled == true }.getOrDefault(false)
        val connected = runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            network != null && cm.getNetworkCapabilities(network)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        }.getOrDefault(false)
        val x = w * .775f
        val y = h * .078f
        paint.color = if (connected) 0xFF8BE7FF.toInt() else 0xFFFFD28A.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (h * .0035f).coerceAtLeast(2f)
        val path = Path().apply {
            moveTo(x - h * .025f, y); quadTo(x, y - h * .027f, x + h * .025f, y)
            moveTo(x - h * .016f, y + h * .009f); quadTo(x, y - h * .009f, x + h * .016f, y + h * .009f)
        }
        c.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        c.drawCircle(x, y + h * .017f, h * .0045f, paint)
        paint.textAlign = Paint.Align.LEFT
        text(c, if (connected) "Wi-Fi 已连接" else if (enabled) "Wi-Fi 未连接" else "Wi-Fi 已关闭",
            x + h * .035f, y + h * .012f, h * .018f, -1, true)
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

    private fun cycleHomeIconScale() {
        val steps = listOf(.65f, .8f, 1f, 1.2f, 1.4f, 1.6f)
        val next = steps.firstOrNull { it > homeIconScale + .04f } ?: steps.first()
        homeIconScale = next
        prefs.edit().putFloat("home_icon_scale", homeIconScale).apply()
        invalidate()
    }

    private fun openWeatherCityDialog() {
        val field = EditText(context).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            hint = "例如：北京、上海、Singapore"
            setText(weatherCity)
            setSelection(text.length)
        }
        AlertDialog.Builder(context)
            .setTitle("设置天气城市")
            .setMessage("自动定位不可用时，可在此输入城市名称获取天气。")
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
        if (weatherLoading) return
        weatherLoading = true
        val requestedCity = weatherCity
        thread(name = "launcher-weather") {
            var result = "天气获取失败"
            var resolvedCity = requestedCity
            try {
                val lat: Double
                val lon: Double
                if (requestedCity.isBlank()) {
                    // TVs commonly lack GPS/Google location services. Try independent IP providers.
                    val endpoints = listOf("https://ipwho.is/", "https://ipapi.co/json/")
                    var location: JSONObject? = null
                    var lastError: Exception? = null
                    for (endpoint in endpoints) {
                        try {
                            val candidate = readWeatherJson(endpoint)
                            if (candidate.optBoolean("success", true) && candidate.has("latitude") && candidate.has("longitude")) {
                                location = candidate; break
                            }
                            lastError = IllegalStateException(candidate.optString("message", "定位服务未返回坐标"))
                        } catch (e: Exception) { lastError = e }
                    }
                    val resolved = location ?: throw (lastError ?: IllegalStateException("IP 定位服务不可用"))
                    lat = resolved.getDouble("latitude")
                    lon = resolved.getDouble("longitude")
                    resolvedCity = resolved.optString("city").ifBlank { resolved.optString("region") }
                        .ifBlank { resolved.optString("country") }.ifBlank { "当前位置" }
                } else {
                    val encoded = URLEncoder.encode(requestedCity, "UTF-8")
                    val geo = readWeatherJson("https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=zh&format=json")
                    val first = geo.optJSONArray("results")?.optJSONObject(0) ?: throw IllegalStateException("找不到该城市")
                    lat = first.getDouble("latitude")
                    lon = first.getDouble("longitude")
                    resolvedCity = first.optString("name", requestedCity)
                }
                val forecast = readWeatherJson("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code&timezone=auto")
                val current = forecast.getJSONObject("current")
                result = String.format(Locale.getDefault(), "%s  %.0f°C",
                    weatherDescription(current.getInt("weather_code")), current.getDouble("temperature_2m"))
            } catch (e: Exception) {
                result = when (e) {
                    is java.net.UnknownHostException -> "网络不可用，天气无法更新"
                    is java.net.SocketTimeoutException -> "天气服务连接超时，点击天气设置城市"
                    else -> if (requestedCity.isBlank()) "自动定位失败，点击天气手动设置城市"
                        else e.message?.takeIf { it.contains("城市") } ?: "天气服务暂不可用"
                }
            }
            val finalResult = result
            val finalCity = resolvedCity
            handler.post {
                if (weatherCity == requestedCity) {
                    if (requestedCity.isBlank() && finalCity.isNotBlank() &&
                        !finalResult.contains("不可用") && !finalResult.contains("超时")) {
                        weatherCity = finalCity
                        prefs.edit().putString("weather_city", finalCity).apply()
                    }
                    weatherSummary = finalResult
                    weatherUpdatedAt = System.currentTimeMillis()
                    prefs.edit().putString("weather_summary", weatherSummary)
                        .putLong("weather_updated_at", weatherUpdatedAt).apply()
                }
                weatherLoading = false
                invalidate()
            }
        }
    }

    private fun readWeatherJson(address: String): JSONObject {
        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 12000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "XiaoLiLauncher/1.0")
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IllegalStateException("天气服务 HTTP $status")
            JSONObject(body)
        } finally { connection.disconnect() }
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
        // App icons are first-class desktop objects; no enclosing tile/card.
        favorites.take(5).forEachIndexed { i, app ->
            val icon = homeIconCardRect(i, w, h)
            val focused = i == homeIndex
            drawUnifiedAppIcon(c, app, icon, focused)
            paint.textAlign = Paint.Align.CENTER
            text(c, app.label.take(12), icon.centerX(), icon.bottom + h * .038f, h * .024f, -1, true)
            paint.textAlign = Paint.Align.LEFT
        }
    }

    // A permanent, clearly labelled desktop entry; never hidden in gestures or the app drawer.
    private fun settingsShortcutRect(w: Float, h: Float) =
        RectF(w * .895f, h * .045f, w * .985f, h * .125f)

    private fun drawSettingsShortcut(c: Canvas, w: Float, h: Float) {
        val r = settingsShortcutRect(w, h)
        paint.style = Paint.Style.FILL
        paint.color = 0xFFBDEBFF.toInt()
        paint.textAlign = Paint.Align.CENTER
        text(c, "⚙", r.centerX(), r.top + h * .039f, h * .038f, -1, true)
        text(c, "设置", r.centerX(), r.bottom - h * .008f, h * .018f, -1, true)
        paint.textAlign = Paint.Align.LEFT
    }
    private fun drawUnifiedAppIcon(c: Canvas, app: AppEntry, rect: RectF, focused: Boolean) {
        val drawable = appIconCache.getOrPut(app.packageName) {
            runCatching { context.packageManager.getApplicationIcon(app.packageName) }
                .getOrNull() ?: android.graphics.drawable.ColorDrawable(0xFF1B74C8.toInt())
        }
        // Fill the icon viewport, clipping only its corners to the same rounded-card silhouette.
        val target = RectF(rect)
        val radius = rect.width() * .20f
        val save = c.save()
        val clip = Path().apply {
            addRoundRect(target, radius, radius, Path.Direction.CW)
        }
        c.clipPath(clip)
        drawable.setBounds(target.left.toInt(), target.top.toInt(), target.right.toInt(), target.bottom.toInt())
        drawable.draw(c)
        c.restoreToCount(save)

        // Draw focus treatment after the artwork so it remains visible and does not shrink it.
        if (focused) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (rect.width() * .018f).coerceAtLeast(2f)
            paint.color = 0xCC7DE5FF.toInt()
            paint.setShadowLayer(rect.width() * .12f, 0f, 0f, 0xAA27BFFF.toInt())
            c.drawRoundRect(target, radius, radius, paint)
            paint.clearShadowLayer()
            paint.style = Paint.Style.FILL
        }
    }

    private fun drawDrawer(c: Canvas, w: Float, h: Float) {
        val panel = RectF(w * .035f, h * .12f, w * .965f, h * .94f)
        paint.color = 0xE6052B68.toInt()
        c.drawRoundRect(panel, h * .035f, h * .035f, paint)
        text(c, if (editingFavorites) "首页常用应用" else "应用", w * .075f, h * .215f, h * .042f, -1, true)
        paint.textAlign = Paint.Align.RIGHT
        text(c, if (editingFavorites) "完成" else "管理", w * .925f, h * .215f, h * .027f, 0xFFBDEBFF.toInt(), true)
        paint.textAlign = Paint.Align.LEFT

        val metrics = drawerMetrics()
        val cols = metrics.columns
        val gridLeft = metrics.gridLeft
        val gridRight = metrics.gridRight
        val cellW = metrics.cellWidth
        val cellH = metrics.cellHeight
        val contentTop = metrics.contentTop
        val contentBottom = metrics.contentBottom
        val visibleRows = metrics.rows
        val totalRows = metrics.totalRows(apps.size)
        val maxScroll = metrics.maxScroll(apps.size)
        drawerScrollRow = drawerScrollRow.coerceIn(0, maxScroll)
        val clipSave = c.save()
        c.clipRect(gridLeft, contentTop, gridRight, contentBottom)
        apps.forEachIndexed { i, app ->
            val row = i / cols
            val col = i % cols
            val left = gridLeft + col * cellW
            val top = contentTop + (row - drawerScrollRow) * cellH
            val cell = RectF(left + cellW * .035f, top + cellH * .025f,
                left + cellW * .965f, top + cellH * .965f)
            // Icon frame and title line are separate visual elements.
            // Drawer uses standalone icons and the same persisted size scale as the desktop.
            val iconSize = minOf(metrics.iconSize(cell.width()) * homeIconScale, cellW * .84f, cellH * .76f)
            val iconTop = cell.top + (cellH * .78f - iconSize) / 2f
            val iconRect = RectF(cell.centerX() - iconSize / 2f, iconTop,
                cell.centerX() + iconSize / 2f, iconTop + iconSize)
            drawUnifiedAppIcon(c, app, iconRect, i == drawerIndex)
            paint.textAlign = Paint.Align.CENTER
            text(c, app.label.take(12), cell.centerX(), cell.bottom - cellH * .045f, h * .023f, -1, i == drawerIndex)
            paint.textAlign = Paint.Align.LEFT
        }
        c.restoreToCount(clipSave)
        if (totalRows > visibleRows) {
            paint.textAlign = Paint.Align.CENTER
            text(c, "${drawerScrollRow + 1}/${maxScroll + 1}", w * .5f, h * .91f, h * .018f, 0xFFBDEBFF.toInt())
            paint.textAlign = Paint.Align.LEFT
        }
        if (apps.isEmpty()) {
            paint.textAlign = Paint.Align.CENTER
            text(c, "暂无可启动的应用", w * .5f, h * .55f, h * .03f, -1)
            paint.textAlign = Paint.Align.LEFT
        }
    }


    private fun isCompactLayout(): Boolean = width < height

    private fun drawerColumns(): Int {
        val saved = prefs.getInt("drawer_columns", 0)
        if (saved in 4..6) return saved
        return when {
            width >= 3000 -> 6
            width >= 1800 -> 5
            else -> 4
        }
    }

    private fun openLauncherSettings() {
        isDrawerOpen = false
        isSettingsOpen = true
        settingsFocus = 8
        invalidate()
    }

    private fun moveSettingsFocus(direction: Int) {
        settingsFocus = SettingsFocusNavigator.next(settingsFocus, direction)
    }

    private fun drawerMetrics(): DrawerGridMetrics =
        DrawerGridMetrics(width.toFloat(), height.toFloat(), drawerColumns(), drawerRows())

    private fun drawerRows(): Int = prefs.getInt("drawer_rows", 2).coerceIn(2, 4)

    private fun setDrawerRows(rows: Int) {
        prefs.edit().putInt("drawer_rows", rows.coerceIn(2, 4)).apply()
        settingsFocus = rows + 15
        invalidate()
    }
    private fun setDrawerColumns(columns: Int) {
        prefs.edit().putInt("drawer_columns", columns.coerceIn(4, 6)).apply()
        settingsFocus = columns - 4
        invalidate()
    }

    private fun clearWallpaper() {
        AlertDialog.Builder(context)
            .setTitle("恢复默认壁纸")
            .setMessage("将移除当前选择的图片壁纸，恢复小李桌面的默认背景。")
            .setNegativeButton("取消", null)
            .setPositiveButton("恢复") { _, _ ->
                wallpaperBitmap?.recycle()
                wallpaperBitmap = null
                prefs.edit().remove("wallpaper_uri").apply()
                invalidate()
            }
            .show()
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

    private fun isDefaultHomeLauncher(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true
        } else {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName == context.packageName
        }
    }
    private fun drawLauncherSettings(c: Canvas, w: Float, h: Float) {
        paint.color = 0xE6052B68.toInt()
        c.drawRoundRect(RectF(w * .08f, h * .14f, w * .92f, h * .97f), 30f, 30f, paint)
        text(c, "小李桌面", w * .13f, h * .235f, h * .04f, -1, true)
        text(c, "桌面设置  ·  个性化与设备管理（长按菜单键可编辑组件）", w * .13f, h * .265f, h * .021f, 0xFFDDEEFF.toInt())
        val iconSizeSetting = RectF(w * .13f, h * .275f, w * .64f, h * .37f)
        paint.color = if (settingsFocus == 8) 0xFF31B9FF.toInt() else 0x5544BFFF
        c.drawRoundRect(iconSizeSetting, 16f, 16f, paint)
        text(c, "应用图标大小：" + (homeIconScale * 100).toInt() + "%  ·  点击调整",
            w * .16f, h * .335f, h * .025f, -1, true)
        val widgetEditCard = RectF(w * .66f, h * .275f, w * .87f, h * .37f)
        paint.color = if (settingsFocus == 22) 0xFF31B9FF.toInt() else 0x5544BFFF
        c.drawRoundRect(widgetEditCard, 16f, 16f, paint)
        paint.textAlign = Paint.Align.CENTER
        text(c, "组件布局编辑", widgetEditCard.centerX(), h * .335f, h * .022f, -1, true)
        paint.textAlign = Paint.Align.LEFT
        text(c, "桌面信息显示", w * .13f, h * .397f, h * .021f, 0xFFDDEEFF.toInt())
        val labels = listOf(if (showClock) "时间：开" else "时间：关",
            if (showDate) "日期：开" else "日期：关",
            if (use24HourClock) "24小时制" else "12小时制",
            if (showClockSeconds) "显示秒数" else "隐藏秒数")
        val componentKeys = listOf(9, 10, 15, 16)
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
        text(c, "应用抽屉网格", w * .13f, h * .492f, h * .023f, 0xFFDDEEFF.toInt())
        for (i in 0..2) {
            val columns = i + 4
            val left = w * (.13f + i * .105f)
            val rect = RectF(left, h * .515f, left + w * .095f, h * .575f)
            paint.color = if (drawerColumns() == columns) 0xFF31B9FF.toInt() else 0x44FFFFFF
            c.drawRoundRect(rect, 14f, 14f, paint)
            if (settingsFocus == i) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = (h * .0035f).coerceAtLeast(2f)
                paint.color = 0xFF8BE7FF.toInt()
                c.drawRoundRect(rect, 14f, 14f, paint)
                paint.style = Paint.Style.FILL
            }
            paint.textAlign = Paint.Align.CENTER
            text(c, "${columns}列", rect.centerX(), h * .553f, h * .021f, -1, true)
            paint.textAlign = Paint.Align.LEFT
        }
        for (i in 0..2) {
            val rows = i + 2
            val left = w * (.55f + i * .105f)
            val rect = RectF(left, h * .515f, left + w * .095f, h * .575f)
            paint.color = if (drawerRows() == rows) 0xFF31B9FF.toInt() else 0x44FFFFFF
            c.drawRoundRect(rect, 14f, 14f, paint)
            if (settingsFocus == rows + 15) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = (h * .0035f).coerceAtLeast(2f)
                paint.color = 0xFF8BE7FF.toInt()
                c.drawRoundRect(rect, 14f, 14f, paint)
                paint.style = Paint.Style.FILL
            }
            paint.textAlign = Paint.Align.CENTER
            text(c, "${rows}行", rect.centerX(), h * .553f, h * .021f, -1, true)
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
        paint.color = 0xB8052B68.toInt()
        c.drawRoundRect(RectF(preview.left, preview.bottom - h * .035f, preview.right, preview.bottom), 10f, 10f, paint)
        paint.textAlign = Paint.Align.CENTER
        text(c, "恢复默认壁纸", preview.centerX(), preview.bottom - h * .011f, h * .016f, -1, true)
        paint.textAlign = Paint.Align.LEFT
        if (settingsFocus == 5) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = h * .003f
            paint.color = 0xFF75D7FF.toInt()
            c.drawRoundRect(preview, 16f, 16f, paint)
            paint.style = Paint.Style.FILL
        }

        val card = RectF(w * .13f, h * .70f, w * .49f, h * .84f)
        paint.color = if (settingsFocus == 6) 0xFF167FD8.toInt() else 0x553AAFFF
        c.drawRoundRect(card, 20f, 20f, paint)
        text(c, "⇄  小李全联 · 快传中心", w * .16f, h * .755f, h * .027f, -1, true)
        text(c, "发送 · 接收 · 附近设备 · 传输历史", w * .16f, h * .805f, h * .019f, 0xFFDDEEFF.toInt())
        val appsCard = RectF(w * .52f, h * .70f, w * .87f, h * .84f)
        paint.color = if (settingsFocus == 21) 0xFF167FD8.toInt() else 0x553AAFFF
        c.drawRoundRect(appsCard, 20f, 20f, paint)
        text(c, "▦  应用管理", w * .56f, h * .755f, h * .027f, -1, true)
        text(c, "选择首页常用应用", w * .56f, h * .805f, h * .019f, 0xFFDDEEFF.toInt())

        val sys = RectF(w * .13f, h * .865f, w * .36f, h * .945f)
        paint.color = if (settingsFocus == 7) 0xFF31B9FF.toInt() else 0x44FFFFFF
        c.drawRoundRect(sys, 16f, 16f, paint)
        paint.textAlign = Paint.Align.CENTER
        text(c, "系统设置", sys.centerX(), h * .92f, h * .024f, -1, true)
        val home = RectF(w * .385f, h * .865f, w * .64f, h * .945f)
        paint.color = if (settingsFocus == 14) 0xFF31B9FF.toInt() else 0x44FFFFFF
        c.drawRoundRect(home, 16f, 16f, paint)
        val isDefaultHome = isDefaultHomeLauncher()
        text(c, if (isDefaultHome) "当前默认桌面" else "设为默认桌面",
            home.centerX(), h * .92f, h * .022f, -1, true)
        val exit = RectF(w * .665f, h * .865f, w * .87f, h * .945f)
        paint.color = if (settingsFocus == 13) 0xFF31B9FF.toInt() else 0x44FFFFFF
        c.drawRoundRect(exit, 16f, 16f, paint)
        text(c, "返回", exit.centerX(), h * .92f, h * .024f, -1, true)
        paint.textAlign = Paint.Align.LEFT
    }

    // Full hit target includes the label line; only homeIconCardRect is painted.
    private fun homeItemRect(index: Int, w: Float, h: Float): RectF {
        val icon = homeIconCardRect(index, w, h)
        return RectF(icon.left - w * .018f, icon.top - h * .012f, icon.right + w * .018f, icon.bottom + h * .055f)
    }

    private fun homeIconCardRect(index: Int, w: Float, h: Float): RectF {
        val baseSize = minOf(w * (if (w >= h) .105f else .19f), h * (if (w >= h) .19f else .105f))
        val size = baseSize * homeIconScale
        if (w >= h) {
            val gap = w * .035f
            val total = favorites.size * size + (favorites.size - 1).coerceAtLeast(0) * gap
            val left = (w - total) / 2f + index * (size + gap)
            return RectF(left, h * .59f, left + size, h * .59f + size)
        }
        val cols = 3
        val gapX = w * .055f
        val gapY = h * .055f
        val total = cols * size + (cols - 1) * gapX
        val startX = (w - total) / 2f
        val rows = (favorites.size + cols - 1) / cols
        val totalH = rows * (size + h * .065f) + (rows - 1).coerceAtLeast(0) * gapY
        val startY = (h - totalH) / 2f + h * .08f
        val left = startX + (index % cols) * (size + gapX)
        val top = startY + (index / cols) * (size + h * .065f + gapY)
        return RectF(left, top, left + size, top + size)
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
        val nx = (x / width).coerceIn(.025f, .985f)
        val ny = (y / height).coerceIn(.035f, .94f)
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
        val scale = value.coerceIn(.55f, 2.4f)
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

    private fun drawerItemIndexAt(x: Float, y: Float): Int =
        drawerMetrics().indexAt(x, y, drawerScrollRow, apps.size)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_SCROLL -> {
                // Mouse wheel over a desktop app icon adjusts the shared desktop/drawer icon size.
                if (!isDrawerOpen && !isSettingsOpen && homeItemIndexAt(event.x, event.y) >= 0) {
                    val wheel = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                    if (wheel != 0f) {
                        homeIconScale = (homeIconScale + wheel * .06f).coerceIn(.65f, 1.6f)
                        prefs.edit().putFloat("home_icon_scale", homeIconScale).apply()
                        invalidate()
                    }
                    return true
                }
                return false
            }
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
                if (!isDrawerOpen && !isSettingsOpen && lastDragIndex >= 0 && event.pointerCount >= 2) {
                    val dx = event.getX(1) - event.getX(0)
                    val dy = event.getY(1) - event.getY(0)
                    homeIconResizeStartSpan = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
                    homeIconResizeStartScale = homeIconScale
                    resizingHomeIcons = true
                    return true
                }
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
                if (resizingHomeIcons && event.pointerCount >= 2) {
                    val dx = event.getX(1) - event.getX(0)
                    val dy = event.getY(1) - event.getY(0)
                    val span = kotlin.math.sqrt(dx * dx + dy * dy)
                    homeIconScale = (homeIconResizeStartScale * span / homeIconResizeStartSpan).coerceIn(.65f, 1.6f)
                    invalidate()
                    return true
                }
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
                if (resizingHomeIcons) {
                    prefs.edit().putFloat("home_icon_scale", homeIconScale).apply()
                    resizingHomeIcons = false
                    performClick()
                    return true
                }
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
                    if (event.y in (height * .275f)..(height * .37f) && event.x in (width * .13f)..(width * .64f)) {
                        settingsFocus = 8
                        cycleHomeIconScale()
                    } else if (event.y in (height * .275f)..(height * .37f) && event.x in (width * .66f)..(width * .87f)) {
                        settingsFocus = 22; isSettingsOpen = false; toggleWidgetEditMode()
                    } else if (event.y in (height * .415f)..(height * .465f)) {
                        val componentGap = width * .009f
                        val componentW = (width * .74f - componentGap * 5f) / 6f
                        val selected = ((event.x - width * .13f) / (componentW + componentGap)).toInt()
                        val componentKeys = listOf(9, 10, 15, 16)
                        if (selected in componentKeys.indices) settingsFocus = componentKeys[selected]
                        when (selected) {
                            0 -> toggleHomeComponent(9)
                            1 -> toggleHomeComponent(10)
                            2 -> { use24HourClock = !use24HourClock; prefs.edit().putBoolean("clock_24_hour", use24HourClock).apply() }
                            3 -> { showClockSeconds = !showClockSeconds; prefs.edit().putBoolean("clock_seconds", showClockSeconds).apply(); updateClockTick() }
                        }
                    } else if (event.y in (height * .515f)..(height * .575f)) {
                        when {
                            event.x in (width * .13f)..(width * .445f) -> {
                                val selected = ((event.x - width * .13f) / (width * .105f)).toInt()
                                if (selected in 0..2) setDrawerColumns(selected + 4)
                            }
                            event.x in (width * .55f)..(width * .865f) -> {
                                val selected = ((event.x - width * .55f) / (width * .105f)).toInt()
                                if (selected in 0..2) setDrawerRows(selected + 2)
                            }
                        }
                    } else if (event.y in (height * .59f)..(height * .68f) && event.x in (width * .13f)..(width * .35f)) {
                        settingsFocus = 3
                        onChooseWallpaper?.invoke()
                    } else if (event.y in (height * .59f)..(height * .68f) && event.x in (width * .38f)..(width * .62f)) {
                        settingsFocus = 4
                        toggleDynamicWallpaper()
                    } else if (event.y in (height * .59f)..(height * .68f) && event.x in (width * .65f)..(width * .87f)) {
                        settingsFocus = 5
                        clearWallpaper()
                    } else if (event.y in (height * .70f)..(height * .84f) && event.x in (width * .13f)..(width * .49f)) {
                        settingsFocus = 6
                        context.startActivity(Intent(context, com.xiaoli.quanlian.QuanLianActivity::class.java))
                    } else if (event.y in (height * .70f)..(height * .84f) && event.x in (width * .52f)..(width * .87f)) {
                        settingsFocus = 21
                        isSettingsOpen = false; isDrawerOpen = true; editingFavorites = true; drawerIndex = 0; drawerScrollRow = 0
                    } else if (event.y in (height * .865f)..(height * .945f)) {
                        when {
                            event.x in (width * .13f)..(width * .36f) -> { settingsFocus = 7; runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                            event.x in (width * .385f)..(width * .64f) -> { settingsFocus = 14; onRequestDefaultHome?.invoke() }
                            event.x in (width * .665f)..(width * .87f) -> { settingsFocus = 13; closeLauncherSettings() }
                        }
                    }
                } else if (isDrawerOpen) {
                    if (event.y in (height * .19f)..(height * .29f) && event.x > width * .68f) {
                        editingFavorites = !editingFavorites
                    } else {
                        val index = drawerItemIndexAt(event.x, event.y)
                        if (index >= 0) {
                            drawerIndex = index
                            val app = apps[index]
                            if (editingFavorites) {
                                val exists = favorites.any { it.packageName == app.packageName }
                                if (exists || favorites.size < 5) toggleFavorite(app)
                                else android.widget.Toast.makeText(context, "首页最多固定 5 个应用，请先移除一个", android.widget.Toast.LENGTH_SHORT).show()
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
                    drawerScrollRow = 0
                } else if (kotlin.math.abs(dx) > width * .08f && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                    if (favorites.isNotEmpty()) {
                        homeIndex = if (dx < 0) (homeIndex + 1).coerceAtMost(favorites.lastIndex)
                            else (homeIndex - 1).coerceAtLeast(0)
                    }
                } else {
                    if (settingsShortcutRect(width.toFloat(), height.toFloat()).contains(event.x, event.y)) {
                        openLauncherSettings()
                        invalidate()
                        return true
                    }
                    if (event.x > width * .70f && event.x < width * .88f && event.y < height * .16f) {
                        runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                        return true
                    }
                    if (showWeather && widgetIndexAt(event.x, event.y) == 3) {
                        openWeatherCityDialog()
                        invalidate()
                        return true
                    }
                    val index = homeItemIndexAt(event.x, event.y)
                    if (index >= 0) {
                        homeIndex = index
                        favorites.getOrNull(index)?.let { runCatching { context.startActivity(it.intent) } }
                    } else if (event.y > height * .78f && event.x < width * .42f) {
                        isDrawerOpen = true
                        drawerIndex = 0
                        drawerScrollRow = 0
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
                resizingHomeIcons = false
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
                KeyEvent.KEYCODE_DPAD_LEFT -> moveSettingsFocus(-1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> moveSettingsFocus(1)
                KeyEvent.KEYCODE_DPAD_UP -> moveSettingsFocus(-2)
                KeyEvent.KEYCODE_DPAD_DOWN -> moveSettingsFocus(2)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (settingsFocus == 9 || settingsFocus == 10) toggleHomeComponent(settingsFocus)
                    else if (settingsFocus == 15) {
                        use24HourClock = !use24HourClock
                        prefs.edit().putBoolean("clock_24_hour", use24HourClock).apply()
                    } else if (settingsFocus == 16) {
                        showClockSeconds = !showClockSeconds
                        prefs.edit().putBoolean("clock_seconds", showClockSeconds).apply()
                        updateClockTick()
                    } else if (settingsFocus == 8) openWeatherCityDialog()
                    else if (settingsFocus == 22) { isSettingsOpen = false; toggleWidgetEditMode() }
                    else if (settingsFocus in 0..2) setDrawerColumns(settingsFocus + 4)
                    else if (settingsFocus in 17..19) setDrawerRows(settingsFocus - 15)
                    else if (settingsFocus == 3) onChooseWallpaper?.invoke()
                    else if (settingsFocus == 4) toggleDynamicWallpaper()
                    else if (settingsFocus == 5) clearWallpaper()
                    else if (settingsFocus == 6) context.startActivity(Intent(context, com.xiaoli.quanlian.QuanLianActivity::class.java))
                    else if (settingsFocus == 21) { isSettingsOpen = false; isDrawerOpen = true; editingFavorites = true; drawerIndex = 0; drawerScrollRow = 0 }
                    else if (settingsFocus == 13) closeLauncherSettings()
                    else if (settingsFocus == 7) runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
            invalidate(); return true
        }
        if (isDrawerOpen) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> { closeDrawer(); return true }
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> editingFavorites = !editingFavorites
                KeyEvent.KEYCODE_DPAD_LEFT -> drawerIndex = drawerMetrics().moveFocus(drawerIndex, -1, apps.size)
                KeyEvent.KEYCODE_DPAD_RIGHT -> drawerIndex = drawerMetrics().moveFocus(drawerIndex, 1, apps.size)
                KeyEvent.KEYCODE_DPAD_UP -> drawerIndex = drawerMetrics().moveFocus(drawerIndex, -2, apps.size)
                KeyEvent.KEYCODE_DPAD_DOWN -> drawerIndex = drawerMetrics().moveFocus(drawerIndex, 2, apps.size)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    apps.getOrNull(drawerIndex)?.let { app ->
                        if (editingFavorites) {
                            val exists = favorites.any { it.packageName == app.packageName }
                            if (exists || favorites.size < 5) toggleFavorite(app)
                            else android.widget.Toast.makeText(context, "首页最多固定 5 个应用，请先移除一个", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            runCatching { context.startActivity(app.intent) }
                        }
                    }
                }
            }
            val metrics = drawerMetrics()
            val cols = metrics.columns
            val visibleRows = metrics.rows
            val maxScroll = metrics.maxScroll(apps.size)
            drawerScrollRow = (drawerIndex / cols - visibleRows + 1).coerceIn(0, maxScroll)
            invalidate(); return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_MENU -> { openLauncherSettings(); return true }
            KeyEvent.KEYCODE_SETTINGS -> { toggleWidgetEditMode(); return true }
            KeyEvent.KEYCODE_DPAD_UP -> {
                isDrawerOpen = true
                drawerIndex = 0
                drawerScrollRow = 0
                invalidate()
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (favorites.isNotEmpty()) homeIndex = (homeIndex - 1).coerceAtLeast(0)
                invalidate()
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (favorites.isNotEmpty()) homeIndex = (homeIndex + 1).coerceAtMost(favorites.lastIndex)
                invalidate()
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
