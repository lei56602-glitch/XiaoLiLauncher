package com.xiaoli.launcher

import android.content.Intent
import android.content.pm.ResolveInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class TvApp(val label: String, val packageName: String, val resolveInfo: ResolveInfo)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        setContent { MaterialTheme { XiaoLiHome() } }
    }
}

@Composable
private fun XiaoLiHome() {
    val context = LocalContext.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    var drawerOpen by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf(loadTvApps(context.packageManager)) }

    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay(30_000)
        }
    }
    BackHandler(enabled = drawerOpen) { drawerOpen = false }

    val time = now.format(DateTimeFormatter.ofPattern("HH:mm"))
    val date = now.format(DateTimeFormatter.ofPattern("M月d日  EEEE", Locale.CHINA))
    val favorites = apps.take(5)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF073B88), Color(0xFF1269C5), Color(0xFF052B68))
                )
            )
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                    drawerOpen = true
                    true
                } else false
            }
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 42.dp, vertical = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("◢", color = Color(0xFF56D6FF), fontSize = 36.sp, fontWeight = FontWeight.Black)
                Spacer(Modifier.width(12.dp))
                Text("小李桌面", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Text(time, color = Color.White, fontSize = 43.sp, fontWeight = FontWeight.Light)
                Spacer(Modifier.width(18.dp))
                Column {
                    Text(date, color = Color.White, fontSize = 17.sp)
                    Text("欢迎回家", color = Color(0xFFD5E9FF), fontSize = 13.sp)
                }
                Spacer(Modifier.width(22.dp))
                Text("天气待配置", color = Color.White.copy(alpha = .9f), fontSize = 15.sp)
            }

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                favorites.forEach { app ->
                    AppTile(app = app, onClick = { launchApp(context, app) })
                    Spacer(Modifier.width(28.dp))
                }
            }

            Spacer(Modifier.height(42.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⌃  按上键打开应用抽屉", color = Color.White.copy(alpha = .88f), fontSize = 14.sp)
                Spacer(Modifier.weight(1f))
                Text("网络状态由系统提供", color = Color.White.copy(alpha = .7f), fontSize = 12.sp)
            }
        }

        if (drawerOpen) {
            Box(
                Modifier.fillMaxSize()
                    .background(Color(0xE8071D42))
                    .padding(horizontal = 54.dp, vertical = 38.dp)
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("应用", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text("按返回键关闭", color = Color.White.copy(alpha = .7f), fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(24.dp))
                    if (apps.isEmpty()) {
                        Text("暂未发现可启动的电视应用", color = Color.White, fontSize = 20.sp)
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(6),
                            verticalArrangement = Arrangement.spacedBy(24.dp),
                            horizontalArrangement = Arrangement.spacedBy(18.dp)
                        ) {
                            items(apps, key = { it.packageName }) { app ->
                                AppTile(app = app, onClick = {
                                    launchApp(context, app)
                                    drawerOpen = false
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppTile(app: TvApp, onClick: () -> Unit) {
    val context = LocalContext.current
    var focused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .width(126.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(22.dp))
            .background(if (focused) Color.White.copy(alpha = .22f) else Color.Transparent)
            .border(
                width = if (focused) 2.dp else 0.dp,
                color = if (focused) Color.White else Color.Transparent,
                shape = RoundedCornerShape(22.dp)
            )
            .focusable()
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier.size(76.dp),
            shape = RoundedCornerShape(20.dp),
            color = Color.White.copy(alpha = .96f),
            shadowElevation = if (focused) 12.dp else 3.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                val icon = remember(app.packageName) {
                    try { context.packageManager.getApplicationIcon(app.packageName) } catch (_: Exception) { null }
                }
                if (icon != null) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.graphics.painter.BitmapPainter(
                            icon.toBitmap().asImageBitmap()
                        ),
                        contentDescription = app.label,
                        modifier = Modifier.fillMaxSize().padding(12.dp)
                    )
                } else {
                    Text(app.label.take(1), color = Color(0xFF1269C5), fontSize = 30.sp)
                }
            }
        }
        Spacer(Modifier.height(9.dp))
        Text(app.label, color = Color.White, fontSize = 14.sp, maxLines = 1)
    }
}

private fun loadTvApps(packageManager: android.content.pm.PackageManager): List<TvApp> {
    val leanback = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val results = (packageManager.queryIntentActivities(leanback, 0) +
        packageManager.queryIntentActivities(launcher, 0))
        .distinctBy { it.activityInfo.packageName }
        .filter { it.activityInfo.packageName != "com.xiaoli.launcher" }
        .map {
            TvApp(
                label = it.loadLabel(packageManager).toString(),
                packageName = it.activityInfo.packageName,
                resolveInfo = it
            )
        }
    return results.sortedBy { it.label.lowercase(Locale.getDefault()) }
}

private fun launchApp(context: android.content.Context, app: TvApp) {
    val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
        ?: Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            setPackage(app.packageName)
        }
    try { context.startActivity(intent) } catch (_: Exception) { }
}

private fun android.graphics.drawable.Drawable.toBitmap(): android.graphics.Bitmap {
    val bitmap = android.graphics.Bitmap.createBitmap(
        intrinsicWidth.coerceAtLeast(1), intrinsicHeight.coerceAtLeast(1),
        android.graphics.Bitmap.Config.ARGB_8888
    )
    val canvas = android.graphics.Canvas(bitmap)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bitmap
}
