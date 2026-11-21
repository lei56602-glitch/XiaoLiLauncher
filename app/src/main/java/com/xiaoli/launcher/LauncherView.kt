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
import android.media.AudioManager
import android.media.tv.TvInputManager
import android.media.tv.TvInputInfo
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
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
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
    private var clockEditMenuOpen = false
    private var clockComponentEditMode = false
    private var clockEditMode = "resize"
    private var clockEditChoice = 0
    private var clockEditOriginal: List<Float> = emptyList()
    private var selectedEditWidget = 0
    private var resizeStartSpan = 0f
    private var resizeStartScale = 1f
    private var activeWidgetDrag = -1
    private var widgetDragStarted = false
    private var widgetDragOffsetX = 0f
    private var widgetDragOffsetY = 0f
    var onChooseWallpaper: (() -> Unit)? = null
    var onRequestDefaultHome: (() -> Unit)? = null
    private val apps = mutableListOf<AppEntry>()
    private val favorites = mutableListOf<AppEntry>()
    private val emptySlot = AppEntry("", "__EMPTY_SLOT__", Intent())
    private var actionMenuOpen = false
    private var actionMenuSource = "home"
    private var actionMenuIndex = 0
    private var actionMenuChoice = 0
    private var actionMenuPackage = ""
    private val homeIconPositions = mutableMapOf<String, Pair<Float, Float>>()
    private val appIconCache = mutableMapOf<String, android.graphics.drawable.Drawable>()
    private var drawerIndex = 0
    private var drawerScrollRow = 0
    private var drawerScrollVisualRow = 0f
    private var drawerScrollAnimator: ValueAnimator? = null
    private var spatialFocusAnimator: ValueAnimator? = null
    private var spatialFocusScale = 1f
    // Header action is a first-class D-pad target, separate from the app grid.
    private var drawerHeaderFocused = false
    private var homeIndex = 0
    // Home-screen top widgets are independent D-pad focus targets: clock, Wi-Fi, settings.
    // -1 keeps focus on the five launcher app slots.
    private var homeTopFocus = -1
    private var homeIconScale = prefs.getFloat("home_icon_scale", 1f).coerceIn(.65f, 1.6f)
    private var homeIconEditMode = false
    private var homePositionEditMode = false
    private var homePositionEditPackage = ""
    private var homePositionEditOriginal: Pair<Float, Float>? = null
    private var homePositionWasCustom = false
    private var homeAppManageMode = false
    private var homeManageSlot = 0
    private var homeIconScaleBeforeEdit = 1f
    private var resizingHomeIcons = false
    private var homeIconResizeStartSpan = 0f
    private var homeIconResizeStartScale = 1f
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDragging = false
    private var lastDragIndex = -1
    private var homeAppDragOffsetX = 0f
    private var homeAppDragOffsetY = 0f
    var isDrawerOpen = false
        private set
    private var isSettingsOpen = false
    // Unified Aerial-style glass control center. Page 0 is the overview; page 1 is inputs.
    private var controlCenterOpen = false
    private var controlCenterPage = 0
    private var controlCenterFocus = 0
    private var controlCenterReturnFocus = -1
    private data class ControlInput(val id: String, val label: String, val kind: String, val setupIntent: Intent?)
    private var controlInputs: List<ControlInput> = emptyList()
    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var settingsReturnHomeFocus = -1
    private var returnToSettingsAfterDrawer = false
    val isLauncherSettingsOpen: Boolean get() = isSettingsOpen
    private var settingsFocus = 0
    private val handler = Handler(Looper.getMainLooper())
    private val okLongPressRunnable = Runnable {
        if (okPressPending && !okLongPressTriggered) {
            okLongPressTriggered = true
            longPressReleaseConsumed = true
            when (okPressSource) {
                "home" -> favorites.getOrNull(okPressIndex)?.takeIf { it.packageName != emptySlot.packageName }
                    ?.let { openAppActionMenu("home", okPressIndex, it) }
                "drawer" -> if (isDrawerOpen && !drawerHeaderFocused)
                    apps.getOrNull(okPressIndex)?.let { openAppActionMenu("drawer", okPressIndex, it) }
            }
            invalidate()
        }
    }

    private fun animateSpatialFocus() {
        spatialFocusAnimator?.cancel()
        spatialFocusScale = 1f
        spatialFocusAnimator = ValueAnimator.ofFloat(1f, 1.08f).apply {
            duration = 150L
            interpolator = DecelerateInterpolator(1.2f)
            addUpdateListener { animator ->
                spatialFocusScale = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun scaledFocusRect(rect: RectF): RectF {
        val scale = spatialFocusScale.coerceAtLeast(1f)
        val halfWidth = rect.width() * scale / 2f
        val halfHeight = rect.height() * scale / 2f
        return RectF(rect.centerX() - halfWidth, rect.centerY() - halfHeight,
            rect.centerX() + halfWidth, rect.centerY() + halfHeight)
    }

    private fun animateDrawerScrollTo(targetRow: Int) {
        drawerScrollAnimator?.cancel()
        val target = targetRow.toFloat().coerceAtLeast(0f)
        if (kotlin.math.abs(drawerScrollVisualRow - target) < .01f) {
            drawerScrollVisualRow = target
            invalidate()
            return
        }
        drawerScrollAnimator = ValueAnimator.ofFloat(drawerScrollVisualRow, target).apply {
            duration = 260L
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { animator ->
                drawerScrollVisualRow = animator.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun resetDrawerScroll() {
        drawerScrollAnimator?.cancel()
        drawerScrollRow = 0
        drawerScrollVisualRow = 0f
    }

    private fun beginOkPressTracking(source: String, index: Int) {
        handler.removeCallbacks(okLongPressRunnable)
        okPressPending = true
        okLongPressTriggered = false
        okPressSource = source
        okPressIndex = index
        handler.postDelayed(okLongPressRunnable, 550L)
    }

    private fun finishOkPressTracking(): Boolean {
        handler.removeCallbacks(okLongPressRunnable)
        okPressPending = false
        val wasLongPress = okLongPressTriggered
        okLongPressTriggered = false
        okPressSource = ""
        okPressIndex = -1
        return wasLongPress
    }
    private val clockTick = object : Runnable {
        override fun run() {
            invalidate()
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
        handler.post(clockTick)
        updateAnimationLoop()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // The launcher is a single custom-drawn surface; keep it as the TV remote key target.
        isFocusable = true
        isFocusableInTouchMode = true
        requestFocus()
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

    fun refreshInstalledApps() {
        appIconCache.clear()
        loadApps()
        invalidate()
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
        homeIconPositions.clear()
        runCatching {
            val stored = JSONObject(prefs.getString("home_icon_positions", "{}") ?: "{}")
            resolved.forEach { app ->
                val position = stored.optJSONObject(app.packageName) ?: return@forEach
                if (position.has("x") && position.has("y")) {
                    homeIconPositions[app.packageName] = Pair(
                        position.optDouble("x", 0.5).toFloat().coerceIn(0f, 1f),
                        position.optDouble("y", 0.6).toFloat().coerceIn(0f, 1f)
                    )
                }
            }
        }
        val saved = prefs.getString("favorites", null)
        favorites.clear()
        val byPackage = apps.associateBy { it.packageName }
        if (saved == null) favorites.addAll(apps.take(5))
        else saved.split("|").take(5).forEach { pkg ->
            favorites.add(if (pkg.isBlank()) emptySlot else byPackage[pkg] ?: emptySlot)
        }
        while (favorites.size < 5) favorites.add(emptySlot)
        homeIndex = homeIndex.coerceIn(0, 4)
    }

    private var editingFavorites = false
    // A timer fallback makes long-OK behavior reliable on TV remotes that do not
    // consistently dispatch Android's onKeyLongPress callback.
    private var okPressPending = false
    private var okLongPressTriggered = false
    private var okPressSource = ""
    private var okPressIndex = -1
    private var actionMenuSelectionConsumed = false
    private var longPressReleaseConsumed = false

    private fun saveFavorites() {
        while (favorites.size < 5) favorites.add(emptySlot)
        prefs.edit().putString("favorites", favorites.take(5).joinToString("|") {
            if (it.packageName == emptySlot.packageName) "" else it.packageName
        }).apply()
        homeIndex = homeIndex.coerceIn(0, 4)
    }

    private fun toggleFavorite(app: AppEntry) {
        val existing = favorites.indexOfFirst { it.packageName == app.packageName }
        if (existing >= 0) favorites[existing] = emptySlot
        else {
            val target = favorites.indexOfFirst { it.packageName == emptySlot.packageName }
            if (target >= 0) favorites[target] = app
        }
        saveFavorites()
    }

    private fun replaceFavoriteAt(slot: Int, app: AppEntry): Boolean {
        if (slot !in 0..4) return false
        val existing = favorites.indexOfFirst { it.packageName == app.packageName }
        if (existing == slot) return false
        if (existing >= 0) {
            val previous = favorites[slot]
            favorites[slot] = app
            favorites[existing] = previous
        } else favorites[slot] = app
        saveFavorites()
        return true
    }

    private fun chooseDrawerApp(app: AppEntry) {
        if (homeAppManageMode) {
            val changed = replaceFavoriteAt(homeManageSlot, app)
            android.widget.Toast.makeText(context,
                if (changed) "已加入桌面第 ${homeManageSlot + 1} 个位置" else "该应用已在此位置",
                android.widget.Toast.LENGTH_SHORT).show()
            closeDrawer()
            return
        }
        if (editingFavorites) {
            if (favorites.any { it.packageName == app.packageName }) {
                android.widget.Toast.makeText(context, "该应用已经在桌面", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                val slot = favorites.indexOfFirst { it.packageName == emptySlot.packageName }
                if (slot >= 0) {
                    favorites[slot] = app
                    saveFavorites()
                    android.widget.Toast.makeText(context, "已加入桌面第 ${slot + 1} 个位置", android.widget.Toast.LENGTH_SHORT).show()
                } else android.widget.Toast.makeText(context, "桌面已满，请先移除一个应用", android.widget.Toast.LENGTH_SHORT).show()
            }
        } else runCatching { context.startActivity(app.intent) }
    }

    private fun hideDesktopApp(slot: Int) {
        if (slot !in 0..4 || favorites[slot].packageName == emptySlot.packageName) return
        favorites[slot] = emptySlot
        saveFavorites()
        android.widget.Toast.makeText(context, "应用已从桌面移除，可在应用抽屉中重新添加", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun beginHomePositionEdit(slot: Int) {
        val app = favorites.getOrNull(slot) ?: return
        if (app.packageName == emptySlot.packageName || width <= 0 || height <= 0) return
        val rect = homeIconCardRect(slot, width.toFloat(), height.toFloat())
        homePositionEditPackage = app.packageName
        homePositionWasCustom = homeIconPositions.containsKey(app.packageName)
        homePositionEditOriginal = homeIconPositions[app.packageName]
            ?: Pair(rect.left / width.toFloat(), rect.top / height.toFloat())
        homeIconPositions[app.packageName] = homePositionEditOriginal!!
        homeIndex = slot
        homePositionEditMode = true
        invalidate()
    }

    private fun moveSelectedHomeIcon(dx: Float, dy: Float) {
        if (!homePositionEditMode || width <= 0 || height <= 0) return
        val current = homeIconPositions[homePositionEditPackage] ?: return
        val size = homeIconCardRect(homeIndex, width.toFloat(), height.toFloat()).width()
        val maxX = ((width.toFloat() - size) / width.toFloat()).coerceAtLeast(0f)
        val maxY = ((height.toFloat() - size) / height.toFloat()).coerceAtLeast(0f)
        homeIconPositions[homePositionEditPackage] = Pair(
            (current.first + dx * .01f).coerceIn(0f, maxX),
            (current.second + dy * .01f).coerceIn(0f, maxY)
        )
        invalidate()
    }

    private fun finishHomePositionEdit(save: Boolean) {
        if (!homePositionEditMode) return
        if (save) saveHomeIconPositions()
        else if (homePositionWasCustom) {
            homePositionEditOriginal?.let { homeIconPositions[homePositionEditPackage] = it }
        } else homeIconPositions.remove(homePositionEditPackage)
        homePositionEditMode = false
        homePositionEditPackage = ""
        homePositionEditOriginal = null
        homePositionWasCustom = false
        invalidate()
    }

    private fun openAppActionMenu(source: String, index: Int, app: AppEntry) {
        actionMenuSource = source
        actionMenuIndex = index
        actionMenuPackage = app.packageName
        actionMenuChoice = 0
        actionMenuOpen = true
        animateSpatialFocus()
        invalidate()
    }

    private fun runAppAction() {
        val app = apps.firstOrNull { it.packageName == actionMenuPackage }
        if (actionMenuSource == "home") {
            when (actionMenuChoice) {
                0 -> {
                    homeManageSlot = actionMenuIndex
                    actionMenuOpen = false
                    homeAppManageMode = true
                    isDrawerOpen = true
                    editingFavorites = true
                    drawerIndex = 0
                    resetDrawerScroll()
                }
                1 -> { hideDesktopApp(actionMenuIndex); actionMenuOpen = false }
                2 -> { actionMenuOpen = false; beginHomePositionEdit(actionMenuIndex) }
                else -> {
                    actionMenuOpen = false
                    isDrawerOpen = true
                    editingFavorites = false
                    drawerIndex = 0
                    resetDrawerScroll()
                }
            }
        } else {
            if (actionMenuChoice == 0 && app != null) {
                val existing = favorites.indexOfFirst { it.packageName == app.packageName }
                if (existing >= 0) {
                    favorites[existing] = emptySlot
                    saveFavorites()
                    android.widget.Toast.makeText(context, "已从主屏幕移除", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    val slot = favorites.indexOfFirst { it.packageName == emptySlot.packageName }
                    if (slot >= 0) {
                        favorites[slot] = app
                        saveFavorites()
                        android.widget.Toast.makeText(context, "已加入桌面第 ${slot + 1} 个位置", android.widget.Toast.LENGTH_SHORT).show()
                    } else android.widget.Toast.makeText(context, "桌面已满，请先移除一个应用", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            actionMenuOpen = false
        }
        invalidate()
    }

    private fun drawAppActionMenu(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.color = 0xB9001028.toInt()
        c.drawRect(0f, 0f, w, h, paint)
        val panel = if (actionMenuSource == "home") RectF(w * .34f, h * .20f, w * .66f, h * .80f)
            else RectF(w * .34f, h * .31f, w * .66f, h * .70f)
        paint.color = 0xF20A3470.toInt()
        c.drawRoundRect(panel, h * .025f, h * .025f, paint)
        val drawerAppAlreadyPinned = actionMenuSource == "drawer" && favorites.any { it.packageName == actionMenuPackage }
        val options = if (actionMenuSource == "home") listOf("移动 APP", "隐藏 APP", "调整位置", "返回抽屉") else listOf(if (drawerAppAlreadyPinned) "从主屏幕移除" else "加入主屏幕", "返回")
        text(c, if (actionMenuSource == "home") "桌面应用管理" else "添加到主屏幕",
            panel.left + panel.width() * .09f, panel.top + panel.height() * (if (actionMenuSource == "home") .16f else .20f), h * .031f, -1, true)
        options.forEachIndexed { i, label ->
            val top = panel.top + panel.height() * (if (actionMenuSource == "home") .25f + i * .17f else .29f + i * .22f)
            val rowHeight = panel.height() * (if (actionMenuSource == "home") .14f else .18f)
            val baseRow = RectF(panel.left + panel.width() * .07f, top,
                panel.right - panel.width() * .07f, top + rowHeight)
            val row = if (i == actionMenuChoice) scaledFocusRect(baseRow) else baseRow
            paint.color = if (i == actionMenuChoice) 0xFF1688E8.toInt() else 0x554D9BEA
            c.drawRoundRect(row, h * .012f, h * .012f, paint)
            paint.textAlign = Paint.Align.CENTER
            text(c, label, row.centerX(), row.centerY() + h * .009f, h * .026f, -1, i == actionMenuChoice)
            paint.textAlign = Paint.Align.LEFT
        }
    }

    fun handleBack(): Boolean {
        when {
            clockEditMenuOpen -> clockEditMenuOpen = false
            clockComponentEditMode -> finishClockComponentEdit(false)
            actionMenuOpen -> actionMenuOpen = false
            controlCenterOpen -> {
                if (controlCenterPage != 0) {
                    controlCenterPage = 0
                    controlCenterFocus = 0
                } else {
                    controlCenterOpen = false
                    homeTopFocus = controlCenterReturnFocus
                    controlCenterReturnFocus = -1
                    animateSpatialFocus()
                }
            }
            isSettingsOpen -> closeLauncherSettings()
            isDrawerOpen -> closeDrawer()
            widgetEditMode -> toggleWidgetEditMode()
            homePositionEditMode -> finishHomePositionEdit(false)
            homeIconEditMode -> {
                homeIconScale = homeIconScaleBeforeEdit
                homeIconEditMode = false
            }
            homeAppManageMode -> homeAppManageMode = false
            homeTopFocus in 0..1 -> homeTopFocus = -1
            else -> {
                // Back on the launcher home screen should not close the default Home activity.
                invalidate()
                return true
            }
        }
        invalidate()
        return true
    }

    fun closeDrawer() {
        isDrawerOpen = false
        drawerHeaderFocused = false
        editingFavorites = false
        if (homeAppManageMode) homeAppManageMode = false
        if (returnToSettingsAfterDrawer) {
            isSettingsOpen = true
            settingsFocus = 21
            returnToSettingsAfterDrawer = false
            animateSpatialFocus()
        }
        invalidate()
    }

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

    fun reloadSavedWallpaper() {
        wallpaperBitmap?.recycle()
        loadSavedWallpaper()
        invalidate()
    }

    private fun loadSavedWallpaper() {
        val defaultWallpaper = {
            BitmapFactory.decodeResource(context.resources, R.drawable.default_wallpaper)
        }
        val value = prefs.getString("wallpaper_uri", null)
        wallpaperBitmap = if (value.isNullOrBlank()) {
            defaultWallpaper()
        } else {
            val uri = runCatching { Uri.parse(value) }.getOrNull()
            uri?.let(::decodeWallpaper) ?: defaultWallpaper()
        }
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
    fun closeLauncherSettings() {
        isSettingsOpen = false
        when (settingsReturnHomeFocus) {
            in 100..101 -> homeTopFocus = settingsReturnHomeFocus - 100
            in 0..4 -> { homeIndex = settingsReturnHomeFocus; homeTopFocus = -1 }
        }
        settingsReturnHomeFocus = -1
        animateSpatialFocus()
        invalidate()
    }

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
        if (actionMenuOpen) drawAppActionMenu(canvas)
        if (controlCenterOpen) drawControlCenter(canvas)
        if (clockEditMenuOpen || clockComponentEditMode) drawClockEditOverlay(canvas)
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
        // Time and date are one desktop component: one anchor and one shared scale.
        if (showClock) text(c, time, w * clockX, height * clockY, height * .052f * clockScale, -1, true)
        if (showDate) text(c, date, w * clockX, height * (clockY + .05f * clockScale),
            height * .021f * clockScale, 0xFFE2F1FF.toInt())
        drawControlCenterShortcut(c, w, height.toFloat())
        drawHomeTopFocus(c, w, height.toFloat())
        paint.textAlign = Paint.Align.LEFT
        if (widgetEditMode) drawSelectedWidgetOutline(c)
    }

    private fun drawClockEditOverlay(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.color = 0xB8001024.toInt()
        c.drawRect(0f, 0f, w, h, paint)
        val panel = RectF(w * .34f, h * .30f, w * .66f, h * .70f)
        paint.color = 0xF20A2448.toInt()
        c.drawRoundRect(panel, h * .025f, h * .025f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (h * .0025f).coerceAtLeast(2f)
        paint.color = 0xFF55CFFF.toInt()
        c.drawRoundRect(panel, h * .025f, h * .025f, paint)
        paint.style = Paint.Style.FILL
        if (clockEditMenuOpen) {
            text(c, "时间与日历", panel.left + panel.width() * .10f, panel.top + panel.height() * .25f, h * .034f, -1, true)
            val labels = listOf("调整大小", "移动位置")
            labels.forEachIndexed { i, label ->
                val baseRow = RectF(panel.left + panel.width() * .09f, panel.top + panel.height() * (.36f + i * .28f),
                    panel.right - panel.width() * .09f, panel.top + panel.height() * (.57f + i * .28f))
                val row = if (clockEditChoice == i) scaledFocusRect(baseRow) else baseRow
                paint.color = if (clockEditChoice == i) 0xFF087FD1.toInt() else 0x554D9BEA
                c.drawRoundRect(row, h * .014f, h * .014f, paint)
                paint.textAlign = Paint.Align.CENTER
                text(c, label, row.centerX(), row.centerY() + h * .010f, h * .028f, -1, clockEditChoice == i)
                paint.textAlign = Paint.Align.LEFT
            }
            text(c, "方向键选择  ·  OK 确认  ·  返回取消", panel.left + panel.width() * .08f,
                panel.bottom - h * .025f, h * .016f, 0xFFD7E8F8.toInt())
        } else {
            val hint = if (clockEditMode == "resize") "左右键调整时间与日历大小" else "方向键移动整个时间与日历组件"
            text(c, hint, panel.left + panel.width() * .09f, panel.centerY(), h * .028f, -1, true)
            text(c, "OK 保存  ·  返回恢复原状", panel.left + panel.width() * .09f,
                panel.bottom - h * .07f, h * .018f, 0xFFD7E8F8.toInt())
        }
    }

    private fun openClockEditMenu() {
        clockEditMenuOpen = true
        clockEditChoice = 0
        animateSpatialFocus()
        invalidate()
    }

    private fun beginClockComponentEdit(mode: String) {
        selectedEditWidget = 0
        clockEditOriginal = listOf(clockX, clockY, clockScale, dateX, dateY, dateScale)
        clockEditMode = mode
        clockEditMenuOpen = false
        clockComponentEditMode = true
        invalidate()
    }

    private fun finishClockComponentEdit(save: Boolean) {
        if (!save && clockEditOriginal.size == 6) {
            clockX = clockEditOriginal[0]
            clockY = clockEditOriginal[1]
            clockScale = clockEditOriginal[2]
            dateX = clockEditOriginal[3]
            dateY = clockEditOriginal[4]
            dateScale = clockEditOriginal[5]
        }
        if (save) saveWidgetPositions()
        clockComponentEditMode = false
        clockEditOriginal = emptyList()
        invalidate()
    }

    private fun drawSelectedWidgetOutline(c: Canvas) {
        val visible = listOf(showClock || showDate, showLunar, showWeather)
        if (selectedEditWidget !in visible.indices || !visible[selectedEditWidget]) return
        val positions = listOf(Pair(clockX, clockY), Pair(lunarX, lunarY), Pair(weatherX, weatherY))
        val p = positions[selectedEditWidget]
        val bottomY = if (selectedEditWidget == 0) p.second + .05f * clockScale + .018f else p.second + .012f
        val leftInset = if (selectedEditWidget == 0) .012f else .39f
        val rightExtent = if (selectedEditWidget == 0) .20f else .025f
        val rect = RectF(width * (p.first - leftInset), height * (p.second - .045f),
            width * (p.first + rightExtent), height * bottomY)
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
        val widgetName = listOf("时间与日历", "农历", "天气")[selectedEditWidget]
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
            0 -> { clockX = .055f; clockY = .085f; dateX = clockX; dateY = clockY + .05f; clockScale = 1f; dateScale = 1f }
            1 -> { lunarX = .965f; lunarY = .16f; lunarScale = 1f }
            2 -> { weatherX = .965f; weatherY = .195f; weatherScale = 1f }
        }
        saveWidgetPositions()
        invalidate()
    }

    private fun toggleWidgetEditMode() {
        widgetEditMode = !widgetEditMode
        if (!widgetEditMode) saveWidgetPositions()
        selectedEditWidget = selectedEditWidget.coerceIn(0, 2)
        invalidate()
    }

    private fun cycleEditableWidget() {
        repeat(3) {
            selectedEditWidget = (selectedEditWidget + 1) % 3
            val visible = when (selectedEditWidget) {
                0 -> showClock || showDate
                1 -> showLunar
                else -> showWeather
            }
            if (visible) return
        }
    }

    private fun adjustSelectedWidgetPosition(dx: Float, dy: Float) {
        // DPAD movement uses consistent screen-relative increments on 720p/1080p/4K.
        val stepX = .01f
        val stepY = .01f
        val x = when (selectedEditWidget) {
            0 -> clockX
            1 -> lunarX
            else -> weatherX
        }
        val y = when (selectedEditWidget) {
            0 -> clockY
            1 -> lunarY
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
        // Approved clean home layout: no brand caption or bottom status/instruction labels.
        // App icons are first-class desktop objects; no enclosing tile/card.
        favorites.take(5).forEachIndexed { i, app ->
            val icon = homeIconCardRect(i, w, h)
            val focused = if (homeAppManageMode) i == homeManageSlot
                else i == homeIndex && homeTopFocus == -1
            if (app.packageName == emptySlot.packageName) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = (h * .003f).coerceAtLeast(2f)
                paint.color = if (focused) 0xFF8BE7FF.toInt() else 0x88FFFFFF.toInt()
                c.drawRoundRect(icon, icon.width() * .20f, icon.width() * .20f, paint)
                paint.style = Paint.Style.FILL
                paint.textAlign = Paint.Align.CENTER
                text(c, "+", icon.centerX(), icon.centerY() + icon.height() * .15f, icon.height() * .62f, -1, true)
                text(c, "添加应用", icon.centerX(), icon.bottom + h * .038f, h * .022f, -1, true)
            } else {
                drawUnifiedAppIcon(c, app, icon, focused)
                paint.textAlign = Paint.Align.CENTER
                text(c, app.label.take(12), icon.centerX(), icon.bottom + h * .038f, h * .024f, -1, true)
            }
            paint.textAlign = Paint.Align.LEFT
        }
        if (homeIconEditMode || homeAppManageMode || homePositionEditMode) {
            paint.textAlign = Paint.Align.CENTER
            val hint = when {
                homePositionEditMode -> "调整图标位置：方向键移动   OK 保存   返回取消"
                homeIconEditMode -> "图标大小调整：↑ 放大   ↓ 缩小   ←→ 选择图标   OK 保存   返回取消"
                else -> "应用管理：←→ 选择桌面位置   OK 选择应用   返回退出"
            }
            text(c, hint, w * .5f, h * .91f, h * .023f, 0xFFBDEBFF.toInt(), true)
            if (homePositionEditMode) {
                val selected = homeIconCardRect(homeIndex, w, h)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = (h * .006f).coerceAtLeast(3f)
                paint.color = 0xFFFFD166.toInt()
                c.drawRoundRect(RectF(selected.left - 8f, selected.top - 8f,
                    selected.right + 8f, selected.bottom + 8f), 18f, 18f, paint)
                paint.style = Paint.Style.FILL
            }
            if (homeAppManageMode && homeManageSlot < favorites.size) {
                val selected = homeIconCardRect(homeManageSlot, w, h)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = (h * .006f).coerceAtLeast(3f)
                paint.color = 0xFFFFD166.toInt()
                c.drawRoundRect(scaledFocusRect(RectF(selected.left - 8f, selected.top - 8f,
                    selected.right + 8f, selected.bottom + 8f)), 18f, 18f, paint)
                paint.style = Paint.Style.FILL
            }
            paint.textAlign = Paint.Align.LEFT
        }
    }

    // A single combined entry replaces the former separate Wi-Fi and settings shortcuts.
    private fun controlCenterShortcutRect(w: Float, h: Float) =
        RectF(w * .905f, h * .028f, w * .975f, h * .128f)

    private fun wifiShortcutRect(w: Float, h: Float) = controlCenterShortcutRect(w, h)
    private fun settingsShortcutRect(w: Float, h: Float) = controlCenterShortcutRect(w, h)

    private fun drawControlCenterShortcut(c: Canvas, w: Float, h: Float) {
        val rect = controlCenterShortcutRect(w, h)
        paint.color = 0x22DDF3FF
        c.drawRoundRect(rect, h * .018f, h * .018f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (h * .0028f).coerceAtLeast(2f)
        paint.color = 0xFFEAF8FF.toInt()
        c.drawRoundRect(rect, h * .018f, h * .018f, paint)
        paint.style = Paint.Style.FILL
        val cx = rect.centerX()
        val cy = rect.centerY()
        paint.color = 0xFFEAF8FF.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (h * .0026f).coerceAtLeast(2f)
        val wifi = Path().apply {
            moveTo(cx - h * .019f, cy - h * .006f)
            quadTo(cx, cy - h * .027f, cx + h * .019f, cy - h * .006f)
            moveTo(cx - h * .012f, cy + h * .003f)
            quadTo(cx, cy - h * .012f, cx + h * .012f, cy + h * .003f)
        }
        c.drawPath(wifi, paint)
        c.drawCircle(cx, cy + h * .011f, h * .0028f, paint)
        paint.style = Paint.Style.FILL
        text(c, "⚙", cx + h * .027f, cy + h * .012f, h * .025f, 0xFFEAF8FF.toInt(), true)
    }

    private fun drawHomeTopFocus(c: Canvas, w: Float, h: Float) {
        if (isDrawerOpen || isSettingsOpen || widgetEditMode || homeIconEditMode || homePositionEditMode ||
            homeAppManageMode || homeTopFocus !in 0..1) return
        if (homeTopFocus == 0 && !showClock && !showDate) return
        val rect = when (homeTopFocus) {
            0 -> RectF(w * (clockX - .025f), h * (clockY - .045f),
                w * (clockX + .245f), h * (clockY + .065f * clockScale))
            else -> controlCenterShortcutRect(w, h)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (h * .004f).coerceAtLeast(2.5f)
        paint.color = 0xFF8BE7FF.toInt()
        paint.setShadowLayer((h * .012f).coerceAtLeast(5f), 0f, 0f, 0xAA27BFFF.toInt())
        val focusRect = scaledFocusRect(rect)
        c.drawRoundRect(focusRect, (h * .018f).coerceAtLeast(10f), (h * .018f).coerceAtLeast(10f), paint)
        paint.clearShadowLayer()
        paint.style = Paint.Style.FILL
    }

    private fun homeTopFocusCenterX(target: Int, w: Float): Float = when (target) {
        0 -> w * (clockX + .10f)
        1 -> w * .94f
        else -> w * .94f
    }

    private fun homeFocusTargets(): List<FocusTarget> {
        val w = width.toFloat()
        val h = height.toFloat()
        val targets = buildList {
            favorites.indices.forEach { index ->
                val rect = homeIconCardRect(index, w, h)
                add(FocusTarget(index, rect.centerX(), rect.centerY()))
            }
            if (showClock || showDate) {
                val rect = RectF(w * (clockX - .025f), h * (clockY - .045f),
                    w * (clockX + .245f), h * (clockY + .065f * clockScale))
                add(FocusTarget(100, rect.centerX(), rect.centerY()))
            }
            val controlCenter = controlCenterShortcutRect(w, h)
            add(FocusTarget(101, controlCenter.centerX(), controlCenter.centerY()))
        }
        return targets
    }

    private fun moveHomeAppFocus(currentIndex: Int, direction: Int): Int {
        val targets = favorites.indices.map { index ->
            val rect = homeIconCardRect(index, width.toFloat(), height.toFloat())
            FocusTarget(index, rect.centerX(), rect.centerY())
        }
        return SpatialFocusNavigator.next(currentIndex, direction, targets)
    }

    private fun moveHomeSpatialFocus(direction: Int) {
        val targets = homeFocusTargets()
        val currentId = if (homeTopFocus in 0..1) 100 + homeTopFocus else homeIndex
        val current = targets.firstOrNull { it.id == currentId } ?: return
        val next = SpatialFocusNavigator.next(currentId, direction, targets)
        if (next != currentId) {
            when (next) {
                in 0..4 -> { homeIndex = next; homeTopFocus = -1 }
                in 100..101 -> homeTopFocus = next - 100
            }
            animateSpatialFocus()
        }
        invalidate()
    }

    private fun moveHomeFocusToTop() = moveHomeSpatialFocus(SpatialFocusNavigator.UP)

    private fun moveHomeFocusToApps() = moveHomeSpatialFocus(SpatialFocusNavigator.DOWN)

    private fun hiddenControlInputIds(): MutableSet<String> =
        prefs.getStringSet("control_hidden_inputs", emptySet())?.toMutableSet() ?: mutableSetOf()

    private fun pinnedControlInputIds(): MutableSet<String> =
        prefs.getStringSet("control_pinned_inputs", emptySet())?.toMutableSet() ?: mutableSetOf()

    private fun visibleControlInputs(): List<ControlInput> {
        val hidden = hiddenControlInputIds()
        val pinned = pinnedControlInputIds()
        return controlInputs.filterNot { it.id in hidden }.sortedByDescending { it.id in pinned }
    }

    private fun toggleControlInputHidden(input: ControlInput) {
        val hidden = hiddenControlInputIds()
        if (!hidden.add(input.id)) hidden.remove(input.id)
        prefs.edit().putStringSet("control_hidden_inputs", hidden).apply()
        invalidate()
    }

    private fun toggleControlInputPinned(input: ControlInput) {
        val pinned = pinnedControlInputIds()
        if (!pinned.add(input.id)) pinned.remove(input.id)
        prefs.edit().putStringSet("control_pinned_inputs", pinned).apply()
        invalidate()
    }

    private fun refreshControlInputs() {
        controlInputs = runCatching {
            val manager = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
                ?: return@runCatching emptyList()
            manager.tvInputList.map { info ->
                val custom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                    runCatching { info.loadCustomLabel(context)?.toString() }.getOrNull() else null
                val label = custom ?: runCatching { info.loadLabel(context).toString() }
                    .getOrDefault(info.id.substringAfterLast('/'))
                val kind = when (info.type) {
                    TvInputInfo.TYPE_HDMI -> "HDMI"
                    TvInputInfo.TYPE_DISPLAY_PORT -> "DisplayPort"
                    TvInputInfo.TYPE_TUNER -> "电视调谐器"
                    TvInputInfo.TYPE_COMPOSITE -> "AV视频"
                    TvInputInfo.TYPE_COMPONENT -> "色差输入"
                    TvInputInfo.TYPE_VGA -> "VGA"
                    TvInputInfo.TYPE_DVI -> "DVI"
                    else -> if (info.isPassthroughInput) "外接输入" else "电视信号"
                }
                ControlInput(info.id, label.ifBlank { info.id }, kind,
                    runCatching { info.createSetupIntent() }.getOrNull())
            }.distinctBy { it.id }.sortedWith(compareBy<ControlInput>({ it.kind }, { it.label }))
        }.getOrDefault(emptyList())
    }

    private fun openControlInput(input: ControlInput) {
        val setup = input.setupIntent
        if (setup != null && runCatching {
                context.startActivity(setup.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess) return
        for (action in listOf("android.intent.action.TV_INPUT", "android.settings.HDMI_SETTINGS")) {
            if (runCatching { context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
        android.widget.Toast.makeText(context,
            "已识别 ${input.label}；此电视固件未提供独立信源设置入口",
            android.widget.Toast.LENGTH_LONG).show()
        runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun volumeLevel(): Pair<Int, Int> = runCatching {
        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) to
            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }.getOrDefault(0 to 1)

    private fun adjustControlVolume(direction: Int) {
        val (level, max) = volumeLevel()
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (level + direction).coerceIn(0, max), 0) }
        invalidate()
    }

    private fun toggleControlMute() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val muted = audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    if (muted) AudioManager.ADJUST_UNMUTE else AudioManager.ADJUST_MUTE, 0)
            } else audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
        }
        invalidate()
    }

    private fun brightnessLevel(): Int = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128).coerceIn(1, 255)
    }.getOrDefault(128)

    private fun adjustControlBrightness(direction: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(context)) {
            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            android.widget.Toast.makeText(context, "需要允许小李桌面修改系统亮度后才能调节",
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val next = (brightnessLevel() + direction * 13).coerceIn(1, 255)
        runCatching {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, next)
        }.onFailure {
            android.widget.Toast.makeText(context, "当前电视固件不允许应用直接调节背光",
                android.widget.Toast.LENGTH_LONG).show()
        }
        invalidate()
    }

    private fun openControlCenter() {
        if (!controlCenterOpen) controlCenterReturnFocus = homeTopFocus
        controlCenterOpen = true
        controlCenterPage = 0
        controlCenterFocus = 0
        refreshControlInputs()
        homeTopFocus = -1
        animateSpatialFocus()
        invalidate()
    }

    private fun activateControlCenterItem() {
        when (controlCenterPage) {
            0 -> when (controlCenterFocus) {
                0 -> {
                    val intent = Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }.onFailure {
                        runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }
                }
                1 -> { refreshControlInputs(); controlCenterPage = 1; controlCenterFocus = 0 }
                2 -> { controlCenterPage = 2; controlCenterFocus = 0 }
                3 -> { controlCenterPage = 3; controlCenterFocus = 0 }
                4 -> { controlCenterOpen = false; openLauncherSettings() }
                5 -> runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            1 -> {
                val input = controlInputs.getOrNull(controlCenterFocus)
                if (input != null) openControlInput(input)
                else runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            2 -> toggleControlMute()
            3 -> adjustControlBrightness(0)
        }
        invalidate()
    }

    private fun activateHomeTopFocus() {
        when (homeTopFocus) {
            0 -> openClockEditMenu()
            1 -> openControlCenter()
        }
    }

    private fun drawControlCenter(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = h * .035f
        paint.color = 0x66000818
        canvas.drawRect(0f, 0f, w, h, paint)

        val panel = RectF(w * .60f, h * .075f, w * .975f, h * .925f)
        paint.shader = LinearGradient(panel.left, panel.top, panel.right, panel.bottom,
            intArrayOf(0xD92B5B86.toInt(), 0xC91A3C68.toInt(), 0xD90B2447.toInt()),
            null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(panel, radius, radius, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (h * .0022f).coerceAtLeast(1.5f)
        paint.color = 0x99D8F2FF.toInt()
        canvas.drawRoundRect(panel, radius, radius, paint)
        paint.style = Paint.Style.FILL

        val left = panel.left + panel.width() * .075f
        val top = panel.top + panel.height() * .105f
        val title = when (controlCenterPage) {
            0 -> "控制中心"
            1 -> "电视信源"
            2 -> "音量控制"
            else -> "屏幕亮度"
        }
        val subtitle = when (controlCenterPage) {
            0 -> "设备连接与快捷设置"
            1 -> "由 Android TV InputManager 实时识别"
            2 -> "左右键调节 · OK 切换静音"
            else -> "系统允许时调节屏幕亮度"
        }
        text(canvas, title, left, top, h * .037f, 0xFFF3FAFF.toInt(), true)
        text(canvas, subtitle, left, top + h * .045f, h * .018f, 0xBFE0F1FF.toInt())

        if (controlCenterPage == 0) {
            val labels = listOf("网络与 Wi-Fi", "电视信源", "音量控制", "屏幕亮度", "小李桌面设置", "系统设置")
            val subtitles = listOf("打开系统网络配置", "检测到 ${controlInputs.size} 个电视输入",
                "媒体音量与静音", "系统允许时调节亮度", "壁纸、桌面与应用管理", "Android 设备设置")
            val icons = listOf("◉", "▣", "♪", "☼", "⚙", "⌘")
            val startY = panel.top + panel.height() * .225f
            val gap = panel.height() * .116f
            labels.forEachIndexed { index, label ->
                val rect = RectF(panel.left + panel.width() * .065f, startY + index * gap,
                    panel.right - panel.width() * .065f, startY + index * gap + panel.height() * .095f)
                paint.color = if (index == controlCenterFocus) 0x775AB9F2 else 0x334D91C8
                canvas.drawRoundRect(rect, h * .018f, h * .018f, paint)
                if (index == controlCenterFocus) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = (h * .0028f).coerceAtLeast(2f)
                    paint.color = 0xFFD8F4FF.toInt()
                    canvas.drawRoundRect(rect, h * .018f, h * .018f, paint)
                    paint.style = Paint.Style.FILL
                }
                text(canvas, icons[index], rect.left + rect.width() * .075f, rect.centerY() + h * .012f,
                    h * .030f, 0xFFEAF8FF.toInt(), true)
                text(canvas, label, rect.left + rect.width() * .19f, rect.top + rect.height() * .44f,
                    h * .021f, 0xFFF4FAFF.toInt(), true)
                text(canvas, subtitles[index], rect.left + rect.width() * .19f, rect.top + rect.height() * .76f,
                    h * .013f, 0xBFD5E9FA.toInt())
                text(canvas, "›", rect.right - rect.width() * .075f, rect.centerY() + h * .012f,
                    h * .034f, 0xFFEAF8FF.toInt(), true)
            }
        } else if (controlCenterPage == 1) {
            if (controlInputs.isEmpty()) {
                text(canvas, "系统没有向应用公开可用的电视信源列表。", left,
                    panel.top + panel.height() * .31f, h * .018f, 0xFFEAF7FF.toInt())
                text(canvas, "请通过电视系统设置管理输入源。", left,
                    panel.top + panel.height() * .36f, h * .015f, 0xBFE0F1FF.toInt())
            } else {
                controlInputs.take(6).forEachIndexed { index, input ->
                    val rowTop = panel.top + panel.height() * (.225f + index * .112f)
                    val rect = RectF(panel.left + panel.width() * .065f, rowTop,
                        panel.right - panel.width() * .065f, rowTop + panel.height() * .092f)
                    paint.color = if (index == controlCenterFocus) 0x775AB9F2 else 0x334D91C8
                    canvas.drawRoundRect(rect, h * .016f, h * .016f, paint)
                    if (index == controlCenterFocus) {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = (h * .0025f).coerceAtLeast(2f)
                        paint.color = 0xFFD8F4FF.toInt()
                        canvas.drawRoundRect(rect, h * .016f, h * .016f, paint)
                        paint.style = Paint.Style.FILL
                    }
                    text(canvas, input.label, rect.left + rect.width() * .07f,
                        rect.top + rect.height() * .44f, h * .020f, 0xFFF4FAFF.toInt(), true)
                    text(canvas, input.kind, rect.left + rect.width() * .07f,
                        rect.top + rect.height() * .76f, h * .013f, 0xBFD5E9FA.toInt())
                    text(canvas, "›", rect.right - rect.width() * .08f,
                        rect.centerY() + h * .010f, h * .032f, 0xFFEAF8FF.toInt(), true)
                }
            }
        } else {
            val isVolume = controlCenterPage == 2
            val current = if (isVolume) {
                val (level, max) = volumeLevel()
                level.toFloat() / max
            } else brightnessLevel() / 255f
            val valueLabel = if (isVolume) {
                val (level, max) = volumeLevel()
                "$level / $max"
            } else "${brightnessLevel()} / 255"
            val slider = RectF(panel.left + panel.width() * .10f, panel.top + panel.height() * .43f,
                panel.right - panel.width() * .10f, panel.top + panel.height() * .49f)
            paint.color = 0x665D8FB8
            canvas.drawRoundRect(slider, slider.height() / 2f, slider.height() / 2f, paint)
            paint.color = 0xFF75D8FF.toInt()
            val filled = RectF(slider.left, slider.top,
                slider.left + slider.width() * current.coerceIn(0f, 1f), slider.bottom)
            canvas.drawRoundRect(filled, slider.height() / 2f, slider.height() / 2f, paint)
            paint.textAlign = Paint.Align.CENTER
            text(canvas, valueLabel, panel.centerX(), panel.top + panel.height() * .37f,
                h * .027f, 0xFFF4FAFF.toInt(), true)
            paint.textAlign = Paint.Align.LEFT
            val hint = if (isVolume) "左右键调节 · OK 切换静音"
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(context))
                    "尚未授权修改系统亮度 · OK 打开授权页面"
                else "左右键调节屏幕亮度"
            text(canvas, hint, left, panel.top + panel.height() * .58f, h * .016f, 0xFFD5EAFB.toInt())
        }
        text(canvas, "返回键：返回上一级", left, panel.bottom - panel.height() * .055f,
            h * .014f, 0xAFCFE5F8.toInt())
        paint.textAlign = Paint.Align.LEFT
    }

    private fun drawUnifiedAppIcon(c: Canvas, app: AppEntry, rect: RectF, focused: Boolean) {
        val drawable = appIconCache.getOrPut(app.packageName) {
            runCatching { context.packageManager.getApplicationIcon(app.packageName) }
                .getOrNull() ?: android.graphics.drawable.ColorDrawable(0xFF1B74C8.toInt())
        }
        // Fill the icon viewport, clipping only its corners to the same rounded-card silhouette.
        val target = if (focused) scaledFocusRect(rect) else RectF(rect)
        val radius = target.width() * .20f
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
        val replacingDesktopSlot = homeAppManageMode
        text(c, when {
            replacingDesktopSlot -> "更换桌面应用"
            editingFavorites -> "首页常用应用"
            else -> "应用"
        }, w * .075f, h * .215f, h * .042f, -1, true)
        paint.textAlign = Paint.Align.RIGHT
        val headerLabel = if (replacingDesktopSlot) "取消" else if (editingFavorites) "完成" else "管理"
        text(c, headerLabel, w * .925f, h * .215f, h * .027f,
            if (drawerHeaderFocused) 0xFFFFFFFF.toInt() else 0xFFBDEBFF.toInt(), true)
        if (drawerHeaderFocused) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (h * .0035f).coerceAtLeast(2f)
            paint.color = 0xFF8BE7FF.toInt()
            c.drawRoundRect(scaledFocusRect(RectF(w * .835f, h * .175f, w * .94f, h * .245f)),
                h * .012f, h * .012f, paint)
            paint.style = Paint.Style.FILL
        }
        paint.textAlign = Paint.Align.LEFT
        if (replacingDesktopSlot) {
            text(c, "选择下方应用，替换桌面第 ${homeManageSlot + 1} 个位置",
                w * .075f, h * .255f, h * .022f, 0xFFBDEBFF.toInt(), true)
        }

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
        drawerScrollVisualRow = drawerScrollVisualRow.coerceIn(0f, maxScroll.toFloat())
        val clipSave = c.save()
        c.clipRect(gridLeft, contentTop, gridRight, contentBottom)
        apps.forEachIndexed { i, app ->
            val row = i / cols
            val col = i % cols
            val left = gridLeft + col * cellW
            val top = contentTop + (row - drawerScrollVisualRow) * cellH
            val cell = RectF(left + cellW * .035f, top + cellH * .025f,
                left + cellW * .965f, top + cellH * .965f)
            // Icon frame and title line are separate visual elements.
            // Drawer uses standalone icons and the same persisted size scale as the desktop.
            val iconSize = minOf(metrics.iconSize(cell.width()) * homeIconScale, cellW * .84f, cellH * .76f)
            val iconTop = cell.top + (cellH * .78f - iconSize) / 2f
            val iconRect = RectF(cell.centerX() - iconSize / 2f, iconTop,
                cell.centerX() + iconSize / 2f, iconTop + iconSize)
            drawUnifiedAppIcon(c, app, iconRect, i == drawerIndex && !drawerHeaderFocused)
            paint.textAlign = Paint.Align.CENTER
            text(c, app.label.take(12), cell.centerX(), cell.bottom - cellH * .045f, h * .023f, -1, i == drawerIndex)
            if (favorites.any { it.packageName == app.packageName }) {
                paint.textAlign = Paint.Align.CENTER
                text(c, "已固定", iconRect.centerX(), iconRect.top - h * .012f,
                    h * .015f, 0xFF8BE7FF.toInt(), true)
                paint.textAlign = Paint.Align.LEFT
            }
            paint.textAlign = Paint.Align.LEFT
        }
        c.restoreToCount(clipSave)
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

    private fun openLauncherSettings(initialFocus: Int = 30) {
        if (!isSettingsOpen) {
            settingsReturnHomeFocus = if (homeTopFocus in 0..1) 100 + homeTopFocus else homeIndex
        }
        isDrawerOpen = false
        isSettingsOpen = true
        homeTopFocus = -1
        settingsFocus = initialFocus
        animateSpatialFocus()
        invalidate()
    }

    private fun moveSettingsFocus(direction: Int) {
        val next = SettingsFocusNavigator.next(settingsFocus, direction)
        if (next != settingsFocus) {
            settingsFocus = next
            animateSpatialFocus()
        }
    }

    private fun moveSettingsCategoryFocus(direction: Int) {
        val targets = (0..6).map { index ->
            FocusTarget(30 + index, .5f, index.toFloat())
        }
        val next = SpatialFocusNavigator.next(settingsFocus, direction, targets)
        if (next != settingsFocus) {
            settingsFocus = next
            animateSpatialFocus()
        }
    }

    private fun moveActionMenuFocus(direction: Int) {
        val count = if (actionMenuSource == "home") 4 else 2
        val targets = (0 until count).map { index ->
            val y = if (actionMenuSource == "home") .25f + index * .17f
                else .29f + index * .22f
            FocusTarget(index, .5f, y)
        }
        val next = SpatialFocusNavigator.next(actionMenuChoice, direction, targets)
        if (next != actionMenuChoice) {
            actionMenuChoice = next
            animateSpatialFocus()
        }
    }

    private fun moveClockMenuFocus(direction: Int) {
        val targets = listOf(FocusTarget(0, .5f, .35f), FocusTarget(1, .5f, .65f))
        val next = SpatialFocusNavigator.next(clockEditChoice, direction, targets)
        if (next != clockEditChoice) {
            clockEditChoice = next
            animateSpatialFocus()
        }
    }

    private fun moveDrawerSpatialFocus(direction: Int) {
        val metrics = drawerMetrics()
        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val targets = buildList {
            apps.indices.forEach { index ->
                val row = index / metrics.columns
                val column = index % metrics.columns
                val centerX = metrics.gridLeft + (column + .5f) * metrics.cellWidth
                val centerY = metrics.contentTop +
                    (row - drawerScrollVisualRow + .5f) * metrics.cellHeight
                add(FocusTarget(index, centerX / w, centerY / h))
            }
            add(FocusTarget(-1, .8875f, .21f))
        }
        val currentId = if (drawerHeaderFocused) -1 else drawerIndex
        val eligibleTargets = if (direction == SpatialFocusNavigator.UP &&
            !drawerHeaderFocused && drawerIndex >= metrics.columns) {
            targets.filter { it.id != -1 }
        } else targets
        val next = SpatialFocusNavigator.next(currentId, direction, eligibleTargets)
        if (next != currentId) {
            if (next == -1) drawerHeaderFocused = true
            else if (next >= 0) {
                drawerHeaderFocused = false
                drawerIndex = next
            }
            animateSpatialFocus()
        }
    }

    private fun drawerMetrics(): DrawerGridMetrics =
        DrawerGridMetrics(width.toFloat(), height.toFloat(), drawerColumns(), drawerRows())

    private fun drawerRows(): Int = prefs.getInt("drawer_rows", 2).coerceIn(2, 4)

    private fun setDrawerRows(rows: Int) {
        prefs.edit().putInt("drawer_rows", rows.coerceIn(2, 4)).apply()
        settingsFocus = rows + 15
        animateSpatialFocus()
        invalidate()
    }
    private fun setDrawerColumns(columns: Int) {
        prefs.edit().putInt("drawer_columns", columns.coerceIn(4, 6)).apply()
        settingsFocus = columns - 4
        animateSpatialFocus()
        invalidate()
    }

    private fun clearWallpaper() {
        AlertDialog.Builder(context)
            .setTitle("恢复默认壁纸")
            .setMessage("将移除当前选择的图片壁纸，恢复小李桌面的默认背景。")
            .setNegativeButton("取消", null)
            .setPositiveButton("恢复") { _, _ ->
                wallpaperBitmap?.recycle()
                wallpaperBitmap = BitmapFactory.decodeResource(context.resources, R.drawable.default_wallpaper)
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
    private fun settingsCategoryControl(index: Int): Int = when (index) {
        0 -> 8   // Desktop layout
        1 -> 3   // Appearance and wallpaper
        2 -> 21  // App management
        3 -> 22  // Desktop widgets
        4 -> 14  // Remote and interaction / home role
        5 -> 6   // XiaoLi transfer
        else -> 7 // Android system settings
    }

    private fun settingsCategoryForControl(focus: Int): Int? = when (focus) {
        8, 0, 1, 2, 17, 18, 19 -> 0
        3, 4, 5, 24 -> 1
        21 -> 2
        22, 9, 10, 15, 16 -> 3
        14, 13 -> 4
        23 -> 0
        6 -> 5
        7 -> 6
        else -> null
    }

    private fun drawSettingsNavigation(c: Canvas, w: Float, h: Float) {
        val rail = RectF(w * .045f, h * .105f, w * .225f, h * .965f)
        paint.style = Paint.Style.FILL
        paint.color = 0xE8091932.toInt()
        c.drawRoundRect(rail, h * .025f, h * .025f, paint)
        paint.color = 0xFF35B9F2.toInt()
        c.drawRoundRect(RectF(rail.left + w * .018f, rail.top + h * .055f,
            rail.left + w * .022f, rail.top + h * .145f), h * .003f, h * .003f, paint)
        text(c, "设置中心", rail.left + w * .035f, rail.top + h * .105f,
            h * .031f, -1, true)
        text(c, "DEVICE SETTINGS", rail.left + w * .035f, rail.top + h * .145f,
            h * .011f, 0xFF8FAFCB.toInt(), true)
        val labels = listOf("桌面布局", "外观与壁纸", "应用管理", "桌面组件", "遥控器与操作", "小李全联", "电视系统设置")
        labels.forEachIndexed { index, label ->
            val top = h * (.285f + index * .087f)
            val rect = RectF(rail.left + w * .014f, top, rail.right - w * .014f, top + h * .066f)
            val selected = settingsFocus == 30 + index || settingsCategoryForControl(settingsFocus) == index
            if (selected) {
                val selectedRect = if (settingsFocus == 30 + index) scaledFocusRect(rect) else rect
                paint.color = 0xFF164D78.toInt()
                c.drawRoundRect(selectedRect, h * .012f, h * .012f, paint)
                paint.color = 0xFF68D9FF.toInt()
                c.drawRoundRect(RectF(selectedRect.left, selectedRect.top + h * .012f,
                    selectedRect.left + w * .004f, selectedRect.bottom - h * .012f),
                    h * .002f, h * .002f, paint)
            }
            paint.color = if (selected) 0xFF8BE7FF.toInt() else 0xFF7693B2.toInt()
            c.drawCircle(rect.left + w * .025f, rect.centerY(), h * .0045f, paint)
            text(c, label, rect.left + w * .045f, rect.centerY() + h * .007f,
                h * .017f, if (selected) -1 else 0xFFD0DCEC.toInt(), selected)
        }
        text(c, "方向键选择  ·  OK 确认", rail.left + w * .025f, rail.bottom - h * .035f,
            h * .011f, 0xFF829BB7.toInt())
    }

    private fun drawSettingCard(c: Canvas, bounds: RectF, heading: String, detail: String, focused: Boolean) {
        val rect = if (focused) scaledFocusRect(bounds) else RectF(bounds)
        val radius = rect.height() * .16f
        paint.style = Paint.Style.FILL
        paint.color = if (focused) 0xFF153F68.toInt() else 0xC9122945.toInt()
        c.drawRoundRect(rect, radius, radius, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (focused) (height * .0032f).coerceAtLeast(2.5f) else (height * .0011f).coerceAtLeast(1f)
        paint.color = if (focused) 0xFF72DFFF.toInt() else 0x554E83B5
        c.drawRoundRect(rect, radius, radius, paint)
        paint.style = Paint.Style.FILL
        val accent = RectF(rect.left + rect.width() * .025f, rect.top + rect.height() * .22f,
            rect.left + rect.width() * .032f, rect.bottom - rect.height() * .22f)
        paint.color = if (focused) 0xFF65D9FF.toInt() else 0x665E9ACB
        c.drawRoundRect(accent, accent.width(), accent.width(), paint)
        val left = rect.left + rect.width() * .075f
        val maxWidth = rect.width() * .79f
        val titleSize = (rect.height() * .235f).coerceAtLeast(height * .017f)
        val detailSize = (rect.height() * .145f).coerceAtLeast(height * .011f)
        text(c, heading, left, rect.top + rect.height() * .40f, titleSize,
            if (focused) -1 else 0xFFF0F6FF.toInt(), true)
        paint.textSize = detailSize
        paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        val lines = mutableListOf<String>()
        var remaining = detail.replace("\\n", " ")
        val maxLines = if (rect.height() > height * .18f) 3 else 1
        while (remaining.isNotEmpty() && lines.size < maxLines) {
            val count = paint.breakText(remaining, true, maxWidth, null).coerceAtLeast(1)
            lines.add(remaining.substring(0, count))
            remaining = remaining.substring(count).trimStart()
        }
        val lineHeight = detailSize * 1.38f
        val firstBaseline = if (lines.size > 1) rect.top + rect.height() * .65f
            else rect.top + rect.height() * .75f
        lines.forEachIndexed { index, line ->
            text(c, line, left, firstBaseline + index * lineHeight, detailSize,
                if (focused) 0xFFDDF5FF.toInt() else 0xFFAFC4DA.toInt())
        }
        if (rect.width() > width * .18f) {
            text(c, "›", rect.right - rect.width() * .065f, rect.centerY() + rect.height() * .12f,
                rect.height() * .34f, if (focused) 0xFF8BE7FF.toInt() else 0xFF6F94B7.toInt(), true)
        }
    }

    private fun drawLauncherSettings(c: Canvas, w: Float, h: Float) {
        val category = if (settingsFocus in 30..36) settingsFocus - 30
            else settingsCategoryForControl(settingsFocus) ?: 0
        val contentSave = c.save()
        c.translate(w * .18f, 0f)
        c.scale(.80f, 1f)
        paint.color = 0xE9081A31.toInt()
        c.drawRoundRect(RectF(w * .08f, h * .14f, w * .92f, h * .97f), h * .025f, h * .025f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (h * .0012f).coerceAtLeast(1f)
        paint.color = 0x334E91C7
        c.drawRoundRect(RectF(w * .08f, h * .14f, w * .92f, h * .97f), h * .025f, h * .025f, paint)
        paint.style = Paint.Style.FILL

        val headings = listOf("桌面布局", "外观与壁纸", "应用管理", "桌面组件",
            "遥控器与操作", "小李全联", "电视系统设置")
        val descriptions = listOf(
            "调整首页图标尺寸与应用抽屉网格",
            "选择壁纸、动态背景或恢复默认背景",
            "管理首页最多五个常用应用",
            "设置时间日期显示，并编辑组件位置与大小",
            "查看遥控器操作方式并设置默认主屏幕",
            "打开内置局域网文件传输中心",
            "打开 Android 电视系统设置"
        )
        text(c, headings[category], w * .13f, h * .225f, h * .04f, -1, true)
        text(c, descriptions[category], w * .13f, h * .258f, h * .019f, 0xFFDDEEFF.toInt())

        when (category) {
            0 -> {
                drawSettingCard(c, RectF(w * .13f, h * .32f, w * .87f, h * .45f),
                    "应用图标大小：${(homeIconScale * 100).toInt()}%",
                    "桌面与应用抽屉使用同一缩放比例 · 点击调整", settingsFocus == 8)
                text(c, "应用抽屉列数", w * .13f, h * .515f, h * .022f, 0xFFDDEEFF.toInt(), true)
                for (i in 0..2) {
                    val rect = RectF(w * (.13f + i * .105f), h * .54f,
                        w * (.13f + i * .105f) + w * .095f, h * .63f)
                    drawSettingCard(c, rect, "${i + 4}列",
                        if (drawerColumns() == i + 4) "当前" else "选择", settingsFocus == i)
                }
                text(c, "应用抽屉行数", w * .13f, h * .685f, h * .022f, 0xFFDDEEFF.toInt(), true)
                for (i in 0..2) {
                    val rect = RectF(w * (.13f + i * .105f), h * .71f,
                        w * (.13f + i * .105f) + w * .095f, h * .80f)
                    drawSettingCard(c, rect, "${i + 2}行",
                        if (drawerRows() == i + 2) "当前" else "选择", settingsFocus == i + 17)
                }
                drawSettingCard(c, RectF(w * .13f, h * .82f, w * .62f, h * .92f),
                    "恢复五个桌面图标默认位置", "保留当前应用和顺序，只恢复预设位置", settingsFocus == 23)
            }
            1 -> {
                drawSettingCard(c, RectF(w * .13f, h * .36f, w * .35f, h * .51f),
                    "选择壁纸", "从图片中选择", settingsFocus == 3)
                drawSettingCard(c, RectF(w * .38f, h * .36f, w * .62f, h * .51f),
                    if (dynamicWallpaperEnabled) "动态壁纸：开" else "动态壁纸：关",
                    "切换动态光效背景", settingsFocus == 4)
                drawSettingCard(c, RectF(w * .65f, h * .36f, w * .87f, h * .51f),
                    "恢复默认", "移除自定义图片", settingsFocus == 5)
                drawWallpaperPreview(c, RectF(w * .13f, h * .57f, w * .87f, h * .84f))
                drawSettingCard(c, RectF(w * .13f, h * .865f, w * .62f, h * .945f),
                    "从手机接收壁纸", "通过小李全联局域网传输，无需扫码", settingsFocus == 24)
            }
            2 -> {
                drawSettingCard(c, RectF(w * .13f, h * .39f, w * .87f, h * .64f),
                    "管理桌面应用", "从应用抽屉选择、固定或移除首页常用应用（最多五个）",
                    settingsFocus == 21)
            }
            3 -> {
                val labels = listOf(if (showClock) "时间：开" else "时间：关",
                    if (showDate) "日期：开" else "日期：关",
                    if (use24HourClock) "24小时制" else "12小时制",
                    if (showClockSeconds) "显示秒数" else "隐藏秒数")
                val ids = listOf(9, 10, 15, 16)
                val gap = w * .012f
                val cardW = (w * .74f - gap * 3f) / 4f
                for (i in 0..3) {
                    val left = w * .13f + i * (cardW + gap)
                    drawSettingCard(c, RectF(left, h * .34f, left + cardW, h * .49f),
                        labels[i], "点击切换", settingsFocus == ids[i])
                }
                drawSettingCard(c, RectF(w * .13f, h * .60f, w * .87f, h * .75f),
                    "组件布局编辑", "拖动时间日期整体位置 · 调整组件大小 · 遥控器方向键微调",
                    settingsFocus == 22)
            }
            4 -> {
                drawSettingCard(c, RectF(w * .13f, h * .39f, w * .87f, h * .64f),
                    if (isDefaultHomeLauncher()) "小李桌面已设为电视默认主屏幕" else "设为电视默认主屏幕",
                    "设为默认后，按遥控器 Home 键直接进入小李桌面；除非更改默认桌面，否则持续生效。\n遥控器：上键进入顶部快捷区，再按上键打开应用抽屉 · 长按菜单进入应用管理 · 长按 OK 打开应用操作菜单",
                    settingsFocus == 14)
            }
            5 -> {
                drawSettingCard(c, RectF(w * .13f, h * .39f, w * .87f, h * .64f),
                    "打开小李全联", "发送 · 接收 · 附近设备 · 传输历史", settingsFocus == 6)
            }
            6 -> {
                drawSettingCard(c, RectF(w * .13f, h * .39f, w * .87f, h * .64f),
                    "打开 Android 系统设置", "网络、显示、设备偏好和系统管理", settingsFocus == 7)
            }
        }
        drawSettingCard(c, RectF(w * .665f, h * .865f, w * .87f, h * .945f),
            "返回", "关闭设置中心", settingsFocus == 13)
        c.restoreToCount(contentSave)
        drawSettingsNavigation(c, w, h)
    }

    // Full hit target includes the label line; only homeIconCardRect is painted.
    private fun homeItemRect(index: Int, w: Float, h: Float): RectF {
        val icon = homeIconCardRect(index, w, h)
        return RectF(icon.left - w * .018f, icon.top - h * .012f, icon.right + w * .018f, icon.bottom + h * .055f)
    }

    private fun homeIconCardRect(index: Int, w: Float, h: Float): RectF {
        val baseSize = minOf(w * (if (w >= h) .105f else .19f), h * (if (w >= h) .19f else .105f))
        val size = baseSize * homeIconScale
        val savedPosition = favorites.getOrNull(index)?.let { homeIconPositions[it.packageName] }
        if (savedPosition != null) {
            val left = (savedPosition.first * w).coerceIn(0f, (w - size).coerceAtLeast(0f))
            val top = (savedPosition.second * h).coerceIn(0f, (h - size).coerceAtLeast(0f))
            return RectF(left, top, left + size, top + size)
        }
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
        // The time/date text is left-aligned at clockX; hit-test the visible text area.
        val left = width * (clockX - .012f)
        val right = width * (clockX + .20f)
        val top = height * (clockY - .045f)
        val bottom = height * (clockY + .05f * clockScale + .018f)
        if ((showClock || showDate) && x in left..right && y in top..bottom) return 0
        val rows = listOf(Triple(showLunar, lunarX, lunarY), Triple(showWeather, weatherX, weatherY))
        rows.forEachIndexed { index, item ->
            if (item.first && x in (width * (item.second - .38f))..(width * item.second + width * .015f) &&
                kotlin.math.abs(y - height * item.third) <= height * .035f) return index + 1
        }
        return -1
    }

    private fun moveWidget(index: Int, x: Float, y: Float) {
        val horizontalExtent = if (index == 0) .24f else .42f
        val verticalExtent = if (index == 0) .105f * clockScale else .04f
        val nx = (x / width).coerceIn(.025f, (1f - horizontalExtent).coerceAtLeast(.025f))
        val ny = (y / height).coerceIn(.045f, (1f - verticalExtent).coerceAtLeast(.045f))
        when (index) {
            0 -> { clockX = nx; clockY = ny; dateX = nx; dateY = ny + .05f * clockScale }
            1 -> { lunarX = nx; lunarY = ny }
            2 -> { weatherX = nx; weatherY = ny }
        }
        invalidate()
    }

    private fun widgetScale(index: Int): Float = when (index) {
        0 -> clockScale
        1 -> lunarScale
        2 -> weatherScale
        else -> 1f
    }

    private fun setWidgetScale(index: Int, value: Float) {
        val scale = value.coerceIn(.55f, 2.4f)
        when (index) {
            0 -> { clockScale = scale; dateScale = scale; dateX = clockX; dateY = clockY + .05f * scale }
            1 -> lunarScale = scale
            2 -> weatherScale = scale
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
            .putFloat("widget_date_scale", clockScale)
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

    private fun saveHomeIconPositions() {
        val stored = JSONObject()
        homeIconPositions.forEach { (packageName, position) ->
            stored.put(packageName, JSONObject().put("x", position.first).put("y", position.second))
        }
        prefs.edit().putString("home_icon_positions", stored.toString()).apply()
    }

    private fun resetHomeIconPositions() {
        homeIconPositions.clear()
        prefs.edit().remove("home_icon_positions").apply()
        homeIndex = homeIndex.coerceIn(0, (favorites.size - 1).coerceAtLeast(0))
        android.widget.Toast.makeText(context, "五个桌面图标已恢复默认位置", android.widget.Toast.LENGTH_SHORT).show()
        invalidate()
    }

    private fun moveHomeIcon(index: Int, pointerX: Float, pointerY: Float) {
        val app = favorites.getOrNull(index) ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val size = homeIconCardRect(index, w, h).width()
        val left = (pointerX - homeAppDragOffsetX).coerceIn(12f, (w - size - 12f).coerceAtLeast(12f))
        val top = (pointerY - homeAppDragOffsetY).coerceIn(h * .12f, (h - size - h * .10f).coerceAtLeast(h * .12f))
        val candidate = RectF(left, top, left + size, top + size)
        val overlaps = favorites.indices.any { other ->
            other != index && RectF.intersects(candidate, homeIconCardRect(other, w, h))
        }
        if (!overlaps) {
            homeIconPositions[app.packageName] = Pair(left / w, top / h)
            invalidate()
        }
    }

    private fun drawerItemIndexAt(x: Float, y: Float): Int =
        drawerMetrics().indexAt(x, y, drawerScrollRow, apps.size)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (controlCenterOpen) {
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val panelLeft = width * .60f
                val panelTop = height * .075f
                val panelBottom = height * .925f
                if (event.x < panelLeft || event.y < panelTop || event.y > panelBottom) {
                    handleBack()
                } else {
                    if (controlCenterPage == 2 || controlCenterPage == 3) {
                        val panelLeft = width * .60f
                        val panelWidth = width * .375f
                        val fraction = ((event.x - (panelLeft + panelWidth * .10f)) /
                            (panelWidth * .80f)).coerceIn(0f, 1f)
                        if (controlCenterPage == 2) {
                            val max = volumeLevel().second
                            val target = (fraction * max).toInt().coerceIn(0, max)
                            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(context)) {
                            adjustControlBrightness(0)
                        } else {
                            val target = (fraction * 255f).toInt().coerceIn(1, 255)
                            runCatching { Settings.System.putInt(context.contentResolver,
                                Settings.System.SCREEN_BRIGHTNESS, target) }
                        }
                    } else {
                        val row = if (controlCenterPage == 0) {
                            val first = height * .075f + height * .85f * .225f
                            val gap = height * .85f * .116f
                            ((event.y - first) / gap).toInt().coerceIn(0, 5)
                        } else {
                            val first = height * .075f + height * .85f * .225f
                            val gap = height * .85f * .112f
                            ((event.y - first) / gap).toInt().coerceIn(0, minOf(5, controlInputs.lastIndex.coerceAtLeast(0)))
                        }
                        controlCenterFocus = row
                        activateControlCenterItem()
                    }
                }
                performClick()
            }
            return true
        }
        if (homePositionEditMode) {
            if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_BUTTON_PRESS -> {
                if ((event.buttonState and MotionEvent.BUTTON_SECONDARY) != 0 &&
                    !isDrawerOpen && !isSettingsOpen && !homeIconEditMode) {
                    homeAppManageMode = true
                    homeManageSlot = homeItemIndexAt(event.x, event.y)
                        .takeIf { it >= 0 } ?: homeIndex.coerceIn(0, favorites.size.coerceAtMost(4))
                    invalidate()
                    return true
                }
                return false
            }
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
                if ((event.buttonState and MotionEvent.BUTTON_SECONDARY) != 0 &&
                    !isDrawerOpen && !isSettingsOpen && !homeIconEditMode) {
                    homeAppManageMode = true
                    homeManageSlot = homeItemIndexAt(event.x, event.y)
                        .takeIf { it >= 0 } ?: homeIndex.coerceIn(0, favorites.size.coerceAtMost(4))
                    invalidate()
                    return true
                }
                touchDownX = event.x
                touchDownY = event.y
                touchDragging = false
                activeWidgetDrag = if (!isSettingsOpen && !isDrawerOpen && !homeAppManageMode && !homeIconEditMode) widgetIndexAt(event.x, event.y) else -1
                widgetDragOffsetX = event.x - width * clockX
                widgetDragOffsetY = event.y - height * clockY
                widgetDragStarted = false
                lastDragIndex = if (activeWidgetDrag < 0 && !homeAppManageMode && !homeIconEditMode) homeItemIndexAt(event.x, event.y) else -1
                if (lastDragIndex >= 0) {
                    val iconRect = homeIconCardRect(lastDragIndex, width.toFloat(), height.toFloat())
                    homeAppDragOffsetX = event.x - iconRect.left
                    homeAppDragOffsetY = event.y - iconRect.top
                }
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
                    moveWidget(activeWidgetDrag, event.x - widgetDragOffsetX, event.y - widgetDragOffsetY)
                    return true
                }
                if (!isDrawerOpen && lastDragIndex >= 0 &&
                    event.eventTime - event.downTime > android.view.ViewConfiguration.getLongPressTimeout() &&
                    (kotlin.math.abs(dx) > width * .01f || kotlin.math.abs(dy) > height * .01f)) {
                    touchDragging = true
                    moveHomeIcon(lastDragIndex, event.x, event.y)
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
                    saveHomeIconPositions()
                    touchDragging = false
                    lastDragIndex = -1
                    performClick()
                    return true
                }
                // The movable clock/date component owns its visible hit area, even if moved near a shortcut.
                if (!isSettingsOpen && !isDrawerOpen && widgetIndexAt(event.x, event.y) == 0) {
                    openClockEditMenu()
                    performClick()
                    return true
                }
                if (!isSettingsOpen && !isDrawerOpen &&
                    (wifiShortcutRect(width.toFloat(), height.toFloat()).contains(event.x, event.y) ||
                     settingsShortcutRect(width.toFloat(), height.toFloat()).contains(event.x, event.y))) {
                    openControlCenter()
                    performClick()
                    return true
                }
                if (actionMenuOpen) {
                    val count = if (actionMenuSource == "home") 4 else 2
                    val top = height * (if (actionMenuSource == "home") .20f else .31f)
                    val bottom = height * (if (actionMenuSource == "home") .80f else .70f)
                    val rowStart = if (actionMenuSource == "home") .25f else .29f
                    val rowStep = if (actionMenuSource == "home") .17f else .22f
                    if (event.x in (width * .34f)..(width * .66f) && event.y in top..bottom) {
                        val row = ((event.y - top - (bottom - top) * rowStart) / ((bottom - top) * rowStep)).toInt()
                        actionMenuChoice = row.coerceIn(0, count - 1)
                        runAppAction()
                    } else actionMenuOpen = false
                } else if (isSettingsOpen) {
                    if (event.x in (width * .055f)..(width * .225f) &&
                        event.y >= height * .275f && event.y <= height * .912f) {
                        val category = ((event.y - height * .275f) / (height * .091f)).toInt().coerceIn(0, 6)
                        settingsFocus = 30 + category
                        animateSpatialFocus()
                        invalidate()
                        performClick()
                        return true
                    }
                    val settingsHitX = (event.x - width * .18f) / .80f
                    if (event.y in (height * .865f)..(height * .945f) &&
                        settingsHitX in (width * .665f)..(width * .87f)) {
                        settingsFocus = 13
                        closeLauncherSettings()
                    } else {
                        val category = if (settingsFocus in 30..36) settingsFocus - 30
                            else settingsCategoryForControl(settingsFocus) ?: 0
                        when (category) {
                            0 -> {
                                if (event.y in (height * .32f)..(height * .45f) &&
                                    settingsHitX in (width * .13f)..(width * .87f)) {
                                    settingsFocus = 8
                                    cycleHomeIconScale()
                                } else if (event.y in (height * .54f)..(height * .63f)) {
                                    val selected = ((settingsHitX - width * .13f) / (width * .105f)).toInt()
                                    if (selected in 0..2) setDrawerColumns(selected + 4)
                                } else if (event.y in (height * .71f)..(height * .80f)) {
                                    val selected = ((settingsHitX - width * .13f) / (width * .105f)).toInt()
                                    if (selected in 0..2) setDrawerRows(selected + 2)
                                } else if (event.y in (height * .82f)..(height * .92f) &&
                                    settingsHitX in (width * .13f)..(width * .62f)) {
                                    settingsFocus = 23
                                    resetHomeIconPositions()
                                }
                            }
                            1 -> {
                                if (event.y in (height * .36f)..(height * .51f)) {
                                    when {
                                        settingsHitX in (width * .13f)..(width * .35f) -> {
                                            settingsFocus = 3
                                            onChooseWallpaper?.invoke()
                                        }
                                        settingsHitX in (width * .38f)..(width * .62f) -> {
                                            settingsFocus = 4
                                            toggleDynamicWallpaper()
                                        }
                                        settingsHitX in (width * .65f)..(width * .87f) -> {
                                            settingsFocus = 5
                                            clearWallpaper()
                                        }
                                    }
                                } else if (event.y in (height * .865f)..(height * .945f) &&
                                    settingsHitX in (width * .13f)..(width * .62f)) {
                                    settingsFocus = 24
                                    context.startActivity(Intent(context, com.xiaoli.quanlian.QuanLianActivity::class.java)
                                        .putExtra(com.xiaoli.quanlian.QuanLianActivity.EXTRA_WALLPAPER_MODE, true))
                                }
                            }
                            2 -> if (event.y in (height * .39f)..(height * .64f) &&
                                settingsHitX in (width * .13f)..(width * .87f)) {
                                settingsFocus = 21
                                isSettingsOpen = false
                                isDrawerOpen = true
                                editingFavorites = true
                                drawerIndex = 0
                                resetDrawerScroll()
                            }
                            3 -> {
                                if (event.y in (height * .34f)..(height * .49f)) {
                                    val gap = width * .012f
                                    val cardW = (width * .74f - gap * 3f) / 4f
                                    val selected = ((settingsHitX - width * .13f) / (cardW + gap)).toInt()
                                    settingsFocus = listOf(9, 10, 15, 16).getOrNull(selected) ?: settingsFocus
                                    when (selected) {
                                        0 -> toggleHomeComponent(9)
                                        1 -> toggleHomeComponent(10)
                                        2 -> { use24HourClock = !use24HourClock; prefs.edit().putBoolean("clock_24_hour", use24HourClock).apply() }
                                        3 -> { showClockSeconds = !showClockSeconds; prefs.edit().putBoolean("clock_seconds", showClockSeconds).apply(); updateClockTick() }
                                    }
                                } else if (event.y in (height * .60f)..(height * .75f) &&
                                    settingsHitX in (width * .13f)..(width * .87f)) {
                                    settingsFocus = 22
                                    isSettingsOpen = false
                                    toggleWidgetEditMode()
                                }
                            }
                            4 -> if (event.y in (height * .39f)..(height * .64f) &&
                                settingsHitX in (width * .13f)..(width * .87f)) {
                                settingsFocus = 14
                                onRequestDefaultHome?.invoke()
                            }
                            5 -> if (event.y in (height * .39f)..(height * .64f) &&
                                settingsHitX in (width * .13f)..(width * .87f)) {
                                settingsFocus = 6
                                context.startActivity(Intent(context, com.xiaoli.quanlian.QuanLianActivity::class.java))
                            }
                            6 -> if (event.y in (height * .39f)..(height * .64f) &&
                                settingsHitX in (width * .13f)..(width * .87f)) {
                                settingsFocus = 7
                                runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            }
                        }
                    }
                } else if (isDrawerOpen) {
                    if (event.eventTime - event.downTime >= android.view.ViewConfiguration.getLongPressTimeout()) {
                        val longIndex = drawerItemIndexAt(event.x, event.y)
                        if (longIndex >= 0) {
                            drawerIndex = longIndex
                            openAppActionMenu("drawer", longIndex, apps[longIndex])
                            invalidate()
                            performClick()
                            return true
                        }
                    }
                    if (event.y in (height * .19f)..(height * .29f) && event.x > width * .68f) {
                        drawerHeaderFocused = false
                        if (homeAppManageMode) closeDrawer() else editingFavorites = !editingFavorites
                    } else {
                        drawerHeaderFocused = false
                        val index = drawerItemIndexAt(event.x, event.y)
                        if (index >= 0) {
                            drawerIndex = index
                            val app = apps[index]
                            chooseDrawerApp(app)
                        } else if (event.x < width * .06f || event.x > width * .94f ||
                            event.y < height * .18f || event.y > height * .9f) {
                            closeDrawer()
                        }
                    }
                } else if (homeAppManageMode) {
                    val selectedSlot = homeItemIndexAt(event.x, event.y)
                    if (selectedSlot >= 0) homeManageSlot = selectedSlot
                    isDrawerOpen = true
                    editingFavorites = true
                    drawerIndex = 0
                    resetDrawerScroll()
                } else if (dy < -height * .06f) {
                    isDrawerOpen = true
                    drawerIndex = 0
                    resetDrawerScroll()
                } else if (kotlin.math.abs(dx) > width * .08f && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                    if (favorites.isNotEmpty()) {
                        homeIndex = if (dx < 0) (homeIndex + 1).coerceAtMost(favorites.lastIndex)
                            else (homeIndex - 1).coerceAtLeast(0)
                    }
                } else {
                    if (event.eventTime - event.downTime >= android.view.ViewConfiguration.getLongPressTimeout()) {
                        val longIndex = homeItemIndexAt(event.x, event.y)
                        if (longIndex >= 0 && favorites[longIndex].packageName != emptySlot.packageName) {
                            homeIndex = longIndex
                            openAppActionMenu("home", longIndex, favorites[longIndex])
                            invalidate()
                            performClick()
                            return true
                        }
                    }
                    if (showWeather && widgetIndexAt(event.x, event.y) == 3) {
                        openWeatherCityDialog()
                        invalidate()
                        return true
                    }
                    val index = homeItemIndexAt(event.x, event.y)
                    if (index >= 0) {
                        homeIndex = index
                        val app = favorites.getOrNull(index)
                        if (app != null && app.packageName == emptySlot.packageName) {
                            isDrawerOpen = true
                            editingFavorites = true
                            drawerIndex = 0
                            resetDrawerScroll()
                        } else app?.let { runCatching { context.startActivity(it.intent) } }
                    } else if (event.y > height * .78f && event.x < width * .42f) {
                        isDrawerOpen = true
                        drawerIndex = 0
                        resetDrawerScroll()
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
        if (!widgetEditMode && !isSettingsOpen && !isDrawerOpen && !homeIconEditMode && !homePositionEditMode && homeTopFocus == -1 &&
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) &&
            favorites.getOrNull(homeIndex)?.packageName != emptySlot.packageName) {
            handler.removeCallbacks(okLongPressRunnable)
            okPressPending = false
            okLongPressTriggered = true
            longPressReleaseConsumed = true
            favorites.getOrNull(homeIndex)?.let { openAppActionMenu("home", homeIndex, it) }
            return true
        }
        if (!widgetEditMode && !isSettingsOpen && isDrawerOpen && !drawerHeaderFocused &&
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER)) {
            handler.removeCallbacks(okLongPressRunnable)
            okPressPending = false
            okLongPressTriggered = true
            longPressReleaseConsumed = true
            apps.getOrNull(drawerIndex)?.let { openAppActionMenu("drawer", drawerIndex, it) }
            return true
        }
        if (widgetEditMode && (keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                keyCode == KeyEvent.KEYCODE_ENTER)) {
            resetSelectedWidget()
            return true
        }
        if (!widgetEditMode && !isSettingsOpen && !isDrawerOpen &&
            !homeIconEditMode && keyCode == KeyEvent.KEYCODE_MENU) {
            homeAppManageMode = true
            homeManageSlot = homeIndex.coerceIn(0, 4)
            invalidate()
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) &&
            (clockEditMenuOpen || clockComponentEditMode)) return true
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) && longPressReleaseConsumed) {
            longPressReleaseConsumed = false
            finishOkPressTracking()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (actionMenuSelectionConsumed) {
                actionMenuSelectionConsumed = false
                return true
            }
            if (finishOkPressTracking()) return true
        }
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) &&
            !event.isCanceled && !event.isLongPress && !actionMenuOpen &&
            !isSettingsOpen && !widgetEditMode && !homeIconEditMode && !homePositionEditMode) {
            if (!isDrawerOpen && homeTopFocus in 0..1) {
                if (homeTopFocus == 0) openClockEditMenu() else activateHomeTopFocus()
                return true
            }
            if (isDrawerOpen) {
                if (drawerHeaderFocused) {
                    if (homeAppManageMode) closeDrawer() else editingFavorites = !editingFavorites
                    invalidate()
                } else apps.getOrNull(drawerIndex)?.let { chooseDrawerApp(it) }
                return true
            }
            if (!homeAppManageMode) {
                favorites.getOrNull(homeIndex)?.let { app ->
                    if (app.packageName == emptySlot.packageName) {
                        isDrawerOpen = true
                        editingFavorites = true
                        drawerIndex = 0
                        resetDrawerScroll()
                        invalidate()
                    } else runCatching { context.startActivity(app.intent) }
                }
                return true
            }
        }
        if (keyCode == KeyEvent.KEYCODE_MENU && !event.isCanceled) {
            if (!event.isLongPress && !homeAppManageMode && !isSettingsOpen && !isDrawerOpen) {
                openControlCenter()
            }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            return handleBack()
        }
        if (controlCenterOpen) {
            val count = when (controlCenterPage) {
                0 -> 6
                1 -> controlInputs.size.coerceAtLeast(1).coerceAtMost(6)
                else -> 1
            }
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> controlCenterFocus = (controlCenterFocus - 1).coerceAtLeast(0)
                KeyEvent.KEYCODE_DPAD_DOWN -> controlCenterFocus = (controlCenterFocus + 1).coerceAtMost(count - 1)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> activateControlCenterItem()
                KeyEvent.KEYCODE_DPAD_LEFT -> when (controlCenterPage) {
                    1 -> { controlCenterPage = 0; controlCenterFocus = 1 }
                    2 -> adjustControlVolume(-1)
                    3 -> adjustControlBrightness(-1)
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> when (controlCenterPage) {
                    1 -> activateControlCenterItem()
                    2 -> adjustControlVolume(1)
                    3 -> adjustControlBrightness(1)
                }
            }
            invalidate()
            return true
        }
        if (clockEditMenuOpen) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> moveClockMenuFocus(SpatialFocusNavigator.UP)
                KeyEvent.KEYCODE_DPAD_DOWN -> moveClockMenuFocus(SpatialFocusNavigator.DOWN)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> beginClockComponentEdit(if (clockEditChoice == 0) "resize" else "move")
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> clockEditMenuOpen = false
            }
            invalidate()
            return true
        }
        if (clockComponentEditMode) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -1f else 1f
                    if (clockEditMode == "resize") setWidgetScale(0, clockScale + direction * .05f)
                    else adjustSelectedWidgetPosition(direction, 0f)
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (clockEditMode == "move") {
                    adjustSelectedWidgetPosition(0f, if (keyCode == KeyEvent.KEYCODE_DPAD_UP) -1f else 1f)
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> finishClockComponentEdit(true)
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> finishClockComponentEdit(false)
            }
            invalidate()
            return true
        }
        if (actionMenuOpen) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> moveActionMenuFocus(SpatialFocusNavigator.UP)
                KeyEvent.KEYCODE_DPAD_DOWN -> moveActionMenuFocus(SpatialFocusNavigator.DOWN)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    actionMenuSelectionConsumed = true
                    runAppAction()
                    return true
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> actionMenuOpen = false
            }
            invalidate()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_SETTINGS) {
            if (clockEditMenuOpen) clockEditMenuOpen = false
            if (clockComponentEditMode) finishClockComponentEdit(false)
            if (widgetEditMode) widgetEditMode = false
            if (homePositionEditMode) finishHomePositionEdit(false)
            homeIconEditMode = false
            homeAppManageMode = false
            openLauncherSettings()
            return true
        }
        if (homePositionEditMode && !isSettingsOpen && !isDrawerOpen) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> moveSelectedHomeIcon(-1f, 0f)
                KeyEvent.KEYCODE_DPAD_RIGHT -> moveSelectedHomeIcon(1f, 0f)
                KeyEvent.KEYCODE_DPAD_UP -> moveSelectedHomeIcon(0f, -1f)
                KeyEvent.KEYCODE_DPAD_DOWN -> moveSelectedHomeIcon(0f, 1f)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> finishHomePositionEdit(true)
            }
            invalidate()
            return true
        }
        if (homeAppManageMode && !isSettingsOpen && !isDrawerOpen) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    val next = moveHomeAppFocus(homeManageSlot, SpatialFocusNavigator.LEFT)
                    if (next != homeManageSlot) { homeManageSlot = next; animateSpatialFocus() }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val next = moveHomeAppFocus(homeManageSlot, SpatialFocusNavigator.RIGHT)
                    if (next != homeManageSlot) { homeManageSlot = next; animateSpatialFocus() }
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    isDrawerOpen = true
                    editingFavorites = true
                    drawerIndex = 0
                    resetDrawerScroll()
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> homeAppManageMode = false
            }
            invalidate()
            return true
        }
        if (homeIconEditMode && !isSettingsOpen && !isDrawerOpen) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> homeIconScale = (homeIconScale + .05f).coerceAtMost(1.6f)
                KeyEvent.KEYCODE_DPAD_DOWN -> homeIconScale = (homeIconScale - .05f).coerceAtLeast(.65f)
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    val next = moveHomeAppFocus(homeIndex, SpatialFocusNavigator.LEFT)
                    if (next != homeIndex) { homeIndex = next; animateSpatialFocus() }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val next = moveHomeAppFocus(homeIndex, SpatialFocusNavigator.RIGHT)
                    if (next != homeIndex) { homeIndex = next; animateSpatialFocus() }
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    // Consume the long-press repeat that opened edit mode; only a fresh OK saves.
                    if (event.repeatCount == 0) {
                        prefs.edit().putFloat("home_icon_scale", homeIconScale).apply()
                        homeIconEditMode = false
                    }
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                    homeIconScale = homeIconScaleBeforeEdit
                    homeIconEditMode = false
                }
            }
            invalidate()
            return true
        }
        if (!widgetEditMode && !isSettingsOpen && !isDrawerOpen &&
            keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.repeatCount == 0) event.startTracking()
            return true
        }
        if (!widgetEditMode && !isSettingsOpen && !isDrawerOpen &&
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) &&
            (homeTopFocus in 0..1 || favorites.isNotEmpty()) && !event.isLongPress) {
            if (event.repeatCount == 0) {
                event.startTracking()
                if (homeTopFocus == -1) beginOkPressTracking("home", homeIndex)
            }
            return true
        }
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
                KeyEvent.KEYCODE_MENU -> {
                    toggleWidgetEditMode()
                    return true
                }
            }
            return true
        }
        if (isSettingsOpen) {
            if (settingsFocus in 30..36) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> moveSettingsCategoryFocus(SpatialFocusNavigator.UP)
                    KeyEvent.KEYCODE_DPAD_DOWN -> moveSettingsCategoryFocus(SpatialFocusNavigator.DOWN)
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                        settingsFocus = settingsCategoryControl(settingsFocus - 30)
                        animateSpatialFocus()
                    }
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { closeLauncherSettings(); return true }
                }
                invalidate()
                return true
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
                settingsCategoryForControl(settingsFocus)?.let { category ->
                    if (settingsFocus == settingsCategoryControl(category) ||
                        SettingsFocusNavigator.next(settingsFocus, -1) == settingsFocus) {
                        settingsFocus = 30 + category
                        invalidate()
                        return true
                    }
                }
            }
            when (keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { closeLauncherSettings(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> moveSettingsFocus(-1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> moveSettingsFocus(1)
                KeyEvent.KEYCODE_DPAD_UP -> moveSettingsFocus(-2)
                KeyEvent.KEYCODE_DPAD_DOWN -> moveSettingsFocus(2)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (settingsFocus == 23) resetHomeIconPositions()
                    else if (settingsFocus == 9 || settingsFocus == 10) toggleHomeComponent(settingsFocus)
                    else if (settingsFocus == 15) {
                        use24HourClock = !use24HourClock
                        prefs.edit().putBoolean("clock_24_hour", use24HourClock).apply()
                    } else if (settingsFocus == 16) {
                        showClockSeconds = !showClockSeconds
                        prefs.edit().putBoolean("clock_seconds", showClockSeconds).apply()
                        updateClockTick()
                    } else if (settingsFocus == 8) cycleHomeIconScale()
                    else if (settingsFocus == 22) { isSettingsOpen = false; toggleWidgetEditMode() }
                    else if (settingsFocus in 0..2) setDrawerColumns(settingsFocus + 4)
                    else if (settingsFocus in 17..19) setDrawerRows(settingsFocus - 15)
                    else if (settingsFocus == 3) onChooseWallpaper?.invoke()
                    else if (settingsFocus == 4) toggleDynamicWallpaper()
                    else if (settingsFocus == 5) clearWallpaper()
                    else if (settingsFocus == 24) context.startActivity(
                        Intent(context, com.xiaoli.quanlian.QuanLianActivity::class.java)
                            .putExtra(com.xiaoli.quanlian.QuanLianActivity.EXTRA_WALLPAPER_MODE, true))
                    else if (settingsFocus == 6) context.startActivity(Intent(context, com.xiaoli.quanlian.QuanLianActivity::class.java))
                    else if (settingsFocus == 21) {
                        isSettingsOpen = false
                        isDrawerOpen = true
                        returnToSettingsAfterDrawer = true
                        editingFavorites = true
                        drawerIndex = 0
                        resetDrawerScroll()
                    }
                    else if (settingsFocus == 13) closeLauncherSettings()
                    else if (settingsFocus == 14) onRequestDefaultHome?.invoke()
                    else if (settingsFocus == 7) runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
            invalidate(); return true
        }
        if (isDrawerOpen) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { closeDrawer(); return true }
                KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> {
                    if (!homeAppManageMode) editingFavorites = !editingFavorites
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> moveDrawerSpatialFocus(SpatialFocusNavigator.LEFT)
                KeyEvent.KEYCODE_DPAD_RIGHT -> moveDrawerSpatialFocus(SpatialFocusNavigator.RIGHT)
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (apps.isEmpty() && !drawerHeaderFocused) drawerHeaderFocused = true
                    else moveDrawerSpatialFocus(SpatialFocusNavigator.UP)
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> moveDrawerSpatialFocus(SpatialFocusNavigator.DOWN)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (event.repeatCount == 0) {
                        event.startTracking()
                        if (!drawerHeaderFocused) beginOkPressTracking("drawer", drawerIndex)
                    }
                    return true
                }
            }
            val metrics = drawerMetrics()
            val cols = metrics.columns
            val visibleRows = metrics.rows
            val maxScroll = metrics.maxScroll(apps.size)
            val focusedRow = drawerIndex / cols
            drawerScrollRow = when {
                focusedRow < drawerScrollRow -> focusedRow
                focusedRow >= drawerScrollRow + visibleRows -> focusedRow - visibleRows + 1
                else -> drawerScrollRow
            }.coerceIn(0, maxScroll)
            animateDrawerScrollTo(drawerScrollRow)
            invalidate(); return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_MENU -> { if (!event.isLongPress) openControlCenter(); return true }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (homeTopFocus == -1) moveHomeFocusToTop()
                else {
                    // Preserve the launcher's established UP gesture for opening the app drawer.
                    isDrawerOpen = true
                    homeTopFocus = -1
                    drawerIndex = 0
                    resetDrawerScroll()
                    invalidate()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (homeTopFocus in 0..1) {
                    moveHomeFocusToApps()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                moveHomeSpatialFocus(SpatialFocusNavigator.LEFT)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                moveHomeSpatialFocus(SpatialFocusNavigator.RIGHT)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }
}
