package com.xiaoli.launcher

import android.content.Context
import android.content.ComponentName
import android.app.role.RoleManager
import android.content.Intent
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.media.AudioManager
import android.media.tv.TvInputManager
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.net.Uri
import android.os.Handler
import android.os.SystemClock
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
import android.view.VelocityTracker
import android.widget.OverScroller
import android.view.View
import android.view.ViewConfiguration
import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import java.text.SimpleDateFormat
import java.io.File
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
    // QR for the user's Baidu Netdisk share; the extraction code is prefilled.
    private val quanLianDownloadQrBitmap: Bitmap? by lazy {
        runCatching {
            val matrix = QRCodeWriter().encode(
                "https://pan.baidu.com/s/1XEKJzSC9qvFMumkhwbhYIw?pwd=1234",
                BarcodeFormat.QR_CODE, 512, 512
            )
            Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).also { bitmap ->
                for (y in 0 until 512) for (x in 0 until 512) {
                    bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                }
            }
        }.getOrNull()
    }
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
    // Touch scrolling follows the finger continuously; fling uses Android's spline physics.
    private val drawerFlingScroller = OverScroller(context)
    private var drawerTouchTracker: VelocityTracker? = null
    private var drawerTouchEligible = false
    private var drawerTouchScrolling = false
    private var drawerTouchDownY = 0f
    private var drawerTouchStartVisualRow = 0f
    private val drawerTouchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var spatialFocusAnimator: ValueAnimator? = null
    private var spatialFocusScale = 1f
    // Header action is a first-class D-pad target, separate from the app grid.
    private var drawerHeaderFocused = false
    // Remember the grid item that entered the header so Down returns to that exact item.
    private var drawerHeaderReturnIndex = 0
    // Aerial-style overlays restore the previously focused home target when dismissed.
    private var drawerReturnHomeTopFocus = -1
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
    private var controlCenterInputOffset = 0
    private var controlSliderTouchActive = false
    private var controlListTouchActive = false
    private var controlListTouchDragging = false
    private var controlListTouchDownY = 0f
    private var controlListTouchLastY = 0f
    private var controlListTouchTravel = 0f
    private var controlCecEnabled = prefs.getBoolean("control_cec_switch", false)
    private var controlBootInputId = prefs.getString("control_boot_input_id", "").orEmpty()
    private var bootInputLaunchChecked = false
    private var controlDefaultHome = false
    private var cachedNetworkLabel = "网络状态未知"
    private var networkLabelCheckedAt = 0L
    private var xgimiBrightnessCached: Int? = null
    private var xgimiBrightnessCheckedAt = 0L
    private var xgimiBrightnessHasBeenRead = false
    private var hisenseBrightnessCached: Int? = null
    private var hisenseBrightnessCheckedAt = 0L
    private var hisenseBrightnessHasBeenRead = false
    private var controlExternalVolume = false
    private var controlStuckVolumeSteps = 0
    private var controlCenterReturnFocus = -1
    private data class ControlInput(val id: String, val label: String, val kind: String, val switchIntent: Intent?, val setupIntent: Intent?, val connected: Boolean = false, val deviceName: String? = null)
    private var controlInputs: List<ControlInput> = emptyList()
    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val packageChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            refreshInstalledApps()
        }
    }
    private val controlCecReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context, intent: Intent) {
            if (!controlCecEnabled) return
            refreshControlInputs()
            val candidates = buildSet {
                intent.getStringExtra(TvInputInfo.EXTRA_INPUT_ID)?.let(::add)
                intent.extras?.keySet()?.forEach { key ->
                    runCatching { intent.getStringExtra(key) }.getOrNull()?.let(::add)
                }
            }
            val manager = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
            val target = controlInputs.firstOrNull { source ->
                source.id in candidates || candidates.any { candidate ->
                    runCatching { manager?.getTvInputInfo(candidate)?.parentId == source.id }.getOrDefault(false)
                }
            }
            target?.let(::openControlInput)
        }
    }

    private fun currentBootCount(): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return null
        return runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
        }.getOrNull()
    }

    private fun toggleControlBootInput(input: ControlInput) {
        controlBootInputId = if (controlBootInputId == input.id) "" else input.id
        val edit = prefs.edit().putString("control_boot_input_id", controlBootInputId)
        currentBootCount()?.let { edit.putInt("control_last_boot_count", it) }
        edit.apply()
        android.widget.Toast.makeText(context,
            if (controlBootInputId.isEmpty()) "已取消开机默认信源" else "已设为开机默认信源：${input.label}",
            android.widget.Toast.LENGTH_SHORT).show()
        invalidate()
    }

    private fun maybeOpenBootInput() {
        if (bootInputLaunchChecked) return
        bootInputLaunchChecked = true
        val current = currentBootCount() ?: return
        if (controlBootInputId.isBlank()) return
        val previous = prefs.getInt("control_last_boot_count", current)
        if (previous == current) return
        prefs.edit().putInt("control_last_boot_count", current).apply()
        handler.postDelayed({
            refreshControlInputs()
            val input = controlInputs.firstOrNull { it.id == controlBootInputId }
            if (input != null) openControlInput(input) else openControlInputPicker()
        }, 2500L)
    }

    private fun toggleControlCec() {
        controlCecEnabled = !controlCecEnabled
        prefs.edit().putBoolean("control_cec_switch", controlCecEnabled).apply()
        android.widget.Toast.makeText(context,
            if (controlCecEnabled) "已开启 HDMI-CEC 自动切换" else "已关闭 HDMI-CEC 自动切换",
            android.widget.Toast.LENGTH_SHORT).show()
        invalidate()
    }
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
        spatialFocusAnimator = ValueAnimator.ofFloat(1f, 1.05f).apply {
            duration = 200L
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

    // Shared static remote-focus light: it follows the focused control without a running animation.
    private fun drawFocusChase(c: Canvas, source: RectF, cornerRadius: Float) {
        if (source.width() <= 0f || source.height() <= 0f) return
        val stroke = (minOf(source.width(), source.height()) * .018f).coerceAtLeast(2f)
        val bounds = RectF(source).apply { inset(stroke * .45f, stroke * .45f) }
        val radius = (cornerRadius - stroke * .45f).coerceAtLeast(stroke)
        val oldStyle = paint.style
        val oldCap = paint.strokeCap
        val oldJoin = paint.strokeJoin
        val oldWidth = paint.strokeWidth
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND

        // Draw a restrained cyan halo and crisp inner edge only when the view invalidates.
        paint.color = android.graphics.Color.argb(150, 91, 224, 255)
        paint.strokeWidth = stroke * 1.15f
        paint.setShadowLayer(stroke * 2f, 0f, 0f,
            android.graphics.Color.argb(95, 39, 191, 255))
        c.drawRoundRect(bounds, radius, radius, paint)

        paint.clearShadowLayer()
        paint.color = android.graphics.Color.argb(225, 145, 238, 255)
        paint.strokeWidth = stroke * .48f
        c.drawRoundRect(bounds, radius, radius, paint)

        paint.clearShadowLayer()
        paint.style = oldStyle
        paint.strokeCap = oldCap
        paint.strokeJoin = oldJoin
        paint.strokeWidth = oldWidth
    }

    private fun animateDrawerScrollTo(targetRow: Int) {
        drawerFlingScroller.abortAnimation()
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
        drawerFlingScroller.abortAnimation()
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
        refreshInstalledApps()
        reloadSavedWallpaper()
        handler.post(clockTick)
        updateAnimationLoop()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        runCatching {
            val filter = IntentFilter("droidlogic.tv.action.OTP_INPUT_SOURCE_CHANGED")
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(controlCecReceiver, filter, Context.RECEIVER_EXPORTED)
            else context.registerReceiver(controlCecReceiver, filter)
        }
        runCatching {
            val packageFilter = IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addDataScheme("package")
            }
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(packageChangeReceiver, packageFilter, Context.RECEIVER_EXPORTED)
            else context.registerReceiver(packageChangeReceiver, packageFilter)
        }
        maybeOpenBootInput()
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
        runCatching { context.unregisterReceiver(controlCecReceiver) }
        runCatching { context.unregisterReceiver(packageChangeReceiver) }
        super.onDetachedFromWindow()
    }

    private var appLoadGeneration = 0

    fun refreshInstalledApps() {
        val generation = ++appLoadGeneration
        thread(name = "launcher-app-scan") {
            val pm = context.packageManager
            val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = runCatching {
                pm.queryIntentActivities(query, PackageManager.MATCH_ALL)
                    .mapNotNull { info ->
                        val pkg = info.activityInfo.packageName
                        if (pkg == context.packageName) null else AppEntry(
                            info.loadLabel(pm).toString(), pkg,
                            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                                .setClassName(pkg, info.activityInfo.name)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }.sortedBy { it.label.lowercase(Locale.getDefault()) }
            }.getOrDefault(emptyList())
            handler.post {
                if (generation != appLoadGeneration) return@post
                apps.clear()
                apps.addAll(resolved)
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
                appIconCache.keys.retainAll(resolved.mapTo(HashSet()) { it.packageName })
                invalidate()
            }
        }
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
                3 -> {
                    actionMenuOpen = false
                    isDrawerOpen = true
                    editingFavorites = false
                    drawerIndex = 0
                    resetDrawerScroll()
                }
                4 -> launchActionMenuApp(app)
                5 -> uninstallActionMenuApp(app)
            }
        } else {
            when (actionMenuChoice) {
                0 -> if (app != null) {
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
                1 -> launchActionMenuApp(app)
                2 -> uninstallActionMenuApp(app)
            }
            actionMenuOpen = false
        }
        invalidate()
    }

    private fun launchActionMenuApp(app: AppEntry?) {
        actionMenuOpen = false
        if (app == null) return
        runCatching { context.startActivity(app.intent) }
            .onFailure { android.widget.Toast.makeText(context, "无法打开此应用", android.widget.Toast.LENGTH_SHORT).show() }
        invalidate()
    }

    private fun uninstallActionMenuApp(app: AppEntry?) {
        actionMenuOpen = false
        if (app == null) return
        runCatching {
            val uninstall = Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(uninstall)
        }.onFailure {
            android.widget.Toast.makeText(context, "系统不允许卸载此应用", android.widget.Toast.LENGTH_LONG).show()
        }
        invalidate()
    }

    private fun drawAppActionMenu(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.color = 0xB9001028.toInt()
        c.drawRect(0f, 0f, w, h, paint)
        val isHomeMenu = actionMenuSource == "home"
        val panel = if (isHomeMenu) RectF(w * .34f, h * .12f, w * .66f, h * .88f)
            else RectF(w * .34f, h * .22f, w * .66f, h * .78f)
        paint.color = 0xF20A3470.toInt()
        c.drawRoundRect(panel, h * .025f, h * .025f, paint)
        val drawerAppAlreadyPinned = actionMenuSource == "drawer" && favorites.any { it.packageName == actionMenuPackage }
        val options = if (isHomeMenu) listOf("移动 APP", "隐藏 APP", "调整位置", "返回抽屉", "打开应用", "卸载应用")
            else listOf(if (drawerAppAlreadyPinned) "从主屏幕移除" else "加入主屏幕", "打开应用", "卸载应用", "返回")
        text(c, if (isHomeMenu) "桌面应用管理" else "应用管理",
            panel.left + panel.width() * .09f, panel.top + panel.height() * (if (isHomeMenu) .105f else .13f), h * .031f, -1, true)
        options.forEachIndexed { i, label ->
            val top = panel.top + panel.height() * (if (isHomeMenu) .20f + i * .125f else .25f + i * .17f)
            val rowHeight = panel.height() * (if (isHomeMenu) .105f else .135f)
            val baseRow = RectF(panel.left + panel.width() * .07f, top,
                panel.right - panel.width() * .07f, top + rowHeight)
            val row = if (i == actionMenuChoice) scaledFocusRect(baseRow) else baseRow
            paint.color = if (i == actionMenuChoice) 0xFF1688E8.toInt() else 0x554D9BEA
            c.drawRoundRect(row, h * .012f, h * .012f, paint)
            paint.textAlign = Paint.Align.CENTER
            text(c, label, row.centerX(), row.centerY() + h * .009f, h * .026f, -1, i == actionMenuChoice)
            if (i == actionMenuChoice) drawFocusChase(c, row, h * .012f)
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
                    val previousPage = controlCenterPage
                    controlCenterPage = 0
                    controlCenterFocus = when (previousPage) { 1 -> 1; 4 -> 2; 2 -> 3; 3 -> 4; else -> 0 }
                } else {
                    controlCenterOpen = false
                    homeTopFocus = controlCenterReturnFocus
                    controlCenterReturnFocus = -1
                    animateSpatialFocus()
                }
            }
            isSettingsOpen -> closeLauncherSettings()
            isDrawerOpen -> {
                val metrics = drawerMetrics()
                val focusedRow = if (metrics.columns > 0) drawerIndex / metrics.columns else 0
                val wasScrolled = drawerScrollRow > 0 || drawerScrollVisualRow > 0.01f
                // Match Aerial: Back from inside a scrolled grid returns focus to its top row
                // before it dismisses the surface. A second Back closes the drawer.
                if (!drawerHeaderFocused && (focusedRow > 0 || wasScrolled)) {
                    drawerIndex = (drawerIndex % metrics.columns.coerceAtLeast(1))
                        .coerceIn(0, (apps.size - 1).coerceAtLeast(0))
                    drawerHeaderFocused = false
                    drawerHeaderReturnIndex = drawerIndex
                    drawerScrollRow = 0
                    animateDrawerScrollTo(0)
                    animateSpatialFocus()
                } else closeDrawer()
            }
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
            drawerReturnHomeTopFocus = -1
            animateSpatialFocus()
        } else if (drawerReturnHomeTopFocus in 0..1) {
            homeTopFocus = drawerReturnHomeTopFocus
            drawerReturnHomeTopFocus = -1
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

    private var wallpaperLoadGeneration = 0

    fun setWallpaper(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        prefs.edit().putString("wallpaper_uri", uri.toString()).apply()
        loadWallpaperAsync(uri)
    }

    fun reloadSavedWallpaper() {
        val value = prefs.getString("wallpaper_uri", null)
        val uri = value?.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }
        loadWallpaperAsync(uri)
    }

    private fun loadWallpaperAsync(uri: Uri?) {
        val generation = ++wallpaperLoadGeneration
        thread(name = "launcher-wallpaper-load") {
            val bitmap = uri?.let(::decodeWallpaper)
                ?: runCatching {
                    BitmapFactory.decodeResource(context.resources, R.drawable.default_wallpaper)
                }.getOrNull()
            handler.post {
                if (generation != wallpaperLoadGeneration) {
                    bitmap?.recycle()
                    return@post
                }
                val previous = wallpaperBitmap
                wallpaperBitmap = bitmap
                if (previous !== bitmap) previous?.recycle()
                invalidate()
            }
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
                if (clockEditChoice == i) drawFocusChase(c, row, h * .014f)
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
        drawFocusChase(c, rect, 12f)
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
                if (focused) drawFocusChase(c, scaledFocusRect(icon), icon.width() * .20f)
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
                val manageFocusRect = scaledFocusRect(RectF(selected.left - 8f, selected.top - 8f,
                    selected.right + 8f, selected.bottom + 8f))
                c.drawRoundRect(manageFocusRect, 18f, 18f, paint)
                paint.style = Paint.Style.FILL
                drawFocusChase(c, manageFocusRect, 18f)
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
        val focusRect = scaledFocusRect(rect)
        drawFocusChase(c, focusRect, (h * .018f).coerceAtLeast(10f))
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
            val infos = manager.tvInputList
            infos.asSequence()
                .filter { info ->
                    info.parentId == null && (info.isPassthroughInput ||
                        info.type == TvInputInfo.TYPE_TUNER && runCatching {
                            info.serviceInfo.applicationInfo.flags and
                                (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                        }.getOrDefault(true))
                }
                .filter { info ->
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.N ||
                        runCatching { !info.isHidden(context) }.getOrDefault(true)
                }
                .mapNotNull { info ->
                    val custom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                        runCatching { info.loadCustomLabel(context)?.toString() }.getOrNull() else null
                    val label = custom ?: runCatching { info.loadLabel(context).toString() }
                        .getOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val kind = when (info.type) {
                        TvInputInfo.TYPE_HDMI -> "HDMI"
                        TvInputInfo.TYPE_DISPLAY_PORT -> "DisplayPort"
                        TvInputInfo.TYPE_TUNER -> "电视调谐器"
                        TvInputInfo.TYPE_COMPOSITE, TvInputInfo.TYPE_SVIDEO,
                        TvInputInfo.TYPE_COMPONENT, TvInputInfo.TYPE_SCART -> "AV视频"
                        TvInputInfo.TYPE_VGA -> "VGA"
                        TvInputInfo.TYPE_DVI -> "DVI"
                        else -> if (info.isPassthroughInput) "外接输入" else "电视信号"
                    }
                    val cecChild = infos.firstOrNull { it.parentId == info.id }
                    val cecLabel = cecChild?.let {
                        runCatching { it.loadLabel(context).toString() }.getOrNull()
                    }?.takeIf { it.isNotBlank() && !Regex(
                        "^(Playback|Recorder|Tuner|Audio ?System|Audio|TV|Reserved)[ _]?\\d*$",
                        RegexOption.IGNORE_CASE
                    ).matches(it) }
                    val targetInfo = cecChild ?: info
                    val genericRoute = runCatching {
                        val uri = if (info.isPassthroughInput)
                            TvContract.buildChannelUriForPassthroughInput(targetInfo.id)
                        else TvContract.buildChannelsUriForInput(targetInfo.id)
                        Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }.getOrNull()
                    val tclRoute = tclInputRoute(info, kind, label)
                    val droidLogicRoute = droidLogicInputRoute(info)
                    val switchIntent = tclRoute ?: genericRoute?.takeIf {
                        runCatching { context.packageManager.resolveActivity(it, 0) != null }.getOrDefault(false)
                    } ?: droidLogicRoute ?: genericRoute
                    val state = runCatching { manager.getInputState(info.id) }
                        .getOrDefault(TvInputManager.INPUT_STATE_DISCONNECTED)
                    ControlInput(
                        id = info.id,
                        label = label,
                        kind = kind,
                        switchIntent = switchIntent,
                        setupIntent = runCatching { info.createSetupIntent() }.getOrNull(),
                        connected = cecLabel != null || state == TvInputManager.INPUT_STATE_CONNECTED && kind != "电视调谐器",
                        deviceName = cecLabel,
                    )
                }
                .distinctBy { it.id }
                .sortedWith(compareBy<ControlInput>({ it.kind }, { it.label }))
                .toList()
        }.getOrDefault(emptyList()).let { discovered ->
            if (discovered.any { it.kind == "HDMI" }) discovered
            else {
                val konka = konkaFallbackInputs()
                if (konka.isNotEmpty()) discovered + konka
                else {
                    val vidaa = vidaaFallbackInputs()
                    if (vidaa.isNotEmpty()) discovered + vidaa
                    else {
                    val xgimi = xgimiMstarFallbackInputs()
                    if (xgimi.isNotEmpty()) discovered + xgimi
                    else {
                        val tcl = tclFallbackInputs()
                        if (tcl.isNotEmpty()) discovered + tcl else discovered + xiaomiFallbackInputs()
                    }
                    }
                }
            }
        }
    }

    private fun vidaaFallbackInputs(): List<ControlInput> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) return emptyList()
        if (runCatching { context.packageManager.getPackageInfo("com.hisense.tv.tiletransition", 0) }.isSuccess)
            return emptyList()
        val config = File("/system/etc/silo_config.xml")
        if (!config.canRead()) return emptyList()
        val xml = runCatching { config.readText() }.getOrNull() ?: return emptyList()
        val physical = Regex("<physical_inputs>(.*?)</physical_inputs>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)?.groupValues?.get(1) ?: return emptyList()
        val silo = Regex("<silo\\s+name=\"([^\"]+)\"[^>]*>(.*?)</silo>", RegexOption.DOT_MATCHES_ALL)
        val type = Regex("<type>\\s*([A-Z_]+)\\s*</type>")
        val input = Regex("<input>\\s*HDMI\\s*(\\d+)\\s*</input>", RegexOption.IGNORE_CASE)
        val ids = mapOf(1 to 1281, 2 to 1282)
        val current = runCatching {
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java, String::class.java)
                .invoke(null, "persist.sys.last_source", "")?.toString()?.toIntOrNull() ?: 0
        }.getOrDefault(0)
        val hdmi = silo.findAll(physical).mapNotNull { match ->
            val body = match.groupValues[2]
            if (type.find(body)?.groupValues?.get(1) != "HDMI") return@mapNotNull null
            val port = input.find(body)?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            val id = ids[port] ?: return@mapNotNull null
            ControlInput(
                id = "vidaa:$id",
                label = "HDMI $port",
                kind = "HDMI",
                switchIntent = Intent("com.jamdeo.tv.policy.SILO_SWITCH_EVENT")
                    .putExtra("silo_input_src_id", id),
                setupIntent = null,
                connected = current == id,
                deviceName = null,
            )
        }.distinctBy { it.id }.sortedBy { it.label }.toList()
        return if (hdmi.isEmpty()) emptyList() else hdmi + ControlInput(
            id = "vidaa:257",
            label = "电视",
            kind = "电视调谐器",
            switchIntent = Intent("com.jamdeo.tv.policy.SILO_SWITCH_EVENT").putExtra("silo_input_src_id", 257),
            setupIntent = null,
            connected = current == 257,
            deviceName = null,
        )
    }

    private fun konkaFallbackInputs(): List<ControlInput> = runCatching {
        val player = ComponentName("com.konka.tvsettings", "com.konka.tvsettings.RootActivity")
        val activity = context.packageManager.getActivityInfo(player, 0)
        if (!activity.exported || !activity.enabled || !activity.applicationInfo.enabled) return emptyList()
        val managerClass = Class.forName("com.konka.android.tv.KKCommonManager")
        val sourceClass = Class.forName("com.konka.android.tv.KKCommonManager\$EN_KK_INPUT_SOURCE")
        val manager = managerClass.getMethod("getInstance", Context::class.java)
            .invoke(null, context.applicationContext)
        val reported = managerClass.getMethod("getTVInputSourceList").invoke(manager) as? List<*>
            ?: return emptyList()
        val connected = runCatching {
            managerClass.getMethod("getAvaliableInputSourceList").invoke(manager) as? List<*>
        }.getOrNull().orEmpty()
        val labelMethod = runCatching {
            managerClass.getMethod("getInputSourceName", sourceClass)
        }.getOrNull()
        reported.filterIsInstance<Enum<*>>().map { source ->
            val label = runCatching { labelMethod?.invoke(manager, source) as? String }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: source.name
            val kind = when {
                source.name.startsWith("HDMI") || source.name == "EXTERNAL_8K_HDMI" -> "HDMI"
                source.name == "DP" || source.name == "TYPE_C" || source.name == "EXTERNAL_8K_DP" -> "DisplayPort"
                source.name.startsWith("AV") || source.name.startsWith("YPBPR") || source.name.startsWith("SVIDEO") -> "AV视频"
                source.name == "ATV" || source.name == "DTMB" || source.name.startsWith("DVB_") ||
                    source.name == "ATSC" || source.name == "ISDB_T" -> "电视调谐器"
                else -> "其他输入"
            }
            ControlInput(
                id = "konka:${source.name}",
                label = label,
                kind = kind,
                switchIntent = Intent("com.konka.tvsettings.intent.action.RootActivity")
                    .setComponent(player).putExtra("source", source.ordinal)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                setupIntent = null,
                connected = Build.VERSION.SDK_INT >= 23 && source in connected,
                deviceName = null,
            )
        }.distinctBy { it.id }.sortedWith(compareBy({ it.kind }, { it.label }))
    }.onFailure {
        android.util.Log.w("XiaoLiControlCenter", "Could not read Konka native inputs", it)
    }.getOrDefault(emptyList())

    private fun xgimiMstarFallbackInputs(): List<ControlInput> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return emptyList()
        val player = ComponentName("com.xgimi.xhplayer", "com.xgimi.xhplayer.InputActivity")
        val activity = runCatching { context.packageManager.getActivityInfo(player, 0) }.getOrNull()
            ?: return emptyList()
        if (!activity.exported || !activity.enabled || !activity.applicationInfo.enabled) return emptyList()
        val permission = activity.permission
        if (permission != null && context.checkCallingOrSelfPermission(permission) != PackageManager.PERMISSION_GRANTED)
            return emptyList()
        return runCatching {
            val managerClass = Class.forName("com.xgimi.clients.GimiConfigManager")
            val manager = managerClass.getMethod("getInstance").invoke(null)
            val raw = managerClass.getMethod("getXgimiConfig", String::class.java)
                .invoke(manager, "global") as? String ?: return emptyList()
            val signals = JSONObject(raw).optJSONArray("signal") ?: return emptyList()
            val connected = xgimiMstarConnectedSources()
            val pattern = Regex("HDMI([1-9][0-9]*)?")
            (0 until signals.length()).mapNotNull { index ->
                val signal = signals.optJSONObject(index) ?: return@mapNotNull null
                val switch = signal.optString("sw").trim().uppercase(Locale.ROOT)
                val match = pattern.matchEntire(switch) ?: return@mapNotNull null
                val port = match.groupValues[1].ifEmpty { "1" }.toIntOrNull() ?: return@mapNotNull null
                val sourceName = "HDMI$port"
                val nativeLabel = signal.optString("name").trim()
                ControlInput(
                    id = "xgimi:$sourceName",
                    label = nativeLabel.takeUnless { it.isBlank() || it.equals("HDMI", true) } ?: "HDMI $port",
                    kind = "HDMI",
                    switchIntent = Intent("com.xgimi.action.hdmiPlayer").setComponent(player)
                        .putExtra("launcher_start_source", sourceName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    setupIntent = null,
                    connected = connected[signal.optString("source")] == true,
                    deviceName = null,
                )
            }.distinctBy { it.id }
        }.onFailure {
            android.util.Log.w("XiaoLiControlCenter", "Could not read XGIMI MStar inputs", it)
        }.getOrDefault(emptyList())
    }

    private fun xgimiMstarConnectedSources(): Map<String, Boolean> = runCatching {
        val commonClass = Class.forName("com.mstar.android.tv.TvCommonManager")
        val common = commonClass.getMethod("getInstance").invoke(null)
        val flags = commonClass.getMethod("GetInputSourceStatus").invoke(common) as? BooleanArray
            ?: return emptyMap()
        val sourceClass = Class.forName("com.mstar.android.tvapi.common.vo.TvOsType\$EnumInputSource")
        sourceClass.enumConstants.orEmpty().filterIsInstance<Enum<*>>()
            .associate { it.name to (flags.getOrNull(it.ordinal) == true) }
    }.getOrDefault(emptyMap())

    private fun tclFallbackInputs(): List<ControlInput> {
        val player = ComponentName("com.tcl.tv", "com.tcl.tv.TVActivity")
        val activity = runCatching { context.packageManager.getActivityInfo(player, 0) }.getOrNull() ?: return emptyList()
        if (!activity.exported || !activity.enabled || !activity.applicationInfo.enabled) return emptyList()
        fun source(name: String, ordinal: Int, kind: String, label: String) = ControlInput(
            id = "tcl:$name",
            label = label,
            kind = kind,
            switchIntent = Intent().setComponent(player).putExtra("targetSourceId", ordinal)
                .putExtra("referer", "com.tcl.settings.module_source")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            setupIntent = null,
            connected = false,
            deviceName = null,
        )
        return listOf(
            source("HDMI1", 8, "HDMI", "HDMI 1"),
            source("HDMI2", 9, "HDMI", "HDMI 2"),
            source("HDMI3", 10, "HDMI", "HDMI 3"),
            source("HDMI4", 11, "HDMI", "HDMI 4"),
            source("DTV", 2, "电视调谐器", "电视"),
            source("AV1", 3, "AV视频", "AV"),
        )
    }

    private fun xiaomiFallbackInputs(): List<ControlInput> {
        if (isPlaybackDevice()) return emptyList()
        val player = ComponentName("com.xiaomi.mitv.tvplayer", "com.xiaomi.mitv.tvplayer.ExternalSourceActivity")
        val activity = runCatching { context.packageManager.getActivityInfo(player, 0) }.getOrNull() ?: return emptyList()
        if (!activity.exported || !activity.enabled || !activity.applicationInfo.enabled) return emptyList()
        val ports = if (Build.MODEL == "MiTV4C") 2 else 3
        return (1..ports).map { port ->
            val inputId = 22 + port
            ControlInput(
                id = "xiaomi:$inputId",
                label = "HDMI $port",
                kind = "HDMI",
                switchIntent = Intent("com.xiaomi.mitv.tvplayer.EXTSRC_PLAY")
                    .setComponent(player)
                    .putExtra("input", inputId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                setupIntent = null,
                connected = false,
                deviceName = null,
            )
        }
    }

    private fun tclInputRoute(info: TvInputInfo, kind: String, label: String): Intent? {
        if (!info.id.startsWith("com.tcl.tvinput/")) return null
        val player = ComponentName("com.tcl.tv", "com.tcl.tv.TVActivity")
        val activity = runCatching { context.packageManager.getActivityInfo(player, 0) }.getOrNull() ?: return null
        if (!activity.exported || !activity.enabled || !activity.applicationInfo.enabled) return null
        val hardware = Regex("/HW(\\d+)$").find(info.id)?.groupValues?.get(1)?.toIntOrNull()
        val ordinal = hardware?.takeIf { kind != "外接输入" } ?: when (kind) {
            "HDMI" -> Regex("(\\d)").find(label)?.groupValues?.get(1)?.toIntOrNull()
                ?.takeIf { it in 1..4 }?.let { 7 + it }
            "电视调谐器" -> 2
            "AV视频" -> 3
            else -> null
        } ?: return null
        return Intent().setComponent(player)
            .putExtra("targetSourceId", ordinal)
            .putExtra("referer", "com.tcl.settings.module_source")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun droidLogicInputRoute(info: TvInputInfo): Intent? {
        if (info.type != TvInputInfo.TYPE_HDMI) return null
        val player = ComponentName("com.droidlogic.tvsource", "com.droidlogic.tvsource.DroidLogicTv")
        val intent = Intent(Intent.ACTION_MAIN).setComponent(player)
            .putExtra("id", info.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = runCatching { context.packageManager.resolveActivity(intent, 0)?.activityInfo }.getOrNull()
            ?: return null
        if (!activity.exported || !activity.enabled || !activity.applicationInfo.enabled) return null
        val permission = activity.permission
        if (permission != null && context.checkCallingOrSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return null
        return intent
    }

    private fun openControlInputPicker(): Boolean {
        val pm = context.packageManager
        val changhong = Intent("CH_MENU_SOURCE")
            .setComponent(ComponentName("com.changhong.SourceMenu", "com.changhong.source.SourceMenuService"))
            .putExtra("MenuSource", "menuSource")
            .putExtra("keyCode", 2001)
        if (runCatching {
                val info = pm.resolveService(changhong, 0)?.serviceInfo
                info != null && info.exported && info.enabled && info.applicationInfo.enabled &&
                    (info.permission.isNullOrEmpty() ||
                        context.checkCallingOrSelfPermission(info.permission) == PackageManager.PERMISSION_GRANTED) &&
                    context.startService(changhong) != null
            }.getOrDefault(false)) return true

        val xgimi = Intent("com.xgimi.settings.SETTINGS")
            .setComponent(ComponentName("com.android.newsettings", "com.xgimi.newsettings.SettingService"))
            .putExtra("data", "Settings://com.xgimi.settings.signalSource/hdmi_misckey")
            .putExtra("packageName", "com.xgimi.misckey")
            .putExtra("unlocked", false)
        if (runCatching { pm.resolveService(xgimi, 0)?.serviceInfo?.exported == true &&
                context.startService(xgimi) != null }.getOrDefault(false)) return true

        val hisense = Intent("com.hisense.tv.start_inputs")
            .setComponent(ComponentName("com.hisense.tv.inputs", "com.hisense.tv.InputsUIService"))
        if (runCatching {
                if (pm.resolveService(hisense, 0)?.serviceInfo?.exported != true) false
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    context.startForegroundService(hisense) != null
                else context.startService(hisense) != null
            }.getOrDefault(false)) return true

        val tcl = if (pm.hasSystemFeature("com.tcl.software.systemui_custom_tv_source")) {
            Intent("com.tcl.tvstatusbar.action.SHOW_WINDOW").setPackage("com.android.systemui")
                .putExtra("TvStatusBar_Type", "Source")
                .putExtra("TvStatusBar_StartFrom", "input")
        } else Intent("com.tcl.insrc.SHOW_WINDOW").setPackage("com.tcl.settings")
        if (runCatching { pm.resolveService(tcl, 0) != null && context.startService(tcl) != null }.getOrDefault(false)) return true

        for (action in listOf("coocaa.intent.action.SOURCE_DIALOG", "coocaa.intent.aigc.home.tvsource")) {
            val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { pm.resolveActivity(intent, 0) != null && runCatching { context.startActivity(intent) }.isSuccess }.getOrDefault(false)) return true
        }
        return false
    }

    private fun openControlInput(input: ControlInput) {
        val route = input.switchIntent
        if (input.id.startsWith("vidaa:") && route != null) {
            runCatching { context.sendBroadcast(Intent(route)) }
                .onFailure { android.util.Log.w("XiaoLiControlCenter", "Could not switch VIDAA input", it) }
            return
        }
        if (route != null && runCatching {
                context.packageManager.resolveActivity(route, PackageManager.MATCH_DEFAULT_ONLY) != null &&
                    runCatching { context.startActivity(route) }.isSuccess
            }.getOrDefault(false)) return
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

    private fun systemProperty(key: String): String? = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java).invoke(null, key) as String
    }.getOrNull()

    private fun isPlaybackDevice(): Boolean {
        val types = systemProperty("ro.hdmi.device_type").orEmpty().split(',').map { it.trim() }
        if ("4" in types && "0" !in types) return true
        val characteristics = systemProperty("ro.build.characteristics").orEmpty()
        return "stb" in characteristics || "mbx" in characteristics
    }

    private fun isControlVolumeFixed(): Boolean = runCatching { audioManager.isVolumeFixed }.getOrDefault(false)

    private fun volumeLevel(): Pair<Int, Int> = runCatching {
        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) to
            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }.getOrDefault(0 to 1)

    private fun adjustControlVolume(direction: Int) {
        if (isControlVolumeFixed()) return
        val (before, max) = volumeLevel()
        runCatching {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
        }.onFailure {
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,
                (before + direction).coerceIn(0, max), 0) }
        }
        val (after, currentMax) = volumeLevel()
        val muted = runCatching { audioManager.isStreamMute(AudioManager.STREAM_MUSIC) }.getOrDefault(false)
        val stuck = before == after && !muted && (if (direction > 0) before < max else before > 0)
        controlStuckVolumeSteps = if (stuck) controlStuckVolumeSteps + 1 else 0
        controlExternalVolume = controlStuckVolumeSteps >= 2 || (controlExternalVolume && before == after)
        invalidate()
    }

    private fun toggleControlMute() {
        if (isControlVolumeFixed()) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val muted = audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    if (muted) AudioManager.ADJUST_UNMUTE else AudioManager.ADJUST_MUTE, 0)
            } else audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, 0)
        }
        invalidate()
    }

    private fun controlNetworkLabel(): String {
        val now = SystemClock.elapsedRealtime()
        if (now - networkLabelCheckedAt < 3000L) return cachedNetworkLabel
        networkLabelCheckedAt = now
        cachedNetworkLabel = runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return@runCatching "未连接网络"
            val caps = cm.getNetworkCapabilities(network) ?: return@runCatching "网络状态未知"
            when {
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "以太网已连接"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> {
                    @Suppress("DEPRECATION")
                    val ssid = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                        .connectionInfo?.ssid?.trim('"')?.takeUnless { it.isBlank() || it == "<unknown ssid>" }
                    ssid?.let { "Wi-Fi · $it" } ?: "Wi-Fi 已连接"
                }
                else -> "网络已连接"
            }
        }.getOrDefault("网络状态未知")
        return cachedNetworkLabel
    }

    private fun openControlNetworkSettings() {
        val xgimi = Intent("com.xgimi.settings.SETTINGS")
            .setComponent(ComponentName("com.android.newsettings", "com.xgimi.newsettings.SettingService"))
            .putExtra("data", "Settings://com.xgimi.settings.net/wifi_misckey")
            .putExtra("packageName", "com.xgimi.misckey")
            .putExtra("unlocked", false)
        if (runCatching {
                context.packageManager.resolveService(xgimi, 0)?.serviceInfo?.exported == true &&
                    context.startService(xgimi) != null
            }.getOrDefault(false)) return
        for (action in listOf(Settings.ACTION_WIFI_SETTINGS, Settings.ACTION_WIRELESS_SETTINGS, Settings.ACTION_SETTINGS)) {
            if (runCatching {
                    context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess) return
        }
    }

    private val hisenseTvManagerClass by lazy {
        runCatching { Class.forName("com.jamdeo.tv.TvManager") }.getOrNull()
    }
    @Volatile
    private var hisensePictureManager: Any? = null

    private fun hisensePictureManager(): Any? {
        hisensePictureManager?.let { return it }
        synchronized(this) {
            hisensePictureManager?.let { return it }
            val clazz = hisenseTvManagerClass ?: return null
            val manager = runCatching {
                val tv = clazz.getMethod("getInstance").invoke(null) ?: return null
                tv.javaClass.getMethod("getPictureManager", Context::class.java)
                    .invoke(tv, context.applicationContext)
            }.getOrNull() ?: return null
            hisensePictureManager = manager
            return manager
        }
    }

    private fun hisenseMethod(target: Any, name: String): java.lang.reflect.Method? =
        runCatching { target.javaClass.getMethod(name) }.getOrNull()

    private fun hisenseLaserLevel(): Int? {
        val now = SystemClock.elapsedRealtime()
        if (hisenseBrightnessHasBeenRead && now - hisenseBrightnessCheckedAt < 500L) return hisenseBrightnessCached
        hisenseBrightnessHasBeenRead = true
        hisenseBrightnessCheckedAt = now
        hisenseBrightnessCached = runCatching {
            val manager = hisensePictureManager() ?: return@runCatching null
            if (hisenseMethod(manager, "getLaserOverLapOrCL0Mode") == null) return@runCatching null
            (hisenseMethod(manager, "getCustomBacklight")?.invoke(manager) as? Int)?.takeIf { it in 0..10 }
        }.getOrNull()
        return hisenseBrightnessCached
    }

    private fun setHisenseLaserLevel(level: Int): Boolean = runCatching {
        val manager = hisensePictureManager() ?: return@runCatching false
        val intType = Int::class.javaPrimitiveType ?: return@runCatching false
        val target = level.coerceIn(0, 10)
        manager.javaClass.getMethod("setBacklightMode", intType).invoke(manager, 0)
        manager.javaClass.getMethod("setCustomBacklight", intType).invoke(manager, target)
        hisenseBrightnessCached = target
        hisenseBrightnessHasBeenRead = true
        hisenseBrightnessCheckedAt = SystemClock.elapsedRealtime()
        true
    }.getOrDefault(false)

    private val xgimiDisplayManagerClass by lazy {
        runCatching { Class.forName("com.xgimi.gmpf.api.DisplayManager") }.getOrNull()
    }

    private fun xgimiDisplayManager(): Any? {
        val clazz = xgimiDisplayManagerClass ?: return null
        return runCatching { clazz.getMethod("getInstance").invoke(null) }.getOrNull()
            ?: runCatching {
                clazz.getDeclaredConstructor().also { it.isAccessible = true }.newInstance()
            }.getOrNull()
    }

    private fun xgimiBrightnessLevel(): Int? {
        val now = SystemClock.elapsedRealtime()
        if (xgimiBrightnessHasBeenRead && now - xgimiBrightnessCheckedAt < 500L) return xgimiBrightnessCached
        xgimiBrightnessHasBeenRead = true
        xgimiBrightnessCheckedAt = now
        xgimiBrightnessCached = runCatching {
            val manager = xgimiDisplayManager() ?: return@runCatching null
            val getter = xgimiDisplayManagerClass?.getMethod("getDlpLumensLevel") ?: return@runCatching null
            ((getter.invoke(manager) as? Number)?.toInt()?.and(0xFF))?.coerceIn(1, 10)
        }.getOrNull()
        return xgimiBrightnessCached
    }

    private fun setXgimiBrightnessLevel(level: Int): Boolean = runCatching {
        val clazz = xgimiDisplayManagerClass ?: return@runCatching false
        val manager = xgimiDisplayManager() ?: return@runCatching false
        val target = level.coerceIn(1, 10)
        clazz.getMethod("setDlpLumensLevel", java.lang.Byte.TYPE).invoke(manager, target.toByte())
        xgimiBrightnessCached = target
        xgimiBrightnessHasBeenRead = true
        xgimiBrightnessCheckedAt = SystemClock.elapsedRealtime()
        true
    }.getOrDefault(false)

    private fun hasVendorBrightnessBackend(): Boolean {
        if (hisenseLaserLevel() != null || xgimiBrightnessLevel() != null) return false
        val packages = listOf(
            "com.xiaomi.mitv.settings", "com.tcl.settings", "com.sony.dtv.configsettings",
            "com.jamdeo.tv.vod", "com.hisense.vidaaassistant",
            "com.konka.tvsettings", "com.skyworth.tv"
        )
        val otherVendor = Build.MANUFACTURER.equals("Sony", true) || packages.any {
            runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess
        }
        val xgimiInstalled = runCatching {
            context.packageManager.getPackageInfo("com.android.newsettings", 0)
        }.isSuccess
        return otherVendor || xgimiInstalled
    }

    private fun openPictureSettings() {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    private fun setControlBrightnessLevel(level: Int) {
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, level.coerceIn(1, 255))
    }

    private fun brightnessLevel(): Int = hisenseLaserLevel()?.let { it * 255 / 10 }
        ?: xgimiBrightnessLevel()?.let { it * 255 / 10 } ?: runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128).coerceIn(1, 255)
    }.getOrDefault(128)

    private fun adjustControlBrightness(direction: Int) {
        if (isPlaybackDevice()) {
            android.widget.Toast.makeText(context, "此设备是电视盒/电视棒，没有本机屏幕背光可调节",
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val hisenseLevel = hisenseLaserLevel()
        if (hisenseLevel != null) {
            if (!setHisenseLaserLevel(hisenseLevel + direction)) {
                android.widget.Toast.makeText(context, "Hisense 背光接口当前不可用",
                    android.widget.Toast.LENGTH_SHORT).show()
            }
            invalidate()
            return
        }
        val xgimiLevel = xgimiBrightnessLevel()
        if (xgimiLevel != null) {
            if (!setXgimiBrightnessLevel(xgimiLevel + direction)) {
                android.widget.Toast.makeText(context, "XGIMI 光源亮度接口当前不可用",
                    android.widget.Toast.LENGTH_SHORT).show()
            }
            invalidate()
            return
        }
        if (hasVendorBrightnessBackend()) {
            android.widget.Toast.makeText(context, "此电视使用厂商画面服务控制背光，请在电视原生画面设置中调节",
                android.widget.Toast.LENGTH_LONG).show()
            openPictureSettings()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(context)) {
            runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:" + context.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            android.widget.Toast.makeText(context, "需要允许小李桌面修改系统亮度后才能调节",
                android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val next = (brightnessLevel() + direction * 13).coerceIn(1, 255)
        runCatching {
            setControlBrightnessLevel(next)
        }.onFailure {
            android.widget.Toast.makeText(context, "当前电视固件不允许应用直接调节背光",
                android.widget.Toast.LENGTH_LONG).show()
        }
        invalidate()
    }

    private fun isDefaultHome(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roles = context.getSystemService(RoleManager::class.java)
            if (roles?.isRoleAvailable(RoleManager.ROLE_HOME) == true) return@runCatching roles.isRoleHeld(RoleManager.ROLE_HOME)
        }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName == context.packageName
    }.getOrDefault(false)

    private fun openControlCenter() {
        controlDefaultHome = isDefaultHome()
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
                0 -> openControlNetworkSettings()
                1 -> { refreshControlInputs(); controlCenterPage = 1; controlCenterFocus = 0; controlCenterInputOffset = 0 }
                2 -> { refreshControlInputs(); controlCenterPage = 4; controlCenterFocus = 0; controlCenterInputOffset = 0 }
                3 -> { controlCenterPage = 2; controlCenterFocus = 0 }
                4 -> { controlCenterPage = 3; controlCenterFocus = 0 }
                5 -> { controlCenterOpen = false; openLauncherSettings() }
                6 -> { controlCenterOpen = false; onRequestDefaultHome?.invoke() }
                7 -> runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            1 -> {
                val input = visibleControlInputs().getOrNull(controlCenterInputOffset + controlCenterFocus)
                if (input != null) openControlInput(input)
                else if (!openControlInputPicker()) {
                    runCatching { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
            2 -> toggleControlMute()
            3 -> adjustControlBrightness(0)
            4 -> if (controlCenterFocus == 0) toggleControlCec()
                else controlInputs.getOrNull(controlCenterInputOffset + controlCenterFocus - 1)?.let(::toggleControlInputPinned)
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
            3 -> "屏幕亮度"
            else -> "信源管理"
        }
        val subtitle = when (controlCenterPage) {
            0 -> "设备连接与快捷设置"
            1 -> "由 Android TV InputManager 实时识别"
            2 -> "左右键调节 · OK 切换静音"
            3 -> "系统允许时调节屏幕亮度"
            else -> "OK 切换 HDMI-CEC/固定 · 左键隐藏 · 右键设为开机默认"
        }
        text(canvas, title, left, top, h * .037f, 0xFFF3FAFF.toInt(), true)
        text(canvas, subtitle, left, top + h * .045f, h * .018f, 0xBFE0F1FF.toInt())

        if (controlCenterPage == 0) {
            val labels = listOf("网络与 Wi-Fi", "电视信源", "信源管理", "音量控制", "屏幕亮度", "小李桌面设置", "默认主屏幕", "系统设置")
            val subtitles = listOf(controlNetworkLabel(), "检测到 ${visibleControlInputs().size} 个可显示输入",
                "隐藏、固定电视输入", "媒体音量与静音", "系统允许时调节亮度", "壁纸、桌面与应用管理",
                if (controlDefaultHome) "小李桌面当前已是默认主屏幕" else "将小李桌面设为默认主屏幕",
                "Android 设备设置")
            val icons = listOf("◉", "▣", "☷", "♪", "☼", "⚙", "⌂", "⌘")
            val startY = panel.top + panel.height() * .18f
            val gap = panel.height() * .085f
            labels.forEachIndexed { index, label ->
                val rect = RectF(panel.left + panel.width() * .065f, startY + index * gap,
                    panel.right - panel.width() * .065f, startY + index * gap + panel.height() * .07f)
                paint.color = if (index == controlCenterFocus) 0x775AB9F2 else 0x334D91C8
                canvas.drawRoundRect(rect, h * .018f, h * .018f, paint)
                if (index == controlCenterFocus) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = (h * .0028f).coerceAtLeast(2f)
                    paint.color = 0xFFD8F4FF.toInt()
                    canvas.drawRoundRect(rect, h * .018f, h * .018f, paint)
                    paint.style = Paint.Style.FILL
                    drawFocusChase(canvas, rect, h * .018f)
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
        } else if (controlCenterPage == 1 || controlCenterPage == 4) {
            val shownInputs = (if (controlCenterPage == 1) visibleControlInputs() else controlInputs)
                .drop(controlCenterInputOffset)
            if (controlCenterPage == 4) {
                val cecRect = RectF(panel.left + panel.width() * .065f,
                    panel.top + panel.height() * .205f, panel.right - panel.width() * .065f,
                    panel.top + panel.height() * .295f)
                paint.color = if (controlCenterFocus == 0) 0x775AB9F2 else 0x334D91C8
                canvas.drawRoundRect(cecRect, h * .016f, h * .016f, paint)
                if (controlCenterFocus == 0) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = (h * .0025f).coerceAtLeast(2f)
                    paint.color = 0xFFD8F4FF.toInt()
                    canvas.drawRoundRect(cecRect, h * .016f, h * .016f, paint)
                    paint.style = Paint.Style.FILL
                    drawFocusChase(canvas, cecRect, h * .016f)
                }
                text(canvas, "HDMI-CEC 自动切源", cecRect.left + cecRect.width() * .07f,
                    cecRect.top + cecRect.height() * .44f, h * .019f, 0xFFF4FAFF.toInt(), true)
                text(canvas, if (controlCecEnabled) "已开启" else "已关闭",
                    cecRect.right - cecRect.width() * .22f, cecRect.top + cecRect.height() * .65f,
                    h * .014f, 0xBFD5E9FA.toInt())
            }
            if (shownInputs.isEmpty()) {
                text(canvas, "系统没有向应用公开可用的电视信源列表。", left,
                    panel.top + panel.height() * .31f, h * .018f, 0xFFEAF7FF.toInt())
                text(canvas, "请通过电视系统设置管理输入源。", left,
                    panel.top + panel.height() * .36f, h * .015f, 0xBFE0F1FF.toInt())
            } else {
                shownInputs.take(if (controlCenterPage == 4) 5 else 6).forEachIndexed { index, input ->
                    val rowTop = panel.top + panel.height() *
                        (if (controlCenterPage == 4) .315f + index * .102f else .225f + index * .10f)
                    val rowHeight = panel.height() * if (controlCenterPage == 4) .092f else .08f
                    val rect = RectF(panel.left + panel.width() * .065f, rowTop,
                        panel.right - panel.width() * .065f, rowTop + rowHeight)
                    val rowFocus = if (controlCenterPage == 4) index + 1 else index
                    paint.color = if (rowFocus == controlCenterFocus) 0x775AB9F2 else 0x334D91C8
                    canvas.drawRoundRect(rect, h * .016f, h * .016f, paint)
                    if (rowFocus == controlCenterFocus) {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = (h * .0025f).coerceAtLeast(2f)
                        paint.color = 0xFFD8F4FF.toInt()
                        canvas.drawRoundRect(rect, h * .016f, h * .016f, paint)
                        paint.style = Paint.Style.FILL
                        drawFocusChase(canvas, rect, h * .016f)
                    }
                    text(canvas, input.label, rect.left + rect.width() * .07f,
                        rect.top + rect.height() * .44f, h * .020f, 0xFFF4FAFF.toInt(), true)
                    val detail = if (controlCenterPage == 1) {
                        input.kind + " · " + (if (input.connected) "已连接" else "未连接") +
                            (input.deviceName?.let { " · $it" } ?: "")
                    } else {
                        val pinned = input.id in pinnedControlInputIds()
                        val hidden = input.id in hiddenControlInputIds()
                        (if (pinned) "已固定" else "未固定") + " · " + (if (hidden) "已隐藏" else "显示中") +
                            (if (input.id == controlBootInputId) " · 开机默认" else "")
                    }
                    text(canvas, detail, rect.left + rect.width() * .07f,
                        rect.top + rect.height() * .76f, h * .013f, 0xBFD5E9FA.toInt())
                    text(canvas, "›", rect.right - rect.width() * .08f,
                        rect.centerY() + h * .010f, h * .032f, 0xFFEAF8FF.toInt(), true)
                }
            }
        } else {
            val isVolume = controlCenterPage == 2
            val unavailable = if (isVolume) isControlVolumeFixed()
                else isPlaybackDevice() || hasVendorBrightnessBackend()
            if (unavailable) {
                val message = if (isVolume) "本设备音量固定，请使用电视或外接音响遥控器"
                    else if (isPlaybackDevice()) "此设备是电视盒/电视棒，没有本机屏幕背光可调节"
                    else "此电视使用厂商画面服务，请打开原生画面设置"
                paint.textAlign = Paint.Align.CENTER
                text(canvas, message, panel.centerX(), panel.top + panel.height() * .45f,
                    h * .019f, 0xFFF4FAFF.toInt(), true)
                paint.textAlign = Paint.Align.LEFT
            } else {
                val hisenseLevel = if (isVolume) null else hisenseLaserLevel()
                val xgimiLevel = if (isVolume) null else xgimiBrightnessLevel()
                val nativeBrightnessLevel = hisenseLevel ?: xgimiLevel
                val current = if (isVolume) {
                    val (level, max) = volumeLevel()
                    level.toFloat() / max
                } else if (nativeBrightnessLevel != null) nativeBrightnessLevel / 10f else brightnessLevel() / 255f
                val valueLabel = if (isVolume) {
                    val (level, max) = volumeLevel()
                    if (controlExternalVolume) "外接音响" else if (audioManager.isStreamMute(AudioManager.STREAM_MUSIC))
                        "已静音" else "$level / $max"
                } else if (nativeBrightnessLevel != null) "$nativeBrightnessLevel / 10" else "${brightnessLevel()} / 255"
                val slider = RectF(panel.left + panel.width() * .10f, panel.top + panel.height() * .43f,
                    panel.right - panel.width() * .10f, panel.top + panel.height() * .49f)
                paint.color = 0x665D8FB8
                canvas.drawRoundRect(slider, slider.height() / 2f, slider.height() / 2f, paint)
                paint.color = 0xFF75D8FF.toInt()
                val filled = RectF(slider.left, slider.top,
                    slider.left + slider.width() * current.coerceIn(0f, 1f), slider.bottom)
                canvas.drawRoundRect(filled, slider.height() / 2f, slider.height() / 2f, paint)
                drawFocusChase(canvas, slider, slider.height() / 2f)
                paint.textAlign = Paint.Align.CENTER
                text(canvas, valueLabel, panel.centerX(), panel.top + panel.height() * .37f,
                    h * .027f, 0xFFF4FAFF.toInt(), true)
                paint.textAlign = Paint.Align.LEFT
                val hint = if (isVolume) "左右键调节 · OK 切换静音"
                    else if (hisenseLevel != null) "左右键调节 VIDAA 激光背光"
                    else if (xgimiLevel != null) "左右键调节 XGIMI DLP 光源亮度"
                    else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(context))
                        "尚未授权修改系统亮度 · OK 打开授权页面"
                    else "左右键调节屏幕亮度"
                text(canvas, hint, left, panel.top + panel.height() * .58f, h * .016f, 0xFFD5EAFB.toInt())
            }
        }
        text(canvas, "返回键：返回上一级", left, panel.bottom - panel.height() * .03f,
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
        // A restrained glass sheen unifies third-party icons without replacing their artwork.
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(target.left, target.top, target.left, target.bottom,
            intArrayOf(0x26FFFFFF, 0x08FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
        paint.color = -1
        c.drawRoundRect(target, radius, radius, paint)
        paint.shader = null
        c.restoreToCount(save)
        if (focused) drawFocusChase(c, target, radius)
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
            val headerFocusRect = scaledFocusRect(RectF(w * .835f, h * .175f, w * .94f, h * .245f))
            c.drawRoundRect(headerFocusRect, h * .012f, h * .012f, paint)
            paint.style = Paint.Style.FILL
            drawFocusChase(c, headerFocusRect, h * .012f)
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
        val firstVisibleRow = (kotlin.math.floor(drawerScrollVisualRow).toInt() - 1).coerceAtLeast(0)
        val lastVisibleRow = (kotlin.math.ceil(drawerScrollVisualRow + visibleRows).toInt() + 1)
            .coerceAtMost(totalRows - 1)
        val pinnedPackages = favorites.asSequence().map { it.packageName }.toHashSet()
        val firstVisibleIndex = (firstVisibleRow * cols).coerceAtMost(apps.size)
        val lastVisibleIndex = ((lastVisibleRow + 1) * cols).coerceAtMost(apps.size)
        for (i in firstVisibleIndex until lastVisibleIndex) {
            val app = apps[i]
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
        val count = if (actionMenuSource == "home") 6 else 4
        val targets = (0 until count).map { index ->
            val y = if (actionMenuSource == "home") .20f + index * .125f
                else .25f + index * .17f
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
        if (apps.isEmpty() || metrics.columns <= 0) {
            if (direction == SpatialFocusNavigator.UP) drawerHeaderFocused = true
            invalidate()
            return
        }

        if (drawerHeaderFocused) {
            // The header is a single target. Down restores the app that entered it,
            // rather than jumping to an unrelated tile at the far-right edge.
            if (direction == SpatialFocusNavigator.DOWN) {
                drawerIndex = drawerHeaderReturnIndex.coerceIn(0, apps.lastIndex)
                drawerHeaderFocused = false
                animateSpatialFocus()
            }
        } else {
            val currentRow = drawerIndex / metrics.columns
            val next = when (direction) {
                SpatialFocusNavigator.LEFT -> metrics.moveFocus(drawerIndex, -1, apps.size)
                SpatialFocusNavigator.RIGHT -> metrics.moveFocus(drawerIndex, 1, apps.size)
                SpatialFocusNavigator.UP -> {
                    if (currentRow == 0) {
                        drawerHeaderReturnIndex = drawerIndex
                        drawerHeaderFocused = true
                        -1
                    } else metrics.moveFocus(drawerIndex, -metrics.columns, apps.size)
                }
                SpatialFocusNavigator.DOWN -> metrics.moveFocus(drawerIndex, metrics.columns, apps.size)
                else -> drawerIndex
            }
            if (next >= 0 && next != drawerIndex) {
                drawerIndex = next
                animateSpatialFocus()
            } else if (next == -1 && drawerHeaderFocused) {
                animateSpatialFocus()
            }
        }

        // Focus is authoritative: keep its row inside the visible window in either direction.
        // This mirrors Aerial's focus-driven scroll, including when the grid is already scrolled.
        val focusedRow = drawerIndex / metrics.columns
        val maxScroll = metrics.maxScroll(apps.size)
        drawerScrollRow = when {
            focusedRow < drawerScrollRow -> focusedRow
            focusedRow >= drawerScrollRow + metrics.rows -> focusedRow - metrics.rows + 1
            else -> drawerScrollRow
        }.coerceIn(0, maxScroll)
        animateDrawerScrollTo(drawerScrollRow)
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
        val railRadius = h * .025f
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(rail.left, rail.top, rail.right, rail.bottom,
            intArrayOf(0xF0142B49.toInt(), 0xF00A1A31.toInt(), 0xF00A1528.toInt()),
            null, Shader.TileMode.CLAMP)
        c.drawRoundRect(rail, railRadius, railRadius, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (h * .0011f).coerceAtLeast(1f)
        paint.color = 0x334D91C7
        c.drawRoundRect(rail, railRadius, railRadius, paint)
        paint.style = Paint.Style.FILL

        val mark = RectF(rail.left + w * .025f, rail.top + h * .045f,
            rail.left + w * .075f, rail.top + h * .095f)
        paint.shader = LinearGradient(mark.left, mark.top, mark.right, mark.bottom,
            intArrayOf(0xFF54D8FF.toInt(), 0xFF2479D8.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(mark, h * .009f, h * .009f, paint)
        paint.shader = null
        text(c, "X", mark.centerX() - h * .008f, mark.centerY() + h * .010f,
            h * .026f, -1, true)
        text(c, "小李桌面", rail.left + w * .088f, rail.top + h * .071f,
            h * .025f, -1, true)
        text(c, "SYSTEM CONTROL", rail.left + w * .025f, rail.top + h * .135f,
            h * .010f, 0xFF82A8CB.toInt(), true)
        paint.color = 0x334D7195
        c.drawRect(rail.left + w * .025f, rail.top + h * .165f,
            rail.right - w * .025f, rail.top + h * .1665f, paint)

        val labels = listOf("桌面布局", "外观与壁纸", "应用管理", "桌面组件",
            "遥控器与操作", "小李全联", "电视系统设置")
        labels.forEachIndexed { index, label ->
            val rect = SettingsLayout.categoryBounds(index, w, h, rail)
            val selected = settingsFocus == 30 + index || settingsCategoryForControl(settingsFocus) == index
            if (selected) {
                val selectedRect = if (settingsFocus == 30 + index) scaledFocusRect(rect) else rect
                paint.shader = LinearGradient(selectedRect.left, selectedRect.top,
                    selectedRect.right, selectedRect.bottom,
                    intArrayOf(0xE82B78B5.toInt(), 0xD915426D.toInt()),
                    null, Shader.TileMode.CLAMP)
                c.drawRoundRect(selectedRect, h * .012f, h * .012f, paint)
                paint.shader = null
                if (settingsFocus == 30 + index) drawFocusChase(c, selectedRect, h * .012f)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = (h * .0012f).coerceAtLeast(1f)
                paint.color = 0x6678DFFF
                c.drawRoundRect(selectedRect, h * .012f, h * .012f, paint)
                paint.style = Paint.Style.FILL
                paint.color = 0xFF67DFFF.toInt()
                c.drawRoundRect(RectF(selectedRect.left + w * .006f,
                    selectedRect.top + h * .014f, selectedRect.left + w * .009f,
                    selectedRect.bottom - h * .014f), h * .002f, h * .002f, paint)
            }
            paint.color = if (selected) 0xFF8BE7FF.toInt() else 0xFF58799A.toInt()
            c.drawCircle(rect.left + w * .025f, rect.centerY(), h * .0042f, paint)
            text(c, label, rect.left + w * .045f, rect.centerY() + h * .007f,
                h * .017f, if (selected) -1 else 0xFFC0D0E2.toInt(), selected)
            if (selected) {
                text(c, "›", rect.right - w * .020f, rect.centerY() + h * .008f,
                    h * .022f, 0xFF9BEAFF.toInt(), true)
            }
        }

        paint.color = 0x334D7195
        c.drawRect(rail.left + w * .025f, rail.bottom - h * .075f,
            rail.right - w * .025f, rail.bottom - h * .0735f, paint)
        text(c, "方向键选择  ·  OK 确认", rail.left + w * .025f,
            rail.bottom - h * .043f, h * .0105f, 0xFF829BB7.toInt())
        text(c, "XIAOLI  /  TV", rail.left + w * .025f,
            rail.bottom - h * .020f, h * .009f, 0xFF58799A.toInt(), true)
    }

    private fun drawSettingCard(c: Canvas, bounds: RectF, heading: String, detail: String, focused: Boolean) {
        val rect = if (focused) scaledFocusRect(bounds) else RectF(bounds)
        val radius = rect.height() * .16f
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(rect.left, rect.top, rect.left, rect.bottom,
            if (focused) intArrayOf(0xF04491C9.toInt(), 0xEE1A5685.toInt(), 0xE8112E50.toInt())
            else intArrayOf(0xC92B4662.toInt(), 0xB817304C.toInt(), 0xB20B2038.toInt()),
            null, Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, radius, radius, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (focused) (height * .0024f).coerceAtLeast(2f)
            else (height * .0009f).coerceAtLeast(1f)
        paint.color = if (focused) 0xFF78DFFF.toInt() else 0x33FFFFFF
        c.drawRoundRect(rect, radius, radius, paint)
        paint.style = Paint.Style.FILL
        if (focused) drawFocusChase(c, rect, radius)

        val sheen = RectF(rect.left + rect.width() * .025f, rect.top + rect.height() * .045f,
            rect.right - rect.width() * .025f, rect.top + rect.height() * .060f)
        paint.color = if (focused) 0x667DE8FF else 0x18FFFFFF
        c.drawRoundRect(sheen, sheen.height(), sheen.height(), paint)

        val accent = RectF(rect.left + rect.width() * .026f, rect.top + rect.height() * .22f,
            rect.left + rect.width() * .033f, rect.bottom - rect.height() * .22f)
        paint.color = if (focused) 0xFF6DE0FF.toInt() else 0x665E9ACB
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
            val cx = rect.right - rect.width() * .055f
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = (height * .0012f).coerceAtLeast(1f)
            paint.color = if (focused) 0xAA8BE7FF.toInt() else 0x556F94B7
            c.drawCircle(cx, rect.centerY(), rect.height() * .105f, paint)
            paint.style = Paint.Style.FILL
            text(c, "›", cx - rect.height() * .035f,
                rect.centerY() + rect.height() * .10f, rect.height() * .25f,
                if (focused) 0xFFB8F3FF.toInt() else 0xFF7898B8.toInt(), true)
        }
    }

    private fun drawIconScaleSlider(c: Canvas, bounds: RectF, focused: Boolean) {
        val rect = if (focused) scaledFocusRect(bounds) else RectF(bounds)
        paint.style = Paint.Style.FILL
        paint.color = if (focused) 0xE52B6795.toInt() else 0xC21A3554.toInt()
        c.drawRoundRect(rect, rect.height() * .16f, rect.height() * .16f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (focused) 2.5f else 1f
        paint.color = if (focused) 0xFF78DFFF.toInt() else 0x44FFFFFF
        c.drawRoundRect(rect, rect.height() * .16f, rect.height() * .16f, paint)
        paint.style = Paint.Style.FILL
        if (focused) drawFocusChase(c, rect, rect.height() * .16f)
        val cy = rect.centerY()
        text(c, "A−", rect.left + rect.width() * .09f, cy + rect.height() * .10f,
            rect.height() * .28f, 0xFFBFEFFF.toInt(), true)
        text(c, "A+", rect.right - rect.width() * .09f, cy + rect.height() * .10f,
            rect.height() * .28f, 0xFFBFEFFF.toInt(), true)
        val left = rect.left + rect.width() * .22f
        val right = rect.right - rect.width() * .22f
        val track = RectF(left, cy - rect.height() * .025f, right, cy + rect.height() * .025f)
        paint.color = 0x665B7892
        c.drawRoundRect(track, track.height(), track.height(), paint)
        val fraction = ((homeIconScale - .65f) / .95f).coerceIn(0f, 1f)
        paint.color = 0xFF31B9F2.toInt()
        c.drawRoundRect(RectF(track.left, track.top, track.left + track.width() * fraction, track.bottom),
            track.height(), track.height(), paint)
        paint.color = -1
        c.drawCircle(track.left + track.width() * fraction, cy, rect.height() * .105f, paint)
        text(c, "${(homeIconScale * 100).toInt()}%", rect.centerX(),
            rect.bottom - rect.height() * .08f, rect.height() * .13f, 0xFF8CEBFF.toInt(), true)
    }

    private fun drawGridChoice(c: Canvas, bounds: RectF, count: Int, selected: Boolean, focused: Boolean, label: String) {
        val rect = if (focused) scaledFocusRect(bounds) else RectF(bounds)
        paint.style = Paint.Style.FILL
        paint.color = if (selected) 0xD92A78B5.toInt() else 0xB21A3554.toInt()
        c.drawRoundRect(rect, rect.height() * .15f, rect.height() * .15f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (focused) 2.5f else 1f
        paint.color = if (focused || selected) 0xFF75DEFF.toInt() else 0x55FFFFFF
        c.drawRoundRect(rect, rect.height() * .15f, rect.height() * .15f, paint)
        paint.style = Paint.Style.FILL
        if (focused) drawFocusChase(c, rect, rect.height() * .15f)
        val cols = if (label.contains("列")) count else minOf(count, 4)
        val rows = if (label.contains("行")) count else 2
        val gap = rect.width() * .035f
        val cellW = (rect.width() * .56f - gap * (cols - 1)) / cols
        val cellH = (rect.height() * .36f - gap * (rows - 1)) / rows
        val totalW = cols * cellW + (cols - 1) * gap
        val startX = rect.centerX() - totalW / 2f
        val startY = rect.top + rect.height() * .12f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (height * .0012f).coerceAtLeast(1f)
        paint.color = if (selected) 0xFF8CEBFF.toInt() else 0xFF91AFC8.toInt()
        for (row in 0 until rows) for (col in 0 until cols) {
            val cell = RectF(startX + col * (cellW + gap), startY + row * (cellH + gap),
                startX + col * (cellW + gap) + cellW, startY + row * (cellH + gap) + cellH)
            if (selected) { paint.style = Paint.Style.FILL; c.drawRoundRect(cell, 3f, 3f, paint); paint.style = Paint.Style.STROKE }
            c.drawRoundRect(cell, 3f, 3f, paint)
        }
        paint.style = Paint.Style.FILL
        text(c, label, rect.centerX(), rect.bottom - rect.height() * .12f,
            rect.height() * .19f, if (selected) 0xFF8CEBFF.toInt() else 0xFFE2EDF7.toInt(), true)
    }

    private fun drawSwitchCard(c: Canvas, bounds: RectF, label: String, enabled: Boolean, focused: Boolean) {
        val rect = if (focused) scaledFocusRect(bounds) else RectF(bounds)
        paint.style = Paint.Style.FILL
        paint.color = if (focused) 0xE52B6795.toInt() else 0xC21A3554.toInt()
        c.drawRoundRect(rect, rect.height() * .18f, rect.height() * .18f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (focused) 2.5f else 1f
        paint.color = if (focused) 0xFF78DFFF.toInt() else 0x44FFFFFF
        c.drawRoundRect(rect, rect.height() * .18f, rect.height() * .18f, paint)
        paint.style = Paint.Style.FILL
        if (focused) drawFocusChase(c, rect, rect.height() * .18f)
        text(c, label, rect.left + rect.width() * .08f, rect.centerY() + rect.height() * .07f,
            rect.height() * .24f, -1, true)
        val sw = RectF(rect.right - rect.width() * .29f, rect.centerY() - rect.height() * .19f,
            rect.right - rect.width() * .07f, rect.centerY() + rect.height() * .19f)
        paint.color = if (enabled) 0xFF168AC7.toInt() else 0xFF53677B.toInt()
        c.drawRoundRect(sw, sw.height(), sw.height(), paint)
        paint.color = -1
        c.drawCircle(if (enabled) sw.right - sw.height() * .5f else sw.left + sw.height() * .5f,
            sw.centerY(), sw.height() * .39f, paint)
    }

    private fun drawSymbolTile(c: Canvas, bounds: RectF, title: String, detail: String, symbol: String) {
        val rect = RectF(bounds)
        paint.style = Paint.Style.FILL
        paint.color = 0xC21A3554.toInt()
        c.drawRoundRect(rect, rect.height() * .14f, rect.height() * .14f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = 0x44FFFFFF
        c.drawRoundRect(rect, rect.height() * .14f, rect.height() * .14f, paint)
        paint.style = Paint.Style.FILL
        text(c, symbol, rect.centerX(), rect.top + rect.height() * .48f,
            rect.height() * .38f, 0xFF8CEBFF.toInt(), true)
        text(c, title, rect.centerX(), rect.top + rect.height() * .76f,
            rect.height() * .16f, -1, true)
        if (detail.isNotBlank()) text(c, detail, rect.centerX(), rect.top + rect.height() * .90f,
            rect.height() * .105f, 0xFFB5C7D8.toInt())
    }

    /**
     * Informational TV tile: icon, title and supporting text share a left edge.
     * The compact centered-poster treatment was too small at sofa distance.
     */
    private fun drawCompactFeatureTile(c: Canvas, bounds: RectF, title: String, detail: String, symbol: String) {
        val rect = RectF(bounds)
        val radius = rect.height() * .14f
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(rect.left, rect.top, rect.left, rect.bottom,
            intArrayOf(0xD3294F72.toInt(), 0xC615304D.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, radius, radius, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (height * .0011f).coerceAtLeast(1.2f)
        paint.color = 0x665F9BC5
        c.drawRoundRect(rect, radius, radius, paint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.LEFT

        val compact = rect.height() < height * .17f
        val iconSize = minOf(rect.height() * if (compact) .25f else .22f, rect.width() * .18f)
        val iconX = rect.left + rect.width() * .09f
        val textX = rect.left + rect.width() * .27f
        val titleSize = minOf(rect.height() * if (compact) .19f else .16f,
            rect.width() * .085f).coerceAtLeast(height * .018f)
        val detailSize = minOf(rect.height() * if (compact) .13f else .115f,
            rect.width() * .065f).coerceAtLeast(height * .012f)
        text(c, symbol, iconX, rect.centerY() + iconSize * .34f,
            iconSize, 0xFF8CEBFF.toInt(), true)
        text(c, title, textX, rect.centerY() - rect.height() * .015f,
            titleSize, 0xFFF2F7FF.toInt(), true)
        text(c, detail, textX, rect.centerY() + rect.height() * .24f,
            detailSize, 0xFFBBD0E3.toInt())
    }

    private fun drawSettingsSectionLabel(c: Canvas, label: String, x: Float, y: Float, w: Float) {
        text(c, label, x, y, height * .013f, 0xFF83B6D9.toInt(), true)
        paint.style = Paint.Style.FILL
        paint.color = 0x335B8AB4
        c.drawRect(x, y + height * .012f, x + w, y + height * .0132f, paint)
    }

    private fun drawSettingsMetaTile(c: Canvas, bounds: RectF, title: String, detail: String) {
        val radius = bounds.height() * .16f
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(bounds.left, bounds.top, bounds.left, bounds.bottom,
            intArrayOf(0xC91B3A5A.toInt(), 0xB80E2843.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRoundRect(bounds, radius, radius, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (height * .0008f).coerceAtLeast(1f)
        paint.color = 0x334E8AB8
        c.drawRoundRect(bounds, radius, radius, paint)
        paint.style = Paint.Style.FILL
        text(c, title, bounds.left + bounds.width() * .08f,
            bounds.top + bounds.height() * .40f, bounds.height() * .22f,
            0xFFEAF5FF.toInt(), true)
        text(c, detail, bounds.left + bounds.width() * .08f,
            bounds.top + bounds.height() * .73f, bounds.height() * .14f,
            0xFF9FBBD3.toInt())
    }

    private fun drawLauncherSettings(c: Canvas, w: Float, h: Float) {
        val category = if (settingsFocus in 30..36) settingsFocus - 30
            else settingsCategoryForControl(settingsFocus) ?: 0
        val contentSave = c.save()
        c.translate(w * .18f, 0f)
        c.scale(.80f, 1f)
        val panel = RectF(w * .08f, h * .14f, w * .92f, h * .97f)
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(panel.left, panel.top, panel.right, panel.bottom,
            intArrayOf(0xF0142B48.toInt(), 0xF00B1D34.toInt(), 0xF009172A.toInt()),
            null, Shader.TileMode.CLAMP)
        c.drawRoundRect(panel, h * .025f, h * .025f, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (h * .0012f).coerceAtLeast(1f)
        paint.color = 0x445E9BCB
        c.drawRoundRect(panel, h * .025f, h * .025f, paint)
        paint.style = Paint.Style.FILL
        paint.color = 0x5548B8EA
        c.drawRect(panel.left + w * .035f, panel.top + h * .018f,
            panel.right - w * .035f, panel.top + h * .020f, paint)

        val headings = listOf("桌面布局", "外观与壁纸", "应用管理", "桌面组件",
            "遥控器与操作", "小李全联", "电视系统设置")
        val descriptions = listOf(
            "控制首页图标比例与应用抽屉网格密度",
            "管理静态壁纸、动态背景与壁纸来源",
            "整理首页常用应用，控制桌面入口",
            "调整时间、日期显示与组件布局",
            "设置默认主屏幕，查看遥控器操作方式",
            "进入独立的局域网文件传输应用",
            "打开 Android TV 系统级配置"
        )
        val categoryEyebrows = listOf("LAYOUT & GRID", "LOOK & FEEL", "APP SHORTCUTS",
            "HOME WIDGETS", "REMOTE CONTROL", "FILE TRANSFER", "ANDROID TV")
        text(c, categoryEyebrows[category], w * .13f, h * .190f,
            h * .012f, 0xFF79B7DE.toInt(), true)
        text(c, "%02d  /  07".format(category + 1), w * .82f, h * .190f,
            h * .012f, 0xFF78A7CA.toInt(), true)
        text(c, headings[category], w * .13f, h * .235f, h * .041f, -1, true)
        text(c, descriptions[category], w * .13f, h * .267f, h * .018f, 0xFFB4CBE0.toInt())

        when (category) {
            0 -> {
                drawSettingsSectionLabel(c, "桌面图标", w * .13f, h * .310f, w * .74f)
                drawIconScaleSlider(c, RectF(w * .13f, h * .32f, w * .87f, h * .45f),
                    settingsFocus == 8)
                drawSettingsSectionLabel(c, "应用抽屉列数", w * .13f, h * .475f, w * .74f)
                for (i in 0..2) {
                    val rect = SettingsLayout.drawerGridOptionBounds(i, 0, w, h)
                    drawGridChoice(c, rect, i + 4, drawerColumns() == i + 4,
                        settingsFocus == i, "${i + 4} 列")
                }
                drawSettingsSectionLabel(c, "应用抽屉行数", w * .13f, h * .68f, w * .74f)
                for (i in 0..2) {
                    val rect = SettingsLayout.drawerGridOptionBounds(i, 1, w, h)
                    drawGridChoice(c, rect, i + 2, drawerRows() == i + 2,
                        settingsFocus == i + 17, "${i + 2} 行")
                }
                drawSettingCard(c, RectF(w * .13f, h * .88f, w * .60f, h * .945f),
                    "恢复桌面图标位置", "只恢复五个常用应用的位置，不改变应用顺序",
                    settingsFocus == 23)
            }
            1 -> {
                drawSettingsSectionLabel(c, "壁纸与背景效果", w * .13f, h * .335f, w * .74f)
                drawWallpaperPreview(c, RectF(w * .13f, h * .37f, w * .485f, h * .59f))
                drawSettingCard(c, RectF(w * .515f, h * .37f, w * .87f, h * .59f),
                    "选择壁纸", "从设备图片中选择", settingsFocus == 3)
                drawSettingCard(c, RectF(w * .13f, h * .62f, w * .485f, h * .80f),
                    if (dynamicWallpaperEnabled) "动态背景 · 开启" else "动态背景 · 关闭",
                    "切换动态光效", settingsFocus == 4)
                drawSettingCard(c, RectF(w * .515f, h * .62f, w * .87f, h * .80f),
                    "恢复默认壁纸", "移除自定义图片", settingsFocus == 5)
                drawSettingCard(c, RectF(w * .13f, h * .835f, w * .485f, h * .945f),
                    "从手机接收壁纸", "通过小李全联接收图片", settingsFocus == 24)
            }
            2 -> {
                drawSettingsSectionLabel(c, "首页常用应用", w * .13f, h * .335f, w * .74f)
                val slots = favorites.take(5)
                val gapX = w * .03f
                val gapY = h * .012f
                val tileW = (w * .74f - gapX) / 2f
                val tileH = h * .125f
                for (i in 0..4) {
                    val col = i % 2
                    val row = i / 2
                    val left = w * .13f + col * (tileW + gapX)
                    val top = h * .365f + row * (tileH + gapY)
                    val rect = RectF(left, top, left + tileW, top + tileH)
                    paint.style = Paint.Style.FILL
                    paint.shader = LinearGradient(rect.left, rect.top, rect.left, rect.bottom,
                        intArrayOf(0xD3224565.toInt(), 0xC6112B46.toInt()), null, Shader.TileMode.CLAMP)
                    c.drawRoundRect(rect, h * .014f, h * .014f, paint)
                    paint.shader = null
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1f
                    paint.color = 0x554F86B1
                    c.drawRoundRect(rect, h * .014f, h * .014f, paint)
                    paint.style = Paint.Style.FILL
                    val entry = slots.getOrNull(i)
                    val app = entry?.let { item -> apps.firstOrNull { it.packageName == item.packageName } }
                    if (app != null) runCatching {
                        val drawable = context.packageManager.getApplicationIcon(app.packageName)
                        val iconSize = h * .064f
                        drawable.setBounds((rect.left + rect.width() * .12f).toInt(),
                            (rect.centerY() - iconSize / 2f).toInt(),
                            (rect.left + rect.width() * .12f + iconSize).toInt(),
                            (rect.centerY() + iconSize / 2f).toInt())
                        drawable.draw(c)
                    }
                    text(c, app?.label ?: "添加应用", rect.left + rect.width() * .27f,
                        rect.centerY() + h * .006f, h * .015f, 0xFFE8F2FC.toInt(), true)
                }
                drawSettingsMetaTile(c, RectF(w * .13f, h * .785f, w * .485f, h * .86f),
                    "最多五个", "首页快捷入口")
                drawSettingsMetaTile(c, RectF(w * .515f, h * .785f, w * .87f, h * .86f),
                    "应用抽屉", "选择已安装应用")
                drawSettingCard(c, RectF(w * .13f, h * .875f, w * .60f, h * .945f),
                    "管理桌面应用", "编辑首页入口", settingsFocus == 21)
            }
            3 -> {
                drawSettingsSectionLabel(c, "时间与日期显示", w * .13f, h * .315f, w * .74f)
                val labels = listOf(if (showClock) "时间" else "时间",
                    if (showDate) "日期" else "日期",
                    if (use24HourClock) "24 小时制" else "12 小时制",
                    if (showClockSeconds) "显示秒数" else "隐藏秒数")
                val states = listOf(showClock, showDate, use24HourClock, showClockSeconds)
                val ids = listOf(9, 10, 15, 16)
                val switchRects = listOf(
                    RectF(w * .13f, h * .35f, w * .485f, h * .475f),
                    RectF(w * .515f, h * .35f, w * .87f, h * .475f),
                    RectF(w * .13f, h * .495f, w * .485f, h * .62f),
                    RectF(w * .515f, h * .495f, w * .87f, h * .62f)
                )
                for (i in 0..3) drawSwitchCard(c, switchRects[i],
                    labels[i], states[i], settingsFocus == ids[i])
                drawSettingsSectionLabel(c, "组件位置与尺寸", w * .13f, h * .675f, w * .74f)
                drawSettingCard(c, RectF(w * .13f, h * .705f, w * .87f, h * .825f),
                    "编辑桌面组件", "拖动调整时间日期整体位置 · 方向键微调 · 音量键调整大小",
                    settingsFocus == 22)
                drawSettingsMetaTile(c, RectF(w * .13f, h * .84f, w * .60f, h * .925f),
                    "时间 / 日期 · 位置与尺寸", "可独立控制显示，并调整桌面组件位置和大小")
            }
            4 -> {
                drawSettingsSectionLabel(c, "默认主屏幕", w * .13f, h * .335f, w * .74f)
                drawSettingCard(c, RectF(w * .13f, h * .36f, w * .87f, h * .59f),
                    if (isDefaultHomeLauncher()) "小李桌面 · 当前默认主屏幕" else "设为默认主屏幕",
                    "按遥控器 Home 键时进入小李桌面；默认应用由 Android 系统管理。",
                    settingsFocus == 14)
                drawSettingsSectionLabel(c, "遥控器快捷操作", w * .13f, h * .655f, w * .74f)
                val remoteTileW = (w * .74f - w * .025f) / 2f
                drawCompactFeatureTile(c, RectF(w * .13f, h * .68f,
                    w * .13f + remoteTileW, h * .805f), "打开应用抽屉", "按遥控器下键", "↓")
                drawCompactFeatureTile(c, RectF(w * .13f + remoteTileW + w * .025f, h * .68f,
                    w * .87f, h * .805f), "应用操作菜单", "长按 OK 键", "OK")
                drawCompactFeatureTile(c, RectF(w * .13f, h * .825f,
                    w * .13f + remoteTileW, h * .95f), "打开控制中心", "按遥控器菜单键", "☰")
                drawCompactFeatureTile(c, RectF(w * .13f + remoteTileW + w * .025f, h * .825f,
                    w * .87f, h * .95f), "退出设置", "按返回键关闭当前页面", "↩")
            }
            5 -> {
                drawSettingsSectionLabel(c, "小李全联 · 应用下载与功能", w * .13f, h * .335f, w * .74f)
                val qrPanel = RectF(w * .13f, h * .36f, w * .37f, h * .84f)
                paint.color = 0xD91A3554.toInt()
                c.drawRoundRect(qrPanel, h * .018f, h * .018f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1f
                paint.color = 0x55FFFFFF
                c.drawRoundRect(qrPanel, h * .018f, h * .018f, paint)
                paint.style = Paint.Style.FILL
                paint.textAlign = Paint.Align.CENTER
                text(c, "小李全联 · 手机安装", qrPanel.centerX(), qrPanel.top + h * .055f,
                    h * .024f, 0xFFF7FAFF.toInt(), true)
                text(c, "使用微信长按识别二维码，进入百度网盘获取安装包",
                    qrPanel.centerX(), qrPanel.top + h * .092f,
                    h * .0115f, 0xFFB9C7D8.toInt())
                val qrSize = minOf(h * .285f, w * .19f)
                val qrRect = RectF(qrPanel.centerX() - qrSize / 2f, qrPanel.top + h * .125f,
                    qrPanel.centerX() + qrSize / 2f, qrPanel.top + h * .125f + qrSize)
                paint.color = android.graphics.Color.WHITE
                c.drawRoundRect(qrRect, h * .012f, h * .012f, paint)
                quanLianDownloadQrBitmap?.let { bitmap ->
                    // Keep the source QR perfectly square and preserve its built-in quiet zone.
                    paint.isFilterBitmap = false
                    c.drawBitmap(bitmap, null, qrRect, paint)
                    paint.isFilterBitmap = true
                }
                text(c, "链接下载密码：1234", qrPanel.centerX(), qrRect.bottom + h * .052f,
                    h * .018f, 0xFFF7FAFF.toInt(), true)
                text(c, "百度网盘分享 · 无需跳转 GitHub", qrPanel.centerX(),
                    qrRect.bottom + h * .086f, h * .0125f, 0xFF9AA9BC.toInt())
                paint.textAlign = Paint.Align.LEFT
                val left = w * .40f
                val gap = w * .014f
                val tileW = (w * .47f - gap) / 2f
                drawCompactFeatureTile(c, RectF(left, h * .405f, left + tileW, h * .535f),
                    "文件发送", "进入应用后使用", "↑")
                drawCompactFeatureTile(c, RectF(left + tileW + gap, h * .405f,
                    w * .87f, h * .535f), "文件接收", "进入应用后使用", "↓")
                drawCompactFeatureTile(c, RectF(left, h * .555f, left + tileW, h * .685f),
                    "传输记录", "在应用内查看", "◷")
                drawSettingCard(c, RectF(left + tileW + gap, h * .555f, w * .87f, h * .685f),
                    "打开小李全联", "进入文件传输应用", settingsFocus == 6)
            }
            6 -> {
                drawSettingsSectionLabel(c, "电视系统入口说明", w * .13f, h * .335f, w * .74f)
                drawCompactFeatureTile(c, RectF(w * .13f, h * .385f, w * .485f, h * .565f),
                    "网络连接", "在电视系统中管理", "⌁")
                drawCompactFeatureTile(c, RectF(w * .515f, h * .385f, w * .87f, h * .565f),
                    "显示设置", "在电视系统中调整", "☼")
                drawCompactFeatureTile(c, RectF(w * .13f, h * .59f, w * .485f, h * .77f),
                    "设备偏好", "查看系统设备选项", "⚙")
                drawSettingCard(c, RectF(w * .515f, h * .59f, w * .87f, h * .77f),
                    "打开系统设置", "进入 Android TV 完整设置", settingsFocus == 7)
                drawSettingsSectionLabel(c, "系统设置说明", w * .13f, h * .845f, w * .74f)
                text(c, "网络、显示与设备选项由电视系统提供，具体项目以当前电视型号为准。",
                    w * .13f, h * .895f, h * .017f, 0xFFB4CBE0.toInt())
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
        drawerMetrics().indexAt(x, y, drawerScrollVisualRow, apps.size)

    /** Aerial-style direct manipulation + native spline fling for the app drawer. */
    private fun handleDrawerTouchScroll(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drawerTouchEligible = isDrawerOpen && event.x in drawerMetrics().gridLeft..drawerMetrics().gridRight &&
                    event.y in drawerMetrics().contentTop..drawerMetrics().contentBottom
                drawerTouchScrolling = false
                if (drawerTouchEligible) {
                    drawerFlingScroller.abortAnimation()
                    drawerScrollAnimator?.cancel()
                    drawerTouchDownY = event.y
                    drawerTouchStartVisualRow = drawerScrollVisualRow
                    drawerTouchTracker?.recycle()
                    drawerTouchTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                }
            }
            MotionEvent.ACTION_MOVE -> if (drawerTouchEligible) {
                drawerTouchTracker?.addMovement(event)
                val dy = event.y - drawerTouchDownY
                if (!drawerTouchScrolling && kotlin.math.abs(dy) > drawerTouchSlop) {
                    drawerTouchScrolling = true
                }
                if (drawerTouchScrolling) {
                    val metrics = drawerMetrics()
                    val maxRow = metrics.maxScroll(apps.size).toFloat()
                    drawerScrollVisualRow = (drawerTouchStartVisualRow - dy / metrics.cellHeight)
                        .coerceIn(0f, maxRow)
                    drawerScrollRow = drawerScrollVisualRow.toInt().coerceIn(0, metrics.maxScroll(apps.size))
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP -> if (drawerTouchEligible) {
                drawerTouchTracker?.addMovement(event)
                if (drawerTouchScrolling) {
                    val metrics = drawerMetrics()
                    drawerTouchTracker?.computeCurrentVelocity(1000)
                    val velocityY = drawerTouchTracker?.yVelocity ?: 0f
                    val maxY = (metrics.maxScroll(apps.size) * metrics.cellHeight).toInt().coerceAtLeast(0)
                    drawerFlingScroller.fling(0, (drawerScrollVisualRow * metrics.cellHeight).toInt(),
                        0, -velocityY.toInt(), 0, 0, 0, maxY)
                    postInvalidateOnAnimation()
                    finishDrawerTouchTracking()
                    return true
                }
                finishDrawerTouchTracking()
            }
            MotionEvent.ACTION_CANCEL -> {
                val wasScrolling = drawerTouchScrolling
                finishDrawerTouchTracking()
                return wasScrolling
            }
        }
        return false
    }

    private fun finishDrawerTouchTracking() {
        drawerTouchTracker?.recycle()
        drawerTouchTracker = null
        drawerTouchEligible = false
        drawerTouchScrolling = false
    }

    override fun computeScroll() {
        super.computeScroll()
        if (drawerFlingScroller.computeScrollOffset()) {
            val metrics = drawerMetrics()
            drawerScrollVisualRow = (drawerFlingScroller.currY / metrics.cellHeight)
                .coerceIn(0f, metrics.maxScroll(apps.size).toFloat())
            drawerScrollRow = drawerScrollVisualRow.toInt()
            postInvalidateOnAnimation()
        }
    }

    private fun updateControlSliderFromTouch(x: Float) {
        val panelLeft = width * .60f
        val panelWidth = width * .375f
        val fraction = ((x - (panelLeft + panelWidth * .10f)) / (panelWidth * .80f)).coerceIn(0f, 1f)
        if (controlCenterPage == 2) {
            if (!isControlVolumeFixed()) {
                val max = volumeLevel().second
                val target = (fraction * max).toInt().coerceIn(0, max)
                runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
            }
        } else if (hisenseLaserLevel() != null) {
            setHisenseLaserLevel((fraction * 10f).toInt().coerceIn(0, 10))
        } else if (xgimiBrightnessLevel() != null) {
            setXgimiBrightnessLevel((fraction * 10f).toInt().coerceIn(1, 10))
        } else if (isPlaybackDevice() || hasVendorBrightnessBackend() ||
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(context)) {
            adjustControlBrightness(0)
        } else {
            val target = (fraction * 255f).toInt().coerceIn(1, 255)
            runCatching { setControlBrightnessLevel(target) }
        }
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (handleDrawerTouchScroll(event)) return true
        if (controlCenterOpen) {
            if (controlCenterPage == 1 || controlCenterPage == 4) {
                val panelTop = height * .075f
                val panelBottom = height * .925f
                val rowPitch = height * .85f * (if (controlCenterPage == 4) .102f else .10f)
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        controlListTouchActive = event.x >= width * .60f &&
                            event.y in (panelTop + height * .85f * .20f)..(panelBottom - height * .08f)
                        controlListTouchDragging = false
                        controlListTouchDownY = event.y
                        controlListTouchLastY = event.y
                        controlListTouchTravel = 0f
                    }
                    MotionEvent.ACTION_MOVE -> if (controlListTouchActive) {
                        val dy = event.y - controlListTouchLastY
                        controlListTouchLastY = event.y
                        if (!controlListTouchDragging &&
                            kotlin.math.abs(event.y - controlListTouchDownY) > ViewConfiguration.get(context).scaledTouchSlop) {
                            controlListTouchDragging = true
                        }
                        if (controlListTouchDragging && rowPitch > 0f) {
                            controlListTouchTravel -= dy
                            val total = if (controlCenterPage == 1) visibleControlInputs().size else controlInputs.size
                            val visible = if (controlCenterPage == 1) 6 else 5
                            while (controlListTouchTravel >= rowPitch) {
                                controlCenterInputOffset = (controlCenterInputOffset + 1).coerceAtMost((total - visible).coerceAtLeast(0))
                                controlListTouchTravel -= rowPitch
                            }
                            while (controlListTouchTravel <= -rowPitch) {
                                controlCenterInputOffset = (controlCenterInputOffset - 1).coerceAtLeast(0)
                                controlListTouchTravel += rowPitch
                            }
                            controlCenterFocus = controlCenterFocus.coerceAtMost(
                                (minOf(visible, (total - controlCenterInputOffset).coerceAtLeast(1)) - 1).coerceAtLeast(0))
                            invalidate()
                            return true
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val wasDragging = controlListTouchDragging
                        controlListTouchActive = false
                        controlListTouchDragging = false
                        if (wasDragging) {
                            if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                            return true
                        }
                    }
                }
            }
            val sliderTop = height * (.075f + .85f * .43f)
            val sliderBottom = height * (.075f + .85f * .49f)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    controlSliderTouchActive = controlCenterPage in 2..3 &&
                        event.x >= width * .60f && event.y in sliderTop..sliderBottom
                    if (controlSliderTouchActive) {
                        updateControlSliderFromTouch(event.x)
                        return true
                    }
                }
                MotionEvent.ACTION_MOVE -> if (controlSliderTouchActive) {
                    updateControlSliderFromTouch(event.x)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (controlSliderTouchActive) {
                    if (event.actionMasked == MotionEvent.ACTION_UP) updateControlSliderFromTouch(event.x)
                    controlSliderTouchActive = false
                    if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                    return true
                }
            }
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
                            if (!isControlVolumeFixed()) {
                                val max = volumeLevel().second
                                val target = (fraction * max).toInt().coerceIn(0, max)
                                runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
                            }
                        } else if (hisenseLaserLevel() != null) {
                            setHisenseLaserLevel((fraction * 10f).toInt().coerceIn(0, 10))
                        } else if (xgimiBrightnessLevel() != null) {
                            setXgimiBrightnessLevel((fraction * 10f).toInt().coerceIn(1, 10))
                        } else if (isPlaybackDevice() || hasVendorBrightnessBackend() ||
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(context)) {
                            adjustControlBrightness(0)
                        } else {
                            val target = (fraction * 255f).toInt().coerceIn(1, 255)
                            runCatching { setControlBrightnessLevel(target) }
                        }
                    } else {
                        val row = if (controlCenterPage == 0) {
                            val first = height * .075f + height * .85f * .18f
                            val gap = height * .85f * .085f
                            ((event.y - first) / gap).toInt().coerceIn(0, 7)
                        } else if (controlCenterPage == 4) {
                            val first = height * .075f + height * .85f * .205f
                            val gap = height * .85f * .102f
                            ((event.y - first) / gap).toInt().coerceIn(0, minOf(5, controlInputs.size.coerceAtMost(5)))
                        } else {
                            val first = height * .075f + height * .85f * .225f
                            val gap = height * .85f * .10f
                            val maxIndex = visibleControlInputs().lastIndex
                            ((event.y - first) / gap).toInt().coerceIn(0, minOf(5, maxIndex.coerceAtLeast(0)))
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
                    val isHomeMenu = actionMenuSource == "home"
                    val count = if (isHomeMenu) 6 else 4
                    val top = height * (if (isHomeMenu) .12f else .22f)
                    val bottom = height * (if (isHomeMenu) .88f else .78f)
                    val rowStart = if (isHomeMenu) .20f else .25f
                    val rowStep = if (isHomeMenu) .125f else .17f
                    if (event.x in (width * .34f)..(width * .66f) && event.y in top..bottom) {
                        val row = ((event.y - top - (bottom - top) * rowStart) / ((bottom - top) * rowStep)).toInt()
                        actionMenuChoice = row.coerceIn(0, count - 1)
                        runAppAction()
                    } else actionMenuOpen = false
                } else if (isSettingsOpen) {
                    if ((0..6).any { SettingsLayout.categoryBounds(it, width.toFloat(), height.toFloat()).contains(event.x, event.y) }) {
                        val category = (0..6).first { SettingsLayout.categoryBounds(it, width.toFloat(), height.toFloat()).contains(event.x, event.y) }
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
                                } else if ((0..2).any { SettingsLayout.drawerGridOptionBounds(it, 0, width.toFloat(), height.toFloat()).contains(settingsHitX, event.y) }) {
                                    val selected = (0..2).first { SettingsLayout.drawerGridOptionBounds(it, 0, width.toFloat(), height.toFloat()).contains(settingsHitX, event.y) }
                                    setDrawerColumns(selected + 4)
                                } else if ((0..2).any { SettingsLayout.drawerGridOptionBounds(it, 1, width.toFloat(), height.toFloat()).contains(settingsHitX, event.y) }) {
                                    val selected = (0..2).first { SettingsLayout.drawerGridOptionBounds(it, 1, width.toFloat(), height.toFloat()).contains(settingsHitX, event.y) }
                                    setDrawerRows(selected + 2)
                                } else if (event.y in (height * .88f)..(height * .945f) &&
                                    settingsHitX in (width * .13f)..(width * .60f)) {
                                    settingsFocus = 23
                                    resetHomeIconPositions()
                                }
                            }
                            1 -> {
                                if (event.y in (height * .37f)..(height * .59f) &&
                                    settingsHitX in (width * .515f)..(width * .87f)) {
                                    settingsFocus = 3
                                    onChooseWallpaper?.invoke()
                                } else if (event.y in (height * .62f)..(height * .80f)) {
                                    if (settingsHitX in (width * .13f)..(width * .485f)) {
                                        settingsFocus = 4
                                        toggleDynamicWallpaper()
                                    } else if (settingsHitX in (width * .515f)..(width * .87f)) {
                                        settingsFocus = 5
                                        clearWallpaper()
                                    }
                                } else if (event.y in (height * .835f)..(height * .945f) &&
                                    settingsHitX in (width * .13f)..(width * .485f)) {
                                    settingsFocus = 24
                                    context.startActivity(Intent(context, com.xiaoli.quanlian.QuanLianActivity::class.java)
                                        .putExtra(com.xiaoli.quanlian.QuanLianActivity.EXTRA_WALLPAPER_MODE, true))
                                }
                            }
                            2 -> if ((event.y in (height * .365f)..(height * .765f) &&
                                settingsHitX in (width * .13f)..(width * .87f)) ||
                                (event.y in (height * .875f)..(height * .945f) &&
                                    settingsHitX in (width * .13f)..(width * .60f))) {
                                settingsFocus = 21
                                isSettingsOpen = false
                                isDrawerOpen = true
                                editingFavorites = true
                                drawerIndex = 0
                                resetDrawerScroll()
                            }
                            3 -> {
                                if (event.y in (height * .35f)..(height * .62f) &&
                                    settingsHitX in (width * .13f)..(width * .87f)) {
                                    val row = if (event.y < height * .485f) 0 else 1
                                    val col = if (settingsHitX < width * .50f) 0 else 1
                                    val selected = row * 2 + col
                                    settingsFocus = listOf(9, 10, 15, 16)[selected]
                                    when (selected) {
                                        0 -> toggleHomeComponent(9)
                                        1 -> toggleHomeComponent(10)
                                        2 -> { use24HourClock = !use24HourClock; prefs.edit().putBoolean("clock_24_hour", use24HourClock).apply() }
                                        3 -> { showClockSeconds = !showClockSeconds; prefs.edit().putBoolean("clock_seconds", showClockSeconds).apply(); updateClockTick() }
                                    }
                                } else if (event.y in (height * .70f)..(height * .83f) &&
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
                            5 -> if (event.y in (height * .555f)..(height * .685f) &&
                                settingsHitX in (width * .642f)..(width * .87f)) {
                                settingsFocus = 6
                                context.startActivity(Intent(context, com.xiaoli.quanlian.QuanLianActivity::class.java))
                            }
                            6 -> if (event.y in (height * .59f)..(height * .77f) &&
                                settingsHitX in (width * .515f)..(width * .87f)) {
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
        // OK long-press is handled only by okLongPressRunnable. Keep Android's
        // native callback from opening a second menu or changing press state.
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) &&
            (okPressPending || longPressReleaseConsumed || actionMenuOpen)) return true
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
        if ((keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) &&
            (actionMenuSelectionConsumed || longPressReleaseConsumed ||
                (okPressPending && okLongPressTriggered))) return true
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            return handleBack()
        }
        if (controlCenterOpen) {
            val count = when (controlCenterPage) {
                0 -> 8
                1 -> visibleControlInputs().size.coerceAtLeast(1).coerceAtMost(6)
                4 -> controlInputs.size.coerceAtMost(5) + 1
                else -> 1
            }
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (controlCenterPage == 1) {
                        if (controlCenterFocus > 0) controlCenterFocus--
                        else if (controlCenterInputOffset > 0) controlCenterInputOffset--
                    } else if (controlCenterPage == 4) {
                        if (controlCenterFocus > 0) controlCenterFocus--
                        else if (controlCenterInputOffset > 0) {
                            controlCenterInputOffset--
                            controlCenterFocus = minOf(5, (controlInputs.size - controlCenterInputOffset).coerceAtMost(5))
                        }
                    } else controlCenterFocus = (controlCenterFocus - 1).coerceAtLeast(0)
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (controlCenterPage == 1) {
                        val total = visibleControlInputs().size
                        val visibleCount = minOf(6, (total - controlCenterInputOffset).coerceAtLeast(1))
                        if (controlCenterFocus < visibleCount - 1) controlCenterFocus++
                        else if (controlCenterInputOffset + visibleCount < total) controlCenterInputOffset++
                    } else if (controlCenterPage == 4) {
                        val total = controlInputs.size
                        val visibleCount = minOf(5, (total - controlCenterInputOffset).coerceAtLeast(0))
                        if (controlCenterFocus < visibleCount) controlCenterFocus++
                        else if (controlCenterInputOffset + visibleCount < total) controlCenterInputOffset++
                    } else controlCenterFocus = (controlCenterFocus + 1).coerceAtMost(count - 1)
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> activateControlCenterItem()
                KeyEvent.KEYCODE_DPAD_LEFT -> when (controlCenterPage) {
                    1 -> { controlCenterPage = 0; controlCenterFocus = 1 }
                    2 -> adjustControlVolume(-1)
                    3 -> adjustControlBrightness(-1)
                    4 -> if (controlCenterFocus > 0)
                        controlInputs.getOrNull(controlCenterInputOffset + controlCenterFocus - 1)?.let(::toggleControlInputHidden)
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> when (controlCenterPage) {
                    1 -> activateControlCenterItem()
                    2 -> adjustControlVolume(1)
                    3 -> adjustControlBrightness(1)
                    4 -> if (controlCenterFocus > 0)
                        controlInputs.getOrNull(controlCenterInputOffset + controlCenterFocus - 1)?.let(::toggleControlBootInput)
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
                    // A held OK key may generate repeat events after the menu opens.
                    // Only a fresh press may activate the focused menu item.
                    if (event.repeatCount == 0 && !okLongPressTriggered) {
                        actionMenuSelectionConsumed = true
                        runAppAction()
                    }
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
            (homeTopFocus in 0..1 || favorites.isNotEmpty())) {
            if (event.repeatCount == 0) {
                if (homeTopFocus == -1) beginOkPressTracking("home", homeIndex)
                else event.startTracking()
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
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (settingsFocus == 8) {
                        homeIconScale = (homeIconScale - .05f).coerceAtLeast(.65f)
                        prefs.edit().putFloat("home_icon_scale", homeIconScale).apply()
                    } else moveSettingsFocus(-1)
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (settingsFocus == 8) {
                        homeIconScale = (homeIconScale + .05f).coerceAtMost(1.6f)
                        prefs.edit().putFloat("home_icon_scale", homeIconScale).apply()
                    } else moveSettingsFocus(1)
                }
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
                        if (!drawerHeaderFocused) beginOkPressTracking("drawer", drawerIndex)
                        else event.startTracking()
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
                // UP moves focus toward the top widgets; it no longer opens the app drawer.
                if (homeTopFocus == -1) moveHomeFocusToTop()
                invalidate()
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (homeTopFocus in 0..1) {
                    moveHomeFocusToApps()
                    return true
                }
                // From the home app area, DOWN opens the app drawer.
                drawerReturnHomeTopFocus = homeTopFocus
                isDrawerOpen = true
                homeTopFocus = -1
                drawerIndex = 0
                resetDrawerScroll()
                invalidate()
                return true
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
