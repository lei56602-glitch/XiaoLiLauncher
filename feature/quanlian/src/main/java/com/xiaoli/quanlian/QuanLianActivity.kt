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
    private var devices: List<DeviceInfo> = emptyList()
    private val selectedUris = arrayListOf<Uri>()
    private val selectedNames = arrayListOf<String>()
    private val selectedSizes = arrayListOf<Long>()
    private var status = "正在搜索附近设备…"
    private var receiveEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receiveEnabled = getSharedPreferences("quanlian_runtime", MODE_PRIVATE)
            .getBoolean("receive_enabled", false)
        screen = QuanLianScreen()
        setContentView(screen)
    }

    override fun onStart() {
        super.onStart()
        discovery.start()
        discoveryJob = screenScope.launch {
            discovery.devices.collect {
                devices = it
                screen.invalidate()
            }
        }
        registerTransferEvents()
    }

    override fun onStop() {
        discoveryJob?.cancel()
        discoveryJob = null
        discovery.stop()
        eventReceiver?.let { runCatching { unregisterReceiver(it) } }
        eventReceiver = null
        super.onStop()
    }

    override fun onDestroy() {
        screenScope.coroutineContext[SupervisorJob]?.cancel()
        super.onDestroy()
    }

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
                        if (!isFinishing) dialog.show()
                    }
                    TransferForegroundService.ACTION_PROGRESS -> {
                        status = intent.getStringExtra(TransferForegroundService.EXTRA_TEXT)
                            ?: "正在传输文件…"
                        screen.invalidate()
                    }
                    TransferForegroundService.ACTION_RESULT -> {
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
        receiveEnabled = true
        getSharedPreferences("quanlian_runtime", MODE_PRIVATE).edit().putBoolean("receive_enabled", true).apply()
        runCatching { ReceiveForegroundService.start(this) }
            .onSuccess { status = "正在启动接收服务…" }
            .onFailure { status = "接收服务启动失败：${it.message}" }
        screen.invalidate()
    }

    private fun stopReceive() {
        receiveEnabled = false
        getSharedPreferences("quanlian_runtime", MODE_PRIVATE).edit().putBoolean("receive_enabled", false).apply()
        stopService(Intent(this, ReceiveForegroundService::class.java))
        status = "接收服务已停止"
        screen.invalidate()
    }

    private fun requestSend(device: DeviceInfo) {
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
            status = "无法启动传输服务：${it.message}"
            screen.invalidate()
        }
    }

    private fun openPage(target: Page) {
        page = target
        focus = 0
        if (target == Page.RECEIVE && !receiveEnabled) startReceive()
        screen.invalidate()
    }

    private fun goBack() {
        when (page) {
            Page.HOME -> finish()
            Page.SEND, Page.RECEIVE, Page.DEVICES, Page.HISTORY -> {
                page = Page.HOME
                focus = 0
                screen.invalidate()
            }
        }
    }

    private fun activateFocused() {
        when (page) {
            Page.HOME -> when (focus.coerceIn(0, 3)) {
                0 -> openPage(Page.SEND)
                1 -> openPage(Page.RECEIVE)
                2 -> openPage(Page.DEVICES)
                3 -> openPage(Page.HISTORY)
            }
            Page.SEND -> if (focus == 0) openFilePicker() else devices.getOrNull(focus - 1)?.let(::requestSend)
            Page.RECEIVE -> if (focus == 0) {
                if (receiveEnabled) stopReceive() else startReceive()
            }
            Page.DEVICES -> devices.getOrNull(focus)?.let { device ->
                page = Page.SEND
                screen.invalidate()
                requestSend(device)
            }
            Page.HISTORY -> if (focus == 0) {
                getSharedPreferences("transfer_history", MODE_PRIVATE).edit().putString("items", "[]").apply()
                status = "传输记录已清空"
                screen.invalidate()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            goBack()
            return true
        }
        val max = when (page) {
            Page.HOME -> 3
            Page.SEND -> devices.size
            Page.DEVICES -> (devices.size - 1).coerceAtLeast(0)
            Page.RECEIVE, Page.HISTORY -> 0
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> focus = (focus - 1).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> focus = (focus + 1).coerceAtMost(max)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> activateFocused()
            else -> return super.onKeyDown(keyCode, event)
        }
        screen.invalidate()
        return true
    }

    private inner class QuanLianScreen : View(this@QuanLianActivity) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val blue = 0xFF0878E8.toInt()
        private val pale = 0xFFDDEEFF.toInt()

        init {
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            val w = width.toFloat()
            val h = height.toFloat()
            val unit = minOf(w, h)
            p.shader = LinearGradient(0f, 0f, w, h,
                intArrayOf(0xFF0878E8.toInt(), 0xFF0754B9.toInt(), 0xFF032D79.toInt()),
                null, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, p)
            p.shader = null
            drawText(c, "‹  返回桌面", w * .045f, h * .075f, unit * .026f, pale)
            when (page) {
                Page.HOME -> drawHome(c, w, h, unit)
                Page.SEND -> drawSend(c, w, h, unit)
                Page.RECEIVE -> drawReceive(c, w, h, unit)
                Page.DEVICES -> drawDevices(c, w, h, unit)
                Page.HISTORY -> drawHistory(c, w, h, unit)
            }
            drawText(c, "小李全联  ·  局域网直连  ·  无广告", w * .045f, h * .96f, unit * .018f, pale)
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
            drawText(c, "发送文件", w * .07f, h * .18f, u * .055f, -1, true)
            val pick = RectF(w * .07f, h * .23f, w * .42f, h * .32f)
            drawButton(c, pick, "＋  添加文件", focus == 0)
            drawText(c, "已选择 ${selectedUris.size} 个文件 · ${sizeText(selectedSizes.sum())}",
                w * .46f, h * .285f, u * .022f, pale)
            var y = h * .37f
            for (i in 0 until minOf(4, selectedNames.size)) {
                drawText(c, "• ${selectedNames[i].take(42)}  ·  ${sizeText(selectedSizes[i])}",
                    w * .09f, y, u * .021f, -1)
                y += h * .045f
            }
            drawText(c, "选择目标设备", w * .07f, h * .59f, u * .028f, -1, true)
            if (devices.isEmpty()) {
                drawText(c, "暂未发现设备。请让双方打开小李全联并连接同一局域网。",
                    w * .09f, h * .67f, u * .021f, pale)
            } else {
                val visible = devices.take(5)
                visible.forEachIndexed { i, device ->
                    val row = RectF(w * .07f, h * (.62f + i * .058f), w * .93f, h * (.67f + i * .058f))
                    drawCard(c, row, focus == i + 1)
                    drawText(c, "${device.name}   ·   ${device.host}   ·   发送 ›",
                        row.left + w * .02f, row.centerY() + u * .008f, u * .021f, -1, true)
                }
            }
            drawText(c, status, w * .07f, h * .93f, u * .019f, pale)
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
            devices.take(9).forEachIndexed { i, device ->
                val rowH = h * .065f
                val row = RectF(w * .07f, h * (.31f + i * .071f), w * .93f, h * (.31f + i * .071f) + rowH)
                drawCard(c, row, focus == i)
                drawText(c, "${device.name}   ·   ${device.host}:${device.port}",
                    row.left + w * .02f, row.centerY() + u * .008f, u * .021f, -1, true)
            }
        }

        private fun drawHistory(c: Canvas, w: Float, h: Float, u: Float) {
            drawText(c, "传输记录", w * .07f, h * .18f, u * .055f, -1, true)
            val items = runCatching {
                JSONArray(getSharedPreferences("transfer_history", MODE_PRIVATE).getString("items", "[]"))
            }.getOrDefault(JSONArray())
            if (items.length() == 0) {
                drawText(c, "暂时没有传输记录", w * .09f, h * .40f, u * .026f, pale)
            } else {
                for (i in 0 until minOf(8, items.length())) {
                    val item = items.optJSONObject(i) ?: continue
                    val row = RectF(w * .07f, h * (.27f + i * .075f), w * .93f, h * (.33f + i * .075f))
                    drawCard(c, row, false)
                    val direction = item.optString("direction", "传输")
                    val name = item.optString("name", "文件")
                    val peer = item.optString("peer", "附近设备")
                    drawText(c, "$direction  ·  ${name.take(32)}  ·  $peer",
                        row.left + w * .02f, row.centerY() + u * .008f, u * .019f, -1)
                }
            }
            val clear = RectF(w * .07f, h * .84f, w * .35f, h * .92f)
            drawButton(c, clear, "清空记录", focus == 0)
        }

        private fun drawCard(c: Canvas, rect: RectF, selected: Boolean) {
            p.color = if (selected) 0xFF168CE8.toInt() else 0x553A9FEA
            c.drawRoundRect(rect, 20f, 20f, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = if (selected) 4f else 1.5f
            p.color = if (selected) 0xFF9CE5FF.toInt() else 0x667CCBFF
            c.drawRoundRect(rect, 20f, 20f, p)
            p.style = Paint.Style.FILL
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

        override fun onTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked != MotionEvent.ACTION_UP) return true
            val x = event.x
            val y = event.y
            if (y < height * .12f && x < width * .28f) {
                goBack()
                return true
            }
            when (page) {
                Page.HOME -> {
                    val cols = if (width >= height) 4 else 2
                    val gap = width * if (cols == 4) .018f else .035f
                    val cardW = (width * .86f - gap * (cols - 1)) / cols
                    val cardH = if (cols == 4) height * .25f else height * .17f
                    for (i in 0..3) {
                        val col = i % cols
                        val row = i / cols
                        val rect = RectF(width * .07f + col * (cardW + gap),
                            height * .34f + row * (cardH + height * .025f),
                            width * .07f + col * (cardW + gap) + cardW,
                            height * .34f + row * (cardH + height * .025f) + cardH)
                        if (rect.contains(x, y)) {
                            focus = i
                            activateFocused()
                            return true
                        }
                    }
                }
                Page.SEND -> {
                    val pick = RectF(width * .07f, height * .23f, width * .42f, height * .32f)
                    if (pick.contains(x, y)) {
                        focus = 0
                        openFilePicker()
                        return true
                    }
                    devices.take(5).forEachIndexed { i, device ->
                        val row = RectF(width * .07f, height * (.62f + i * .058f),
                            width * .93f, height * (.67f + i * .058f))
                        if (row.contains(x, y)) {
                            focus = i + 1
                            requestSend(device)
                            return true
                        }
                    }
                }
                Page.RECEIVE -> {
                    val action = RectF(width * .07f, height * .34f, width * .48f, height * .46f)
                    if (action.contains(x, y)) {
                        focus = 0
                        activateFocused()
                        return true
                    }
                }
                Page.DEVICES -> devices.take(9).forEachIndexed { i, device ->
                    val row = RectF(width * .07f, height * (.31f + i * .071f),
                        width * .93f, height * (.31f + i * .071f) + height * .065f)
                    if (row.contains(x, y)) {
                        focus = i
                        page = Page.SEND
                        screen.invalidate()
                        requestSend(device)
                        return true
                    }
                }
                Page.HISTORY -> {
                    val clear = RectF(width * .07f, height * .84f, width * .35f, height * .92f)
                    if (clear.contains(x, y)) {
                        focus = 0
                        activateFocused()
                        return true
                    }
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
    }
}
