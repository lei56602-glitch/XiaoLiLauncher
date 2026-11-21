package com.xiaoli.quanlian

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.Cursor
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import com.xiaoli.transfer.android.ReceiveForegroundService
import com.xiaoli.transfer.android.TransferForegroundService
import com.xiaoli.transfer.api.DeviceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import org.json.JSONArray
import java.util.Locale

class QuanLianActivity : Activity() {
    private enum class Page { HOME, SEND, RECEIVE, DEVICES, HISTORY }

    private lateinit var screen: QuanLianScreen
    private val discovery by lazy { com.xiaoli.quanlian.discovery.AndroidLanDiscovery(applicationContext) }
    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var discoveryJob: Job? = null
    private var eventReceiver: BroadcastReceiver? = null
    private var page = Page.HOME
    private var focus = 0
    private var navFocused = false
    private var navIndex = 0
    private var backFocused = false
    private var historyScrollOffset = 0
    private var sendDeviceScrollRow = 0
    private var deviceScrollOffset = 0
    private var devices: List<DeviceInfo> = emptyList()
    private val selectedUris = arrayListOf<Uri>()
    private val selectedNames = arrayListOf<String>()
    private val selectedSizes = arrayListOf<Long>()
    private var status = "正在搜索附近设备…"
    private var receiveEnabled = false
    private var transferRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receiveEnabled = getSharedPreferences("quanlian_runtime", MODE_PRIVATE)
            .getBoolean("receive_enabled", false)
        screen = QuanLianScreen()
        setContentView(screen)
    }

    override fun onResume() {
        super.onResume()
        if (::screen.isInitialized) screen.requestFocus()
    }

    override fun onBackPressed() {
        if (::screen.isInitialized) {
            if (navFocused) {
                navFocused = false
                screen.invalidate()
            } else goBack()
            return
        }
        super.onBackPressed()
    }

    override fun onStart() {
        super.onStart()
        getSharedPreferences("quanlian_runtime", MODE_PRIVATE).edit().putBoolean("activity_visible", true).apply()
        discovery.start()
        discoveryJob = screenScope.launch {
            discovery.devices.collect {
                devices = it
                if (page == Page.DEVICES) {
                    focus = focus.coerceAtMost((devices.size - 1).coerceAtLeast(0))
                    deviceScrollOffset = deviceScrollOffset.coerceIn(0, (devices.size - 9).coerceAtLeast(0))
                }
                screen.invalidate()
            }
        }
        registerTransferEvents()
        // A persisted enabled flag represents an earlier explicit user opt-in.
        if (receiveEnabled) startReceiveService()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    override fun onStop() {
        getSharedPreferences("quanlian_runtime", MODE_PRIVATE).edit().putBoolean("activity_visible", false).apply()
        discoveryJob?.cancel()
        discoveryJob = null
        discovery.stop()
        super.onStop()
    }

    override fun onDestroy() {
        eventReceiver?.let { runCatching { unregisterReceiver(it) } }
        eventReceiver = null
        screenScope.cancel()
        super.onDestroy()
    }

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerTransferEvents() {
        if (eventReceiver != null) return
        eventReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    ReceiveForegroundService.ACTION_STATUS -> {
                        status = intent.getStringExtra(ReceiveForegroundService.EXTRA_TEXT)
                            ?: "接收服务状态已更新"
                        screen.invalidate()
                        if (status.contains("接收完成")) {
                            AlertDialog.Builder(this@QuanLianActivity)
                                .setTitle("文件接收完成")
                                .setMessage("$status\n文件已通过 SHA-256 完整性校验。")
                                .setPositiveButton("知道了", null)
                                .show()
                        }
                    }
                    ReceiveForegroundService.ACTION_INCOMING -> {
                        val requestId = intent.getStringExtra(ReceiveForegroundService.EXTRA_ID) ?: return
                        val sender = intent.getStringExtra(ReceiveForegroundService.EXTRA_SENDER) ?: "附近设备"
                        val count = intent.getIntExtra(ReceiveForegroundService.EXTRA_COUNT, 0)
                        val total = intent.getLongExtra(ReceiveForegroundService.EXTRA_TOTAL, 0L)
                        val activityVisible = getSharedPreferences("quanlian_runtime", MODE_PRIVATE)
                            .getBoolean("activity_visible", false)
                        if (activityVisible && !isFinishing && !isDestroyed) {
                            val dialog = AlertDialog.Builder(this@QuanLianActivity)
                                .setTitle("收到文件传输请求")
                                .setMessage("发送设备：$sender\n文件数量：$count 个\n文件总大小：${sizeText(total)}\n\n是否接收这些文件？")
                                .setPositiveButton("接收") { _, _ ->
                                    ReceiveForegroundService.decide(this@QuanLianActivity, requestId, true)
                                }
                                .setNegativeButton("拒绝") { _, _ ->
                                    ReceiveForegroundService.decide(this@QuanLianActivity, requestId, false)
                                }
                                .setOnCancelListener {
                                    ReceiveForegroundService.decide(this@QuanLianActivity, requestId, false)
                                }
                                .create()
                            dialog.setOnShowListener {
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply { setTextColor(0xFF20C878.toInt()); textSize = 17f }
                                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply { setTextColor(0xFFFF5B65.toInt()); textSize = 17f }
                                val gap = (24 * resources.displayMetrics.density).toInt()
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.let { button ->
                                    (button.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { lp ->
                                        lp.marginStart = gap; button.layoutParams = lp
                                    }
                                }
                                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.let { button ->
                                    (button.layoutParams as? android.widget.LinearLayout.LayoutParams)?.let { lp ->
                                        lp.marginEnd = gap; button.layoutParams = lp
                                    }
                                }
                            }
                            dialog.show()
                        }
                    }
                    TransferForegroundService.ACTION_PROGRESS -> {
                        transferRunning = true
                        status = intent.getStringExtra(TransferForegroundService.EXTRA_TEXT)
                            ?: "正在传输文件…"
                        screen.invalidate()
                    }
                    TransferForegroundService.ACTION_RESULT -> {
                        transferRunning = false
                        val success = intent.getBooleanExtra(TransferForegroundService.EXTRA_SUCCESS, false)
                        val cancelled = intent.getBooleanExtra(TransferForegroundService.EXTRA_CANCELLED, false)
                        val error = intent.getStringExtra(TransferForegroundService.EXTRA_ERROR)
                        status = when {
                            success -> "发送完成，文件完整性校验通过"
                            cancelled -> "传输已取消"
                            else -> "发送失败：${error ?: "未知错误"}"
                        }
                        screen.invalidate()
                        if (!isFinishing) {
                            AlertDialog.Builder(this@QuanLianActivity)
                                .setTitle(if (success) "发送成功" else if (cancelled) "传输已取消" else "发送失败")
                                .setMessage(status)
                                .setPositiveButton("确定", null)
                                .show()
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ReceiveForegroundService.ACTION_STATUS)
            addAction(ReceiveForegroundService.ACTION_INCOMING)
            addAction(TransferForegroundService.ACTION_PROGRESS)
            addAction(TransferForegroundService.ACTION_RESULT)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(eventReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(eventReceiver, filter)
    }

    private fun openFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        runCatching { startActivityForResult(intent, REQUEST_PICK_FILES) }
            .onFailure { Toast.makeText(this, "无法打开系统文件选择器", Toast.LENGTH_LONG).show() }
    }

    @Deprecated("The document picker result is handled for Android TV compatibility.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_FILES || resultCode != RESULT_OK || data == null) return
        val picked = arrayListOf<Uri>()
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(picked::add)
        }
        if (picked.isEmpty()) data.data?.let(picked::add)
        for (uri in picked) {
            if (selectedUris.size >= 500 || selectedUris.contains(uri)) continue
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val (name, size) = queryFileInfo(uri)
            selectedUris.add(uri)
            selectedNames.add(name)
            selectedSizes.add(size)
        }
        status = if (selectedUris.isEmpty()) "尚未选择文件" else "已添加 ${selectedUris.size} 个文件，可选择附近设备发送"
        page = Page.SEND
        focus = 0
        screen.invalidate()
    }

    private fun queryFileInfo(uri: Uri): Pair<String, Long> {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "文件"
        var size = 0L
        var cursor: Cursor? = null
        try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            if (cursor?.moveToFirst() == true) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: name
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
            }
        } catch (_: Exception) {
        } finally {
            cursor?.close()
        }
        return name to size
    }

    private fun startReceive() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            status = "需要通知权限才能可靠提示接收请求，正在申请…"
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
            screen.invalidate()
            return
        }
        startReceiveService()
    }

    private fun startReceiveService() {
        receiveEnabled = true
        getSharedPreferences("quanlian_runtime", MODE_PRIVATE).edit().putBoolean("receive_enabled", true).apply()
        runCatching { ReceiveForegroundService.start(this) }
            .onSuccess { status = "正在启动接收服务…" }
            .onFailure {
                receiveEnabled = false
                getSharedPreferences("quanlian_runtime", MODE_PRIVATE).edit().putBoolean("receive_enabled", false).apply()
                status = "接收服务启动失败：${it.message}"
            }
        screen.invalidate()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS) {
            if (grantResults.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                startReceiveService()
            } else {
                // Foreground service can still run, but incoming-request notifications may be hidden.
                startReceiveService()
                status = "接收已开启，但通知权限被拒绝，可能无法及时看到接收请求"
                screen.invalidate()
            }
        }
    }

    private fun stopReceive() {
        receiveEnabled = false
        getSharedPreferences("quanlian_runtime", MODE_PRIVATE).edit().putBoolean("receive_enabled", false).apply()
        stopService(Intent(this, ReceiveForegroundService::class.java))
        status = "接收服务已停止"
        screen.invalidate()
    }

    private fun requestSend(device: DeviceInfo) {
        if (transferRunning) {
            Toast.makeText(this, "当前已有传输任务，请先等待完成或取消", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedUris.isEmpty()) {
            Toast.makeText(this, "请先添加要发送的文件", Toast.LENGTH_SHORT).show()
            page = Page.SEND
            focus = 0
            screen.invalidate()
            return
        }
        val pins = getSharedPreferences("trusted_device_pins", MODE_PRIVATE)
        val existing = pins.getString(device.id, null)
        if (existing != null && !existing.equals(device.fingerprint, ignoreCase = true)) {
            AlertDialog.Builder(this)
                .setTitle("设备身份验证失败")
                .setMessage("该设备的证书指纹与此前保存的身份不一致。为保护文件安全，已阻止连接。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        if (existing == null) {
            AlertDialog.Builder(this)
                .setTitle("首次连接 · 确认设备身份")
                .setMessage("设备：${device.name}\nTLS 证书 SHA-256 指纹：\n\n${device.fingerprint}\n\n确认信任后会保存该设备身份。以后证书发生变化时将拒绝连接。")
                .setNegativeButton("取消", null)
                .setPositiveButton("信任并发送") { _, _ ->
                    pins.edit().putString(device.id, device.fingerprint).commit()
                    startTransfer(device)
                }
                .show()
            return
        }
        startTransfer(device)
    }

    private fun startTransfer(device: DeviceInfo) {
        transferRunning = true
        status = "正在准备发送 ${selectedUris.size} 个文件…"
        screen.invalidate()
        runCatching {
            TransferForegroundService.sendFiles(
                this,
                ArrayList(selectedUris),
                ArrayList(selectedNames),
                device.host,
                device.port,
                device.name,
                device.fingerprint
            )
        }.onFailure {
            transferRunning = false
            status = "无法启动传输服务：${it.message}"
            screen.invalidate()
        }
    }

    private fun openPage(target: Page) {
        page = target
        navIndex = target.ordinal
        navFocused = false
        backFocused = false
        focus = 0
        if(target==Page.HISTORY)historyScrollOffset=0
        if(target==Page.DEVICES)deviceScrollOffset=0
        screen.invalidate()
    }

    private fun goBack() {
        backFocused = false
        when (page) {
            Page.HOME -> finish()
            Page.SEND, Page.RECEIVE, Page.DEVICES, Page.HISTORY -> {
                page = Page.HOME
                focus = 0
                screen.invalidate()
            }
        }
    }

    private fun readHistoryItems(): JSONArray = runCatching {
        JSONArray(getSharedPreferences("transfer_history",MODE_PRIVATE).getString("items","[]"))
    }.getOrDefault(JSONArray())

    private fun openHistoryDirectory() {
        val directoryUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2F%E5%B0%8F%E6%9D%8E%E5%85%A8%E8%81%94")
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(directoryUri, "vnd.android.document/directory")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(viewIntent) }.onFailure {
            runCatching { startActivity(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)) }
                .onFailure { Toast.makeText(this, "当前设备没有可用的文件管理器，请从下载目录进入“小李全联”文件夹", Toast.LENGTH_LONG).show() }
        }
    }

    private fun openHistoryItem(item: org.json.JSONObject?) {
        val value=item?.optString("uri").orEmpty()
        if(value.isBlank()){Toast.makeText(this,"这条记录没有可打开的文件地址",Toast.LENGTH_SHORT).show();return}
        val uri=runCatching{Uri.parse(value)}.getOrNull()?:return
        val intent=Intent(Intent.ACTION_VIEW).apply{
            setDataAndType(uri,contentResolver.getType(uri)?:"*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching{startActivity(intent)}
            .onFailure{Toast.makeText(this,"没有可打开此文件的应用",Toast.LENGTH_LONG).show()}
    }

    private fun activateFocused() {
        when (page) {
            Page.HOME -> when (focus.coerceIn(0, 3)) {
                0 -> openPage(Page.SEND)
                1 -> openPage(Page.RECEIVE)
                2 -> openPage(Page.DEVICES)
                3 -> openPage(Page.HISTORY)
            }
            Page.SEND -> when {
                focus == 0 -> openFilePicker()
                transferRunning && focus == 1 -> {
                    TransferForegroundService.cancelTransfer(this)
                    status = "已请求取消传输…"
                    screen.invalidate()
                }
                else -> devices.getOrNull(focus - if (transferRunning) 2 else 1)?.let(::requestSend)
            }
            Page.RECEIVE -> if (focus == 0) {
                if (receiveEnabled) stopReceive() else startReceive()
            }
            Page.DEVICES -> devices.getOrNull(focus)?.let { device ->
                page = Page.SEND
                screen.invalidate()
                requestSend(device)
            }
            Page.HISTORY -> {
                val items=readHistoryItems()
                if(focus<items.length()) openHistoryItem(items.optJSONObject(focus))
                else if(focus==items.length()) openHistoryDirectory()
                else if(focus==items.length()+1){
                    getSharedPreferences("transfer_history",MODE_PRIVATE).edit().putString("items","[]").apply()
                    focus=0;historyScrollOffset=0;status="传输记录已清空"
                }
                screen.invalidate()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (backFocused) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> backFocused = false
                KeyEvent.KEYCODE_DPAD_LEFT -> { backFocused = false; navFocused = true; navIndex = page.ordinal }
                KeyEvent.KEYCODE_DPAD_UP -> Unit
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { goBack(); return true }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { backFocused = false; return true }
                else -> return super.onKeyDown(keyCode, event)
            }
            screen.invalidate()
            return true
        }
        if (!navFocused && keyCode == KeyEvent.KEYCODE_DPAD_UP && focus == 0) {
            backFocused = true
            screen.invalidate()
            return true
        }
        if (navFocused) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> navIndex = (navIndex - 1).coerceAtLeast(0)
                KeyEvent.KEYCODE_DPAD_DOWN -> navIndex = (navIndex + 1).coerceAtMost(4)
                KeyEvent.KEYCODE_DPAD_RIGHT -> navFocused = false
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    openPage(Page.values()[navIndex])
                }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { navFocused = false; return true }
                else -> return super.onKeyDown(keyCode, event)
            }
            screen.invalidate()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && focus == 0) {
            navFocused = true
            navIndex = page.ordinal
            screen.invalidate()
            return true
        }
        if (page == Page.HOME) {
            val columns = if (screen.width * .83f >= screen.height) 4 else 2
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (focus % columns > 0) focus-- else if (focus == 0) navFocused = true
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (focus % columns < columns - 1 && focus + 1 < 4) focus++
                KeyEvent.KEYCODE_DPAD_UP -> if (focus >= columns) focus -= columns
                KeyEvent.KEYCODE_DPAD_DOWN -> if (focus + columns < 4) focus += columns
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> activateFocused()
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { goBack(); return true }
                else -> return super.onKeyDown(keyCode, event)
            }
            screen.invalidate()
            return true
        }
        if (page == Page.SEND) {
            val deviceStart = if (transferRunning) 2 else 1
            val deviceIndex = focus - deviceStart
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (focus < deviceStart) focus = (focus - 1).coerceAtLeast(0)
                    else if (deviceIndex >= 0 && deviceIndex % 2 == 1) focus--
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (focus < deviceStart) focus = if (transferRunning) 1 else 0
                    else if (deviceIndex >= 0 && deviceIndex % 2 == 0 && deviceIndex + 1 < devices.size) focus++
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (deviceIndex >= 2) focus -= 2
                    else if (deviceIndex >= 0) {
                        // The first device row maps back to the matching top action.
                        focus = if (transferRunning && deviceIndex % 2 == 1) 1 else 0
                    }
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (focus < deviceStart) {
                        if (devices.isNotEmpty()) {
                            focus = if (transferRunning && focus == 1 && devices.size > 1) deviceStart + 1 else deviceStart
                        }
                    } else if (deviceIndex >= 0 && deviceIndex + 2 < devices.size) focus += 2
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> activateFocused()
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> { goBack(); return true }
                else -> return super.onKeyDown(keyCode, event)
            }
            val visibleRows = 3
            val maxScroll = ((devices.size + 1) / 2 - visibleRows).coerceAtLeast(0)
            val selectedDevice = focus - deviceStart
            if (selectedDevice >= 0) {
                sendDeviceScrollRow = (selectedDevice / 2 - visibleRows + 1).coerceIn(0, maxScroll)
            } else sendDeviceScrollRow = sendDeviceScrollRow.coerceIn(0, maxScroll)
            screen.invalidate()
            return true
        }
        if(page==Page.HISTORY){
            val count=readHistoryItems().length()
            when(keyCode){
                KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_LEFT -> focus=(focus-1).coerceAtLeast(0)
                KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT -> focus=(focus+1).coerceAtMost(count+1)
                KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER -> activateFocused()
                KeyEvent.KEYCODE_BACK,KeyEvent.KEYCODE_ESCAPE -> {goBack();return true}
                else -> return super.onKeyDown(keyCode,event)
            }
            historyScrollOffset=when{
                focus<historyScrollOffset -> focus
                focus>=historyScrollOffset+8 -> focus-7
                else -> historyScrollOffset
            }.coerceAtLeast(0)
            screen.invalidate();return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            goBack()
            return true
        }
        val max = when (page) {
            Page.HOME -> 3
            Page.SEND -> devices.size + if (transferRunning) 1 else 0
            Page.DEVICES -> (devices.size - 1).coerceAtLeast(0)
            Page.RECEIVE -> 0
            Page.HISTORY -> readHistoryItems().length()+1
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> focus = (focus - 1).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> focus = (focus + 1).coerceAtMost(max)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> activateFocused()
            else -> return super.onKeyDown(keyCode, event)
        }
        if (page == Page.DEVICES) {
            deviceScrollOffset = when {
                focus < deviceScrollOffset -> focus
                focus >= deviceScrollOffset + 9 -> focus - 8
                else -> deviceScrollOffset
            }.coerceIn(0, (devices.size - 9).coerceAtLeast(0))
        }
        screen.invalidate()
        return true
    }

    private inner class QuanLianScreen : View(this@QuanLianActivity) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val blue = 0xFF0878E8.toInt()
        private val pale = 0xFFDDEEFF.toInt()
        private var backgroundGradient: LinearGradient? = null
        private var backgroundGradientWidth = -1f
        private var backgroundGradientHeight = -1f

        private fun backgroundGradientFor(w: Float, h: Float): LinearGradient {
            val cached = backgroundGradient
            if (cached != null && backgroundGradientWidth == w && backgroundGradientHeight == h) return cached
            return LinearGradient(
                0f, 0f, w, h,
                intArrayOf(0xFF0878E8.toInt(), 0xFF0754B9.toInt(), 0xFF032D79.toInt()),
                null, Shader.TileMode.CLAMP
            ).also {
                backgroundGradient = it
                backgroundGradientWidth = w
                backgroundGradientHeight = h
            }
        }

        init {
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            contentDescription = "小李全联电视版，左侧导航包含首页、发送、接收、设备和记录"
            requestFocus()
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val w = width.toFloat()
            val h = height.toFloat()
            val unit = minOf(w, h)
            p.shader = backgroundGradientFor(w, h)
            c.drawRect(0f, 0f, w, h, p)
            p.shader = null
            val railWidth = w * .17f
            drawNavigationRail(c, railWidth, h, unit)
            c.save()
            c.translate(railWidth, 0f)
            val contentWidth = w - railWidth
            if (backFocused) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = (unit * .003f).coerceAtLeast(2f)
                p.color = 0xFF8BE7FF.toInt()
                c.drawRoundRect(RectF(contentWidth * .025f, h * .035f, contentWidth * .30f, h * .105f),
                    h * .014f, h * .014f, p)
                p.style = Paint.Style.FILL
            }
            drawText(c, "‹  返回桌面", contentWidth * .045f, h * .075f, unit * .026f,
                if (backFocused) -1 else pale, backFocused)
            when (page) {
                Page.HOME -> drawHome(c, contentWidth, h, minOf(contentWidth, h))
                Page.SEND -> drawSend(c, contentWidth, h, minOf(contentWidth, h))
                Page.RECEIVE -> drawReceive(c, contentWidth, h, minOf(contentWidth, h))
                Page.DEVICES -> drawDevices(c, contentWidth, h, minOf(contentWidth, h))
                Page.HISTORY -> drawHistory(c, contentWidth, h, minOf(contentWidth, h))
            }
            drawText(c, "小李全联  ·  局域网直连  ·  无广告", contentWidth * .045f, h * .96f, unit * .018f, pale)
            c.restore()
        }

        private fun drawNavigationRail(c: Canvas, railWidth: Float, h: Float, u: Float) {
            p.color = 0xCC042B68.toInt()
            c.drawRect(0f, 0f, railWidth, h, p)
            p.color = 0x557FCBFF
            c.drawRect(railWidth - 2f, 0f, railWidth, h, p)
            drawText(c, "小李", railWidth * .16f, h * .12f, u * .038f, -1, true)
            drawText(c, "全联", railWidth * .16f, h * .16f, u * .024f, pale, true)
            val labels = listOf("首页", "发送", "接收", "设备", "记录")
            val icons = listOf("⌂", "↑", "↓", "⌖", "◷")
            labels.forEachIndexed { index, label ->
                val top = h * (.235f + index * .125f)
                val rect = RectF(railWidth * .08f, top, railWidth * .92f, top + h * .09f)
                val selected = if (navFocused) navIndex == index else page.ordinal == index
                p.color = if (selected) 0xEE087DEB.toInt() else 0x334C9DD8
                c.drawRoundRect(rect, h * .018f, h * .018f, p)
                drawText(c, icons[index], rect.left + railWidth * .12f, rect.centerY() + h * .012f,
                    u * .032f, -1, true)
                drawText(c, label, rect.left + railWidth * .38f, rect.centerY() + h * .009f,
                    u * .021f, -1, selected)
            }
        }

        private fun drawHome(c: Canvas, w: Float, h: Float, u: Float) {
            drawText(c, "小李全联", w * .07f, h * .19f, u * .065f, -1, true)
            drawText(c, "手机、电视与电脑之间快速传输文件", w * .07f, h * .245f, u * .027f, pale)
            val cols = if (w >= h) 4 else 2
            val gap = w * if (cols == 4) .018f else .035f
            val left = w * .07f
            val top = h * .34f
            val totalW = w * .86f
            val cardW = (totalW - gap * (cols - 1)) / cols
            val cardH = if (cols == 4) h * .25f else h * .17f
            val titles = listOf("发送文件", "接收文件", "附近设备", "传输记录")
            val details = listOf(
                "选择文件并发送到设备",
                if (receiveEnabled) "接收服务运行中" else "开启后等待文件",
                "已发现 ${devices.size} 台设备",
                "查看发送与接收记录"
            )
            val icons = listOf("↑", "↓", "⌖", "◷")
            for (i in 0..3) {
                val col = i % cols
                val row = i / cols
                val x = left + col * (cardW + gap)
                val y = top + row * (cardH + h * .025f)
                val rect = RectF(x, y, x + cardW, y + cardH)
                drawCard(c, rect, focus == i)
                drawText(c, icons[i], rect.left + cardW * .12f, rect.top + cardH * .45f, cardH * .28f, -1, true)
                drawText(c, titles[i], rect.left + cardW * .12f, rect.top + cardH * .70f, u * .033f, -1, true)
                drawText(c, details[i], rect.left + cardW * .12f, rect.top + cardH * .87f, u * .019f, pale)
            }
            val notice = RectF(w * .07f, h * .72f, w * .93f, h * .86f)
            p.color = 0xCC052B68.toInt()
            c.drawRoundRect(notice, 20f, 20f, p)
            drawText(c, if (devices.isEmpty()) "正在搜索局域网设备…" else "附近设备：${devices.take(4).joinToString("、") { it.name }}",
                notice.left + w * .025f, notice.top + notice.height() * .43f, u * .024f, -1, true)
            drawText(c, status, notice.left + w * .025f, notice.top + notice.height() * .76f, u * .019f, pale)
        }

        private fun drawSend(c: Canvas, w: Float, h: Float, u: Float) {
            drawText(c, "发送文件", w * .07f, h * .16f, u * .052f, -1, true)
            val pick = RectF(w * .07f, h * .205f, w * .30f, h * .305f)
            drawButton(c, pick, "＋  添加文件", focus == 0)
            drawText(c, "已选 ${selectedUris.size} 个文件", w * .34f, h * .245f, u * .023f, -1, true)
            drawText(c, "总大小  ${sizeText(selectedSizes.sum())}", w * .34f, h * .285f, u * .019f, pale)
            if (transferRunning) {
                val cancel = RectF(w * .72f, h * .205f, w * .93f, h * .305f)
                drawButton(c, cancel, "取消传输", focus == 1)
            }
            val filePanel = RectF(w * .07f, h * .335f, w * .93f, h * .475f)
            p.color = 0x663A9FEA
            c.drawRoundRect(filePanel, 20f, 20f, p)
            if (selectedNames.isEmpty()) {
                drawText(c, "添加文件后将在这里显示文件清单", filePanel.left + w * .025f,
                    filePanel.centerY() + u * .008f, u * .022f, pale)
            } else {
                for (i in 0 until minOf(2, selectedNames.size)) {
                    val lineY = filePanel.top + h * (.055f + i * .058f)
                    drawText(c, "文件 ${i + 1}", filePanel.left + w * .025f, lineY, u * .017f, 0xFF9DDAFF.toInt(), true)
                    drawText(c, selectedNames[i].take(46), filePanel.left + w * .10f, lineY, u * .020f, -1)
                    drawText(c, sizeText(selectedSizes[i]), w * .89f, lineY, u * .018f, pale)
                }
                if (selectedNames.size > 2) drawText(c, "还有 ${selectedNames.size - 2} 个文件…",
                    filePanel.left + w * .025f, filePanel.bottom - h * .012f, u * .016f, pale)
            }
            drawText(c, "选择目标设备  ·  ${devices.size} 台在线设备", w * .07f, h * .535f, u * .028f, -1, true)
            if (devices.isEmpty()) {
                val empty = RectF(w * .07f, h * .58f, w * .93f, h * .78f)
                drawCard(c, empty, false)
                drawText(c, "正在搜索附近设备，请确认双方处于同一 Wi-Fi 或热点网络",
                    empty.left + w * .025f, empty.centerY() + u * .008f, u * .021f, pale)
            } else {
                val firstDevice = sendDeviceScrollRow * 2
                for (i in firstDevice until minOf(devices.size, firstDevice + 6)) {
                    val device = devices[i]
                    val col = i % 2
                    val rowIndex = i / 2 - sendDeviceScrollRow
                    val left = if (col == 0) w * .07f else w * .515f
                    val row = RectF(left, h * (.575f + rowIndex * .112f),
                        left + w * .415f, h * (.675f + rowIndex * .112f))
                    drawCard(c, row, focus == i + if (transferRunning) 2 else 1)
                    drawText(c, device.name.take(20), row.left + w * .018f,
                        row.top + row.height() * .43f, u * .022f, -1, true)
                    drawText(c, "${device.host}  ·  选择发送 ›", row.left + w * .018f,
                        row.top + row.height() * .76f, u * .016f, pale)
                }
                if (devices.size > 6) {
                    drawText(c, "设备列表  " + (sendDeviceScrollRow + 1) + "/" + (((devices.size + 1) / 2 - 2).coerceAtLeast(1)),
                        w * .75f, h * .90f, u * .016f, pale)
                }
            }
            drawText(c, status, w * .07f, h * .94f, u * .018f, pale)
        }

        private fun drawReceive(c: Canvas, w: Float, h: Float, u: Float) {
            drawText(c, "接收文件", w * .07f, h * .18f, u * .055f, -1, true)
            drawText(c, "接收服务会在后台保持运行，离开此页面不会主动中断传输。",
                w * .07f, h * .25f, u * .022f, pale)
            val action = RectF(w * .07f, h * .34f, w * .48f, h * .46f)
            drawButton(c, action, if (receiveEnabled) "停止接收" else "开启接收", focus == 0)
            val panel = RectF(w * .07f, h * .53f, w * .93f, h * .78f)
            p.color = 0xCC052B68.toInt()
            c.drawRoundRect(panel, 22f, 22f, p)
            drawText(c, if (receiveEnabled) "接收服务已开启" else "接收服务尚未开启",
                panel.left + w * .03f, panel.top + panel.height() * .32f, u * .03f, -1, true)
            drawText(c, status, panel.left + w * .03f, panel.top + panel.height() * .58f, u * .022f, pale)
            drawText(c, "收到请求时会显示发送设备、文件数量和总大小，由你决定接收或拒绝。",
                panel.left + w * .03f, panel.top + panel.height() * .82f, u * .019f, pale)
        }

        private fun drawDevices(c: Canvas, w: Float, h: Float, u: Float) {
            drawText(c, "附近设备", w * .07f, h * .18f, u * .055f, -1, true)
            drawText(c, "已发现 ${devices.size} 台设备 · 选择设备后可进入发送页面",
                w * .07f, h * .25f, u * .022f, pale)
            if (devices.isEmpty()) {
                drawText(c, "正在搜索。请确认设备处于同一 Wi-Fi 或热点网络。",
                    w * .09f, h * .42f, u * .025f, pale)
            }
            devices.drop(deviceScrollOffset).take(9).forEachIndexed { visibleIndex, device ->
                val absoluteIndex = deviceScrollOffset + visibleIndex
                val rowH = h * .065f
                val row = RectF(w * .07f, h * (.31f + visibleIndex * .071f), w * .93f, h * (.31f + visibleIndex * .071f) + rowH)
                drawCard(c, row, focus == absoluteIndex)
                drawText(c, "${device.name}   ·   ${device.host}:${device.port}",
                    row.left + w * .02f, row.centerY() + u * .008f, u * .021f, -1, true)
            }
            if (devices.size > 9) {
                drawText(c, "${deviceScrollOffset + 1}–${minOf(deviceScrollOffset + 9, devices.size)} / ${devices.size}",
                    w * .82f, h * .94f, u * .018f, pale)
            }
        }

        private fun drawHistory(c: Canvas,w: Float,h: Float,u: Float){
            drawText(c,"传输记录",w*.07f,h*.18f,u*.055f,-1,true)
            val items=readHistoryItems()
            val end=minOf(items.length(),historyScrollOffset+8)
            if(items.length()==0)drawText(c,"暂时没有传输记录",w*.09f,h*.40f,u*.026f,pale)
            else for(i in historyScrollOffset until end){
                val item=items.optJSONObject(i)?:continue
                val row=RectF(w*.07f,h*(.27f+(i-historyScrollOffset)*.067f),w*.93f,h*(.325f+(i-historyScrollOffset)*.067f))
                drawCard(c,row,focus==i)
                drawText(c,item.optString("direction","传输")+"  ·  "+item.optString("name","文件").take(32)+"  ·  "+item.optString("peer","附近设备"),
                    row.left+w*.02f,row.centerY()+u*.008f,u*.019f,-1)
            }
            val openDir=RectF(w*.07f,h*.82f,w*.39f,h*.91f)
            val clear=RectF(w*.43f,h*.82f,w*.75f,h*.91f)
            drawButton(c,openDir,"打开目录",focus==items.length())
            drawButton(c,clear,"清除历史",focus==items.length()+1)
            drawText(c,"方向键选择 · 确认执行",w*.77f,h*.875f,u*.016f,pale)
        }

        private fun drawCard(c: Canvas, rect: RectF, selected: Boolean) {
            p.style = Paint.Style.FILL
            p.color = if (selected) 0xEE087DEB.toInt() else 0x553A9FEA
            if (selected) p.setShadowLayer(18f, 0f, 0f, 0xAA38C5FF.toInt())
            c.drawRoundRect(rect, 22f, 22f, p)
            p.clearShadowLayer()
            if (!selected) {
                p.color = 0x226FCBFF
                c.drawRoundRect(rect, 22f, 22f, p)
            }
        }

        private fun drawButton(c: Canvas, rect: RectF, label: String, selected: Boolean) {
            drawCard(c, rect, selected)
            drawText(c, label, rect.left + rect.width() * .08f,
                rect.centerY() + rect.height() * .10f, minOf(rect.height() * .30f, minOf(width, height) * .026f), -1, true)
        }

        private fun drawText(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false) {
            p.color = color
            p.textSize = size.coerceAtLeast(12f)
            p.typeface = if (bold) android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.BOLD)
                else android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
            p.textAlign = Paint.Align.LEFT
            c.drawText(value, x, y, p)
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked != MotionEvent.ACTION_UP) return true
            performClick()
            var x = event.x
            val y = event.y
            val railWidth = width * .17f
            if (x < railWidth) {
                val selected = (0..4).firstOrNull { index ->
                    val top = height * (.235f + index * .125f)
                    y >= top && y <= top + height * .09f
                }
                if (selected != null) openPage(Page.values()[selected])
                return true
            }
            x -= railWidth
            val contentWidth = width.toFloat() - railWidth
            if (y < height * .12f && x < contentWidth * .28f) {
                backFocused = false
                goBack()
                return true
            }
            when (page) {
                Page.HOME -> {
                    val cols = if (contentWidth >= height) 4 else 2
                    val gap = contentWidth * if (cols == 4) .018f else .035f
                    val cardW = (contentWidth * .86f - gap * (cols - 1)) / cols
                    val cardH = if (cols == 4) height * .25f else height * .17f
                    for (i in 0..3) {
                        val col = i % cols
                        val row = i / cols
                        val rect = RectF(contentWidth * .07f + col * (cardW + gap),
                            height * .34f + row * (cardH + height * .025f),
                            contentWidth * .07f + col * (cardW + gap) + cardW,
                            height * .34f + row * (cardH + height * .025f) + cardH)
                        if (rect.contains(x, y)) {
                            focus = i
                            activateFocused()
                            return true
                        }
                    }
                }
                Page.SEND -> {
                    val pick = RectF(contentWidth * .07f, height * .205f, contentWidth * .30f, height * .305f)
                    if (pick.contains(x, y)) {
                        focus = 0
                        openFilePicker()
                        return true
                    }
                    if (transferRunning) {
                        val cancel = RectF(contentWidth * .72f, height * .205f, contentWidth * .93f, height * .305f)
                        if (cancel.contains(x, y)) {
                            focus = 1
                            TransferForegroundService.cancelTransfer(this@QuanLianActivity)
                            status = "已请求取消传输…"
                            screen.invalidate()
                            return true
                        }
                    }
                    val firstDevice = sendDeviceScrollRow * 2
                    for (i in firstDevice until minOf(devices.size, firstDevice + 6)) {
                        val device = devices[i]
                        val col = i % 2
                        val rowIndex = i / 2 - sendDeviceScrollRow
                        val left = if (col == 0) contentWidth * .07f else contentWidth * .515f
                        val row = RectF(left, height * (.575f + rowIndex * .112f),
                            left + contentWidth * .415f, height * (.675f + rowIndex * .112f))
                        if (row.contains(x, y)) {
                            focus = i + if (transferRunning) 2 else 1
                            requestSend(device)
                            return true
                        }
                    }
                }
                Page.RECEIVE -> {
                    val action = RectF(contentWidth * .07f, height * .34f, contentWidth * .48f, height * .46f)
                    if (action.contains(x, y)) {
                        focus = 0
                        activateFocused()
                        return true
                    }
                }
                Page.DEVICES -> devices.drop(deviceScrollOffset).take(9).forEachIndexed { visibleIndex, device ->
                    val absoluteIndex = deviceScrollOffset + visibleIndex
                    val row = RectF(contentWidth * .07f, height * (.31f + visibleIndex * .071f),
                        contentWidth * .93f, height * (.31f + visibleIndex * .071f) + height * .065f)
                    if (row.contains(x, y)) {
                        focus = absoluteIndex
                        page = Page.SEND
                        screen.invalidate()
                        requestSend(device)
                        return true
                    }
                }
                Page.HISTORY -> {
                    val items=readHistoryItems()
                    for(i in historyScrollOffset until minOf(items.length(),historyScrollOffset+8)){
                        val visibleIndex=i-historyScrollOffset
                        val row=RectF(contentWidth*.07f,height*(.27f+visibleIndex*.067f),contentWidth*.93f,height*(.325f+visibleIndex*.067f))
                        if(row.contains(x,y)){focus=i;activateFocused();return true}
                    }
                    val openDir=RectF(contentWidth*.07f,height*.82f,contentWidth*.39f,height*.91f)
                    val clear=RectF(contentWidth*.43f,height*.82f,contentWidth*.75f,height*.91f)
                    if(openDir.contains(x,y)){focus=items.length();activateFocused();return true}
                    if(clear.contains(x,y)){focus=items.length()+1;activateFocused();return true}
                }
            }
            return true
        }
    }

    private fun sizeText(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format(Locale.getDefault(), "%.2f GB", bytes / (1024.0 * 1024 * 1024))
    }

    companion object {
        private const val REQUEST_PICK_FILES = 401
        private const val REQUEST_NOTIFICATIONS = 402
    }
}
